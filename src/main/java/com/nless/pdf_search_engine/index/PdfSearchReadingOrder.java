package com.nless.pdf_search_engine.index;

import android.graphics.RectF;

import com.nless.pdf_search_engine.coordinate.PdfCoordinateConverter;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchResultComparator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 常见单栏/双栏文档的稳定阅读顺序。 */
public final class PdfSearchReadingOrder {

    private PdfSearchReadingOrder() {
    }

    public static void sort(List<PdfSearchResult> results, PdfSearchOptions options) {
        if (results == null || results.size() < 2) return;
        PdfSearchOptions request = options != null ? options : new PdfSearchOptions();
        if (!request.detectMultiColumnLayout) {
            results.sort(PdfSearchResultComparator.INSTANCE);
            return;
        }

        Map<Integer, List<PdfSearchResult>> byPage = new TreeMap<>();
        for (PdfSearchResult result : results) {
            if (result == null) continue;
            byPage.computeIfAbsent(result.pageIndex, ignored -> new ArrayList<>()).add(result);
        }

        List<PdfSearchResult> ordered = new ArrayList<>(results.size());
        for (List<PdfSearchResult> pageResults : byPage.values()) {
            float split = detectTwoColumnSplit(
                    pageResults,
                    Math.max(0.08f, Math.min(0.40f, request.multiColumnMinGapRatio))
            );
            if (Float.isNaN(split)) {
                pageResults.sort(PdfSearchResultComparator.INSTANCE);
            } else {
                pageResults.sort(new ColumnComparator(split));
            }
            ordered.addAll(pageResults);
        }
        results.clear();
        results.addAll(ordered);
    }

    private static float detectTwoColumnSplit(
            List<PdfSearchResult> pageResults,
            float minGap
    ) {
        List<RectF> rects = new ArrayList<>();
        for (PdfSearchResult result : pageResults) {
            RectF rect = firstRatioRect(result);
            if (rect != null && rect.width() < 0.48f) rects.add(rect);
        }
        if (rects.size() < 4) return Float.NaN;
        rects.sort(Comparator.comparingDouble(RectF::centerX));

        float bestGap = 0f;
        int bestIndex = -1;
        for (int i = 0; i < rects.size() - 1; i++) {
            float gap = rects.get(i + 1).centerX() - rects.get(i).centerX();
            if (gap > bestGap) {
                bestGap = gap;
                bestIndex = i;
            }
        }
        if (bestIndex < 1 || rects.size() - bestIndex - 1 < 2 || bestGap < minGap) {
            return Float.NaN;
        }

        float split = (rects.get(bestIndex).centerX()
                + rects.get(bestIndex + 1).centerX()) * 0.5f;
        float leftMaxRight = 0f;
        float rightMinLeft = 1f;
        for (RectF rect : rects) {
            if (rect.centerX() < split) leftMaxRight = Math.max(leftMaxRight, rect.right);
            else rightMinLeft = Math.min(rightMinLeft, rect.left);
        }
        if (rightMinLeft - leftMaxRight < minGap * 0.35f) return Float.NaN;
        return split;
    }

    private static RectF firstRatioRect(PdfSearchResult result) {
        RectF best = null;
        if (result == null || result.rects == null) return null;
        for (PdfSearchRect item : result.rects) {
            RectF ratio = PdfCoordinateConverter.searchRectToPageRatio(item);
            if (ratio == null) continue;
            if (best == null || ratio.top < best.top
                    || (Float.compare(ratio.top, best.top) == 0 && ratio.left < best.left)) {
                best = ratio;
            }
        }
        return best;
    }

    private static final class ColumnComparator implements Comparator<PdfSearchResult> {
        private final float split;

        ColumnComparator(float split) {
            this.split = split;
        }

        @Override
        public int compare(PdfSearchResult a, PdfSearchResult b) {
            if (a.pageIndex != b.pageIndex) return Integer.compare(a.pageIndex, b.pageIndex);
            RectF ar = firstRatioRect(a);
            RectF br = firstRatioRect(b);
            if (ar == null || br == null) return PdfSearchResultComparator.INSTANCE.compare(a, b);
            int ac = ar.centerX() < split ? 0 : 1;
            int bc = br.centerX() < split ? 0 : 1;
            int column = Integer.compare(ac, bc);
            if (column != 0) return column;
            int top = Float.compare(ar.top, br.top);
            if (top != 0) return top;
            int left = Float.compare(ar.left, br.left);
            if (left != 0) return left;
            return safe(a.resultId).compareTo(safe(b.resultId));
        }

        private String safe(String value) {
            return value != null ? value : "";
        }
    }
}
