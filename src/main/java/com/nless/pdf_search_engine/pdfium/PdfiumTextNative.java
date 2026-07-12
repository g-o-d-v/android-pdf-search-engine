package com.nless.pdf_search_engine.pdfium;

import android.util.Log;

/**
 * PDFium 文本层 JNI 入口。
 *
 * 文本搜索只需要 PDFium C API（libmodpdfium.so / libpdfium.so）和本项目
 * libNative.so。AndroidPdfViewer 自己负责加载 jniPdfium、png 和 freetype。
 * 这里不再把整个阅读器 native 栈的加载结果作为文本后端可用性的前置条件，
 * 避免因不必要的 jniPdfium 重复加载而错误退化到 OCR。
 */
public class PdfiumTextNative {

    private static final String TAG = "PdfiumTextNative";
    private static final boolean PROJECT_NATIVE_LOADED;
    private static final Throwable PROJECT_NATIVE_LOAD_ERROR;

    static {
        // 优先触发集成应用中的 PdfiumCore 静态初始化。使用反射以保持 compileOnly。
        try {
            Class.forName(
                    "com.shockwave.pdfium.PdfiumCore",
                    true,
                    PdfiumTextNative.class.getClassLoader()
            );
        } catch (Throwable ignored) {
            // 高级调用方也可以只提供兼容的 libmodpdfium.so。
        }

        // 文本 JNI 直接解析 PDFium C 符号，只需要 PDFium 主库。
        try {
            System.loadLibrary("modpdfium");
        } catch (Throwable firstError) {
            try {
                System.loadLibrary("pdfium");
            } catch (Throwable secondError) {
                Log.w(TAG, "PDFium main library was not loaded by Java; native dlopen will retry", secondError);
            }
        }

        boolean nativeLoaded = false;
        Throwable nativeError = null;
        try {
            System.loadLibrary("Native");
            nativeLoaded = true;
        } catch (Throwable error) {
            nativeError = error;
            Log.e(TAG, "Project native library failed to load", error);
        }

        PROJECT_NATIVE_LOADED = nativeLoaded;
        PROJECT_NATIVE_LOAD_ERROR = nativeError;
    }

    public static boolean isProjectNativeLoaded() {
        return PROJECT_NATIVE_LOADED;
    }

    public static Throwable getProjectNativeLoadError() {
        return PROJECT_NATIVE_LOAD_ERROR;
    }

    /** 返回 native PDFium 符号解析状态，便于集成诊断。 */
    public native String nativeGetBackendStatus();

    /**
     * 详细协议：逐页文本层统计 + 按逻辑命中分组的 1..N 个矩形。
     */
    public native float[] nativeSearchDetailed(
            String pdfPath,
            String keyword,
            int startPage,
            int endPage,
            boolean ignoreCase
    );

    /** 提取与关键词无关的页面字符和字符框。 */
    public native byte[] nativeExtractIndex(
            String pdfPath,
            int startPage,
            int endPage
    );

    /**
     * 兼容旧版扁平协议：每 7 个 float 为一个矩形：
     * pageIndex, left, top, right, bottom, pageWidth, pageHeight。
     */
    public native float[] nativeSearch(
            String pdfPath,
            String keyword,
            int startPage,
            int endPage
    );
}
