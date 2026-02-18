package io.github.ralfspoeth.utf8;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundTripTest {

    @Test
    void testRoundTrip() throws Exception {
        // given
        String testInput = "Greek: ΩΣ; Japanese: こんにちは; Chinese: 你好; Emoji: 🚀";
        // when
        var out = new ByteArrayOutputStream();
        try (var writer = new Utf8Writer(out)) {
            writer.write(testInput);
        }

        byte[] utf8Bytes = out.toByteArray();
        var in = new ByteArrayInputStream(utf8Bytes);

        StringBuilder sb = new StringBuilder();
        try (var reader = new Utf8Reader(in)) {
            char[] buf = new char[1024];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
        }

        // then
        String result = sb.toString();
        assertAll(
                () -> assertEquals(testInput, result)
        );
    }
}
