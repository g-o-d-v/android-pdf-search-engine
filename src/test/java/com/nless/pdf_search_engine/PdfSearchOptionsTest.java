package com.nless.pdf_search_engine;

import com.nless.pdf_search_engine.core.PdfSearchOptions;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class PdfSearchOptionsTest {

    @Test
    public void copyConstructorDeepCopiesIndexAndQueryOptions() {
        PdfSearchOptions source = new PdfSearchOptions();
        source.enableDocumentIndex = true;
        source.targetPages = Arrays.asList(1, 3, 5);
        source.queryOptions.caseSensitive = true;
        source.queryOptions.contextCharacters = 40;
        source.queryOptions.tolerateOcrOZeroConfusion = false;

        PdfSearchOptions copy = new PdfSearchOptions(source);

        assertTrue(copy.enableDocumentIndex);
        assertEquals(Arrays.asList(1, 3, 5), copy.targetPages);
        assertNotSame(source.targetPages, copy.targetPages);
        assertNotSame(source.queryOptions, copy.queryOptions);
        assertTrue(copy.queryOptions.caseSensitive);
        assertEquals(40, copy.queryOptions.contextCharacters);
        assertEquals(false, copy.queryOptions.tolerateOcrOZeroConfusion);
    }
}
