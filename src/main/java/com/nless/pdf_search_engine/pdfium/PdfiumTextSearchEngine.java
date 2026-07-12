package com.nless.pdf_search_engine.pdfium;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.nless.pdf_search_engine.core.PdfSearchCancelChecker;
import com.nless.pdf_search_engine.core.PdfSearchProgressListener;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.io.File;

import com.nless.pdf_search_engine.cache.PdfDocumentFingerprint;
import java.util.ArrayList;
import java.util.List;

public class PdfiumTextSearchEngine {

    private static final String TAG = "PdfiumTextSearchEngine";
    private final PdfiumTextNative nativeApi = new PdfiumTextNative();

    public List<PdfiumTextSearchResult> search(
            Context context,
            Uri pdfUri,
            String keyword
    ) {
        return search(context, pdfUri, keyword, 0, -1);
    }

    public List<PdfiumTextSearchResult> search(
            Context context,
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage
    ) {
        return searchDetailed(
                context,
                pdfUri,
                keyword,
                startPage,
                endPage,
                true
        ).results;
    }

    /**
     * 一次打开 PDF，返回全文/范围内的逻辑结果及逐页文本层质量。
     */
    public PdfiumTextSearchReport searchDetailed(
            Context context,
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            boolean ignoreCase
    ) {
        if (context == null || pdfUri == null
                || keyword == null || keyword.trim().isEmpty()) {
            return PdfiumTextSearchReport.empty();
        }

        try {
            File pdfFile = PdfiumFileCache.copy(
                    context,
                    pdfUri,
                    PdfDocumentFingerprint.build(context, pdfUri)
            );
            if (pdfFile == null || !pdfFile.exists()) {
                Log.e(TAG, "copy pdf uri to cache failed");
                return PdfiumTextSearchReport.empty();
            }

            float[] values = nativeApi.nativeSearchDetailed(
                    pdfFile.getAbsolutePath(),
                    keyword,
                    startPage,
                    endPage,
                    ignoreCase
            );
            return PdfiumTextProtocolParser.parseDetailed(values);
        } catch (Throwable error) {
            Log.e(TAG, "PDFium detailed text search failed", error);
            return PdfiumTextSearchReport.empty();
        }
    }

    /**
     * 兼容旧接口。详细协议会一次完成范围搜索，然后补发逐页进度。
     */
    public List<PdfiumTextSearchResult> searchWithProgress(
            Context context,
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker
    ) {
        if (cancelChecker != null && cancelChecker.isCancelled()) {
            return new ArrayList<>();
        }
        PdfiumTextSearchReport report = searchDetailed(
                context,
                pdfUri,
                keyword,
                startPage,
                endPage,
                true
        );
        if (progressListener != null) {
            for (PdfiumTextPageInfo pageInfo : report.pageInfos) {
                if (cancelChecker != null && cancelChecker.isCancelled()) break;
                progressListener.onProgress(
                        pageInfo.pageIndex,
                        report.documentPageCount,
                        PdfSearchSource.PDF_TEXT_LAYER
                );
            }
        }
        return report.results;
    }
}
