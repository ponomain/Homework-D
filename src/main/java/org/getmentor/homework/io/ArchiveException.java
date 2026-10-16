package org.getmentor.homework.io;

import java.io.IOException;

public class ArchiveException extends IOException {
    public ArchiveException(String fileName, String check, String details) {
        super("[" + fileName + "] проверка '" + check + "' не пройдена: " + details);
    }

    public ArchiveException(String fileName, String check, String details, Throwable cause) {
        super("[" + fileName + "] проверка '" + check + "' не пройдена: " + details, cause);
    }
}
