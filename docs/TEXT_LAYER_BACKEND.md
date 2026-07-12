# PDF 文本层后端与兼容版本

本项目的文本索引由 `libNative.so` 动态调用集成应用提供的 PDFium C API。

当前经过真机回归验证的阅读器组合：

```groovy
implementation "com.github.mhiew:android-pdf-viewer:3.2.0-beta.3"
```

其传递 PDFium runtime：

```text
com.github.mhiew:pdfium-android:1.9.2
```

更换 AndroidPdfViewer 或 PDFium 版本属于兼容性变更，必须重新验证文本提取、字符坐标、混合页回退和 Release/R8 构建。

## 加载策略

搜索库主动准备：

```text
libmodpdfium.so（或 libpdfium.so）
libNative.so
```

`jniPdfium`、png、freetype 等由阅读器自身加载。native 层解析以下 PDFium 符号：

```text
FPDF_InitLibrary
FPDF_LoadDocument
FPDF_CloseDocument
FPDF_GetPageCount
FPDF_LoadPage
FPDF_ClosePage
FPDF_GetPageSizeByIndex
FPDFText_LoadPage
FPDFText_ClosePage
FPDFText_CountChars
FPDFText_GetUnicode
FPDFText_GetCharBox
```

## 混合页策略

同一页面同时包含文本层和扫描图片时，推荐：

```java
options.textLayerOcrFallbackPolicy =
        PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH;
```

文本层未命中当前关键词时，该页再执行 OCR。

## Logcat

过滤：

```text
PdfiumTextNative
PdfiumTextSearch
PdfiumTextIndex
PdfDocumentIndexer
```

正常情况下可看到：

```text
Pdfium text symbols ready from libmodpdfium.so
```

文本提取失败不会写成有效文本索引，随后可按搜索选项回退 OCR。

## 回归测试

首次切换 PDFium runtime 或文本索引协议后，建议清除测试应用数据：

```powershell
adb shell pm clear com.nless.sandbox
```

再分别测试：

```text
全文检索  -> 第 1 页 PDF 文本层
扫描页    -> 第 2 页 OCR
混合搜索  -> 第 3 页 PDF 文本层
扫描      -> 第 3 页图片区域 OCR 回退
```
