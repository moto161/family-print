# 家庭打印助手 · 服务端归档 (server/)

> 归档时间：2026-10-04
> 来源：示例服务器 `192.0.2.100` 上的 nginx 站点（宝塔面板管理），nginx 监听 **端口 8765**
> 站点根目录：`/www/server/nginx/html/print`
> nginx 配置：`/www/server/panel/vhost/nginx/print-service.conf`
> PHP 运行方式：FastCGI，socket `unix:/tmp/php-cgi-82.sock`（宝塔 PHP 8.2）
> 归档方式：本地 scp 被沙箱拦截，改用 `ssh … cat` 重定向拉取 PHP 源码；previews/uploads 用 tar 管道归档。

---

## 1. 部署拓扑

```
Android 客户端 (FamilyPrint)
   │  HTTP POST
   ▼
http://192.0.2.100:8765/index.php      ← 打印
http://192.0.2.100:8765/preview.php    ← 预览转图
   │
   ▼
nginx :8765  →  php-cgi-82.sock
                   ├─ libreoffice --headless --convert-to pdf   (doc/xls/ppt → pdf)
                   ├─ convert / pdftoppm                          (图片→pdf / pdf→png缩略图)
                   └─ lp -d Pantum-P2200NW                       (提交到 CUPS 打印队列)
                          │
                          ▼
                  打印机 Pantum-P2200NW  (CUPS 队列名；客户端"打印机局域网IP"填 192.168.1.100)
```

## 2. 接口契约

### POST `/index.php` —— 提交打印
字段（multipart/form-data 或 application/x-www-form-urlencoded）：
| 字段 | 类型 | 说明 |
|------|------|------|
| `file` | 文件 | 直接上传源文件（pdf/doc/docx/xls/xlsx/ppt/pptx/jpg/jpeg/png/gif/bmp） |
| `pdf_id` | 字符串 | 或传 preview.php 返回的 `pdf_id`，复用已生成的 PDF（二选一） |
| `page_ranges` | 字符串 | 如 `1-3,5`，空=全部 |
| `orientation` | int | `3`=纵向(默认)，`4`=横向 |
| `copies` | int | `1`–`10` 份，默认 1 |

返回 JSON：
```json
{ "success": true,  "message": "打印任务已提交", "pages": "全部", "orientation": 3 }
{ "success": false, "error": "..." }
```
后端命令：`lp -d Pantum-P2200NW -o page-ranges=... -o orientation-requested=N -n <copies> <pdf>`

### POST `/preview.php` —— 预览并取页数
字段：`file`（同上格式）。
流程：源文件 → PDF → `pdfinfo` 取页数 → `pdftoppm -png -r 80` 生成 80dpi 缩略图到 `previews/{id}/page-*.png`。
返回 JSON：
```json
{
  "success": true,
  "pages": 3,
  "pdf_id": "57f3a1...",
  "previews": [
    "http://192.0.2.100:8765/previews/57f3a1.../page-1.png",
    "http://192.0.2.100:8765/previews/57f3a1.../page-2.png",
    "http://192.0.2.100:8765/previews/57f3a1.../page-3.png"
  ]
}
```
客户端先用 preview 拿到 `pdf_id` + 每页缩略图，用户选页/设份数后，再用 `pdf_id` 调 index.php 打印（避免二次上传）。

### `test.php` —— 健康检查（44 字节）
### `index.php.bak_v1` —— 上一代 index.php 备份

## 3. 运行时数据目录（本归档同步收录）
- `uploads/` —— 每次上传/转换的源文件与中间 PDF（含 `*.pdf` / `*.docx` 等），约 31M
- `previews/` —— 各文档每页 80dpi 缩略图，按 `pdf_id` 分子目录，约 39M
- 注意：站点根下还有一批历史 `FamilyPrint*.apk` 与第三方 `pantum_gadget2.apk`（230M），属发布物/第三方，**未收录**进本归档（本地 `E:\家庭打印机\` 已有 v1.57–v1.60）。

## 4. 还原/迁移要点
1. 把本目录 PHP 文件放到目标 nginx 站点 root，确保 `previews/`、`uploads/` 可写（777）。
2. 安装依赖：`libreoffice`（headless）、`imagemagick`（`convert`）、`poppler-utils`（`pdfinfo`/`pdftoppm`）、`cups`（`lp`），并建好打印机队列 `Pantum-P2200NW`。
3. nginx 配置参考 `print-service.conf`：`client_max_body_size 100m`，PHP 走 `php-cgi-82.sock`（按实际 PHP 版本改 socket 路径）。
