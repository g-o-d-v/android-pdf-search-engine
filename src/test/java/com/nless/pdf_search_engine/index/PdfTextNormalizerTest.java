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

}
