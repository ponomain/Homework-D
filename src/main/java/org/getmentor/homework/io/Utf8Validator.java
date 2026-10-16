package org.getmentor.homework.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

final class Utf8Validator {
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final ByteBuffer pending = ByteBuffer.allocate(8 * 1024);
    private final CharBuffer sink = CharBuffer.allocate(4 * 1024);

    void update(byte[] b, int off, int len) throws CharacterCodingException {
        int pos = off, end = off + len;
        while (pos < end) {
            int n = Math.min(pending.remaining(), end - pos);
            pending.put(b, pos, n);
            pos += n;
            pending.flip();
            drain(false);
            pending.compact();
        }
    }

    void finish() throws CharacterCodingException {
        pending.flip();
        drain(true);
        CoderResult r;
        do {
            sink.clear();
            r = decoder.flush(sink);
        } while (r.isOverflow());
    }

    private void drain(boolean endOfInput) throws CharacterCodingException {
        CoderResult r;
        do {
            sink.clear();
            r = decoder.decode(pending, sink, endOfInput);
            if (r.isError()) r.throwException();
        } while (r.isOverflow());
    }
}
