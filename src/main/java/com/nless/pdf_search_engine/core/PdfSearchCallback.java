package com.nless.pdf_search_engine.core;

import java.util.List;

public interface PdfSearchCallback {

    void onSearchStarted(String keyword);

    /**
     * @param currentPage 当前处理页，0-based
     * @param totalPage 文档总页数，未知时可传 -1
     * @param source 当前搜索来源
     */
    void onSearchProgress(int currentPage, int totalPage, PdfSearchSource source);

    /**
     * 详细进度回调。使用 default 保持旧调用方源码兼容。
     */
    default void onSearchProgress(PdfSearchProgressInfo progressInfo) {
    }

    /**
     * 每完成一页 OCR 后回调。pageResults 只包含该页结果。
     */
    default void onSearchPageCompleted(
            int pageIndex,
            List<PdfSearchResult> pageResults,
            PdfSearchCacheSource cacheSource
    ) {
    }

    /**
     * 单页 OCR 流水线性能数据。默认空实现保持旧调用方兼容。
     */
    default void onSearchPageMetrics(PdfSearchPageMetrics metrics) {
    }

    /** 单页失败时回调；全文任务会继续处理后续页面。 */
    default void onSearchPageFailed(PdfSearchPageError error) {
    }

    void onSearchCompleted(List<PdfSearchResult> results);

    /**
     * 搜索完整性摘要。默认转发到旧 onSearchCompleted，保持源码兼容。
     */
    default void onSearchCompleted(
            List<PdfSearchResult> results,
            PdfSearchSummary summary
    ) {
        onSearchCompleted(results);
    }

    void onSearchFailed(Throwable error);

    void onSearchCancelled();
}
