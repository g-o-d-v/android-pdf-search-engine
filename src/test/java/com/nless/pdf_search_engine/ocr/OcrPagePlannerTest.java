package com.nless.pdf_search_engine.ocr;

import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class OcrPagePlannerTest {

    @Test
    public void currentPageOutward_prioritizesVisiblePage() {
        PdfSearchOptions options = new PdfSearchOptions();
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = true;
        options.startPage = 0;
        options.endPage = 5;
        options.currentPage = 3;
        options.ocrPageOrder = PdfSearchPageOrder.CURRENT_PAGE_OUTWARD;

        // 本测试只验证页面顺序，不应用默认的 5 页安全限制。
        options.maxOcrPages = 0;


        assertEquals(
                Arrays.asList(3, 2, 4, 1, 5, 0),
                OcrPagePlanner.build(options, 6)
        );
    }

    @Test
    public void maxPages_appliesAfterPriorityOrdering() {
        PdfSearchOptions options = new PdfSearchOptions();
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = true;
        options.startPage = 0;
        options.endPage = -1;
        options.currentPage = 4;
        options.maxOcrPages = 3;
        options.ocrPageOrder = PdfSearchPageOrder.CURRENT_PAGE_OUTWARD;

        assertEquals(
                Arrays.asList(4, 3, 5),
                OcrPagePlanner.build(options, 10)
        );
    }

    @Test
    public void disabledFullDocument_forcesCurrentPage() {
        PdfSearchOptions options = new PdfSearchOptions();
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = false;
        options.currentPage = 8;

        assertEquals(
                Arrays.asList(4),
                OcrPagePlanner.build(options, 5)
        );
    }

    @Test
    public void explicitTargets_keepPriorityOrderAndSkipOtherPages() {
        PdfSearchOptions options = new PdfSearchOptions();
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = true;
        options.startPage = 0;
        options.endPage = 7;
        options.currentPage = 4;
        options.maxOcrPages = 0;
        options.ocrPageOrder = PdfSearchPageOrder.CURRENT_PAGE_OUTWARD;
        options.ocrTargetPages = Arrays.asList(1, 4, 6);

        assertEquals(
                Arrays.asList(4, 6, 1),
                OcrPagePlanner.build(options, 8)
        );
    }

    @Test
    public void explicitTargets_areCopiedByOptionsSnapshot() {
        PdfSearchOptions options = new PdfSearchOptions();
        options.ocrTargetPages = new java.util.ArrayList<>(Arrays.asList(2, 5));

        PdfSearchOptions copy = new PdfSearchOptions(options);
        options.ocrTargetPages.add(7);

        assertEquals(Arrays.asList(2, 5), copy.ocrTargetPages);
    }
}
