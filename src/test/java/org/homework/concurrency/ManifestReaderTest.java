package org.homework.concurrency;

import org.homework.concurrent.ManifestEntry;
import org.homework.concurrent.ManifestException;
import org.homework.concurrent.ManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ManifestReaderTest {

    @TempDir
    Path dir;

    private Path manifest(String content) throws IOException {
        Path path = dir.resolve("manifest.csv");
        Files.writeString(path, content);
        return path;
    }

    @Test
    void readsEntriesInOrderWithLineNumbers() throws IOException {
        Path path = manifest("source,target\nphotos/001.jpg,images/001.jpg\n\ndocuments/manual.pdf,docs/manual.pdf\n");
        assertEquals(List.of(
                        new ManifestEntry(2, "photos/001.jpg", "images/001.jpg"),
                        new ManifestEntry(4, "documents/manual.pdf", "docs/manual.pdf")),
                ManifestReader.read(path));
    }

    @Test
    void columnsMayBeInEitherOrder() throws IOException {
        Path path = manifest("target,source\nimages/001.jpg,photos/001.jpg\n");
        assertEquals(List.of(new ManifestEntry(2, "photos/001.jpg", "images/001.jpg")), ManifestReader.read(path));
    }

    @Test
    void handlesCrlfAndBom() throws IOException {
        Path path = manifest("\uFEFFsource,target\r\na.txt,b.txt\r\n");
        assertEquals(List.of(new ManifestEntry(2, "a.txt", "b.txt")), ManifestReader.read(path));
    }

    @Test
    void keepsDuplicatesForTheCopierToDecide() throws IOException {
        Path path = manifest("source,target\na.txt,x.txt\nb.txt,x.txt\n");
        assertEquals(2, ManifestReader.read(path).size());
    }

    @Test
    void headerOnlyGivesEmptyList() throws IOException {
        assertEquals(List.of(), ManifestReader.read(manifest("source,target\n")));
    }

    @Test
    void rejectsEmptyFile() throws IOException {
        assertThrows(ManifestException.class, () -> ManifestReader.read(manifest("")));
    }

    @Test
    void rejectsWrongHeader() throws IOException {
        assertThrows(ManifestException.class, () -> ManifestReader.read(manifest("src,dst\na,b\n")));
    }

    @Test
    void rejectsExtraHeaderColumn() throws IOException {
        assertThrows(ManifestException.class, () -> ManifestReader.read(manifest("source,target,extra\na,b,c\n")));
    }

    @Test
    void rejectsRowWithWrongNumberOfValues() throws IOException {
        Path path = manifest("source,target\na.txt,b.txt\nonly-one\n");
        ManifestException ex = assertThrows(ManifestException.class, () -> ManifestReader.read(path));
        assertTrue(ex.getMessage().contains("строка 3"), ex.getMessage());
    }

    @Test
    void rejectsEmptyPath() throws IOException {
        Path path = manifest("source,target\n,b.txt\n");
        ManifestException ex = assertThrows(ManifestException.class, () -> ManifestReader.read(path));
        assertTrue(ex.getMessage().contains("строка 2"), ex.getMessage());
    }

    @Test
    void rejectsInvalidUtf8() throws IOException {
        byte[] bytes = "source,target\nax,b\n".getBytes(StandardCharsets.UTF_8);
        bytes[14] = (byte) 0xFF;   // вместо 'a'
        Path path = dir.resolve("manifest.csv");
        Files.write(path, bytes);
        assertThrows(ManifestException.class, () -> ManifestReader.read(path));
    }

    @Test
    void missingFileIsReportedAsManifestException() {
        assertThrows(ManifestException.class, () -> ManifestReader.read(dir.resolve("nope.csv")));
    }
}
