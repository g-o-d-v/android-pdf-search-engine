package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与关键词无关的文档内容索引，可在同一页同时保存文本层和 OCR 来源。 */
public final class PdfDocumentIndex {

    public final String documentFingerprint;
    public final int documentPageCount;
    private final Map<Integer, List<PdfPageIndex>> pages;
    private final List<PdfPageIndex> flattened;

    public PdfDocumentIndex(
            String documentFingerprint,
            int documentPageCount,
            Collection<PdfPageIndex> pageIndexes
    ) {
        this.documentFingerprint = documentFingerprint != null
                ? documentFingerprint
                : "";
        this.documentPageCount = Math.max(0, documentPageCount);
        Map<Integer, List<PdfPageIndex>> map = new LinkedHashMap<>();
        List<PdfPageIndex> sorted = pageIndexes == null
                ? new ArrayList<>()
                : new ArrayList<>(pageIndexes);
        sorted.sort((a, b) -> {
            int page = Integer.compare(a.pageIndex, b.pageIndex);
            if (page != 0) return page;
            return Integer.compare(sourcePriority(a.source), sourcePriority(b.source));
        });
        for (PdfPageIndex page : sorted) {
            if (page == null) continue;
            map.computeIfAbsent(page.pageIndex, ignored -> new ArrayList<>()).add(page);
        }
        Map<Integer, List<PdfPageIndex>> immutable = new LinkedHashMap<>();
        List<PdfPageIndex> flat = new ArrayList<>();
        for (Map.Entry<Integer, List<PdfPageIndex>> entry : map.entrySet()) {
            List<PdfPageIndex> values = Collections.unmodifiableList(
                    new ArrayList<>(entry.getValue())
            );
            immutable.put(entry.getKey(), values);
            flat.addAll(values);
        }
        this.pages = Collections.unmodifiableMap(immutable);
        this.flattened = Collections.unmodifiableList(flat);
    }

    /** 优先返回文本层，其次 OCR。 */
    public PdfPageIndex getPage(int pageIndex) {
        List<PdfPageIndex> values = pages.get(pageIndex);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<PdfPageIndex> getPageIndexes(int pageIndex) {
        List<PdfPageIndex> values = pages.get(pageIndex);
        return values != null ? values : Collections.emptyList();
    }

    public List<PdfPageIndex> getPages() {
        return flattened;
    }

    /** 已覆盖的逻辑页数，不把同页双来源重复计数。 */
    public int getIndexedPageCount() {
        return pages.size();
    }

    private static int sourcePriority(PdfSearchSource source) {
        return source == PdfSearchSource.PDF_TEXT_LAYER ? 0 : 1;
    }
}
