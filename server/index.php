<?php
/**
 * 家庭打印助手 · 提交打印
 *
 * 两种模式：
 *   1. 直接上传源文件打印
 *   2. 传 pdf_id 复用 preview.php 已转换好的 PDF
 *
 * 安全要点（2026-10-08 整改）：
 *   - 令牌认证（config.php::print_require_auth）
 *   - 扩展名先白名单校验、后落盘（杜绝 webshell 落盘）
 *   - pdf_id / page_ranges 严格校验（防路径遍历与参数注入）
 *   - 命令执行细节只写 error_log，不回显客户端
 */

declare(strict_types=1);
require_once __DIR__ . '/config.php';

header('Content-Type: application/json');

// 健康检查：GET ?action=health 返回 200，供 APP「测试连接」探活
// （2026-10-08 方法白名单后 index.php 拒绝 GET，旧版 APP 误报"阿里云接口连接失败"）
// 2026-10-08 补：请求若带了令牌则一并校验，让 APP 能在"测试连接"里顺带验证令牌是否正确
if ($_SERVER['REQUEST_METHOD'] === 'GET'
    && isset($_GET['action']) && $_GET['action'] === 'health') {
    $expected = print_load_token();
    if ($expected !== null) {
        $given = '';
        foreach (['HTTP_X_PRINT_TOKEN', 'HTTP_X_AUTH_TOKEN'] as $key) {
            if (!empty($_SERVER[$key])) { $given = (string)$_SERVER[$key]; break; }
        }
        if ($given !== '' && !hash_equals($expected, $given)) {
            http_response_code(401);
            echo json_encode(['success' => false, 'error' => 'unauthorized']);
            exit;
        }
    }
    echo json_encode(['success' => true, 'service' => 'print', 'time' => time()]);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    print_fail('method not allowed', 405);
}
print_require_auth();

print_ensure_dir(PRINT_UPLOAD_DIR);
print_gc(PRINT_UPLOAD_DIR);
print_gc(PRINT_PREVIEW_DIR);

$pageRanges  = isset($_POST['page_ranges']) ? trim((string)$_POST['page_ranges']) : '';
$pdfId       = isset($_POST['pdf_id']) ? trim((string)$_POST['pdf_id']) : '';
$orientation = isset($_POST['orientation']) ? intval($_POST['orientation']) : 3;
$copies      = isset($_POST['copies']) ? max(1, min(10, intval($_POST['copies']))) : 1;
// 纸张：客户端传 A4/A5，白名单校验后透传给 lp -o media=（2026-10-09 修复：此前该参数被忽略，选 A5 永远按默认纸打）
$paper       = isset($_POST['paper']) ? strtoupper(trim((string)$_POST['paper'])) : 'A4';
if (!in_array($paper, ['A4', 'A5'], true)) $paper = 'A4';
$nup         = isset($_POST['nup']) ? intval($_POST['nup']) : 1;
if (!in_array($nup, [1, 2, 4], true)) $nup = 1;   // N合一省纸：每页纸印1/2/4页
$eco         = isset($_POST['eco']) ? intval($_POST['eco']) : 0;
if (!in_array($eco, [0, 1], true)) $eco = 0;       // 省墨模式：lp -o TonerMode=True（Pantum 驱动原生省粉）
$duplex      = isset($_POST['duplex']) ? intval($_POST['duplex']) : 0;
if (!in_array($duplex, [0, 1], true)) $duplex = 0; // 手动双面：lp -o ManualDuplex=True（P2200NW 无自动双面，驱动层奇偶分页+翻纸提示）

// orientation 只允许 CUPS 的 3=纵向 / 4=横向
if (!in_array($orientation, [3, 4], true)) $orientation = 3;
if (!print_valid_ranges($pageRanges)) print_fail('invalid page_ranges');

