package com.nless.pdf_search_engine.core;

/**
 * 搜索索引与缓存的独立版本号。
 *
 * 只修改对应能力的版本，避免无关变化导致全部 OCR 缓存失效。
 */
public final class PdfSearchVersions {

    private PdfSearchVersions() {
    }

    /** PDFium 文本字符和坐标提取协议。 */
    public static final int TEXT_EXTRACTION_VERSION = 3;

    /** Paddle OCR 模型集合。替换 det/rec/cls 模型时递增。 */
    public static final int OCR_MODEL_VERSION = 1;

    /** OCR token 几何生成算法。 */
    public static final int OCR_GEOMETRY_VERSION = 31;

    /** 文本规范化规则。 */
    public static final int NORMALIZATION_VERSION = 1;

    /** 持久化页面索引格式。 */
    public static final int PAGE_INDEX_SERIALIZATION_VERSION = 1;

    public static String textPageIndexNamespace() {
        return "text=" + TEXT_EXTRACTION_VERSION
                + ";index=" + PAGE_INDEX_SERIALIZATION_VERSION;
    }

    public static String ocrPageIndexNamespace() {
        return "ocrModel=" + OCR_MODEL_VERSION
                + ";ocrGeometry=" + OCR_GEOMETRY_VERSION
                + ";index=" + PAGE_INDEX_SERIALIZATION_VERSION;
    }

    /** 兼容旧调用；新缓存键应按来源选择独立命名空间。 */
    public static String pageIndexNamespace() {
        return textPageIndexNamespace() + ";" + ocrPageIndexNamespace();
    }
}
