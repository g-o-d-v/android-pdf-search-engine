package com.nless.pdf_search_engine.ocr;

import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchPageMetrics;
import com.nless.pdf_search_engine.core.PdfSearchPageError;

import java.util.List;

/**
 * OCR 每完成一页时触发，用于增量展示结果和详细进度。
 */
public interface OcrPageSearchListener {
    void onPageCompleted(
            int pageIndex,
            int documentPageCount,
            int processedPages,
            int targetPages,
            List<PdfSearchResult> pageResults,
            PdfSearchCacheSource cacheSource,
            long elapsedMillis,
            int cumulativeMatchCount
    );

    /**
     * 页面 OCR 内容已经可用。该回调与关键词无关，供统一页面索引使用。
     */
    default void onPageExtracted(
            int pageIndex,
            OcrPageResult pageResult,
            PdfSearchCacheSource cacheSource
    ) {
    }

    default void onPageMetrics(PdfSearchPageMetrics metrics) {
    }

    default void onPageFailed(PdfSearchPageError error) {
    }
}
