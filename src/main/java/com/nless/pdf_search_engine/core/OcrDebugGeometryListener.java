package com.nless.pdf_search_engine.core;

import java.util.List;

/**
 * OCR 几何调试回调。
 *
 * <p>通过 {@link PdfSearchOptions#ocrDebugOverlayEnabled} 开启。由
 * {@link PdfSearchManager} 转发到主线程，便于直接更新调试覆盖层。</p>
 */
public interface OcrDebugGeometryListener {
    void onOcrDebugGeometry(int pageIndex, List<OcrDebugRect> rects);
}
