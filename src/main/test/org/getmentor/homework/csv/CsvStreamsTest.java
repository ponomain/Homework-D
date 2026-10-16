package org.getmentor.homework.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class CsvStreamsTest {
    private static final String TEXT = "id,name\n1,Привет 😀\n2,Ivan\n";

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"data.csv", "data.csv.gz", "data.gz", "data.zip"})
    void roundTripsEveryFormat(String name) throws IOException {
        String file = dir.resolve(name).toString();
        CsvStreams.write(file, w -> w.write(TEXT));
        assertEquals(TEXT, readAll(file));
    }

    @Test
    void gzFileStartsWithGzipMagic() throws IOException {
        Path gz = dir.resolve("a.csv.gz");
        CsvStreams.write(gz.toString(), w -> w.write(TEXT));
        byte[] bytes = Files.readAllBytes(gz);
        assertEquals((byte) 0x1f, bytes[0]);
        assertEquals((byte) 0x8b, bytes[1]);
    }

    @Test
    void zipHoldsOneCsvEntryNamedAfterArchive() throws IOException {
        Path zip = dir.resolve("people.zip");
        CsvStreams.write(zip.toString(), w -> w.write(TEXT));
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertEquals(1, zf.size());
            assertEquals("people.csv", zf.entries().nextElement().getName());
        }
    }

    @Test
    void rejectsZipWithTwoEntries() throws IOException {
        Path zip = zipWith("two.zip", "a.csv", "b.csv");
        CsvException ex = assertThrows(CsvException.class, () -> readAll(zip.toString()));
        assertTrue(ex.getMessage().contains("two.zip"));
    }

    @Test
    void rejectsEmptyZip() throws IOException {
        Path zip = zipWith("empty.zip");
        assertThrows(CsvException.class, () -> readAll(zip.toString()));
    }

    @Test
    void rejectsZipWithNonCsvEntry() throws IOException {
        Path zip = zipWith("notes.zip", "notes.txt");
        assertThrows(CsvException.class, () -> readAll(zip.toString()));
    }

    @Test
    void rejectsUnknownExtension() {
        String file = dir.resolve("a.txt").toString();
        assertThrows(CsvException.class, () -> readAll(file));
        assertThrows(CsvException.class, () -> CsvStreams.write(file, w -> { }));
    }

    @Test
    void rejectsInvalidUtf8WithoutReplacement() throws IOException {
        Path csv = dir.resolve("bad.csv");
        Files.write(csv, new byte[] {'a', (byte) 0xFF, '\n'});
        assertThrows(CharacterCodingException.class, () -> readAll(csv.toString()));
    }

    @Test
    void rejectsLoneSurrogateOnWriteAndLeavesNoFiles() throws IOException {
        Path csv = dir.resolve("out.csv");
        assertThrows(CsvException.class, () -> CsvStreams.write(csv.toString(), w -> w.write("\uD800")));
        assertEquals(0, countFiles());
    }

    @Test
    void rejectsGarbageGzip() throws IOException {
        Path gz = dir.resolve("bad.gz");
        Files.writeString(gz, "это не gzip");
        assertThrows(IOException.class, () -> readAll(gz.toString()));
    }

    @Test
    void rejectsTruncatedGzip() throws IOException {
        Path gz = dir.resolve("big.csv.gz");
        CsvStreams.write(gz.toString(), w -> w.write(randomText()));
        byte[] bytes = Files.readAllBytes(gz);
        Files.write(gz, Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> readAll(gz.toString()));
    }

    @Test
    void rejectsCorruptedGzipData() throws IOException {
        Path gz = dir.resolve("flip.csv.gz");
        CsvStreams.write(gz.toString(), w -> w.write(randomText()));
        byte[] bytes = Files.readAllBytes(gz);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(gz, bytes);
        assertThrows(IOException.class, () -> readAll(gz.toString()));
    }

    @Test
    void rejectsTruncatedZip() throws IOException {
        Path zip = dir.resolve("big.zip");
        CsvStreams.write(zip.toString(), w -> w.write(randomText()));
        byte[] bytes = Files.readAllBytes(zip);
        Files.write(zip, Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> readAll(zip.toString()));
    }

    @Test
    void rejectsCorruptedZipData() throws IOException {
        Path zip = dir.resolve("flip.zip");
        CsvStreams.write(zip.toString(), w -> w.write(randomText()));
        byte[] bytes = Files.readAllBytes(zip);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(zip, bytes);
        assertThrows(IOException.class, () -> readAll(zip.toString()));
    }

    @Test
    void failedWriteLeavesNoFiles() throws IOException {
        String file = dir.resolve("out.csv").toString();
        assertThrows(CsvException.class, () -> CsvStreams.write(file, w -> {
            w.write("partial");
            throw new IOException("boom");
        }));
        assertEquals(0, countFiles());
    }

    @Test
    void failedRewriteKeepsPreviousFile() throws IOException {
        Path csv = dir.resolve("out.csv");
        CsvStreams.write(csv.toString(), w -> w.write(TEXT));
        assertThrows(CsvException.class, () -> CsvStreams.write(csv.toString(), w -> {
            w.write("partial");
            throw new IOException("boom");
        }));
        assertEquals(TEXT, Files.readString(csv));
        assertEquals(1, countFiles());
    }

    // ---------- helpers ----------

    private static String readAll(String file) throws IOException {
        try (BufferedReader reader = CsvStreams.openReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private Path zipWith(String archiveName, String... entryNames) throws IOException {
        Path zip = dir.resolve(archiveName);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : entryNames) {
                out.putNextEntry(new ZipEntry(name));
                out.write("a\n".getBytes());
                out.closeEntry();
            }
        }
        return zip;
    }

    /** Достаточно большой и плохо сжимаемый текст, чтобы середина файла гарантированно была внутри данных. */
    private static String randomText() {
        Random random = new Random(1);
        StringBuilder sb = new StringBuilder("id\n");
        for (int i = 0; i < 5000; i++) {
            sb.append(random.nextLong()).append('\n');
        }
        return sb.toString();
    }

    private long countFiles() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }
}
