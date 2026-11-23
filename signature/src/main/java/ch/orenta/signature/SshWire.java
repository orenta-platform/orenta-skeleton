package ch.orenta.signature;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reading and writing of the SSH wire format (RFC 4251: uint32 and length-prefixed strings). */
final class SshWire {

    private SshWire() {
    }

    static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] data) {
            this.buffer = ByteBuffer.wrap(data);
        }

        byte[] raw(int length) throws SignatureException {
            if (length < 0 || buffer.remaining() < length) {
                throw new SignatureException("truncated data");
            }
            byte[] out = new byte[length];
            buffer.get(out);
            return out;
        }

        int uint32() throws SignatureException {
            if (buffer.remaining() < 4) {
                throw new SignatureException("truncated data");
            }
            return buffer.getInt();
        }

        byte[] string() throws SignatureException {
            return raw(uint32());
        }

        String utf8() throws SignatureException {
            return new String(string(), StandardCharsets.UTF_8);
        }

        boolean done() {
            return !buffer.hasRemaining();
        }
    }

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer raw(byte[] data) {
            out.writeBytes(data);
            return this;
        }

        Writer string(byte[] data) {
            out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
            out.writeBytes(data);
            return this;
        }

        Writer string(String value) {
            return string(value.getBytes(StandardCharsets.UTF_8));
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
