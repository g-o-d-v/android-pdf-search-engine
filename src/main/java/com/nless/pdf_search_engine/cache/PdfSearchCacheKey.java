package com.nless.pdf_search_engine.cache;

import android.net.Uri;

public class PdfSearchCacheKey {

    public static String buildOcrPageKey(
            String documentFingerprint,
            int pageIndex,
            int renderWidth,
            String cacheNamespace
    ) {
        String fingerprint = documentFingerprint != null ? documentFingerprint : "";
        String namespace = cacheNamespace != null ? cacheNamespace : "default";
        return fingerprint
                + "#page=" + pageIndex
                + "#ocrWidth=" + renderWidth
                + "#namespace=" + namespace;
    }

    /**
     * 兼容旧调用。新代码应优先传入包含文件大小/修改时间的 documentFingerprint。
     */
    public static String buildOcrPageKey(
            Uri pdfUri,
            int pageIndex,
            int renderWidth
    ) {
        String fileKey = pdfUri != null ? pdfUri.toString() : "";
        return buildOcrPageKey(
                fileKey,
                pageIndex,
                renderWidth,
                "ppocr-mobile-geometry-v3.1-phase2-v1"
        );
    }

    public static String buildTextSearchKey(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage
    ) {
        String fileKey = pdfUri != null ? pdfUri.toString() : "";
        String kw = keyword != null ? keyword : "";
        return fileKey + "#text#" + kw + "#start=" + startPage + "#end=" + endPage;
    }
}
