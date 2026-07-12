package com.nless.pdf_search_engine.index;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.nless.pdf_search_engine.cache.PdfDocumentFingerprint;
import com.nless.pdf_search_engine.cache.PdfPageIndexCacheKey;
import com.nless.pdf_search_engine.cache.PdfPageIndexDiskCache;
import com.nless.pdf_search_engine.cache.PdfPageIndexMemoryCache;
import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchCancelChecker;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageError;
import com.nless.pdf_search_engine.core.PdfSearchPauseChecker;
import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.core.PdfSearchSummary;
import com.nless.pdf_search_engine.core.PdfTextLayerOcrFallbackPolicy;
import com.nless.pdf_search_engine.ocr.OcrPagePlanner;
import com.nless.pdf_search_engine.ocr.OcrPageRenderer;
import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrPageSearchListener;
import com.nless.pdf_search_engine.ocr.OcrSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextIndexExtractor;
import com.nless.pdf_search_engine.pdfium.PdfiumTextIndexPage;
import com.nless.pdf_search_engine.pdfium.PdfiumTextIndexReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档内容索引器。
 *
 * 文本层提取和 OCR 都只生成与关键词无关的 token 索引；查询阶段完全在 Java 中完成。
 */
public final class PdfDocumentIndexer {

    private static final String TAG = "PdfDocumentIndexer";
    private static final long DEFAULT_INDEX_CACHE_BYTES = 256L * 1024L * 1024L;

    private final Context appContext;
    private final PdfiumTextIndexExtractor textExtractor;
    private final OcrSearchEngine ocrSearchEngine;
    private final OcrPageRenderer pageRenderer;
    private final PdfPageIndexMemoryCache memoryCache;
    private final PdfPageIndexDiskCache diskCache;

    public PdfDocumentIndexer(Context context, OcrSearchEngine ocrSearchEngine) {
        this.appContext = context.getApplicationContext();
        this.textExtractor = new PdfiumTextIndexExtractor();
        this.ocrSearchEngine = ocrSearchEngine;
        this.pageRenderer = new OcrPageRenderer();
        this.memoryCache = new PdfPageIndexMemoryCache(128);
        this.diskCache = new PdfPageIndexDiskCache(appContext, DEFAULT_INDEX_CACHE_BYTES);
    }

    public PdfDocumentIndexBuildResult build(
            Uri pdfUri,
            PdfSearchOptions options,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker,
            PdfDocumentIndexListener listener
    ) {
        long startedAt = System.currentTimeMillis();
        PdfSearchOptions request = options != null
                ? new PdfSearchOptions(options)
                : new PdfSearchOptions();
        diskCache.setMaxBytes(request.pageIndexDiskCacheMaxBytes > 0
                ? request.pageIndexDiskCacheMaxBytes
                : DEFAULT_INDEX_CACHE_BYTES);

        if (pdfUri == null) throw new IllegalArgumentException("pdfUri is null");
        String fingerprint = PdfDocumentFingerprint.build(appContext, pdfUri);
        int pageCount = pageRenderer.getPageCount(appContext, pdfUri);
        if (pageCount <= 0) throw new IllegalStateException("无法读取 PDF 页数");

        List<Integer> targetPages = buildTargetPages(request, pageCount);
        if (listener != null) listener.onStarted(targetPages.size());

        BuildState state = new BuildState(targetPages.size());
        Map<String, PdfPageIndex> indexesByKey = new LinkedHashMap<>();
        Set<Integer> unresolved = new LinkedHashSet<>(targetPages);
        Set<Integer> ocrCandidates = new LinkedHashSet<>();

        if (request.mode != PdfSearchMode.OCR_ONLY) {
            loadCachedTextIndexes(
                    fingerprint,
                    targetPages,
                    request,
                    indexesByKey,
                    unresolved,
                    ocrCandidates,
                    state,
                    listener
            );
            if (isCancelled(cancelChecker)) {
                return cancelledResult(fingerprint, pageCount, indexesByKey, state, startedAt);
            }
            awaitPause(pauseChecker);

            List<Integer> missingTextPages = new ArrayList<>();
            for (int page : targetPages) {
                if (unresolved.contains(page) && !ocrCandidates.contains(page)) {
                    missingTextPages.add(page);
                }
            }
            if (!missingTextPages.isEmpty()) {
                extractTextIndexes(
                        pdfUri,
                        fingerprint,
                        missingTextPages,
                        request,
                        indexesByKey,
                        unresolved,
                        ocrCandidates,
                        state,
                        listener,
                        cancelChecker,
                        pauseChecker
                );
            }
        } else {
            ocrCandidates.addAll(targetPages);
        }

        if (request.mode == PdfSearchMode.TEXT_ONLY) {
            for (int page : new ArrayList<>(unresolved)) {
                failPage(
                        page,
                        PdfSearchPageError.STAGE_TEXT_LAYER,
                        "页面文本层不可用",
                        null,
                        true,
                        state,
                        listener
                );
                unresolved.remove(page);
            }
        } else {
            loadAndExtractOcrIndexes(
                    pdfUri,
                    fingerprint,
                    pageCount,
                    targetPages,
                    ocrCandidates,
                    request,
                    indexesByKey,
                    unresolved,
                    state,
                    listener,
                    cancelChecker,
                    pauseChecker
            );
        }

        if (!isCancelled(cancelChecker)) {
            for (int page : new ArrayList<>(unresolved)) {
                failPage(
                        page,
                        PdfSearchPageError.STAGE_OCR,
                        "页面没有生成可用索引",
                        null,
                        true,
                        state,
                        listener
                );
                unresolved.remove(page);
            }
        }

        PdfDocumentIndex index = new PdfDocumentIndex(
                fingerprint,
                pageCount,
                indexesByKey.values()
        );
        boolean cancelled = isCancelled(cancelChecker);
        int skipped = Math.max(0, targetPages.size() - state.completedPages.size()
                - state.failedPages.size());
        PdfSearchSummary summary = new PdfSearchSummary(
                pageCount,
                targetPages.size(),
                state.completedPages.size(),
                state.failedPages.size(),
                skipped,
                0,
                state.memoryHits,
                state.diskHits,
                !cancelled
                        && state.failedPages.isEmpty()
                        && skipped == 0
                        && !state.limitedByOptions,
                cancelled,
                state.limitedByOptions,
                System.currentTimeMillis() - startedAt,
                state.errors
        );
        return new PdfDocumentIndexBuildResult(index, summary);
    }

