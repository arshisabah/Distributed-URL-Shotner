package com.shortener.url.application;

import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.shortener.common.domain.exception.UrlNotFoundException;
import com.shortener.url.infrastructure.persistence.UrlRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Map;

/**
 * QR code generator backed by ZXing.
 * Results are cached in-process (Caffeine) — QR codes never change for a given URL.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QrCodeService {

    private static final int MIN_SIZE = 100;
    private static final int MAX_SIZE = 1000;

    private final UrlRepository urlRepo;

    @Value("${app.base-url:https://sho.rt}")
    private String baseUrl;

    /** L0 cache: keyed by "shortCode:size:format", 10 MB max, 1 h TTL */
    private final LoadingCache<String, byte[]> cache = Caffeine.newBuilder()
        .maximumWeight(10 * 1024 * 1024)
        .weigher((String k, byte[] v) -> v.length)
        .expireAfterWrite(Duration.ofHours(1))
        .recordStats()
        .build(this::generate);

    public byte[] getQrCode(String shortCode, int size, String format) {
        int clamped = Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);
        String cacheKey = shortCode + ":" + clamped + ":" + format;
        return cache.get(cacheKey);
    }

    private byte[] generate(String cacheKey) {
        String[] parts     = cacheKey.split(":");
        String   shortCode = parts[0];
        int      size      = Integer.parseInt(parts[1]);
        String   format    = parts[2];

        String url = urlRepo.findByShortCodeAndActiveTrue(shortCode)
            .map(u -> baseUrl + "/" + u.getShortCode())
            .orElseThrow(() -> new UrlNotFoundException(shortCode));

        try {
            Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 1,
                EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix matrix = new MultiFormatWriter()
                .encode(url, BarcodeFormat.QR_CODE, size, size, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, format.toUpperCase(), out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("QR generation failed for: " + shortCode, e);
        }
    }
}
