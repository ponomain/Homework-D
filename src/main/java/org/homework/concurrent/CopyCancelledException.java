package org.homework.concurrent;

import java.io.IOException;

public class CopyCancelledException extends IOException {
    CopyCancelledException() {
        super("копирование отменено");
    }
}
