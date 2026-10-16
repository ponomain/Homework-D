package org.homework.concurrent;

/**
 * Управление одним запуском copyAll: отмена и потокобезопасный прогресс.
 * Один объект на один запуск: повторное использование запрещено, счётчики иначе перемешались бы.
 *
 * <p>Отмена кооперативная: новые файлы не начинаются (получают статус CANCELLED), идущие копирования
 * останавливаются на границе буфера и удаляют временный файл. Возвращаемый future всё равно завершится
 * отчётом, а не CancellationException, и только после остановки исполнителя.
 */
public final class CopyControl {

    /**
     * Снимок прогресса. completedFiles считает все завершённые строки манифеста с любым статусом
     * (копирование, ошибка, отмена), copiedBytes только байты успешно скопированных файлов.
     * Поля снимка согласованы между собой.
     */
    public record Progress(int completedFiles, int totalFiles, long copiedBytes) {
    }

    private final Object lock = new Object();
    private volatile boolean cancelled;
    private boolean attached;
    private int totalFiles;
    private int completedFiles;
    private long copiedBytes;

    /** Просит остановить копирование. Можно вызывать из любого потока и до начала работы. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Текущий прогресс. totalFiles равен 0, пока манифест не прочитан. */
    public Progress progress() {
        synchronized (lock) {
            return new Progress(completedFiles, totalFiles, copiedBytes);
        }
    }

    void attach() {
        synchronized (lock) {
            if (attached) {
                throw new IllegalStateException("CopyControl уже использован другим запуском copyAll");
            }
            attached = true;
        }
    }

    void setTotal(int totalFiles) {
        synchronized (lock) {
            this.totalFiles = totalFiles;
        }
    }

    void record(FileResult result) {
        synchronized (lock) {
            completedFiles++;
            if (result.status() == Status.COPIED) {
                copiedBytes += result.bytes();
            }
        }
    }
}
