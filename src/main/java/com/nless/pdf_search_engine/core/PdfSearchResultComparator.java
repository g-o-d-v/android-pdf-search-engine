package com.nless.pdf_search_engine.core;

import android.graphics.RectF;

import com.nless.pdf_search_engine.coordinate.PdfCoordinateConverter;

import java.util.Comparator;

/** 文档阅读顺序的稳定比较器：页码、顶部、左侧、来源、resultId。 */
public final class PdfSearchResultComparator implements Comparator<PdfSearchResult> {

    public static final PdfSearchResultComparator INSTANCE = new PdfSearchResultComparator();

    private PdfSearchResultComparator() {
    }

    @Override
    public int compare(PdfSearchResult first, PdfSearchResult second) {
        if (first == second) return 0;
        if (first == null) return 1;
        if (second == null) return -1;

        int page = Integer.compare(first.pageIndex, second.pageIndex);
        if (page != 0) return page;

        RectF firstRect = firstPageRatioRect(first);
        RectF secondRect = firstPageRatioRect(second);
        if (firstRect != null && secondRect != null) {
            int top = Float.compare(firstRect.top, secondRect.top);
            if (top != 0) return top;
            int left = Float.compare(firstRect.left, secondRect.left);
            if (left != 0) return left;
        } else if (firstRect != null) {
            return -1;
        } else if (secondRect != null) {
            return 1;
        }

        String firstSource = first.source == null ? "" : first.source.name();
        String secondSource = second.source == null ? "" : second.source.name();
        int source = firstSource.compareTo(secondSource);
        if (source != 0) return source;

        return safe(first.resultId).compareTo(safe(second.resultId));
    }

    private RectF firstPageRatioRect(PdfSearchResult result) {
        if (result.rects == null || result.rects.isEmpty()) return null;
        RectF best = null;
        for (PdfSearchRect rect : result.rects) {
            RectF ratio = PdfCoordinateConverter.searchRectToPageRatio(rect);
            if (ratio == null) continue;
            if (best == null
                    || ratio.top < best.top
                    || (Float.compare(ratio.top, best.top) == 0 && ratio.left < best.left)) {
                best = ratio;
            }
        }
        return best;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
