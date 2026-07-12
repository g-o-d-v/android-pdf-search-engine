package com.nless.pdf_search_engine.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.nless.pdf_search_engine.cache.PdfDocumentFingerprint;
import com.nless.pdf_search_engine.index.PdfDocumentIndex;
import com.nless.pdf_search_engine.index.PdfDocumentIndexBuildResult;
import com.nless.pdf_search_engine.index.PdfDocumentIndexListener;
import com.nless.pdf_search_engine.index.PdfDocumentIndexer;
import com.nless.pdf_search_engine.index.PdfPageIndex;
import com.nless.pdf_search_engine.index.PdfPageIndexSearcher;
import com.nless.pdf_search_engine.index.PdfSearchReadingOrder;
import com.nless.pdf_search_engine.index.PdfSearchResultDeduplicator;
import com.nless.pdf_search_engine.ocr.OcrPageSearchListener;
import com.nless.pdf_search_engine.ocr.OcrSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextPageInfo;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchReport;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文本层与 OCR 的统一调度器。
 *
 * 主要流程：
 * - PDFium 与 OCR 先建立与关键词无关的页面 token 索引；
 * - 同一文档后续查询复用内存/磁盘索引，不重复 OCR；
 * - 查询支持 Unicode、空白、跨行、上下文和全词规则；
 * - 结果提供稳定 ID、跨来源去重、双栏阅读顺序与完整性摘要。
 */
public class PdfSearchManager implements PdfSearchEngine, AutoCloseable {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PdfiumTextSearchEngine textSearchEngine;
    private final OcrSearchEngine ocrSearchEngine;
    private final PdfDocumentIndexer documentIndexer;
    private final ThreadPoolExecutor searchExecutor;

    private final AtomicLong tokenGenerator = new AtomicLong(0);
    private final Object pauseLock = new Object();
    private volatile boolean paused = false;
    private volatile boolean closed = false;

