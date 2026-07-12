package com.nless.pdf_search_engine.core;

/**
 * 单页 OCR 流水线性能数据。
 *
 * 所有耗时单位均为毫秒。缓存写入在独立 I/O 线程执行，因此这里只记录是否已进入
 * 写入队列；写入耗时不会阻塞当前页结果返回。
 */
public final class PdfSearchPageMetrics {

    public final int pageIndex;
    public final PdfSearchCacheSource cacheSource;

    /** 查询内存/磁盘缓存耗时。 */
    public final long cacheReadMillis;

    /** PDF 页面渲染耗时。缓存命中时为 0。 */
    public final long renderMillis;

    /** 页面已渲染完成后，在有界队列中等待唯一 OCR predictor 的时间；它与前一页 OCR 重叠，不应再次累加到全文耗时。 */
    public final long queueWaitMillis;

    /** Paddle OCR 推理耗时。缓存命中时为 0。 */
    public final long ocrMillis;

    /** 从 OCR 页面结果中匹配关键词并生成搜索结果的耗时。 */
    public final long resultBuildMillis;

    /** 从开始读取该页缓存到该页搜索结果完成的总耗时。 */
    public final long totalMillis;

    /** 渲染 Bitmap 的估算字节数；缓存命中时为 0。 */
    public final long renderedBitmapBytes;

    /** 新识别结果是否已提交或执行磁盘缓存写入。 */
    public final boolean cacheWriteQueued;

    public PdfSearchPageMetrics(
            int pageIndex,
            PdfSearchCacheSource cacheSource,
            long cacheReadMillis,
            long renderMillis,
            long queueWaitMillis,
            long ocrMillis,
            long resultBuildMillis,
            long totalMillis,
            long renderedBitmapBytes,
            boolean cacheWriteQueued
    ) {
        this.pageIndex = pageIndex;
        this.cacheSource = cacheSource != null
                ? cacheSource
                : PdfSearchCacheSource.NONE;
        this.cacheReadMillis = Math.max(0L, cacheReadMillis);
        this.renderMillis = Math.max(0L, renderMillis);
        this.queueWaitMillis = Math.max(0L, queueWaitMillis);
        this.ocrMillis = Math.max(0L, ocrMillis);
        this.resultBuildMillis = Math.max(0L, resultBuildMillis);
        this.totalMillis = Math.max(0L, totalMillis);
        this.renderedBitmapBytes = Math.max(0L, renderedBitmapBytes);
        this.cacheWriteQueued = cacheWriteQueued;
    }

    public boolean isCacheHit() {
        return cacheSource == PdfSearchCacheSource.MEMORY
                || cacheSource == PdfSearchCacheSource.DISK;
    }
}
