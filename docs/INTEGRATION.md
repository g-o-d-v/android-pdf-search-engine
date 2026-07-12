# 集成说明

## 1. 添加 JitPack 仓库

在集成项目的 `settings.gradle` 中：

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.g-o-d-v")
            }
        }
    }
}
```

## 2. 添加依赖

```groovy
dependencies {
    implementation "com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha01"

    // 使用文本层搜索或 AndroidPdfViewer 适配层时添加。
    implementation "com.github.mhiew:android-pdf-viewer:3.2.0-beta.3"
}
```

本地发布测试：

```powershell
.\gradlew.bat publishReleasePublicationToMavenLocal
```

本地 Maven 坐标与 JitPack 坐标一致：

```text
com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha01
```

## 3. 发布产物与模型

JitPack 发布的是根目录 Android Library 生成的 AAR。仓库中的 `sample` 模块不会被打入 AAR，也不会作为依赖传递给集成项目。

JitPack 构建时会下载官方 `.nb` 模型并将其打入 AAR：

```text
assets/cls.nb
assets/det.nb
assets/rec.nb
```

集成项目只下载已经构建完成的 AAR，不会执行模型下载任务，也不需要直接访问 PaddlePaddle 模型服务器。

## 4. PDFium / AndroidPdfViewer

AAR 自带 OCR 相关 native runtime，但不把 PDFium/AndroidPdfViewer 作为传递依赖。文本层搜索和 AndroidPdfViewer 适配层当前验证组合为：

```groovy
implementation "com.github.mhiew:android-pdf-viewer:3.2.0-beta.3"
```

文本层引擎会尝试加载：

```text
libmodpdfium.so
libpdfium.so
```

只使用 OCR 核心 API 时可以不集成 AndroidPdfViewer，但文本层搜索需要应用提供兼容的 PDFium runtime。

## 5. ABI

当前 AAR 支持：

```text
arm64-v8a
armeabi-v7a
```

启动前可检查：

```java
if (!PdfSearchLibraryInfo.isCurrentDeviceAbiSupported()) {
    // 禁用 native 搜索或提示当前 ABI 未打包。
}
```

集成应用的 ABI 集合应同时被 OCR runtime 与 PDFium 支持。

## 6. R8 / ProGuard

AAR 已自带混淆规则。使用非标准 shrinker 时至少保留：

```text
com.nless.pdf_search_engine.paddle.PaddleOcrNative
com.nless.pdf_search_engine.pdfium.PdfiumTextNative
```

## 7. 生命周期

```java
session.close();
session.trimMemory(level);
session.releaseOcrResources();
```

阅读器关闭文档时应关闭 session。进入后台或内存紧张时可释放 OCR predictor。

## 8. 混合页回退策略

需要搜索同一页中的文本层和扫描图片时，建议：

```java
options.mode = PdfSearchMode.TEXT_THEN_OCR;
options.enablePageLevelTextOcrFallback = true;
options.textLayerOcrFallbackPolicy =
        PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH;
```

该策略先搜索文本层；文本层没有当前关键词时，再对该页执行 OCR。

## 9. 结果与高亮

搜索库返回逻辑结果，不管理阅读器 UI：

1. `results.size()` 是逻辑命中数；
2. 一个结果可包含多个 `rects`；
3. 使用 `resultId` 保存当前选中结果；
4. 阅读器自行实现普通/选中高亮、上一个/下一个、跳页和滚动。

## 10. 16 KiB

项目自带的 ARM64 OCR native 库提供 16 KiB ELF 对齐检查。当前已验证 PDFium runtime 不声明完整支持最终 APK 的 16 KiB page size。详见 [`PDFIUM_16K_COMPATIBILITY.md`](PDFIUM_16K_COMPATIBILITY.md)。
