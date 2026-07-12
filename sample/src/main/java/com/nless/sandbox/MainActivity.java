package com.nless.sandbox;

import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;
import com.nless.pdf_search_engine.androidpdfviewer.AndroidPdfViewerAdapter;
import com.nless.pdf_search_engine.core.OcrDebugRect;
import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchCallback;
import com.nless.pdf_search_engine.core.PdfSearchManager;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageMetrics;
import com.nless.pdf_search_engine.core.PdfSearchPageError;
import com.nless.pdf_search_engine.core.PdfTextLayerOcrFallbackPolicy;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;
import com.nless.pdf_search_engine.core.PdfSearchProgressInfo;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.core.PdfSearchSummary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private PDFView pdfView;
    private EditText etKeyword;
    private Button btnSearch;
    private Button btnPause;
    private Button btnCancel;
    private CheckBox cbFullDocument;
    private TextView tvProgress;

    private PdfSearchManager searchManager;
    private AndroidPdfViewerAdapter adapter;
    private List<AndroidPdfViewerAdapter.ViewerSearchHighlight> currentHighlights =
            new ArrayList<>();
    private final List<OcrDebugRect> currentOcrDebugRects = new ArrayList<>();

    private static final boolean SHOW_OCR_DEBUG_OVERLAY = false;

    private Paint highlightPaint;
    private Paint detectionDebugPaint;
    private Paint tokenDebugPaint;

    private static final String TEST_PDF_NAME = "test_ocr.pdf";
    private Uri pdfUri;
    private boolean searchPaused = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pdfView = findViewById(R.id.pdfView);
        etKeyword = findViewById(R.id.et_keyword);
        btnSearch = findViewById(R.id.btn_search);
        btnPause = findViewById(R.id.btn_pause);
        btnCancel = findViewById(R.id.btn_cancel);
        cbFullDocument = findViewById(R.id.cb_full_document);
        tvProgress = findViewById(R.id.tv_progress);

        highlightPaint = new Paint();
        highlightPaint.setColor(Color.argb(100, 255, 235, 59));
        highlightPaint.setStyle(Paint.Style.FILL);

        detectionDebugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detectionDebugPaint.setColor(Color.argb(210, 244, 67, 54));
        detectionDebugPaint.setStyle(Paint.Style.STROKE);
        detectionDebugPaint.setStrokeWidth(2f);

        tokenDebugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tokenDebugPaint.setColor(Color.argb(220, 33, 150, 243));
        tokenDebugPaint.setStyle(Paint.Style.STROKE);
        tokenDebugPaint.setStrokeWidth(1.5f);

        searchManager = new PdfSearchManager(this);
        adapter = new AndroidPdfViewerAdapter(pdfView);

        File testFile = copyAssetToCache(TEST_PDF_NAME);
        if (testFile != null) {
            pdfUri = Uri.fromFile(testFile);
            loadPdfToView(testFile);
        } else {
            Toast.makeText(this, "请先在 assets 目录下放置 test_ocr.pdf", Toast.LENGTH_LONG).show();
        }

        btnSearch.setOnClickListener(v -> performSearch());
        btnCancel.setOnClickListener(v -> searchManager.cancel());
        btnPause.setOnClickListener(v -> togglePause());
    }

    private void loadPdfToView(File pdfFile) {
        pdfView.fromFile(pdfFile)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .defaultPage(0)
                .onLoad(pageCount -> adapter.invalidatePageSizeCache())
                .onDrawAll((canvas, pageWidth, pageHeight, displayedPage) -> {
                    adapter.drawHighlights(
                            canvas,
                            pageWidth,
                            pageHeight,
                            displayedPage,
                            currentHighlights,
                            highlightPaint,
                            true
                    );

                    if (SHOW_OCR_DEBUG_OVERLAY) {
                        adapter.drawOcrDebugGeometry(
                                canvas,
                                pageWidth,
                                pageHeight,
                                displayedPage,
                                currentOcrDebugRects,
                                detectionDebugPaint,
                                tokenDebugPaint,
                                true
                        );
                    }
                })
                .load();
    }

    private void performSearch() {
        String keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty() || pdfUri == null) return;

        boolean fullDocument = cbFullDocument.isChecked();
        setSearchRunning(true);
        currentHighlights.clear();
        currentOcrDebugRects.clear();
        pdfView.invalidate();

        PdfSearchOptions options = new PdfSearchOptions();
        options.enableDocumentIndex = true;
        options.usePersistentPageIndexCache = true;
        options.mode = PdfSearchMode.TEXT_THEN_OCR;
        options.enablePageLevelTextOcrFallback = true;
        // 文本层未命中当前关键词时，对该页执行 OCR，以覆盖混合页面。
        options.textLayerOcrFallbackPolicy =
                PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH;
        options.currentPage = pdfView.getCurrentPage();
        options.currentPageOnly = !fullDocument;
        options.allowFullDocumentOcr = fullDocument;
        options.startPage = 0;
        options.endPage = -1;
        options.maxOcrPages = 0; // 0 = 全部目标页
        options.ocrPageOrder = PdfSearchPageOrder.CURRENT_PAGE_OUTWARD;
        options.ocrRenderWidth = fullDocument ? 1280 : 1440;
        options.usePersistentOcrCache = true;
        options.emitIncrementalResults = true;

        // 单 OCR Predictor 流水线：只预取 1 页，限制 Bitmap 峰值内存。
        options.enableOcrPipeline = true;
        options.ocrPrefetchPages = 1;
        options.ocrCacheWriteQueueCapacity = 2;
        options.ocrCacheWriteDrainTimeoutMillis = 5_000L;

        options.ocrDebugOverlayEnabled = SHOW_OCR_DEBUG_OVERLAY;
        options.ocrDebugGeometryListener = (pageIndex, rects) -> {
            currentOcrDebugRects.removeIf(item -> item.pageIndex == pageIndex);
            if (rects != null) currentOcrDebugRects.addAll(rects);
            pdfView.invalidate();
        };

        searchManager.search(pdfUri, keyword, options, new PdfSearchCallback() {
            @Override
            public void onSearchStarted(String keyword) {
                tvProgress.setText(fullDocument
                        ? "正在分析文本层并准备必要页面 OCR……"
                        : "正在搜索当前页……");
            }

            @Override
            public void onSearchProgress(
                    int currentPage,
                    int totalPage,
                    PdfSearchSource source
            ) {
                // 详细进度由下方回调显示。
            }

            @Override
            public void onSearchProgress(PdfSearchProgressInfo info) {
                String cacheText;
                if (info.cacheSource == PdfSearchCacheSource.MEMORY) {
                    cacheText = "OCR 内存缓存";
                } else if (info.cacheSource == PdfSearchCacheSource.DISK) {
                    cacheText = "OCR 磁盘缓存";
                } else if (info.cacheSource == PdfSearchCacheSource.INDEX_MEMORY) {
                    cacheText = "页面索引内存缓存";
                } else if (info.cacheSource == PdfSearchCacheSource.INDEX_DISK) {
                    cacheText = "页面索引磁盘缓存";
                } else {
                    cacheText = "新识别";
                }

                tvProgress.setText(
                        "已处理 " + info.processedPages + "/" + info.targetPages
                                + " 页；当前第 " + (info.pageIndex + 1) + " 页；"
                                + cacheText + "；命中 " + info.cumulativeMatchCount
                                + "；耗时 " + info.elapsedMillis + "ms"
                );
            }

            @Override
            public void onSearchPageCompleted(
                    int pageIndex,
                    List<PdfSearchResult> pageResults,
                    PdfSearchCacheSource cacheSource
            ) {
                if (pageResults != null && !pageResults.isEmpty()) {
                    currentHighlights.addAll(adapter.convertResults(pageResults));
                    pdfView.invalidate();
                }
            }

            @Override
            public void onSearchPageMetrics(PdfSearchPageMetrics metrics) {
                if (metrics == null) return;
                String detail = metrics.isCacheHit()
                        ? "缓存页 " + metrics.cacheReadMillis + "ms"
                        : "渲染 " + metrics.renderMillis + "ms；OCR "
                        + metrics.ocrMillis + "ms；渲染后等待 OCR "
                        + metrics.queueWaitMillis + "ms";
                tvProgress.setText(
                        "第 " + (metrics.pageIndex + 1) + " 页：" + detail
                                + "；匹配 " + metrics.resultBuildMillis + "ms"
                );
            }

            @Override
            public void onSearchPageFailed(PdfSearchPageError error) {
                if (error == null) return;
                tvProgress.setText(
                        "第 " + (error.pageIndex + 1) + " 页处理失败，已继续后续页面"
                );
            }

            @Override
            public void onSearchCompleted(List<PdfSearchResult> results) {
                showCompleted(results, null);
            }

            @Override
            public void onSearchCompleted(
                    List<PdfSearchResult> results,
                    PdfSearchSummary summary
            ) {
                showCompleted(results, summary);
            }

            @Override
            public void onSearchFailed(Throwable error) {
                setSearchRunning(false);
                tvProgress.setText("搜索失败");
                Toast.makeText(
                        MainActivity.this,
                        "搜索失败: " + error.getMessage(),
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onSearchCancelled() {
                setSearchRunning(false);
                tvProgress.setText("搜索已取消；再次搜索会复用已完成页面的磁盘缓存");
            }
        });
    }

    private void showCompleted(
            List<PdfSearchResult> results,
            PdfSearchSummary summary
    ) {
        setSearchRunning(false);
        currentHighlights = adapter.convertResults(results);
        pdfView.invalidate();
        int textLayerHits = 0;
        int ocrHits = 0;
        for (PdfSearchResult result : results) {
            if (result == null) continue;
            if (result.source == PdfSearchSource.PDF_TEXT_LAYER) textLayerHits++;
            else if (result.source == PdfSearchSource.OCR) ocrHits++;
        }

        String completeness = summary == null
                ? ""
                : "；页面 " + summary.completedPages + "/" + summary.targetPages
                + (summary.failedPages > 0 ? "；失败 " + summary.failedPages : "")
                + (summary.limitedByOptions ? "；受选项限制" : "")
                + "；索引缓存命中 "
                + (summary.memoryIndexHits + summary.diskIndexHits);
        tvProgress.setText(
                "搜索完成：逻辑命中 " + results.size()
                        + "；文本层 " + textLayerHits
                        + "；OCR " + ocrHits
                        + "；高亮框 " + currentHighlights.size()
                        + completeness
                        + "；OCR 缓存 "
                        + formatBytes(searchManager.getPersistentOcrCacheSizeBytes())
                        + "；页面索引 "
                        + formatBytes(searchManager.getPersistentPageIndexCacheSizeBytes())
        );
        if (results.isEmpty()) {
            Toast.makeText(MainActivity.this, "未找到目标", Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePause() {
        if (searchPaused) {
            searchManager.resume();
            searchPaused = false;
            btnPause.setText("暂停");
            tvProgress.setText("任务已继续");
        } else {
            searchManager.pause();
            searchPaused = true;
            btnPause.setText("继续");
            tvProgress.setText("将在当前页 OCR 完成后暂停");
        }
    }

    private void setSearchRunning(boolean running) {
        btnSearch.setEnabled(!running);
        btnCancel.setEnabled(running);
        btnPause.setEnabled(running);
        cbFullDocument.setEnabled(!running);
        btnSearch.setText(running ? "搜索中…" : "精准搜索");
        if (!running) {
            searchPaused = false;
            btnPause.setText("暂停");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + "B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + "KiB";
        return String.format("%.1fMiB", bytes / (1024f * 1024f));
    }

    private File copyAssetToCache(String fileName) {
        try {
            File cacheFile = new File(getCacheDir(), fileName);
            if (cacheFile.exists()) return cacheFile;

            try (InputStream input = getAssets().open(fileName);
                 FileOutputStream output = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    output.write(buffer, 0, length);
                }
                output.flush();
            }
            return cacheFile;
        } catch (Exception error) {
            error.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        if (searchManager != null) searchManager.close();
        super.onDestroy();
    }
}
