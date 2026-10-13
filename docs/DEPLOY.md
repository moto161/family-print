# 后端服务部署指南

本指南说明如何部署「家庭打印助手」配套的打印服务（参考实现位于仓库 `server/`
目录，PHP 编写）。部署完成后，在 App「设置」中填入本服务的地址与令牌即可使用。

> 文档中的路径与地址（如 `/www/server/nginx/html/print`、`192.0.2.100`、
> `Pantum-P2200NW`）均为示例，请替换为你的实际环境。

---

## 1. 运行依赖

| 组件 | 用途 | 备注 |
|---|---|---|
| PHP 8.x（含 `json` 扩展） | 服务主程序 | 参考实现用 PHP |
| nginx + PHP-FPM | Web 服务 | `fastcgi_pass` 指向 php-fpm socket |
| CUPS | 实际打印队列 | 提供 `lp` / `lpstat` / `cancel` 命令 |
| LibreOffice | Office/文档 → PDF 转换 | `libreoffice --headless --convert-to pdf` |
| ImageMagick | 图片 → PDF、证件复印合成 | `convert` 命令 |
| Poppler Utils | PDF 页数 / 缩略图 | `pdfinfo` / `pdftoppm` |

确保上述命令行工具在 `PATH` 中可被 PHP 的 `exec()` 调用。

---

## 2. 目录布局

```
/www/server/nginx/html/print/      # 站点根（nginx root）
├── index.php                       # 打印提交
├── preview.php                     # 预览转图
├── jobs.php                        # 队列状态 / 取消
├── update_printer.php              # 更新打印机 IP
├── config.php                      # 公共配置与认证（参考实现）
├── previews/                       # 缩略图（nginx 禁止 PHP 解析，仅静态）
└── uploads/                        # 上传文件（nginx 禁止任何访问，防 RCE）

/etc/print-service/
├── token                           # 令牌文件（600，不入库、不进代码）
└── printer_ip                      # 打印机 IP（update_printer.php 写入）
```

> **安全要点**：`uploads/` 与 `previews/` 必须通过 nginx 配置禁止 PHP 解析与外部访问
> （参见下方 nginx 配置）。否则上传 `shell.php` 即可远程执行。

---

## 3. 生成令牌

```bash
mkdir -p /etc/print-service
head -c 32 /dev/urandom | base64 > /etc/print-service/token
chmod 600 /etc/print-service/token
```

将 **同样的令牌** 填入 App「设置 → 访问令牌」。

也可改用环境变量（不落文件）：

```bash
export PRINT_SERVICE_TOKEN="你的令牌"
export PRINT_AUTH_MODE="strict"      # 配好所有设备后改为 strict，强制校验
```

---

## 4. nginx 配置示例

参考 `server/print-service.conf`：

```nginx
server {
    listen 8765;
    server_name _;
    root /www/server/nginx/html/print;
    client_max_body_size 100m;

    # 上传目录：禁止任何 HTTP 访问（防 webshell 执行）
    location ^~ /uploads/ { deny all; return 404; }

    # 仅允许 GET/POST
    if ($request_method !~ ^(GET|POST)$) { return 405; }

    # 缩略图：仅静态，禁止 PHP 解析
    location ^~ /previews/ {
        location ~ \.php$ { deny all; }
        try_files $uri =404;
    }

    location / {
        try_files $uri $uri/ =404;
    }

    location ~ \.php$ {
        try_files $uri =404;
        fastcgi_pass unix:/tmp/php-cgi-82.sock;
        fastcgi_index index.php;
        include fastcgi.conf;
    }

    # 版本更新 APK 目录（若同站点）：同样禁 PHP 解析
    location ^~ /apk/ {
        location ~ \.php$ { deny all; }
        try_files $uri =404;
    }
}
```

可选：若你有固定出口 IP，在 `server` 块内加 IP 白名单多一层保险：

```nginx
# allow 203.0.113.10;   # 换成你的出口 IP
# allow 127.0.0.1;
# deny  all;
```

---

## 5. 关键配置项（config.php）

| 常量 / 环境变量 | 说明 | 默认值 |
|---|---|---|
| `PRINT_TOKEN_FILE` | 令牌文件路径 | `/etc/print-service/token` |
| `PRINT_UPLOAD_DIR` | 上传目录 | `/www/server/nginx/html/print/uploads/` |
| `PRINT_PREVIEW_DIR` | 缩略图目录 | `/www/server/nginx/html/print/previews/` |
| `PRINT_MAX_BYTES` | 单文件上限 | 50 MB |
| `PRINT_MAX_PAGES` | 缩略图生成上限 | 60 页 |
| `PRINT_PRINTER` | CUPS 打印机名 | `Pantum-P2200NW` |
| `PRINT_AUTH_MODE` | `auto` / `strict` | `auto`（未配令牌即放行） |
| `PRINT_TUNNEL_SERVICE` | 改打印机 IP 后重启的 systemd 服务名 | 空（仅写文件） |

- 打印机名用 `lpstat -p` 查看。
- 局域网直连打印机需支持 IPP（端口 631）；App 内「IPP 直连」模式可绕过本服务直连打印机。

---

## 6. 应用内更新（可选）

若要让 App 支持「应用内更新」，在同站点放一个 `version.json`（字段见
[API.md](API.md) 第 7 节），并在 `url` 指向你的 APK 下载地址。更新包需：
**SHA-256 一致 + 与已装 App 同签名 + 域名在白名单内**，三项全过才安装。

---

## 7. HTTPS（强烈建议）

公网部署务必启用 HTTPS（如 Let's Encrypt），并在 App「设置」中填 `https://` 地址，
避免 `X-Print-Token` 与打印内容在明文 HTTP 下被同网嗅探。

---

## 8. 排错

| 现象 | 排查 |
|---|---|
| App 提示「连接失败」 | 先访问 `GET /index.php?action=health` 看是否 `200` |
| 401 unauthorized | 令牌不一致；确认 `/etc/print-service/token` 与 App 填写相同 |
| 上传后无缩略图 | 检查 `libreoffice` / `pdftoppm` 是否可用，`uploads/` 可写 |
| 提交后不打印 | `lpstat -p` 确认打印机在线；查 CUPS 队列 |
| 改打印机 IP 无效 | 确认 `update_printer.php` 可写 `/etc/print-service/printer_ip`，隧道服务是否重启 |
