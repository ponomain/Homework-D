package org.homework.concurrency;

import org.homework.concurrent.CopyControl;
import org.homework.concurrent.CopyReport;
import org.homework.concurrent.FileCopier;
import org.homework.concurrent.FileResult;
import org.homework.concurrent.Status;
import org.homework.concurrent.StreamOpener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyControlTest {

    @TempDir
    Path dir;

    private Path manifest;
    private Path sourceDir;
    private Path targetDir;

    @BeforeEach
    void setUp() throws IOException {
        manifest = dir.resolve("manifest.csv");
        sourceDir = Files.createDirectory(dir.resolve("src"));
        targetDir = dir.resolve("dst");
    }

    // ---------- прогресс ----------

    @Test
    void progressCountsEveryFinishedFileButOnlyCopiedBytes() throws Exception {
        write("a.bin", random(100));
        write("c.bin", random(50));
        manifest("a.bin,a.bin", "missing.bin,missing.bin", "c.bin,c.bin");
        CopyControl control = new CopyControl();
        assertEquals(new CopyControl.Progress(0, 0, 0), control.progress());

        new FileCopier().copyAll(manifest, sourceDir, targetDir, 2, control).get(30, TimeUnit.SECONDS);

        assertEquals(new CopyControl.Progress(3, 3, 150), control.progress());
    }

    @Test
    void progressSnapshotsStayMonotonicAndConsistentWhilePolledFromAnotherThread() throws Exception {
        int files = 40;
        int size = 1000;
        String[] rows = new String[files];
        for (int i = 0; i < files; i++) {
            write("f" + i + ".bin", random(size));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (first) {
                    first = false;
                    sleep(5);
                }
                return super.read(b, off, len);
            }
        }), FileCopier::newPool);
        CopyControl control = new CopyControl();

        CompletableFuture<CopyReport> future = copier.copyAll(manifest, sourceDir, targetDir, 4, control);
        List<CopyControl.Progress> samples = new ArrayList<>();
        while (!future.isDone()) {
            samples.add(control.progress());
            sleep(1);
        }
        future.get(30, TimeUnit.SECONDS);
        samples.add(control.progress());

        CopyControl.Progress previous = new CopyControl.Progress(0, 0, 0);
        for (CopyControl.Progress sample : samples) {
            assertTrue(sample.completedFiles() >= previous.completedFiles(), "файлы уменьшились: " + sample);
            assertTrue(sample.copiedBytes() >= previous.copiedBytes(), "байты уменьшились: " + sample);
            assertEquals((long) sample.completedFiles() * size, sample.copiedBytes(),
                    "файлы и байты в одном снимке рассогласованы: " + sample);
            previous = sample;
        }
        assertEquals(new CopyControl.Progress(files, files, (long) files * size), control.progress());
    }

    @Test
    void controlCannotBeReusedByAnotherRun() throws Exception {
        write("a.bin", random(10));
        manifest("a.bin,a.bin");
        CopyControl control = new CopyControl();
        new FileCopier().copyAll(manifest, sourceDir, targetDir, 1, control).get(30, TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class,
                () -> new FileCopier().copyAll(manifest, sourceDir, targetDir, 1, control));
    }

    // ---------- отмена ----------

    @Test
    void cancellingMidRunStopsEverythingAndLeavesNoFiles() throws Exception {
        int parallelism = 2;
        String[] rows = new String[10];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(1000));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);

        CountDownLatch started = new CountDownLatch(parallelism);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ExecutorService> created = new AtomicReference<>();
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (first) {
                    first = false;
                    started.countDown();
                    await(release);
                }
                return super.read(b, off, len);
            }
        }), threads -> {
            created.set(FileCopier.newPool(threads));
            return created.get();
        });
        CopyControl control = new CopyControl();

        CompletableFuture<CopyReport> future = copier.copyAll(manifest, sourceDir, targetDir, parallelism, control);
        assertTrue(started.await(10, TimeUnit.SECONDS), "два копирования должны были начаться");
        control.cancel();
        release.countDown();
        CopyReport report = future.get(30, TimeUnit.SECONDS);

        assertEquals(10, report.cancelled(), report.toString());
        assertEquals(0, report.copied());
        assertEquals(List.of(), tempFilesIn(targetDir));
        for (int i = 0; i < rows.length; i++) {
            assertFalse(Files.exists(targetDir.resolve("out/f" + i + ".bin")), "f" + i);
        }
        assertEquals(new CopyControl.Progress(10, 10, 0), control.progress());
        assertTrue(created.get().isTerminated());
    }

    @Test
    void cancellingBeforeStartCancelsEverythingWithoutTouchingTheDisk() throws Exception {
        write("a.bin", random(10));
        write("b.bin", random(10));
        manifest("a.bin,a.bin", "b.bin,b.bin");
        CopyControl control = new CopyControl();
        control.cancel();

        CopyReport report = new FileCopier().copyAll(manifest, sourceDir, targetDir, 2, control)
                .get(30, TimeUnit.SECONDS);

        assertEquals(List.of(Status.CANCELLED, Status.CANCELLED),
                report.results().stream().map(FileResult::status).toList());
        assertFalse(Files.exists(targetDir), "целевая папка не должна создаваться");
    }

    @Test
    void cancellationKeepsAlreadyCopiedFilesAndDropsTheRest() throws Exception {
        byte[] first = random(2000);
        write("f0.bin", first);
        write("f1.bin", random(2000));
        write("f2.bin", random(2000));
        write("f3.bin", random(2000));
        manifest("f0.bin,f0.bin", "f1.bin,f1.bin", "f2.bin,f2.bin", "f3.bin,f3.bin");
        CopyControl control = new CopyControl();
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (path.getFileName().toString().equals("f1.bin")) {
                    control.cancel();   // отмена приходит, пока копируется второй файл
                }
                return super.read(b, off, len);
            }
        }), FileCopier::newPool);

        CopyReport report = copier.copyAll(manifest, sourceDir, targetDir, 1, control).get(30, TimeUnit.SECONDS);

        assertEquals(List.of(Status.COPIED, Status.CANCELLED, Status.CANCELLED, Status.CANCELLED),
                report.results().stream().map(FileResult::status).toList());
        assertArrayEquals(first, Files.readAllBytes(targetDir.resolve("f0.bin")));
        for (String name : List.of("f1.bin", "f2.bin", "f3.bin")) {
            assertFalse(Files.exists(targetDir.resolve(name)), name);
        }
        assertEquals(List.of("f0.bin"), namesIn(targetDir), "временных файлов остаться не должно");
        assertEquals(new CopyControl.Progress(4, 4, first.length), control.progress());
    }

    // ---------- helpers ----------

    private void write(String relative, byte[] data) throws IOException {
        Path path = sourceDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }

    private void manifest(String... rows) throws IOException {
        Files.writeString(manifest, "source,target\n" + String.join("\n", rows) + "\n");
    }

    private static byte[] random(int size) {
        byte[] data = new byte[size];
        new Random(11).nextBytes(data);
        return data;
    }

    private static List<String> namesIn(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> tempFilesIn(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith(".copy-")).toList();
        }
    }

    private static StreamOpener opener(BiFunction<Path, InputStream, InputStream> input) {
        return new StreamOpener() {
            @Override
            public InputStream openInput(Path path) throws IOException {
                return input.apply(path, Files.newInputStream(path));
            }

            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return Files.newOutputStream(path);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
