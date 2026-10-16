package org.homework.concurrent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Один запуск копирования: окно из parallelism цепочек CompletableFuture поверх списка строк манифеста.
 * Цепочка файла сама запускает следующую по завершении, поэтому в очереди исполнителя
 * не бывает больше parallelism задач, а не весь манифест.
 */
final class CopyRun {

    private record Resolved(Path source, Path target) {
    }

    private record Staged(Path temp, Path target, long bytes) {
    }

    private final List<ManifestEntry> entries;
    private final PathGuard guard;
    private final FileTransfer transfer;
    private final Executor executor;
    private final BooleanSupplier cancelled;
    private final Consumer<FileResult> listener;

    /** duplicateOf[i] это индекс более ранней строки с тем же целевым путём или -1. */
    private final int[] duplicateOf;
    private final List<CompletableFuture<FileResult>> promises;
    private final AtomicInteger cursor = new AtomicInteger();

    CopyRun(List<ManifestEntry> entries, PathGuard guard, FileTransfer transfer, Executor executor,
            BooleanSupplier cancelled, Consumer<FileResult> listener) {
        this.entries = entries;
        this.guard = guard;
        this.transfer = transfer;
        this.executor = executor;
        this.cancelled = cancelled;
        this.listener = listener;
        this.duplicateOf = findDuplicates(entries, guard);
        this.promises = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            promises.add(new CompletableFuture<>());
        }
    }

    /** Возвращает результаты в порядке строк манифеста. Завершается, когда закончились все цепочки. */
    CompletableFuture<List<FileResult>> start(int parallelism) {
        int initial = Math.min(parallelism, entries.size());
        for (int i = 0; i < initial; i++) {
            launchNext();
        }
        return CompletableFuture.allOf(promises.toArray(new CompletableFuture<?>[0]))
                .thenApply(ignored -> promises.stream().map(CompletableFuture::join).toList());
    }

    private void launchNext() {
        int index = cursor.getAndIncrement();
        if (index >= entries.size()) {
            return;
        }
        ManifestEntry entry = entries.get(index);
        processFile(index, entry).whenComplete((result, error) -> {
            promises.get(index).complete(error == null ? result : internalFailure(entry, error));
            launchNext();
        });
    }

    private CompletableFuture<FileResult> processFile(int index, ManifestEntry entry) {
        long start = System.nanoTime();
        return CompletableFuture
                .supplyAsync(() -> prepare(index, entry), executor)
                .thenApplyAsync(this::stage, executor)
                .thenApplyAsync(this::commit, executor)
                .handle((staged, error) -> toResult(entry, staged, error, start));
    }

    // ---------- стадии цепочки ----------

    private Resolved prepare(int index, ManifestEntry entry) {
        if (cancelled.getAsBoolean()) {
            throw new UncheckedIOException(new CopyCancelledException());
        }
        if (duplicateOf[index] >= 0) {
            throw new IllegalArgumentException("цель '" + entry.target() + "': этот целевой путь уже занят строкой "
                    + entries.get(duplicateOf[index]).line() + " манифеста");
        }
        return new Resolved(guard.resolveSource(entry.source()), guard.resolveTarget(entry.target()));
    }

    private Staged stage(Resolved resolved) {
        try {
            FileTransfer.TempCopy copy = transfer.copyToTemp(resolved.source(), resolved.target(), cancelled);
            return new Staged(copy.temp(), resolved.target(), copy.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Staged commit(Staged staged) {
        try {
            transfer.commit(staged.temp(), staged.target());
            return staged;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileResult toResult(ManifestEntry entry, Staged staged, Throwable error, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        FileResult result;
        if (error == null) {
            result = FileResult.copied(entry.source(), entry.target(), staged.bytes(), elapsed);
        } else {
            Throwable cause = unwrap(error);
            result = cause instanceof CopyCancelledException
                    ? FileResult.cancelled(entry.source(), entry.target())
                    : FileResult.failed(entry.source(), entry.target(), elapsed, describe(cause));
        }
        listener.accept(result);
        return result;
    }

    private static FileResult internalFailure(ManifestEntry entry, Throwable error) {
        return FileResult.failed(entry.source(), entry.target(), Duration.ZERO,
                "внутренняя ошибка: " + describe(unwrap(error)));
    }

    // ---------- вспомогательное ----------

    private static int[] findDuplicates(List<ManifestEntry> entries, PathGuard guard) {
        int[] duplicateOf = new int[entries.size()];
        Map<Path, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            duplicateOf[i] = -1;
            try {
                Path target = guard.lexicalTarget(entries.get(i).target());
                Integer first = firstSeen.putIfAbsent(target, i);
                if (first != null) {
                    duplicateOf[i] = first;
                }
            } catch (IllegalArgumentException ignored) {
                // недопустимый путь: ошибку выдаст цепочка самого файла
            }
        }
        return duplicateOf;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof UncheckedIOException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (t instanceof IllegalArgumentException && message != null) {
            return message;   // сообщения PathGuard и проверка дубликатов уже понятны
        }
        String name = t.getClass().getSimpleName();
        return message == null ? name : name + ": " + message;
    }
}
