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
    implementation "com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha03"

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
com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha03
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

## 5. Native 库冲突处理

本库与 `android-pdf-viewer` 可能同时包含 `libc++_shared.so`。该冲突发生在最终 APK/AAB 的应用模块打包阶段，Library AAR 中的 `packaging` 配置不会自动传递给集成应用。

在应用模块的 `build.gradle` 中加入：

```groovy
android {
    packaging {
        jniLibs {
            pickFirsts += ['**/libc++_shared.so']
        }
    }
}
```

不要使用 `excludes` 删除全部 `libc++_shared.so`。构建完成后需在真机上同时验证 OCR 与 PDF 渲染。仓库中的 `sample` 模块已经使用该配置。

## 6. 查询规范化

Alpha03 默认启用 OCR `O/o/0` 易混淆字符容错；该规则只作用于 OCR 页面，不改变 PDF 原生文本层的字符语义：

```java
options.queryOptions.tolerateOcrOZeroConfusion = true;
```

跨行匹配默认只移除换行及其两侧的版面空白，同一行中的普通单词空格仍参与匹配：

```java
options.queryOptions.allowCrossLineMatch = true;
options.queryOptions.ignoreWhitespaceForMatching = false;
```

例如页面内容 `跨行\n检索测试` 可以由查询 `跨行检索测试` 命中；同一行中的 `PDF search` 不会被无空格查询 `PDFsearch` 命中。需要忽略所有空白时，再显式设置 `ignoreWhitespaceForMatching = true`。

Alpha03 还会根据 OCR token 字符框和同一视觉行中文本块的水平间距恢复模型遗漏的普通空格。例如图像中可见的 `OCR 扫描` 即使被识别模型返回为 `OCR扫描`，页面索引也会在几何间距足够明确时重建空格；普通中文字符间距和统一的标题字距不会被直接视为空格。

## 7. ABI

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

## 8. R8 / ProGuard

AAR 已自带混淆规则。使用非标准 shrinker 时至少保留：

```text
com.nless.pdf_search_engine.paddle.PaddleOcrNative
com.nless.pdf_search_engine.pdfium.PdfiumTextNative
```

## 9. 生命周期

```java
session.close();
session.trimMemory(level);
session.releaseOcrResources();
```

阅读器关闭文档时应关闭 session。进入后台或内存紧张时可释放 OCR predictor。

## 10. 混合页回退策略

需要搜索同一页中的文本层和扫描图片时，建议：

```java
options.mode = PdfSearchMode.TEXT_THEN_OCR;
options.enablePageLevelTextOcrFallback = true;
options.textLayerOcrFallbackPolicy =
        PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH;
```

该策略先搜索文本层；文本层没有当前关键词时，再对该页执行 OCR。

## 11. 结果与高亮

搜索库返回逻辑结果，不管理阅读器 UI：

1. `results.size()` 是逻辑命中数；
2. 一个结果可包含多个 `rects`；
3. 使用 `resultId` 保存当前选中结果；
4. 阅读器自行实现普通/选中高亮、上一个/下一个、跳页和滚动。

## 12. 16 KiB

项目自带的 ARM64 OCR native 库提供 16 KiB ELF 对齐检查。当前已验证 PDFium runtime 不声明完整支持最终 APK 的 16 KiB page size。详见 [`PDFIUM_16K_COMPATIBILITY.md`](PDFIUM_16K_COMPATIBILITY.md)。
