package com.nless.pdf_search_engine.core;

/** 页面内容或 OCR 结果的来源。 */
public enum PdfSearchCacheSource {
    NONE,
    MEMORY,
    DISK,
    INDEX_MEMORY,
    INDEX_DISK
}