// —— 模式 0：证件复印（身份证正反面合成一页 A4，2026-10-09）——
// 两张照片按身份证 85.6:54 比例等比缩放（2x@150dpi=1010x638），上下排列居中放到 A4 画布（1240x1754@150dpi）
if (isset($_FILES['id_front'], $_FILES['id_back'])) {
    $idImgs = ['id_front' => $_FILES['id_front'], 'id_back' => $_FILES['id_back']];
    $tmpPaths = [];
    foreach ($idImgs as $key => $img) {
        if (!is_uploaded_file($img['tmp_name'] ?? '')) print_fail('bad upload');
        if (($img['error'] ?? UPLOAD_ERR_OK) !== UPLOAD_ERR_OK) print_fail('upload error: ' . (int)$img['error']);
        if (($img['size'] ?? 0) > PRINT_MAX_BYTES) print_fail('file too large (max 50MB)', 413);
        $ext = print_safe_ext((string)$img['name']);
        if ($ext === null || !in_array($ext, ['jpg', 'jpeg', 'png'], true)) print_fail('id photos must be jpg/png');
        $tmp = PRINT_UPLOAD_DIR . 'idtmp_' . bin2hex(random_bytes(8)) . '.' . $ext;
        if (!move_uploaded_file($img['tmp_name'], $tmp)) print_fail('save failed', 500);
        $tmpPaths[$key] = $tmp;
    }

    $newId = print_new_id();
    $outPdf = PRINT_UPLOAD_DIR . $newId . '.pdf';
    $side = '-auto-orient -resize 1010x638 -gravity center -extent 1010x638 -bordercolor white -border 0x30';
    // 两步合成（实测：IM 直接一步写 PDF 页面会被转成横向 A4）：
    // ① 正反面等比缩放到身份证 2x@150dpi(1010x638)，上下排列放到 A4@150dpi 画布(1240x1754)
    // ② 显式 -page 1240x1754+0+0 + density 150x150 写 PDF → 页面正好 210x297mm 纵向 A4
    $pageImg = PRINT_UPLOAD_DIR . 'idtmp_page_' . $newId . '.png';
    $cmd = 'convert'
        . ' \( ' . escapeshellarg($tmpPaths['id_front']) . ' ' . $side . ' \)'
        . ' \( ' . escapeshellarg($tmpPaths['id_back']) . ' ' . $side . ' \)'
        . ' -background white -gravity center -append -extent 1240x1754 '
        . escapeshellarg($pageImg) . ' 2>&1';
    $output = [];
    exec($cmd, $output, $ret);
    if ($ret === 0 && is_file($pageImg) && filesize($pageImg) > 0) {
        $cmd = 'convert ' . escapeshellarg($pageImg)
            . ' -units PixelsPerInch -density 150x150 -page 1240x1754+0+0 '
            . escapeshellarg($outPdf) . ' 2>&1';
        $output = [];
        exec($cmd, $output, $ret);
    }
    @unlink($pageImg);
    foreach ($tmpPaths as $tmp) @unlink($tmp);
    if (!is_file($outPdf) || filesize($outPdf) === 0) {
        error_log('[print] idcopy convert failed, out=' . implode(' | ', $output));
        @unlink($outPdf);
        print_fail('id copy convert failed', 500);
    }

    // 提交 CUPS（证件复印固定 A4 纵向，不支持 page-ranges/nup）
    $cmd = 'lp -d ' . escapeshellarg(PRINT_PRINTER)
        . ' -o orientation-requested=3 -o media=A4';
    if ($eco === 1) $cmd .= ' -o TonerMode=True';
    if ($duplex === 1) $cmd .= ' -o ManualDuplex=True';
    $cmd .= ' -n ' . $copies
        . ' ' . escapeshellarg($outPdf) . ' 2>&1';
    $output = [];
    exec($cmd, $output, $ret);

    $jobId = null;
    if ($ret === 0 && preg_match('/request id is\s+([A-Za-z0-9._-]+-\d+)/', implode("\n", $output), $m)) {
        $jobId = $m[1];
        print_jobs_record($jobId, '身份证复印.pdf');
    }
    if ($ret === 0) {
        echo json_encode([
            'success' => true,
            'message' => '证件复印任务已提交',
            'job_id'  => $jobId,
            'pdf_id'  => $newId,
            'eco'     => $eco,
            'duplex'  => $duplex,
        ]);
    } else {
        error_log('[print] idcopy lp failed ret=' . $ret . ' out=' . implode(' | ', $output));
        print_fail('print queue error', 500);
    }
    exit;
}

$pdfPath  = null;
$newId    = null;

