package com.nless.pdf_search_engine.pdfium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 PDFium 文本层扫描的完整报告。
 * 同时返回逻辑命中和逐页文本层质量，供逐页 OCR fallback 使用。
 */
public final class PdfiumTextSearchReport {

    public final int documentPageCount;
    public final List<PdfiumTextPageInfo> pageInfos;
    public final List<PdfiumTextSearchResult> results;

    public PdfiumTextSearchReport(
            int documentPageCount,
            List<PdfiumTextPageInfo> pageInfos,
            List<PdfiumTextSearchResult> results
    ) {
        this.documentPageCount = Math.max(0, documentPageCount);
        this.pageInfos = Collections.unmodifiableList(
                pageInfos == null ? new ArrayList<>() : new ArrayList<>(pageInfos)
        );
        this.results = Collections.unmodifiableList(
                results == null ? new ArrayList<>() : new ArrayList<>(results)
        );
    }

    public static PdfiumTextSearchReport empty() {
        return new PdfiumTextSearchReport(0, null, null);
    }

    public PdfiumTextPageInfo findPage(int pageIndex) {
        for (PdfiumTextPageInfo info : pageInfos) {
            if (info != null && info.pageIndex == pageIndex) return info;
        }
        return null;
    }
}