    private void loadCachedTextIndexes(
            String fingerprint,
            List<Integer> targetPages,
            PdfSearchOptions request,
            Map<String, PdfPageIndex> indexesByKey,
            Set<Integer> unresolved,
            Set<Integer> ocrCandidates,
            BuildState state,
            PdfDocumentIndexListener listener
    ) {
        for (int page : targetPages) {
            String key = PdfPageIndexCacheKey.build(
                    fingerprint,
                    page,
                    PdfSearchSource.PDF_TEXT_LAYER,
                    request.ocrRenderWidth,
                    request.ocrCacheNamespace
            );
            CacheValue cached = getCached(key, request.usePersistentPageIndexCache);
            if (cached.page == null) continue;
            handleTextIndex(
                    cached.page,
                    key,
                    request,
                    indexesByKey,
                    unresolved,
                    ocrCandidates,
                    state,
                    listener,
                    cached.source
            );
        }
    }

    private void extractTextIndexes(
            Uri pdfUri,
            String fingerprint,
            List<Integer> missingPages,
            PdfSearchOptions request,
            Map<String, PdfPageIndex> indexesByKey,
            Set<Integer> unresolved,
            Set<Integer> ocrCandidates,
            BuildState state,
            PdfDocumentIndexListener listener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker
    ) {
        int start = Collections.min(missingPages);
        int end = Collections.max(missingPages);
        PdfiumTextIndexReport report;
        try {
            report = textExtractor.extract(appContext, pdfUri, start, end);
        } catch (Throwable error) {
            Log.e(
                    TAG,
                    "PDFium text extraction failed; falling back according to search mode",
                    error
            );
            for (int pageIndex : missingPages) {
                if (request.mode == PdfSearchMode.TEXT_ONLY
                        || !request.enablePageLevelTextOcrFallback
                        || !request.fallbackToOcrWhenTextNotFound) {
                    failPage(
                            pageIndex,
                            PdfSearchPageError.STAGE_TEXT_LAYER,
                            "PDFium 文本层后端不可用",
                            error,
                            true,
                            state,
                            listener
                    );
                    unresolved.remove(pageIndex);
                } else {
                    // 不缓存失败的文本索引；后续版本或依赖修复后可以重新尝试。
                    ocrCandidates.add(pageIndex);
                }
            }
            return;
        }
        Map<Integer, PdfiumTextIndexPage> extracted = new HashMap<>();
        for (PdfiumTextIndexPage page : report.pages) extracted.put(page.pageIndex, page);

        for (int pageIndex : missingPages) {
            if (isCancelled(cancelChecker)) return;
            awaitPause(pauseChecker);
            PdfiumTextIndexPage raw = extracted.get(pageIndex);
            PdfPageIndex page = raw != null
                    ? PdfPageIndexFactory.fromPdfium(raw)
                    : new PdfPageIndex(
                            pageIndex,
                            0f,
                            0f,
                            PdfSearchSource.PDF_TEXT_LAYER,
                            "",
                            null,
                            PdfPageIndex.STATUS_EXTRACTION_FAILED,
                            0,
                            0,
                            0,
                            0f
                    );
            String key = PdfPageIndexCacheKey.build(
                    fingerprint,
                    pageIndex,
                    PdfSearchSource.PDF_TEXT_LAYER,
                    request.ocrRenderWidth,
                    request.ocrCacheNamespace
            );
            if (raw != null) {
                putCached(key, page, request.usePersistentPageIndexCache);
            }
            handleTextIndex(
                    page,
                    key,
                    request,
                    indexesByKey,
                    unresolved,
                    ocrCandidates,
                    state,
                    listener,
                    PdfSearchCacheSource.NONE
            );
        }
    }

