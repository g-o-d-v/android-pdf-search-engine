package com.nless.pdf_search_engine.ocr;

import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯 Java 页面调度器，便于单元测试。
 */
public final class OcrPagePlanner {

    private OcrPagePlanner() {
    }

    public static List<Integer> build(PdfSearchOptions options, int pageCount) {
        if (pageCount <= 0) return Collections.emptyList();
        PdfSearchOptions request = options != null ? options : new PdfSearchOptions();

        int current = clampPage(request.currentPage, pageCount);
        List<Integer> pages = new ArrayList<>();

        if (request.currentPageOnly || !request.allowFullDocumentOcr) {
            pages.add(current);
        } else {
            int start = clampPage(Math.max(0, request.startPage), pageCount);
            int end = request.endPage < 0
                    ? pageCount - 1
                    : clampPage(request.endPage, pageCount);
            if (end < start) end = start;

            int pageTotal = end - start + 1;
            if (request.ocrPageOrder == PdfSearchPageOrder.CURRENT_PAGE_OUTWARD) {
                int pivot = Math.max(start, Math.min(end, current));
                pages.add(pivot);
                for (int distance = 1; pages.size() < pageTotal; distance++) {
                    int before = pivot - distance;
                    int after = pivot + distance;
                    if (before >= start) pages.add(before);
                    if (after <= end) pages.add(after);
                }
            } else {
                for (int page = start; page <= end; page++) pages.add(page);
            }
        }

        pages = filterExplicitTargets(pages, request.ocrTargetPages, pageCount);
        if (request.maxOcrPages > 0 && pages.size() > request.maxOcrPages) {
            return new ArrayList<>(pages.subList(0, request.maxOcrPages));
        }
        return pages;
    }

    private static List<Integer> filterExplicitTargets(
            List<Integer> orderedPages,
            List<Integer> targetPages,
            int pageCount
    ) {
        if (targetPages == null || targetPages.isEmpty()) return orderedPages;

        Set<Integer> targets = new HashSet<>();
        for (Integer page : targetPages) {
            if (page != null && page >= 0 && page < pageCount) targets.add(page);
        }
        if (targets.isEmpty()) return Collections.emptyList();

        List<Integer> filtered = new ArrayList<>();
        for (Integer page : orderedPages) {
            if (targets.contains(page)) filtered.add(page);
        }
        return filtered;
    }

    private static int clampPage(int page, int pageCount) {
        return Math.max(0, Math.min(pageCount - 1, page));
    }
}
