# ABI 扩展指南

## 当前发布矩阵

```text
arm64-v8a
armeabi-v7a
```

每个 ABI 必须具备：

```text
libNative.so
libpaddle_light_api_shared.so
libopencv_java4.so
libc++_shared.so
```

`gradle.properties`：

```properties
SUPPORTED_ABIS=arm64-v8a,armeabi-v7a
```

发布检查会拒绝缺少文件或目录与配置不一致的 AAR。

## 为什么不能只增加 abiFilters

仅写入 `x86_64` 不会自动产生 Paddle Lite、OpenCV 和 C++ runtime。缺少任意一个文件都会在构建或运行时失败。

## 添加 x86_64

1. 获取与当前 Paddle Lite API、OpenCV 头文件和 C++ runtime 兼容的 `x86_64` 版本；
2. 建立 `src/main/jniLibs/x86_64/`；
3. 放入三个预编译 runtime；
4. 修改：

   ```properties
   SUPPORTED_ABIS=arm64-v8a,armeabi-v7a,x86_64
   ```

5. 清理并构建：

   ```powershell
   Remove-Item -Recurse -Force .cxx, build, src\.cxx, src\build, sample\build -ErrorAction SilentlyContinue
   .\gradlew.bat clean releaseCandidate
   ```

6. 验证：

   ```powershell
   python tools\verify_native_libs.py --require-abis arm64-v8a,armeabi-v7a,x86_64 src\main\jniLibs
   python tools\verify_native_libs.py build\outputs\aar\pdf-search-engine-release.aar
   ```

7. 在 `x86_64` 模拟器测试模型初始化、OCR、文本层搜索、暂停/取消和多页压力；
8. 确认集成应用使用的 PDFium 同样包含 `x86_64`。

`x86` 步骤相同，但优先级通常低于 `x86_64`。

## 发布说明

不要写“支持所有 Android 设备”。准确写明：

```text
Native ABIs: arm64-v8a, armeabi-v7a
x86/x86_64 are not included in this release.
```
