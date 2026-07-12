package com.nless.pdf_search_engine.core;

import java.util.ArrayList;
import java.util.List;

public class PdfSearchOptions {

    /**
     * 使用页面内容索引执行搜索。开启后内容提取与关键词查询分离，
     * 同一文档后续搜索可直接复用内存或磁盘索引。
     */
    public boolean enableDocumentIndex = true;

    /** 是否持久化统一页面索引（文本层和 OCR token）。 */
    public boolean usePersistentPageIndexCache = true;

    /** 页面索引磁盘缓存上限，默认 256 MiB。 */
    public long pageIndexDiskCacheMaxBytes = 256L * 1024L * 1024L;

    /** 查询规范化选项。 */
    public PdfSearchQueryOptions queryOptions = new PdfSearchQueryOptions();

    /** 是否去除文本层与 OCR 的重复命中。 */
    public boolean enableCrossSourceDeduplication = true;

    /** 跨来源去重的矩形 IoU 阈值。 */
    public float crossSourceDeduplicationIou = 0.65f;

    /** 是否检测常见双栏版面并按栏排序结果。 */
    public boolean detectMultiColumnLayout = true;

    /** 判定双栏所需的最小横向中心间隔比例。 */
    public float multiColumnMinGapRatio = 0.16f;


    /** 搜索模式。 */
    public PdfSearchMode mode = PdfSearchMode.TEXT_THEN_OCR;

    /** 是否只搜索当前页。 */
    public boolean currentPageOnly = false;

    /** 当前页页码，0-based。 */
    public int currentPage = 0;

    /**
     * 可选的精确目标页列表，优先于 startPage/endPage。用于失败页重试等场景。
     */
    public List<Integer> targetPages = null;

    /** 起始页，0-based。 */
    public int startPage = 0;

    /** 结束页（包含），-1 表示最后一页。 */
    public int endPage = -1;

    /**
     * OCR 渲染宽度。
     * 960：快；1280：平衡；1440：精度优先。
     */
    public int ocrRenderWidth = 1280;

    /** TEXT_THEN_OCR 模式下是否允许 fallback 到 OCR。 */
    public boolean fallbackToOcrWhenTextNotFound = true;

    /**
     * 是否按页分析文本层，并只对文本层不可用/不完整的页面执行 OCR。
     * 开启后可正确处理“部分页面有文本层、部分页面是扫描图”的混合 PDF。
     */
    public boolean enablePageLevelTextOcrFallback = true;

    /** 逐页 OCR fallback 策略。 */
    public PdfTextLayerOcrFallbackPolicy textLayerOcrFallbackPolicy =
            PdfTextLayerOcrFallbackPolicy.UNUSABLE_TEXT_LAYER_ONLY;

    /** 判断文本层可用所需的最少可见字符数。 */
    public int textLayerMinVisibleCharacters = 8;

    /** 判断文本层坐标可用所需的最小字符框覆盖率。 */
    public float textLayerMinBoxCoverage = 0.50f;

    /**
     * 指定 OCR 目标页。null/空表示由范围和页面顺序自动规划。
     * 主要由 TEXT_THEN_OCR 的逐页 fallback 内部使用，也可供高级调用方限定 OCR 页。
     */
    public List<Integer> ocrTargetPages = null;

    /**
     * 是否忽略大小写（兼容旧 API）。新代码优先使用 queryOptions.caseSensitive。
     * 任一处要求区分大小写时，索引查询都会按区分大小写执行。
     */
    public boolean ignoreCase = true;

    /** 是否允许全文/多页 OCR。关闭时 OCR 只处理 currentPage。 */
    public boolean allowFullDocumentOcr = false;

    /**
     * 全文 OCR 最多处理页数。
     * 默认仍保留旧版的 5 页安全限制；0 或负数表示不额外限制。
     */
    public int maxOcrPages = 5;

    /** 全文 OCR 页面处理顺序。 */
    public PdfSearchPageOrder ocrPageOrder = PdfSearchPageOrder.CURRENT_PAGE_OUTWARD;

    /** 是否把已完成页面的结果增量回调给调用方。 */
    public boolean emitIncrementalResults = true;

    /**
     * 是否启用“渲染下一页 / OCR 当前页 / 写入上一页缓存”的三阶段流水线。
     * 流水线仍然只有一个 OCR predictor，不会并行执行多份 OCR 推理。
     */
    public boolean enableOcrPipeline = true;

