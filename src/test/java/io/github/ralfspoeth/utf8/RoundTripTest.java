package io.github.ralfspoeth.utf8;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoundTripTest {

    // ---------- helpers ----------

    private static byte[] encode(String s) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var writer = new Utf8Writer(out)) {
            writer.write(s);
        }
        return out.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        var in = new ByteArrayInputStream(bytes);
        var sb = new StringBuilder();
        try (var reader = new Utf8Reader(in)) {
            char[] buf = new char[1024];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    private static String roundTrip(String s) throws IOException {
        return decode(encode(s));
    }

    // ---------- existing test ----------

    @Test
    void testRoundTrip() throws Exception {
        String testInput = "Greek: ΩΣ; Japanese: こんにちは; Chinese: 你好; Emoji: 🚀";
        assertEquals(testInput, roundTrip(testInput));
    }

    // ---------- writer correctness ----------

    @Test
    void testWriterMatchesJdkEncoding() throws Exception {
        String s = "ASCII / Größe / Ω / 漢字 / 🌍🚀";
        assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), encode(s));
    }

    @Test
    void testEachByteLength() throws Exception {
        // 1-byte (ASCII), 2-byte (Latin-1 supplement), 3-byte (BMP), 4-byte (supplementary)
        String s = "A©€🚀";
        byte[] expected = {
                (byte) 0x41,                                            // 'A'
                (byte) 0xC2, (byte) 0xA9,                               // '©'
                (byte) 0xE2, (byte) 0x82, (byte) 0xAC,                  // '€'
                (byte) 0xF0, (byte) 0x9F, (byte) 0x9A, (byte) 0x80      // '🚀'
        };
        assertArrayEquals(expected, encode(s));
        assertEquals(s, decode(expected));
    }

    @Test
    void testSurrogatePairSplitAcrossWriteCalls() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var w = new Utf8Writer(out)) {
            // High surrogate alone in the first call, low surrogate in the next.
            w.write(new char[]{'\uD83D'}, 0, 1);
            w.write(new char[]{'\uDE80'}, 0, 1);
        }
        assertArrayEquals(
                "🚀".getBytes(StandardCharsets.UTF_8),
                out.toByteArray());
    }

    @Test
    void testSurrogatePairAcrossWriteIntCalls() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var w = new Utf8Writer(out)) {
            w.write((int) '\uD83D');
            w.write((int) '\uDE80');
        }
        assertArrayEquals(
                "🚀".getBytes(StandardCharsets.UTF_8),
                out.toByteArray());
    }

    @Test
    void testUnpairedHighSurrogateBecomesReplacement() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var w = new Utf8Writer(out)) {
            w.write("A\uD83DB"); // high surrogate not followed by low surrogate
        }
        assertArrayEquals(
                "A�B".getBytes(StandardCharsets.UTF_8),
                out.toByteArray());
    }

    @Test
    void testUnpairedLowSurrogateBecomesReplacement() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var w = new Utf8Writer(out)) {
            w.write("A\uDE80B"); // low surrogate without preceding high
        }
        assertArrayEquals(
                "A�B".getBytes(StandardCharsets.UTF_8),
                out.toByteArray());
    }

    @Test
    void testTrailingHighSurrogateAtCloseBecomesReplacement() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var w = new Utf8Writer(out)) {
            w.write("A");
            w.write((int) '\uD83D'); // pending; never paired
        }
        assertArrayEquals(
                "A�".getBytes(StandardCharsets.UTF_8),
                out.toByteArray());
    }

    @Test
    void testWriterRejectsTooSmallBuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new Utf8Writer(new ByteArrayOutputStream(), 4));
    }

    @Test
    void testWriterValidatesArgs() throws Exception {
        try (var w = new Utf8Writer(new ByteArrayOutputStream())) {
            assertThrows(IndexOutOfBoundsException.class, () -> w.write(new char[3], -1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> w.write(new char[3], 0, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> w.write(new char[3], 2, 2));
        }
    }

    // ---------- reader correctness ----------

    @Test
    void testEmptyInput() throws Exception {
        try (var r = new Utf8Reader(new ByteArrayInputStream(new byte[0]))) {
            assertEquals(-1, r.read());
            assertEquals(-1, r.read(new char[8]));
        }
    }

    @Test
    void testReadSingleChar() throws Exception {
        byte[] bytes = "Aé€🚀".getBytes(StandardCharsets.UTF_8);
        var sb = new StringBuilder();
        try (var r = new Utf8Reader(new ByteArrayInputStream(bytes))) {
            int c;
            while ((c = r.read()) != -1) sb.append((char) c);
        }
        assertEquals("Aé€🚀", sb.toString());
    }

    @Test
    void testOddLengthReadAtSupplementaryBoundary() throws Exception {
        // Two supplementary code points back-to-back; read 1 char at a time.
        // Each emoji is 2 chars in UTF-16, so any odd-length read schedule will
        // land mid-pair. This used to throw; now it must not.
        String s = "🚀🌞"; // 🚀🌞
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);

        var sb = new StringBuilder();
        try (var r = new Utf8Reader(new ByteArrayInputStream(bytes))) {
            char[] one = new char[1];
            int n;
            while ((n = r.read(one, 0, 1)) != -1) {
                sb.append(one, 0, n);
            }
        }
        assertEquals(s, sb.toString());
    }

    @Test
    void testMalformedUtf8Throws() {
        // 0xC0 0x80 is an overlong encoding of NUL — rejected by Hoehrmann's DFA.
        byte[] bad = {(byte) 0xC0, (byte) 0x80};
        assertThrows(IOException.class, () -> {
            try (var r = new Utf8Reader(new ByteArrayInputStream(bad))) {
                while (r.read() != -1);// drain
            }
        });
    }

    @Test
    void testTruncatedSequenceAtEofThrows() {
        // Lead byte for a 2-byte sequence with no continuation byte.
        byte[] bad = {(byte) 0xC2};
        assertThrows(IOException.class, () -> {
            try (var r = new Utf8Reader(new ByteArrayInputStream(bad))) {
                while (r.read() != -1);// drain
            }
        });
    }

    @Test
    void testReaderValidatesArgs() throws Exception {
        try (var r = new Utf8Reader(new ByteArrayInputStream(new byte[]{'a'}))) {
            assertThrows(IndexOutOfBoundsException.class, () -> r.read(new char[3], -1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> r.read(new char[3], 0, 4));
            assertThrows(IndexOutOfBoundsException.class, () -> r.read(new char[3], 2, 2));
        }
    }

    @Test
    void testZeroLengthReadReturnsZero() throws Exception {
        try (var r = new Utf8Reader(new ByteArrayInputStream(new byte[]{'a'}))) {
            assertEquals(0, r.read(new char[4], 0, 0));
        }
    }

    // ---------- size / scale ----------

    @Test
    void testLargeAsciiInput() throws Exception {
        // Big enough to cross the 8 KiB byte buffer multiple times.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_000; i++) sb.append((char) ('a' + (i % 26)));
        String s = sb.toString();
        assertEquals(s, roundTrip(s));
    }

    @Test
    void testLargeMixedInput() throws Exception {
        StringBuilder sb = new StringBuilder();
        // ASCII + 2-byte + 3-byte + 4-byte
        sb.repeat("Aé€🚀", 5_000);
        String s = sb.toString();
        assertEquals(s, roundTrip(s));
    }

    @Test
    void testRoundTripCoversAllBmpCodePoints() throws Exception {
        // Every BMP code point except the surrogate range.
        StringBuilder sb = new StringBuilder();
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            sb.append((char) cp);
        }
        String s = sb.toString();
        assertEquals(s, roundTrip(s));
    }

    @Test
    void testAllAssertions() {
        // A couple of things in one go for parity with the previous test style.
        assertAll(
                () -> assertEquals("", roundTrip("")),
                () -> assertEquals("hello", roundTrip("hello")),
                () -> assertEquals(" ", roundTrip(" ")),
                () -> assertEquals("￿", roundTrip("￿"))
        );
    }
}
