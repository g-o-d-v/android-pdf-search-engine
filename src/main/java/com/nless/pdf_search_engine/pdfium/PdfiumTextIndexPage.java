package com.nless.pdf_search_engine.pdfium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 与关键词无关的 PDFium 单页文本提取结果。 */
public final class PdfiumTextIndexPage {
    public final int pageIndex;
    public final float pageWidth;
    public final float pageHeight;
    public final int status;
    public final int rawCharCount;
    public final int visibleCharCount;
    public final int validBoxCount;
    public final List<PdfiumTextIndexChar> characters;

    public PdfiumTextIndexPage(
            int pageIndex,
            float pageWidth,
            float pageHeight,
            int status,
            int rawCharCount,
            int visibleCharCount,
            int validBoxCount,
            List<PdfiumTextIndexChar> characters
    ) {
        this.pageIndex = pageIndex;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.status = status;
        this.rawCharCount = Math.max(0, rawCharCount);
        this.visibleCharCount = Math.max(0, visibleCharCount);
        this.validBoxCount = Math.max(0, validBoxCount);
        this.characters = Collections.unmodifiableList(
                characters == null ? new ArrayList<>() : new ArrayList<>(characters)
        );
    }
}
