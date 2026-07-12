package com.nless.pdf_search_engine.core;

import android.graphics.RectF;

/**
 * OCR 调试几何，坐标为页面归一化坐标：原点左上，范围 0..1。
 */
public final class OcrDebugRect {

    public final int pageIndex;
    public final OcrDebugGeometryType type;
    public final RectF rectInPageRatio;
    public final String label;

    public OcrDebugRect(
            int pageIndex,
            OcrDebugGeometryType type,
            RectF rectInPageRatio,
            String label
    ) {
        this.pageIndex = pageIndex;
        this.type = type;
        this.rectInPageRatio = rectInPageRatio != null
                ? new RectF(rectInPageRatio)
                : null;
        this.label = label;
    }
}
