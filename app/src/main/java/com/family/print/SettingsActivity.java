package com.family.print;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import com.google.android.material.card.MaterialCardView;
import androidx.core.content.FileProvider;
import com.google.android.material.color.MaterialColors;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {
    private static final String VERSION_CHECK_URL = "http://192.0.2.100:8081/apk/family_print_version.json";

    // APK 下载域名白名单：version.json 里的 url 完全由远端控制，必须限定只能指向自家服务器
    private static final String APK_TRUSTED_HOST = "192.0.2.100";

    private EditText etPrintUrl, etPrinterIp, etTunnelAddr, etAuthToken;
    private TextView tvCacheSize, tvVersion, tvTestResult;
    /** 配置码协议前缀：FPRINT1|<print_url>|<token> */
    private static final String QR_PREFIX = "FPRINT1|";
    private SharedPreferences prefs;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("print_settings", MODE_PRIVATE);

        etPrintUrl = findViewById(R.id.etPrintUrl);
        etPrinterIp = findViewById(R.id.etPrinterIp);
        etTunnelAddr = findViewById(R.id.etTunnelAddr);
        tvCacheSize = findViewById(R.id.tvCacheSize);
        tvVersion = findViewById(R.id.tvVersion);
        tvTestResult = findViewById(R.id.tvTestResult);

        // 加载设置
        etPrintUrl.setText(prefs.getString("print_url", "http://192.0.2.100:8765/index.php"));
        etPrinterIp.setText(prefs.getString("printer_ip", "192.168.1.100"));
        etTunnelAddr.setText(prefs.getString("tunnel_addr", "192.0.2.100:19100"));
        etAuthToken = findViewById(R.id.etAuthToken);
        if (etAuthToken != null) etAuthToken.setText(prefs.getString("auth_token", ""));

        // 显示版本号
        try {
            tvVersion.setText("版本: v" + appVersionName() + " (点击检查更新)");
        } catch (Exception e) {
            tvVersion.setText("版本: 未知 (点击检查更新)");
        }

        // 计算并显示缓存大小
        calculateCacheSize();

        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnUpdatePrinterIp).setOnClickListener(v -> updatePrinterIp());
        findViewById(R.id.btnClearCache).setOnClickListener(v -> clearCache());
        findViewById(R.id.btnTestConnection).setOnClickListener(v -> testConnection());
        findViewById(R.id.btnShareConfig).setOnClickListener(v -> showShareQr());
        findViewById(R.id.btnScanConfig).setOnClickListener(v -> startScanConfig());

        // 版本号点击检查更新
        tvVersion.setOnClickListener(v -> checkUpdate());

        // 三主题切换
        setupThemeSelector();
        // 底部导航
        setupBottomNav(R.id.nav_settings);
    }

    /** 主题选择卡片：点击切换 theme_mode 并即时换肤 */
    private void setupThemeSelector() {
        MaterialCardView[] cards = {
                findViewById(R.id.cardThemeModern),
                findViewById(R.id.cardThemeWarm),
                findViewById(R.id.cardThemePro),
                findViewById(R.id.cardThemeDark)
        };
        String current = prefs.getString("theme_mode", "modern");
        highlightThemeCard(cards, current);

        View.OnClickListener listener = v -> {
            String mode = "modern";
            if (v.getId() == R.id.cardThemeWarm) mode = "warm";
            else if (v.getId() == R.id.cardThemePro) mode = "pro";
            else if (v.getId() == R.id.cardThemeDark) mode = "dark";
            prefs.edit().putString("theme_mode", mode).apply();
            highlightThemeCard(cards, mode);
            // 立即换肤：重新创建 Activity 让 BaseActivity 重新应用主题
            recreate();
        };
        for (MaterialCardView c : cards) {
            if (c != null) c.setOnClickListener(listener);
        }
    }

    /** 根据当前主题高亮对应卡片（选中=主题主色描边，未选=中性描边） */
    private void highlightThemeCard(MaterialCardView[] cards, String mode) {
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLACK);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.BLACK);
        int container = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, Color.WHITE);
        int surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        float density = getResources().getDisplayMetrics().density;
        int selW = (int) (3 * density);
        int unsW = (int) (1.5 * density);
        int idx = mode.equals("warm") ? 1 : mode.equals("pro") ? 2 : 0;
        for (int i = 0; i < cards.length; i++) {
            MaterialCardView c = cards[i];
            if (c == null) continue;
            boolean sel = (i == idx);
            c.setCardBackgroundColor(sel ? container : surface);
            c.setStrokeColor(sel ? primary : outline);
            c.setStrokeWidth(sel ? selW : unsW);
        }
    }

    // ==================== 自动更新 ====================

    /** 检查更新 */
    private void checkUpdate() {
        tvVersion.setText("正在检查更新...");
        tvVersion.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.graphite));

        executor.execute(() -> {
            try {
                URL url = new URL(VERSION_CHECK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("GET");

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                conn.disconnect();

                JSONObject json = new JSONObject(response.toString());
                int latestCode = json.getInt("versionCode");
                String latestName = json.getString("versionName");
                String title = json.getString("title");
                String content = json.getString("content");
                String apkUrl = json.getString("url");
                String sha256 = json.optString("sha256", "");
                boolean force = json.optBoolean("force", false);

                int currentCode = (int) appVersionCode();

                mainHandler.post(() -> {
                    restoreVersionDisplay();
                    if (latestCode > currentCode) {
                        showUpdateDialog(title, content, apkUrl, sha256, force);
                    } else {
                        Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    restoreVersionDisplay();
                    Toast.makeText(this, "检查更新失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 恢复版本号显示 */
    private void restoreVersionDisplay() {
        try {
            tvVersion.setText("版本: v" + appVersionName() + " (点击检查更新)");
        } catch (Exception e) {
            tvVersion.setText("版本: 未知 (点击检查更新)");
        }
        tvVersion.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary));
    }

    /** 显示更新对话框 */
    private void showUpdateDialog(String title, String content, String apkUrl, String sha256, boolean force) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(content)
            .setCancelable(!force)
            .setPositiveButton("立即更新", (dialog, which) -> downloadAndInstall(apkUrl, sha256));

        if (!force) {
            builder.setNegativeButton("稍后再说", null);
        }
        builder.show();
    }

    /**
     * 下载并安装APK。
     * 2026-10-08 加固：原实现直接下载远端 JSON 里给的任意 URL 并安装，
     * 中间人改一下 url 字段就能推送恶意安装包（配合 REQUEST_INSTALL_PACKAGES 权限 = 设备沦陷）。
     */
    private void downloadAndInstall(String apkUrl, String expectedSha256) {
        if (!isTrustedApkUrl(apkUrl)) {
            Toast.makeText(this, "更新地址不合法，已拒绝下载", Toast.LENGTH_LONG).show();
            return;
        }
        // 创建进度条对话框
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setPadding(40, 20, 40, 20);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle("正在下载更新")
            .setView(progressBar)
            .setCancelable(false)
            .show();

        executor.execute(() -> {
            try {
                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestMethod("GET");

                int totalLength = conn.getContentLength();
                InputStream input = conn.getInputStream();

                File apkFile = new File(getExternalCacheDir(), "update.apk");
                try (FileOutputStream output = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        if (totalLength > 0) {
                            int progress = (int) (totalRead * 100 / totalLength);
                            mainHandler.post(() -> progressBar.setProgress(progress));
                        }
                    }
                }
                input.close();
                conn.disconnect();

                // SHA-256 校验：version.json 提供了 sha256 就必须一致，否则拒绝安装
                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    String actual = sha256Of(apkFile);
                    if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
                        apkFile.delete();
                        mainHandler.post(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(this, "安装包校验失败，已拒绝安装", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                }

                // v1.82 M3：HTTP 更新链路最后防线——下载包签名必须与当前安装包一致。
                // 基准是设备上已安装 app 自己的签名（自包含信任锚），同信道 MITM 无法伪造。
                boolean sigOk = isSignatureConsistent(apkFile);
                final String sigLog = "下载完成 size=" + apkFile.length() + " 签名校验=" + (sigOk ? "通过" : "不一致");
                if (!sigOk) {
                    apkFile.delete();
                    LogUtils.w(this, "更新", sigLog + "，拒绝安装");
                    mainHandler.post(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(this, "安装包签名校验失败，已拒绝安装", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                LogUtils.i(this, "更新", sigLog + "，启动安装");
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    installApk(apkFile);
                });
            } catch (Exception e) {
                LogUtils.e(this, "更新", "下载异常: " + e);
                mainHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 只信任自家服务器的 APK 地址 */
    private boolean isTrustedApkUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = new URL(url).getHost();
            return host != null && (host.equals(APK_TRUSTED_HOST) || host.endsWith("." + APK_TRUSTED_HOST));
        } catch (Exception e) {
            return false;
        }
    }

    /** 计算文件 SHA-256，失败返回 null */
    private String sha256Of(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int n;
                while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算字节数组的 SHA-256 十六进制，失败返回 null */
    private String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前已安装 app 的签名摘要（信任基准），取不到返回 null */
    @SuppressWarnings("deprecation") // API<28 只有旧 GET_SIGNATURES，双分支故意保留
    private String installedSignatureDigest() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo si = pi.signingInfo;
                if (si == null) return null;
                android.content.pm.Signature[] sigs = si.hasMultipleSigners()
                        ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
                return (sigs != null && sigs.length > 0) ? sha256Hex(sigs[0].toByteArray()) : null;
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNATURES);
                return (pi.signatures != null && pi.signatures.length > 0)
                        ? sha256Hex(pi.signatures[0].toByteArray()) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载包签名是否与当前安装包一致；任何取不到/解析失败都视为不一致（fail-closed） */
    @SuppressWarnings("deprecation") // API<28 只有旧 GET_SIGNATURES / int 重载，双分支故意保留
    private boolean isSignatureConsistent(File apkFile) {
        String expected = installedSignatureDigest();
        if (expected == null) return false;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo p2 = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                        android.content.pm.PackageManager.GET_SIGNATURES);
                pi = p2;
            }
            if (pi == null) return false;
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            } else if (pi.signatures != null && pi.signatures.length > 0) {
                sigs = pi.signatures;
            } else {
                return false;
            }
            if (sigs == null || sigs.length == 0) return false;
            return expected.equalsIgnoreCase(sha256Hex(sigs[0].toByteArray()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 安装APK */
    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            startActivity(intent);
        } catch (Exception e) {
            LogUtils.e(this, "更新", "启动安装异常: " + e);
            Toast.makeText(this, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 配置二维码分享 / 导入 ====================

    /** 生成配置二维码弹窗：内容 = print_url + 访问令牌，新设备扫码一键导入 */
    private void showShareQr() {
        String url = etPrintUrl.getText().toString().trim();
        String token = (etAuthToken != null ? etAuthToken.getText().toString().trim() : "");
        if (url.isEmpty()) {
            Toast.makeText(this, "请先填写打印接口地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String payload = QR_PREFIX + url + "|" + token;
            BitMatrix matrix = new QRCodeWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, 600, 600);
            int w = matrix.getWidth(), h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);

            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            iv.setPadding(pad, pad, pad, pad);

            new AlertDialog.Builder(this)
                    .setTitle("配置二维码")
                    .setMessage("新设备在设置页点「扫码导入」即可自动填入服务器地址与令牌")
                    .setView(iv)
                    .setPositiveButton("完成", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "生成二维码失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 启动扫码（zxing embedded CaptureActivity，相机权限由其自行申请） */
    @SuppressWarnings("deprecation") // zxing 4.x 推荐 ScanContract；扫码链路待真机实测后再迁移（v1.82 登记）
    private void startScanConfig() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setOrientationLocked(false);
        integrator.setBeepEnabled(false);
        integrator.setPrompt("对准已配置设备设置页中的二维码");
        integrator.initiateScan();
    }

    /** 解析扫码结果并填入输入框（不自动保存，由用户核对后点保存） */
    private void applyScannedConfig(String content) {
        if (content == null || !content.startsWith(QR_PREFIX)) {
            Toast.makeText(this, "不是本应用的配置二维码", Toast.LENGTH_LONG).show();
            return;
        }
        String[] parts = content.split("\\|");
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            Toast.makeText(this, "配置码内容不完整", Toast.LENGTH_LONG).show();
            return;
        }
        String url = parts[1].trim();
        String token = parts.length > 2 ? parts[2].trim() : "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "配置码中的地址不合法", Toast.LENGTH_LONG).show();
            return;
        }
        etPrintUrl.setText(url);
        if (etAuthToken != null) etAuthToken.setText(token);
        Toast.makeText(this, "已导入配置，请核对后点「保存设置」", Toast.LENGTH_LONG).show();
    }

    // ==================== 原有功能 ====================

    /** 测试连接 */
    private void testConnection() {
        tvTestResult.setText("正在测试连接...");
        tvTestResult.setTextColor(0xFF9E9E9E);

        executor.execute(() -> {
            StringBuilder result = new StringBuilder();
            boolean serverOk = testServerConnection(result);
            boolean printerOk = testTunnelConnection(result);
            testLocalConnection(result);

            final String finalResult = result.toString();
            final boolean allOk = serverOk && printerOk;
            mainHandler.post(() -> {
                tvTestResult.setText(finalResult);
                tvTestResult.setTextColor(allOk ? 0xFF4CAF50 : 0xFFF44336);
            });
        });
    }

    /** 测试阿里云打印接口，返回是否连通（走 ?action=health 健康检查，避免被方法白名单误伤） */
    private boolean testServerConnection(StringBuilder result) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        String errMsg = "";
        try {
            URL url = new URL(etPrintUrl.getText().toString().trim() + "?action=health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            // 优先用输入框当前值（用户可能刚改还没保存），否则用已保存值
            String token = (etAuthToken != null ? etAuthToken.getText().toString().trim() : "");
            if (token.isEmpty()) token = prefs.getString("auth_token", "");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
            int code = conn.getResponseCode();
            conn.disconnect();
            ok = (code == 200);
            if (code == 401) errMsg = "401（访问令牌无效或不匹配）";
            else if (!ok) errMsg = "HTTP " + code;
        } catch (Exception e) {
            errMsg = e.getClass().getSimpleName();
        }
        long time = System.currentTimeMillis() - start;
        result.append(ok ? "✅ 阿里云接口：连通 (" + time + "ms)\n"
                         : "❌ 阿里云接口：连接失败" + (errMsg.isEmpty() ? "" : " (" + errMsg + ")") + "\n");
        return ok;
    }

    /** 测试打印机隧道，返回是否连通 */
    private boolean testTunnelConnection(StringBuilder result) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            String[] parts = etTunnelAddr.getText().toString().trim().split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9100;
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), 5000);
            ok = socket.isConnected();
            socket.close();
        } catch (Exception e) {
            ok = false;
        }
        long time = System.currentTimeMillis() - start;
        result.append(ok ? "✅ 打印机隧道：连通 (" + time + "ms)\n" : "❌ 打印机隧道：连接失败\n");
        return ok;
    }

    /** 测试打印机局域网直连 */
    private void testLocalConnection(StringBuilder result) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            String ip = etPrinterIp.getText().toString().trim();
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(ip, 9100), 3000);
            ok = socket.isConnected();
            socket.close();
        } catch (Exception e) {
            ok = false;
        }
        long time = System.currentTimeMillis() - start;
        result.append(ok ? "✅ 打印机直连：连通 (" + time + "ms)\n" : "⚠️ 打印机直连：未连通（远程时正常）");
    }

    private void saveSettings() {
        prefs.edit()
            .putString("print_url", etPrintUrl.getText().toString().trim())
            .putString("printer_ip", etPrinterIp.getText().toString().trim())
            .putString("tunnel_addr", etTunnelAddr.getText().toString().trim())
            .putString("auth_token", (etAuthToken != null ? etAuthToken.getText().toString().trim() : ""))
            .apply();
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void updatePrinterIp() {
        String newIp = etPrinterIp.getText().toString().trim();
        if (newIp.isEmpty()) {
            Toast.makeText(this, "请输入打印机IP", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isValidIpv4(newIp)) {
            Toast.makeText(this, "IP格式不正确（示例 192.168.1.100）", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "正在更新打印机IP...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                // 调用阿里云接口更新隧道上的打印机IP。IP 必须编码，避免参数注入。
                String baseUrl = etPrintUrl.getText().toString().replace("/index.php", "");
                URL url = new URL(baseUrl + "/update_printer.php?ip=" + java.net.URLEncoder.encode(newIp, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                String token = prefs.getString("auth_token", "");
                if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                // 先看响应码再读流：401 时 getInputStream 会抛 FileNotFoundException，提示不可读（v1.79 修复）
                int code = conn.getResponseCode();
                java.io.InputStream ris = (code >= 400 && conn.getErrorStream() != null)
                        ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(ris))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                conn.disconnect();

                String result = response.toString();
                // 严格 JSON 解析。原 contains("success") 在返回 {"success":false,...} 时误报成功
                boolean ok = (code == 200);
                if (ok) {
                    try { ok = new org.json.JSONObject(result).optBoolean("success", false); }
                    catch (Exception ignored) { }
                }
                final boolean finalOk = ok;
                mainHandler.post(() -> {
                    if (finalOk) {
                        Toast.makeText(SettingsActivity.this, "打印机IP已更新: " + newIp, Toast.LENGTH_LONG).show();
                        prefs.edit().putString("printer_ip", newIp).apply();
                    } else {
                        Toast.makeText(SettingsActivity.this, "更新失败: " + result, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(SettingsActivity.this, "更新失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 严格校验 IPv4 字面量，避免把任意字符串送去服务端 */
    private boolean isValidIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            int v;
            try { v = Integer.parseInt(p); } catch (NumberFormatException e) { return false; }
            if (v < 0 || v > 255) return false;
            if (p.length() > 1 && p.startsWith("0")) return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation") // 同 startScanConfig：待真机实测后整体迁移 ScanContract
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                applyScannedConfig(result.getContents());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 计算缓存大小并显示 */
    private void calculateCacheSize() {
        executor.execute(() -> {
            long size = 0;
            try {
                java.io.File cacheDir = getCacheDir();
                size = getDirSize(cacheDir);
                // 也包括外部缓存
                if (getExternalCacheDir() != null) {
                    size += getDirSize(getExternalCacheDir());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            final long finalSize = size;
            mainHandler.post(() -> {
                tvCacheSize.setText("缓存大小: " + formatSize(finalSize));
            });
        });
    }

    /** 递归计算目录大小 */
    private long getDirSize(java.io.File dir) {
        long size = 0;
        if (dir != null && dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isDirectory()) {
                        size += getDirSize(file);
                    } else {
                        size += file.length();
                    }
                }
            }
        }
        return size;
    }

    /** 格式化文件大小 */
    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    /** 清理缓存 */
    private void clearCache() {
        executor.execute(() -> {
            try {
                deleteDirKeepPreview(getCacheDir());
                if (getExternalCacheDir() != null) {
                    deleteDirKeepPreview(getExternalCacheDir());
                }
                mainHandler.post(() -> {
                    Toast.makeText(SettingsActivity.this, "缓存已清理", Toast.LENGTH_SHORT).show();
                    calculateCacheSize();
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(SettingsActivity.this, "清理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * 递归删除目录内容，但保留首页已选文件的预览副本（preview_* 前缀）。
     * 原实现全删：用户选好图 → 进设置清缓存 → 回首页缩略图还在（Bitmap 在内存）→ 一点打印
     * 就 FileNotFoundException。
     */
    private void deleteDirKeepPreview(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File file : files) {
            String name = file.getName();
            if (name.startsWith("preview_") || name.startsWith("preview_img_")) continue;
            if (file.isDirectory()) {
                deleteDirKeepPreview(file);
            } else {
                file.delete();
            }
        }
    }
}
