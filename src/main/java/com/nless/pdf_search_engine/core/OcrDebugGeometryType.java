package com.nless.pdf_search_engine.core;

/**
 * OCR 几何调试框类型。
 */
public enum OcrDebugGeometryType {
    /** Paddle DB 检测出的整行文本框。 */
    DETECTION_BLOCK,

    /** CTC 对齐并经过前景像素收紧后的单 token 框。 */
    TOKEN_BOX
}
