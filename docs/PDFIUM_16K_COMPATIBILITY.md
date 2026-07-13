# PDFium / AndroidPdfViewer 与 16 KiB 兼容检查清单

> **当前发布状态**：`0.1.0-alpha03` 使用已验证的
> `com.github.mhiew:android-pdf-viewer:3.2.0-beta.3` 与
> `com.github.mhiew:pdfium-android:1.9.2`。本项目不声明该 PDFium runtime
> 已通过 Android 15 的 16 KiB page-size 要求。

## 当前能确认的范围

项目自带的 OCR/C++ native 库可检查 ELF LOAD 对齐：

```powershell
python tools\verify_native_libs.py `
  --require-abis arm64-v8a,armeabi-v7a `
  src\main\jniLibs
```

构建 AAR 后：

```powershell
python tools\verify_native_libs.py `
  build\outputs\aar\pdf-search-engine-release.aar
```

这只能证明搜索库自身携带的 `.so` 状态，不能证明集成应用最终 APK 兼容。

## 更换 PDFium runtime 的验证要求

新的 PDFium/AndroidPdfViewer 组合必须同时满足：

1. 纯文本 PDF 返回文本层结果；
2. 扫描 PDF 按页回退 OCR；
3. 混合 PDF 的文本层与图片区域均可搜索；
4. 字符坐标和高亮框正确；
5. Release/R8 构建正常；
6. 所有 64 位 `.so` 通过 16 KiB ELF 对齐检查；
7. 最终 APK 通过 ZIP 对齐检查；
8. 在真实 16 KiB 系统或模拟器上运行通过。

## 最终 APK 检查

```powershell
python tools\verify_native_libs.py path\to\app-release.apk
zipalign -c -P 16 -v 4 path\to\app-release.apk
adb shell getconf PAGE_SIZE
```

16 KiB 设备应返回：

```text
16384
```
