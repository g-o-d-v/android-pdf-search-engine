package com.nless.pdf_search_engine.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PdfSearchLibraryInfoTest {

    @Test
    public void packagedAbisComeFromBuildConfig() {
        assertFalse(PdfSearchLibraryInfo.getPackagedAbis().isEmpty());
        assertEquals("arm64-v8a", PdfSearchLibraryInfo.getPackagedAbis().get(0));
    }
}
