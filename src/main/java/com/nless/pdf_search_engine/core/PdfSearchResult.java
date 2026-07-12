package com.nless.pdf_search_engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PdfSearchResult {

    /** 稳定逻辑结果 ID，不等于列表下标。 */
    public final String resultId;
    public final String keyword;
    public final int pageIndex;
    public final PdfSearchSource source;

    /** 一个逻辑命中可以包含多个矩形，例如跨行关键词。 */
    public final List<PdfSearchRect> rects;

    /** 页面原始文本中的实际命中内容。 */
    public final String matchedText;

    /** 在当前页面原始文本序列中的起点；未知时为 -1。 */
    public final int matchStart;

    /** 在当前页面原始文本序列中的长度；未知时为 -1。 */
    public final int matchLength;

    /** 包含前后文的结果摘要。 */
    public final String contextText;

    /** 命中在 contextText 中的起点。 */
    public final int matchStartInContext;

    /** 命中在 contextText 中的结束位置（不包含）。 */
    public final int matchEndInContext;

    /** 来源置信度；文本层通常为 1，未知时为 -1。 */
    public final float confidence;

    public PdfSearchResult(
            String resultId,
            String keyword,
            int pageIndex,
            PdfSearchSource source,
            List<PdfSearchRect> rects,
            String matchedText,
            int matchStart,
            int matchLength,
            String contextText,
            int matchStartInContext,
            int matchEndInContext,
            float confidence
    ) {
        this.resultId = resultId;
        this.keyword = keyword;
        this.pageIndex = pageIndex;
        this.source = source;
        this.rects = Collections.unmodifiableList(
                rects == null ? new ArrayList<>() : new ArrayList<>(rects)
        );
        this.matchedText = matchedText;
        this.matchStart = matchStart;
        this.matchLength = matchLength;
        this.contextText = contextText;
        this.matchStartInContext = matchStartInContext;
        this.matchEndInContext = matchEndInContext;
        this.confidence = confidence;
    }

    public PdfSearchResult(
            String resultId,
            String keyword,
            int pageIndex,
            PdfSearchSource source,
            List<PdfSearchRect> rects,
            String matchedText,
            int matchStart,
            int matchLength
    ) {
        this(
                resultId,
                keyword,
                pageIndex,
                source,
                rects,
                matchedText,
                matchStart,
                matchLength,
                matchedText,
                0,
                matchedText != null ? matchedText.length() : 0,
                source == PdfSearchSource.PDF_TEXT_LAYER ? 1f : -1f
        );
    }

    /** 兼容旧版构造方法。 */
    public PdfSearchResult(
            String keyword,
            int pageIndex,
            PdfSearchSource source,
            List<PdfSearchRect> rects,
            String matchedText
    ) {
        this(null, keyword, pageIndex, source, rects, matchedText, -1, -1);
    }

    public PdfSearchResult withResultId(String value) {
        return new PdfSearchResult(
                value,
                keyword,
                pageIndex,
                source,
                rects,
                matchedText,
                matchStart,
                matchLength,
                contextText,
                matchStartInContext,
                matchEndInContext,
                confidence
        );
    }
}
