<?php
/**
 * 家庭打印助手 · 打印队列状态
 *
 * GET ?action=list          列出未完成任务（队首=打印中，其余=排队中）+ 最近已完成
 * GET ?action=cancel&id=    取消任务（仅能取消 www 用户自己提交的任务）
 *
 * 认证与 index.php 相同（X-Print-Token）。任务名来自 index.php 提交时登记的
 * uploads/.jobs.json 映射（job_id → 原文件名）。
 */

declare(strict_types=1);
require_once __DIR__ . '/config.php';

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    print_fail('method not allowed', 405);
}
print_require_auth();

$action = isset($_GET['action']) ? (string)$_GET['action'] : '';

/* ---------- 取消任务 ---------- */
if ($action === 'cancel') {
    $id = isset($_GET['id']) ? trim((string)$_GET['id']) : '';
    // 只允许 打印机名-数字 形式的 job id，杜绝参数注入
    if (!preg_match('/^[A-Za-z0-9._-]+-\d+$/', $id)) print_fail('invalid job id');

    $cmd = 'cancel ' . escapeshellarg($id) . ' 2>&1';
    $output = [];
    exec($cmd, $output, $ret);
    if ($ret !== 0) {
        error_log('[print] cancel failed ret=' . $ret . ' id=' . $id . ' out=' . implode(' | ', $output));
        print_fail('cancel failed (may already be done)', 500);
    }
    // 映射里标记已取消
    $jobs = print_jobs_load();
    if (isset($jobs[$id])) {
        $jobs[$id]['state'] = 'canceled';
        $jobs[$id]['done'] = time();
        print_jobs_save($jobs);
    }
    echo json_encode(['success' => true, 'message' => '任务已取消']);
    exit;
}

/* ---------- 列出任务 ---------- */
if ($action !== 'list') print_fail('unknown action');

// ① CUPS 未完成队列：格式 "Pantum-P2200NW-31   www   1024   Thu 08 Oct 2026 10:34:10 PM CST"
$lines = [];
exec('lpstat -W not-completed -o 2>&1', $lines, $ret);

$activeIds = [];
$jobs = [];
$i = 0;
foreach ($lines as $line) {
    if (!preg_match('/^(\S+-\d+)\s+(\S+)\s+(\d+)\s+(.+)$/', trim($line), $m)) continue;
    $id = $m[1];
    $activeIds[] = $id;
    $jobs[] = [
        'id'    => $id,
        // 队首视为打印中（CUPS 串行队列的合理近似）
        'state' => ($i === 0) ? 'printing' : 'pending',
        'size'  => (int)$m[3],
        'time'  => trim($m[4]),
    ];
    $i++;
}

// ② 对照映射补文件名；把映射里已不在队列的任务标记完成
$map = print_jobs_load();
$dirty = false;
foreach ($jobs as &$j) {
    $j['name'] = isset($map[$j['id']]['name']) ? $map[$j['id']]['name'] : $j['id'];
}
unset($j);
foreach ($map as $id => $info) {
    if ($info['state'] === 'queued' && !in_array($id, $activeIds, true)) {
        $map[$id]['state'] = 'done';
        $map[$id]['done'] = time();
        $dirty = true;
    }
}
if ($dirty) print_jobs_save($map);

// ③ 最近完成的任务（供客户端显示"已完成"）
$recent = [];
foreach ($map as $id => $info) {
    if ($info['state'] !== 'queued') {
        $recent[] = [
            'id'    => $id,
            'name'  => $info['name'],
            'state' => $info['state'],   // done | canceled
            'time'  => isset($info['done']) ? date('Y-m-d H:i', (int)$info['done']) : '',
        ];
    }
}
usort($recent, function ($a, $b) { return strcmp($b['time'], $a['time']); });
$recent = array_slice($recent, 0, 5);

echo json_encode([
    'success' => true,
    'jobs'    => $jobs,
    'recent'  => $recent,
    'printer' => PRINT_PRINTER,
]);
