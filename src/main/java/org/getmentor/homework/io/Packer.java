package org.getmentor.homework.io;

import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.CRC32;


public class Packer {

    private Packer() {
    }

    public static void pack(Path dir, Path archive) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new ArchiveException(dir.toString(), "входная папка", "не является папкой");
        }
        Path absDir = dir.toAbsolutePath().normalize();
        Path absArchive = archive.toAbsolutePath().normalize();
        Path parent = absArchive.getParent();
        // Архив внутри упаковываемой папки попал бы сам в себя (через temp-файл)
        if (parent != null && parent.equals(absDir)) {
            throw new ArchiveException(archive.toString(), "расположение архива",
                    "архив не должен лежать внутри упаковываемой папки");
        }

        List<Entry> entries = collect(dir);

        Path tmp = Files.createTempFile(parent, ".sarc-", ".tmp");
        try {
            try (OutputStream raw = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 8 * 1024))) {
                out.write(Format.MAGIC);
                out.writeByte(Format.VERSION);
                for (Entry e : entries) writeEntry(out, e);
                out.writeByte(Format.END_MARKER);
            }
            try {
                Files.move(tmp, absArchive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, absArchive, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }

    private static void writeEntry(DataOutputStream out, Entry e) throws IOException {
        long size = Files.size(e.path());
        CRC32 crc = new CRC32();
        Utf8Validator utf8 = e.text() ? new Utf8Validator() : null;

        out.writeByte(e.text() ? Format.TYPE_TEXT : Format.TYPE_BINARY);
        out.writeShort(e.nameBytes().length);
        out.write(e.nameBytes());
        out.writeLong(size);

        try (InputStream in = Files.newInputStream(e.path())) {
            StreamIo.copyExactly(in, out, size, crc, utf8, e.name());
            if (in.read() != -1) { // TOCTOU: файл вырос между size() и чтением
                throw new ArchiveException(e.name(), "стабильность размера",
                        "файл изменился во время упаковки (стал больше " + size + " байт)");
            }
            if (utf8 != null) utf8.finish();
        } catch (CharacterCodingException ex) {
            throw new ArchiveException(e.name(), "корректность UTF-8",
                    "недопустимая последовательность байт в .txt", ex);
        }
        out.writeInt((int) crc.getValue()); // CRC32 = 32 бита, big-endian
    }

    private record Entry(String name, byte[] nameBytes, Path path, boolean text) {
    }

    private static List<Entry> collect(Path dir) throws IOException {
        List<Entry> result = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ArchiveException(name, "тип записи",
                            "поддерживаются только обычные файлы (подпапки и ссылки не поддерживаются)");
                }
                byte[] nb = name.getBytes(StandardCharsets.UTF_8);
                if (nb.length > Format.MAX_NAME_BYTES) {
                    throw new ArchiveException(name, "длина имени",
                            nb.length + " байт > " + Format.MAX_NAME_BYTES);
                }
                boolean text = name.toLowerCase(Locale.ROOT).endsWith(".txt");
                result.add(new Entry(name, nb, p, text));
            }
        }
        result.sort((a, b) -> Arrays.compareUnsigned(a.nameBytes(), b.nameBytes()));
        return result;
    }
}
