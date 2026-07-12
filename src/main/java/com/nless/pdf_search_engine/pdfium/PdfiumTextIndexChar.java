package com.nless.pdf_search_engine.pdfium;

import android.graphics.RectF;

/** PDFium 文本页中的单个 Unicode 字符及其字符框。 */
public final class PdfiumTextIndexChar {
    public final int codePoint;
    public final int sourceCharIndex;
    public final RectF rectInPdfPoint;

    public PdfiumTextIndexChar(int codePoint, int sourceCharIndex, RectF rectInPdfPoint) {
        this.codePoint = codePoint;
        this.sourceCharIndex = sourceCharIndex;
        this.rectInPdfPoint = rectInPdfPoint != null ? new RectF(rectInPdfPoint) : null;
    }
}