    private void handleTextIndex(
            PdfPageIndex page,
            String key,
            PdfSearchOptions request,
            Map<String, PdfPageIndex> indexesByKey,
            Set<Integer> unresolved,
            Set<Integer> ocrCandidates,
            BuildState state,
            PdfDocumentIndexListener listener,
            PdfSearchCacheSource cacheSource
    ) {
        if (page == null) return;
        boolean usable = page.hasUsableTextLayer(
                request.textLayerMinVisibleCharacters,
                request.textLayerMinBoxCoverage
        );
        boolean searchableFallback = page.isSearchable();

        if (request.mode == PdfSearchMode.TEXT_ONLY) {
            if (searchableFallback) {
                indexesByKey.put(key, page);
                unresolved.remove(page.pageIndex);
                completePage(page, state, listener, cacheSource);
            }
            return;
        }

        if (usable) {
            indexesByKey.put(key, page);
            if (request.textLayerOcrFallbackPolicy
                    == PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH) {
                // 索引阶段不知道未来关键词是否命中，因此保留文本层并同时建立 OCR 索引。
                ocrCandidates.add(page.pageIndex);
            } else {
                unresolved.remove(page.pageIndex);
                completePage(page, state, listener, cacheSource);
            }
        } else if (!request.fallbackToOcrWhenTextNotFound
                || !request.enablePageLevelTextOcrFallback) {
            if (searchableFallback) {
                indexesByKey.put(key, page);
                unresolved.remove(page.pageIndex);
                completePage(page, state, listener, cacheSource);
            }
        } else {
            ocrCandidates.add(page.pageIndex);
        }
    }

