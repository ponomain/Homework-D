package org.homework.concurrent;

public class ManifestException extends RuntimeException {

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
