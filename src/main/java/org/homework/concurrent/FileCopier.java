package org.homework.concurrent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Копирует файлы по CSV-манифесту параллельно, цепочками CompletableFuture. */
public class FileCopier {

    private final StreamOpener opener;
    private final IntFunction<ExecutorService> executorFactory;

    public FileCopier() {
        this(StreamOpener.files(), FileCopier::newPool);
    }

    /** Для тестов: подмена потоков и исполнителя. */
    public FileCopier(StreamOpener opener, IntFunction<ExecutorService> executorFactory) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    /**
     * Результат завершается, когда все копирования закончились и исполнитель остановлен.
     * Ошибка манифеста завершает его исключением ManifestException, ошибка отдельного файла
     * превращается в FileResult со статусом FAILED.
     */
    public CompletableFuture<CopyReport> copyAll(Path manifest, Path sourceDirectory, Path targetDirectory,
                                                 int parallelism) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism должен быть не меньше 1: " + parallelism);
        }

        ExecutorService executor = executorFactory.apply(parallelism);
        PathGuard guard = new PathGuard(sourceDirectory, targetDirectory);
        FileTransfer transfer = new FileTransfer(opener);
        long start = System.nanoTime();

        CompletableFuture<CopyReport> work = CompletableFuture
                .supplyAsync(() -> ManifestReader.read(manifest), executor)
                .thenCompose(entries -> new CopyRun(entries, guard, transfer, executor, () -> false, result -> { })
                        .start(parallelism))
                .thenApply(results -> new CopyReport(results, Duration.ofNanos(System.nanoTime() - start)));

        return completeAfterTermination(work, executor);
    }

    /**
     * Завершает возвращаемый future только после остановки исполнителя. awaitTermination нельзя вызывать
     * из потока самого пула (он ждал бы сам себя), поэтому ждёт отдельный поток.
     */
    private static <T> CompletableFuture<T> completeAfterTermination(CompletableFuture<T> work, ExecutorService executor) {
        CompletableFuture<T> done = new CompletableFuture<>();
        work.whenComplete((value, error) -> {
            executor.shutdown();
            Thread finisher = new Thread(() -> {
                try {
                    while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        // ждём, пока все потоки пула закончат работу
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.completeExceptionally(e);
                    return;
                }
                if (error == null) {
                    done.complete(value);
                } else {
                    done.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                }
            }, "copy-finisher");
            finisher.start();
        });
        return done;
    }

    public static ExecutorService newPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> new Thread(task, "copy-" + counter.incrementAndGet()));
    }
}
