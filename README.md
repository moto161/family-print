# 家庭打印助手 FamilyPrint

一款面向家庭场景的 Android 打印工具：手机选图/选文档 → 自托管打印服务 → 打印机出纸。支持网络打印、IPP 直连、打印历史与多主题 UI。

> **当前版本：v1.84**（versionCode 84）

## 功能特性

- 📄 **文档打印**：支持 PDF / 图片，服务端逐页生成缩略预览，勾选页码、设置方向与份数后提交打印
- 🔌 **IPP 直连**：内置 IPP 客户端，可绕过打印服务直连局域网打印机
- 🖨️ **网络打印**：与自托管打印服务（HTTP API）配合，支持隧道访问
- 🕘 **打印历史**：本地记录打印任务，支持一键重打
- 🌙 **多主题**：暗色 / 现代 / 专业 / 暖色 四套皮肤
- 🧭 **底部导航**：首页 / 历史 / 日志 / 设置
- 🔄 **应用内更新**：版本检查 + SHA-256 校验 + 可信域名白名单，防止更新包被篡改

## 项目结构

```
family-print/
├── app/src/main/java/com/family/print/   # Java 源码（10 个类）
│   ├── MainActivity.java        # 主界面：文件选择、预览、打印队列
│   ├── PrintApiClient.java      # 网络层：multipart 上传、认证、错误处理
│   ├── IppDirectClient.java     # IPP 直连客户端
│   ├── ImageViewerActivity.java # 页面预览放大查看
│   ├── HistoryActivity.java     # 打印历史与重打
│   ├── SettingsActivity.java    # 设置：服务地址、令牌、主题、扫码导入
│   └── ...
├── app/src/main/res/                      # 资源（layout / drawable / values）
└── build.gradle                           # AGP 8.2 / compileSdk 33 / JDK 17
```

> **服务端说明**：本仓库仅包含 Android 客户端。配套的打印服务（HTTP API + IPP 桥接）需自行部署，客户端内通过「设置 → 服务地址 / 访问令牌」配置。

## 构建要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 8.2.2 |
| compileSdk | 33 |
| 最低支持 | minSdk 见 `app/build.gradle` |

```bash
# 命令行构建（Debug 包）
./gradlew assembleDebug

# 产物路径
app/build/outputs/apk/debug/app-debug.apk
```

> 若项目路径包含非 ASCII 字符（如中文），需在 `gradle.properties` 中保留 `android.overridePathCheck=true`（本仓库已配置）。

## 配置说明

首次使用请在 App「设置」页配置：

1. **服务地址**：自托管打印服务的 URL（如 `http://192.0.2.100:8765/index.php`）
2. **访问令牌**：与服务端 `X-Print-Token` 一致，用于接口鉴权
3. **打印机 IP**：局域网直连模式下的打印机地址

仓库中的 `192.0.2.100` / `192.168.1.100` 均为文档示例地址（RFC 5737 / RFC 1918 常见示例），请替换为你自己的服务地址。

## 安全设计

- 更新包强制 **SHA-256 校验 + 同签名验证 + 可信域名白名单**
- 服务端接口走 `X-Print-Token` 令牌鉴权
- FileProvider 权限收窄，EXIF 隐私信息处理

## 参与贡献

欢迎 Issue 与 PR！请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE)
