package com.nless.pdf_search_engine.pdfium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PdfiumTextIndexReport {
    public final int documentPageCount;
    public final List<PdfiumTextIndexPage> pages;

    public PdfiumTextIndexReport(int documentPageCount, List<PdfiumTextIndexPage> pages) {
        this.documentPageCount = Math.max(0, documentPageCount);
        this.pages = Collections.unmodifiableList(
                pages == null ? new ArrayList<>() : new ArrayList<>(pages)
        );
    }

    public static PdfiumTextIndexReport empty() {
        return new PdfiumTextIndexReport(0, null);
    }
}
