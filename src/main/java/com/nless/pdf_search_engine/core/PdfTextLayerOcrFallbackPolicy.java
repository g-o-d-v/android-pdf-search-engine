package com.nless.pdf_search_engine.core;

/**
 * TEXT_THEN_OCR 模式下逐页决定 OCR fallback 的策略。
 */
public enum PdfTextLayerOcrFallbackPolicy {

    /** 仅对没有文本层、文本层损坏或文本坐标质量过低的页面执行 OCR。 */
    UNUSABLE_TEXT_LAYER_ONLY,

    /**
     * 除不可用文本层外，对“文本层可用但没有命中”的页面也执行 OCR。
     * 适合文本层不完整的混合 PDF，但会显著增加 OCR 页数。
     */
    UNUSABLE_OR_NO_MATCH
}
