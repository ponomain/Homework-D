package org.homework.concurrent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ManifestReader {

    private static final String SEPARATOR = ",";

    private ManifestReader() {

    }

    public static List<ManifestEntry> read(Path manifest) {
        // Files.newBufferedReader читает UTF-8 и бросает ошибку на недопустимых байтах, а не заменяет их

        try (BufferedReader reader = Files.newBufferedReader(manifest)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ManifestException(manifest + ": файл пуст, ожидался заголовок 'source,target'");
            }
            String[] header = stripBom(headerLine).split(SEPARATOR, -1);
            int sourceIndex = indexOf(header, "source");
            int targetIndex = indexOf(header, "target");
            if (header.length != 2 || sourceIndex < 0 || targetIndex < 0) {
                throw new ManifestException(manifest + ", строка 1: ожидался заголовок 'source,target'");
            }

            List<ManifestEntry> entries = new ArrayList<>();
            int lineNumber = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                String where = manifest + ", строка " + lineNumber;
                String[] cells = line.split(SEPARATOR, -1);
                if (cells.length != 2) {
                    throw new ManifestException(where + ": ожидалось 2 значения, получено " + cells.length);
                }
                String source = cells[sourceIndex];
                String target = cells[targetIndex];
                if (source.isEmpty() || target.isEmpty()) {
                    throw new ManifestException(where + ": пустой путь в source или target");
                }
                entries.add(new ManifestEntry(lineNumber, source, target));
            }
            return entries;
        } catch (IOException e) {
            throw new ManifestException(manifest + ": не удалось прочитать манифест: " + e.getMessage(), e);
        }
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
