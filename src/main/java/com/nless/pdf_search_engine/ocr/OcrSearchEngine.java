package com.nless.pdf_search_engine.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;

import com.nless.pdf_search_engine.cache.OcrDiskCache;
import com.nless.pdf_search_engine.cache.OcrMemoryCache;
import com.nless.pdf_search_engine.cache.PdfDocumentFingerprint;
import com.nless.pdf_search_engine.cache.PdfSearchCacheKey;
import com.nless.pdf_search_engine.core.OcrDebugGeometryListener;
import com.nless.pdf_search_engine.core.OcrDebugGeometryType;
import com.nless.pdf_search_engine.core.OcrDebugRect;
import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchCancelChecker;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchQueryOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageMetrics;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;
import com.nless.pdf_search_engine.core.PdfSearchPageError;
import com.nless.pdf_search_engine.core.PdfSearchResultId;
import com.nless.pdf_search_engine.core.PdfSearchResultComparator;
import com.nless.pdf_search_engine.core.PdfSearchPauseChecker;
import com.nless.pdf_search_engine.core.PdfSearchProgressListener;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.index.PdfPageIndex;
import com.nless.pdf_search_engine.index.PdfPageIndexFactory;
import com.nless.pdf_search_engine.index.PdfPageIndexSearcher;
import com.nless.pdf_search_engine.paddle.PaddleOcrEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OCR 搜索实现。
 *
 * 默认启用单 OCR predictor 流水线：
 * - 渲染线程预取下一页；
 * - 当前搜索线程只执行一份 Paddle OCR；
 * - I/O 线程异步写入上一页缓存。
 *
 * 该实现不会并行调用多个 OCR predictor。
 */
public class OcrSearchEngine {

    private static final long DEFAULT_DISK_CACHE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_PREFETCH_PAGES = 2;

    private final Context appContext;
    private final OcrPageRenderer pageRenderer;
    private final OcrMemoryCache memoryCache;
    private final OcrDiskCache diskCache;

    public OcrSearchEngine(Context context) {
        this(context, new OcrMemoryCache(8));
    }

    public OcrSearchEngine(Context context, OcrMemoryCache memoryCache) {
        this.appContext = context.getApplicationContext();
        this.pageRenderer = new OcrPageRenderer();
        this.memoryCache = memoryCache != null ? memoryCache : new OcrMemoryCache(8);
        this.diskCache = new OcrDiskCache(appContext, DEFAULT_DISK_CACHE_BYTES);
    }

    /**
     * 只提取 OCR 页面内容，不执行关键词匹配。页面内容通过 onPageExtracted 回调。
     */
    public void extractPages(
            Uri pdfUri,
            PdfSearchOptions options,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            OcrPageSearchListener listener
    ) {
        search(
                pdfUri,
                null,
                options,
                null,
                cancelChecker,
                pauseChecker,
                null,
                listener
        );
    }

    public List<PdfSearchResult> searchCurrentPage(
            Uri pdfUri,
            String keyword,
            int pageIndex,
            int renderWidth
    ) {
        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = com.nless.pdf_search_engine.core.PdfSearchMode.OCR_ONLY;
        options.currentPageOnly = true;
        options.currentPage = pageIndex;
        options.ocrRenderWidth = renderWidth;
        return search(pdfUri, keyword, options, null, null, null, null);
    }

    public List<PdfSearchResult> searchRange(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            int renderWidth
    ) {
        return searchRange(pdfUri, keyword, startPage, endPage, renderWidth, null, null, null);
    }

    public List<PdfSearchResult> searchRange(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            int renderWidth,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker
    ) {
        return searchRange(
                pdfUri,
                keyword,
                startPage,
                endPage,
                renderWidth,
                progressListener,
                cancelChecker,
                null
        );
    }

    public List<PdfSearchResult> searchRange(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            int renderWidth,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker,
            OcrDebugGeometryListener debugGeometryListener
    ) {
        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = com.nless.pdf_search_engine.core.PdfSearchMode.OCR_ONLY;
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = true;
        options.startPage = startPage;
        options.endPage = endPage;
        options.currentPage = startPage;
        options.maxOcrPages = 0;
        options.ocrPageOrder = PdfSearchPageOrder.NATURAL;
        options.ocrRenderWidth = renderWidth;
        return search(
                pdfUri,
                keyword,
                options,
                progressListener,
                cancelChecker,
                null,
                debugGeometryListener
        );
    }

