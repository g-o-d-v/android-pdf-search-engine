package com.nless.pdf_search_engine.core;

/**
 * 单页搜索失败。全文任务可以继续处理后续页面。
 */
public final class PdfSearchPageError {

    public static final int STAGE_TEXT_LAYER = 1;
    public static final int STAGE_RENDER = 2;
    public static final int STAGE_OCR = 3;
    public static final int STAGE_CACHE = 4;

    public final int pageIndex;
    public final int stage;
    public final String message;
    public final Throwable cause;
    public final boolean retryable;

    public PdfSearchPageError(
            int pageIndex,
            int stage,
            String message,
            Throwable cause,
            boolean retryable
    ) {
        this.pageIndex = pageIndex;
        this.stage = stage;
        this.message = message != null ? message : "page search failed";
        this.cause = cause;
        this.retryable = retryable;
    }
}
