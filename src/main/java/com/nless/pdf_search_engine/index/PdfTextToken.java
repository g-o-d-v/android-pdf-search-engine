package com.nless.pdf_search_engine.index;

import android.graphics.RectF;

/** 页面原始文本与几何坐标之间的最小映射单元。 */
public final class PdfTextToken {

    public final String text;
    public final int originalStart;
    public final int originalEnd;
    public final RectF rectInPdfPoint;
    public final int lineIndex;
    public final float confidence;

    public PdfTextToken(
            String text,
            int originalStart,
            int originalEnd,
            RectF rectInPdfPoint,
            int lineIndex,
            float confidence
    ) {
        this.text = text != null ? text : "";
        this.originalStart = Math.max(0, originalStart);
        this.originalEnd = Math.max(this.originalStart, originalEnd);
        this.rectInPdfPoint = rectInPdfPoint != null ? new RectF(rectInPdfPoint) : null;
        this.lineIndex = Math.max(0, lineIndex);
        this.confidence = confidence;
    }

    public boolean hasRect() {
        return rectInPdfPoint != null
                && rectInPdfPoint.width() > 0f
                && Math.abs(rectInPdfPoint.height()) > 0f;
    }

    public boolean isLineBreak() {
        return "\n".equals(text) || "\r".equals(text) || "\r\n".equals(text);
    }

    public boolean isWhitespace() {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }
}
