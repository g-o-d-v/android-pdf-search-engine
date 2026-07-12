# Native 与设备兼容性

## ABI

当前 Release AAR 包含：

```text
arm64-v8a
armeabi-v7a
```

每个 ABI 都必须同时具有 Paddle Lite、OpenCV、C++ runtime 和本项目 `libNative.so`。`verifyReleaseInputs` 会拒绝不完整 ABI。

添加 `x86/x86_64` 的完整步骤见 [`ABI_EXPANSION.md`](ABI_EXPANSION.md)。

## 16 KiB memory page size

项目固定 NDK `21.4.7075529` 以兼容当前 OCR 预编译库；本项目 `libNative.so` 显式链接：

```text
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

当前 ARM64 Paddle/OpenCV/C++ `.so` 的 ELF LOAD 对齐已验证。`armeabi-v7a` 是 32 位 ABI，不属于 64 位 16 KiB 强制检查范围，但仍会报告其对齐值。

验证：

```bash
python tools/verify_native_libs.py src/main/jniLibs
python tools/verify_native_libs.py build/outputs/aar/pdf-search-engine-release.aar
```

本工具只验证 ELF。最终集成 APK 还要使用 `zipalign -c -P 16 -v 4` 并在 16 KiB 系统上运行。

## PDFium

当前文本层已验证的 runtime：

```text
com.github.mhiew:android-pdf-viewer:3.2.0-beta.3
com.github.mhiew:pdfium-android:1.9.2
```

这是原项目已经通过文本层全文搜索回归的组合。搜索库通过 `dlopen`/`dlsym`
获取 PDFium C API；更换 PDFium 版本属于兼容性变更，必须重新测试文本提取、
字符坐标、混合 PDF 和 Release/R8。

### 16 KiB 状态

本项目自带的 ARM64 OCR/C++ native 库继续进行 16 KiB ELF 对齐检查；但当前
已验证的 mhiew/PdfiumAndroid 运行时较旧，项目**不声明最终集成 APK 已完整支持
16 KiB page size**。在找到同时通过文本搜索和 16 KiB 回归的新 PDFium 组合前，
不要在 README 或 Release 中声称整个应用已具备该兼容性。

完整检查方法仍可参考 [`PDFIUM_16K_COMPATIBILITY.md`](PDFIUM_16K_COMPATIBILITY.md)，
但其中 PDFium 运行时必须由集成方重新验证。