    public PdfSearchManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.textSearchEngine = new PdfiumTextSearchEngine();
        this.ocrSearchEngine = new OcrSearchEngine(appContext);
        this.documentIndexer = new PdfDocumentIndexer(appContext, ocrSearchEngine);
        this.searchExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new SearchThreadFactory()
        );
    }

    @Override
    public long search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    ) {
        if (closed) {
            postFailed(callback, new IllegalStateException("PdfSearchManager 已关闭"));
            return -1L;
        }

        PdfSearchOptions finalOptions = new PdfSearchOptions(options);
        long token = tokenGenerator.incrementAndGet();
        setPaused(false);
        cancelQueuedSearches();
        postStarted(callback, keyword);

        searchExecutor.execute(new SearchTask(
                token,
                pdfUri,
                keyword,
                finalOptions,
                callback
        ));
        return token;
    }

    /**
     * 只准备与关键词无关的页面索引。之后调用 search() 会直接复用该索引。
     */
    public long prepareIndex(
            Uri pdfUri,
            PdfSearchOptions options,
            PdfIndexCallback callback
    ) {
        if (closed) {
            postIndexFailed(callback, new IllegalStateException("PdfSearchManager 已关闭"));
            return -1L;
        }
        PdfSearchOptions finalOptions = new PdfSearchOptions(options);
        long token = tokenGenerator.incrementAndGet();
        setPaused(false);
        cancelQueuedSearches();
        searchExecutor.execute(new IndexTask(token, pdfUri, finalOptions, callback));
        return token;
    }

    public PdfSearchSession openSession(Uri pdfUri, PdfSearchOptions options) {
        if (closed) throw new IllegalStateException("PdfSearchManager 已关闭");
        return new PdfSearchSession(this, pdfUri, options, false);
    }

    private void executePrepareIndex(
            long token,
            Uri pdfUri,
            PdfSearchOptions options,
            PdfIndexCallback callback
    ) {
        try {
            PdfDocumentIndexBuildResult result = documentIndexer.build(
                    pdfUri,
                    options,
                    () -> isCancelled(token),
                    () -> awaitIfPaused(token),
                    new PdfDocumentIndexListener() {
                        @Override
                        public void onStarted(int targetPages) {
                            postIndexStarted(callback, targetPages);
                        }

                        @Override
                        public void onPageIndexed(
                                PdfPageIndex page,
                                int processedPages,
                                int targetPages,
                                PdfSearchCacheSource cacheSource
                        ) {
                            if (!isCancelled(token)) {
                                postIndexPageCompleted(
                                        callback,
                                        page,
                                        processedPages,
                                        targetPages,
                                        cacheSource
                                );
                            }
                        }

                        @Override
                        public void onPageFailed(PdfSearchPageError error) {
                            if (!isCancelled(token)) postIndexPageFailed(callback, error);
                        }
                    }
            );
            if (isCancelled(token) || result.summary.cancelled) {
                postIndexCancelled(callback);
            } else {
                postIndexCompleted(callback, result.index, result.summary);
            }
        } catch (Throwable error) {
            if (isCancelled(token)) postIndexCancelled(callback);
            else postIndexFailed(callback, PdfSearchError.classify(
                    error,
                    PdfSearchError.ERROR_INDEX_FAILED,
                    "文档索引构建失败"
            ));
        }
    }

    private void executeSearch(
            long token,
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    ) {
        if (options.enableDocumentIndex) {
            executeIndexedSearch(token, pdfUri, keyword, options, callback);
        } else {
            executeLegacySearch(token, pdfUri, keyword, options, callback);
        }
    }

    private void executeIndexedSearch(
            long token,
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
                throw new PdfSearchError(
                        PdfSearchError.ERROR_INVALID_ARGUMENT,
                        "pdfUri or keyword is empty"
                );
            }
            String fingerprint = PdfDocumentFingerprint.build(appContext, pdfUri);
            PdfSearchQueryOptions queryOptions = effectiveQueryOptions(options);

            final int[] cumulativeMatches = new int[]{0};
            PdfDocumentIndexBuildResult buildResult = documentIndexer.build(
                    pdfUri,
                    options,
                    () -> isCancelled(token),
                    () -> awaitIfPaused(token),
                    new PdfDocumentIndexListener() {
                        @Override
                        public void onPageIndexed(
                                PdfPageIndex page,
                                int processedPages,
                                int targetPages,
                                PdfSearchCacheSource cacheSource
                        ) {
                            if (isCancelled(token) || page == null) return;
                            postProgress(
                                    callback,
                                    page.pageIndex,
                                    -1,
                                    page.source
                            );
                            List<PdfSearchResult> pageResults = PdfPageIndexSearcher.searchPage(
                                    fingerprint,
                                    page,
                                    keyword,
                                    queryOptions
                            );
                            pageResults = finalizeResults(pageResults, options, false);
                            cumulativeMatches[0] += pageResults.size();
                            postDetailedProgress(callback, new PdfSearchProgressInfo(
                                    page.pageIndex,
                                    -1,
                                    processedPages,
                                    targetPages,
                                    cumulativeMatches[0],
                                    page.source,
                                    cacheSource,
                                    System.currentTimeMillis() - startedAt
                            ));
                            if (options.emitIncrementalResults) {
                                postPageCompleted(
                                        callback,
                                        page.pageIndex,
                                        pageResults,
                                        cacheSource
                                );
                            }
                        }

                        @Override
                        public void onPageFailed(PdfSearchPageError error) {
                            if (!isCancelled(token)) postPageFailed(callback, error);
                        }
                    }
            );

            if (isCancelled(token) || buildResult.summary.cancelled) {
                postCancelled(callback);
                return;
            }

            List<PdfSearchResult> results = PdfPageIndexSearcher.searchDocument(
                    buildResult.index,
                    keyword,
                    queryOptions
            );
            boolean limitedByResultCount = options.maxResults > 0
                    && results.size() > options.maxResults;
            results = finalizeResults(results, options, true);
            PdfSearchSummary summary = buildResult.summary.withResultCount(
                    results.size(),
                    System.currentTimeMillis() - startedAt,
                    limitedByResultCount
            );
            postCompleted(callback, results, summary);
        } catch (Throwable error) {
            if (isCancelled(token)) postCancelled(callback);
            else postFailed(callback, PdfSearchError.classify(
                    error,
                    PdfSearchError.ERROR_INDEX_FAILED,
                    "索引搜索失败"
            ));
        }
    }


    private PdfSearchQueryOptions effectiveQueryOptions(PdfSearchOptions options) {
        PdfSearchQueryOptions query = options != null && options.queryOptions != null
                ? new PdfSearchQueryOptions(options.queryOptions)
                : new PdfSearchQueryOptions();
        // 兼容旧版 ignoreCase。显式的 caseSensitive=true 始终生效；
        // 旧调用方设置 ignoreCase=false 时，也会得到区分大小写的行为。
        if (options != null && !options.ignoreCase) query.caseSensitive = true;
        return query;
    }

    private List<PdfSearchResult> finalizeResults(
            List<PdfSearchResult> input,
            PdfSearchOptions options,
            boolean applyLimit
    ) {
        List<PdfSearchResult> results = deduplicateByResultId(input);
        if (options.enableCrossSourceDeduplication) {
            results = PdfSearchResultDeduplicator.deduplicate(results, options);
        }
        PdfSearchReadingOrder.sort(results, options);
        if (applyLimit && options.maxResults > 0 && results.size() > options.maxResults) {
            results = new ArrayList<>(results.subList(0, options.maxResults));
        }
        return results;
    }

    private void executeLegacySearch(
            long token,
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    ) {
        try {
            if (pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
                throw new PdfSearchError(
                        PdfSearchError.ERROR_INVALID_ARGUMENT,
                        "pdfUri or keyword is empty"
                );
            }

            String documentFingerprint = PdfDocumentFingerprint.build(appContext, pdfUri);
            List<PdfSearchResult> finalResults = new ArrayList<>();
            PdfiumTextSearchReport textReport = null;

            if (options.mode == PdfSearchMode.TEXT_ONLY
                    || options.mode == PdfSearchMode.TEXT_THEN_OCR) {
                if (isCancelled(token)) {
                    postCancelled(callback);
                    return;
                }

                textReport = searchTextLayer(
                        pdfUri,
                        keyword,
                        options,
                        callback,
                        token
                );
                finalResults.addAll(convertTextResults(
                        textReport,
                        keyword,
                        documentFingerprint
                ));
                postTextLayerPageFailures(textReport, callback);

                if (isCancelled(token)) {
                    postCancelled(callback);
                    return;
                }
            }

            PdfSearchOptions ocrOptions = buildOcrRequest(
                    options,
                    textReport,
                    finalResults
            );
            if (ocrOptions != null) {
                if (isCancelled(token)) {
                    postCancelled(callback);
                    return;
                }
                finalResults.addAll(searchOcr(
                        pdfUri,
                        keyword,
                        ocrOptions,
                        callback,
                        token
                ));
            }

            if (isCancelled(token)) {
                postCancelled(callback);
                return;
            }

            boolean legacyLimitedByResultCount = options.maxResults > 0
                    && finalResults.size() > options.maxResults;
            finalResults = finalizeResults(finalResults, options, true);
            postCompleted(callback, finalResults, new PdfSearchSummary(
                    textReport != null ? textReport.documentPageCount : 0,
                    0,
                    0,
                    0,
                    0,
                    finalResults.size(),
                    0,
                    0,
                    !legacyLimitedByResultCount,
                    false,
                    legacyLimitedByResultCount,
                    0L,
                    null
            ));
        } catch (Throwable error) {
            if (isCancelled(token)) postCancelled(callback);
            else postFailed(callback, PdfSearchError.classify(
                    error,
                    PdfSearchError.ERROR_UNKNOWN,
                    "PDF 搜索失败"
            ));
        }
    }

    private PdfSearchOptions buildOcrRequest(
            PdfSearchOptions options,
            PdfiumTextSearchReport textReport,
            List<PdfSearchResult> textResults
    ) {
        if (options.mode == PdfSearchMode.OCR_ONLY) {
            return new PdfSearchOptions(options);
        }
        if (options.mode != PdfSearchMode.TEXT_THEN_OCR
                || !options.fallbackToOcrWhenTextNotFound) {
            return null;
        }

        PdfSearchOptions ocrRequest = new PdfSearchOptions(options);
        if (!options.enablePageLevelTextOcrFallback
                || textReport == null
                || textReport.pageInfos.isEmpty()) {
            return textResults == null || textResults.isEmpty() ? ocrRequest : null;
        }

        List<Integer> fallbackPages = new ArrayList<>();
        for (PdfiumTextPageInfo pageInfo : textReport.pageInfos) {
            if (pageInfo == null) continue;
            boolean usable = pageInfo.hasUsableTextLayer(
                    options.textLayerMinVisibleCharacters,
                    options.textLayerMinBoxCoverage
            );
            boolean noMatchFallback = options.textLayerOcrFallbackPolicy
                    == PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH
                    && pageInfo.matchCount == 0;
            if (!usable || noMatchFallback) fallbackPages.add(pageInfo.pageIndex);
        }

        if (fallbackPages.isEmpty()) return null;
        ocrRequest.ocrTargetPages = fallbackPages;
        return ocrRequest;
    }

    private PdfiumTextSearchReport searchTextLayer(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback,
            long token
    ) {
        int startPage;
        int endPage;
        if (options.currentPageOnly) {
            startPage = Math.max(0, options.currentPage);
            endPage = startPage;
        } else {
            startPage = Math.max(0, options.startPage);
            endPage = options.endPage;
        }

        PdfiumTextSearchReport report = textSearchEngine.searchDetailed(
                appContext,
                pdfUri,
                keyword,
                startPage,
                endPage,
                options.ignoreCase
        );
        if (report != null) {
            for (PdfiumTextPageInfo pageInfo : report.pageInfos) {
                if (isCancelled(token)) break;
                postProgress(
                        callback,
                        pageInfo.pageIndex,
                        report.documentPageCount,
                        PdfSearchSource.PDF_TEXT_LAYER
                );
            }
        }
        return report != null ? report : PdfiumTextSearchReport.empty();
    }

    private List<PdfSearchResult> convertTextResults(
            PdfiumTextSearchReport report,
            String keyword,
            String documentFingerprint
    ) {
        List<PdfSearchResult> results = new ArrayList<>();
        if (report == null || report.results == null) return results;

        for (PdfiumTextSearchResult item : report.results) {
            if (item == null || item.rectsInPdfPoint == null
                    || item.rectsInPdfPoint.isEmpty()) {
                continue;
            }

            List<PdfSearchRect> rects = new ArrayList<>();
            for (android.graphics.RectF rect : item.rectsInPdfPoint) {
                if (rect == null) continue;
                rects.add(new PdfSearchRect(
                        item.pageIndex,
                        rect,
                        item.pageWidth,
                        item.pageHeight
                ));
            }
            if (rects.isEmpty()) continue;

            String resultId = PdfSearchResultId.create(
                    documentFingerprint,
                    item.pageIndex,
                    PdfSearchSource.PDF_TEXT_LAYER,
                    item.matchOrdinal,
                    keyword,
                    rects
            );
            results.add(new PdfSearchResult(
                    resultId,
                    keyword,
                    item.pageIndex,
                    PdfSearchSource.PDF_TEXT_LAYER,
                    rects,
                    keyword,
                    item.matchStart,
                    item.matchLength
            ));
        }
        return results;
    }

    private void postTextLayerPageFailures(
            PdfiumTextSearchReport report,
            PdfSearchCallback callback
    ) {
        if (report == null) return;
        for (PdfiumTextPageInfo info : report.pageInfos) {
            if (info == null || info.status == PdfiumTextPageInfo.STATUS_OK) continue;
            postPageFailed(callback, new PdfSearchPageError(
                    info.pageIndex,
                    PdfSearchPageError.STAGE_TEXT_LAYER,
                    "PDFium 文本层读取失败，已允许 OCR fallback，status=" + info.status,
                    null,
                    true
            ));
        }
    }

    private List<PdfSearchResult> deduplicateByResultId(List<PdfSearchResult> input) {
        Map<String, PdfSearchResult> unique = new LinkedHashMap<>();
        int anonymous = 0;
        if (input != null) {
            for (PdfSearchResult result : input) {
                if (result == null) continue;
                String key = result.resultId;
                if (key == null || key.isEmpty()) key = "anonymous_" + anonymous++;
                unique.putIfAbsent(key, result);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<PdfSearchResult> searchOcr(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback,
            long token
    ) {
        OcrDebugGeometryListener debugGeometryListener = null;
        if (options.ocrDebugOverlayEnabled && options.ocrDebugGeometryListener != null) {
            debugGeometryListener = (pageIndex, rects) -> {
                if (!isCancelled(token)) {
                    postOcrDebugGeometry(options.ocrDebugGeometryListener, pageIndex, rects);
                }
            };
        }

        return ocrSearchEngine.search(
                pdfUri,
                keyword,
                options,
                (currentPage, totalPage, source) ->
                        postProgress(callback, currentPage, totalPage, source),
                () -> isCancelled(token),
                () -> awaitIfPaused(token),
                debugGeometryListener,
                new OcrPageSearchListener() {
                    @Override
                    public void onPageCompleted(
                            int pageIndex,
                            int documentPageCount,
                            int processedPages,
                            int targetPages,
                            List<PdfSearchResult> pageResults,
                            PdfSearchCacheSource cacheSource,
                            long elapsedMillis,
                            int cumulativeMatchCount
                    ) {
                        if (isCancelled(token)) return;
                        postDetailedProgress(callback, new PdfSearchProgressInfo(
                                pageIndex,
                                documentPageCount,
                                processedPages,
                                targetPages,
                                cumulativeMatchCount,
                                PdfSearchSource.OCR,
                                cacheSource,
                                elapsedMillis
                        ));
                        if (options.emitIncrementalResults) {
                            postPageCompleted(callback, pageIndex, pageResults, cacheSource);
                        }
                    }

                    @Override
                    public void onPageMetrics(PdfSearchPageMetrics metrics) {
                        if (!isCancelled(token)) postPageMetrics(callback, metrics);
                    }

                    @Override
                    public void onPageFailed(PdfSearchPageError error) {
                        if (!isCancelled(token)) postPageFailed(callback, error);
                    }
                }
        );
    }

    @Override
    public void cancel() {
        tokenGenerator.incrementAndGet();
        cancelQueuedSearches();
        setPaused(false);
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        setPaused(false);
    }

    @Override
    public void clearCache() {
        clearOcrCache();
        clearPageIndexCache();
    }

    public void clearOcrCache() {
        ocrSearchEngine.clearCache();
    }

    public void clearPageIndexCache() {
        documentIndexer.clearCache();
    }

    public long getPersistentOcrCacheSizeBytes() {
        return ocrSearchEngine.getPersistentCacheSizeBytes();
    }

    public long getPersistentPageIndexCacheSizeBytes() {
        return documentIndexer.getPersistentCacheSizeBytes();
    }

    /** 在后台线程提前初始化 OCR 模型。 */
    public void warmUpOcr() {
        if (!closed) searchExecutor.execute(() -> ocrSearchEngine.warmUp());
    }

    /** 显式释放 OCR native 资源。 */
    public void releaseOcrResources() {
        ocrSearchEngine.releaseResources();
    }

    /** Android 低内存回调可直接转发到这里。 */
    public void trimMemory(int level) {
        ocrSearchEngine.trimMemory(level);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancel();
        searchExecutor.shutdownNow();
        ocrSearchEngine.releaseResources();
    }

    private boolean isCancelled(long token) {
        return closed || token != tokenGenerator.get();
    }

    private boolean awaitIfPaused(long token) {
        synchronized (pauseLock) {
            while (paused && !isCancelled(token)) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !isCancelled(token);
    }

    private void setPaused(boolean value) {
        synchronized (pauseLock) {
            paused = value;
            if (!value) pauseLock.notifyAll();
        }
    }

    private void postOcrDebugGeometry(
            OcrDebugGeometryListener listener,
            int pageIndex,
            List<OcrDebugRect> rects
    ) {
        if (listener == null) return;
        List<OcrDebugRect> snapshot = rects == null
                ? new ArrayList<>()
                : new ArrayList<>(rects);
        mainHandler.post(() -> listener.onOcrDebugGeometry(pageIndex, snapshot));
    }

    private void postStarted(PdfSearchCallback callback, String keyword) {
        if (callback != null) mainHandler.post(() -> callback.onSearchStarted(keyword));
    }

    private void postProgress(
            PdfSearchCallback callback,
            int currentPage,
            int totalPage,
            PdfSearchSource source
    ) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSearchProgress(currentPage, totalPage, source));
        }
    }

    private void postDetailedProgress(
            PdfSearchCallback callback,
            PdfSearchProgressInfo progressInfo
    ) {
        if (callback != null && progressInfo != null) {
            mainHandler.post(() -> callback.onSearchProgress(progressInfo));
        }
    }

    private void postPageCompleted(
            PdfSearchCallback callback,
            int pageIndex,
            List<PdfSearchResult> pageResults,
            PdfSearchCacheSource cacheSource
    ) {
        if (callback == null) return;
        List<PdfSearchResult> snapshot = pageResults == null
                ? new ArrayList<>()
                : new ArrayList<>(pageResults);
        mainHandler.post(() -> callback.onSearchPageCompleted(
                pageIndex,
                snapshot,
                cacheSource
        ));
    }

    private void postPageMetrics(PdfSearchCallback callback, PdfSearchPageMetrics metrics) {
        if (callback != null && metrics != null) {
            mainHandler.post(() -> callback.onSearchPageMetrics(metrics));
        }
    }

    private void postPageFailed(PdfSearchCallback callback, PdfSearchPageError error) {
        if (callback != null && error != null) {
            mainHandler.post(() -> callback.onSearchPageFailed(error));
        }
    }

    private void postCompleted(PdfSearchCallback callback, List<PdfSearchResult> results) {
        postCompleted(callback, results, PdfSearchSummary.empty());
    }

    private void postCompleted(
            PdfSearchCallback callback,
            List<PdfSearchResult> results,
            PdfSearchSummary summary
    ) {
        if (callback == null) return;
        List<PdfSearchResult> snapshot = results == null
                ? new ArrayList<>()
                : new ArrayList<>(results);
        PdfSearchSummary safeSummary = summary != null ? summary : PdfSearchSummary.empty();
        mainHandler.post(() -> callback.onSearchCompleted(snapshot, safeSummary));
    }

    private void postIndexStarted(PdfIndexCallback callback, int targetPages) {
        if (callback != null) mainHandler.post(() -> callback.onIndexStarted(targetPages));
    }

    private void postIndexPageCompleted(
            PdfIndexCallback callback,
            PdfPageIndex page,
            int processedPages,
            int targetPages,
            PdfSearchCacheSource cacheSource
    ) {
        if (callback != null) {
            mainHandler.post(() -> callback.onIndexPageCompleted(
                    page.pageIndex,
                    page,
                    processedPages,
                    targetPages,
                    cacheSource
            ));
        }
    }

    private void postIndexPageFailed(PdfIndexCallback callback, PdfSearchPageError error) {
        if (callback != null && error != null) {
            mainHandler.post(() -> callback.onIndexPageFailed(error));
        }
    }

    private void postIndexCompleted(
            PdfIndexCallback callback,
            PdfDocumentIndex index,
            PdfSearchSummary summary
    ) {
        if (callback != null) mainHandler.post(() -> callback.onIndexCompleted(index, summary));
    }

    private void postIndexFailed(PdfIndexCallback callback, Throwable error) {
        if (callback != null) mainHandler.post(() -> callback.onIndexFailed(error));
    }

    private void postIndexCancelled(PdfIndexCallback callback) {
        if (callback != null) mainHandler.post(callback::onIndexCancelled);
    }

    private void postFailed(PdfSearchCallback callback, Throwable error) {
        if (callback != null) mainHandler.post(() -> callback.onSearchFailed(error));
    }

    private void postCancelled(PdfSearchCallback callback) {
        if (callback != null) mainHandler.post(callback::onSearchCancelled);
    }

    private void cancelQueuedSearches() {
        List<Runnable> removed = new ArrayList<>();
        searchExecutor.getQueue().drainTo(removed);
        for (Runnable runnable : removed) {
            if (runnable instanceof QueuedTask) {
                ((QueuedTask) runnable).cancelBeforeStart();
            }
        }
    }

    private interface QueuedTask extends Runnable {
        void cancelBeforeStart();
    }

    private final class SearchTask implements QueuedTask {
        private final long token;
        private final Uri pdfUri;
        private final String keyword;
        private final PdfSearchOptions options;
        private final PdfSearchCallback callback;

        SearchTask(
                long token,
                Uri pdfUri,
                String keyword,
                PdfSearchOptions options,
                PdfSearchCallback callback
        ) {
            this.token = token;
            this.pdfUri = pdfUri;
            this.keyword = keyword;
            this.options = options;
            this.callback = callback;
        }

        @Override
        public void run() {
            executeSearch(token, pdfUri, keyword, options, callback);
        }

        @Override
        public void cancelBeforeStart() {
            postCancelled(callback);
        }
    }

    private final class IndexTask implements QueuedTask {
        private final long token;
        private final Uri pdfUri;
        private final PdfSearchOptions options;
        private final PdfIndexCallback callback;

        IndexTask(
                long token,
                Uri pdfUri,
                PdfSearchOptions options,
                PdfIndexCallback callback
        ) {
            this.token = token;
            this.pdfUri = pdfUri;
            this.options = options;
            this.callback = callback;
        }

        @Override
        public void run() {
            executePrepareIndex(token, pdfUri, options, callback);
        }

        @Override
        public void cancelBeforeStart() {
            postIndexCancelled(callback);
        }
    }

    private static final class SearchThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "pdf-search-worker");
            thread.setDaemon(true);
            return thread;
        }
    }
}
