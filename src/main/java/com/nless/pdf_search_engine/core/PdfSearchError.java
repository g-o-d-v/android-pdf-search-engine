package com.nless.pdf_search_engine.core;

import java.util.Locale;

/** 搜索任务级错误。单页错误使用 {@link PdfSearchPageError}。 */
public class PdfSearchError extends Exception {

    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_OPEN_PDF_FAILED = 1;
    public static final int ERROR_TEXT_SEARCH_FAILED = 2;
    public static final int ERROR_OCR_FAILED = 3;
    public static final int ERROR_NATIVE_LIBRARY_NOT_FOUND = 4;
    public static final int ERROR_CANCELLED = 5;
    public static final int ERROR_INVALID_ARGUMENT = 6;
    public static final int ERROR_PDF_ENCRYPTED = 7;
    public static final int ERROR_PDF_DAMAGED = 8;
    public static final int ERROR_OCR_MODEL_INIT_FAILED = 9;
    public static final int ERROR_OUT_OF_MEMORY = 10;
    public static final int ERROR_INDEX_FAILED = 11;
    public static final int ERROR_CACHE_FAILED = 12;

    public final int code;

    public PdfSearchError(int code, String message) {
        super(message);
        this.code = code;
    }

    public PdfSearchError(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 对常见 Android/PDF/OCR 异常做保守分类。加密与损坏只能根据底层异常信息尽力判断，
     * 无法可靠判断时保留调用方给出的 fallbackCode。
     */
    public static PdfSearchError classify(
            Throwable error,
            int fallbackCode,
            String fallbackMessage
    ) {
        if (error instanceof PdfSearchError) return (PdfSearchError) error;

        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof OutOfMemoryError) {
                return new PdfSearchError(
                        ERROR_OUT_OF_MEMORY,
                        "搜索过程中内存不足",
                        error
                );
            }
            if (cursor instanceof UnsatisfiedLinkError) {
                return new PdfSearchError(
                        ERROR_NATIVE_LIBRARY_NOT_FOUND,
                        "搜索所需 native 库未加载或 ABI 不匹配",
                        error
                );
            }
            String message = cursor.getMessage();
            String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("password") || lower.contains("encrypted")
                    || lower.contains("encrypt")) {
                return new PdfSearchError(
                        ERROR_PDF_ENCRYPTED,
                        "PDF 已加密或需要密码",
                        error
                );
            }
            if (lower.contains("corrupt") || lower.contains("damaged")
                    || lower.contains("malformed") || lower.contains("损坏")) {
                return new PdfSearchError(
                        ERROR_PDF_DAMAGED,
                        "PDF 文件损坏或结构不可解析",
                        error
                );
            }
            if ((lower.contains("ocr") || lower.contains("model")
                    || lower.contains("模型"))
                    && (lower.contains("init") || lower.contains("load")
                    || lower.contains("初始化") || lower.contains("加载"))) {
                return new PdfSearchError(
                        ERROR_OCR_MODEL_INIT_FAILED,
                        "OCR 模型初始化失败",
                        error
                );
            }
            cursor = cursor.getCause();
        }

        String message = fallbackMessage;
        if (message == null || message.isEmpty()) {
            message = error != null && error.getMessage() != null
                    ? error.getMessage()
                    : "PDF 搜索失败";
        }
        return new PdfSearchError(fallbackCode, message, error);
    }
}
