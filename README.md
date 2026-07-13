# Android PDF Search Engine

[![JitPack](https://jitpack.io/v/g-o-d-v/android-pdf-search-engine.svg)](https://jitpack.io/#g-o-d-v/android-pdf-search-engine)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-brightgreen.svg)](#兼容范围)

面向 Android 的 PDF 搜索库，统一提供 PDF 文本层搜索、Paddle Lite OCR、页面级持久化索引和逻辑搜索结果。

当前版本：**`0.1.0-alpha03`**。项目采用 **Apache License 2.0**，允许个人或组织使用、修改、分发和商业使用；再分发时需保留许可证与必要通知。

## 主要能力

- PDFium 文本层全文、页范围或当前页搜索；
- 扫描 PDF 当前页或全文 OCR；
- 文本层优先、按页回退 OCR，支持文本页、扫描页和混合页；
- 单 OCR Predictor 流水线：页面预渲染、单路推理和异步缓存；
- 页面级持久化索引，更换关键词时复用文本与坐标；
- Unicode NFKC、大小写、OCR 几何空格重建、无空格跨换行和英文断行规范化；
- OCR `O/o/0` 易混淆字符容错，可按查询关闭；
- 稳定 `resultId`、跨行多矩形、结果上下文和跨来源去重；
- 增量结果、进度、页面性能统计、完整性摘要、失败页继续；
- 暂停、恢复、取消以及 OCR/native 资源释放；
- AndroidPdfViewer 页面归一化坐标适配，缩放和拖动后高亮稳定。

## 兼容范围

| 项目 | 当前支持 |
|---|---|
| 最低 Android | API 24 |
| 编译 SDK | API 35 |
| Native ABI | `arm64-v8a`、`armeabi-v7a` |
| OCR | PP-OCRv4 Mobile + Paddle Lite CPU 单 Predictor |
| 已验证 PDF runtime | `com.github.mhiew:android-pdf-viewer:3.2.0-beta.3` |
| 许可证 | Apache-2.0 |
| 发布方式 | GitHub + JitPack |

`x86` 和 `x86_64` 暂未提供完整的 Paddle Lite/OpenCV native 运行库，因此本版本不声明支持。

## 仓库结构

```text
android-pdf-search-engine/
├─ src/                  Android Library 源码与 native 实现
├─ sample/               示例和回归测试应用
├─ docs/                 集成、模型和兼容性文档
├─ tools/                JitPack 与 native 检查工具
├─ build.gradle          Library 构建和 Maven Publication
└─ settings.gradle
```

`sample` 仅用于演示和回归测试：

- 它通过 `implementation project(':')` 引用根目录 Library；
- 它不会进入 Release AAR；
- 它不会作为 JitPack 依赖发布；
- 其中的 Activity、布局、图标和测试 PDF 不会进入集成应用。

JitPack 正式发布对象只有根目录 Android Library。

## 通过 JitPack 引用

在集成项目的 `settings.gradle` 中加入 JitPack：

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

### 仅使用 OCR 搜索

如果项目只使用扫描页 OCR 搜索，在应用模块的 `build.gradle` 中加入：

```groovy
dependencies {
    implementation "com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha03"
}
```

例如：

```java
options.mode = PdfSearchMode.OCR_ONLY;
```

### 使用 PDF 文本层搜索

如果需要使用以下功能：

- PDF 原生文本层搜索；
- `TEXT_ONLY` 模式；
- `TEXT_THEN_OCR` 混合搜索；
- `AndroidPdfViewerAdapter`；
- 示例项目中的 PDF 阅读和高亮能力；

集成项目还需要提供兼容的 PDF Viewer/PDFium 运行时：

```groovy
dependencies {
    implementation "com.github.g-o-d-v:android-pdf-search-engine:0.1.0-alpha03"

    implementation "com.github.mhiew:android-pdf-viewer:3.2.0-beta.3"
}
```

例如：

```java
options.mode = PdfSearchMode.TEXT_THEN_OCR;
```

`android-pdf-viewer` 在搜索库中以 `compileOnly` 方式引用，只用于编译可选的 PDF Viewer 适配器，不会自动随搜索库 AAR 传递给集成项目。

如果集成项目已经引用了兼容版本：

```groovy
implementation "com.github.mhiew:android-pdf-viewer:3.2.0-beta.3"
```

则无需重复添加。

> 当前文本层搜索链路基于上述 PDF Viewer/PDFium 运行时完成验证。使用其他 PDF Viewer 或 PDFium 版本时，需要自行确认 native 库和 PDFium 文本 API 的兼容性。

### Native 库冲突处理

本库与 `android-pdf-viewer` 都可能向最终 APK 提供 `libc++_shared.so`。最终 native 文件由**应用模块**统一打包，Android Library AAR 无法把自己的 `packaging` 规则自动传递给集成应用，因此当前不能在本库内部安全地替宿主完成该选择。

请在应用模块的 `build.gradle` 中加入：

```groovy
android {
    packaging {
        jniLibs {
            pickFirsts += ['**/libc++_shared.so']
        }
    }
}
```

不要把所有 `libc++_shared.so` 排除。OCR、OpenCV、Paddle Lite 或 PDFium 可能在运行时依赖该共享 C++ 运行库。构建完成后，请在真机上同时验证 OCR 搜索与 PDF 渲染。

仓库中的 `sample` 已采用同一配置，可作为集成参考。

JitPack 坐标规则：

```text
com.github.<GitHub 用户名>:<仓库名>:<Git 标签>
```

完整集成说明见 [`docs/INTEGRATION.md`](docs/INTEGRATION.md)。

## OCR 模型如何交付

仓库不直接提交 `cls.nb`、`det.nb` 和 `rec.nb`。

维护者本地构建或 JitPack 构建 Library 时，Gradle 会从 PaddlePaddle 官方地址下载模型，并将它们打入最终 AAR：

```text
assets/cls.nb
assets/det.nb
assets/rec.nb
assets/MODEL_SHA256.txt
```

集成项目引用 JitPack 版本时只下载已经构建好的 AAR，**不会执行本仓库的模型下载任务，也不需要访问 PaddlePaddle 模型服务器**。模型和 native 库会增加最终应用包体积，这是离线 OCR 的必要组成部分。

详情见 [`MODEL_PROVENANCE.md`](MODEL_PROVENANCE.md) 和 [`docs/OFFICIAL_OCR_MODELS.md`](docs/OFFICIAL_OCR_MODELS.md)。

## 本地构建

构建环境：

```text
JDK 17
Gradle 8.7（Wrapper 已包含）
Android Gradle Plugin 8.6.1
Android SDK 35
NDK 21.4.7075529
CMake 3.22.1
```

提前准备官方 OCR 模型：

```powershell
.\gradlew.bat prepareOfficialOcrModels
```

构建示例应用：

```powershell
.\gradlew.bat :sample:assembleDebug
```

本地模拟 JitPack 发布：

```powershell
.\verify-before-jitpack.bat
```

完整发布候选检查：

```powershell
.\gradlew.bat clean releaseCandidate
```

## 基本使用

```java
PdfSearchOptions options = new PdfSearchOptions();
options.mode = PdfSearchMode.TEXT_THEN_OCR;
options.enablePageLevelTextOcrFallback = true;
options.textLayerOcrFallbackPolicy =
        PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH;
options.allowFullDocumentOcr = true;
options.maxOcrPages = 0;
options.enableDocumentIndex = true;
options.usePersistentPageIndexCache = true;
options.enableOcrPipeline = true;
options.ocrPrefetchPages = 1;

// Alpha03：OCR O/o/0 容错，以及只忽略换行、保留普通空格。
options.queryOptions.tolerateOcrOZeroConfusion = true;
options.queryOptions.allowCrossLineMatch = true;
options.queryOptions.ignoreWhitespaceForMatching = false;

// OCR 页面索引会根据字符框和同一视觉行的块间距恢复遗漏的普通空格。
// 因此可见内容 “OCR 扫描” 应由带空格查询命中；无空格连接只用于真正换行。

try (PdfSearchSession session =
             new PdfSearchSession(context, pdfUri, options)) {

    session.search("音乐", new PdfSearchCallback() {
        @Override
        public void onSearchStarted(String keyword) {
        }

        @Override
        public void onSearchProgress(
                int currentPage,
                int totalPage,
                PdfSearchSource source
        ) {
        }

        @Override
        public void onSearchCompleted(List<PdfSearchResult> results) {
        }

        @Override
        public void onSearchCompleted(
                List<PdfSearchResult> results,
                PdfSearchSummary summary
        ) {
            // results.size() 是逻辑命中数量。
            // 一个跨行结果可能包含多个 result.rects。
        }

        @Override
        public void onSearchFailed(Throwable error) {
        }

        @Override
        public void onSearchCancelled() {
        }
    });
}
```

同一 PDF 连续搜索多个关键词时，可先建立并复用索引：

```java
session.prepareIndex(indexCallback);
session.search("音乐", callback);
session.search("乐器", callback);
```

示例应用使用项目自建的三页合成 PDF，说明见 [`docs/SAMPLE_PDF.md`](docs/SAMPLE_PDF.md)。

## 搜索库与阅读器的职责

搜索库负责：

- 文本层和 OCR 内容提取；
- 页面索引、缓存和查询规范化；
- 逻辑结果分组、稳定排序、结果 ID、页码、矩形和上下文；
- 进度、错误、完整性、暂停、恢复和取消。

PDF 阅读器负责：

- 普通高亮和当前选中高亮的样式；
- 当前结果序号、上一个和下一个；
- 跳页、滚动、缩放、居中和首个结果选择策略。

## Native 与 16 KiB page size

项目提供工具检查自身携带的 OCR/C++ native 库：

```powershell
python tools\verify_native_libs.py `
  --require-abis arm64-v8a,armeabi-v7a `
  src\main\jniLibs
```

当前只确认项目自带 ARM64 OCR/C++ 库的 ELF 对齐。为保证文本层搜索正确，本版本使用已验证的 mhiew/PdfiumAndroid runtime，因此**不声明最终集成 APK 已完整通过 Android 15 的 16 KiB page-size 要求**。

详情见 [`docs/NATIVE_COMPATIBILITY.md`](docs/NATIVE_COMPATIBILITY.md) 和 [`docs/PDFIUM_16K_COMPATIBILITY.md`](docs/PDFIUM_16K_COMPATIBILITY.md)。

## 支持项目

项目完全开源，打赏完全自愿，不影响任何功能、许可或使用权。

<table>
  <tr>
    <td align="center"><strong>微信</strong></td>
    <td align="center"><strong>支付宝</strong></td>
  </tr>
  <tr>
    <td><img src="docs/assets/donation/wechat-pay.png" width="240" alt="微信收款码"></td>
    <td><img src="docs/assets/donation/alipay.png" width="240" alt="支付宝收款码"></td>
  </tr>
</table>

## 文档

- [`docs/INTEGRATION.md`](docs/INTEGRATION.md)：依赖、ABI、R8、生命周期与结果集成；
- [`docs/TEXT_LAYER_BACKEND.md`](docs/TEXT_LAYER_BACKEND.md)：PDFium 文本层后端与兼容组合；
- [`docs/OFFICIAL_OCR_MODELS.md`](docs/OFFICIAL_OCR_MODELS.md)：官方模型下载、缓存与 AAR 打包；
- [`docs/API_STABILITY.md`](docs/API_STABILITY.md)：Alpha 阶段 API 与版本策略。

## 参与贡献

欢迎提交 Issue 和 Pull Request。贡献代码默认按本项目的 Apache-2.0 许可证提供，不要求签署额外 CLA。

参见 [`CONTRIBUTING.md`](CONTRIBUTING.md)、[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) 和 [`SECURITY.md`](SECURITY.md)。

## 许可证

项目代码使用 [Apache License 2.0](LICENSE)。第三方组件继续使用各自许可证，详见 [`NOTICE`](NOTICE)、[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 和 [`LICENSES/`](LICENSES/)。

Copyright 2026 g-o-d-v · `3472966871@qq.com`
