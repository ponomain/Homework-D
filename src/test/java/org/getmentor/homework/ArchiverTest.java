package org.getmentor.homework;

import org.getmentor.homework.io.ArchiveException;
import org.getmentor.homework.io.Main;
import org.getmentor.homework.io.Packer;
import org.getmentor.homework.io.Unpacker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ArchiverTest {
    @TempDir
    Path tmp;
    private int counter;

    // ---------- helpers ----------

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private Path makeDir(Map<String, byte[]> files) throws IOException {
        Path d = Files.createTempDirectory(tmp, "src");
        for (var e : files.entrySet()) Files.write(d.resolve(e.getKey()), e.getValue());
        return d;
    }

    private Path newPath(String prefix) { return tmp.resolve(prefix + counter++); }

    private static Set<String> listNames(Path dir) throws IOException {
        if (!Files.exists(dir)) return Set.of();
        try (Stream<Path> s = Files.list(dir)) {
            Set<String> r = new TreeSet<>();
            s.forEach(p -> r.add(p.getFileName().toString()));
            return r;
        }
    }

    private byte[] pack(Map<String, byte[]> files) throws IOException {
        Path archive = newPath("a") ;
        Packer.pack(makeDir(files), archive);
        return Files.readAllBytes(archive);
    }

    private void assertRoundTrip(Map<String, byte[]> files) throws IOException {
        byte[] archive = pack(files);
        Path out = newPath("out");
        Unpacker.unpack(new TrickleInputStream(archive, 42), out); // заодно trickle
        assertEquals(new TreeSet<>(files.keySet()), listNames(out));
        for (var e : files.entrySet()) {
            assertArrayEquals(e.getValue(), Files.readAllBytes(out.resolve(e.getKey())), e.getKey());
        }
    }

    private record ParsedEntry(int type, String name) {}

    /** Разбирает готовый архив по формату SARC: тип(1) длина имени(2) имя длина(8) данные CRC(4), маркер 0. */
    private static List<ParsedEntry> parseEntries(byte[] archive) {
        ByteBuffer buf = ByteBuffer.wrap(archive);
        buf.position(5); // MAGIC(4) + версия(1)
        List<ParsedEntry> result = new ArrayList<>();
        int type;
        while ((type = buf.get() & 0xFF) != 0) {
            byte[] name = new byte[buf.getShort() & 0xFFFF];
            buf.get(name);
            long length = buf.getLong();
            buf.position(buf.position() + Math.toIntExact(length) + 4);
            result.add(new ParsedEntry(type, new String(name, StandardCharsets.UTF_8)));
        }
        return result;
    }

    private static List<String> entryNames(byte[] archive) {
        return parseEntries(archive).stream().map(ParsedEntry::name).toList();
    }

    private static boolean isStaging(String name) { return name.startsWith(".sarc-unpack-"); }

    /** Распаковка обязана упасть ArchiveException, и в целевой папке не должно остаться ничего. */
    private ArchiveException assertFailsClean(byte[] archive, String messageFragment) throws IOException {
        return assertFailsClean(new ByteArrayInputStream(archive), messageFragment);
    }

    private ArchiveException assertFailsClean(InputStream archive, String messageFragment) throws IOException {
        Path out = newPath("bad");
        ArchiveException ex = assertThrows(ArchiveException.class,
                () -> Unpacker.unpack(archive, out));
        if (messageFragment != null) {
            assertTrue(ex.getMessage().contains(messageFragment),
                    "ожидали '" + messageFragment + "' в: " + ex.getMessage());
        }
        assertEquals(Set.of(), listNames(out), "остались файлы после ошибки");
        return ex;
    }

    // ---------- корректные сценарии ----------

    @Test
    void emptyArchive() throws Exception {
        byte[] archive = pack(Map.of());
        assertArrayEquals(new byte[]{'S', 'A', 'R', 'C', 1, 0}, archive);
        assertRoundTrip(Map.of());
    }

    @Test void emptyFiles() throws Exception {
        assertRoundTrip(Map.of("empty.txt", new byte[0], "empty.bin", new byte[0]));
    }

    @Test void russianTextWithEmoji() throws Exception {
        assertRoundTrip(Map.of("Привет мир.txt", utf8("Привет, мир! 😀🚀 日本語 \uFEFF\n"),
                "файл.txt", utf8("ёжик 🦔")));
    }

    @Test void emojiSplitAcrossBufferBoundaries() throws Exception {
        // 5 байт на единицу, 32768 % 5 != 0 — границы чанков попадают внутрь 4-байтных символов
        String s = "x😀".repeat(50_000);
        assertRoundTrip(Map.of("big.txt", utf8(s)));
    }

    @Test void allByteValuesAndLargeBinary() throws Exception {
        byte[] all = new byte[256 * 4];
        for (int i = 0; i < all.length; i++) all[i] = (byte) i;
        byte[] large = new byte[200_000]; // больше и 32 КиБ, и 64 КиБ
        new Random(1).nextBytes(large);
        assertRoundTrip(Map.of("all.bin", all, "large.dat", large, "noext", all));
    }

    @Test void filesAreSortedByName() throws Exception {
        byte[] archive = pack(Map.of("b.txt", utf8("b"), "a.bin", new byte[]{1}, "я.txt", utf8("я")));
        assertEquals(List.of("a.bin", "b.txt", "я.txt"), entryNames(archive));
    }

    @Test void sortsByUtf8BytesIncludingNonAscii() throws Exception {
        // UTF-8: B(42) < a(61) < U+FFFD(EF BF BD) < эмодзи(F0 9F 98 80).
        // По UTF-16 эмодзи (D83D) пошёл бы раньше U+FFFD — тест ловит сортировку по char.
        byte[] archive = pack(Map.of(
                "😀.bin", new byte[]{1},
                "�.bin", new byte[]{2},
                "a.bin", new byte[]{3},
                "B.bin", new byte[]{4}));
        assertEquals(List.of("B.bin", "a.bin", "�.bin", "😀.bin"), entryNames(archive));
    }

    @Test void acceptsUppercaseTxtAsText() throws Exception {
        Map<String, byte[]> files = Map.of("X.TXT", utf8("текст 😀"));
        byte[] archive = pack(files);
        assertEquals(List.of(new ParsedEntry(1, "X.TXT")), parseEntries(archive), "X.TXT должен быть записан как текст (тип 1)");
        assertRoundTrip(files);
    }

    @Test void acceptsMaxCodePointU10FFFF() throws Exception {
        assertRoundTrip(Map.of("max.txt", new byte[]{(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF}));
    }

    @Test void trickleWithManySeeds() throws Exception {
        byte[] data = new byte[5_000];
        new Random(7).nextBytes(data);
        byte[] archive = pack(Map.of("d.bin", data, "t.txt", utf8("текст 😀")));
        for (long seed = 0; seed < 20; seed++) {
            Path out = newPath("tr");
            Unpacker.unpack(new TrickleInputStream(archive, seed), out);
            assertArrayEquals(data, Files.readAllBytes(out.resolve("d.bin")));
            assertArrayEquals(utf8("текст 😀"), Files.readAllBytes(out.resolve("t.txt")));
        }
    }

    // ---------- pack: ошибки ----------

    @Test void packRejectsInvalidUtf8InTxt() throws Exception {
        Path src = makeDir(Map.of("ok.bin", new byte[]{1}, "bad.txt", new byte[]{'a', (byte) 0xC3, '('}));
        Path archive = newPath("a");
        ArchiveException ex = assertThrows(ArchiveException.class, () -> Packer.pack(src, archive));
        assertTrue(ex.getMessage().contains("bad.txt") && ex.getMessage().contains("UTF-8"));
        assertFalse(Files.exists(archive));
        assertTrue(listNames(tmp).stream().noneMatch(n -> n.startsWith(".sarc-")), "остался temp-файл");
    }

    @Test void packRejectsSubdirectory() throws Exception {
        Path src = makeDir(Map.of("a.bin", new byte[]{1}));
        Files.createDirectory(src.resolve("sub"));
        assertThrows(ArchiveException.class, () -> Packer.pack(src, newPath("a")));
    }

    @Test void packKeepsOldArchiveOnFailure() throws Exception {
        Path archive = newPath("a");
        Files.write(archive, new byte[]{9, 9, 9});
        Path src = makeDir(Map.of("bad.txt", new byte[]{(byte) 0xFF}));
        assertThrows(ArchiveException.class, () -> Packer.pack(src, archive));
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(archive));
    }

    @Test void packRejectsInvalidUtf8InUppercaseTxt() throws Exception {
        Path src = makeDir(Map.of("X.TXT", new byte[]{'a', (byte) 0xFF}));
        Path archive = newPath("a");
        ArchiveException ex = assertThrows(ArchiveException.class, () -> Packer.pack(src, archive));
        assertTrue(ex.getMessage().contains("X.TXT") && ex.getMessage().contains("корректность UTF-8"), ex.getMessage());
        assertFalse(Files.exists(archive));
    }

    @Test void packRejectsNameLongerThan65535Bytes() throws Exception {
        Path src = Files.createTempDirectory(tmp, "src");
        String name = "a".repeat(65_536);
        try {
            Files.write(src.resolve(name), new byte[]{1});
        } catch (IOException | InvalidPathException ex) {
            // Windows, ext4 и др. ограничивают компонент пути 255 байтами — упереться в формат нельзя.
            Assumptions.abort("ФС не умеет создавать имя длиной 65536 байт: " + ex);
        }
        Path archive = newPath("a");
        ArchiveException ex = assertThrows(ArchiveException.class, () -> Packer.pack(src, archive));
        assertTrue(ex.getMessage().contains("длина имени"), ex.getMessage());
        assertFalse(Files.exists(archive));
    }

    // ---------- unpack: отклонения ----------

    @Test void acceptsNameLengthFieldOf65535() throws Exception {
        // Поле длины имени 16-битное: 65535 — максимум формата. Имя из 0xFF читается целиком и
        // отклоняется уже на проверке UTF-8 имени, а не как обрыв архива.
        byte[] name = new byte[0xFFFF];
        Arrays.fill(name, (byte) 0xFF);
        byte[] a = new ArchiveBuilder().header().entry(2, name, new byte[]{1}).end().build();
        assertFailsClean(a, "корректность UTF-8 в имени");
    }

    @Test void truncatedNameOf65535BytesIsReportedAsTruncation() throws Exception {
        byte[] a = new ArchiveBuilder().header().bytes(2, 0xFF, 0xFF).bytes(new int[100]).build();
        assertFailsClean(a, "целостность архива");
    }

    @Test void unpackRejectsNameLongerThan255() throws Exception {
        // Допустимое для формата (<= 65535 байт, валидный UTF-8), но невозможное для ФС имя:
        // ждём ArchiveException с причиной-IOException, а в целевой папке нет ни файлов, ни staging.
        String name = "a".repeat(300);
        byte[] a = new ArchiveBuilder().header().entry(2, name, new byte[]{1}).end().build();
        ArchiveException ex = assertFailsClean(a, "имя файла допустимо в файловой системе");
        assertTrue(ex.getMessage().contains(name), "в сообщении нет имени файла");
        assertInstanceOf(FileSystemException.class, ex.getCause());
        assertFalse(ex.getCause() instanceof FileAlreadyExistsException, "это не конфликт имён");
    }

    @Test void rejectsCaseInsensitiveDuplicate() throws Exception {
        Path probe = Files.createFile(tmp.resolve("CaseProbe"));
        Assumptions.assumeTrue(Files.exists(tmp.resolve("caseprobe")), "ФС чувствительна к регистру");
        Files.delete(probe);
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "A.bin", new byte[]{1}).entry(2, "a.bin", new byte[]{2}).end().build();
        ArchiveException ex = assertFailsClean(a, "уникальность имени");
        assertTrue(ex.getMessage().contains("a.bin") || ex.getMessage().contains("A.bin"), ex.getMessage());
        assertInstanceOf(FileAlreadyExistsException.class, ex.getCause());
    }

    @Test void rejectsBadMagic() throws Exception {
        assertFailsClean(new byte[]{'X', 'A', 'R', 'C', 1, 0}, "сигнатура");
    }

    @Test void rejectsUnknownVersion() throws Exception {
        assertFailsClean(new ArchiveBuilder().header(2).end().build(), "версия");
    }

    @Test void rejectsUnknownType() throws Exception {
        assertFailsClean(new ArchiveBuilder().header().entry(3, "a.bin", new byte[]{1}).end().build(), "тип файла");
    }

    @Test void rejectsNegativeLength() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .raw(2, utf8("a.bin"), -1, new byte[0], 0).end().build();
        assertFailsClean(a, "длина содержимого");
    }

    @Test void rejectsHugeLengthAsTruncation() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .raw(2, utf8("a.bin"), Long.MAX_VALUE, new byte[]{1, 2}, 0).build();
        assertFailsClean(a, "обрыв");
    }

    @Test void rejectsBadCrc() throws Exception {
        byte[] c = {1, 2, 3};
        byte[] a = new ArchiveBuilder().header()
                .raw(2, utf8("a.bin"), 3, c, ArchiveBuilder.crc(c) ^ 1).end().build();
        assertFailsClean(a, "CRC32");
    }

    @Test void rejectsDuplicateName() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "a.bin", new byte[]{1}).entry(2, "a.bin", new byte[]{2}).end().build();
        assertFailsClean(a, "повторное имя");
    }

    @Test void rejectsTrailingBytes() throws Exception {
        byte[] a = new ArchiveBuilder().header().entry(2, "a.bin", new byte[]{1}).end().bytes(0).build();
        assertFailsClean(a, "после завершающего маркера");
    }

    @Test void rejectsMissingEndMarker() throws Exception {
        assertFailsClean(new ArchiveBuilder().header().entry(2, "a.bin", new byte[]{1}).build(), "маркер");
    }

    @Test void rejectsPathTraversalNames() throws Exception {
        for (String name : List.of("../evil.bin", "a/b.bin", "a\\b.bin", "..", ".", "/abs.bin", "a\0b")) {
            byte[] a = new ArchiveBuilder().header().entry(2, name, new byte[]{1}).end().build();
            assertFailsClean(a, "безопасность имени");
            assertFalse(Files.exists(tmp.resolve("evil.bin")));
        }
    }

    @Test void rejectsEmptyName() throws Exception {
        assertFailsClean(new ArchiveBuilder().header().entry(2, "", new byte[]{1}).end().build(), "имя файла");
    }

    @Test void rejectsInvalidUtf8InTxtContent() throws Exception {
        byte[][] bad = {
                {'a', (byte) 0xC3, '('},                // обрыв последовательности
                {(byte) 0xC0, (byte) 0x80},             // overlong
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80},// суррогат
                {(byte) 0xE2, (byte) 0x82},             // хвост оборван на конце файла
                {(byte) 0xFF},
        };
        for (byte[] content : bad) {
            byte[] a = new ArchiveBuilder().header().entry(1, "t.txt", content).end().build(); // CRC верный
            assertFailsClean(a, "UTF-8");
        }
    }

    @Test void rejectsInvalidUtf8WithUtf8Message() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .entry(1, "bad.txt", new byte[]{'o', 'k', (byte) 0xFF}).end().build();
        ArchiveException ex = assertFailsClean(a, "корректность UTF-8");
        assertTrue(ex.getMessage().contains("bad.txt"), ex.getMessage());
        assertFalse(ex.getMessage().contains("уникальность имени"), ex.getMessage());
        assertInstanceOf(CharacterCodingException.class, ex.getCause());
    }

    @Test void rejectsInvalidUtf8SplitAcrossTrickleReads() throws Exception {
        byte[] emoji = utf8("😀");
        byte[][] bad = {
                Arrays.copyOf(utf8("привет 😀"), utf8("привет 😀").length - 1),  // эмодзи оборван на конце файла
                {'a', (byte) 0xF0, (byte) 0x9F, (byte) 0x98},                    // то же, 3 из 4 байт
                {'a', (byte) 0xFF, 'b', 'c'},                                    // недопустимый байт
                {(byte) 0xF0, (byte) 0x9F, '(', (byte) 0x80},                    // вместо продолжения — ASCII
                concat(emoji, new byte[]{(byte) 0xF0, (byte) 0x9F}, utf8("x")),  // оборванная последовательность в середине
        };
        for (byte[] content : bad) {
            byte[] a = new ArchiveBuilder().header().entry(1, "t.txt", content).end().build();
            for (long seed = 0; seed < 20; seed++) {
                ArchiveException ex = assertFailsClean(new TrickleInputStream(a, seed), "корректность UTF-8");
                assertTrue(ex.getMessage().contains("t.txt"), ex.getMessage());
            }
        }
    }

    @Test void rejectsInvalidUtf8LateInLargeTxt() throws Exception {
        byte[] content = new byte[100_000];
        Arrays.fill(content, (byte) 'a');
        content[70_000] = (byte) 0xFF; // за пределами первых буферов (32 КиБ, 64 КиБ)
        byte[] a = new ArchiveBuilder().header().entry(1, "large.txt", content).end().build();
        ArchiveException ex = assertFailsClean(a, "корректность UTF-8");
        assertTrue(ex.getMessage().contains("large.txt"), ex.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "codePointAboveU10FFFF, F4908080",
            "f5Lead,                F5808080",
            "overlongC080,          C080",
            "surrogateEDA080,       EDA080",
    })
    void rejectsInvalidUtf8Sequence(String caseName, String hex) throws Exception {
        byte[] content = HexFormat.of().parseHex(hex);
        byte[] a = new ArchiveBuilder().header().entry(1, "t.txt", content).end().build();
        ArchiveException ex = assertFailsClean(a, "корректность UTF-8");
        assertTrue(ex.getMessage().contains("t.txt"), ex.getMessage());
    }

    private static byte[] concat(byte[]... parts) {
        var bos = new ByteArrayOutputStream();
        for (byte[] p : parts) bos.writeBytes(p);
        return bos.toByteArray();
    }

    @Test void sameBytesAsBinaryAreAccepted() throws Exception {
        byte[] a = new ArchiveBuilder().header().entry(2, "t.bin", new byte[]{(byte) 0xFF}).end().build();
        Path out = newPath("o");
        Unpacker.unpack(new ByteArrayInputStream(a), out);
        assertArrayEquals(new byte[]{(byte) 0xFF}, Files.readAllBytes(out.resolve("t.bin")));
    }

    @Test void secondFileBrokenRollsBackFirst() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "a.bin", new byte[]{1, 2, 3})
                .raw(2, utf8("b.bin"), 1, new byte[]{9}, 12345)
                .end().build();
        assertFailsClean(a, "b.bin");
    }

    @Test void unpackFailureInSecondFileContentLeavesNothing() throws Exception {
        // заявлено 10 байт, пришло 4 и конец потока; первый файл к этому моменту уже полностью записан в staging
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "a.bin", new byte[]{1, 2, 3})
                .raw(2, utf8("b.bin"), 10, new byte[]{1, 2, 3, 4}, 0)
                .build();
        ArchiveException ex = assertFailsClean(a, "b.bin");
        assertTrue(ex.getMessage().contains("обрыв"), ex.getMessage());
    }

    @Test void unpackFailureRemovesStagingDir() throws Exception {
        Path out = Files.createDirectory(newPath("o"));
        Files.write(out.resolve("keep.bin"), new byte[]{5});
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "a.bin", new byte[]{1})
                .raw(2, utf8("b.bin"), 1, new byte[]{9}, 12345) // неверный CRC
                .end().build();
        assertThrows(ArchiveException.class, () -> Unpacker.unpack(new ByteArrayInputStream(a), out));
        assertEquals(Set.of("keep.bin"), listNames(out), "остались staging-папка или файлы распаковки");
    }

    @Test void unpackSuccessRemovesStagingDir() throws Exception {
        Path out = newPath("o");
        byte[] a = new ArchiveBuilder().header().entry(2, "a.bin", new byte[]{1}).end().build();
        Unpacker.unpack(new ByteArrayInputStream(a), out);
        assertEquals(Set.of("a.bin"), listNames(out));
        assertTrue(listNames(out).stream().noneMatch(ArchiverTest::isStaging));
    }

    @Test void refusesToOverwriteExistingFile() throws Exception {
        byte[] a = new ArchiveBuilder().header()
                .entry(2, "a.bin", new byte[]{1}).entry(2, "b.bin", new byte[]{2}).end().build();
        Path out = Files.createDirectory(newPath("o"));
        Files.write(out.resolve("b.bin"), new byte[]{7});
        assertThrows(ArchiveException.class, () -> Unpacker.unpack(new ByteArrayInputStream(a), out));
        assertEquals(Set.of("b.bin"), listNames(out));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(out.resolve("b.bin")));
    }

    // ---------- повреждения ----------

    @Test void everyTruncationFailsCleanly() throws Exception {
        byte[] archive = pack(Map.of("a.bin", new byte[]{1, 2, 3, 4, 5}, "t.txt", utf8("привет 😀")));
        for (int len = 0; len < archive.length; len++) {
            assertFailsClean(Arrays.copyOf(archive, len), null);
        }
    }

    @Test void everyByteFlipIsSafe() throws Exception {
        byte[] content = new byte[100];
        new Random(3).nextBytes(content);
        byte[] archive = new ArchiveBuilder().header().entry(2, "a.bin", content).end().build();
        // смещения: заголовок 0-4, тип 5, длина имени 6-7, имя 8-12, длина 13-20,
        // содержимое 21-120, CRC 121-124, маркер 125
        for (int i = 0; i < archive.length; i++) {
            byte[] broken = archive.clone();
            broken[i] ^= (byte) 0xFF;
            Path out = newPath("fz");
            boolean mustFail = i < 5 || i >= 13; // тип/имя могут «законно» измениться, остальное — нет
            try {
                Unpacker.unpack(new ByteArrayInputStream(broken), out);
                assertFalse(mustFail, "повреждение байта " + i + " не обнаружено");
                Set<String> names = listNames(out);
                assertEquals(1, names.size(), "байт " + i + ": ожидался ровно один файл, есть " + names);
                assertTrue(names.stream().noneMatch(ArchiverTest::isStaging), "остался staging после байта " + i);
            } catch (IOException expected) {
                assertEquals(Set.of(), listNames(out), "остатки после повреждения байта " + i);
            }
        }
    }

    // ---------- CLI ----------

    @Test void cliExitCodes() throws Exception {
        Path src = makeDir(Map.of("a.bin", new byte[]{1}));
        Path archive = newPath("a");
        Path out = newPath("o");
        var sink = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, Main.run(new String[]{"pack", src.toString(), archive.toString()}, sink));
        assertEquals(0, Main.run(new String[]{"unpack", archive.toString(), out.toString()}, sink));
        assertEquals(1, Main.run(new String[]{"unpack", archive.toString(), out.toString()}, sink)); // файл уже есть
        assertEquals(2, Main.run(new String[]{"zip"}, sink));
    }
}
