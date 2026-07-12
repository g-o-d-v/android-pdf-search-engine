# Rules used only if this library is minified as part of a future build setup.
# Consumer-facing JNI rules live in consumer-rules.pro and are packaged into
# the AAR automatically.
-keep class com.nless.pdf_search_engine.paddle.PaddleOcrNative {
    public <init>(...);
    native <methods>;
}
-keep class com.nless.pdf_search_engine.pdfium.PdfiumTextNative {
    public <init>(...);
    native <methods>;
}
