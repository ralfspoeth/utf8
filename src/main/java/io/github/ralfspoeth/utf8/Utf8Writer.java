package io.github.ralfspoeth.utf8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Objects;

/**
 * A fast UTF-8 {@link Writer} implementation built on top of an {@link OutputStream}.
 *
 * <p>Encodes UTF-16 input (Java {@code char} values) into UTF-8 bytes. Supplementary
 * code points represented as a high-surrogate / low-surrogate pair are encoded as a
 * single 4-byte UTF-8 sequence, even when the pair is split across multiple
 * {@link #write(char[], int, int) write} or {@link #write(int) write(int)} calls.
 *
 * <p>Unpaired surrogates (a high surrogate followed by a non-low-surrogate, a low
 * surrogate without a preceding high surrogate, or a high surrogate left pending at
 * {@link #close()}) are replaced with the Unicode replacement character U+FFFD so the
 * output stays well-formed UTF-8.
 *
 * <p>This class is not thread-safe.
 */
public class Utf8Writer extends Writer {

    /** Minimum size of the internal byte buffer; large enough for one 4-byte sequence
     *  and a trailing replacement character without over-flushing. */
    private static final int MIN_BUFFER_SIZE = 16;

    private final OutputStream out;
    private final byte[] byteBuf;
    private int ptr = 0;

    // High surrogate held over from a previous call, waiting for its low surrogate.
    // -1 means none pending.
    private int pendingHighSurrogate = -1;

    /**
     * Creates a writer with an 8 KiB internal buffer.
     *
     * @param out the destination stream; never {@code null}
     */
    public Utf8Writer(OutputStream out) {
        this(out, 8192);
    }

    /**
     * Creates a writer with an internal buffer of the given size.
     *
     * @param out        the destination stream; never {@code null}
     * @param bufferSize the buffer size in bytes; must be at least {@value #MIN_BUFFER_SIZE}
     * @throws IllegalArgumentException if {@code bufferSize} is too small
     */
    public Utf8Writer(OutputStream out, int bufferSize) {
        this.out = Objects.requireNonNull(out, "out");
        if (bufferSize < MIN_BUFFER_SIZE) {
            throw new IllegalArgumentException(
                    "bufferSize must be >= " + MIN_BUFFER_SIZE + ", got " + bufferSize);
        }
        this.byteBuf = new byte[bufferSize];
    }

    /**
     * Writes a single UTF-16 code unit (the low 16 bits of {@code c}). Surrogates are
     * paired across consecutive calls when possible; an unpaired surrogate is written
     * as U+FFFD.
     */
    @Override
    public void write(int c) throws IOException {
        char ch = (char) c;

        if (pendingHighSurrogate != -1) {
            if (Character.isLowSurrogate(ch)) {
                int codePoint = Character.toCodePoint((char) pendingHighSurrogate, ch);
                pendingHighSurrogate = -1;
                writeSupplementary(codePoint);
                return;
            }
            // Pending high surrogate is now known to be unpaired -> emit replacement.
            pendingHighSurrogate = -1;
            writeReplacement();
            // Fall through and process ch normally.
        }

        if (Character.isHighSurrogate(ch)) {
            pendingHighSurrogate = ch;
            return;
        }
        if (Character.isLowSurrogate(ch)) {
            writeReplacement();
            return;
        }

        ensureSpace(3);
        if (ch < 0x80) {
            byteBuf[ptr++] = (byte) ch;
        } else if (ch < 0x800) {
            byteBuf[ptr++] = (byte) (0xc0 | (ch >> 6));
            byteBuf[ptr++] = (byte) (0x80 | (ch & 0x3f));
        } else {
            byteBuf[ptr++] = (byte) (0xe0 | (ch >> 12));
            byteBuf[ptr++] = (byte) (0x80 | ((ch >> 6) & 0x3f));
            byteBuf[ptr++] = (byte) (0x80 | (ch & 0x3f));
        }
    }

    /**
     * Writes a portion of an array of characters.
     *
     * @throws IndexOutOfBoundsException if {@code off} or {@code len} is negative or
     *         {@code off + len} is greater than {@code cbuf.length}
     */
    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        Objects.requireNonNull(cbuf);
        Objects.checkFromIndexSize(off, len, cbuf.length);
        int end = off + len;
        int i = off;

        // Handle a high surrogate held over from the previous call.
        if (pendingHighSurrogate != -1 && i < end) {
            char low = cbuf[i];
            if (Character.isLowSurrogate(low)) {
                int codePoint = Character.toCodePoint((char) pendingHighSurrogate, low);
                pendingHighSurrogate = -1;
                writeSupplementary(codePoint);
                i++;
            } else {
                pendingHighSurrogate = -1;
                writeReplacement();
                // Fall through; cbuf[i] is processed below.
            }
        }

        while (i < end) {
            char c = cbuf[i];
            ensureSpace(4);

            if (c < 0x80) {
                byteBuf[ptr++] = (byte) c;
                i++;
            } else if (c < 0x800) {
                byteBuf[ptr++] = (byte) (0xc0 | (c >> 6));
                byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
                i++;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < end) {
                    char low = cbuf[i + 1];
                    if (Character.isLowSurrogate(low)) {
                        int codePoint = Character.toCodePoint(c, low);
                        byteBuf[ptr++] = (byte) (0xf0 | (codePoint >> 18));
                        byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
                        byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
                        byteBuf[ptr++] = (byte) (0x80 | (codePoint & 0x3f));
                        i += 2;
                    } else {
                        // Unpaired high surrogate.
                        writeReplacement();
                        i++;
                    }
                } else {
                    // High surrogate at end of input; remember it for the next call.
                    pendingHighSurrogate = c;
                    i++;
                }
            } else if (Character.isLowSurrogate(c)) {
                writeReplacement();
                i++;
            } else {
                // 3-byte BMP character.
                byteBuf[ptr++] = (byte) (0xe0 | (c >> 12));
                byteBuf[ptr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                byteBuf[ptr++] = (byte) (0x80 | (c & 0x3f));
                i++;
            }
        }
    }

    private void writeSupplementary(int codePoint) throws IOException {
        ensureSpace(4);
        byteBuf[ptr++] = (byte) (0xf0 | (codePoint >> 18));
        byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 12) & 0x3f));
        byteBuf[ptr++] = (byte) (0x80 | ((codePoint >> 6) & 0x3f));
        byteBuf[ptr++] = (byte) (0x80 | (codePoint & 0x3f));
    }

    private void writeReplacement() throws IOException {
        // U+FFFD REPLACEMENT CHARACTER -> EF BF BD
        ensureSpace(3);
        byteBuf[ptr++] = (byte) 0xEF;
        byteBuf[ptr++] = (byte) 0xBF;
        byteBuf[ptr++] = (byte) 0xBD;
    }

    private void ensureSpace(int needed) throws IOException {
        if (ptr + needed > byteBuf.length) flushBuffer();
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
        // A high surrogate left pending at close has no partner -> replacement.
        if (pendingHighSurrogate != -1) {
            pendingHighSurrogate = -1;
            writeReplacement();
        }
        flush();
        out.close();
    }
}
