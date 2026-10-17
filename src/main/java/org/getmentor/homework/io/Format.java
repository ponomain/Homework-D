package org.getmentor.homework.io;

import java.nio.charset.StandardCharsets;

final class Format {
    static final byte[] MAGIC = "SARC".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    static final int TYPE_TEXT = 1;
    static final int TYPE_BINARY = 2;
    static final int END_MARKER = 0;
    static final int BUFFER_SIZE = 32 * 1024; // 32 KiB
    static final int MAX_NAME_BYTES = 0xFFFF;

    private Format() {}
}
