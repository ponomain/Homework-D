package org.getmentor.homework.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;

public class StreamIo {
    private StreamIo() {}

    static void copyExactly(InputStream in, OutputStream out, long length,
                            CRC32 crc, Utf8Validator utf8, String fileName) throws IOException {
        byte[] buf = new byte[Format.BUFFER_SIZE];
        long left = length;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new ArchiveException(fileName, "целостность содержимого",
                        "обрыв данных, не хватает " + left + " байт");
            }
            if (n == 0) continue;
            crc.update(buf, 0, n);
            if (utf8 != null) utf8.update(buf, 0, n);
            out.write(buf, 0, n);
            left -= n;
        }
    }
}
