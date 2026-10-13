# 贡献指南（CONTRIBUTING）

感谢关注「家庭打印助手 FamilyPrint」！提交 Issue 或 PR 前，请先阅读本指南。

## 提交 Issue

- **Bug 报告**请使用 [.github/ISSUE_TEMPLATE/bug_report.md](.github/ISSUE_TEMPLATE/bug_report.md) 模板，包含：
  - 复现步骤（越具体越好）
  - 期望行为 vs 实际行为
  - 设备型号、Android 版本、App 版本号
  - 相关日志（可在 App「日志」页导出；**请务必脱敏，移除令牌、IP 等隐私信息**）
- **功能建议**请使用 [.github/ISSUE_TEMPLATE/feature_request.md](.github/ISSUE_TEMPLATE/feature_request.md)。
- 提交前请先搜索是否已有相同 Issue，避免重复。

## 提交 Pull Request

1. **先建 Issue 讨论**：较大的改动请先开 Issue 描述方案，确认方向后再动手，避免白做。
2. **分支命名**：从 `main` 拉出功能分支，命名格式：
   - 功能：`feature/简短描述`（如 `feature/ipp-status-check`）
   - 修复：`fix/简短描述`（如 `fix/orientation-index-overflow`）
3. **提交信息（Commit Message）**：采用 `类型: 摘要` 格式，类型限定为：
   - `feat` 新功能 / `fix` 缺陷修复 / `refactor` 重构 / `docs` 文档 / `build` 构建配置 / `chore` 杂项
   - 示例：`fix: 修复大文档打印页码越界崩溃`
   - 一次提交做一件事，多个逻辑改动请分开提交。
4. **PR 描述**：请使用 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)，关联对应 Issue（修复类请用 `Closes #编号` 语法）。
5. **验证要求**：PR 必须编译通过（`./gradlew assembleDebug`），涉及行为改动的请说明自测情况。

## 代码规范

- Java 代码遵循项目现有风格（4 空格缩进）。
- 新增资源（layout/drawable）命名与现有 `snake_case` 风格保持一致。
- **安全红线**：
  - 不得硬编码任何密钥、令牌、真实服务器地址或签名文件；
  - 不得提交 `local.properties`、`*.keystore`、`*.jks`（已在 `.gitignore` 中排除）；
  - 涉及网络请求的改动必须考虑令牌鉴权与错误处理。

## 安全问题

发现安全漏洞请**不要**直接开公开 Issue，请通过 Issues 私下联系维护者（或邮件说明），确认修复后再披露。