    private void loadAndExtractOcrIndexes(
            Uri pdfUri,
            String fingerprint,
            int documentPageCount,
            List<Integer> targetPages,
            Set<Integer> ocrCandidates,
            PdfSearchOptions request,
            Map<String, PdfPageIndex> indexesByKey,
            Set<Integer> unresolved,
            BuildState state,
            PdfDocumentIndexListener listener,
            PdfSearchCancelChecker cancelChecker,
            PdfSearchPauseChecker pauseChecker
    ) {
        List<Integer> missingOcr = new ArrayList<>();
        for (int page : targetPages) {
            if (!ocrCandidates.contains(page)) continue;
            String key = PdfPageIndexCacheKey.build(
                    fingerprint,
                    page,
                    PdfSearchSource.OCR,
                    request.ocrRenderWidth,
                    request.ocrCacheNamespace
            );
            CacheValue cached = getCached(key, request.usePersistentPageIndexCache);
            if (cached.page != null && cached.page.isSearchable()) {
                indexesByKey.put(key, cached.page);
                unresolved.remove(page);
                completePage(cached.page, state, listener, cached.source);
            } else {
                missingOcr.add(page);
            }
        }
        if (missingOcr.isEmpty() || isCancelled(cancelChecker)) return;

        PdfSearchOptions ocrOptions = new PdfSearchOptions(request);
        ocrOptions.mode = PdfSearchMode.OCR_ONLY;
        ocrOptions.currentPageOnly = false;
        ocrOptions.allowFullDocumentOcr = request.allowFullDocumentOcr;
        ocrOptions.ocrTargetPages = new ArrayList<>(missingOcr);

        // 先用现有调度器算出真正会处理的页面，以便标记 maxOcrPages 限制。
        List<Integer> planned = OcrPagePlanner.build(
                ocrOptions,
                Math.max(1, documentPageCount)
        );
        Set<Integer> plannedSet = new HashSet<>(planned);
        if (planned.size() < missingOcr.size()) state.limitedByOptions = true;
        for (int page : missingOcr) {
            if (!plannedSet.contains(page)) {
                unresolved.remove(page);
                PdfPageIndex textFallback = findPageIndex(
                        indexesByKey,
                        page,
                        PdfSearchSource.PDF_TEXT_LAYER
                );
                if (textFallback != null && textFallback.isSearchable()) {
                    completePage(
                            textFallback,
                            state,
                            listener,
                            PdfSearchCacheSource.NONE
                    );
                }
            }
        }
        ocrOptions.ocrTargetPages = planned;
        ocrOptions.maxOcrPages = 0;

        Set<Integer> extractedPages = new HashSet<>();
        ocrSearchEngine.extractPages(
                pdfUri,
                ocrOptions,
                cancelChecker,
                pauseChecker,
                new OcrPageSearchListener() {
                    @Override
                    public void onPageCompleted(
                            int pageIndex,
                            int documentPageCount,
                            int processedPages,
                            int targetPages,
                            List<com.nless.pdf_search_engine.core.PdfSearchResult> pageResults,
                            PdfSearchCacheSource cacheSource,
                            long elapsedMillis,
                            int cumulativeMatchCount
                    ) {
                    }

                    @Override
                    public void onPageExtracted(
                            int pageIndex,
                            OcrPageResult pageResult,
                            PdfSearchCacheSource cacheSource
                    ) {
                        PdfPageIndex page = PdfPageIndexFactory.fromOcr(
                                pageIndex,
                                pageResult,
                                request.detectMultiColumnLayout,
                                request.multiColumnMinGapRatio
                        );
                        if (page == null || !page.isSearchable()) return;
                        String key = PdfPageIndexCacheKey.build(
                                fingerprint,
                                pageIndex,
                                PdfSearchSource.OCR,
                                request.ocrRenderWidth,
                                request.ocrCacheNamespace
                        );
                        putCached(key, page, request.usePersistentPageIndexCache);
                        indexesByKey.put(key, page);
                        unresolved.remove(pageIndex);
                        extractedPages.add(pageIndex);
                        completePage(page, state, listener, cacheSource);
                    }

                    @Override
                    public void onPageFailed(PdfSearchPageError error) {
                        if (error == null) return;
                        unresolved.remove(error.pageIndex);
                        PdfPageIndex textFallback = findPageIndex(
                                indexesByKey,
                                error.pageIndex,
                                PdfSearchSource.PDF_TEXT_LAYER
                        );
                        if (textFallback != null && textFallback.isSearchable()) {
                            completePage(
                                    textFallback,
                                    state,
                                    listener,
                                    PdfSearchCacheSource.NONE
                            );
                        } else {
                            failPage(
                                    error.pageIndex,
                                    error.stage,
                                    error.message,
                                    error.cause,
                                    error.retryable,
                                    state,
                                    listener
                            );
                        }
                    }
                }
        );

        for (int page : planned) {
            if (!extractedPages.contains(page)
                    && !state.failedPages.contains(page)
                    && !isCancelled(cancelChecker)) {
                unresolved.remove(page);
                PdfPageIndex textFallback = findPageIndex(
                        indexesByKey,
                        page,
                        PdfSearchSource.PDF_TEXT_LAYER
                );
                if (textFallback != null && textFallback.isSearchable()) {
                    completePage(
                            textFallback,
                            state,
                            listener,
                            PdfSearchCacheSource.NONE
                    );
                } else {
                    failPage(
                            page,
                            PdfSearchPageError.STAGE_OCR,
                            "OCR 未返回页面索引",
                            null,
                            true,
                            state,
                            listener
                    );
                }
            }
        }
    }

    private PdfPageIndex findPageIndex(
            Map<String, PdfPageIndex> values,
            int pageIndex,
            PdfSearchSource source
    ) {
        for (PdfPageIndex page : values.values()) {
            if (page != null && page.pageIndex == pageIndex && page.source == source) {
                return page;
            }
        }
        return null;
    }

    private CacheValue getCached(String key, boolean persistent) {
        PdfPageIndex memory = memoryCache.get(key);
        if (memory != null) return new CacheValue(memory, PdfSearchCacheSource.INDEX_MEMORY);
        if (persistent) {
            PdfPageIndex disk = diskCache.get(key);
            if (disk != null) {
                memoryCache.put(key, disk);
                return new CacheValue(disk, PdfSearchCacheSource.INDEX_DISK);
            }
        }
        return new CacheValue(null, PdfSearchCacheSource.NONE);
    }

