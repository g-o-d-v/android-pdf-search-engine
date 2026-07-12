package com.nless.pdf_search_engine.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PdfSearchSummaryTest {

    @Test
    public void resultLimitMakesSummaryIncomplete() {
        PdfSearchSummary summary = new PdfSearchSummary(
                10, 10, 10, 0, 0, 0, 0, 0,
                true, false, false, 100, null
        );

        PdfSearchSummary limited = summary.withResultCount(20, 120, true);

        assertFalse(limited.complete);
        assertTrue(limited.limitedByOptions);
    }
}
