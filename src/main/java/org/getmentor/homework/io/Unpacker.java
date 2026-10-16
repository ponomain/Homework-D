package org.getmentor.homework.io;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.CRC32;

public class Unpacker {
    private static final String ARCHIVE = "архив";

    private Unpacker() {
    }

    /**
     * Открывает и закрывает поток сам.
     */
    public static void unpack(Path archive, Path target) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(archive), 8 * 1024)) {
            unpack(in, target);
        }
    }

    /**
     * Поток НЕ закрывается: им владеет вызывающий (удобно для тестов с trickle-стримом).
     */
    public static void unpack(InputStream in, Path target) throws IOException {
        Files.createDirectories(target);
        Path absTarget = target.toAbsolutePath().normalize();
        Path staging = Files.createTempDirectory(absTarget, ".sarc-unpack-");
        try {
            List<String> names = new ArrayList<>();
            readArchive(in, staging, names); // всё проверено, файлы лежат в staging
            commit(staging, absTarget, names); // только теперь появляются конечные имена
        } finally {
            deleteQuietly(staging);
        }
    }

    // ---------- чтение и валидация ----------
    private static void readArchive(InputStream in, Path staging, List<String> names) throws IOException {
        byte[] header = readN(in, 5, ARCHIVE, "заголовок");
        if (!Arrays.equals(header, 0, 4, Format.MAGIC, 0, 4)) {
            throw new ArchiveException(ARCHIVE, "сигнатура", "ожидалось SARC");
        }
        int version = header[4] & 0xFF;
        if (version != Format.VERSION) {
            throw new ArchiveException(ARCHIVE, "версия", "неизвестная версия " + version);
        }

        Set<String> seen = new HashSet<>();
        while (true) {
            int type = in.read();
            if (type < 0) {
                throw new ArchiveException(ARCHIVE, "завершающий маркер",
                        "архив оборван: маркер конца не найден");
            }
            if (type == Format.END_MARKER) break;
            if (type != Format.TYPE_TEXT && type != Format.TYPE_BINARY) {
                throw new ArchiveException("запись №" + (names.size() + 1), "тип файла",
                        "неизвестный тип " + type);
            }
            readEntry(in, type, staging, seen, names);
        }
        if (in.read() != -1) {
            throw new ArchiveException(ARCHIVE, "хвост после маркера",
                    "обнаружены байты после завершающего маркера");
        }
    }

    private static void readEntry(InputStream in, int type, Path staging, Set<String> seen, List<String> names) throws IOException {
        String label = "запись №" + (names.size() + 1);

        int nameLen = ByteBuffer.wrap(readN(in, 2, label, "длина имени")).getShort() & 0xFFFF;
        if (nameLen == 0) throw new ArchiveException(label, "имя файла", "пустое имя");
        String name = decodeName(readN(in, nameLen, label, "имя файла"), label);
        validateName(name);
        if (!seen.add(name)) {
            throw new ArchiveException(name, "уникальность имени", "повторное имя в архиве");
        }

        long length = ByteBuffer.wrap(readN(in, 8, name, "длина содержимого")).getLong();
        if (length < 0) {
            throw new ArchiveException(name, "длина содержимого", "отрицательная длина " + length);
        }

        Path out = safeResolve(staging, name);
        CRC32 crc = new CRC32();
        Utf8Validator utf8 = type == Format.TYPE_TEXT ? new Utf8Validator() : null;

        try (OutputStream os = openStaged(out, name)) {
            StreamIo.copyExactly(in, os, length, crc, utf8, name);
            if (utf8 != null) utf8.finish();
        } catch (CharacterCodingException ex) {
            throw new ArchiveException(name, "корректность UTF-8",
                    "недопустимая последовательность байт в .txt", ex);
        }

        int expected = ByteBuffer.wrap(readN(in, 4, name, "CRC32")).getInt();
        int actual = (int) crc.getValue();
        if (expected != actual) {
            throw new ArchiveException(name, "CRC32",
                    String.format("ожидалось %08x, получено %08x", expected, actual));
        }
        names.add(name);
    }

    /**
     * Создаёт файл записи в staging. Ловится только сбой создания/открытия файла, а не чтения архива:
     * ошибки входного потока в этот метод не попадают.
     */
    private static OutputStream openStaged(Path out, String name) throws IOException {
        try {
            return Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ex) {
            // Регистронезависимая ФС: A.bin и a.bin прошли HashSet, но конфликтуют на диске.
            // Должен стоять раньше FileSystemException: это его подкласс.
            throw new ArchiveException(name, "уникальность имени",
                    "имя конфликтует с уже распакованным файлом на файловой системе "
                            + "(например, имена различаются только регистром)", ex);
        } catch (IOException ex) {
            // Например, имя длиннее 255 байт: допустимо для формата архива, но не для ФС.
            throw new ArchiveException(name, "имя файла допустимо в файловой системе",
                    "не удалось создать файл (" + ex.getMessage() + ")", ex);
        }
    }

    private static byte[] readN(InputStream in, int n, String file, String what) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length < n) {
            throw new ArchiveException(file, "целостность архива",
                    "обрыв при чтении поля '" + what + "': нужно " + n + ", есть " + b.length);
        }
        return b;
    }

    private static String decodeName(byte[] bytes, String label) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new ArchiveException(label, "корректность UTF-8 в имени", "недопустимые байты", ex);
        }
    }

    private static void validateName(String name) throws ArchiveException {
        if (name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new ArchiveException(name, "безопасность имени",
                    "недопустимое имя (разделитель пути, '.', '..' или NUL)");
        }
    }

    /**
     * Вторая линия обороны: результат обязан быть прямым потомком dir.
     */
    private static Path safeResolve(Path dir, String name) throws ArchiveException {
        Path p;
        try {
            p = dir.resolve(name).normalize();
        } catch (InvalidPathException ex) {
            throw new ArchiveException(name, "безопасность имени", "имя недопустимо для ФС", ex);
        }
        if (!dir.equals(p.getParent())) {
            throw new ArchiveException(name, "безопасность имени", "выход за пределы целевой папки");
        }
        return p;
    }

    // ---------- фиксация результата ----------
    private static void commit(Path staging, Path target, List<String> names) throws IOException {
        for (String n : names) {
            if (Files.exists(safeResolve(target, n), LinkOption.NOFOLLOW_LINKS)) {
                throw new ArchiveException(n, "перезапись",
                        "файл уже существует в целевой папке; ничего не распаковано");
            }
        }
        List<Path> moved = new ArrayList<>();
        try {
            for (String n : names) {
                Path dst = safeResolve(target, n);
                Files.move(staging.resolve(n), dst); // без опций: не перезаписывает существующий
                moved.add(dst);
            }
        } catch (IOException | RuntimeException ex) {
            for (Path p : moved) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException s) {
                    ex.addSuppressed(s);
                }
            }
            throw ex;
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {

        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {

        }
    }
}
