package com.nless.pdf_search_engine.pdfium;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个逻辑文本层命中。跨行关键词仍只是一条结果，但可以包含多个矩形。
 */
public class PdfiumTextSearchResult {

    public final int pageIndex;
    public final int matchOrdinal;
    public final int matchStart;
    public final int matchLength;
    public final List<RectF> rectsInPdfPoint;

    /** 兼容旧调用方，等于 rectsInPdfPoint 的第一个矩形。 */
    @Deprecated
    public final RectF rectInPdfPoint;

    public final float pageWidth;
    public final float pageHeight;

    public PdfiumTextSearchResult(
            int pageIndex,
            int matchOrdinal,
            int matchStart,
            int matchLength,
            List<RectF> rectsInPdfPoint,
            float pageWidth,
            float pageHeight
    ) {
        this.pageIndex = pageIndex;
        this.matchOrdinal = matchOrdinal;
        this.matchStart = matchStart;
        this.matchLength = matchLength;
        this.rectsInPdfPoint = Collections.unmodifiableList(
                rectsInPdfPoint == null
                        ? new ArrayList<>()
                        : copyRects(rectsInPdfPoint)
        );
        this.rectInPdfPoint = this.rectsInPdfPoint.isEmpty()
                ? null
                : new RectF(this.rectsInPdfPoint.get(0));
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }

    /** 兼容旧的单矩形构造方法。 */
    public PdfiumTextSearchResult(
            int pageIndex,
            RectF rectInPdfPoint,
            float pageWidth,
            float pageHeight
    ) {
        this(
                pageIndex,
                0,
                -1,
                -1,
                rectInPdfPoint == null
                        ? Collections.emptyList()
                        : Collections.singletonList(rectInPdfPoint),
                pageWidth,
                pageHeight
        );
    }

    private static List<RectF> copyRects(List<RectF> source) {
        List<RectF> copy = new ArrayList<>(source.size());
        for (RectF rect : source) {
            if (rect != null) copy.add(new RectF(rect));
        }
        return copy;
    }
}
