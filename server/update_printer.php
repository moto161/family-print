<?php
/**
 * 家庭打印助手 · 更新打印机局域网 IP
 *
 * 客户端「设置 → 更新打印机IP」调用：GET /update_printer.php?ip=192.168.1.100
 *
 * 安全要点（2026-10-08 整改）：
 *   - 令牌认证（原版本无任何认证，任何人可改你家打印机指向）
 *   - IP 必须过 filter_var 严格校验，绝不拼进 shell
 *   - 只落配置文件 + 重启 systemd 服务，不直接 exec 拼字符串
 *
 * 依赖（部署侧）：
 *    soccer/socket 隧道若由 systemd 管理，服务名写在 PRINT_TUNNEL_SERVICE；
 *    未配置服务名时只写 IP 文件，由隧道侧的 watcher 自行加载。
 */

declare(strict_types=1);
require_once __DIR__ . '/config.php';

header('Content-Type: application/json');

print_require_auth();

$ip = isset($_GET['ip']) ? trim((string)$_GET['ip']) : '';
if ($ip === '') print_fail('missing ip');

// 严格校验：只接受合法 IPv4/IPv6，杜绝参数注入与命令注入
if (filter_var($ip, FILTER_VALIDATE_IP) === false) {
    print_fail('invalid ip');
}

$ipFile = '/etc/print-service/printer_ip';
$dirOk  = is_dir(dirname($ipFile)) || mkdir(dirname($ipFile), 0755, true);
if (!$dirOk || file_put_contents($ipFile, $ip . "\n") === false) {
    error_log('[update_printer] write failed: ' . $ipFile);
    print_fail('write failed', 500);
}

// 隧道服务重启（可选）。服务名留空则跳过，只更新配置。
$service = getenv('PRINT_TUNNEL_SERVICE') ?: '';
$restarted = false;
if ($service !== '' && preg_match('/^[a-zA-Z0-9_\-\.]+$/', $service)) {
    $output = [];
    $cmd = 'systemctl restart ' . escapeshellarg($service) . ' 2>&1';
    exec($cmd, $output, $ret);
    $restarted = ($ret === 0);
    if (!$restarted) {
        error_log('[update_printer] restart failed service=' . $service . ' out=' . implode(' | ', $output));
    }
}

echo json_encode([
    'success'   => true,
    'ip'        => $ip,
    'restarted' => $restarted,
]);
