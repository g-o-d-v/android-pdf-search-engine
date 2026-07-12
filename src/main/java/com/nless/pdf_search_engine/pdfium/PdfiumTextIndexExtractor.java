package com.nless.pdf_search_engine.pdfium;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.nless.pdf_search_engine.cache.PdfDocumentFingerprint;

import java.io.File;

/** 一次打开 PDF，提取范围内所有字符和字符框。 */
public final class PdfiumTextIndexExtractor {

    private static final String TAG = "PdfiumTextIndex";
    private final PdfiumTextNative nativeApi = new PdfiumTextNative();

    public PdfiumTextIndexReport extract(
            Context context,
            Uri pdfUri,
            int startPage,
            int endPage
    ) {
        if (context == null || pdfUri == null) {
            throw new IllegalArgumentException("context/pdfUri is null");
        }
        try {
            if (!PdfiumTextNative.isProjectNativeLoaded()) {
                throw new IllegalStateException(
                        "Project native library is unavailable",
                        PdfiumTextNative.getProjectNativeLoadError()
                );
            }

            String backendStatus = nativeApi.nativeGetBackendStatus();
            if (backendStatus == null || !backendStatus.startsWith("READY:")) {
                throw new IllegalStateException(
                        "PDFium text backend is not ready: " + backendStatus
                );
            }

            String fingerprint = PdfDocumentFingerprint.build(context, pdfUri);
            File file = PdfiumFileCache.copy(context, pdfUri, fingerprint);
            byte[] protocol = nativeApi.nativeExtractIndex(
                    file.getAbsolutePath(),
                    startPage,
                    endPage
            );
            if (protocol == null || protocol.length == 0) {
                throw new IllegalStateException(
                        "PDFium returned an empty text-index protocol; backend="
                                + nativeApi.nativeGetBackendStatus()
                );
            }
            PdfiumTextIndexReport report = PdfiumTextIndexProtocolParser.parse(protocol);
            if (report.pages.isEmpty() && endPage >= startPage) {
                throw new IllegalStateException(
                        "PDFium text-index protocol contained no requested pages"
                );
            }
            return report;
        } catch (Throwable error) {
            String message = "Text-layer extraction failed for pages "
                    + startPage + ".." + endPage;

            Log.e(TAG, message, error);

            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }

            if (error instanceof Error) {
                throw (Error) error;
            }

            throw new IllegalStateException(message, error);
        }
    }
}
