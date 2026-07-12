package com.nless.pdf_search_engine.pdfium;

import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** JNI float 协议解析器，独立于 native 生命周期，便于单元测试。 */
final class PdfiumTextProtocolParser {

    private static final String TAG = "PdfiumTextProtocol";
    private static final float DETAILED_MAGIC = -41001f;
    private static final int DETAILED_VERSION = 1;

    private PdfiumTextProtocolParser() {
    }

    static PdfiumTextSearchReport parseDetailed(float[] values) {
        if (values == null || values.length < 4) {
            return PdfiumTextSearchReport.empty();
        }
        int cursor = 0;
        if (Float.compare(values[cursor++], DETAILED_MAGIC) != 0) {
            Log.e(TAG, "unknown detailed text protocol magic");
            return PdfiumTextSearchReport.empty();
        }
        int version = safeNonNegativeInt(values[cursor++]);
        if (version != DETAILED_VERSION) {
            Log.e(TAG, "unsupported detailed text protocol version=" + version);
            return PdfiumTextSearchReport.empty();
        }

        int documentPageCount = safeNonNegativeInt(values[cursor++]);
        int pageRecordCount = Math.min(
                safeNonNegativeInt(values[cursor++]),
                Math.max(0, (values.length - cursor) / 8)
        );
        List<PdfiumTextPageInfo> pageInfos = new ArrayList<>(pageRecordCount);
        List<PdfiumTextSearchResult> results = new ArrayList<>();

        for (int pageRecord = 0; pageRecord < pageRecordCount; pageRecord++) {
            if (cursor + 8 > values.length) break;

            int pageIndex = safeNonNegativeInt(values[cursor++]);
            float pageWidth = values[cursor++];
            float pageHeight = values[cursor++];
            int status = safeNonNegativeInt(values[cursor++]);
            int rawCharCount = safeNonNegativeInt(values[cursor++]);
            int visibleCharCount = safeNonNegativeInt(values[cursor++]);
            int validBoxCount = safeNonNegativeInt(values[cursor++]);
            int declaredMatchCount = safeNonNegativeInt(values[cursor++]);

            int parsedMatches = 0;
            for (int matchIndex = 0; matchIndex < declaredMatchCount; matchIndex++) {
                if (cursor + 4 > values.length) {
                    cursor = values.length;
                    break;
                }
                int ordinal = safeNonNegativeInt(values[cursor++]);
                int matchStart = safeNonNegativeInt(values[cursor++]);
                int matchLength = safeNonNegativeInt(values[cursor++]);
                int rectCount = safeNonNegativeInt(values[cursor++]);
                if (rectCount > (values.length - cursor) / 4) {
                    cursor = values.length;
                    break;
                }

                List<RectF> rects = new ArrayList<>(rectCount);
                for (int rectIndex = 0; rectIndex < rectCount; rectIndex++) {
                    float left = values[cursor++];
                    float top = values[cursor++];
                    float right = values[cursor++];
                    float bottom = values[cursor++];
                    RectF rect = new RectF(left, top, right, bottom);
                    if (isFiniteRect(rect)) rects.add(rect);
                }

                if (!rects.isEmpty()) {
                    results.add(new PdfiumTextSearchResult(
                            pageIndex,
                            ordinal,
                            matchStart,
                            matchLength,
                            rects,
                            pageWidth,
                            pageHeight
                    ));
                    parsedMatches++;
                }
            }

            pageInfos.add(new PdfiumTextPageInfo(
                    pageIndex,
                    pageWidth,
                    pageHeight,
                    status,
                    rawCharCount,
                    visibleCharCount,
                    validBoxCount,
                    parsedMatches
            ));
            if (cursor >= values.length && pageRecord + 1 < pageRecordCount) break;
        }

        return new PdfiumTextSearchReport(documentPageCount, pageInfos, results);
    }

    private static int safeNonNegativeInt(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0;
        return Math.max(0, Math.round(value));
    }

    private static boolean isFiniteRect(RectF rect) {
        return rect != null
                && isFinite(rect.left)
                && isFinite(rect.top)
                && isFinite(rect.right)
                && isFinite(rect.bottom)
                && Math.abs(rect.right - rect.left) > 0.0001f
                && Math.abs(rect.top - rect.bottom) > 0.0001f;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
