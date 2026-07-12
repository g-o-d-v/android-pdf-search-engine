package com.nless.pdf_search_engine.core;

/**
 * 详细搜索进度。旧版三参数进度回调仍然保留。
 */
public class PdfSearchProgressInfo {
    public final int pageIndex;
    public final int documentPageCount;
    public final int processedPages;
    public final int targetPages;
    public final int cumulativeMatchCount;
    public final PdfSearchSource source;
    public final PdfSearchCacheSource cacheSource;
    public final long elapsedMillis;

    public PdfSearchProgressInfo(
            int pageIndex,
            int documentPageCount,
            int processedPages,
            int targetPages,
            int cumulativeMatchCount,
            PdfSearchSource source,
            PdfSearchCacheSource cacheSource,
            long elapsedMillis
    ) {
        this.pageIndex = pageIndex;
        this.documentPageCount = documentPageCount;
        this.processedPages = processedPages;
        this.targetPages = targetPages;
        this.cumulativeMatchCount = cumulativeMatchCount;
        this.source = source;
        this.cacheSource = cacheSource != null ? cacheSource : PdfSearchCacheSource.NONE;
        this.elapsedMillis = elapsedMillis;
    }
}
