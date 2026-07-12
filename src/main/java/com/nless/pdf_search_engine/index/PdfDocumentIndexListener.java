package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchPageError;

/** 索引器内部同步回调，由 PdfSearchManager 再切换到主线程。 */
public interface PdfDocumentIndexListener {
    default void onStarted(int targetPages) {
    }

    default void onPageIndexed(
            PdfPageIndex page,
            int processedPages,
            int targetPages,
            PdfSearchCacheSource cacheSource
    ) {
    }

    default void onPageFailed(PdfSearchPageError error) {
    }
}
