package com.nless.pdf_search_engine.core;

/**
 * 全文 OCR 的页面调度顺序。
 */
public enum PdfSearchPageOrder {
    /** 从 startPage 到 endPage 顺序处理。 */
    NATURAL,

    /** 先处理 currentPage，再向前后页面交替扩散。 */
    CURRENT_PAGE_OUTWARD
}
