# 官方 OCR 模型

仓库不直接跟踪生成后的 Paddle Lite `.nb` 文件。维护者本地构建或 JitPack 构建 Library 时，Gradle 会下载 `MODEL_PROVENANCE.md` 中记录的 PaddlePaddle 官方模型包，提取后加入 AAR：

```text
assets/cls.nb
assets/det.nb
assets/rec.nb
assets/MODEL_SHA256.txt
```

## 对集成项目的影响

集成项目引用 JitPack 依赖时：

- 只下载已经构建好的 AAR；
- 不执行本仓库的 `prepareOfficialOcrModels`；
- 不需要访问 PaddlePaddle 模型服务器；
- 不需要手工复制 `.nb` 文件；
- 模型与 OCR native 库会增加最终 APK/AAB 体积。

模型下载只发生在维护者本地构建或 JitPack 构建该版本时。一旦 JitPack 构建成功，该版本的使用方不再依赖官方模型下载地址。

## 源码构建

普通 Android 构建会自动触发：

```text
prepareOfficialOcrModels
```

Windows 可提前下载：

```powershell
.\download-official-models.bat
```

或：

```powershell
.\gradlew.bat prepareOfficialOcrModels
```

## 缓存和生成目录

下载包与提取模型缓存于：

```text
%USERPROFILE%\.gradle\caches\android-pdf-search-engine\
```

当前构建复制到：

```text
build/generated/officialOcrModels/
```

`MODEL_SHA256.txt` 记录该次 AAR 实际打包模型的 SHA-256。

## 为什么不提交模型

- 避免在 Git 历史中维护重复的大型二进制；
- 固定并公开官方来源 URL；
- 降低源码仓库体积；
- 让本地发布与 JitPack 使用同一模型准备流程。

## 下载故障处理

```powershell
Remove-Item -Recurse -Force `
  "$env:USERPROFILE\.gradle\caches\android-pdf-search-engine" `
  -ErrorAction SilentlyContinue

.\gradlew.bat prepareOfficialOcrModels --rerun-tasks --stacktrace
```
