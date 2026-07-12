package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchSummary;

/** 同步索引构建结果。 */
public final class PdfDocumentIndexBuildResult {
    public final PdfDocumentIndex index;
    public final PdfSearchSummary summary;

    public PdfDocumentIndexBuildResult(PdfDocumentIndex index, PdfSearchSummary summary) {
        this.index = index;
        this.summary = summary;
    }
}
