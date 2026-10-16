package org.homework.concurrency;

import org.homework.concurrent.CopyControl;
import org.homework.concurrent.CopyReport;
import org.homework.concurrent.FileCopier;
import org.homework.concurrent.FileResult;
import org.homework.concurrent.ManifestException;
import org.homework.concurrent.Status;
import org.homework.concurrent.StreamOpener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileCopierTest {

    @TempDir
    Path dir;

    private Path manifest;
    private Path sourceDir;
    private Path targetDir;

    @BeforeEach
    void setUp() throws IOException {
        manifest = dir.resolve("manifest.csv");
        sourceDir = Files.createDirectory(dir.resolve("src"));
        targetDir = dir.resolve("dst");   // создаётся копировщиком
    }

    // ---------- базовое поведение ----------

    @Test
    void copiesAllFilesAndReportsInManifestOrder() throws Exception {
        byte[] a = random(100);
        byte[] b = random(0);
        byte[] c = random(200_000);
        byte[] d = random(5);
        write("photos/001.jpg", a);
        write("documents/manual.pdf", b);
        write("c.bin", c);
        write("d.txt", d);
        manifest("photos/001.jpg,images/001.jpg", "documents/manual.pdf,docs/manual.pdf",
                "c.bin,deep/er/c.bin", "d.txt,d.txt");

        CopyReport report = run(new FileCopier(), 3);

        assertEquals(List.of("photos/001.jpg", "documents/manual.pdf", "c.bin", "d.txt"), sources(report));
        assertTrue(report.isSuccess(), report.toString());
        assertEquals(a.length + b.length + c.length + d.length, report.totalBytes());
        assertArrayEquals(a, Files.readAllBytes(targetDir.resolve("images/001.jpg")));
        assertArrayEquals(b, Files.readAllBytes(targetDir.resolve("docs/manual.pdf")));
        assertArrayEquals(c, Files.readAllBytes(targetDir.resolve("deep/er/c.bin")));
        assertArrayEquals(d, Files.readAllBytes(targetDir.resolve("d.txt")));
        assertEquals(List.of(), tempFilesIn(targetDir));
    }

    @Test
    void emptyManifestGivesEmptyReport() throws Exception {
        manifest();
        CopyReport report = run(new FileCopier(), 2);
        assertEquals(List.of(), report.results());
        assertTrue(report.isSuccess());
    }

    @Test
    void largeBinaryFileIsCopiedExactly() throws Exception {
        write("big.bin", random(20 * 1024 * 1024));
        manifest("big.bin,big.bin");

        CopyReport report = run(new FileCopier(), 2);

        assertTrue(report.isSuccess(), report.toString());
        assertEquals(-1, Files.mismatch(sourceDir.resolve("big.bin"), targetDir.resolve("big.bin")));
        assertEquals(List.of(), tempFilesIn(targetDir));
    }

    // ---------- ошибки одного файла не мешают остальным ----------

    @Test
    void missingSourceFailsOnlyThatFile() throws Exception {
        write("a.txt", random(10));
        write("c.txt", random(10));
        manifest("a.txt,out/a.txt", "missing.txt,out/missing.txt", "c.txt,out/c.txt");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.COPIED, Status.FAILED, Status.COPIED), statuses(report));
        FileResult failed = report.results().get(1);
        assertFalse(failed.error().isBlank());
        assertEquals(0, failed.bytes());
        assertFalse(Files.exists(targetDir.resolve("out/missing.txt")));
    }

    @Test
    void readErrorHalfwayLeavesNoPartialFileAndOthersContinue() throws Exception {
        write("good1.bin", random(1000));
        write("bad.bin", random(300_000));
        write("good2.bin", random(1000));
        manifest("good1.bin,good1.bin", "bad.bin,bad.bin", "good2.bin,good2.bin");
        FileCopier copier = new FileCopier(
                opener((path, in) -> path.getFileName().toString().equals("bad.bin") ? new FailingInput(in, 100_000) : in),
                FileCopier::newPool);

        CopyReport report = run(copier, 2);

        assertEquals(List.of(Status.COPIED, Status.FAILED, Status.COPIED), statuses(report));
        assertTrue(report.results().get(1).error().contains("read error"), report.results().get(1).error());
        assertFalse(Files.exists(targetDir.resolve("bad.bin")), "частичного файла под конечным именем быть не должно");
        assertEquals(List.of(), tempFilesIn(targetDir));
    }

    @Test
    void writeSideFailureFailsOnlyThatFile() throws Exception {
        write("a.txt", random(10));
        write("b.txt", random(10));
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("blocked"), "я файл, а не папка");
        manifest("a.txt,blocked/a.txt", "b.txt,b.txt");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.FAILED, Status.COPIED), statuses(report));
        assertFalse(report.results().get(0).error().isBlank());
    }

    @Test
    void unsafePathsFailOnlyTheirOwnRows() throws Exception {
        write("ok.txt", random(10));
        Files.writeString(dir.resolve("secret.txt"), "secret");   // лежит вне sourceDir
        manifest("ok.txt,ok.txt", "../secret.txt,stolen.txt", "ok.txt,../escaped.txt");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.COPIED, Status.FAILED, Status.FAILED), statuses(report));
        assertFalse(Files.exists(targetDir.resolve("stolen.txt")));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void duplicateTargetFailsLaterRowAndFirstOneWins() throws Exception {
        write("one.txt", "first".getBytes(StandardCharsets.UTF_8));
        write("two.txt", "second".getBytes(StandardCharsets.UTF_8));
        manifest("one.txt,out.txt", "two.txt,out.txt");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.COPIED, Status.FAILED), statuses(report));
        assertTrue(report.results().get(1).error().contains("строкой 2"), report.results().get(1).error());
        assertEquals("first", Files.readString(targetDir.resolve("out.txt")));
    }

    // ---------- параллелизм ----------

    @Test
    void runsInParallelButNeverExceedsTheLimit() throws Exception {
        int parallelism = 3;
        String[] rows = new String[9];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(1000));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);

        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch together = new CountDownLatch(parallelism);
        AtomicBoolean reachedTogether = new AtomicBoolean(true);

        FileCopier copier = new FileCopier(opener((path, in) -> {
            max.accumulateAndGet(current.incrementAndGet(), Math::max);
            return new FilterInputStream(in) {
                private boolean first = true;

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (first) {
                        first = false;
                        waitForOthers(together, reachedTogether);
                    }
                    return super.read(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        current.decrementAndGet();
                    }
                }
            };
        }), FileCopier::newPool);

        CopyReport report = run(copier, parallelism);

        assertTrue(report.isSuccess(), report.toString());
        assertTrue(reachedTogether.get(), "три чтения должны были идти одновременно, а не по очереди");
        assertEquals(parallelism, max.get());
    }

    @Test
    void manyFilesNeverQueueMoreThanTheLimitAndNeverExceedItInParallel() throws Exception {
        int parallelism = 4;
        String[] rows = new String[200];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);

        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        AtomicReference<TrackingExecutor> executor = new AtomicReference<>();
        FileCopier copier = new FileCopier(opener((path, in) -> {
            max.accumulateAndGet(current.incrementAndGet(), Math::max);
            return new FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        current.decrementAndGet();
                    }
                }
            };
        }), threads -> {
            executor.set(new TrackingExecutor(threads));
            return executor.get();
        });

        CopyReport report = run(copier, parallelism);

        assertEquals(200, report.copied());
        assertTrue(max.get() <= parallelism, "одновременно копировалось: " + max.get());
        assertTrue(executor.get().maxQueued.get() <= parallelism,
                "в очереди исполнителя было задач: " + executor.get().maxQueued.get());
    }

    @Test
    void blockingWorkRunsOnOwnThreadsNotOnTheCommonPool() throws Exception {
        for (int i = 0; i < 4; i++) {
            write("f" + i + ".bin", random(100));
        }
        manifest("f0.bin,o0.bin", "f1.bin,o1.bin", "f2.bin,o2.bin", "f3.bin,o3.bin");
        Queue<String> threadNames = new ConcurrentLinkedQueue<>();
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                threadNames.add(Thread.currentThread().getName());
                return super.read(b, off, len);
            }
        }), FileCopier::newPool);

        run(copier, 2);

        assertFalse(threadNames.isEmpty());
        assertTrue(threadNames.stream().allMatch(name -> name.startsWith("copy-")), threadNames.toString());
    }

    @Test
    void resultsKeepManifestOrderEvenIfTheFirstFileIsTheSlowest() throws Exception {
        String[] rows = new String[6];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (first && path.getFileName().toString().equals("f0.bin")) {
                    first = false;
                    sleep(300);
                }
                return super.read(b, off, len);
            }
        }), FileCopier::newPool);

        CopyReport report = run(copier, 4);

        assertEquals(List.of("f0.bin", "f1.bin", "f2.bin", "f3.bin", "f4.bin", "f5.bin"), sources(report));
        assertTrue(report.results().get(0).duration().toMillis() >= 250, report.results().get(0).toString());
    }

    // ---------- жизненный цикл ----------

    @Test
    void futureCompletesOnlyAfterExecutorIsTerminated() throws Exception {
        write("a.txt", random(100));
        manifest("a.txt,a.txt");
        AtomicReference<ExecutorService> created = new AtomicReference<>();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
            created.set(new TrackingExecutor(threads));
            return created.get();
        });

        run(copier, 2);

        assertTrue(created.get().isTerminated());
    }

    @Test
    void manifestErrorCompletesExceptionallyAndStillStopsTheExecutor() {
        AtomicReference<ExecutorService> created = new AtomicReference<>();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
            created.set(new TrackingExecutor(threads));
            return created.get();
        });

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> copier.copyAll(dir.resolve("none.csv"), sourceDir, targetDir, 2).get(30, TimeUnit.SECONDS));

        assertInstanceOf(ManifestException.class, ex.getCause());
        assertTrue(created.get().isTerminated());
    }

    @Test
    void invalidParallelismIsRejectedBeforeAnythingStarts() {
        AtomicInteger executorsCreated = new AtomicInteger();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
            executorsCreated.incrementAndGet();
            return FileCopier.newPool(threads);
        });

        assertThrows(IllegalArgumentException.class, () -> copier.copyAll(manifest, sourceDir, targetDir, 0));
        assertThrows(IllegalArgumentException.class, () -> copier.copyAll(manifest, sourceDir, targetDir, -1));
        assertEquals(0, executorsCreated.get());
    }

    // ---------- усиленные проверки по итогам ревью ----------

    @Test
    void everyStageRunsOnOurExecutor() throws Exception {
        int files = 5;
        String[] rows = new String[files];
        for (int i = 0; i < files; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        AtomicReference<TrackingExecutor> executor = new AtomicReference<>();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
            executor.set(new TrackingExecutor(threads));
            return executor.get();
        });

        run(copier, 2);

        // 1 чтение манифеста + 1 запуск окна цепочек + по 3 блокирующие стадии на файл (проверка путей, копия, перенос)
        assertEquals(2 + 3L * files, executor.get().getCompletedTaskCount());
    }

    @Test
    void futureStaysIncompleteWhileExecutorIsStillTerminating() throws Exception {
        write("a.txt", random(100));
        manifest("a.txt,a.txt");
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> new TrackingExecutor(threads) {
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                awaiting.countDown();
                release.await();
                return super.awaitTermination(timeout, unit);
            }
        });

        CompletableFuture<CopyReport> future = copier.copyAll(manifest, sourceDir, targetDir, 2);
        assertTrue(awaiting.await(10, TimeUnit.SECONDS), "ожидание остановки исполнителя должно начаться");
        assertFalse(future.isDone(), "future не должен завершиться, пока исполнитель не остановлен");
        release.countDown();

        assertTrue(future.get(30, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void resultsKeepManifestOrderEvenIfTheFirstFileFinishesLast() throws Exception {
        String[] rows = new String[6];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        CountDownLatch othersClosed = new CountDownLatch(5);
        List<String> finishOrder = Collections.synchronizedList(new java.util.ArrayList<>());
        FileCopier copier = new FileCopier(opener((path, in) -> {
            String name = path.getFileName().toString();
            return new FilterInputStream(in) {
                private boolean first = true;

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (first && name.equals("f0.bin")) {
                        first = false;
                        awaitQuietly(othersClosed);   // первый файл ждёт, пока завершатся все остальные
                    }
                    return super.read(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        finishOrder.add(name);
                        if (!name.equals("f0.bin")) {
                            othersClosed.countDown();
                        }
                    }
                }
            };
        }), FileCopier::newPool);

        CopyReport report = run(copier, 4);

        assertEquals("f0.bin", finishOrder.get(finishOrder.size() - 1), "f0 должен закончиться последним: " + finishOrder);
        assertEquals(List.of("f0.bin", "f1.bin", "f2.bin", "f3.bin", "f4.bin", "f5.bin"), sources(report));
    }

    @Test
    void everyFileKeepsItsOwnContent() throws Exception {
        int files = 60;
        byte[][] data = new byte[files][];
        String[] rows = new String[files];
        for (int i = 0; i < files; i++) {
            data[i] = random(500 + i, i);
            write("f" + i + ".bin", data[i]);
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);

        CopyReport report = run(new FileCopier(), 6);

        assertEquals(files, report.copied());
        for (int i = 0; i < files; i++) {
            assertArrayEquals(data[i], Files.readAllBytes(targetDir.resolve("out/f" + i + ".bin")), "f" + i);
        }
    }

    @Test
    void writeErrorThroughCopyAllFailsOnlyThatFileAndLeavesNoTemp() throws Exception {
        for (int i = 0; i < 3; i++) {
            write("f" + i + ".bin", random(1000));
        }
        manifest("f0.bin,f0.bin", "f1.bin,f1.bin", "f2.bin,f2.bin");
        AtomicInteger outputs = new AtomicInteger();
        FileCopier copier = new FileCopier(opener(
                (path, in) -> in,
                (path, out) -> outputs.incrementAndGet() == 2 ? new BrokenOutput(out) : out),
                FileCopier::newPool);

        CopyReport report = run(copier, 1);   // один поток, чтобы "вторым" всегда был f1

        assertEquals(List.of(Status.COPIED, Status.FAILED, Status.COPIED), statuses(report));
        assertTrue(report.results().get(1).error().contains("write error"), report.results().get(1).error());
        assertFalse(Files.exists(targetDir.resolve("f1.bin")));
        assertEquals(List.of(), tempFilesIn(targetDir));
    }

    @Test
    void commitFailureThroughCopyAllFailsOnlyThatRow() throws Exception {
        write("a.bin", random(100));
        write("b.bin", random(100));
        Files.createDirectories(targetDir.resolve("x.bin"));   // конечный путь занят непустой папкой
        Files.writeString(targetDir.resolve("x.bin/keep.txt"), "keep");
        manifest("a.bin,x.bin", "b.bin,b.bin");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.FAILED, Status.COPIED), statuses(report));
        assertEquals(List.of(), tempFilesIn(targetDir));
        assertEquals("keep", Files.readString(targetDir.resolve("x.bin/keep.txt")));
    }

    @Test
    void badManifestsCompleteExceptionallyAndStopTheExecutor() throws Exception {
        for (String content : List.of("foo,bar\na,b\n", "source,target\nonly-one-value\n", "")) {
            Files.writeString(manifest, content);
            AtomicReference<ExecutorService> created = new AtomicReference<>();
            FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
                created.set(new TrackingExecutor(threads));
                return created.get();
            });

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> copier.copyAll(manifest, sourceDir, targetDir, 2).get(30, TimeUnit.SECONDS), content);

            assertInstanceOf(ManifestException.class, ex.getCause(), content);
            assertTrue(created.get().isTerminated(), content);
        }
    }

    @Test
    void emptyManifestStopsTheExecutor() throws Exception {
        manifest();
        AtomicReference<ExecutorService> created = new AtomicReference<>();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> {
            created.set(new TrackingExecutor(threads));
            return created.get();
        });

        run(copier, 3);

        assertTrue(created.get().isTerminated());
    }

    @Test
    void parallelismOneProcessesRowsInManifestOrder() throws Exception {
        String[] rows = new String[6];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        List<String> opened = Collections.synchronizedList(new java.util.ArrayList<>());
        FileCopier copier = new FileCopier(opener((path, in) -> {
            opened.add(path.getFileName().toString());
            return in;
        }), FileCopier::newPool);

        run(copier, 1);

        assertEquals(List.of("f0.bin", "f1.bin", "f2.bin", "f3.bin", "f4.bin", "f5.bin"), opened);
    }

    @Test
    void tenThousandFastFailingRowsDoNotOverflowTheStack() throws Exception {
        StringBuilder rows = new StringBuilder("source,target\n");
        for (int i = 0; i < 10_000; i++) {
            rows.append("missing").append(i).append(".bin,out/").append(i).append(".bin\n");
        }
        Files.writeString(manifest, rows);
        CopyControl control = new CopyControl();

        CopyReport report = new FileCopier().copyAll(manifest, sourceDir, targetDir, 8, control)
                .get(120, TimeUnit.SECONDS);

        assertEquals(10_000, report.failed());
        assertEquals(new CopyControl.Progress(10_000, 10_000, 0), control.progress());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void rejectingExecutorDoesNotHangTheFuture(int rejectAt) throws Exception {
        String[] rows = new String[6];
        for (int i = 0; i < rows.length; i++) {
            write("f" + i + ".bin", random(100));
            rows[i] = "f" + i + ".bin,out/f" + i + ".bin";
        }
        manifest(rows);
        AtomicInteger calls = new AtomicInteger();
        FileCopier copier = new FileCopier(StreamOpener.files(), threads -> new TrackingExecutor(threads) {
            @Override
            public void execute(Runnable command) {
                if (calls.incrementAndGet() == rejectAt) {
                    throw new RejectedExecutionException("simulated rejection #" + rejectAt);
                }
                super.execute(command);
            }
        });

        CopyReport report = run(copier, 2);   // главное: не зависает; отказ отрабатывается на вызывающем потоке

        assertEquals(6, report.results().size());
        assertTrue(report.isSuccess(), report.toString());
    }

    @Test
    void cancellingTheReturnedFutureCancelsTheRun() throws Exception {
        for (int i = 0; i < 6; i++) {
            write("f" + i + ".bin", random(1000));
        }
        manifest("f0.bin,f0.bin", "f1.bin,f1.bin", "f2.bin,f2.bin", "f3.bin,f3.bin", "f4.bin,f4.bin", "f5.bin,f5.bin");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ExecutorService> created = new AtomicReference<>();
        FileCopier copier = new FileCopier(opener((path, in) -> new FilterInputStream(in) {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (first) {
                    first = false;
                    started.countDown();
                    awaitQuietly(release);
                }
                return super.read(b, off, len);
            }
        }), threads -> {
            created.set(FileCopier.newPool(threads));
            return created.get();
        });

        CompletableFuture<CopyReport> future = copier.copyAll(manifest, sourceDir, targetDir, 1);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        future.cancel(true);
        release.countDown();

        assertTrue(created.get().awaitTermination(30, TimeUnit.SECONDS), "пул должен остановиться");
        assertEquals(List.of(), tempFilesIn(targetDir));
        for (int i = 0; i < 6; i++) {
            assertFalse(Files.exists(targetDir.resolve("f" + i + ".bin")), "f" + i + " не должен быть скопирован");
        }
    }

    @Test
    void directoryAsSourceFailsClearly() throws Exception {
        Files.createDirectories(sourceDir.resolve("folder"));
        write("ok.bin", random(10));
        manifest("folder,out.bin", "ok.bin,ok.bin");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.FAILED, Status.COPIED), statuses(report));
        assertTrue(report.results().get(0).error().contains("папка"), report.results().get(0).error());
        assertFalse(Files.exists(targetDir.resolve("out.bin")));
    }

    @Test
    void deviceAsSourceFailsOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "только для Windows");
        write("ok.bin", random(10));
        manifest("NUL,out.bin", "ok.bin,ok.bin");

        CopyReport report = run(new FileCopier(), 2);

        assertEquals(List.of(Status.FAILED, Status.COPIED), statuses(report));
        assertFalse(Files.exists(targetDir.resolve("out.bin")), "из устройства файл создаваться не должен");
    }

    // ---------- helpers ----------

    private CopyReport run(FileCopier copier, int parallelism) throws Exception {
        return copier.copyAll(manifest, sourceDir, targetDir, parallelism).get(60, TimeUnit.SECONDS);
    }

    private void write(String relative, byte[] data) throws IOException {
        Path path = sourceDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }

    private void manifest(String... rows) throws IOException {
        Files.writeString(manifest, "source,target\n" + String.join("\n", rows) + (rows.length > 0 ? "\n" : ""));
    }

    private static byte[] random(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static StreamOpener opener(BiFunction<Path, InputStream, InputStream> input,
                                       BiFunction<Path, OutputStream, OutputStream> output) {
        return new StreamOpener() {
            @Override
            public InputStream openInput(Path path) throws IOException {
                return input.apply(path, Files.newInputStream(path));
            }

            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return output.apply(path, Files.newOutputStream(path));
            }
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Выходной поток, который не умеет записывать. */
    private static final class BrokenOutput extends FilterOutputStream {
        BrokenOutput(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("simulated write error");
        }

        @Override
        public void write(int b) throws IOException {
            throw new IOException("simulated write error");
        }
    }

    private static List<String> sources(CopyReport report) {
        return report.results().stream().map(FileResult::source).toList();
    }

    private static List<Status> statuses(CopyReport report) {
        return report.results().stream().map(FileResult::status).toList();
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

    private static void waitForOthers(CountDownLatch together, AtomicBoolean reachedTogether) {
        together.countDown();
        try {
            if (!together.await(5, TimeUnit.SECONDS)) {
                reachedTogether.set(false);
            }
            Thread.sleep(30);   // держим слот занятым, чтобы чтения заведомо перекрывались
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

    /** Пул, который запоминает, сколько задач ждало в очереди в момент постановки новой. */
    private static class TrackingExecutor extends ThreadPoolExecutor {
        final AtomicInteger maxQueued = new AtomicInteger();

        TrackingExecutor(int threads) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), namedThreads());
        }

        @Override
        public void execute(Runnable command) {
            maxQueued.accumulateAndGet(getQueue().size(), Math::max);
            super.execute(command);
        }

        private static ThreadFactory namedThreads() {
            AtomicInteger counter = new AtomicInteger();
            return task -> new Thread(task, "copy-" + counter.incrementAndGet());
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
}
