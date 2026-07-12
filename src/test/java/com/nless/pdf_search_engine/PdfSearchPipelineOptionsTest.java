package com.nless.pdf_search_engine;

import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageMetrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PdfSearchPipelineOptionsTest {

    @Test
    public void copyConstructorPreservesPipelineOptions() {
        PdfSearchOptions source = new PdfSearchOptions();
        source.enableOcrPipeline = false;
        source.ocrPrefetchPages = 2;
        source.ocrCacheWriteQueueCapacity = 4;
        source.ocrCacheWriteDrainTimeoutMillis = 1234L;

        PdfSearchOptions copy = new PdfSearchOptions(source);

        assertFalse(copy.enableOcrPipeline);
        assertEquals(2, copy.ocrPrefetchPages);
        assertEquals(4, copy.ocrCacheWriteQueueCapacity);
        assertEquals(1234L, copy.ocrCacheWriteDrainTimeoutMillis);
    }

    @Test
    public void metricsClampNegativeValuesAndExposeCacheHit() {
        PdfSearchPageMetrics metrics = new PdfSearchPageMetrics(
                3,
                PdfSearchCacheSource.DISK,
                -1L,
                -2L,
                -3L,
                -4L,
                -5L,
                -6L,
                -7L,
                true
        );

        assertTrue(metrics.isCacheHit());
        assertEquals(0L, metrics.cacheReadMillis);
        assertEquals(0L, metrics.renderMillis);
        assertEquals(0L, metrics.ocrMillis);
        assertEquals(0L, metrics.renderedBitmapBytes);
        assertTrue(metrics.cacheWriteQueued);
    }
}
