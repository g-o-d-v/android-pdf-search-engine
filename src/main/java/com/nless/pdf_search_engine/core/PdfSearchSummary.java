package com.nless.pdf_search_engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 全文搜索的完整性摘要。 */
public final class PdfSearchSummary {

    public final int totalDocumentPages;
    public final int targetPages;
    public final int completedPages;
    public final int failedPages;
    public final int skippedPages;
    public final int resultCount;
    public final int memoryIndexHits;
    public final int diskIndexHits;
    public final boolean complete;
    public final boolean cancelled;
    public final boolean limitedByOptions;
    public final long elapsedMillis;
    public final List<PdfSearchPageError> pageErrors;

    public PdfSearchSummary(
            int totalDocumentPages,
            int targetPages,
            int completedPages,
            int failedPages,
            int skippedPages,
            int resultCount,
            int memoryIndexHits,
            int diskIndexHits,
            boolean complete,
            boolean cancelled,
            boolean limitedByOptions,
            long elapsedMillis,
            List<PdfSearchPageError> pageErrors
    ) {
        this.totalDocumentPages = Math.max(0, totalDocumentPages);
        this.targetPages = Math.max(0, targetPages);
        this.completedPages = Math.max(0, completedPages);
        this.failedPages = Math.max(0, failedPages);
        this.skippedPages = Math.max(0, skippedPages);
        this.resultCount = Math.max(0, resultCount);
        this.memoryIndexHits = Math.max(0, memoryIndexHits);
        this.diskIndexHits = Math.max(0, diskIndexHits);
        this.complete = complete;
        this.cancelled = cancelled;
        this.limitedByOptions = limitedByOptions;
        this.elapsedMillis = Math.max(0L, elapsedMillis);
        this.pageErrors = Collections.unmodifiableList(
                pageErrors == null ? new ArrayList<>() : new ArrayList<>(pageErrors)
        );
    }

    public PdfSearchSummary withResultCount(int value, long elapsed) {
        return withResultCount(value, elapsed, false);
    }

    public PdfSearchSummary withResultCount(
            int value,
            long elapsed,
            boolean additionallyLimited
    ) {
        return new PdfSearchSummary(
                totalDocumentPages,
                targetPages,
                completedPages,
                failedPages,
                skippedPages,
                value,
                memoryIndexHits,
                diskIndexHits,
                complete && !additionallyLimited,
                cancelled,
                limitedByOptions || additionallyLimited,
                elapsed,
                pageErrors
        );
    }

    public static PdfSearchSummary empty() {
        return new PdfSearchSummary(
                0, 0, 0, 0, 0, 0, 0, 0,
                true, false, false, 0L, null
        );
    }
}
