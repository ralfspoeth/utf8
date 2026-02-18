package io.github.ralfspoeth.utf8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

public class Utf8Writer extends Writer {
    private final OutputStream out;
    private final byte[] byteBuf;
    private int ptr = 0;

    public Utf8Writer(OutputStream out) {
        this(out, 8192); // Standard 8KB buffer
    }

    public Utf8Writer(OutputStream out, int bufferSize) {
        this.out = out;
        this.byteBuf = new byte[bufferSize];
    }

    @Override
    public void write(int c) throws IOException {
        // Ensure space for up to 3 bytes (max for a single char/UTF-16 code unit)
        if (ptr + 3 > byteBuf.length) flushBuffer();

        if (c < 0x80) {
            byteBuf[ptr++] = (byte) c;
        } else if (c < 0x800) {
            byteBuf[ptr++] = (byte) (0xc0 | (c >> 6));
            byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
        } else {
            // Note: This doesn't handle surrogate pairs (4-byte)
            // Use write(char[], int, int) for full surrogate support
            byteBuf[ptr++] = (byte) (0xe0 | (c >> 12));
            byteBuf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
            byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        int end = off + len;
        for (int i = off; i < end; i++) {
            char c = cbuf[i];

            // 1. Check for buffer space (max 4 bytes for surrogate pairs)
            if (ptr + 4 > byteBuf.length) flushBuffer();

            if (c < 0x80) {
                // ASCII - Common case
                byteBuf[ptr++] = (byte) c;
            } else if (c < 0x800) {
                // 2-byte sequence
                byteBuf[ptr++] = (byte) (0xc0 | (c >> 6));
                byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
            } else if (!Character.isSurrogate(c)) {
                // 3-byte sequence
                byteBuf[ptr++] = (byte) (0xe0 | (c >> 12));
                byteBuf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
            } else {
                // 4-byte sequence (Surrogate Pairs)
                int codePoint = Character.codePointAt(cbuf, i, end);
                if (Character.isSupplementaryCodePoint(codePoint)) {
                    byteBuf[ptr++] = (byte) (0xf0 | (codePoint >> 18));
                    byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
                    byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
                    byteBuf[ptr++] = (byte) (0x80 | (codePoint & 0x3f));
                    i++; // Skip the low surrogate
                } else {
                    // Malformed surrogate
                    byteBuf[ptr++] = (byte) '?';
                }
            }
        }
    }

    private void flushBuffer() throws IOException {
        if (ptr > 0) {
            out.write(byteBuf, 0, ptr);
            ptr = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        out.close();
    }
}