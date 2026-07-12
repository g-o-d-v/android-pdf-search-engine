package com.nless.pdf_search_engine.pdfium;

/**
 * PDFium 对单页文本层的分析结果。
 */
public final class PdfiumTextPageInfo {

    public static final int STATUS_OK = 0;
    public static final int STATUS_PAGE_SIZE_FAILED = 1;
    public static final int STATUS_PAGE_LOAD_FAILED = 2;
    public static final int STATUS_TEXT_PAGE_LOAD_FAILED = 3;

    public final int pageIndex;
    public final float pageWidth;
    public final float pageHeight;
    public final int status;
    public final int rawCharCount;
    public final int visibleCharCount;
    public final int validBoxCount;
    public final int matchCount;

    public PdfiumTextPageInfo(
            int pageIndex,
            float pageWidth,
            float pageHeight,
            int status,
            int rawCharCount,
            int visibleCharCount,
            int validBoxCount,
            int matchCount
    ) {
        this.pageIndex = pageIndex;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.status = status;
        this.rawCharCount = rawCharCount;
        this.visibleCharCount = visibleCharCount;
        this.validBoxCount = validBoxCount;
        this.matchCount = matchCount;
    }

    public boolean isReadable() {
        return status == STATUS_OK;
    }

    public float getBoxCoverage() {
        if (visibleCharCount <= 0) return 0f;
        return Math.max(0f, Math.min(1f, validBoxCount / (float) visibleCharCount));
    }

    public boolean hasUsableTextLayer(int minVisibleCharacters, float minBoxCoverage) {
        if (!isReadable()) return false;
        int safeMinChars = Math.max(0, minVisibleCharacters);
        float safeCoverage = Math.max(0f, Math.min(1f, minBoxCoverage));
        return visibleCharCount >= safeMinChars && getBoxCoverage() >= safeCoverage;
    }
}
