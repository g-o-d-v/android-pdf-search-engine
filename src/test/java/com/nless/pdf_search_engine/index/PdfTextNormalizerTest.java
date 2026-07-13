package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchQueryOptions;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PdfTextNormalizerTest {

    @Test
    public void nfkcAndCaseNormalizationPreserveTokenMapping() {
        PdfPageIndex page = new PdfPageIndex(
                0,
                100,
                100,
                PdfSearchSource.PDF_TEXT_LAYER,
                "ＡBc",
                Arrays.asList(
                        new PdfTextToken("Ａ", 0, 1, null, 0, 1f),
                        new PdfTextToken("B", 1, 2, null, 0, 1f),
                        new PdfTextToken("c", 2, 3, null, 0, 1f)
                ),
                PdfPageIndex.STATUS_OK,
                3,
                3,
                0,
                1f
        );
        PdfSearchQueryOptions options = new PdfSearchQueryOptions();

        PdfNormalizedText normalized = PdfTextNormalizer.normalizePage(page, options);

        assertEquals("abc", normalized.text);
        assertArrayEquals(new int[]{0, 1, 2}, normalized.normalizedToToken);
        assertEquals("abc", PdfTextNormalizer.normalizeQuery("ＡBC", options));
    }

    @Test
    public void crossLineAndHyphenRulesAreConfigurable() {
        PdfPageIndex page = new PdfPageIndex(
                0,
                100,
                100,
                PdfSearchSource.OCR,
                "search-\nengine",
                Arrays.asList(
                        new PdfTextToken("search-", 0, 7, null, 0, 1f),
                        new PdfTextToken("\n", 7, 8, null, 0, 1f),
                        new PdfTextToken("engine", 8, 14, null, 1, 1f)
                ),
                PdfPageIndex.STATUS_OK,
                14,
                12,
                0,
                1f
        );
        PdfSearchQueryOptions options = new PdfSearchQueryOptions();
        options.allowCrossLineMatch = true;
        options.joinHyphenatedLineBreaks = true;

        PdfNormalizedText normalized = PdfTextNormalizer.normalizePage(page, options);

        assertEquals("searchengine", normalized.text);
        assertEquals(12, normalized.normalizedToToken.length);
        assertEquals(0, normalized.normalizedToToken[5]);
        assertEquals(2, normalized.normalizedToToken[6]);
    }
    @Test
    public void disablingCrossLineMatchAddsHardBoundary() {
        PdfPageIndex page = new PdfPageIndex(
                0, 100, 100, PdfSearchSource.OCR, "A\nB",
                Arrays.asList(
                        new PdfTextToken("A", 0, 1, null, 0, 1f),
                        new PdfTextToken("\n", 1, 2, null, 0, 1f),
                        new PdfTextToken("B", 2, 3, null, 1, 1f)
                ),
                PdfPageIndex.STATUS_OK, 3, 2, 0, 1f
        );
        PdfSearchQueryOptions options = new PdfSearchQueryOptions();
        options.allowCrossLineMatch = false;

        PdfNormalizedText normalized = PdfTextNormalizer.normalizePage(page, options);

        assertEquals(3, normalized.text.length());
        assertEquals('\u0000', normalized.text.charAt(1));
    }

    @Test
    public void crossLineMatchRemovesOnlyLineBreakAndAdjacentLayoutWhitespace() {
        PdfPageIndex ocrPage = new PdfPageIndex(
                0,
                100,
                100,
                PdfSearchSource.OCR,
                "跨行  \n  检索测试 PDF search",
                Arrays.asList(
                        new PdfTextToken("跨行", 0, 2, null, 0, 1f),
                        new PdfTextToken("  ", 2, 4, null, 0, 1f),
                        new PdfTextToken("\n", 4, 5, null, 0, 1f),
                        new PdfTextToken("  ", 5, 7, null, 1, 1f),
                        new PdfTextToken("检索测试", 7, 11, null, 1, 1f),
                        new PdfTextToken(" ", 11, 12, null, 1, 1f),
                        new PdfTextToken("PDF", 12, 15, null, 1, 1f),
                        new PdfTextToken(" ", 15, 16, null, 1, 1f),
                        new PdfTextToken("search", 16, 22, null, 1, 1f)
                ),
                PdfPageIndex.STATUS_OK,
                22,
                17,
                0,
                1f
        );
        PdfSearchQueryOptions options = new PdfSearchQueryOptions();
        options.allowCrossLineMatch = true;
        options.ignoreWhitespaceForMatching = false;

        PdfNormalizedText normalized = PdfTextNormalizer.normalizePage(ocrPage, options);

        assertEquals("跨行检索测试 pdf search", normalized.text);
        assertEquals(
                "跨行检索测试",
                PdfTextNormalizer.normalizeQuery(
                        "跨行\n检索测试",
                        options,
                        PdfSearchSource.OCR
                )
        );
        assertEquals(
                "pdf search",
                PdfTextNormalizer.normalizeQuery(
                        "PDF search",
                        options,
                        PdfSearchSource.OCR
                )
        );
        assertEquals(
                "pdfsearch",
                PdfTextNormalizer.normalizeQuery(
                        "PDFsearch",
                        options,
                        PdfSearchSource.OCR
                )
        );
    }

    @Test
    public void ocrOZeroToleranceIsSourceSpecificAndConfigurable() {
        PdfSearchQueryOptions options = new PdfSearchQueryOptions();
        PdfPageIndex ocrPage = new PdfPageIndex(
                0,
                100,
                100,
                PdfSearchSource.OCR,
                "A-0O1",
                Arrays.asList(new PdfTextToken("A-0O1", 0, 5, null, 0, 1f)),
                PdfPageIndex.STATUS_OK,
                5,
                5,
                0,
                1f
        );
        PdfPageIndex textPage = new PdfPageIndex(
                0,
                100,
                100,
                PdfSearchSource.PDF_TEXT_LAYER,
                "A-0O1",
                Arrays.asList(new PdfTextToken("A-0O1", 0, 5, null, 0, 1f)),
                PdfPageIndex.STATUS_OK,
                5,
                5,
                0,
                1f
        );

        assertEquals("a-001", PdfTextNormalizer.normalizePage(ocrPage, options).text);
        assertEquals("a-0o1", PdfTextNormalizer.normalizePage(textPage, options).text);
        assertEquals(
                "a-001",
                PdfTextNormalizer.normalizeQuery("A-OO1", options, PdfSearchSource.OCR)
        );
        assertEquals(
                "a-oo1",
                PdfTextNormalizer.normalizeQuery(
                        "A-OO1",
                        options,
                        PdfSearchSource.PDF_TEXT_LAYER
                )
        );

        options.tolerateOcrOZeroConfusion = false;
        assertEquals("a-0o1", PdfTextNormalizer.normalizePage(ocrPage, options).text);
        assertEquals(
                "a-oo1",
                PdfTextNormalizer.normalizeQuery("A-OO1", options, PdfSearchSource.OCR)
        );
    }

}