    /**
     * 最多预渲染并保留多少页 Bitmap。默认 1，建议保持 1。
     * 该值只影响渲染预取，不会增加 OCR predictor 数量。
     */
    public int ocrPrefetchPages = 1;

    /**
     * 异步磁盘缓存队列容量。队列满时当前 OCR 线程会临时承担写入，形成内存背压。
     */
    public int ocrCacheWriteQueueCapacity = 2;

    /**
     * 正常搜索完成时等待异步缓存落盘的最长时间。0 表示不等待。
     */
    public long ocrCacheWriteDrainTimeoutMillis = 5_000L;

    /** 找到第一批 OCR 命中后是否立即停止。默认继续搜索全部目标页。 */
    public boolean stopAfterFirstOcrMatch = false;

    /** 最大结果数，0 或负数表示不限制。 */
    public int maxResults = 0;

    /** 是否启用跨进程/重启可复用的 OCR 磁盘缓存。 */
    public boolean usePersistentOcrCache = true;

    /** OCR 磁盘缓存上限，默认 128 MiB。 */
    public long ocrDiskCacheMaxBytes = 128L * 1024L * 1024L;

    /**
     * OCR 模型和几何算法命名空间。模型或返回协议变化时必须修改，
     * 防止旧缓存与新模型混用。
     */
    public String ocrCacheNamespace = "ppocr-mobile-geometry-v3.1-phase5-v1";

    /** 是否输出 OCR 几何调试框。正式版本建议关闭。 */
    public boolean ocrDebugOverlayEnabled = false;

    /** OCR 几何调试回调。 */
    public OcrDebugGeometryListener ocrDebugGeometryListener = null;

    public PdfSearchOptions() {
    }

    /** 搜索开始时复制一份，避免调用方在后台任务运行期间修改参数。 */
    public PdfSearchOptions(PdfSearchOptions other) {
        if (other == null) return;
        mode = other.mode;
        enableDocumentIndex = other.enableDocumentIndex;
        usePersistentPageIndexCache = other.usePersistentPageIndexCache;
        pageIndexDiskCacheMaxBytes = other.pageIndexDiskCacheMaxBytes;
        queryOptions = new PdfSearchQueryOptions(other.queryOptions);
        enableCrossSourceDeduplication = other.enableCrossSourceDeduplication;
        crossSourceDeduplicationIou = other.crossSourceDeduplicationIou;
        detectMultiColumnLayout = other.detectMultiColumnLayout;
        multiColumnMinGapRatio = other.multiColumnMinGapRatio;
        currentPageOnly = other.currentPageOnly;
        currentPage = other.currentPage;
        targetPages = other.targetPages == null
                ? null
                : new ArrayList<>(other.targetPages);
        startPage = other.startPage;
        endPage = other.endPage;
        ocrRenderWidth = other.ocrRenderWidth;
        fallbackToOcrWhenTextNotFound = other.fallbackToOcrWhenTextNotFound;
        enablePageLevelTextOcrFallback = other.enablePageLevelTextOcrFallback;
        textLayerOcrFallbackPolicy = other.textLayerOcrFallbackPolicy;
        textLayerMinVisibleCharacters = other.textLayerMinVisibleCharacters;
        textLayerMinBoxCoverage = other.textLayerMinBoxCoverage;
        ocrTargetPages = other.ocrTargetPages == null
                ? null
                : new ArrayList<>(other.ocrTargetPages);
        ignoreCase = other.ignoreCase;
        allowFullDocumentOcr = other.allowFullDocumentOcr;
        maxOcrPages = other.maxOcrPages;
        ocrPageOrder = other.ocrPageOrder;
        emitIncrementalResults = other.emitIncrementalResults;
        enableOcrPipeline = other.enableOcrPipeline;
        ocrPrefetchPages = other.ocrPrefetchPages;
        ocrCacheWriteQueueCapacity = other.ocrCacheWriteQueueCapacity;
        ocrCacheWriteDrainTimeoutMillis = other.ocrCacheWriteDrainTimeoutMillis;
        stopAfterFirstOcrMatch = other.stopAfterFirstOcrMatch;
        maxResults = other.maxResults;
        usePersistentOcrCache = other.usePersistentOcrCache;
        ocrDiskCacheMaxBytes = other.ocrDiskCacheMaxBytes;
        ocrCacheNamespace = other.ocrCacheNamespace;
        ocrDebugOverlayEnabled = other.ocrDebugOverlayEnabled;
        ocrDebugGeometryListener = other.ocrDebugGeometryListener;
    }
}
