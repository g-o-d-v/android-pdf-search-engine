# 参与贡献

欢迎通过 Issue、Pull Request、文档修正、测试结果和性能数据参与项目。

项目代码采用 Apache License 2.0。提交 Pull Request 即表示：

- 你有权提交这些内容；
- 你同意该贡献按 Apache License 2.0 提供；
- 你没有加入来源不明的模型、原生库、PDF、图片或其他第三方材料。

项目不要求签署额外 CLA。

## 开始前

1. Bug 或较大功能建议先创建 Issue，说明使用场景和预期行为。
2. 不要上传包含个人、商业或机密信息的 PDF；可使用最小化合成样本。
3. 涉及第三方文件时，必须同时记录来源、版本、许可证和必要的通知。
4. 不要提交 `.idea`、`local.properties`、构建目录、密钥、Token、真实用户文档或生成的 `.nb` 模型。

## Pull Request 要求

- 说明修改目的、受影响 API 和兼容性；
- 列出测试设备、Android 版本和 ABI；
- JNI、缓存协议或序列化格式变化时同步更新版本常量与测试；
- 用户可见变化写入 `CHANGELOG.md`；
- 保留第三方版权和许可证头；
- 运行至少一项：

```powershell
.\gradlew.bat testReleaseUnitTest
.\gradlew.bat :sample:assembleDebug
.\gradlew.bat clean releaseCandidate
```

## 代码风格

- Java 使用 4 空格缩进；
- C/C++ 保持现有格式；
- 公共 API 尽量补充注释；
- 避免无关格式化和大范围重命名；
- 新线程、Bitmap、文件描述符和 native 资源必须有明确释放路径。

## 安全问题

可能涉及 native 崩溃、内存破坏或敏感数据泄露的问题，请按 [`SECURITY.md`](SECURITY.md) 私下报告，不要直接公开利用细节。
