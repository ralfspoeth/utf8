package io.github.ralfspoeth.utf8;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Objects;

/**
 * A fast UTF-8 {@link Reader} implementation built on top of an {@link InputStream}.
 *
 * <p>The decoder uses Bj&ouml;rn H&ouml;hrmann's branchless DFA-based UTF-8 decoder
 * (see <a href="https://bjoern.hoehrmann.de/utf-8/decoder/dfa/">"Flexible and Economical
 * UTF-8 Decoder"</a>) plus an ASCII fast path that bypasses the DFA when the next byte
 * is plain ASCII and the decoder is in the accept state.
 *
 * <p>Supplementary code points (U+10000 and above) are returned as UTF-16 surrogate
 * pairs, exactly as the {@link Reader} contract requires.
 *
 * <p>This class is not thread-safe.
 *
 * <p>The {@code DFA_TABLE} below is derived from Bj&ouml;rn H&ouml;hrmann's decoder,
 * which is published under the MIT license:
 * <pre>
 * Copyright (c) 2008-2010 Bjoern Hoehrmann &lt;bjoern&#64;hoehrmann.de&gt;
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * </pre>
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

    // char buffer cache (used by the single-character read() path)
    private final char[] charBuf = new char[8_192];
    private int charBufPtr = 0, charBufLen = 0;

    // DFA state
    private int state = 0;
    private int codePoint = 0;

    // A low surrogate held over from a previous decode call, when the caller's
    // buffer was full after we wrote the high surrogate. -1 means none pending.
    private int pendingLowSurrogate = -1;

    /**
     * Creates a new {@code Utf8Reader} that decodes bytes from the given stream.
     *
     * @param in the source stream; never {@code null}
     */
    public Utf8Reader(InputStream in) {
        this.in = Objects.requireNonNull(in, "in");
    }

    /**
     * Reads a single UTF-16 code unit (a {@code char} value, returned as an
     * unsigned int in the range 0&ndash;65535) or {@code -1} on end-of-stream.
     * Supplementary code points are returned across two consecutive calls as a
     * high-surrogate / low-surrogate pair.
     */
    @Override
    public int read() throws IOException {
        if (charBufPtr >= charBufLen) {
            fillCharBuffer();
            if (charBufLen == -1) return -1;
        }
        return charBuf[charBufPtr++];
    }

    /**
     * Reads characters into a portion of an array.
     *
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} is negative
     *         or {@code off + len} is greater than {@code cbuf.length}
     */
    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) return 0;

        int totalCharsWritten = 0;

        // 1. First, drain anything left in the internal char cache.
        if (charBufPtr < charBufLen) {
            int available = charBufLen - charBufPtr;
            int toCopy = Math.min(available, len);
            System.arraycopy(charBuf, charBufPtr, cbuf, off, toCopy);
            charBufPtr += toCopy;
            totalCharsWritten += toCopy;
            if (totalCharsWritten == len) return totalCharsWritten;
        }

        // 2. Decode directly into the user's buffer for the remaining slots.
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

        // Drain a low surrogate held over from a previous call, if any.
        if (pendingLowSurrogate != -1 && charsDecoded < len) {
            target[off + charsDecoded++] = (char) pendingLowSurrogate;
            pendingLowSurrogate = -1;
            if (charsDecoded == len) return charsDecoded;
        }

        while (charsDecoded < len) {
            if (byteBufPtr >= byteBufLen) {
                byteBufLen = in.read(byteBuf);
                byteBufPtr = 0;
                if (byteBufLen == -1) {
                    if (state != 0) {
                        // Stream ended mid-sequence.
                        state = 0;
                        throw new IOException("Truncated UTF-8 sequence at end of stream.");
                    }
                    return (charsDecoded == 0) ? -1 : charsDecoded;
                }
            }

            while (byteBufPtr < byteBufLen && charsDecoded < len) {
                int b = byteBuf[byteBufPtr] & 0xFF;

                // ASCII fast path: most text is ASCII, and the DFA accepts every
                // byte < 0x80 in state 0 as a single-byte code point.
                if (state == 0 && b < 0x80) {
                    target[off + charsDecoded++] = (char) b;
                    byteBufPtr++;
                    continue;
                }

                byteBufPtr++;
                int type = DFA_TABLE[b];
                codePoint = (state == 0) ? (0xFF >> type) & b : (b & 0x3F) | (codePoint << 6);
                state = DFA_TABLE[256 + state + type];

                if (state == 0) {
                    if (codePoint <= 0xFFFF) {
                        target[off + charsDecoded++] = (char) codePoint;
                    } else {
                        target[off + charsDecoded++] = Character.highSurrogate(codePoint);
                        if (charsDecoded < len) {
                            target[off + charsDecoded++] = Character.lowSurrogate(codePoint);
                        } else {
                            // No room for the low surrogate. Stash it for the next call.
                            pendingLowSurrogate = Character.lowSurrogate(codePoint);
                            return charsDecoded;
                        }
                    }
                } else if (state == 12) {
                    state = 0;
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
