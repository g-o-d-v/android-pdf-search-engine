package com.nless.pdf_search_engine.core;

import com.nless.pdf_search_engine.index.PdfDocumentIndex;
import com.nless.pdf_search_engine.index.PdfPageIndex;

/** 文档内容索引构建回调。 */
public interface PdfIndexCallback {

    default void onIndexStarted(int targetPages) {
    }

    default void onIndexPageCompleted(
            int pageIndex,
            PdfPageIndex pageIndexData,
            int processedPages,
            int targetPages,
            PdfSearchCacheSource cacheSource
    ) {
    }

    default void onIndexPageFailed(PdfSearchPageError error) {
    }

    void onIndexCompleted(PdfDocumentIndex index, PdfSearchSummary summary);

    void onIndexFailed(Throwable error);

    void onIndexCancelled();
}
