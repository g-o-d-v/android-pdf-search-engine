package com.nless.pdf_search_engine.index;

import android.graphics.RectF;

import com.nless.pdf_search_engine.coordinate.PdfCoordinateConverter;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 文本层与 OCR 结果的几何去重。 */
public final class PdfSearchResultDeduplicator {

    private PdfSearchResultDeduplicator() {
    }

    public static List<PdfSearchResult> deduplicate(
            List<PdfSearchResult> input,
            PdfSearchOptions options
    ) {
        List<PdfSearchResult> output = new ArrayList<>();
        if (input == null) return output;
        PdfSearchOptions request = options != null ? options : new PdfSearchOptions();
        float threshold = Math.max(0f, Math.min(1f, request.crossSourceDeduplicationIou));

        for (PdfSearchResult candidate : input) {
            if (candidate == null) continue;
            int duplicateIndex = -1;
            for (int i = 0; i < output.size(); i++) {
                PdfSearchResult existing = output.get(i);
                if (isDuplicate(existing, candidate, threshold)) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                output.add(candidate);
            } else {
                PdfSearchResult existing = output.get(duplicateIndex);
                if (prefer(candidate, existing)) output.set(duplicateIndex, candidate);
            }
        }
        return output;
    }

    private static boolean isDuplicate(
            PdfSearchResult first,
            PdfSearchResult second,
            float threshold
    ) {
        if (first.pageIndex != second.pageIndex) return false;
        if (!normalize(first.matchedText).equals(normalize(second.matchedText))) return false;
        RectF a = unionRatio(first);
        RectF b = unionRatio(second);
        return a != null && b != null && iou(a, b) >= threshold;
    }

    private static boolean prefer(PdfSearchResult candidate, PdfSearchResult existing) {
        if (candidate.source == PdfSearchSource.PDF_TEXT_LAYER
                && existing.source != PdfSearchSource.PDF_TEXT_LAYER) {
            return true;
        }
        if (candidate.source != PdfSearchSource.PDF_TEXT_LAYER
                && existing.source == PdfSearchSource.PDF_TEXT_LAYER) {
            return false;
        }
        return candidate.confidence > existing.confidence;
    }

    private static RectF unionRatio(PdfSearchResult result) {
        RectF union = null;
        if (result.rects == null) return null;
        for (PdfSearchRect item : result.rects) {
            RectF ratio = PdfCoordinateConverter.searchRectToPageRatio(item);
            if (ratio == null) continue;
            if (union == null) union = new RectF(ratio);
            else union.union(ratio);
        }
        return union;
    }

    private static float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float areaA = Math.max(0f, a.width()) * Math.max(0f, a.height());
        float areaB = Math.max(0f, b.width()) * Math.max(0f, b.height());
        float union = areaA + areaB - intersection;
        return union > 0f ? intersection / union : 0f;
    }

    private static String normalize(String value) {
        String source = value != null ? value : "";
        return Normalizer.normalize(source, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