    private void putCached(String key, PdfPageIndex page, boolean persistent) {
        memoryCache.put(key, page);
        if (persistent) diskCache.put(key, page);
    }

    private void completePage(
            PdfPageIndex page,
            BuildState state,
            PdfDocumentIndexListener listener,
            PdfSearchCacheSource source
    ) {
        if (page == null) return;
        if (source == PdfSearchCacheSource.INDEX_MEMORY) state.memoryHits++;
        if (source == PdfSearchCacheSource.INDEX_DISK) state.diskHits++;
        boolean firstCompletion = state.completedPages.add(page.pageIndex);
        if (firstCompletion && listener != null) {
            listener.onPageIndexed(
                    page,
                    state.completedPages.size() + state.failedPages.size(),
                    state.targetPages,
                    source
            );
        }
    }

    private void failPage(
            int pageIndex,
            int stage,
            String message,
            Throwable cause,
            boolean retryable,
            BuildState state,
            PdfDocumentIndexListener listener
    ) {
        if (state.completedPages.contains(pageIndex) || state.failedPages.contains(pageIndex)) {
            return;
        }
        PdfSearchPageError error = new PdfSearchPageError(
                pageIndex,
                stage,
                message,
                cause,
                retryable
        );
        state.failedPages.add(pageIndex);
        state.errors.add(error);
        if (listener != null) listener.onPageFailed(error);
    }

    private PdfDocumentIndexBuildResult cancelledResult(
            String fingerprint,
            int pageCount,
            Map<String, PdfPageIndex> indexes,
            BuildState state,
            long startedAt
    ) {
        PdfDocumentIndex index = new PdfDocumentIndex(fingerprint, pageCount, indexes.values());
        PdfSearchSummary summary = new PdfSearchSummary(
                pageCount,
                state.targetPages,
                state.completedPages.size(),
                state.failedPages.size(),
                Math.max(0, state.targetPages - state.completedPages.size() - state.failedPages.size()),
                0,
                state.memoryHits,
                state.diskHits,
                false,
                true,
                state.limitedByOptions,
                System.currentTimeMillis() - startedAt,
                state.errors
        );
        return new PdfDocumentIndexBuildResult(index, summary);
    }

    private List<Integer> buildTargetPages(PdfSearchOptions options, int pageCount) {
        if (pageCount <= 0) return Collections.emptyList();
        if (options.currentPageOnly) {
            return Collections.singletonList(clamp(options.currentPage, 0, pageCount - 1));
        }
        if (options.targetPages != null) {
            Set<Integer> unique = new java.util.TreeSet<>();
            for (Integer page : options.targetPages) {
                if (page != null && page >= 0 && page < pageCount) unique.add(page);
            }
            return new ArrayList<>(unique);
        }
        int start = clamp(Math.max(0, options.startPage), 0, pageCount - 1);
        int end = options.endPage < 0
                ? pageCount - 1
                : clamp(options.endPage, 0, pageCount - 1);
        if (end < start) end = start;
        List<Integer> pages = new ArrayList<>(end - start + 1);
        for (int page = start; page <= end; page++) pages.add(page);
        return pages;
    }

    private void awaitPause(PdfSearchPauseChecker checker) {
        if (checker != null && !checker.awaitIfPaused()) {
            throw new IndexCancelledException();
        }
    }

    private boolean isCancelled(PdfSearchCancelChecker checker) {
        return checker != null && checker.isCancelled();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void clearCache() {
        memoryCache.clear();
        diskCache.clear();
    }

    public long getPersistentCacheSizeBytes() {
        return diskCache.sizeBytes();
    }

    private static final class CacheValue {
        final PdfPageIndex page;
        final PdfSearchCacheSource source;

        CacheValue(PdfPageIndex page, PdfSearchCacheSource source) {
            this.page = page;
            this.source = source;
        }
    }

    private static final class BuildState {
        final int targetPages;
        final Set<Integer> completedPages = new HashSet<>();
        final Set<Integer> failedPages = new HashSet<>();
        final List<PdfSearchPageError> errors = new ArrayList<>();
        int memoryHits;
        int diskHits;
        boolean limitedByOptions;

        BuildState(int targetPages) {
            this.targetPages = Math.max(0, targetPages);
        }
    }

    private static final class IndexCancelledException extends RuntimeException {
    }
}
