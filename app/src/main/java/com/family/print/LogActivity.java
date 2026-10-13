package com.family.print;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.os.Environment;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogActivity extends BaseActivity {
    private static final int REQ_WRITE_STORAGE = 2001;
    private TextView tvLogs;
    private TextView tvLogStats;
    private String pendingExport = null;   // 等权限授予后再落盘的文本

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        tvLogs = findViewById(R.id.tvLogs);
        tvLogStats = findViewById(R.id.tvLogStats);
        ScrollView scrollLog = findViewById(R.id.scrollLog);

        String logs = LogUtils.getLogs(this);
        tvLogs.setText(logs.isEmpty() ? "暂无日志" : logs);
        updateLogStats(logs);

        // 自动滚动到底部（显示最新日志）
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            LogUtils.clearLogs(this);
            tvLogs.setText("日志已清空");
            tvLogStats.setText("共 0 条日志");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            copyToClipboard(tvLogs.getText().toString());
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnExportLog).setOnClickListener(v -> exportLogToFile());
        setupBottomNav(R.id.nav_log);
    }

    /**
     * 导出日志到文件。
     * Android 9 及以下写公共目录需要运行时权限，原实现从未申请过，必然 Permission denied。
     */
    private void exportLogToFile() {
        try {
            String logs = tvLogs.getText().toString();
            if (logs == null || logs.isEmpty() || logs.equals("暂无日志")) {
                Toast.makeText(this, "没有日志可导出", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                       != PackageManager.PERMISSION_GRANTED) {
                pendingExport = logs;
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
                return;
            }
            doExport(logs);
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingExport != null) doExport(pendingExport);
        } else {
            Toast.makeText(this, "需要存储权限才能导出日志", Toast.LENGTH_LONG).show();
        }
        pendingExport = null;
    }

    private void doExport(String logs) {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "family_print_log_" + timeStamp + ".txt";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10以上使用MediaStore
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(logs.getBytes("UTF-8"));
                    }
                    Toast.makeText(this, "日志已导出到下载目录: " + fileName, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "导出失败：无法创建文件", Toast.LENGTH_LONG).show();
                }
            } else {
                // Android 9以下直接写入
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File logFile = new File(downloadDir, fileName);
                try (FileWriter writer = new FileWriter(logFile)) {
                    writer.write(logs);
                }
                Toast.makeText(this, "日志已导出到: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 更新日志统计信息 */
    private void updateLogStats(String logs) {
        if (logs == null || logs.isEmpty()) {
            tvLogStats.setText("共 0 条日志");
            return;
        }
        String[] lines = logs.split("\n");
        int count = 0;
        int errorCount = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            count++;
            if (line.contains("错误") || line.contains("失败") || line.contains("ERROR")) {
                errorCount++;
            }
        }
        if (errorCount > 0) {
            tvLogStats.setText("共 " + count + " 条日志，其中 " + errorCount + " 条错误");
        } else {
            tvLogStats.setText("共 " + count + " 条日志");
        }
    }

    /** 复制文本到剪贴板 */
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("打印日志", text);
        clipboard.setPrimaryClip(clip);
    }
}
