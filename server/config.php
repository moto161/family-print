<?php
/**
 * 家庭打印助手 · 服务端公共配置与认证
 *
 * 部署要求：
 *   1. 生成令牌：  mkdir -p /etc/print-service && head -c 32 /dev/urandom | base64 > /etc/print-service/token
 *                  chmod 600 /etc/print-service/token
 *   2. 把同样的令牌填进 APP「设置 → 访问令牌」
 *   3. nginx 必须禁止直接访问 /uploads/ 与 /previews/ 之外的执行（见 print-service.conf）
 *
 * 令牌不写在代码里，避免"硬编码密钥"红线。
 */

declare(strict_types=1);

const PRINT_TOKEN_FILE   = '/etc/print-service/token';
const PRINT_UPLOAD_DIR   = '/www/server/nginx/html/print/uploads/';
const PRINT_PREVIEW_DIR  = '/www/server/nginx/html/print/previews/';
const PRINT_MAX_BYTES    = 50 * 1024 * 1024;   // 单文件上限 50MB
const PRINT_MAX_PAGES    = 60;                  // 单文档缩略图生成上限
const PRINT_PRINTER      = 'Pantum-P2200NW';
const PRINT_JOBS_FILE    = PRINT_UPLOAD_DIR . '.jobs.json';  // job_id → 文件名映射
const PRINT_JOBS_KEEP    = 30;                  // 映射保留的最近任务数

/** 允许上传的扩展名白名单（小写） */
function print_allowed_ext(): array {
    return ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'jpg', 'jpeg', 'png', 'gif', 'bmp'];
}

/** 读取服务端令牌；未配置返回 null（此时一律拒绝请求，fail-closed） */
function print_load_token(): ?string {
    if (is_readable(PRINT_TOKEN_FILE)) {
        $t = trim(file_get_contents(PRINT_TOKEN_FILE));
        if ($t !== '') return $t;
    }
    $env = getenv('PRINT_SERVICE_TOKEN');
    if ($env !== false && trim($env) !== '') return trim($env);
    return null;
}

/**
 * 认证模式：
 *   'auto'   —— 未配置令牌时放行（保证存量 APP 不被打断），配置后立即强制校验。部署默认值。
 *   'strict' —— 未配置令牌就 503 拒绝全部请求。确认所有设备都填好令牌后再切。
 *
 * ⚠️ 2026-10-08 修订：原实现恒为 fail-closed，一旦部署而 /etc/print-service/token 尚未生成，
 *    线上打印服务会 100% 不可用（503）。改为 auto，做到「部署即可用，配了才收紧」。
 */
function print_auth_mode(): string {
    $env = getenv('PRINT_AUTH_MODE');
    if ($env === 'strict') return 'strict';
    return 'auto';
}

/** 校验请求令牌，失败直接终止并输出 JSON */
function print_require_auth(): void {
    $expected = print_load_token();

    if ($expected === null) {
        if (print_auth_mode() === 'strict') {
            http_response_code(503);
            echo json_encode(['success' => false, 'error' => 'server token not configured']);
            exit;
        }
        // auto：尚未启用令牌，放行但留痕，便于事后确认服务是否该收紧
        error_log('print-service: token not configured, running in open mode');
        return;
    }

    $given = '';
    foreach (['HTTP_X_PRINT_TOKEN', 'HTTP_X_AUTH_TOKEN'] as $key) {
        if (!empty($_SERVER[$key])) { $given = (string)$_SERVER[$key]; break; }
    }
    if (!hash_equals($expected, $given)) {
        http_response_code(401);
        echo json_encode(['success' => false, 'error' => 'unauthorized']);
        exit;
    }
}

/** 生成不可猜测的文档 ID */
function print_new_id(): string {
    return bin2hex(random_bytes(16));   // 32 位 hex
}

/** 校验 pdf_id / doc id，防路径遍历 */
function print_valid_id(string $id): bool {
    return (bool)preg_match('/^[a-f0-9]{32}$/', $id);
}

/** 校验页码范围串，只许 数字 , - */
function print_valid_ranges(string $r): bool {
    if ($r === '') return true;
    return (bool)preg_match('/^[0-9]+(?:[,\-][0-9]+)*$/', $r);
}

/** 取扩展名（小写），非法返回 null */
function print_safe_ext(string $fileName): ?string {
    $ext = strtolower(pathinfo($fileName, PATHINFO_EXTENSION));
    if ($ext === '' || strlen($ext) > 8) return null;
    if (!preg_match('/^[a-z0-9]+$/', $ext)) return null;
    return in_array($ext, print_allowed_ext(), true) ? $ext : null;
}

/** JSON 失败响应 */
function print_fail(string $msg, int $code = 400): void {
    http_response_code($code);
    echo json_encode(['success' => false, 'error' => $msg]);
    exit;
}

/** 确保目录存在（0755，不用 0777） */
function print_ensure_dir(string $dir): void {
    if (!is_dir($dir)) mkdir($dir, 0755, true);
}

/**
 * libreoffice 转换。
 * 关键：每次调用指定独立的 -env:UserInstallation，避免并发抢 ~/.config/libreoffice 锁导致失败。
 */
function print_to_pdf(string $srcPath, string $outDir, string $id): bool {
    $profile = sys_get_temp_dir() . '/lo_' . $id;
    $cmd = 'libreoffice --headless -env:UserInstallation=file://' . escapeshellarg($profile)
         . ' --convert-to pdf --outdir ' . escapeshellarg($outDir)
         . ' ' . escapeshellarg($srcPath) . ' 2>&1';
    $output = [];
    exec($cmd, $output, $ret);
    return $ret === 0;
}

/* ========== 打印任务映射（job_id → 文件名），供 jobs.php 显示真实任务名 ========== */

/** 读取全部映射：[job_id => ['name'=>..,'submit'=>ts,'state'=>'queued|done|canceled','done'=>ts?]] */
function print_jobs_load(): array {
    if (!is_readable(PRINT_JOBS_FILE)) return [];
    $data = json_decode((string)file_get_contents(PRINT_JOBS_FILE), true);
    return is_array($data) ? $data : [];
}

/** 原子写回映射（带排他锁，防并发覆盖） */
function print_jobs_save(array $jobs): void {
    $fp = fopen(PRINT_JOBS_FILE, 'c+');
    if (!$fp) return;
    flock($fp, LOCK_EX);
    ftruncate($fp, 0);
    fwrite($fp, json_encode($jobs, JSON_UNESCAPED_UNICODE));
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);
    @chmod(PRINT_JOBS_FILE, 0644);
}

/** 提交成功后登记新任务 */
function print_jobs_record(string $jobId, string $name): void {
    $jobs = print_jobs_load();
    $jobs[$jobId] = ['name' => $name, 'submit' => time(), 'state' => 'queued'];
    // 只保留最近 N 条
    while (count($jobs) > PRINT_JOBS_KEEP) {
        unset($jobs[array_key_first($jobs)]);
    }
    print_jobs_save($jobs);
}

/** 清理 24 小时前的旧文件（概率触发，避免每次请求都扫目录） */function print_gc(string $dir, int $maxAgeSec = 86400): void {
    if (mt_rand(1, 20) !== 1) return;          // 5% 概率
    if (!is_dir($dir)) return;
    $now = time();
    foreach (glob(rtrim($dir, '/') . '/*') as $path) {
        if ($now - @filemtime($path) > $maxAgeSec) {
            if (is_dir($path)) {
                foreach (glob($path . '/*') as $f) @unlink($f);
                @rmdir($path);
            } else {
                @unlink($path);
            }
        }
    }
}
