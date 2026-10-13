<?php
/**
 * 家庭打印助手 · 预览转图
 *
 * 流程：源文件 → PDF → pdfinfo 取页数 → pdftoppm 生成 80dpi 缩略图
 * 返回 pdf_id + 每页缩略图 URL，客户端据此选页、设份数，再用 pdf_id 调 index.php 打印（避免二次上传）
 *
 * 安全要点（2026-10-08 整改）：
 *   - 令牌认证
 *   - 扩展名先白名单校验、后落盘
 *   - 单次最多生成 PRINT_MAX_PAGES 张缩略图（防大文档把 PHP 拖到超时 502）
 *   - 预览 URL 按请求 Host 动态拼接（改服务器地址后预览图不会指向旧 IP）
 */

declare(strict_types=1);
require_once __DIR__ . '/config.php';

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    print_fail('method not allowed', 405);
}
print_require_auth();

print_ensure_dir(PRINT_UPLOAD_DIR);
print_ensure_dir(PRINT_PREVIEW_DIR);
print_gc(PRINT_UPLOAD_DIR);
print_gc(PRINT_PREVIEW_DIR);

if (!isset($_FILES['file'])) print_fail('no file');

$file = $_FILES['file'];
if (!is_uploaded_file($file['tmp_name'] ?? '')) print_fail('bad upload');
if (($file['error'] ?? UPLOAD_ERR_OK) !== UPLOAD_ERR_OK) print_fail('upload error: ' . (int)$file['error']);
if (($file['size'] ?? 0) > PRINT_MAX_BYTES) print_fail('file too large (max 50MB)', 413);

// ① 先校验扩展名
$ext = print_safe_ext((string)$file['name']);
if ($ext === null) print_fail('unsupported format');

// ② 校验通过后才落盘
$id      = print_new_id();
$srcPath = PRINT_UPLOAD_DIR . $id . '.' . $ext;
$pdfPath = PRINT_UPLOAD_DIR . $id . '.pdf';
$pageDir = PRINT_PREVIEW_DIR . $id . '/';

print_ensure_dir($pageDir);
if (!move_uploaded_file($file['tmp_name'], $srcPath)) print_fail('save failed', 500);

// —— 转 PDF ——
if ($ext === 'pdf') {
    copy($srcPath, $pdfPath);
} elseif (in_array($ext, ['jpg', 'jpeg', 'png', 'gif', 'bmp'], true)) {
    $output = [];
    $cmd = 'convert ' . escapeshellarg($srcPath) . ' ' . escapeshellarg($pdfPath) . ' 2>&1';
    exec($cmd, $output, $ret);
    if (!is_file($pdfPath) || filesize($pdfPath) === 0) {
        @unlink($pdfPath);
        print_to_pdf($srcPath, PRINT_UPLOAD_DIR, $id);
    }
    if (!is_file($pdfPath) || filesize($pdfPath) === 0) {
        error_log('[preview] image->pdf failed, id=' . $id . ' out=' . implode(' | ', $output));
        @unlink($srcPath); @rmdir($pageDir);         // v1.79：失败清源文件与空预览目录
        print_fail('image convert failed', 500);
    }
} else {
    if (!print_to_pdf($srcPath, PRINT_UPLOAD_DIR, $id)) {
        error_log('[preview] office->pdf failed, id=' . $id);
        @unlink($srcPath); @rmdir($pageDir);
        print_fail('convert failed', 500);
    }
    if (!is_file($pdfPath) || filesize($pdfPath) === 0) {
        error_log('[preview] office->pdf produced empty file, id=' . $id);
        @unlink($srcPath); @rmdir($pageDir);
        print_fail('convert failed', 500);
    }
}

// —— 取真实总页数（不受缩略图上限影响）——
$pages = 0;
$output = [];                                    // exec 是追加，必须先清空
exec('pdfinfo ' . escapeshellarg($pdfPath) . ' 2>&1 | grep Pages', $output);
foreach ($output as $line) {
    if (preg_match('/Pages:\s+(\d+)/', $line, $m)) {
        $pages = intval($m[1]);
    }
}

// —— 生成缩略图，最多 PRINT_MAX_PAGES 页 ——
$limit = PRINT_MAX_PAGES;
$cmd = 'pdftoppm -png -r 80 -f 1 -l ' . $limit . ' '
     . escapeshellarg($pdfPath) . ' ' . escapeshellarg($pageDir . 'page') . ' 2>&1';
$output = [];
exec($cmd, $output, $ret);

// —— 收集预览图 URL（按请求 Host 动态拼，不再硬编码 IP）——
$scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
$host   = $_SERVER['HTTP_HOST'] ?? '127.0.0.1:8765';
$base   = $scheme . '://' . $host . '/previews/' . $id . '/';

$previews = [];
$files = glob($pageDir . 'page-*.png');
if ($files) {
    natsort($files);                             // page-2 排在 page-10 前
    foreach ($files as $f) {
        $previews[] = $base . basename($f);
    }
}

// 真实页数优先取 pdfinfo；若 pdftoppm 失败了再用实际生成数兜底
if ($pages <= 0) $pages = count($previews);

echo json_encode([
    'success'      => true,
    'pages'        => $pages,
    'previewPages' => count($previews),          // 客户端据此提示"仅前 N 页可预览"
    'pdf_id'       => $id,
    'previews'     => $previews,
]);
