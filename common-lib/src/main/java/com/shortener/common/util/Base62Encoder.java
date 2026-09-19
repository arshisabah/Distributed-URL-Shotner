package com.shortener.common.util;

import com.shortener.common.domain.exception.InvalidShortCodeException;
import org.springframework.stereotype.Component;

/**
 * Encodes/decodes Snowflake IDs to/from 7-character Base62 strings.
 *
 * Alphabet : [0-9a-zA-Z]  (62 chars, URL-safe)
 * Capacity : 62^7 = 3.52 trillion unique codes
 */
@Component
public class Base62Encoder {

    private static final String ALPHABET   = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int    BASE       = 62;
    public  static final int    CODE_LENGTH = 7;

    public String encode(long id) {
        if (id < 0) throw new IllegalArgumentException("ID must be non-negative, got: " + id);
        char[] result = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            result[i] = ALPHABET.charAt((int) (id % BASE));
            id /= BASE;
        }
        return new String(result);
    }

    public long decode(String code) {
        validateFormat(code);
        long result = 0;
        for (char c : code.toCharArray()) {
            int pos = ALPHABET.indexOf(c);
            if (pos == -1) throw new InvalidShortCodeException("Invalid character '" + c + "' in code: " + code);
            result = result * BASE + pos;
        }
        return result;
    }

    public boolean isValidFormat(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (char c : code.toCharArray()) {
            if (ALPHABET.indexOf(c) == -1) return false;
        }
        return true;
    }

    private void validateFormat(String code) {
        if (!isValidFormat(code)) {
            throw new InvalidShortCodeException("Invalid short code format: " + code);
        }
    }
}
