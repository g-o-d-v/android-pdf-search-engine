package com.nless.pdf_search_engine.cache;

import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.core.PdfSearchVersions;

/** 统一页面索引缓存键。 */
public final class PdfPageIndexCacheKey {

    private PdfPageIndexCacheKey() {
    }

    public static String build(
            String documentFingerprint,
            int pageIndex,
            PdfSearchSource source,
            int ocrRenderWidth
    ) {
        return build(
                documentFingerprint,
                pageIndex,
                source,
                ocrRenderWidth,
                null
        );
    }

    public static String build(
            String documentFingerprint,
            int pageIndex,
            PdfSearchSource source,
            int ocrRenderWidth,
            String ocrNamespace
    ) {
        String fingerprint = documentFingerprint != null ? documentFingerprint : "";
        PdfSearchSource safeSource = source != null ? source : PdfSearchSource.OCR;
        StringBuilder key = new StringBuilder()
                .append(fingerprint)
                .append("#page=").append(pageIndex)
                .append("#source=").append(safeSource.name())
                .append("#versions=").append(
                        safeSource == PdfSearchSource.PDF_TEXT_LAYER
                                ? PdfSearchVersions.textPageIndexNamespace()
                                : PdfSearchVersions.ocrPageIndexNamespace()
                );
        if (safeSource == PdfSearchSource.OCR) {
            key.append("#ocrWidth=").append(Math.max(1, ocrRenderWidth));
            if (ocrNamespace != null && !ocrNamespace.isEmpty()) {
                key.append("#ocrNamespace=").append(ocrNamespace);
            }
        }
        return key.toString();
    }
}
