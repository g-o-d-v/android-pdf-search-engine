package com.nless.pdf_search_engine.pdfium;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PdfiumTextPageInfoTest {

    @Test
    public void usableTextLayer_requiresCharactersAndCoordinateCoverage() {
        PdfiumTextPageInfo usable = new PdfiumTextPageInfo(
                0, 595f, 842f,
                PdfiumTextPageInfo.STATUS_OK,
                120, 100, 92, 2
        );
        assertTrue(usable.hasUsableTextLayer(8, 0.5f));

        PdfiumTextPageInfo imageOnly = new PdfiumTextPageInfo(
                1, 595f, 842f,
                PdfiumTextPageInfo.STATUS_OK,
                2, 1, 1, 0
        );
        assertFalse(imageOnly.hasUsableTextLayer(8, 0.5f));

        PdfiumTextPageInfo brokenBoxes = new PdfiumTextPageInfo(
                2, 595f, 842f,
                PdfiumTextPageInfo.STATUS_OK,
                100, 80, 10, 0
        );
        assertFalse(brokenBoxes.hasUsableTextLayer(8, 0.5f));
    }
}
