# Changelog

本文件记录面向使用者的主要变化。版本号遵循语义化版本原则；`0.x` 阶段公共 API 仍可能调整。

## [Unreleased]

暂无。

## [0.1.0-alpha01] - 2026-07-12

首个公开 Alpha 版本。

### Added

- PDFium 文本层全文、页范围和当前页搜索；
- Paddle Lite OCR 当前页与全文搜索；
- 文本层优先、按页回退 OCR，支持文本页、扫描页和混合页；
- 单 Predictor OCR 流水线、增量结果、暂停、恢复和取消；
- 页面级持久化索引与多关键词复用；
- Unicode、空白、跨行和英文断行规范化及坐标映射；
- 稳定结果 ID、上下文、多矩形逻辑结果和跨来源去重；
- 页面失败继续、搜索完整性摘要和损坏缓存恢复；
- AndroidPdfViewer 归一化坐标适配；
- `arm64-v8a` 与 `armeabi-v7a` native runtime；
- Apache-2.0 开源文件、JitPack 发布配置和官方 OCR 模型自动准备；
- 独立 `sample` 示例与回归测试应用。

### Fixed

- OCR 高亮由行框估算升级为 CTC token 与像素紧致框；
- 高亮坐标改为页面归一化并在绘制时动态换算；
- 文本层后端恢复到已验证的 mhiew/PdfiumAndroid runtime；
- 文本层提取失败不再被缓存成有效空索引；
- Gradle/CMake 发布与 Windows native 构建配置整理。

### Known limitations

- 暂不包含 `x86`/`x86_64` OCR runtime；
- PDFium/AndroidPdfViewer runtime 由集成应用提供；
- 当前已验证 PDFium runtime 不声明完整支持 Android 15 的 16 KiB page size；
- 特殊旋转 PDF 不提供额外兼容层；
- OCR 速度主要受设备 CPU、分辨率和页面内容影响；
- Alpha API 在 1.0 前可能调整。
