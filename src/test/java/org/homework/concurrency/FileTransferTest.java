package org.homework.concurrency;

import org.homework.concurrent.CopyCancelledException;
import org.homework.concurrent.FileTransfer;
import org.homework.concurrent.StreamOpener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class FileTransferTest {

    private static final BooleanSupplier NEVER = () -> false;

    @TempDir
    Path dir;

    private Path target;
    private final FileTransfer transfer = new FileTransfer(StreamOpener.files());

    @BeforeEach
    void setUp() {
        target = dir.resolve("dst").resolve("sub").resolve("out.bin");   // папок dst/sub ещё нет
    }

    // ---------- успешное копирование ----------

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 65535, 65536, 65537, 200_000})
    void copiesExactBytesAroundBufferBoundaries(int size) throws IOException {
        byte[] data = randomBytes(size);
        Path source = writeSource(data);

        FileTransfer.TempCopy copy = transfer.copyToTemp(source, target, NEVER);
        assertEquals(size, copy.bytes());
        assertFalse(Files.exists(target), "до commit конечного файла быть не должно");

        transfer.commit(copy.temp(), target);
        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(List.of("out.bin"), namesIn(target.getParent()), "временный файл должен исчезнуть");
    }

    @Test
    void copiesAllByteValues() throws IOException {
        byte[] data = new byte[256 * 300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        copyFully(writeSource(data));
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    void copiesFromStreamThatReturnsOneToThreeBytesPerRead() throws IOException {
        byte[] data = randomBytes(5000);
        FileTransfer trickling = new FileTransfer(wrapping(TrickleInput::new, UnaryOperator.identity()));

        FileTransfer.TempCopy copy = trickling.copyToTemp(writeSource(data), target, NEVER);
        trickling.commit(copy.temp(), target);

        assertEquals(data.length, copy.bytes());
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    void neverRequestsMoreThanTheBufferPerRead() throws IOException {
        AtomicInteger maxRequest = new AtomicInteger();
        FileTransfer measuring = new FileTransfer(wrapping(in -> new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                maxRequest.accumulateAndGet(len, Math::max);
                return super.read(b, off, len);
            }
        }, UnaryOperator.identity()));

        FileTransfer.TempCopy copy = measuring.copyToTemp(writeSource(randomBytes(300_000)), target, NEVER);
        measuring.commit(copy.temp(), target);

        assertTrue(maxRequest.get() <= 64 * 1024, "запрошено за раз: " + maxRequest.get());
    }

    @Test
    void targetStaysAbsentWhileDataGoesToTempFile() throws IOException {
        AtomicBoolean targetSeen = new AtomicBoolean();
        AtomicBoolean tempSeen = new AtomicBoolean();
        FileTransfer watching = new FileTransfer(wrapping(in -> new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (Files.exists(target)) {
                    targetSeen.set(true);
                }
                if (namesIn(target.getParent()).stream().anyMatch(name -> name.startsWith(".copy-"))) {
                    tempSeen.set(true);
                }
                return super.read(b, off, len);
            }
        }, UnaryOperator.identity()));

        FileTransfer.TempCopy copy = watching.copyToTemp(writeSource(randomBytes(200_000)), target, NEVER);
        watching.commit(copy.temp(), target);

        assertFalse(targetSeen.get(), "конечный файл не должен появляться до commit");
        assertTrue(tempSeen.get(), "данные должны писаться во временный файл");
    }

    @Test
    void createsMissingTargetDirectories() throws IOException {
        copyFully(writeSource(randomBytes(10)));
        assertTrue(Files.isDirectory(target.getParent()));
    }

    @Test
    void overwritesExistingTarget() throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old content");
        byte[] data = randomBytes(1000);

        copyFully(writeSource(data));

        assertArrayEquals(data, Files.readAllBytes(target));
        assertEquals(List.of("out.bin"), namesIn(target.getParent()));
    }

    // ---------- ошибки ----------

    @Test
    void missingSourceFailsWithoutSideEffects() {
        assertThrows(NoSuchFileException.class, () -> transfer.copyToTemp(dir.resolve("nope.bin"), target, NEVER));
        assertFalse(Files.exists(dir.resolve("dst")), "папки в цели создаваться не должны");
    }

    @Test
    void readErrorRemovesTempFile() throws IOException {
        FileTransfer failing = new FileTransfer(wrapping(in -> new FailingInput(in, 100_000), UnaryOperator.identity()));
        Path source = writeSource(randomBytes(200_000));

        IOException ex = assertThrows(IOException.class, () -> failing.copyToTemp(source, target, NEVER));

        assertTrue(ex.getMessage().contains("read error"), ex.getMessage());
        assertEquals(List.of(), namesIn(target.getParent()));
        assertFalse(Files.exists(target));
    }

    @Test
    void writeErrorRemovesTempFile() throws IOException {
        FileTransfer failing = new FileTransfer(wrapping(UnaryOperator.identity(), out -> new FailingOutput(out, 1000, false)));
        Path source = writeSource(randomBytes(200_000));

        IOException ex = assertThrows(IOException.class, () -> failing.copyToTemp(source, target, NEVER));

        assertTrue(ex.getMessage().contains("write error"), ex.getMessage());
        assertEquals(List.of(), namesIn(target.getParent()));
    }

    @Test
    void closeErrorRemovesTempFile() throws IOException {
        FileTransfer failing = new FileTransfer(wrapping(UnaryOperator.identity(), out -> new FailingOutput(out, Long.MAX_VALUE, true)));
        Path source = writeSource(randomBytes(1000));

        IOException ex = assertThrows(IOException.class, () -> failing.copyToTemp(source, target, NEVER));

        assertTrue(ex.getMessage().contains("close error"), ex.getMessage());
        assertEquals(List.of(), namesIn(target.getParent()));
    }

    @Test
    void commitFailureRemovesTempFileAndKeepsExistingDirectory() throws IOException {
        // конечный путь занят непустой папкой, перенос файла на неё невозможен
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "keep");
        FileTransfer.TempCopy copy = transfer.copyToTemp(writeSource(randomBytes(100)), target, NEVER);

        assertThrows(IOException.class, () -> transfer.commit(copy.temp(), target));

        assertEquals(List.of("out.bin"), namesIn(target.getParent()), "временный файл должен быть удалён");
        assertEquals("keep", Files.readString(target.resolve("keep.txt")));
    }

    // ---------- отмена ----------

    @Test
    void cancellationInTheMiddleRemovesTempFile() throws IOException {
        Path source = writeSource(randomBytes(300_000));
        AtomicInteger checks = new AtomicInteger();
        BooleanSupplier cancelAfterFirstChunk = () -> checks.incrementAndGet() > 1;

        assertThrows(CopyCancelledException.class, () -> transfer.copyToTemp(source, target, cancelAfterFirstChunk));

        assertEquals(List.of(), namesIn(target.getParent()));
        assertFalse(Files.exists(target));
    }

    @Test
    void cancellationBeforeFirstReadRemovesTempFile() throws IOException {
        Path source = writeSource(randomBytes(100));

        assertThrows(CopyCancelledException.class, () -> transfer.copyToTemp(source, target, () -> true));

        assertEquals(List.of(), namesIn(target.getParent()));
    }

    // ---------- helpers ----------

    private void copyFully(Path source) throws IOException {
        FileTransfer.TempCopy copy = transfer.copyToTemp(source, target, NEVER);
        transfer.commit(copy.temp(), target);
    }

    private Path writeSource(byte[] data) throws IOException {
        Path source = dir.resolve("in.bin");
        Files.write(source, data);
        return source;
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private static List<String> namesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static StreamOpener wrapping(UnaryOperator<InputStream> input, UnaryOperator<OutputStream> output) {
        return new StreamOpener() {
            @Override
            public InputStream openInput(Path path) throws IOException {
                return input.apply(Files.newInputStream(path));
            }

            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return output.apply(Files.newOutputStream(path));
            }
        };
    }

    /** Отдаёт не больше 1-3 байт за вызов read. */
    private static final class TrickleInput extends FilterInputStream {
        private final Random random = new Random(1);

        TrickleInput(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, 1 + random.nextInt(3)));
        }
    }

    /** После failAfter байт бросает ошибку чтения. */
    private static final class FailingInput extends FilterInputStream {
        private final long failAfter;
        private long delivered;

        FailingInput(InputStream in, long failAfter) {
            super(in);
            this.failAfter = failAfter;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (delivered >= failAfter) {
                throw new IOException("simulated read error");
            }
            int n = super.read(b, off, (int) Math.min(len, failAfter - delivered));
            if (n > 0) {
                delivered += n;
            }
            return n;
        }
    }

    /** После failAfter записанных байт бросает ошибку записи; при failOnClose падает при закрытии. */
    private static final class FailingOutput extends FilterOutputStream {
        private final long failAfter;
        private final boolean failOnClose;
        private long written;

        FailingOutput(OutputStream out, long failAfter, boolean failOnClose) {
            super(out);
            this.failAfter = failAfter;
            this.failOnClose = failOnClose;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written + len > failAfter) {
                throw new IOException("simulated write error");
            }
            out.write(b, off, len);
            written += len;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void close() throws IOException {
            out.close();
            if (failOnClose) {
                throw new IOException("simulated close error");
            }
        }
    }
}
