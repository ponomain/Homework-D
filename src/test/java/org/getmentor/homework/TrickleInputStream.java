package org.getmentor.homework;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Random;

/** Отдаёт за один read(byte[]) от 1 до 3 байт. */
final class TrickleInputStream extends InputStream {
    private final byte[] data;
    private final Random rnd;
    private int pos;

    TrickleInputStream(byte[] data , long seed) {
        this.data = data;
        this.rnd = new Random(seed);
    }

    @Override
    public int read() throws IOException {
        return pos < data.length ? data[pos++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) return 0;
        if (pos >= data.length) return -1;
        int n = Math.min(Math.min(len, 1 + rnd.nextInt(3)), data.length - pos);
        System.arraycopy(data, pos, b, off, n);
        pos += n;
        return n;
    }
}