    public List<PdfSearchResult> search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            OcrDebugGeometryListener debugGeometryListener
    ) {
        return search(
                pdfUri,
                keyword,
                options,
                progressListener,
                cancelChecker,
                pauseChecker,
                debugGeometryListener,
                null
        );
    }

    public List<PdfSearchResult> search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            OcrDebugGeometryListener debugGeometryListener,
            OcrPageSearchListener pageSearchListener
    ) {
        List<PdfSearchResult> empty = new ArrayList<>();
        if (pdfUri == null) {
            return empty;
        }
        final boolean extractionOnly = keyword == null || keyword.trim().isEmpty();

        PdfSearchOptions request = options != null
                ? new PdfSearchOptions(options)
                : new PdfSearchOptions();
        diskCache.setMaxBytes(
                request.ocrDiskCacheMaxBytes > 0
                        ? request.ocrDiskCacheMaxBytes
                        : DEFAULT_DISK_CACHE_BYTES
        );

        boolean multiPageOcr = !request.currentPageOnly
                && request.allowFullDocumentOcr;
        if (request.enableOcrPipeline && multiPageOcr) {
            return searchWithPipeline(
                    pdfUri,
                    keyword,
                    request,
                    progressListener,
                    cancelChecker,
                    pauseChecker,
                    debugGeometryListener,
                    pageSearchListener,
                    extractionOnly
            );
        }

        return searchSerial(
                pdfUri,
                keyword,
                request,
                progressListener,
                cancelChecker,
                pauseChecker,
                debugGeometryListener,
                pageSearchListener,
                extractionOnly
        );
    }

    private List<PdfSearchResult> searchWithPipeline(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions request,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            OcrDebugGeometryListener debugGeometryListener,
            OcrPageSearchListener pageSearchListener,
            boolean extractionOnly
    ) {
        List<PdfSearchResult> results = new ArrayList<>();
        int renderWidth = request.ocrRenderWidth > 0 ? request.ocrRenderWidth : 1280;
        int prefetchPages = Math.max(
                1,
                Math.min(MAX_PREFETCH_PAGES, request.ocrPrefetchPages)
        );
        int cacheQueueCapacity = Math.max(1, request.ocrCacheWriteQueueCapacity);
        long startedAt = System.currentTimeMillis();
        String fingerprint = PdfDocumentFingerprint.build(appContext, pdfUri);

        ArrayBlockingQueue<PipelineItem> renderQueue =
                new ArrayBlockingQueue<>(prefetchPages);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        ExecutorService renderExecutor = java.util.concurrent.Executors
                .newSingleThreadExecutor(new NamedThreadFactory("pdf-ocr-render"));
        ThreadPoolExecutor cacheExecutor = createCacheExecutor(cacheQueueCapacity);

        Future<?> producer = renderExecutor.submit(() -> producePages(
                pdfUri,
                request,
                fingerprint,
                renderWidth,
                renderQueue,
                stopRequested,
                cancelChecker,
                pauseChecker
        ));

        boolean cancelled = false;
        try {
            PipelineItem start = takeNextItem(
                    renderQueue,
                    producer,
                    stopRequested,
                    cancelChecker
            );
            if (start == null) return results;
            if (start.type == PipelineItem.TYPE_ERROR) {
                throw new IllegalStateException("OCR 渲染线程启动失败", start.error);
            }
            if (start.type != PipelineItem.TYPE_START) {
                throw new IllegalStateException("OCR 流水线缺少 START 事件");
            }

            int pageCount = start.documentPageCount;
            int targetPages = start.targetPages;
            int processedPages = 0;

            while (!stopRequested.get()) {
                if (isCancelled(cancelChecker)) {
                    cancelled = true;
                    break;
                }
                if (pauseChecker != null && !pauseChecker.awaitIfPaused()) {
                    cancelled = true;
                    break;
                }

                PipelineItem item = takeNextItem(
                        renderQueue,
                        producer,
                        stopRequested,
                        cancelChecker
                );
                if (item == null || item.type == PipelineItem.TYPE_END) break;
                if (item.type == PipelineItem.TYPE_ERROR) {
                    throw new IllegalStateException("OCR 流水线失败", item.error);
                }
                if (item.type == PipelineItem.TYPE_PAGE_ERROR) {
                    processedPages++;
                    notifyPageFailed(pageSearchListener, item.pageError);
                    continue;
                }
                if (item.type != PipelineItem.TYPE_PAGE) continue;

                long consumedAtNanos = System.nanoTime();
                if (progressListener != null) {
                    progressListener.onProgress(
                            item.pageIndex,
                            pageCount,
                            PdfSearchSource.OCR
                    );
                }

                OcrPageResult pageResult = item.cachedResult;
                long ocrMillis = 0L;
                boolean cacheWriteQueued = false;

                Throwable pageFailure = null;
                try {
                    if (pageResult == null && item.renderedPage != null) {
                        long ocrStarted = System.nanoTime();
                        pageResult = PaddleOcrEngine.getInstance().recognizePage(
                                appContext,
                                item.renderedPage.bitmap,
                                item.renderedPage.pageWidth,
                                item.renderedPage.pageHeight
                        );
                        ocrMillis = nanosToMillis(System.nanoTime() - ocrStarted);

                        if (pageResult != null) {
                            memoryCache.put(item.cacheKey, pageResult);
                            if (request.usePersistentOcrCache) {
                                final OcrPageResult resultForCache = pageResult;
                                cacheExecutor.execute(() ->
                                        diskCache.put(item.cacheKey, resultForCache));
                                cacheWriteQueued = true;
                            }
                        }
                    }
                } catch (Throwable error) {
                    pageFailure = error;
                } finally {
                    if (item.renderedPage != null) item.renderedPage.recycle();
                }

                if (pageFailure != null) {
                    processedPages++;
                    notifyPageFailed(pageSearchListener, new PdfSearchPageError(
                            item.pageIndex,
                            PdfSearchPageError.STAGE_OCR,
                            "OCR 页面识别失败",
                            pageFailure,
                            true
                    ));
                    continue;
                }

                try {
                    notifyPageExtracted(
                            pageSearchListener,
                            item.pageIndex,
                            pageResult,
                            item.cacheSource
                    );
                } catch (Throwable error) {
                    processedPages++;
                    notifyPageFailed(pageSearchListener, new PdfSearchPageError(
                            item.pageIndex,
                            PdfSearchPageError.STAGE_CACHE,
                            "OCR 页面索引保存失败",
                            error,
                            true
                    ));
                    continue;
                }

                if (isCancelled(cancelChecker)) {
                    cancelled = true;
                    break;
                }

                long matchStarted = System.nanoTime();
                List<PdfSearchResult> pageResults;
                try {
                    pageResults = extractionOnly
                            ? new ArrayList<>()
                            : buildPageResults(
                                    item.pageIndex,
                                    keyword,
                                    pageResult,
                                    request,
                                    debugGeometryListener,
                                    fingerprint
                            );
                } catch (Throwable error) {
                    processedPages++;
                    notifyPageFailed(pageSearchListener, new PdfSearchPageError(
                            item.pageIndex,
                            PdfSearchPageError.STAGE_OCR,
                            "OCR 结果构建失败",
                            error,
                            true
                    ));
                    continue;
                }
                pageResults = applyResultLimit(results, pageResults, request.maxResults);
                results.addAll(pageResults);
                long resultBuildMillis = nanosToMillis(System.nanoTime() - matchStarted);
                processedPages++;

                PdfSearchPageMetrics metrics = new PdfSearchPageMetrics(
                        item.pageIndex,
                        item.cacheSource,
                        item.cacheReadMillis,
                        item.renderMillis,
                        nanosToMillis(consumedAtNanos - item.readyAtNanos),
                        ocrMillis,
                        resultBuildMillis,
                        nanosToMillis(System.nanoTime() - item.pageStartedAtNanos),
                        item.renderedBitmapBytes,
                        cacheWriteQueued
                );

                notifyPageCompleted(
                        pageSearchListener,
                        item.pageIndex,
                        pageCount,
                        processedPages,
                        targetPages,
                        pageResults,
                        item.cacheSource,
                        startedAt,
                        results.size(),
                        metrics
                );

                if (!extractionOnly
                        && request.stopAfterFirstOcrMatch
                        && !pageResults.isEmpty()) break;
                if (!extractionOnly
                        && request.maxResults > 0
                        && results.size() >= request.maxResults) break;
            }

            results.sort(PdfSearchResultComparator.INSTANCE);
            return results;
        } catch (InterruptedException error) {
            cancelled = true;
            Thread.currentThread().interrupt();
            return results;
        } finally {
            stopRequested.set(true);
            producer.cancel(true);
            renderExecutor.shutdownNow();
            recycleQueuedBitmaps(renderQueue);

            if (cancelled || isCancelled(cancelChecker)) {
                cacheExecutor.shutdownNow();
            } else {
                drainCacheExecutor(cacheExecutor, request.ocrCacheWriteDrainTimeoutMillis);
            }
        }
    }

    /**
     * 兼容/对照模式：关闭 enableOcrPipeline 后使用。
     */
    private List<PdfSearchResult> searchSerial(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions request,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            OcrDebugGeometryListener debugGeometryListener,
            OcrPageSearchListener pageSearchListener,
            boolean extractionOnly
    ) {
        List<PdfSearchResult> results = new ArrayList<>();
        int renderWidth = request.ocrRenderWidth > 0 ? request.ocrRenderWidth : 1280;
        long startedAt = System.currentTimeMillis();
        String fingerprint = PdfDocumentFingerprint.build(appContext, pdfUri);

        try (OcrDocumentSession session = pageRenderer.openSession(appContext, pdfUri)) {
            int pageCount = session.getPageCount();
            if (pageCount <= 0) return results;
            List<Integer> pageOrder = OcrPagePlanner.build(request, pageCount);
            int targetPages = pageOrder.size();
            int processedPages = 0;

            for (int pageIndex : pageOrder) {
                if (isCancelled(cancelChecker)) return results;
                if (pauseChecker != null && !pauseChecker.awaitIfPaused()) return results;
                if (progressListener != null) {
                    progressListener.onProgress(pageIndex, pageCount, PdfSearchSource.OCR);
                }

                long pageStarted = System.nanoTime();
                PagePreparation prepared = null;
                try {
                    prepared = preparePage(
                            session,
                            fingerprint,
                            pageIndex,
                            renderWidth,
                            request.usePersistentOcrCache,
                            request.ocrCacheNamespace
                    );
                    OcrPageResult pageResult = prepared.cachedResult;
                    long ocrMillis = 0L;
                    boolean cacheWriteQueued = false;
                    try {
                        if (pageResult == null && prepared.renderedPage != null) {
                            long ocrStarted = System.nanoTime();
                            pageResult = PaddleOcrEngine.getInstance().recognizePage(
                                    appContext,
                                    prepared.renderedPage.bitmap,
                                    prepared.renderedPage.pageWidth,
                                    prepared.renderedPage.pageHeight
                            );
                            ocrMillis = nanosToMillis(System.nanoTime() - ocrStarted);
                            if (pageResult != null) {
                                memoryCache.put(prepared.cacheKey, pageResult);
                                if (request.usePersistentOcrCache) {
                                    diskCache.put(prepared.cacheKey, pageResult);
                                    cacheWriteQueued = true;
                                }
                            }
                        }
                    } finally {
                        if (prepared.renderedPage != null) prepared.renderedPage.recycle();
                    }

                    notifyPageExtracted(
                            pageSearchListener,
                            pageIndex,
                            pageResult,
                            prepared.cacheSource
                    );

                    long matchStarted = System.nanoTime();
                    List<PdfSearchResult> pageResults = extractionOnly
                            ? new ArrayList<>()
                            : buildPageResults(
                                    pageIndex,
                                    keyword,
                                    pageResult,
                                    request,
                                    debugGeometryListener,
                                    fingerprint
                            );
                    pageResults = applyResultLimit(results, pageResults, request.maxResults);
                    results.addAll(pageResults);
                    long matchMillis = nanosToMillis(System.nanoTime() - matchStarted);
                    processedPages++;

                    PdfSearchPageMetrics metrics = new PdfSearchPageMetrics(
                            pageIndex,
                            prepared.cacheSource,
                            prepared.cacheReadMillis,
                            prepared.renderMillis,
                            0L,
                            ocrMillis,
                            matchMillis,
                            nanosToMillis(System.nanoTime() - pageStarted),
                            prepared.renderedBitmapBytes,
                            cacheWriteQueued
                    );
                    notifyPageCompleted(
                            pageSearchListener,
                            pageIndex,
                            pageCount,
                            processedPages,
                            targetPages,
                            pageResults,
                            prepared.cacheSource,
                            startedAt,
                            results.size(),
                            metrics
                    );

                    if (!extractionOnly
                            && request.stopAfterFirstOcrMatch
                            && !pageResults.isEmpty()) break;
                    if (!extractionOnly
                            && request.maxResults > 0
                            && results.size() >= request.maxResults) break;
                } catch (Throwable error) {
                    if (prepared != null && prepared.renderedPage != null) {
                        prepared.renderedPage.recycle();
                    }
                    processedPages++;
                    notifyPageFailed(pageSearchListener, new PdfSearchPageError(
                            pageIndex,
                            prepared == null
                                    ? PdfSearchPageError.STAGE_RENDER
                                    : PdfSearchPageError.STAGE_OCR,
                            prepared == null ? "PDF 页面渲染失败" : "OCR 页面处理失败",
                            error,
                            true
                    ));
                }
            }

            results.sort(PdfSearchResultComparator.INSTANCE);
            return results;
        } catch (Exception error) {
            throw new IllegalStateException("OCR 搜索任务失败", error);
        }
    }

    private void producePages(
            Uri pdfUri,
            PdfSearchOptions request,
            String fingerprint,
            int renderWidth,
            ArrayBlockingQueue<PipelineItem> queue,
            AtomicBoolean stopRequested,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker
    ) {
        try (OcrDocumentSession session = pageRenderer.openSession(appContext, pdfUri)) {
            int pageCount = session.getPageCount();
            List<Integer> pageOrder = pageCount > 0
                    ? OcrPagePlanner.build(request, pageCount)
                    : new ArrayList<>();

            if (!offerItem(
                    queue,
                    PipelineItem.start(pageCount, pageOrder.size()),
                    stopRequested,
                    cancelChecker
            )) return;

            for (int pageIndex : pageOrder) {
                if (shouldStop(stopRequested, cancelChecker)) break;
                if (pauseChecker != null && !pauseChecker.awaitIfPaused()) break;
                if (shouldStop(stopRequested, cancelChecker)) break;

                PipelineItem item;
                try {
                    PagePreparation prepared = preparePage(
                            session,
                            fingerprint,
                            pageIndex,
                            renderWidth,
                            request.usePersistentOcrCache,
                            request.ocrCacheNamespace
                    );
                    item = PipelineItem.page(prepared);
                } catch (Throwable error) {
                    item = PipelineItem.pageError(new PdfSearchPageError(
                            pageIndex,
                            PdfSearchPageError.STAGE_RENDER,
                            "PDF 页面渲染失败",
                            error,
                            true
                    ));
                }
                if (!offerItem(queue, item, stopRequested, cancelChecker)) {
                    item.recycle();
                    break;
                }
            }

            offerItem(queue, PipelineItem.end(), stopRequested, cancelChecker);
        } catch (Throwable error) {
            offerItem(queue, PipelineItem.error(error), stopRequested, cancelChecker);
        }
    }

    private PagePreparation preparePage(
            OcrDocumentSession session,
            String fingerprint,
            int pageIndex,
            int renderWidth,
            boolean usePersistentCache,
            String cacheNamespace
    ) {
        long pageStarted = System.nanoTime();
        String key = PdfSearchCacheKey.buildOcrPageKey(
                fingerprint,
                pageIndex,
                renderWidth,
                cacheNamespace
        );

        long cacheStarted = System.nanoTime();
        OcrPageResult cached = memoryCache.get(key);
        PdfSearchCacheSource source = PdfSearchCacheSource.NONE;
        if (cached != null) {
            source = PdfSearchCacheSource.MEMORY;
        } else if (usePersistentCache) {
            cached = diskCache.get(key);
            if (cached != null) {
                memoryCache.put(key, cached);
                source = PdfSearchCacheSource.DISK;
            }
        }
        long cacheReadMillis = nanosToMillis(System.nanoTime() - cacheStarted);

        if (cached != null) {
            return new PagePreparation(
                    pageIndex,
                    key,
                    cached,
                    null,
                    source,
                    cacheReadMillis,
                    0L,
                    0L,
                    pageStarted
            );
        }

        long renderStarted = System.nanoTime();
        OcrRenderedPage renderedPage = session.renderPage(pageIndex, renderWidth);
        long renderMillis = nanosToMillis(System.nanoTime() - renderStarted);
        long bitmapBytes = estimateBitmapBytes(
                renderedPage != null ? renderedPage.bitmap : null
        );

        return new PagePreparation(
                pageIndex,
                key,
                null,
                renderedPage,
                PdfSearchCacheSource.NONE,
                cacheReadMillis,
                renderMillis,
                bitmapBytes,
                pageStarted
        );
    }

    private List<PdfSearchResult> buildPageResults(
            int pageIndex,
            String keyword,
            OcrPageResult pageResult,
            PdfSearchOptions request,
            OcrDebugGeometryListener debugGeometryListener,
            String documentFingerprint
    ) {
        List<PdfSearchResult> pageResults = new ArrayList<>();
        if (pageResult == null) return pageResults;

        if (debugGeometryListener != null) {
            debugGeometryListener.onOcrDebugGeometry(
                    pageIndex,
                    buildDebugGeometry(pageIndex, pageResult)
            );
        }
        PdfPageIndex pageIndexData = PdfPageIndexFactory.fromOcr(
                pageIndex,
                pageResult,
                request.detectMultiColumnLayout,
                request.multiColumnMinGapRatio
        );
        if (pageIndexData == null || keyword == null || keyword.trim().isEmpty()) {
            return pageResults;
        }

        PdfSearchQueryOptions queryOptions = request.queryOptions != null
                ? new PdfSearchQueryOptions(request.queryOptions)
                : new PdfSearchQueryOptions();
        if (!request.ignoreCase) queryOptions.caseSensitive = true;

        pageResults.addAll(PdfPageIndexSearcher.searchPage(
                documentFingerprint,
                pageIndexData,
                keyword,
                queryOptions
        ));
        pageResults.sort(PdfSearchResultComparator.INSTANCE);
        return pageResults;
    }

    private List<PdfSearchResult> applyResultLimit(
            List<PdfSearchResult> accumulated,
            List<PdfSearchResult> pageResults,
            int maxResults
    ) {
        if (maxResults <= 0) return pageResults;
        int remaining = maxResults - accumulated.size();
        if (remaining <= 0) return new ArrayList<>();
        if (pageResults.size() <= remaining) return pageResults;
        return new ArrayList<>(pageResults.subList(0, remaining));
    }

    private void notifyPageExtracted(
            OcrPageSearchListener listener,
            int pageIndex,
            OcrPageResult pageResult,
            PdfSearchCacheSource cacheSource
    ) {
        if (listener != null && pageResult != null) {
            listener.onPageExtracted(pageIndex, pageResult, cacheSource);
        }
    }

    private void notifyPageCompleted(
            OcrPageSearchListener listener,
            int pageIndex,
            int pageCount,
            int processedPages,
            int targetPages,
            List<PdfSearchResult> pageResults,
            PdfSearchCacheSource cacheSource,
            long startedAt,
            int cumulativeMatchCount,
            PdfSearchPageMetrics metrics
    ) {
        if (listener == null) return;
        listener.onPageCompleted(
                pageIndex,
                pageCount,
                processedPages,
                targetPages,
                Collections.unmodifiableList(new ArrayList<>(pageResults)),
                cacheSource,
                System.currentTimeMillis() - startedAt,
                cumulativeMatchCount
        );
        listener.onPageMetrics(metrics);
    }

    private void notifyPageFailed(
            OcrPageSearchListener listener,
            PdfSearchPageError error
    ) {
        if (listener != null && error != null) listener.onPageFailed(error);
    }

    private PipelineItem takeNextItem(
            ArrayBlockingQueue<PipelineItem> queue,
            Future<?> producer,
            AtomicBoolean stopRequested,
            PdfSearchCancelChecker cancelChecker
    ) throws InterruptedException {
        while (!shouldStop(stopRequested, cancelChecker)) {
            PipelineItem item = queue.poll(100L, TimeUnit.MILLISECONDS);
            if (item != null) return item;
            if (producer.isDone() && queue.isEmpty()) return null;
        }
        return null;
    }

    private boolean offerItem(
            ArrayBlockingQueue<PipelineItem> queue,
            PipelineItem item,
            AtomicBoolean stopRequested,
            PdfSearchCancelChecker cancelChecker
    ) {
        while (!shouldStop(stopRequested, cancelChecker)) {
            try {
                if (queue.offer(item, 100L, TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean shouldStop(
            AtomicBoolean stopRequested,
            PdfSearchCancelChecker checker
    ) {
        return stopRequested.get() || isCancelled(checker);
    }

    private boolean isCancelled(PdfSearchCancelChecker checker) {
        return checker != null && checker.isCancelled();
    }

    private ThreadPoolExecutor createCacheExecutor(int queueCapacity) {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                new NamedThreadFactory("pdf-ocr-cache"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void drainCacheExecutor(
            ThreadPoolExecutor executor,
            long timeoutMillis
    ) {
        executor.shutdown();
        if (timeoutMillis <= 0L) return;
        try {
            if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void recycleQueuedBitmaps(ArrayBlockingQueue<PipelineItem> queue) {
        PipelineItem item;
        while ((item = queue.poll()) != null) item.recycle();
    }

    private long estimateBitmapBytes(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0L;
        try {
            return Math.max(0L, bitmap.getAllocationByteCount());
        } catch (Throwable ignored) {
            return (long) bitmap.getWidth() * bitmap.getHeight() * 4L;
        }
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanos)));
    }

    private static final class PagePreparation {
        final int pageIndex;
        final String cacheKey;
        final OcrPageResult cachedResult;
        final OcrRenderedPage renderedPage;
        final PdfSearchCacheSource cacheSource;
        final long cacheReadMillis;
        final long renderMillis;
        final long renderedBitmapBytes;
        final long pageStartedAtNanos;

        PagePreparation(
                int pageIndex,
                String cacheKey,
                OcrPageResult cachedResult,
                OcrRenderedPage renderedPage,
                PdfSearchCacheSource cacheSource,
                long cacheReadMillis,
                long renderMillis,
                long renderedBitmapBytes,
                long pageStartedAtNanos
        ) {
            this.pageIndex = pageIndex;
            this.cacheKey = cacheKey;
            this.cachedResult = cachedResult;
            this.renderedPage = renderedPage;
            this.cacheSource = cacheSource != null
                    ? cacheSource
                    : PdfSearchCacheSource.NONE;
            this.cacheReadMillis = cacheReadMillis;
            this.renderMillis = renderMillis;
            this.renderedBitmapBytes = renderedBitmapBytes;
            this.pageStartedAtNanos = pageStartedAtNanos;
        }
    }

    private static final class PipelineItem {
        static final int TYPE_START = 1;
        static final int TYPE_PAGE = 2;
        static final int TYPE_END = 3;
        static final int TYPE_ERROR = 4;
        static final int TYPE_PAGE_ERROR = 5;

        final int type;
        final int pageIndex;
        final int documentPageCount;
        final int targetPages;
        final String cacheKey;
        final OcrPageResult cachedResult;
        final OcrRenderedPage renderedPage;
        final PdfSearchCacheSource cacheSource;
        final long cacheReadMillis;
        final long renderMillis;
        final long renderedBitmapBytes;
        final long pageStartedAtNanos;
        final long readyAtNanos;
        final Throwable error;
        final PdfSearchPageError pageError;

        private PipelineItem(
                int type,
                int pageIndex,
                int documentPageCount,
                int targetPages,
                String cacheKey,
                OcrPageResult cachedResult,
                OcrRenderedPage renderedPage,
                PdfSearchCacheSource cacheSource,
                long cacheReadMillis,
                long renderMillis,
                long renderedBitmapBytes,
                long pageStartedAtNanos,
                Throwable error,
                PdfSearchPageError pageError
        ) {
            this.type = type;
            this.pageIndex = pageIndex;
            this.documentPageCount = documentPageCount;
            this.targetPages = targetPages;
            this.cacheKey = cacheKey;
            this.cachedResult = cachedResult;
            this.renderedPage = renderedPage;
            this.cacheSource = cacheSource;
            this.cacheReadMillis = cacheReadMillis;
            this.renderMillis = renderMillis;
            this.renderedBitmapBytes = renderedBitmapBytes;
            this.pageStartedAtNanos = pageStartedAtNanos;
            this.readyAtNanos = System.nanoTime();
            this.error = error;
            this.pageError = pageError;
        }

        static PipelineItem start(int pageCount, int targetPages) {
            return new PipelineItem(
                    TYPE_START, -1, pageCount, targetPages,
                    null, null, null, PdfSearchCacheSource.NONE,
                    0L, 0L, 0L, System.nanoTime(), null, null
            );
        }

        static PipelineItem page(PagePreparation prepared) {
            return new PipelineItem(
                    TYPE_PAGE,
                    prepared.pageIndex,
                    -1,
                    -1,
                    prepared.cacheKey,
                    prepared.cachedResult,
                    prepared.renderedPage,
                    prepared.cacheSource,
                    prepared.cacheReadMillis,
                    prepared.renderMillis,
                    prepared.renderedBitmapBytes,
                    prepared.pageStartedAtNanos,
                    null,
                    null
            );
        }

        static PipelineItem end() {
            return new PipelineItem(
                    TYPE_END, -1, -1, -1,
                    null, null, null, PdfSearchCacheSource.NONE,
                    0L, 0L, 0L, System.nanoTime(), null, null
            );
        }

        static PipelineItem error(Throwable error) {
            return new PipelineItem(
                    TYPE_ERROR, -1, -1, -1,
                    null, null, null, PdfSearchCacheSource.NONE,
                    0L, 0L, 0L, System.nanoTime(), error, null
            );
        }

        static PipelineItem pageError(PdfSearchPageError error) {
            return new PipelineItem(
                    TYPE_PAGE_ERROR,
                    error != null ? error.pageIndex : -1,
                    -1,
                    -1,
                    null,
                    null,
                    null,
                    PdfSearchCacheSource.NONE,
                    0L,
                    0L,
                    0L,
                    System.nanoTime(),
                    null,
                    error
            );
        }

        void recycle() {
            if (renderedPage != null) renderedPage.recycle();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }

    private List<OcrDebugRect> buildDebugGeometry(
            int pageIndex,
            OcrPageResult pageResult
    ) {
        List<OcrDebugRect> debugRects = new ArrayList<>();
        if (pageResult == null || pageResult.blocks == null
                || pageResult.bitmapWidth <= 0 || pageResult.bitmapHeight <= 0) {
            return debugRects;
        }

        for (OcrTextBlock block : pageResult.blocks) {
            if (block == null || block.rectInBitmap == null) {
                continue;
            }

            RectF detectionRatio = bitmapRectToPageRatio(
                    block.rectInBitmap,
                    pageResult.bitmapWidth,
                    pageResult.bitmapHeight
            );
            if (detectionRatio != null) {
                debugRects.add(new OcrDebugRect(
                        pageIndex,
                        OcrDebugGeometryType.DETECTION_BLOCK,
                        detectionRatio,
                        block.text
                ));
            }

            if (!block.hasTokenBoxes()) {
                continue;
            }

            for (int tokenIndex = 0; tokenIndex < block.getTokenCount(); tokenIndex++) {
                int offset = tokenIndex * 4;
                float left = block.tokenBoxesInLine[offset];
                float top = block.tokenBoxesInLine[offset + 1];
                float right = block.tokenBoxesInLine[offset + 2];
                float bottom = block.tokenBoxesInLine[offset + 3];
                if (!isFiniteUnitRect(left, top, right, bottom)) {
                    continue;
                }

                RectF tokenBitmapRect;
                if (block.hasValidQuad()) {
                    float[] q = block.quadInBitmap;
                    tokenBitmapRect = boundingRect(
                            mapPointInQuad(q, left, top),
                            mapPointInQuad(q, right, top),
                            mapPointInQuad(q, right, bottom),
                            mapPointInQuad(q, left, bottom)
                    );
                } else {
                    RectF lineRect = block.rectInBitmap;
                    tokenBitmapRect = new RectF(
                            lineRect.left + lineRect.width() * left,
                            lineRect.top + lineRect.height() * top,
                            lineRect.left + lineRect.width() * right,
                            lineRect.top + lineRect.height() * bottom
                    );
                }

                RectF tokenRatio = bitmapRectToPageRatio(
                        tokenBitmapRect,
                        pageResult.bitmapWidth,
                        pageResult.bitmapHeight
                );
                if (tokenRatio == null) {
                    continue;
                }

                String label = null;
                if (block.text != null) {
                    int start = Math.max(0, Math.min(
                            block.tokenUtf16Starts[tokenIndex],
                            block.text.length()
                    ));
                    int end = Math.max(start, Math.min(
                            block.tokenUtf16Ends[tokenIndex],
                            block.text.length()
                    ));
                    label = block.text.substring(start, end);
                }

                debugRects.add(new OcrDebugRect(
                        pageIndex,
                        OcrDebugGeometryType.TOKEN_BOX,
                        tokenRatio,
                        label
                ));
            }
        }

        return debugRects;
    }

    private List<PdfSearchResult> buildSearchResultsFromOcrPage(
            int pageIndex,
            String keyword,
            OcrPageResult pageResult,
            boolean ignoreCase,
            String documentFingerprint
    ) {
        List<PdfSearchResult> results = new ArrayList<>();

        if (pageResult == null || pageResult.blocks == null) {
            return results;
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            return results;
        }

        int logicalOrdinal = 0;
        for (OcrTextBlock block : pageResult.blocks) {
            if (block == null || block.text == null || block.rectInBitmap == null) {
                continue;
            }

            String originalText = block.text;
            int fromIndex = 0;

            while (true) {
                int matchIndex = findMatch(
                        originalText,
                        keyword,
                        fromIndex,
                        ignoreCase
                );
                if (matchIndex < 0) {
                    break;
                }

                RectF keywordBitmapRect = estimateKeywordRectInBlock(
                        block,
                        originalText,
                        matchIndex,
                        keyword.length()
                );

                RectF pdfPointRect = bitmapRectToPdfPointRect(
                        keywordBitmapRect,
                        pageResult.bitmapWidth,
                        pageResult.bitmapHeight,
                        pageResult.pageWidth,
                        pageResult.pageHeight
                );

                if (pdfPointRect != null) {
                    List<PdfSearchRect> rects = new ArrayList<>();

                    rects.add(new PdfSearchRect(
                            pageIndex,
                            pdfPointRect,
                            pageResult.pageWidth,
                            pageResult.pageHeight
                    ));

                    String resultId = PdfSearchResultId.create(
                            documentFingerprint,
                            pageIndex,
                            PdfSearchSource.OCR,
                            logicalOrdinal++,
                            keyword,
                            rects
                    );
                    results.add(new PdfSearchResult(
                            resultId,
                            keyword,
                            pageIndex,
                            PdfSearchSource.OCR,
                            rects,
                            block.text,
                            matchIndex,
                            keyword.length()
                    ));
                }

                fromIndex = matchIndex + Math.max(1, keyword.length());
            }
        }

        return results;
    }


    private int findMatch(
            String text,
            String keyword,
            int fromIndex,
            boolean ignoreCase
    ) {
        if (text == null || keyword == null || keyword.isEmpty()) return -1;
        int safeFrom = Math.max(0, fromIndex);
        if (!ignoreCase) return text.indexOf(keyword, safeFrom);

        int lastStart = text.length() - keyword.length();
        for (int index = safeFrom; index <= lastStart; index++) {
            if (text.regionMatches(true, index, keyword, 0, keyword.length())) {
                return index;
            }
        }
        return -1;
    }

    private RectF estimateKeywordRectInBlock(
            OcrTextBlock block,
            String blockText,
            int matchStart,
            int keywordLength
    ) {
        if (block == null || block.rectInBitmap == null
                || blockText == null || blockText.isEmpty()) {
            return block != null ? block.rectInBitmap : null;
        }

        int totalLength = blockText.length();
        int safeMatchStart = Math.max(0, Math.min(matchStart, totalLength));
        int matchEnd = Math.max(
                safeMatchStart,
                Math.min(totalLength, safeMatchStart + Math.max(0, keywordLength))
        );

        /*
         * 优先使用 native 返回的 CTC token 对齐框。它不是按字符串长度均分，
         * 而是利用识别网络时间步和原图前景像素收紧，因此对“1”“i”“/”等
         * 窄字符、比例字体以及长文本行中的局部命中明显更准确。
         */
        RectF alignedRect = estimateKeywordRectFromTokenBoxes(
                block,
                safeMatchStart,
                matchEnd
        );
        if (alignedRect != null) {
            return alignedRect;
        }

        float totalWeight = calculateTextVisualWeight(blockText, 0, totalLength);
        if (totalWeight <= 0.01f) {
            totalWeight = totalLength;
        }

        float preKeywordWeight = calculateTextVisualWeight(blockText, 0, safeMatchStart);
        float keywordWeight = calculateTextVisualWeight(blockText, safeMatchStart, matchEnd);

        float startRatio = clamp01(preKeywordWeight / totalWeight);
        float endRatio = clamp01((preKeywordWeight + keywordWeight) / totalWeight);
        if (endRatio <= startRatio) {
            return null;
        }

        RectF blockRect = block.rectInBitmap;
        float blockWidth = blockRect.width();
        float blockHeight = blockRect.height();
        if (blockWidth <= 0f || blockHeight <= 0f) return null;

        /*
         * DB 文本检测框本身经过 unclip，会比真实字形略宽。只做很小的内容区收缩，
         * 不再像旧版那样对最终关键词框重复加 padding。
         */
        float contentInsetPx = Math.min(blockHeight * 0.10f, blockWidth * 0.015f);
        float contentInsetRatio = Math.min(0.08f, contentInsetPx / blockWidth);
        startRatio = contentInsetRatio + startRatio * (1f - 2f * contentInsetRatio);
        endRatio = contentInsetRatio + endRatio * (1f - 2f * contentInsetRatio);

        /*
         * OCR 检测框上下通常包含少量背景。每侧收缩 8%，替代旧版的 0.78 倍高度、
         * 18% 上移、最小/最大高度钳制等多重经验修正。
         */
        final float verticalInsetRatio = 0.08f;

        if (block.hasValidQuad()) {
            float[] q = block.quadInBitmap;

            Point topStart = interpolate(q[0], q[1], q[2], q[3], startRatio);
            Point topEnd = interpolate(q[0], q[1], q[2], q[3], endRatio);
            Point bottomStart = interpolate(q[6], q[7], q[4], q[5], startRatio);
            Point bottomEnd = interpolate(q[6], q[7], q[4], q[5], endRatio);

            Point innerTopStart = interpolate(topStart, bottomStart, verticalInsetRatio);
            Point innerTopEnd = interpolate(topEnd, bottomEnd, verticalInsetRatio);
            Point innerBottomStart = interpolate(bottomStart, topStart, verticalInsetRatio);
            Point innerBottomEnd = interpolate(bottomEnd, topEnd, verticalInsetRatio);

            return boundingRect(
                    innerTopStart,
                    innerTopEnd,
                    innerBottomEnd,
                    innerBottomStart
            );
        }

        float left = blockRect.left + blockWidth * startRatio;
        float right = blockRect.left + blockWidth * endRatio;
        float top = blockRect.top + blockHeight * verticalInsetRatio;
        float bottom = blockRect.bottom - blockHeight * verticalInsetRatio;

        if (right <= left || bottom <= top) return null;
        return new RectF(left, top, right, bottom);
    }

    private RectF estimateKeywordRectFromTokenBoxes(
            OcrTextBlock block,
            int matchStart,
            int matchEnd
    ) {
        if (block == null || !block.hasTokenBoxes() || matchEnd <= matchStart) {
            return null;
        }

        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        int firstMatchedToken = -1;
        int lastMatchedToken = -1;
        int matchedTokenCount = 0;

        for (int tokenIndex = 0; tokenIndex < block.getTokenCount(); tokenIndex++) {
            int tokenStart = block.tokenUtf16Starts[tokenIndex];
            int tokenEnd = block.tokenUtf16Ends[tokenIndex];

            // token 与命中 UTF-16 区间有交集。
            if (tokenEnd <= matchStart || tokenStart >= matchEnd) {
                continue;
            }

            int offset = tokenIndex * 4;
            float tokenLeft = block.tokenBoxesInLine[offset];
            float tokenTop = block.tokenBoxesInLine[offset + 1];
            float tokenRight = block.tokenBoxesInLine[offset + 2];
            float tokenBottom = block.tokenBoxesInLine[offset + 3];

            if (!isFiniteUnitRect(tokenLeft, tokenTop, tokenRight, tokenBottom)) {
                continue;
            }

            if (firstMatchedToken < 0) {
                firstMatchedToken = tokenIndex;
            }
            lastMatchedToken = tokenIndex;
            matchedTokenCount++;

            left = Math.min(left, tokenLeft);
            top = Math.min(top, tokenTop);
            right = Math.max(right, tokenRight);
            bottom = Math.max(bottom, tokenBottom);
        }

        if (matchedTokenCount <= 0 || right <= left || bottom <= top) {
            return null;
        }

        /*
         * 以相邻 token 中心的中点作为最终框不可越过的硬边界。
         * 正常字距下 token 紧致框不会触碰该边界；只有扫描噪点或 CTC 对齐
         * 造成框向相邻字符扩张时才会被夹紧。
         */
        float hardLeft = 0f;
        float hardRight = 1f;

        if (firstMatchedToken > 0) {
            float previousCenter = getTokenCenterX(block, firstMatchedToken - 1);
            float firstCenter = getTokenCenterX(block, firstMatchedToken);
            if (!Float.isNaN(previousCenter) && !Float.isNaN(firstCenter)) {
                hardLeft = clamp01((previousCenter + firstCenter) * 0.5f);
            }
        }

        if (lastMatchedToken >= 0 && lastMatchedToken + 1 < block.getTokenCount()) {
            float lastCenter = getTokenCenterX(block, lastMatchedToken);
            float nextCenter = getTokenCenterX(block, lastMatchedToken + 1);
            if (!Float.isNaN(lastCenter) && !Float.isNaN(nextCenter)) {
                hardRight = clamp01((lastCenter + nextCenter) * 0.5f);
            }
        }

        left = Math.max(left, hardLeft);
        right = Math.min(right, hardRight);
        if (right <= left) {
            return null;
        }

        /*
         * 查询级横向余量：
         * - 单 token：最多 0.25px，且不超过自身宽度的 8%；
         * - 多 token：最多 0.60px，且不超过整体宽度的 4%。
         *
         * 这样数字“1”、字母 i/l 等窄字符不会被 1px 固定 padding 放宽，
         * 多字符关键词又能保留很小的抗锯齿安全边缘。
         */
        RectF blockRect = block.rectInBitmap;
        if (blockRect == null || blockRect.width() <= 0f || blockRect.height() <= 0f) {
            return null;
        }

        float matchWidth = right - left;
        float maxPaddingPx = matchedTokenCount == 1 ? 0.25f : 0.60f;
        float maxPaddingRatio = maxPaddingPx / blockRect.width();
        float proportionalLimit = matchWidth * (matchedTokenCount == 1 ? 0.08f : 0.04f);
        float horizontalPadding = Math.min(maxPaddingRatio, proportionalLimit);

        left = Math.max(hardLeft, left - horizontalPadding);
        right = Math.min(hardRight, right + horizontalPadding);
        if (right <= left) {
            return null;
        }

        if (block.hasValidQuad()) {
            float[] q = block.quadInBitmap;
            Point topLeft = mapPointInQuad(q, left, top);
            Point topRight = mapPointInQuad(q, right, top);
            Point bottomRight = mapPointInQuad(q, right, bottom);
            Point bottomLeft = mapPointInQuad(q, left, bottom);
            return boundingRect(topLeft, topRight, bottomRight, bottomLeft);
        }

        return new RectF(
                blockRect.left + blockRect.width() * left,
                blockRect.top + blockRect.height() * top,
                blockRect.left + blockRect.width() * right,
                blockRect.top + blockRect.height() * bottom
        );
    }

    private float getTokenCenterX(OcrTextBlock block, int tokenIndex) {
        if (block == null || !block.hasTokenBoxes()
                || tokenIndex < 0 || tokenIndex >= block.getTokenCount()) {
            return Float.NaN;
        }

        int offset = tokenIndex * 4;
        float left = block.tokenBoxesInLine[offset];
        float top = block.tokenBoxesInLine[offset + 1];
        float right = block.tokenBoxesInLine[offset + 2];
        float bottom = block.tokenBoxesInLine[offset + 3];
        if (!isFiniteUnitRect(left, top, right, bottom)) {
            return Float.NaN;
        }
        return (left + right) * 0.5f;
    }

    private Point mapPointInQuad(float[] quad, float xRatio, float yRatio) {
        float x = clamp01(xRatio);
        float y = clamp01(yRatio);
        Point top = interpolate(quad[0], quad[1], quad[2], quad[3], x);
        Point bottom = interpolate(quad[6], quad[7], quad[4], quad[5], x);
        return interpolate(top, bottom, y);
    }

    private boolean isFiniteUnitRect(float left, float top, float right, float bottom) {
        return !Float.isNaN(left)
                && !Float.isNaN(top)
                && !Float.isNaN(right)
                && !Float.isNaN(bottom)
                && !Float.isInfinite(left)
                && !Float.isInfinite(top)
                && !Float.isInfinite(right)
                && !Float.isInfinite(bottom)
                && left >= 0f
                && top >= 0f
                && right <= 1f
                && bottom <= 1f
                && right > left
                && bottom > top;
    }

    /**
     * 旧版 native 或无 token 对齐信息时的回退算法：估算字符串视觉宽度。
     * 新版正常路径会优先使用 CTC token 框。
     */
    private float calculateTextVisualWeight(String text, int start, int end) {
        float weight = 0f;
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(text.length(), end);

        for (int i = safeStart; i < safeEnd; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                weight += 1.0f;
            } else if (Character.isUpperCase(codePoint)) {
                weight += 0.62f;
            } else if (Character.isLowerCase(codePoint)) {
                if (codePoint == 'i' || codePoint == 'l' || codePoint == 'f'
                        || codePoint == 't' || codePoint == 'r') {
                    weight += 0.34f;
                } else if (codePoint == 'm' || codePoint == 'w') {
                    weight += 0.72f;
                } else {
                    weight += 0.52f;
                }
            } else if (Character.isDigit(codePoint)) {
                weight += 0.58f;
            } else if (Character.isWhitespace(codePoint)) {
                weight += 0.28f;
            } else {
                int type = Character.getType(codePoint);
                if (type == Character.CONNECTOR_PUNCTUATION
                        || type == Character.DASH_PUNCTUATION
                        || type == Character.START_PUNCTUATION
                        || type == Character.END_PUNCTUATION
                        || type == Character.OTHER_PUNCTUATION) {
                    weight += codePoint > 0xFF ? 1.0f : 0.30f;
                } else {
                    weight += codePoint > 0xFF ? 1.0f : 0.52f;
                }
            }
        }
        return weight;
    }

    private RectF boundingRect(Point... points) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (Point point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }

        if (maxX <= minX || maxY <= minY) return null;
        return new RectF(minX, minY, maxX, maxY);
    }

    private Point interpolate(float x1, float y1, float x2, float y2, float ratio) {
        return new Point(
                x1 + (x2 - x1) * ratio,
                y1 + (y2 - y1) * ratio
        );
    }

    private Point interpolate(Point start, Point end, float ratio) {
        return interpolate(start.x, start.y, end.x, end.y, ratio);
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private RectF bitmapRectToPageRatio(
            RectF bitmapRect,
            int bitmapWidth,
            int bitmapHeight
    ) {
        if (bitmapRect == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return null;
        }

        float left = clamp01(bitmapRect.left / bitmapWidth);
        float top = clamp01(bitmapRect.top / bitmapHeight);
        float right = clamp01(bitmapRect.right / bitmapWidth);
        float bottom = clamp01(bitmapRect.bottom / bitmapHeight);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new RectF(left, top, right, bottom);
    }

    /**
     * OCR Bitmap 坐标转换成真实 PDF point 坐标。
     *
     * OCR Bitmap 坐标：
     * - 原点左上
     * - Y 向下
     *
     * PDF point 坐标：
     * - 原点左下
     * - Y 向上
     */
    private RectF bitmapRectToPdfPointRect(
            RectF bitmapRect,
            int bitmapWidth,
            int bitmapHeight,
            float pageWidth,
            float pageHeight
    ) {
        if (bitmapRect == null) {
            return null;
        }

        if (bitmapWidth <= 0 || bitmapHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return null;
        }

        float left = bitmapRect.left / bitmapWidth * pageWidth;
        float right = bitmapRect.right / bitmapWidth * pageWidth;

        float bottom = pageHeight - (bitmapRect.bottom / bitmapHeight * pageHeight);
        float top = pageHeight - (bitmapRect.top / bitmapHeight * pageHeight);

        if (right <= left || top <= bottom) {
            return null;
        }

        return new RectF(left, bottom, right, top);
    }


    /** 在后台线程调用，提前加载模型。 */
    public void warmUp() {
        PaddleOcrEngine.getInstance().initEngine(appContext);
    }

    public void releaseResources() {
        PaddleOcrEngine.getInstance().releaseIfIdle();
    }

    public void trimMemory(int level) {
        // Android ComponentCallbacks2 中 RUNNING_LOW=10、BACKGROUND=40。
        if (level >= 10) memoryCache.clear();
        if (level >= 40) PaddleOcrEngine.getInstance().releaseIfIdle();
    }

    public void clearCache() {
        memoryCache.clear();
        diskCache.clear();
    }

    public long getPersistentCacheSizeBytes() {
        return diskCache.sizeBytes();
    }
}
