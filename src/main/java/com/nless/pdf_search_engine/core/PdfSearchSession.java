package com.nless.pdf_search_engine.core;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;

/**
 * 绑定单个 PDF 的搜索会话。
 *
 * <p>prepareIndex() 与 search() 共用同一套内存/磁盘页面索引，适合阅读器连续搜索
 * 多个关键词。直接使用 public 构造函数创建的会话拥有内部 manager，close() 会释放它；
 * 由 PdfSearchManager.openSession() 创建的会话不拥有外部 manager。</p>
 */
public final class PdfSearchSession implements AutoCloseable {

    private final Uri pdfUri;
    private final PdfSearchOptions baseOptions;
    private final PdfSearchManager manager;
    private final boolean ownsManager;
    private volatile boolean closed;

    public PdfSearchSession(Context context, Uri pdfUri, PdfSearchOptions options) {
        this(new PdfSearchManager(requireContext(context)), pdfUri, options, true);
    }

    PdfSearchSession(
            PdfSearchManager manager,
            Uri pdfUri,
            PdfSearchOptions options,
            boolean ownsManager
    ) {
        if (manager == null || pdfUri == null) {
            throw new IllegalArgumentException("manager/pdfUri is null");
        }
        this.pdfUri = pdfUri;
        this.baseOptions = new PdfSearchOptions(options);
        this.manager = manager;
        this.ownsManager = ownsManager;
    }

    public long prepareIndex(PdfIndexCallback callback) {
        ensureOpen();
        return manager.prepareIndex(pdfUri, baseOptions, callback);
    }

    public long search(String keyword, PdfSearchCallback callback) {
        ensureOpen();
        return manager.search(pdfUri, keyword, baseOptions, callback);
    }

    public long search(
            String keyword,
            PdfSearchQueryOptions queryOptions,
            PdfSearchCallback callback
    ) {
        ensureOpen();
        PdfSearchOptions options = new PdfSearchOptions(baseOptions);
        options.queryOptions = new PdfSearchQueryOptions(queryOptions);
        return manager.search(pdfUri, keyword, options, callback);
    }

    /** 仅重试上次摘要中的可重试失败页面。 */
    public long retryFailedPages(
            String keyword,
            PdfSearchSummary previousSummary,
            PdfSearchCallback callback
    ) {
        ensureOpen();
        PdfSearchOptions options = new PdfSearchOptions(baseOptions);
        options.targetPages = new ArrayList<>();
        if (previousSummary != null) {
            for (PdfSearchPageError error : previousSummary.pageErrors) {
                if (error != null && error.retryable
                        && !options.targetPages.contains(error.pageIndex)) {
                    options.targetPages.add(error.pageIndex);
                }
            }
        }
        options.currentPageOnly = false;
        options.allowFullDocumentOcr = true;
        options.maxOcrPages = 0;
        return manager.search(pdfUri, keyword, options, callback);
    }

    public void pause() {
        ensureOpen();
        manager.pause();
    }

    public void resume() {
        ensureOpen();
        manager.resume();
    }

    public void cancel() {
        if (!closed) manager.cancel();
    }

    public void warmUpOcr() {
        ensureOpen();
        manager.warmUpOcr();
    }

    public void releaseOcrResources() {
        if (!closed) manager.releaseOcrResources();
    }

    public void trimMemory(int level) {
        if (!closed) manager.trimMemory(level);
    }

    public void clearCache() {
        ensureOpen();
        manager.clearCache();
    }

    public long getPersistentPageIndexCacheSizeBytes() {
        ensureOpen();
        return manager.getPersistentPageIndexCacheSizeBytes();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (ownsManager) manager.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PdfSearchSession 已关闭");
    }

    private static Context requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        return context;
    }
}
