package org.homework.concurrent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

public final class FileTransfer {

    static final int BUFFER_SIZE = 64 * 1024;

    private static final String TEMP_PREFIX = ".copy-";
    private static final String TEMP_SUFFIX = ".tmp";

    /** Временный файл с уже записанным содержимым и число записанных в него байт. */
    public record TempCopy(Path temp, long bytes) {
    }

    private final StreamOpener opener;

    public FileTransfer(StreamOpener opener) {
        this.opener = opener;
    }

    /** Шаг 1: копирует source во временный файл рядом с target и считает байты. */
    public TempCopy copyToTemp(Path source, Path target, BooleanSupplier cancelled) throws IOException {
        // Источник открываем первым: если его нет, не создаём в цели ни папки, ни временного файла
        try (InputStream in = opener.openInput(source)) {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
            try {
                long bytes;
                try (OutputStream out = opener.openOutput(temp)) {
                    bytes = transfer(in, out, cancelled);
                }   // здесь поток уже закрыт, и ошибка close() тоже попадёт в catch ниже
                return new TempCopy(temp, bytes);
            } catch (Throwable e) {   // и Error тоже: временный файл не должен оставаться ни при каких сбоях
                discardTemp(temp, e);
                throw e;
            }
        }
    }

    /** Шаг 2: переносит временный файл на конечный путь, заменяя существующий файл. */
    public void commit(Path temp, Path target) throws IOException {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            discardTemp(temp, e);
            throw e;
        }
    }

    private static long transfer(InputStream in, OutputStream out, BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CopyCancelledException();
            }
            int n = in.read(buffer);     // может вернуть меньше, чем размер буфера
            if (n == -1) {
                return total;
            }
            out.write(buffer, 0, n);
            total += n;
        }
    }

    private static void discardTemp(Path temp, Throwable primary) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException suppressed) {
            primary.addSuppressed(suppressed);   // не прячем исходную ошибку
        }
    }
}
