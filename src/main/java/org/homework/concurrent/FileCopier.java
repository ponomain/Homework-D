package org.homework.concurrent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
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
        return copyAll(manifest, sourceDirectory, targetDirectory, parallelism, new CopyControl());
    }

    /** То же, но вызывающий получает отмену и прогресс через переданный control (по одному на запуск). */
    public CompletableFuture<CopyReport> copyAll(Path manifest, Path sourceDirectory, Path targetDirectory,
                                                 int parallelism, CopyControl control) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Objects.requireNonNull(control, "control");
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism должен быть не меньше 1: " + parallelism);
        }

        ExecutorService pool = executorFactory.apply(parallelism);
        PathGuard guard = new PathGuard(sourceDirectory, targetDirectory);
        FileTransfer transfer = new FileTransfer(opener);
        try {
            control.attach();   // последним, чтобы неудачный запуск не "сжигал" control
        } catch (IllegalStateException e) {
            pool.shutdown();
            throw e;
        }
        Executor executor = rejectionSafe(pool);
        long start = System.nanoTime();

        CompletableFuture<CopyReport> work = CompletableFuture
                .supplyAsync(() -> ManifestReader.read(manifest), executor)
                .thenComposeAsync(entries -> {
                    control.setTotal(entries.size());
                    return new CopyRun(entries, guard, transfer, executor, control::isCancelled, control::record)
                            .start(parallelism);
                }, executor)
                .thenApply(results -> new CopyReport(results, Duration.ofNanos(System.nanoTime() - start)));

        return completeAfterTermination(work, pool, control);
    }

    /**
     * Если исполнитель отказал принять задачу, CompletableFuture теряет исключение внутри себя, и зависимая
     * стадия остаётся незавершённой навсегда. Поэтому отказ обрабатываем как CallerRunsPolicy: выполняем
     * стадию на вызывающем потоке. Это патологический случай, но он лучше вечного зависания.
     */
    private static Executor rejectionSafe(ExecutorService pool) {
        return command -> {
            try {
                pool.execute(command);
            } catch (RuntimeException | Error e) {
                command.run();
            }
        };
    }

    /**
     * Завершает возвращаемый future только после остановки исполнителя. awaitTermination нельзя вызывать
     * из потока самого пула (он ждал бы сам себя), поэтому ждёт отдельный поток.
     *
     * <p>Вызов cancel() на возвращённом future считается отменой запуска (как control.cancel()):
     * сам future при этом завершается сразу, а копирование и остановка исполнителя дорабатывают в фоне.
     */
    private static <T> CompletableFuture<T> completeAfterTermination(CompletableFuture<T> work,
                                                                     ExecutorService executor,
                                                                     CopyControl control) {
        CompletableFuture<T> done = new CompletableFuture<>();
        done.whenComplete((value, error) -> {
            if (done.isCancelled()) {
                control.cancel();
            }
        });
        work.whenComplete((value, error) -> {
            executor.shutdown();
            try {
                Thread finisher = new Thread(() -> awaitTerminationThenComplete(done, executor, value, error),
                        "copy-finisher");
                finisher.start();
            } catch (Throwable e) {
                done.completeExceptionally(e);   // иначе future завис бы навсегда
            }
        });
        return done;
    }

    private static <T> void awaitTerminationThenComplete(CompletableFuture<T> done, ExecutorService executor,
                                                         T value, Throwable error) {
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
    }

    public static ExecutorService newPool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> new Thread(task, "copy-" + counter.incrementAndGet()));
    }
}
