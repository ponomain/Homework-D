package org.homework.concurrent;

import java.time.Duration;
import java.util.Objects;

public record FileResult(String source, String target, Status status, long bytes, Duration duration, String error) {

    public FileResult {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes < 0");
        }
        if (status == Status.FAILED && error == null) {
            throw new IllegalArgumentException("для FAILED нужно описание ошибки");
        }
    }

    public static FileResult copied(String source, String target, long bytes, Duration duration) {
        return new FileResult(source, target, Status.COPIED, bytes, duration, null);
    }

    public static FileResult failed(String source, String target, Duration duration, String error) {
        return new FileResult(source, target, Status.FAILED, 0, duration, error);
    }

    public static FileResult cancelled(String source, String target) {
        return new FileResult(source, target, Status.CANCELLED, 0, Duration.ZERO, null);
    }
}
