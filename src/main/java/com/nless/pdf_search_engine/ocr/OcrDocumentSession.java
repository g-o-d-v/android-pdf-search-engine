package com.nless.pdf_search_engine.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;

/**
 * 单次多页 OCR 任务复用同一个 PdfRenderer，避免每页重复打开文件和解析 PDF。
 */
public class OcrDocumentSession implements Closeable {

    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer renderer;

    public OcrDocumentSession(Context context, Uri pdfUri) throws Exception {
        fileDescriptor = context.getContentResolver().openFileDescriptor(pdfUri, "r");
        if (fileDescriptor == null) {
            throw new IllegalStateException("无法打开 PDF 文件描述符");
        }
        renderer = new PdfRenderer(fileDescriptor);
    }

    public int getPageCount() {
        return renderer != null ? renderer.getPageCount() : 0;
    }

    public OcrRenderedPage renderPage(int pageIndex, int targetWidthPx) {
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
            return null;
        }
        if (targetWidthPx <= 0) return null;

        PdfRenderer.Page page = null;
        try {
            page = renderer.openPage(pageIndex);
            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();
            if (pageWidth <= 0 || pageHeight <= 0) return null;

            float scale = targetWidthPx / (float) pageWidth;
            int targetHeightPx = Math.max(1, Math.round(pageHeight * scale));

            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidthPx,
                    targetHeightPx,
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            );

            return new OcrRenderedPage(
                    pageIndex,
                    bitmap,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    pageWidth,
                    pageHeight
            );
        } catch (Throwable error) {
            return null;
        } finally {
            try {
                if (page != null) page.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void close() {
        try {
            if (renderer != null) renderer.close();
        } catch (Throwable ignored) {
        } finally {
            renderer = null;
        }

        try {
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (Throwable ignored) {
        } finally {
            fileDescriptor = null;
        }
    }
}
