package com.shortener.url.unit;

import com.shortener.common.domain.exception.InvalidShortCodeException;
import com.shortener.common.util.Base62Encoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Base62Encoder")
class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @Test
    @DisplayName("encode then decode is symmetric")
    void encodeDecodeShouldBeSymmetric() {
        long[] ids = {1L, 100L, 1_000_000L, 999_999_999L, Long.MAX_VALUE / 1000};
        for (long id : ids) {
            assertThat(encoder.decode(encoder.encode(id))).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("encoded codes are exactly 7 characters")
    void encodedCodesShouldBe7Characters() {
        assertThat(encoder.encode(1L)).hasSize(7);
        assertThat(encoder.encode(Long.MAX_VALUE / 1000)).hasSize(7);
    }

    @Test
    @DisplayName("uses only URL-safe alphanumeric characters")
    void encodedCodesShouldUseOnlyUrlSafeChars() {
        for (long i = 1; i <= 1000; i++) {
            assertThat(encoder.encode(i)).matches("[0-9a-zA-Z]{7}");
        }
    }

    @Test
    @DisplayName("produces unique codes for 100k consecutive IDs")
    void consecutiveIdsShouldProduceUniqueCodes() {
        Set<String> codes = new HashSet<>();
        for (long i = 1; i <= 100_000; i++) {
            codes.add(encoder.encode(i));
        }
        assertThat(codes).hasSize(100_000);
    }

    @Test
    @DisplayName("throws IllegalArgumentException for negative IDs")
    void negativeShouldThrow() {
        assertThatThrownBy(() -> encoder.encode(-1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "!@#$%^&", "ABCDEFGH", "123456"})
    @DisplayName("rejects invalid formats")
    void invalidFormatShouldThrow(String code) {
        assertThatThrownBy(() -> encoder.decode(code))
            .isInstanceOf(InvalidShortCodeException.class);
    }

    @Test
    @DisplayName("isValidFormat returns true for valid 7-char alphanumeric")
    void isValidFormatShouldReturnTrue() {
        assertThat(encoder.isValidFormat("aB3kX7Y")).isTrue();
        assertThat(encoder.isValidFormat("0000000")).isTrue();
        assertThat(encoder.isValidFormat("ZZZZZZZ")).isTrue();
    }

    @Test
    @DisplayName("isValidFormat returns false for invalid codes")
    void isValidFormatShouldReturnFalse() {
        assertThat(encoder.isValidFormat(null)).isFalse();
        assertThat(encoder.isValidFormat("abc")).isFalse();       // too short
        assertThat(encoder.isValidFormat("abc!@#$")).isFalse();   // bad chars
        assertThat(encoder.isValidFormat("abcdefgh")).isFalse();  // too long
    }
}
