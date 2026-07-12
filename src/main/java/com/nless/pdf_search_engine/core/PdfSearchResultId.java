package com.nless.pdf_search_engine.core;

import android.graphics.RectF;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 为逻辑命中生成稳定 ID。ID 不依赖结果列表下标，增量结果插入时不会改变。
 */
public final class PdfSearchResultId {

    private PdfSearchResultId() {
    }

    public static String create(
            String documentFingerprint,
            int pageIndex,
            PdfSearchSource source,
            int logicalOrdinal,
            String matchedText,
            java.util.List<PdfSearchRect> rects
    ) {
        StringBuilder raw = new StringBuilder();
        raw.append(documentFingerprint == null ? "" : documentFingerprint)
                .append('|').append(pageIndex)
                .append('|').append(source == null ? "UNKNOWN" : source.name())
                .append('|').append(matchedText == null ? "" : matchedText);

        boolean hasRect = false;
        if (rects != null) {
            for (PdfSearchRect item : rects) {
                if (item == null || item.rectInPdfPoint == null) continue;
                RectF rect = item.rectInPdfPoint;
                hasRect = true;
                raw.append('|').append(round(rect.left))
                        .append(',').append(round(rect.top))
                        .append(',').append(round(rect.right))
                        .append(',').append(round(rect.bottom));
            }
        }
        if (!hasRect) raw.append("|ordinal=").append(logicalOrdinal);
        return "psr_" + sha256Prefix(raw.toString(), 24);
    }

    private static String round(float value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String sha256Prefix(String value, int chars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) hex.append(String.format(Locale.US, "%02x", item));
            return hex.substring(0, Math.min(chars, hex.length()));
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
