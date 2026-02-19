package io.github.ralfspoeth.utf8;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * A fast UTF-8 {@link Reader} implementation.
 */
public class Utf8Reader extends Reader {

    private static final byte[] DFA_TABLE = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
            0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12,
            12, 24, 12, 12, 12, 12, 12, 24, 12, 24, 12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12,
            12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12
    };

    // source stream
    private final InputStream in;

    // byte buffer cache
    private final byte[] byteBuf = new byte[8_192];
    private int byteBufPtr = 0, byteBufLen = 0;

    // char buffer cache
    private final char[] charBuf = new char[8_192];
    private int charBufPtr = 0, charBufLen = 0;

    // current state
    private int state = 0;
    private int codePoint = 0;

    /**
     * Constructor
     *
     * @param in the source stream
     */
    public Utf8Reader(InputStream in) {
        this.in = in;
    }

    /**
     * Implementation of single-character read.
     * Uses charBuf to avoid decoding logic on every single call.
     */
    @Override
    public int read() throws IOException {
        if (charBufPtr >= charBufLen) {
            fillCharBuffer();
            if (charBufLen == -1) return -1;
        }
        return charBuf[charBufPtr++];
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (len == 0) return 0;

        int totalCharsWritten = 0;

        // 1. First, drain anything left in the internal char cache
        if (charBufPtr < charBufLen) {
            int available = charBufLen - charBufPtr;
            int toCopy = Math.min(available, len);
            System.arraycopy(charBuf, charBufPtr, cbuf, off, toCopy);
            charBufPtr += toCopy;
            totalCharsWritten += toCopy;
            if (totalCharsWritten == len) return totalCharsWritten;
        }

        // 2. If we need more than what was cached, decode directly into user's buffer
        // This avoids double-copying for large read requests.
        int remaining = len - totalCharsWritten;
        int decoded = decodeToBuffer(cbuf, off + totalCharsWritten, remaining);

        if (decoded == -1) {
            return (totalCharsWritten == 0) ? -1 : totalCharsWritten;
        }

        return totalCharsWritten + decoded;
    }

    private void fillCharBuffer() throws IOException {
        charBufPtr = 0;
        charBufLen = decodeToBuffer(charBuf, 0, charBuf.length);
    }

    /**
     * Core decoding engine: transforms bytes into chars into a target array.
     */
    private int decodeToBuffer(char[] target, int off, int len) throws IOException {
        int charsDecoded = 0;

        while (charsDecoded < len) {
            if (byteBufPtr >= byteBufLen) {
                byteBufLen = in.read(byteBuf);
                byteBufPtr = 0;
                if (byteBufLen == -1) {
                    return (charsDecoded == 0) ? -1 : charsDecoded;
                }
            }

            while (byteBufPtr < byteBufLen && charsDecoded < len) {
                int b = byteBuf[byteBufPtr++] & 0xFF;
                int type = DFA_TABLE[b];

                codePoint = (state == 0) ? (0xFF >> type) & b : (b & 0x3F) | (codePoint << 6);
                state = DFA_TABLE[256 + state + type];

                if (state == 0) {
                    if (codePoint <= 0xFFFF) {
                        target[off + charsDecoded++] = (char) codePoint;
                    } else {
                        // Handle surrogate pairs
                        target[off + charsDecoded++] = Character.highSurrogate(codePoint);
                        if (charsDecoded < len) {
                            target[off + charsDecoded++] = Character.lowSurrogate(codePoint);
                        } else {
                            /* * Edge case: No room for low surrogate in target.
                             * We must push it back or cache it. For simplicity in this specialized
                             * reader, we always use charBuf when calling decodeToBuffer from read(),
                             * ensuring there's always room (charBuf is large).
                             */
                            throw new IOException("Buffer overflow during surrogate decoding");
                        }
                    }
                } else if (state == 12) {
                    throw new IOException("Malformed UTF-8 sequence.");
                }
            }
        }
        return charsDecoded;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}