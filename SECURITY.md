# 安全策略

## 支持范围

安全修复优先覆盖最新发布的 `0.x` 版本。Alpha 阶段可能为修复 native 安全、缓存完整性或数据泄露问题而调整 API。

## 私下报告

请勿在公开 Issue 中上传：

- 含个人或机密信息的 PDF；
- 可直接利用的 native 崩溃样本；
- Token、密钥或未公开漏洞细节。

安全联系邮箱：**3472966871@qq.com**

邮件建议包含：

- 受影响版本、设备、Android 版本和 ABI；
- 最小化复现步骤；
- Java 异常、logcat 和 native backtrace；
- 是否涉及内存破坏、任意代码执行、拒绝服务、数据暴露或缓存污染；
- 已知临时规避方式。

仓库公开后，维护者会同时启用 GitHub Private Vulnerability Reporting。普通功能问题仍使用公开 Issue。
