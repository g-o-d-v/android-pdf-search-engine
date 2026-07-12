# JNI entry points use name-based symbol lookup. Keep both the native method
# names and their declaring class names when the consuming application enables
# R8/ProGuard minification.
-keep class com.nless.pdf_search_engine.paddle.PaddleOcrNative {
    public <init>(...);
    native <methods>;
}

-keep class com.nless.pdf_search_engine.pdfium.PdfiumTextNative {
    public <init>(...);
    native <methods>;
}

# Preserve useful source/line information for crash reports originating in the
# public search API. Consumers remain free to strip it in their final rules.
-keepattributes SourceFile,LineNumberTable
