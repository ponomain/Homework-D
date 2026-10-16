package org.getmentor.homework.csv;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.*;

/**
 * Выбирает цепочку потоков по расширению файла. Кодировка всегда UTF-8, без тихой замены символов.
 */
final class CsvStreams {

    @FunctionalInterface
    interface WriterAction {
        void accept(Writer writer) throws IOException;
    }

    private enum Format {
        CSV, GZIP, ZIP
    }

    private CsvStreams() {
    }

    private static Format detect(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".gz")) {
            return Format.GZIP;
        }
        if (name.endsWith(".zip")) {
            return Format.ZIP;
        }
        if (name.endsWith(".csv")) {
            return Format.CSV;
        }
        throw new CsvException(filename
                + ": неподдерживаемое расширение (ожидается .csv, .csv.gz, .gz или .zip)");
    }

    // ---------- чтение ----------

    static BufferedReader openReader(String filename) throws IOException {
        Format format = detect(filename);
        InputStream in = Files.newInputStream(Path.of(filename));
        try {
            InputStream csv = switch (format) {
                case CSV -> in;
                case GZIP -> new GZIPInputStream(in);
                case ZIP -> openSingleCsvEntry(in, filename);
            };
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return new BufferedReader(new InputStreamReader(csv, decoder));
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(in, e);
            throw e;
        }
    }

    private static InputStream openSingleCsvEntry(InputStream in, String filename) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                throw new CsvException(filename + ": ZIP-архив не содержит записей");
            }
            if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                throw new CsvException(filename + ": запись '" + entry.getName() + "' не является CSV-файлом");
            }
            return new SingleEntryZipStream(zip, filename);
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(zip, e);
            throw e;
        }
    }

    /**
     * Отдаёт содержимое первой записи ZIP. Когда запись дочитана до конца, проверяет, что
     * второй записи нет: заранее это не узнать, пока читаем поток последовательно.
     * ZipInputStream сам сверяет CRC записи на её конце.
     */
    private static final class SingleEntryZipStream extends FilterInputStream {
        private final ZipInputStream zip;
        private final String filename;
        private boolean checked;

        SingleEntryZipStream(ZipInputStream zip, String filename) {
            super(zip);
            this.zip = zip;
            this.filename = filename;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                verifyNoMoreEntries();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n == -1) {
                verifyNoMoreEntries();
            }
            return n;
        }

        private void verifyNoMoreEntries() throws IOException {
            if (checked) {
                return;
            }
            checked = true;
            if (zip.getNextEntry() != null) {
                throw new CsvException(filename + ": в ZIP-архиве больше одной записи, нужна ровно одна");
            }
        }
    }

    // ---------- запись ----------

    /**
     * Пишет сначала во временный файл рядом с целевым и переносит его на место только при успехе:
     * при ошибке целевой файл остаётся прежним, временный удаляется.
     */

    static void write(String filename, WriterAction action) {
        Format format = detect(filename);
        Path target = Path.of(filename).toAbsolutePath();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), ".csv-", ".tmp");
            try (Writer writer = openWriter(tmp, format, target)) {
                action.accept(writer);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new CsvException(filename + ": не удалось записать файл: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {

                }
            }
        }
    }

    private static Writer openWriter(Path tmp, Format format, Path target) throws IOException {
        OutputStream out = Files.newOutputStream(tmp);
        try {
            OutputStream csv = switch (format) {
                case CSV -> out;
                case GZIP -> new GZIPOutputStream(out);
                case ZIP -> {
                    ZipOutputStream zip = new ZipOutputStream(out);
                    zip.putNextEntry(new ZipEntry(zipEntryName(target)));
                    yield zip;
                }
            };
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return new BufferedWriter(new OutputStreamWriter(csv, encoder));
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(out, e);
            throw e;
        }
    }

    /**
     * people.zip -> people.csv
     */
    private static String zipEntryName(Path target) {
        String base = target.getFileName().toString();
        base = base.substring(0, base.length() - ".zip".length());
        return base.toLowerCase(Locale.ROOT).endsWith(".csv") ? base : base + ".csv";
    }

    private static void closeAfterFailure(Closeable resource, Throwable primary) {
        try {
            resource.close();
        } catch (IOException suppressed) {
            primary.addSuppressed(suppressed);
        }
    }
}
