package org.getmentor.homework;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

final class ArchiveBuilder {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bos);

    ArchiveBuilder header() throws IOException {
        return header(1);
    }

    ArchiveBuilder header(int version) throws IOException {
        out.write("SARC".getBytes(StandardCharsets.US_ASCII));
        out.writeByte(version);
        return this;
    }

    ArchiveBuilder entry(int type, String name, byte[] content) throws IOException {
        return raw(type, name.getBytes(StandardCharsets.UTF_8), content.length, content, crc(content));
    }

    ArchiveBuilder entry(int type, byte[] nameBytes, byte[] content) throws IOException {
        return raw(type, nameBytes, content.length, content, crc(content));
    }

    ArchiveBuilder raw(int type, byte[] nameBytes, long length, byte[] content, int crc) throws IOException {
        out.writeByte(type);
        out.writeShort(nameBytes.length);
        out.write(nameBytes);
        out.writeLong(length);
        out.write(content);
        out.writeInt(crc);
        return this;
    }
    ArchiveBuilder end() throws IOException {
        out.writeByte(0);
        return this;
    }
    ArchiveBuilder bytes(int... b) throws IOException {
        for (int x : b) {
            out.writeByte(x);
        }
        return this;
    }

    byte[] build() {
        return bos.toByteArray();
    }

    static int crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return (int) c.getValue();
    }
}
