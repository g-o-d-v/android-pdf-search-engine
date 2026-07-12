package com.nless.pdf_search_engine.index;

import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 与关键词无关的单页内容索引。 */
public final class PdfPageIndex {

    public static final int STATUS_OK = 0;
    public static final int STATUS_EXTRACTION_FAILED = 1;

    public final int pageIndex;
    public final float pageWidth;
    public final float pageHeight;
    public final PdfSearchSource source;
    public final String originalText;
    public final List<PdfTextToken> tokens;
    public final int status;
    public final int rawCharacterCount;
    public final int visibleCharacterCount;
    public final int validBoxCount;
    public final float confidence;

    public PdfPageIndex(
            int pageIndex,
            float pageWidth,
            float pageHeight,
            PdfSearchSource source,
            String originalText,
            List<PdfTextToken> tokens,
            int status,
            int rawCharacterCount,
            int visibleCharacterCount,
            int validBoxCount,
            float confidence
    ) {
        this.pageIndex = Math.max(0, pageIndex);
        this.pageWidth = Math.max(0f, pageWidth);
        this.pageHeight = Math.max(0f, pageHeight);
        this.source = source != null ? source : PdfSearchSource.OCR;
        this.originalText = originalText != null ? originalText : "";
        this.tokens = Collections.unmodifiableList(
                tokens == null ? new ArrayList<>() : new ArrayList<>(tokens)
        );
        this.status = status;
        this.rawCharacterCount = Math.max(0, rawCharacterCount);
        this.visibleCharacterCount = Math.max(0, visibleCharacterCount);
        this.validBoxCount = Math.max(0, validBoxCount);
        this.confidence = confidence;
    }

    public boolean isReadable() {
        return status == STATUS_OK;
    }

    public float getBoxCoverage() {
        if (visibleCharacterCount <= 0) return 0f;
        return Math.max(0f, Math.min(1f, validBoxCount / (float) visibleCharacterCount));
    }

    public boolean hasUsableTextLayer(int minVisibleCharacters, float minCoverage) {
        return source == PdfSearchSource.PDF_TEXT_LAYER
                && isReadable()
                && visibleCharacterCount >= Math.max(0, minVisibleCharacters)
                && getBoxCoverage() >= Math.max(0f, Math.min(1f, minCoverage));
    }

    public boolean isSearchable() {
        return isReadable() && !originalText.isEmpty() && !tokens.isEmpty();
    }
}
