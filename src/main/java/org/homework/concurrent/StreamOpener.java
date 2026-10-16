package org.homework.concurrent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public interface StreamOpener {
    InputStream openInput(Path path) throws IOException;

    OutputStream openOutput(Path path) throws IOException;

    static StreamOpener files() {
        return new StreamOpener() {
            @Override
            public InputStream openInput(Path path) throws IOException {
                return Files.newInputStream(path);
            }

            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return Files.newOutputStream(path);
            }
        };
    }
}