if ($pdfId !== '') {
    // —— 模式 2：复用已转换的 PDF ——
    if (!print_valid_id($pdfId)) print_fail('invalid pdf_id');
    $candidate = PRINT_UPLOAD_DIR . $pdfId . '.pdf';
    if (!is_file($candidate)) print_fail('pdf_id not found', 404);
    $pdfPath = $candidate;
} elseif (isset($_FILES['file'])) {
    // —— 模式 1：上传源文件 ——
    $file = $_FILES['file'];
    if (!is_uploaded_file($file['tmp_name'] ?? '')) print_fail('bad upload');
    if (($file['error'] ?? UPLOAD_ERR_OK) !== UPLOAD_ERR_OK) print_fail('upload error: ' . (int)$file['error']);
    if (($file['size'] ?? 0) > PRINT_MAX_BYTES) print_fail('file too large (max 50MB)', 413);

    // ① 先校验扩展名，校验不过绝不落盘
    $ext = print_safe_ext((string)$file['name']);
    if ($ext === null) print_fail('unsupported format');

    // ② 校验通过后才写盘
    $newId   = print_new_id();
    $srcPath = PRINT_UPLOAD_DIR . $newId . '.' . $ext;
    $outPdf  = PRINT_UPLOAD_DIR . $newId . '.pdf';
    if (!move_uploaded_file($file['tmp_name'], $srcPath)) print_fail('save failed', 500);

    // ③ 转 PDF
    if ($ext === 'pdf') {
        copy($srcPath, $outPdf);
    } elseif (in_array($ext, ['jpg', 'jpeg', 'png', 'gif', 'bmp'], true)) {
        $rotate = ($orientation === 4) ? ' -rotate 90' : '';
        $output = [];
        // -auto-orient：按 EXIF 方向摆正（手机竖拍照片 EXIF 常为 6/8，缺它打印出来横倒）
        $cmd = 'convert -auto-orient' . $rotate . ' -page ' . $paper . ' ' . escapeshellarg($srcPath) . ' ' . escapeshellarg($outPdf) . ' 2>&1';
        exec($cmd, $output, $ret);
        if (!is_file($outPdf) || filesize($outPdf) === 0) {
            @unlink($outPdf);                       // 清掉 0 字节残骸再兜底
            print_to_pdf($srcPath, PRINT_UPLOAD_DIR, $newId);
        }
        // ④ 最终校验：兜底也失败就明确报错，绝不能把 0 字节 PDF 丢给 lp
        if (!is_file($outPdf) || filesize($outPdf) === 0) {
            error_log('[print] image->pdf failed, id=' . $newId . ' out=' . implode(' | ', $output));
            @unlink($srcPath);                       // v1.79：失败清源文件，不留 24h 垃圾
            print_fail('image convert failed', 500);
        }
    } else {
        if (!print_to_pdf($srcPath, PRINT_UPLOAD_DIR, $newId)) {
            error_log('[print] office->pdf failed, id=' . $newId);
            @unlink($srcPath);
            print_fail('convert failed', 500);
        }
        if (!is_file($outPdf) || filesize($outPdf) === 0) {
            error_log('[print] office->pdf produced empty file, id=' . $newId);
            @unlink($srcPath);
            print_fail('convert failed', 500);
        }
    }
    $pdfPath = $outPdf;
} else {
    print_fail('no file or pdf_id');
}

// —— 队列显示名：客户端可传 job_name（原文件名），清洗后用于 lp -t 与队列映射
//    （历史行为：pdf_id 模式落 32 位 hash 名，队列页可读性差，2026-10-09 v1.82 修复）
$jobName = '';
if (isset($_POST['job_name'])) {
    $jobName = trim((string)$_POST['job_name']);
    $jobName = preg_replace('/[ -]/', '', $jobName);   // 去控制字符
    if (function_exists('mb_substr')) $jobName = mb_substr($jobName, 0, 80, 'UTF-8');
    else $jobName = substr($jobName, 0, 80);
}
if ($jobName === '') {
    $jobName = $pdfId !== '' ? ($newId ?? $pdfId) . '.pdf'
             : (isset($file['name']) ? (string)$file['name'] : 'document');
}

// —— 提交到 CUPS ——
$cmd = 'lp -d ' . escapeshellarg(PRINT_PRINTER);
$cmd .= ' -t ' . escapeshellarg($jobName);
if ($pageRanges !== '') {
    $cmd .= ' -o page-ranges=' . escapeshellarg($pageRanges);
}
$cmd .= ' -o orientation-requested=' . $orientation;
$cmd .= ' -o media=' . $paper;
if ($nup > 1) {
    $cmd .= ' -o number-up=' . $nup;
}
if ($eco === 1) {
    $cmd .= ' -o TonerMode=True';
}
if ($duplex === 1) {
    $cmd .= ' -o ManualDuplex=True';
}
$cmd .= ' -n ' . $copies;
$cmd .= ' ' . escapeshellarg($pdfPath) . ' 2>&1';

$output = [];
exec($cmd, $output, $ret);

// 解析 job id（lp 输出形如 "request id is Pantum-P2200NW-31 (1 file(s))"），供队列状态跟踪
$jobId = null;
if ($ret === 0 && preg_match('/request id is\s+([A-Za-z0-9._-]+-\d+)/', implode("\n", $output), $m)) {
    $jobId = $m[1];
    // 登记任务名映射（与 lp -t 同名，队列页显示可读文件名）
    print_jobs_record($jobId, $jobName);
}

if ($ret === 0) {
    echo json_encode([
        'success'     => true,
        'message'     => '打印任务已提交',
        'pages'       => $pageRanges !== '' ? $pageRanges : '全部',
        'orientation' => $orientation,
        'paper'       => $paper,
        'eco'         => $eco,
        'duplex'      => $duplex,
        'job_id'      => $jobId,
        'pdf_id'      => $pdfId !== '' ? $pdfId : $newId,
    ]);
} else {
    // 命令细节只进服务端日志，客户端拿通用错误码，避免泄露路径/队列配置
    error_log('[print] lp failed ret=' . $ret . ' cmd=' . $cmd . ' out=' . implode(' | ', $output));
    print_fail('print queue error', 500);
}
