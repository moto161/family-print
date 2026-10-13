# 后端服务 API 协议（自建兼容服务参考）

家庭打印助手 Android 客户端通过一组 HTTP 接口与「自托管打印服务」协作。
本文件描述客户端实际依赖的接口契约，供你自行实现 / 部署兼容后端（仓库
`server/` 目录含一份 PHP 参考实现）。

> 所有接口默认挂载在同一站点根下，例如服务地址
> `http://192.0.2.100:8765/`，则：
> - 打印/提交：`http://192.0.2.100:8765/index.php`
> - 预览：`http://192.0.2.100:8765/preview.php`
> - 队列：`http://192.0.2.100:8765/jobs.php`
> - 改打印机 IP：`http://192.0.2.100:8765/update_printer.php`
>
> 仓库中的 `192.0.2.100` 为文档示例地址（RFC 5737），请替换为你自己的服务地址。

---

## 1. 认证

所有写操作与队列查询需携带令牌请求头（二者任一均可）：

```
X-Print-Token: <你的令牌>
X-Auth-Token:  <你的令牌>
```

- 服务端**未配置令牌**时（参考实现 `auto` 模式）放行，便于首次部署开箱即用；
  一旦配置令牌即强制校验，失败返回 `HTTP 401` + `{"success":false,"error":"unauthorized"}`。
- 建议生产环境配置令牌，并将 `PRINT_AUTH_MODE` 设为 `strict`。

---

## 2. 健康检查

`GET {base}/index.php?action=health`

用于 App「测试连接」探活。

响应（`200`）：

```json
{ "success": true, "service": "print", "time": 1696700000 }
```

若服务端已启用令牌且请求令牌错误，返回 `401` + `{"success":false,"error":"unauthorized"}`。

---

## 3. 预览（上传源文件 → 生成缩略图）

`POST {base}/preview.php` —— `multipart/form-data`

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | 文件 | 待打印源文件（PDF / Office / 图片），见扩展名白名单 |

响应（`200`）：

```json
{
  "success": true,
  "pages": 20,            // 文档真实总页数（pdfinfo 取得，不受缩略图上限影响）
  "previewPages": 12,     // 实际生成的缩略图数量（可能小于 pages）
  "pdf_id": "3f2a...",    // 32 位 hex，后续打印复用
  "previews": [           // 每页缩略图 URL，按请求 Host 动态拼接
    "http://192.0.2.100:8765/previews/3f2a/page-1.png",
    "http://192.0.2.100:8765/previews/3f2a/page-2.png"
  ]
}
```

> 客户端以 `pages` 作为可选页码上限；当 `previewPages < pages` 时，提示用户
> 「仅前 N 页支持预览」，超出的页码仍可提交打印（服务端按真实 PDF 处理）。

失败返回 `{"success":false,"error":"..."}`（如 `unsupported format`、`file too large`）。

---

## 4. 打印提交

`POST {base}/index.php`

两种模式（二选一）：

### 模式 A：直接上传源文件

`multipart/form-data`

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | 文件 | 源文件 |
| `page_ranges` | 文本 | 页码范围，如 `1-3,5`（仅数字/逗号/短横） |
| `orientation` | 文本 | `3`=纵向，`4`=横向（CUPS 约定） |
| `copies` | 文本 | 份数 `1`–`10` |
| `paper` | 文本 | `A4` / `A5` |
| `nup` | 文本 | 每页纸印几页 `1`/`2`/`4`（省纸） |
| `eco` | 文本 | `1`=省墨模式 |
| `duplex` | 文本 | `1`=手动双面 |
| `job_name` | 文本 | 队列显示名（原文件名），服务端会清洗控制字符 |

### 模式 B：复用预览已转换的 PDF

`application/x-www-form-urlencoded`

| 字段 | 类型 | 说明 |
|---|---|---|
| `pdf_id` | 文本 | 预览返回的 32 位 hex id（免二次上传） |
| 其余字段 | 文本 | 同模式 A（不含 `file`） |

响应（`200`）：

```json
{
  "success": true,
  "message": "打印任务已提交",
  "pages": "1-3,5",
  "orientation": 3,
  "paper": "A4",
  "eco": 0,
  "duplex": 0,
  "job_id": "Pantum-P2200NW-31",   // CUPS 任务 id，供取消/状态跟踪
  "pdf_id": "3f2a..."
}
```

失败：`{"success":false,"error":"..."}`（如 `invalid page_ranges`、`pdf_id not found`、`print queue error`）。

---

## 5. 打印队列

`GET {base}/jobs.php?action=list`

响应（`200`）：

```json
{
  "success": true,
  "jobs": [
    { "id": "Pantum-P2200NW-31", "state": "printing", "name": "报告.pdf", "size": 1024, "time": "..." }
  ],
  "recent": [
    { "id": "Pantum-P2200NW-30", "state": "done", "name": "身份证.pdf", "time": "2026-10-08 10:34" }
  ],
  "printer": "Pantum-P2200NW"
}
```

- `jobs[].state`：`printing`（队首）| `pending`（其余排队）
- `recent[].state`：`done` | `canceled`

取消任务：

`GET {base}/jobs.php?action=cancel&id=<job_id>`

响应：`{"success":true,"message":"任务已取消"}`

---

## 6. 更新打印机局域网 IP

`GET {base}/update_printer.php?ip=<ipv4>`

用于 App「设置 → 更新打印机 IP」。IP 需为合法 IPv4/IPv6（服务端严格校验，杜绝注入）。

响应（`200`）：

```json
{ "success": true, "ip": "192.168.1.100", "restarted": false }
```

`restarted` 表示是否重启了关联的隧道服务（取决于服务端 `PRINT_TUNNEL_SERVICE` 配置）。

---

## 7. 应用内更新（版本检查）

客户端定期检查一个 `version.json`（默认与 APK 同站点目录下，如 `{base}/apk/version.json` 或站点根），字段：

```json
{
  "versionCode": 84,
  "versionName": "1.84",
  "title": "家庭打印助手 v1.84",
  "content": "更新说明…",
  "url": "http://192.0.2.100:8761/apk/FamilyPrint_v1.84.apk",
  "sha256": "<apk 的 sha256>",
  "force": false
}
```

客户端安全链：下载 APK 后强制 **SHA-256 校验 + 同签名验证 + 可信域名白名单**，
三项全过才安装，防止更新包被篡改。

---

## 通用响应约定

- 成功：`{"success": true, ...}`
- 失败：`{"success": false, "error": "<可读错误>"}`
- 服务端不应把内部路径 / 命令细节回显给客户端（仅记 error_log）。

完整参考实现见仓库 `server/` 目录（PHP + CUPS + libreoffice + ImageMagick）。
