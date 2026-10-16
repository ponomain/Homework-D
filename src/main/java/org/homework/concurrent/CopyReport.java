package org.homework.concurrent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record CopyReport(List<FileResult> results, Duration duration) {

    public CopyReport {
        results = List.copyOf(results);
        Objects.requireNonNull(duration, "duration");
    }

    public int count(Status status) {
        int n = 0;
        for (FileResult result : results) {
            if (result.status() == status) {
                n++;
            }
        }
        return n;
    }

    public int copied() {
        return count(Status.COPIED);
    }

    public int failed() {
        return count(Status.FAILED);
    }

    public int cancelled() {
        return count(Status.CANCELLED);
    }

    /** Сумма байт только успешно скопированных файлов. */
    public long totalBytes() {
        long sum = 0;
        for(FileResult result: results) {
            if (result.status() == Status.COPIED) {
                sum += result.bytes();
            }
        }
        return sum;
    }

    public boolean isSuccess() {
        return copied() == results.size();
    }
}
