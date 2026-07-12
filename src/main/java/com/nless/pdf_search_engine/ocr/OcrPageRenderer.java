package com.nless.pdf_search_engine.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

public class OcrPageRenderer {

    public OcrDocumentSession openSession(Context context, Uri pdfUri) throws Exception {
        if (context == null || pdfUri == null) {
            throw new IllegalArgumentException("context/pdfUri is null");
        }
        return new OcrDocumentSession(context, pdfUri);
    }

    /** 兼容旧调用：只返回 Bitmap。 */
    public Bitmap renderPageForOcr(
            Context context,
            Uri pdfUri,
            int pageIndex,
            int targetWidthPx
    ) {
        OcrRenderedPage renderedPage = renderPage(
                context,
                pdfUri,
                pageIndex,
                targetWidthPx
        );
        return renderedPage != null ? renderedPage.bitmap : null;
    }

    /**
     * 单页兼容接口。全文 OCR 应使用 openSession() 复用 PdfRenderer。
     */
    public OcrRenderedPage renderPage(
            Context context,
            Uri pdfUri,
            int pageIndex,
            int targetWidthPx
    ) {
        try (OcrDocumentSession session = openSession(context, pdfUri)) {
            return session.renderPage(pageIndex, targetWidthPx);
        } catch (Throwable error) {
            return null;
        }
    }

    public int getPageCount(Context context, Uri pdfUri) {
        try (OcrDocumentSession session = openSession(context, pdfUri)) {
            return session.getPageCount();
        } catch (Throwable error) {
            return -1;
        }
    }
}
