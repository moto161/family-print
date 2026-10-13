package com.family.print;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.FileProvider;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.core.app.NotificationCompat;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends BaseActivity {
    private static final String TAG = "FamilyPrint";
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_ID_FRONT = 1002;   // 证件复印：选正面
    private static final int REQUEST_ID_BACK = 1003;    // 证件复印：选反面
    private static final int REQUEST_CAMERA_SHOT = 1004; // 拍照直打
    private static final int REQ_PERM_CAMERA = 2002;     // 拍照所需相机权限（zxing 库合并引入 CAMERA 声明，调相机前必须运行时授权）
    private static final String CHANNEL_ID = "print_notification";

    private static final int ORIENT_PORTRAIT = 0;
    private static final int ORIENT_LANDSCAPE = 1;
    private static final int CUPS_PORTRAIT = 3;
    private static final int CUPS_LANDSCAPE = 4;
    private static final int PAPER_A4 = 0;
    private static final int PAPER_A5 = 1;

    private static final int TIMEOUT_SHORT = 5000;
    private static final int TIMEOUT_CONNECT = 10000;
    private static final int TIMEOUT_READ = 15000;

    private static final int C_GREEN = 0xFF4CAF50;
    private static final int C_RED = 0xFFF44336;
    private static final int C_GRAY = 0xFF9E9E9E;
    private static final int C_ORANGE = 0xFFFF9800;
    private static final int C_ICON_BG = 0x80000000;
    private static final int C_WHITE = 0xFFFFFFFF;
    private static final int C_TEXT_SECONDARY = 0xFF666666;

    // 图片处理常量
    private static final int THUMBNAIL_SAMPLE_SIZE = 4;
    private static final int THUMBNAIL_RETRY_COUNT = 2;
    private static final int COLOR_THUMBNAIL_ERROR = 0xFFFF6B6B;
    private static final int COLOR_THUMBNAIL_RETRYING = 0xFF999999;

    // 打印常量
    private static final int MIN_PRINT_COPIES = 1;
    private static final int MAX_PRINT_COPIES = 10;
    private static final int PRINT_RETRY_DELAY = 1000;
    private static final int MAX_HISTORY_COUNT = 50;

    // 批量处理常量
    private static final int BATCH_PROCESS_TIMEOUT = 60;
    private static final int FILE_NAME_TRUNCATE_LEN = 12;
    private static final int MAX_FILES_PER_BATCH = 30;

    // 放大查看的页来源类型（与 ImageViewerActivity 约定）
    public static final int PAGE_SRC_LOCAL = 0;
    public static final int PAGE_SRC_URL = 1;

    private Button btnSelect, btnPrint, btnSelectAll, btnClearAll, btnDelete;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh; // v1.84：下拉刷新替代右上角刷新按钮
    private TextView tvFileName, tvStatus, tvServerStatus, tvPrinterStatus, tvPageCount, tvSelectedPages;
    private ImageView dotServer, dotPrinter;
    private View cardPreview, emptyHint;
    private LinearLayout llThumbnails;
    private ProgressBar progressBar;

    private ArrayList<PageGroup> pageGroups = new ArrayList<>();
    private ArrayList<ImageView> thumbnailImages = new ArrayList<>();
    private ArrayList<ImageView> rotateButtons = new ArrayList<>();
    private ArrayList<TextView> statusViews = new ArrayList<>();
    private List<Integer> selectedPages = new ArrayList<>();
    private int totalPages = 0;
    private int paperSize = PAPER_A4;
    private int nupMode = 1;                       // N合一省纸：1/2/4
    private boolean ecoMode = false;                // 省墨模式：Toner Save（降低碳粉用量）
    private boolean duplexMode = false;             // 手动双面：驱动 ManualDuplex（奇偶分页+翻纸提示）
    private JSONArray lastPrintPlan = new JSONArray(); // 本次打印的任务计划（供历史一键重打）
    private boolean isFirstResume = true; // 避免onCreate和onResume重复检测连接
    private volatile long lastConnectCheckAt = 0L; // 上次连接检测时间（节流用）
    private static final long CONNECT_CHECK_INTERVAL_MS = 60_000L; // 60秒内不重复检测

    // AbortPolicy：队列满时直接拒绝。原 CallerRunsPolicy 会让主线程替线程池跑网络上传，导致 ANR。
    private ExecutorService executor = new ThreadPoolExecutor(2, 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
    // 独立的缩略图下载线程池，避免与主executor死锁
    private ExecutorService thumbnailExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
    /** 打印服务网络客户端（v1.82 从本类抽出） */
    private PrintApiClient api;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private android.util.SparseArray<Bitmap> pendingBitmaps = new android.util.SparseArray<>();
    private java.util.Set<Bitmap> rotatedBitmaps = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    // 缩略图加载失败状态。改用独立集合记录，不再靠 TextView 的显示文本判断（原实现脆弱）。
    private java.util.Set<Integer> failedPages = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("print_settings", MODE_PRIVATE);
        api = new PrintApiClient(prefs);
        createNotificationChannel();
        // Android 13+ 通知权限需运行时请求（targetSdk 33），拒绝时打印通知静默丢弃
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2001);
        }

        btnSelect = findViewById(R.id.btnSelect);
        btnPrint = findViewById(R.id.btnPrint);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnClearAll = findViewById(R.id.btnClearAll);
        btnDelete = findViewById(R.id.btnDelete);
        tvFileName = findViewById(R.id.tvFileName);
        tvStatus = findViewById(R.id.tvStatus);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvPrinterStatus = findViewById(R.id.tvPrinterStatus);
        dotServer = findViewById(R.id.dotServer);
        dotPrinter = findViewById(R.id.dotPrinter);
        progressBar = findViewById(R.id.progressBar);
        cardPreview = findViewById(R.id.cardPreview);
        emptyHint = findViewById(R.id.emptyHint);
        llThumbnails = findViewById(R.id.llThumbnails);
        tvPageCount = findViewById(R.id.tvPageCount);
        tvSelectedPages = findViewById(R.id.tvSelectedPages);

        btnSelect.setOnClickListener(v -> selectFile());
        findViewById(R.id.btnIdCopy).setOnClickListener(v -> startIdCopy());
        findViewById(R.id.btnCamera).setOnClickListener(v -> startCameraCapture());
        btnPrint.setOnClickListener(v -> printFile());
        // v1.84：下拉刷新 = 原右上角刷新按钮的连接状态检测
        swipeRefresh.setColorSchemeResources(R.color.primary);
        swipeRefresh.setOnRefreshListener(this::checkConnectivity);
        btnSelectAll.setOnClickListener(v -> selectAllPages());
        btnClearAll.setOnClickListener(v -> clearAllPages());
        btnDelete.setOnClickListener(v -> deleteSelectedPages());

        setupBottomNav(R.id.nav_home);

        final long startTime = System.currentTimeMillis();
        LogUtils.i(this, "启动", "APP启动，版本" + appVersionName());
        // 清理缓存放到后台线程，避免阻塞启动
        executor.execute(() -> clearOldCache());
        checkConnectivity();
        handleIncomingIntent(getIntent());
        LogUtils.i(this, "启动", "启动耗时: " + (System.currentTimeMillis() - startTime) + "ms");

        // v1.82 迁移：onBackPressed 已弃用，系统返回键改走 OnBackPressedDispatcher
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("退出确认")
                        .setMessage("确定要退出家庭打印助手吗？")
                        .setPositiveButton("退出", (dialog, which) -> {
                            LogUtils.i(MainActivity.this, "退出", "用户确认退出");
                            finish();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    /** 限制单批处理数量，避免任务队列打满被 AbortPolicy 拒绝 */
    private void limitBatch(ArrayList<Uri> uris, ArrayList<String> fileNames) {
        if (uris.size() <= MAX_FILES_PER_BATCH) return;
        while (uris.size() > MAX_FILES_PER_BATCH) uris.remove(uris.size() - 1);
        while (fileNames.size() > MAX_FILES_PER_BATCH) fileNames.remove(fileNames.size() - 1);
        Toast.makeText(this, "一次最多处理 " + MAX_FILES_PER_BATCH + " 个文件", Toast.LENGTH_LONG).show();
    }

    /** 处理外部分享的文件（SEND intent） */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;

        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> fileNames = new ArrayList<>();
        // API33 弃用 int 重载，但泛型重载（getParcelableExtra(name, Class)）需 androidx.core 1.10+，
        // 当前依赖 core 1.9 无该方法——暂保留，升级 core 后迁移 IntentCompat（v1.82 登记）
        @SuppressWarnings("deprecation")
        ArrayList<Uri> streamList = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (Intent.ACTION_SEND_MULTIPLE.equals(action) && streamList != null) {
            for (Uri uri : streamList) {
                uris.add(uri); fileNames.add(getFileName(uri));
            }
        } else {
            @SuppressWarnings("deprecation")
            Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (streamUri != null) {
                uris.add(streamUri); fileNames.add(getFileName(streamUri));
            }
        }
        if (uris.isEmpty()) return;
        limitBatch(uris, fileNames);

        pageGroups.clear(); selectedPages.clear();
        thumbnailImages.clear(); rotateButtons.clear();
        llThumbnails.removeAllViews(); totalPages = 0;
        clearAllBitmaps();

        LogUtils.i(this, "文件", "外部分享了 " + uris.size() + " 个文件");
        StringBuilder names = new StringBuilder();
        for (String n : fileNames) names.append(n).append(", ");
        tvFileName.setText("已选择 " + uris.size() + " 个文件: " + names.toString());
        btnPrint.setEnabled(false);
        tvStatus.setText("正在加载 " + uris.size() + " 个文件的预览...");
        tvStatus.setTextColor(Color.GRAY);
        progressBar.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);
        processFilesBatch(uris, fileNames);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "打印通知", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String content) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, builder.build());
    }

    private void clearOldCache() {
        try {
            File cacheDir = getCacheDir();
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("preview_") || f.getName().startsWith("preview_img_")) f.delete();
                }
            }
        } catch (Exception e) { Log.e(TAG, "Clear cache error", e); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 第一次onResume（刚启动）时onCreate已经检测过连接，跳过避免重复日志；
        // 60秒内切回首页不重复检测（底导来回切会频繁触发，避免刷屏日志）
        if (isFirstResume) {
            isFirstResume = false;
        } else if (System.currentTimeMillis() - lastConnectCheckAt > CONNECT_CHECK_INTERVAL_MS) {
            checkConnectivity();
        }
        if (ImageViewerActivity.sSelectedPages != null) {
            selectedPages.clear();
            selectedPages.addAll(ImageViewerActivity.sSelectedPages);
            ImageViewerActivity.sSelectedPages = null;
            updateThumbnailSelection();
            updateSelectedDisplay();
            LogUtils.i(this, "预览", "从放大查看返回，恢复选中页码");
        }
    }

    /* ==================== 证件复印 ==================== */

    private File idFrontFile = null;   // 正面照片（cache）
    private File idBackFile = null;    // 反面照片（cache）

    /** 证件复印入口：依次选正面/反面照片 */
    @SuppressWarnings("deprecation") // startForResult 弃用；迁移 registerForActivityResult 需重验证件链路，单独变更
    private void startIdCopy() {
        if (idFrontFile != null && idBackFile != null) {
            // 两张都齐了再点 = 重新开始
            idFrontFile = null; idBackFile = null;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        // v1.82 登记：startForResult 已弃用，迁移 registerForActivityResult 需重验选图/拍照链路，单独变更
        startActivityForResult(intent,
                idFrontFile == null ? REQUEST_ID_FRONT : REQUEST_ID_BACK);
    }

    /** 正反两张都齐，确认打印 */
    private void confirmIdCopy() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("证件复印")
                .setMessage("正反面照片将合成为一页 A4（身份证原大、上下排列），确认开始打印？")
                .setPositiveButton("开始打印", (d, w) -> doIdCopyPrint())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 上传正反面照片，服务端合成 A4 后提交打印 */
    private void doIdCopyPrint() {
        final File front = idFrontFile, back = idBackFile;
        if (front == null || back == null) return;
        btnPrint.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvStatus.setText("正在上传证件照片...");
        tvStatus.setTextColor(Color.GRAY);
        LogUtils.i(this, "证件复印", "上传正反面照片");

        executor.execute(() -> {
            String result = null;
            Exception err = null;
            try {
                String boundary = "*****" + System.currentTimeMillis();
                HttpURLConnection conn = api.createMultipartConnection(api.getPrintUrl(), boundary);
                conn.setConnectTimeout(TIMEOUT_CONNECT);
                conn.setReadTimeout(TIMEOUT_READ);
                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    api.writeFileField(dos, boundary, "id_front", "id_front.jpg", front);
                    api.writeFileField(dos, boundary, "id_back", "id_back.jpg", back);
                    api.writeTextField(dos, boundary, "copies", "1");
                    api.writeTextField(dos, boundary, "job_name", "身份证复印");
                    api.finishMultipart(dos, boundary);
                }
                result = api.readResponse(conn);
            } catch (Exception e) {
                err = e;
            }
            final boolean ok = err == null && api.isSuccessResponse(result);
            final String msg = ok ? "证件复印任务已提交"
                    : (err != null ? "打印失败: " + err.getMessage()
                    : "打印失败: " + PrintApiClient.extractError(result));
            if (ok) LogUtils.i(this, "证件复印", "任务已提交");
            else LogUtils.e(this, "证件复印", msg);
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                progressBar.setIndeterminate(false);
                btnPrint.setEnabled(!pageGroups.isEmpty());
                tvStatus.setText(msg);
                tvStatus.setTextColor(ok ? C_GREEN : C_RED);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                if (ok) {
                    saveHistory("身份证复印（正反面一页）", 1, true);
                    idFrontFile = null; idBackFile = null;
                    Button btn = findViewById(R.id.btnIdCopy);
                    btn.setText("证件复印（身份证正反面合成一页 A4）");
                }
            });
        });
    }

    private String getTunnelAddr() { return prefs.getString("tunnel_addr", "192.0.2.100:19100"); }

    // v1.82：网络层（URL/认证/multipart/打印提交）已抽取到 PrintApiClient，见 api 字段

    private void checkConnectivity() {
        lastConnectCheckAt = System.currentTimeMillis();
        tvServerStatus.setText("检测中..."); tvServerStatus.setTextColor(Color.GRAY); dotServer.setImageResource(R.drawable.dot_checking);
        tvPrinterStatus.setText("检测中..."); tvPrinterStatus.setTextColor(Color.GRAY); dotPrinter.setImageResource(R.drawable.dot_checking);

        executor.execute(() -> {
            boolean serverOk = false;
            boolean authFailed = false;
            try {
                // 走 ?action=health 健康检查（与设置页一致），不再 POST "test" 吃 400 判活
                URL url = new URL(api.getPrintUrl() + "?action=health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(TIMEOUT_SHORT); conn.setReadTimeout(TIMEOUT_SHORT);
                conn.setRequestMethod("GET");
                api.applyAuth(conn);
                int code = conn.getResponseCode();
                serverOk = (code == 200);
                authFailed = (code == 401);
                conn.disconnect();
            } catch (Exception e) { Log.e(TAG, "Server check failed", e); }
            final boolean sOk = serverOk;
            final boolean needToken = authFailed;
            mainHandler.post(() -> {
                if (sOk) {
                    LogUtils.i(this, "连接", "阿里云服务器连接成功");
                    tvServerStatus.setText("已连接"); tvServerStatus.setTextColor(C_GREEN); dotServer.setImageResource(R.drawable.dot_online);
                } else if (needToken) {
                    LogUtils.w(this, "连接", "访问令牌缺失或不正确");
                    tvServerStatus.setText("令牌无效"); tvServerStatus.setTextColor(C_RED); dotServer.setImageResource(R.drawable.dot_offline);
                    Toast.makeText(this, "访问令牌缺失或不正确，请在设置中填写", Toast.LENGTH_LONG).show();
                } else {
                    LogUtils.w(this, "连接", "阿里云服务器连接失败");
                    tvServerStatus.setText("连接失败"); tvServerStatus.setTextColor(C_RED); dotServer.setImageResource(R.drawable.dot_offline);
                }
            });

            boolean printerOk = false;
            try {
                String[] parts = getTunnelAddr().split(":");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), TIMEOUT_SHORT);
                printerOk = socket.isConnected();
                socket.close();
            } catch (Exception e) { Log.e(TAG, "Printer check failed", e); }
            final boolean pOk = printerOk;
            mainHandler.post(() -> {
                if (pOk) {
                    LogUtils.i(this, "连接", "打印机在线");
                    tvPrinterStatus.setText("在线"); tvPrinterStatus.setTextColor(C_GREEN); dotPrinter.setImageResource(R.drawable.dot_online);
                } else {
                    LogUtils.w(this, "连接", "打印机离线");
                    tvPrinterStatus.setText("离线"); tvPrinterStatus.setTextColor(C_RED); dotPrinter.setImageResource(R.drawable.dot_offline);
                }
                swipeRefresh.setRefreshing(false); // 下拉圈在连接检测收尾时停转
            });
        });
    }

    // ==================== 拍照直打 ====================

    private Uri pendingCameraUri;
    private File pendingCameraFile;

    /** 调系统相机拍照，照片存 cache，拍完走现有图片打印链路 */
    @SuppressWarnings("deprecation") // startForResult 弃用；迁移 registerForActivityResult 需重验选图/拍照链路，单独变更
    private void startCameraCapture() {
        // Manifest 声明了 CAMERA（zxing 合并）就必须先运行时授权，否则 ACTION_IMAGE_CAPTURE 直接被拒
        if (checkSelfPermission("android.permission.CAMERA") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_PERM_CAMERA);
            return;
        }
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            pendingCameraFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            pendingCameraUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", pendingCameraFile);
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // v1.82 登记：startForResult 已弃用，迁移 registerForActivityResult 需重验选图/拍照链路，单独变更
        startActivityForResult(intent, REQUEST_CAMERA_SHOT);
        } catch (Exception e) {
            Log.e(TAG, "Camera capture start failed", e);
            Toast.makeText(this, "无法启动相机: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startCameraCapture();
            } else {
                Toast.makeText(this, "需要相机权限才能拍照打印", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 文件入库公共逻辑：首页首批清空重建，追加批保留已有页 */
    private void addFilesBatch(ArrayList<Uri> uris, ArrayList<String> fileNames) {
        boolean isFirstBatch = pageGroups.isEmpty();
        if (isFirstBatch) {
            pageGroups.clear(); selectedPages.clear();
            thumbnailImages.clear(); rotateButtons.clear();
            llThumbnails.removeAllViews(); totalPages = 0;
            clearAllBitmaps();
        }
        if (uris.isEmpty()) return;
        limitBatch(uris, fileNames);

        LogUtils.i(this, "文件", (isFirstBatch ? "选择了 " : "追加了 ") + uris.size() + " 个文件");
        if (isFirstBatch) {
            StringBuilder names = new StringBuilder();
            for (String n : fileNames) names.append(n).append(", ");
            tvFileName.setText("已选择 " + uris.size() + " 个文件: " + names.toString());
        } else {
            tvFileName.setText("已选择 " + (pageGroups.size() + uris.size()) + " 个文件（已追加 " + uris.size() + " 个）");
        }
        btnPrint.setEnabled(false);
        tvStatus.setText("正在加载 " + uris.size() + " 个文件的预览...");
        tvStatus.setTextColor(Color.GRAY);
        progressBar.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);
        processFilesBatch(uris, fileNames);
    }

    // ==================== 原有功能 ====================

    @SuppressWarnings("deprecation") // startForResult 弃用；迁移 registerForActivityResult 需重验选图/拍照链路，单独变更
    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        String[] mimeTypes = {
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "image/*"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        // v1.82 登记：startForResult 已弃用，迁移 registerForActivityResult 需重验选图/拍照链路，单独变更
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 证件复印两步选图
        if ((requestCode == REQUEST_ID_FRONT || requestCode == REQUEST_ID_BACK)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final boolean isFront = (requestCode == REQUEST_ID_FRONT);
            executor.execute(() -> {
                try {
                    File f = copyUriToCache(uri, isFront ? "id_front" : "id_back");
                    if (isFront) idFrontFile = f; else idBackFile = f;
                    mainHandler.post(() -> {
                        Button btn = findViewById(R.id.btnIdCopy);
                        if (isFront) {
                            btn.setText("正面已选，请点此选择反面照片");
                            Toast.makeText(this, "正面已选，请再点一次按钮选择反面照片", Toast.LENGTH_LONG).show();
                        } else {
                            btn.setText("证件复印（身份证正反面合成一页 A4）");
                            confirmIdCopy();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "IdCopy pick failed", e);
                    mainHandler.post(() -> Toast.makeText(this, "照片读取失败，请重试", Toast.LENGTH_SHORT).show());
                }
            });
            return;
        }
        if (requestCode == REQUEST_CAMERA_SHOT && resultCode == RESULT_OK) {
            if (pendingCameraUri != null && pendingCameraFile != null && pendingCameraFile.length() > 0) {
                ArrayList<Uri> uris = new ArrayList<>();
                uris.add(pendingCameraUri);
                ArrayList<String> fileNames = new ArrayList<>();
                fileNames.add("拍照_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.CHINA)
                        .format(new java.util.Date()) + ".jpg");
                LogUtils.i(this, "拍照", "拍摄完成，进入打印流程");
                addFilesBatch(uris, fileNames);
            } else {
                Toast.makeText(this, "照片保存失败，请重试", Toast.LENGTH_SHORT).show();
            }
            pendingCameraUri = null; pendingCameraFile = null;
            return;
        }
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            ArrayList<String> fileNames = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    uris.add(uri); fileNames.add(getFileName(uri));
                }
            } else if (data.getData() != null) {
                uris.add(data.getData()); fileNames.add(getFileName(data.getData()));
            }
            addFilesBatch(uris, fileNames);
        }
    }

    /** 批量并行处理文件（循环+CountDownLatch，替代递归避免栈溢出） */
    private void processFilesBatch(final ArrayList<Uri> uris, final ArrayList<String> fileNames) {
        final CountDownLatch latch = new CountDownLatch(uris.size());
        for (int i = 0; i < uris.size(); i++) {
            final Uri uri = uris.get(i);
            final String fileName = fileNames.get(i);
            executor.execute(() -> {
                try { processSingleFile(uri, fileName); }
                catch (Exception e) { Log.e(TAG, "Process file error: " + fileName, e); }
                finally { latch.countDown(); }
            });
        }
        executor.execute(() -> {
            try { latch.await(BATCH_PROCESS_TIMEOUT, TimeUnit.SECONDS); } catch (InterruptedException e) { Log.e(TAG, "Batch wait interrupted", e); }
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                if (totalPages > 0) {
                    cardPreview.setVisibility(View.VISIBLE);
                    tvPageCount.setText("共 " + totalPages + " 页，点击缩略图选中，左上角旋转方向，右上角放大查看");
                    rebuildThumbnails();
                    updateSelectedDisplay();
                    btnPrint.setEnabled(true);
                    tvStatus.setText("预览完成，共" + totalPages + "页，" + pageGroups.size() + "个文件");
                    tvStatus.setTextColor(C_GREEN);
                } else {
                    tvStatus.setText("没有可预览的内容");
                    tvStatus.setTextColor(C_RED);
                }
            });
        });
    }

    /** 获取尺寸资源的像素值 */
    private int dimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /** 按 sp 资源设置字号。DESIGN.md 规定最小 12sp，原实现用了 9sp/10sp。 */
    private void setTextSizeRes(TextView tv, int dimenResId) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(dimenResId));
    }

    /** 把Uri对应的文件复制到缓存目录，返回缓存文件 */
    private File copyUriToCache(Uri uri, String prefix) throws Exception {
        File cacheFile = new File(getCacheDir(), prefix + "_" + UUID.randomUUID());
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(cacheFile)) {
            byte[] buffer = new byte[8192]; int len;
            while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
        }
        return cacheFile;
    }

    /** 处理图片文件：复制到缓存、解码缩略图、添加到列表 */
    private void processImageFile(Uri uri, PageGroup group) throws Exception {
        File imgFile = copyUriToCache(uri, "preview_img");

        group.localImgPath = imgFile.getAbsolutePath();
        group.pdfId = null;
        group.pageCount = 1;
        group.orientations.add(ORIENT_PORTRAIT);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = THUMBNAIL_SAMPLE_SIZE;
        Bitmap thumb = BitmapFactory.decodeFile(imgFile.getAbsolutePath(), opts);
        if (thumb == null) {
            LogUtils.e(this, "预览", "图片解码失败: " + imgFile.getName());
            mainHandler.post(() -> Toast.makeText(this, "图片加载失败: " + imgFile.getName(), Toast.LENGTH_SHORT).show());
            return;
        }
        // 按 EXIF 方向摆正缩略图（手机竖拍照片 EXIF orientation=6/8，不摆正预览与打印都横倒）
        thumb = applyExifOrientation(imgFile.getAbsolutePath(), thumb);

        synchronized (this) {
            group.startPageIndex = totalPages;
            totalPages += 1;
            pageGroups.add(group);
            selectedPages.add(totalPages);
            pendingBitmaps.put(totalPages - 1, thumb);
        }
    }

    /** 按 EXIF orientation 摆正 bitmap；与 ImageMagick -auto-orient 语义一致，读取失败返回原图 */
    private Bitmap applyExifOrientation(String path, Bitmap src) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            int tag = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            int degrees;
            switch (tag) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: return src;
            }
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap fixed = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (fixed != src) rotatedBitmaps.add(fixed);
            return fixed;
        } catch (Exception e) {
            Log.e(TAG, "Exif read failed: " + path, e);
            return src;
        }
    }

    /** 直连前置检查：EXIF orientation 非 NORMAL/UNDEFINED 视为待旋转（打印机不读 EXIF，须走云端 -auto-orient 摆正） */
    private boolean hasExifRotation(String path) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(path);
            int tag = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            return tag != android.media.ExifInterface.ORIENTATION_NORMAL
                    && tag != android.media.ExifInterface.ORIENTATION_UNDEFINED;
        } catch (Exception e) {
            return true;   // EXIF 读不了就保守走云端，云端 -auto-orient 兜底
        }
    }

    /** 处理PDF/Office文件：上传预览、解析JSON、渐进式加载缩略图 */    private void processPdfFile(Uri uri, String fileName, PageGroup group) throws Exception {
        File tempFile = copyUriToCache(uri, "preview_" + fileName);

        // 更新进度：正在上传
        final String uploadFileName = fileName;
        mainHandler.post(() -> {
            tvStatus.setText("正在上传: " + uploadFileName);
            tvStatus.setTextColor(C_ORANGE);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
        });

        String result = api.uploadForPreview(tempFile, fileName);
        tempFile.delete();

        // 更新进度：正在转换/生成预览
        mainHandler.post(() -> {
            tvStatus.setText("正在生成预览: " + uploadFileName);
        });

        JSONObject json = new JSONObject(result);
        if (!json.getBoolean("success")) {
            String errMsg = json.optString("error", "未知错误");
            LogUtils.e(this, "预览", "生成预览失败: " + fileName + " - " + errMsg);
            final String finalErrMsg = errMsg;
            mainHandler.post(() -> {
                tvStatus.setText("预览失败: " + finalErrMsg);
                tvStatus.setTextColor(C_RED);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "预览失败: " + finalErrMsg, Toast.LENGTH_LONG).show();
            });
            return;
        }

        group.pdfId = json.getString("pdf_id");
        group.localImgPath = null;
        group.pageCount = json.getInt("pages");

        JSONArray previews = json.getJSONArray("previews");
        // 大文档提示：预览缩略图只生成前 previewPages 页，但打印仍可选全部页
        final int previewLimit = json.optInt("previewPages", previews.length());
        if (group.pageCount > previewLimit) {
            final String hint = "文档共 " + group.pageCount + " 页，预览仅显示前 " + previewLimit
                    + " 页，打印时仍可选择全部页或指定页范围";
            LogUtils.w(this, "预览", hint);
            mainHandler.post(() -> Toast.makeText(this, hint, Toast.LENGTH_LONG).show());
        }
        group.previews = new ArrayList<>();
        for (int p = 0; p < previews.length(); p++) {
            group.orientations.add(ORIENT_PORTRAIT);
            group.previews.add(previews.getString(p));
        }
        // 大文档：服务端只生成前 previewPages 页缩略图，但 pageCount 是真实总页数，
        // 可能远大于 orientations 长度。补齐到 pageCount，超预览集的页方向默认纵向，
        // 避免打印/统计时 orientations.get(p) 越界崩溃（复现：勾选超出预览集的页码）。
        while (group.orientations.size() < group.pageCount) {
            group.orientations.add(ORIENT_PORTRAIT);
        }

        final int totalThumbs = previews.length();
        final AtomicInteger thumbDone = new AtomicInteger(0);

        // 渐进式加载：先把group加入列表，创建占位缩略图，然后逐个下载更新
        synchronized (this) {
            group.startPageIndex = totalPages;
            totalPages += group.pageCount;
            pageGroups.add(group);
            for (int p = 0; p < group.pageCount; p++) {
                selectedPages.add(group.startPageIndex + p + 1);
            }
        }
        mainHandler.post(() -> {
            rebuildThumbnails();
            progressBar.setIndeterminate(false);
            progressBar.setMax(totalThumbs);
            progressBar.setProgress(0);
            tvStatus.setText("正在加载缩略图 0/" + totalThumbs + "...");
        });

        // 并发下载缩略图，每下载完一个立即更新显示
        for (int p = 0; p < previews.length(); p++) {
            final int pageIdx = p;
            final int globalIdx = group.startPageIndex + p;
            final String previewUrl = previews.getString(pageIdx);
            thumbnailExecutor.execute(() -> {
                try {
                    Bitmap bmp = downloadThumbnailWithRetry(previewUrl, THUMBNAIL_RETRY_COUNT);
                    if (bmp != null) {
                        markThumbnailOk(globalIdx);
                        synchronized (this) {
                            pendingBitmaps.put(globalIdx, bmp);
                        }
                        mainHandler.post(() -> {
                            renderThumbnail(globalIdx);
                            if (globalIdx < statusViews.size()) {
                                statusViews.get(globalIdx).setVisibility(View.GONE);
                            }
                        });
                    } else {
                        markThumbnailFailed(globalIdx);
                        // 下载失败，显示"加载失败，点击重试"
                        mainHandler.post(() -> {
                            if (globalIdx < statusViews.size()) {
                                TextView sv = statusViews.get(globalIdx);
                                sv.setText("加载失败\n点击重试");
                                sv.setTextColor(COLOR_THUMBNAIL_ERROR);
                                sv.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                } catch (Exception e) {
                    markThumbnailFailed(globalIdx);
                    Log.e(TAG, "Thumbnail download failed for page " + pageIdx, e);
                    mainHandler.post(() -> {
                        if (globalIdx < statusViews.size()) {
                            TextView sv = statusViews.get(globalIdx);
                            sv.setText("加载失败\n点击重试");
                            sv.setTextColor(COLOR_THUMBNAIL_ERROR);
                            sv.setVisibility(View.VISIBLE);
                        }
                    });
                } finally {
                    int done = thumbDone.incrementAndGet();
                    mainHandler.post(() -> {
                        progressBar.setProgress(done);
                        // 原实现无论是否全部完成都显示"正在加载"，最后一页加载完后状态栏永远卡在加载中
                        if (done >= totalThumbs) {
                            tvStatus.setText("预览已就绪，共 " + totalThumbs + " 页");
                            tvStatus.setTextColor(C_GREEN);
                        } else {
                            tvStatus.setText("正在加载缩略图 " + done + "/" + totalThumbs + "...");
                        }
                    });
                }
            });
        }
    }

    private void processSingleFile(Uri uri, String fileName) throws Exception {
        PageGroup group = new PageGroup();
        group.fileName = fileName;
        group.orientations = new ArrayList<>();

        if (isImageFile(fileName)) {
            processImageFile(uri, group);
        } else {
            processPdfFile(uri, fileName, group);
        }
    }


    private Bitmap downloadThumbnailWithRetry(String urlStr, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(TIMEOUT_CONNECT);
                conn.setReadTimeout(TIMEOUT_READ);
                try (InputStream is = conn.getInputStream()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    if (bmp != null) return bmp;
                }
            } catch (Exception e) {
                Log.e(TAG, "Thumbnail attempt " + (attempt + 1) + "/" + (maxRetries + 1) + " failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempt < maxRetries) {
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }

    /** 重新下载指定页的缩略图（点击加载失败的缩略图时调用） */
    private void retryThumbnail(final int globalIndex) {
        int[] info = getPageInfo(globalIndex);
        if (info == null) return;
        final int groupIdx = info[0];
        final int pageInGroup = info[1];
        final PageGroup group = pageGroups.get(groupIdx);
        if (group.previews == null || pageInGroup >= group.previews.size()) return;

        // 显示"重试中..."
        if (globalIndex < statusViews.size()) {
            TextView sv = statusViews.get(globalIndex);
            sv.setText("重试中...");
            sv.setTextColor(COLOR_THUMBNAIL_RETRYING);
            sv.setVisibility(View.VISIBLE);
        }

        final String url = group.previews.get(pageInGroup);
        thumbnailExecutor.execute(() -> {
            try {
                Bitmap bmp = downloadThumbnailWithRetry(url, THUMBNAIL_RETRY_COUNT);
                if (bmp != null) {
                    markThumbnailOk(globalIndex);
                    synchronized (this) {
                        pendingBitmaps.put(globalIndex, bmp);
                    }
                    mainHandler.post(() -> {
                        renderThumbnail(globalIndex);
                        if (globalIndex < statusViews.size()) {
                            statusViews.get(globalIndex).setVisibility(View.GONE);
                        }
                    });
                } else {
                    markThumbnailFailed(globalIndex);
                    mainHandler.post(() -> {
                        if (globalIndex < statusViews.size()) {
                            TextView sv = statusViews.get(globalIndex);
                            sv.setText("加载失败\n点击重试");
                            sv.setTextColor(COLOR_THUMBNAIL_ERROR);
                            sv.setVisibility(View.VISIBLE);
                        }
                    });
                }
            } catch (Exception e) {
                markThumbnailFailed(globalIndex);
                Log.e(TAG, "Retry thumbnail failed for page " + globalIndex, e);
                mainHandler.post(() -> {
                    if (globalIndex < statusViews.size()) {
                        TextView sv = statusViews.get(globalIndex);
                        sv.setText("加载失败\n点击重试");
                        sv.setTextColor(COLOR_THUMBNAIL_ERROR);
                        sv.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    /** 回收所有bitmap资源，避免内存泄漏 */
    private void clearAllBitmaps() {
        for (int i = 0; i < pendingBitmaps.size(); i++) {
            Bitmap bmp = pendingBitmaps.valueAt(i);
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        pendingBitmaps.clear();
        for (Bitmap bmp : rotatedBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        rotatedBitmaps.clear();
    }

    private void rebuildThumbnails() {
        llThumbnails.removeAllViews();
        thumbnailImages.clear();
        rotateButtons.clear();
        statusViews.clear();
        for (int i = 0; i < totalPages; i++) {
            Bitmap bmp = pendingBitmaps.get(i);
            addThumbnailView(bmp, i);
        }
        updateThumbnailSelection(); // 循环结束后统一更新，O(n)而非O(n²)
    }

    private void addThumbnailView(Bitmap bitmap, final int globalIndex) {
        int[] info = getPageInfo(globalIndex);
        if (info == null) return;
        final int groupIdx = info[0];
        final int pageInGroup = info[1];
        if (groupIdx >= pageGroups.size()) return;
        final String fileName = pageGroups.get(groupIdx).fileName;

        LinearLayout pageContainer = createThumbnailContainer();
        android.widget.FrameLayout frameLayout = createThumbnailFrame();

        ImageView iv = createThumbnailImageView(bitmap);
        ImageView btnRotate = createRotateButton(groupIdx, pageInGroup, fileName, iv, globalIndex);
        ImageView btnZoom = createZoomButton(globalIndex);
        final TextView tvStatus = createStatusTextView(bitmap);

        frameLayout.addView(tvStatus);
        frameLayout.addView(iv);
        frameLayout.addView(btnRotate);
        frameLayout.addView(btnZoom);

        TextView tvPage = createPageLabel(pageInGroup);
        TextView tvFileName = createFileNameLabel(fileName);

        pageContainer.addView(frameLayout);
        pageContainer.addView(tvPage);
        pageContainer.addView(tvFileName);

        setupThumbnailClick(pageContainer, globalIndex);

        llThumbnails.addView(pageContainer);
        thumbnailImages.add(iv);
        rotateButtons.add(btnRotate);
        statusViews.add(tvStatus);
        // 建视图即按记录方向渲染，覆盖 rebuildThumbnails 这类复用原图的路径
        renderThumbnail(globalIndex);
    }

    private LinearLayout createThumbnailContainer() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);
        return container;
    }

    private android.widget.FrameLayout createThumbnailFrame() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dimen(R.dimen.thumbnail_width), dimen(R.dimen.thumbnail_height)));
        return frame;
    }

    private ImageView createThumbnailImageView(Bitmap bitmap) {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(dimen(R.dimen.thumbnail_width), dimen(R.dimen.thumbnail_height)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundResource(R.drawable.bg_thumbnail);
        if (bitmap != null) iv.setImageBitmap(bitmap);
        return iv;
    }

    private ImageView createRotateButton(final int groupIdx, final int pageInGroup, final String fileName, final ImageView iv, final int globalIndex) {
        ImageView btn = new ImageView(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(dimen(R.dimen.icon_button_size), dimen(R.dimen.icon_button_size));
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.setMargins(dimen(R.dimen.spacing_small), dimen(R.dimen.spacing_small), 0, 0);
        btn.setLayoutParams(params);
        btn.setImageResource(android.R.drawable.ic_menu_rotate);
        btn.setBackgroundColor(C_ICON_BG);
        btn.setPadding(dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding));
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setOnClickListener(v -> {
            // 闭包捕获的索引可能已过期（期间可能删过页/组），这里每次重新取并做边界检查
            if (groupIdx >= pageGroups.size()) return;
            PageGroup group = pageGroups.get(groupIdx);
            if (group.orientations == null || pageInGroup >= group.orientations.size()) return;
            int newOri = (group.orientations.get(pageInGroup) == ORIENT_PORTRAIT) ? ORIENT_LANDSCAPE : ORIENT_PORTRAIT;
            group.orientations.set(pageInGroup, newOri);
            String oriName = newOri == ORIENT_PORTRAIT ? "纵向" : "横向";
            LogUtils.i(this, "方向", fileName + " 第" + (pageInGroup + 1) + "页 → " + oriName);
            Toast.makeText(this, "第" + (pageInGroup + 1) + "页: " + oriName, Toast.LENGTH_SHORT).show();
            applyOrientation(iv, globalIndex, newOri);
        });
        return btn;
    }

    private ImageView createZoomButton(final int globalIndex) {
        ImageView btn = new ImageView(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(dimen(R.dimen.icon_button_size), dimen(R.dimen.icon_button_size));
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
        params.setMargins(0, dimen(R.dimen.spacing_small), dimen(R.dimen.spacing_small), 0);
        btn.setLayoutParams(params);
        btn.setImageResource(android.R.drawable.ic_menu_view);
        btn.setBackgroundColor(C_ICON_BG);
        btn.setPadding(dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding), dimen(R.dimen.icon_button_padding));
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setOnClickListener(v -> {
            LogUtils.i(this, "预览", "点击放大按钮，全局第" + (globalIndex + 1) + "页");
            openImageViewer(globalIndex);
        });
        return btn;
    }

    private TextView createStatusTextView(Bitmap bitmap) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        tv.setGravity(android.view.Gravity.CENTER);
        setTextSizeRes(tv, R.dimen.text_caption);
        tv.setTextColor(COLOR_THUMBNAIL_RETRYING);
        tv.setPadding(0, dimen(R.dimen.status_padding_top), 0, 0);
        if (bitmap == null) tv.setText("加载中...");
        return tv;
    }

    private TextView createPageLabel(int pageInGroup) {
        TextView tv = new TextView(this);
        tv.setText(pageInGroup + 1 + "页");
        setTextSizeRes(tv, R.dimen.text_caption);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(0, 4, 0, 0);
        return tv;
    }

    private TextView createFileNameLabel(String fileName) {
        TextView tv = new TextView(this);
        String shortName = fileName.length() > FILE_NAME_TRUNCATE_LEN ? fileName.substring(0, FILE_NAME_TRUNCATE_LEN) + "…" : fileName;
        tv.setText(shortName);
        setTextSizeRes(tv, R.dimen.text_caption);
        tv.setTextColor(COLOR_THUMBNAIL_RETRYING);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setMaxLines(1);
        return tv;
    }

    private void setupThumbnailClick(View pageContainer, final int globalIndex) {
        pageContainer.setOnClickListener(v -> {
            if (isThumbnailFailed(globalIndex)) {
                retryThumbnail(globalIndex);
                return;
            }
            togglePageSelection(globalIndex);
        });
    }

    private boolean isThumbnailFailed(int globalIndex) {
        return failedPages.contains(globalIndex);
    }

    /** 标记某页缩略图加载失败 */
    private void markThumbnailFailed(int globalIndex) {
        failedPages.add(globalIndex);
    }

    /** 标记某页缩略图加载成功 */
    private void markThumbnailOk(int globalIndex) {
        failedPages.remove(globalIndex);
    }

    private void togglePageSelection(int globalIndex) {
        int pageNum = globalIndex + 1;
        if (selectedPages.contains(pageNum)) {
            selectedPages.remove(Integer.valueOf(pageNum));
        } else {
            selectedPages.add(pageNum);
        }
        Collections.sort(selectedPages);
        updateThumbnailSelection();
        updateSelectedDisplay();
    }

    /**
     * 把缩略图渲染成指定方向。
     *
     * ★ 源图恒取 pendingBitmaps 里的原图，绝不从 iv.getDrawable() 取。
     *   原实现在 original == null 时从 iv 当前显示取，而那已经是上一次的旋转副本，
     *   于是「横向 → 再点一次归位」只是把横图复制了一份，永远回不了正（2026-10-08 修复）。
     */
    private void applyOrientation(ImageView iv, int globalIndex, int orientation) {
        Bitmap source = pendingBitmaps.get(globalIndex);
        if (source == null || source.isRecycled()) {
            // 缩略图尚未加载完成。orientations 已写入，加载完成的回调会按它渲染，此处无需处理。
            return;
        }
        if (iv.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap old = ((android.graphics.drawable.BitmapDrawable) iv.getDrawable()).getBitmap();
            // 只回收我们自己生成的旋转副本；绝不回收 pendingBitmaps 里的原图（别处还在引用）
            if (old != source && rotatedBitmaps.contains(old) && !old.isRecycled()) {
                old.recycle();
                rotatedBitmaps.remove(old);
            }
        }
        if (orientation == ORIENT_LANDSCAPE) {
            Bitmap rotated = makeRotated(source, ORIENT_LANDSCAPE);
            rotatedBitmaps.add(rotated);
            iv.setImageBitmap(rotated);
        } else {
            iv.setImageBitmap(source);   // 归位：直接用原图，不再套一层副本
        }
    }

    /** 取某页当前记录的方向；缺数据时按纵向处理 */
    private int orientationOf(int globalIndex) {
        int[] info = getPageInfo(globalIndex);
        if (info == null || info[0] >= pageGroups.size()) return ORIENT_PORTRAIT;
        PageGroup g = pageGroups.get(info[0]);
        if (g.orientations == null || info[1] >= g.orientations.size()) return ORIENT_PORTRAIT;
        return g.orientations.get(info[1]);
    }

    /**
     * 按该页「当前记录的方向」渲染缩略图。
     * 所有把 bitmap 塞进缩略图 ImageView 的地方都必须走这里，
     * 否则一旦缩略图被重新加载（重试/删页重建/追加文件），旋转状态就会视觉上丢失。
     */
    private void renderThumbnail(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= thumbnailImages.size()) return;
        ImageView iv = thumbnailImages.get(globalIndex);
        if (iv == null) return;
        applyOrientation(iv, globalIndex, orientationOf(globalIndex));
    }

    /** 生成旋转后的 bitmap。调用方负责加入 rotatedBitmaps 以便统一回收。 */
    private Bitmap makeRotated(Bitmap src, int orientation) {
        Matrix matrix = new Matrix();
        if (orientation == ORIENT_LANDSCAPE) matrix.postRotate(90);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    /**
     * 返回 {groupIdx, pageInGroup}；找不到返回 null。
     * 原实现兜底返回 {0, globalIndex}，pageGroups 为空时会 IndexOutOfBounds。
     */
    private int[] getPageInfo(int globalIndex) {
        for (int g = 0; g < pageGroups.size(); g++) {
            PageGroup grp = pageGroups.get(g);
            if (globalIndex >= grp.startPageIndex && globalIndex < grp.startPageIndex + grp.pageCount) {
                return new int[]{g, globalIndex - grp.startPageIndex};
            }
        }
        return null;
    }

    private void openImageViewer(int globalIndex) {
        LogUtils.i(this, "预览", "打开放大查看，全局第" + (globalIndex+1) + "页");
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra("title", "预览 (第" + (globalIndex+1) + "页/共" + totalPages + "页)");
        intent.putExtra("page", globalIndex);
        intent.putExtra("selectedPages", new ArrayList<>(selectedPages));

        // 关键：refs/types 的下标必须严格等于全局页索引 globalIndex。
        // 原实现把「图片按文件」和「PDF 按页」收进两个列表，混选时索引错位 → 放大看错页/空白。
        String[] refs = new String[totalPages];
        int[] types = new int[totalPages];
        for (PageGroup group : pageGroups) {
            if (group.pdfId == null && group.localImgPath != null) {
                for (int p = 0; p < group.pageCount; p++) {
                    int gi = group.startPageIndex + p;
                    if (gi >= 0 && gi < totalPages) { refs[gi] = group.localImgPath; types[gi] = PAGE_SRC_LOCAL; }
                }
            } else if (group.previews != null) {
                for (int p = 0; p < group.pageCount; p++) {
                    int gi = group.startPageIndex + p;
                    if (gi < 0 || gi >= totalPages) continue;
                    // 服务端对超大文档只生成前 N 页缩略图，超出的留 null，由 ImageViewer 提示
                    refs[gi] = (p < group.previews.size()) ? group.previews.get(p) : null;
                    types[gi] = PAGE_SRC_URL;
                }
            }
        }
        if (totalPages > 0) {
            intent.putExtra("pageRefs", refs);
            intent.putExtra("pageTypes", types);
        }
        startActivity(intent);
    }

    private void updateThumbnailSelection() {
        for (int i = 0; i < thumbnailImages.size(); i++) {
            ImageView iv = thumbnailImages.get(i);
            int pageNum = i + 1;
            iv.setBackgroundResource(selectedPages.contains(pageNum) ? R.drawable.bg_thumbnail_selected : R.drawable.bg_thumbnail);
        }
    }

    private void selectAllPages() {
        selectedPages.clear();
        for (int i = 1; i <= totalPages; i++) selectedPages.add(i);
        updateThumbnailSelection();
        updateSelectedDisplay();
    }

    private void clearAllPages() {
        selectedPages.clear();
        updateThumbnailSelection();
        updateSelectedDisplay();
    }

    private void deleteSelectedPages() {
        if (selectedPages.isEmpty()) { Toast.makeText(this, "请先选择要删除的页面", Toast.LENGTH_SHORT).show(); return; }
        final int deleteCount = selectedPages.size();
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要从列表中删除选中的 " + deleteCount + " 页吗？")
                .setPositiveButton("删除", (dialog, which) -> doDeletePages(deleteCount))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDeletePages(int deleteCount) {
        ArrayList<Integer> toDelete = new ArrayList<>(selectedPages);
        Collections.sort(toDelete, Collections.reverseOrder());
        for (int globalPage : toDelete) {
            int globalIndex = globalPage - 1;
            int[] info = getPageInfo(globalIndex);
            if (info == null) continue;
            int groupIdx = info[0];
            int pageInGroup = info[1];
            if (groupIdx < pageGroups.size()) {
                PageGroup group = pageGroups.get(groupIdx);
                if (pageInGroup < group.orientations.size()) group.orientations.remove(pageInGroup);
                if (group.previews != null && pageInGroup < group.previews.size()) group.previews.remove(pageInGroup);
                group.pageCount--;
                if (group.pageCount <= 0) pageGroups.remove(groupIdx);
            }
        }
        totalPages = 0;
        for (PageGroup group : pageGroups) { group.startPageIndex = totalPages; totalPages += group.pageCount; }
        selectedPages.clear();
        if (totalPages > 0) for (int i = 1; i <= totalPages; i++) selectedPages.add(i);
        rebuildThumbnailsAfterDelete();
        updateSelectedDisplay();
        LogUtils.i(this, "删除", "删除了 " + deleteCount + " 页，剩余 " + totalPages + " 页");
        Toast.makeText(this, "已删除 " + deleteCount + " 页，剩余 " + totalPages + " 页", Toast.LENGTH_SHORT).show();
        if (totalPages == 0) {
            cardPreview.setVisibility(View.GONE);
            tvFileName.setText("未选择文件");
            btnPrint.setEnabled(false);
            emptyHint.setVisibility(View.VISIBLE);
        }
    }

    private void rebuildThumbnailsAfterDelete() {
        llThumbnails.removeAllViews();
        thumbnailImages.clear();
        rotateButtons.clear();
        statusViews.clear();      // 原实现漏了这行：删页后状态视图索引错乱 + 旧 TextView 泄漏
        failedPages.clear();
        // 回收pendingBitmaps中的所有bitmap，避免内存泄漏
        for (int i = 0; i < pendingBitmaps.size(); i++) {
            Bitmap bmp = pendingBitmaps.valueAt(i);
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        pendingBitmaps.clear();
        // 回收rotatedBitmaps中的所有旋转bitmap
        for (Bitmap bmp : rotatedBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        rotatedBitmaps.clear();
        for (int g = 0; g < pageGroups.size(); g++) {
            PageGroup group = pageGroups.get(g);
            for (int p = 0; p < group.pageCount; p++) {
                int globalIndex = group.startPageIndex + p;
                pendingBitmaps.put(globalIndex, null);
                addThumbnailView(null, globalIndex);
            }
        }
        updateThumbnailSelection();
        executor.execute(() -> {
            for (int g = 0; g < pageGroups.size(); g++) {
                PageGroup group = pageGroups.get(g);
                for (int p = 0; p < group.pageCount; p++) {
                    int globalIndex = group.startPageIndex + p;
                    Bitmap bmp = null;
                    if (group.pdfId == null && group.localImgPath != null) {
                        try {
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = THUMBNAIL_SAMPLE_SIZE;
                            bmp = BitmapFactory.decodeFile(group.localImgPath, opts);
                            // 与首载一致：按 EXIF 摆正，否则删页后缩略图横倒
                            if (bmp != null) bmp = applyExifOrientation(group.localImgPath, bmp);
                        } catch (Exception e) { Log.e(TAG, "Reload image thumb failed", e); }
                    } else if (group.previews != null && p < group.previews.size()) {
                        bmp = downloadThumbnailWithRetry(group.previews.get(p), THUMBNAIL_RETRY_COUNT);
                    }
                    if (bmp != null) {
                        final Bitmap finalBmp = bmp;
                        final int finalIdx = globalIndex;
                        mainHandler.post(() -> {
                            pendingBitmaps.put(finalIdx, finalBmp);
                            markThumbnailOk(finalIdx);
                            // 按该页记录的方向渲染：删页后旋转状态不能视觉上丢失，必须与打印方向一致
                            renderThumbnail(finalIdx);
                            if (finalIdx < statusViews.size()) statusViews.get(finalIdx).setVisibility(View.GONE);
                        });
                    } else {
                        final int failedIdx = globalIndex;
                        mainHandler.post(() -> {
                            markThumbnailFailed(failedIdx);
                            if (failedIdx < statusViews.size()) {
                                TextView sv = statusViews.get(failedIdx);
                                sv.setText("加载失败\n点击重试");
                                sv.setTextColor(COLOR_THUMBNAIL_ERROR);
                                sv.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }
            }
        });
    }

    private void updateSelectedDisplay() {
        if (selectedPages.size() == totalPages) { tvSelectedPages.setText("已选：全部 " + totalPages + " 页"); tvSelectedPages.setTextColor(C_GREEN); }
        else if (selectedPages.isEmpty()) { tvSelectedPages.setText("已选：0 页（请至少选择一页）"); tvSelectedPages.setTextColor(C_RED); }
        else { tvSelectedPages.setText("已选：" + selectedPages.size() + " 页"); tvSelectedPages.setTextColor(C_ORANGE); }
    }

    private void recordPrintHistory(boolean success) {
        StringBuilder allNames = new StringBuilder();
        for (int g = 0; g < pageGroups.size(); g++) {
            if (g > 0) allNames.append(", ");
            allNames.append(pageGroups.get(g).fileName);
        }
        saveHistory(allNames.toString(), selectedPages.size(), success);
    }

    private void saveHistory(String fileName, int pageCount, boolean success) {
        try {
            SharedPreferences prefs = getSharedPreferences("print_history", MODE_PRIVATE);
            String json = prefs.getString("history", "[]");
            JSONArray arr = new JSONArray(json);
            JSONObject obj = new JSONObject();
            obj.put("fileName", fileName);
            obj.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));
            obj.put("pages", pageCount + "页");
            obj.put("status", success ? "打印成功" : "打印失败");
            if (success && lastPrintPlan.length() > 0) {
                obj.put("plan", lastPrintPlan); // 打印任务计划，供历史页一键重打
            }
            arr.put(obj);
            if (arr.length() > MAX_HISTORY_COUNT) {
                JSONArray newArr = new JSONArray();
                for (int i = arr.length() - MAX_HISTORY_COUNT; i < arr.length(); i++) newArr.put(arr.get(i));
                arr = newArr;
            }
            prefs.edit().putString("history", arr.toString()).apply();
        } catch (Exception e) { LogUtils.e(this, "历史", "保存打印历史失败: " + e.getMessage()); }
    }

    private boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    private String getFileName(Uri uri) {
        String result = null;
        // query 抛 SecurityException 会让 APP 在 onCreate 直接闪退（分享方未带读授权时），
        // 必须 catch 住走路径兜底（2026-10-08 真机实测崩溃：content://media 无授权即崩）
        try {
            if (uri.getScheme().equals("content")) {
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) result = cursor.getString(idx);
                    }
                } finally { if (cursor != null) cursor.close(); }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFileName query failed for " + uri, e);
        }
        if (result == null) {
            String p = uri.getLastPathSegment();
            if (p == null || p.isEmpty()) p = "file_" + System.currentTimeMillis();
            int cut = p.lastIndexOf('/');
            result = (cut != -1) ? p.substring(cut + 1) : p;
        }
        return result;
    }

    private void printFile() {
        if (pageGroups.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedPages.isEmpty()) {
            Toast.makeText(this, "请至少选择一页", Toast.LENGTH_SHORT).show();
            return;
        }

        int[] orientationCounts = countOrientationPages();
        int fileCount = pageGroups.size();
        int pageCount = selectedPages.size();
        final java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger(1);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_print_confirm, null);
        setupPrintDialogSummary(dialogView, fileCount, pageCount, orientationCounts);
        setupCopiesButtons(dialogView, copies);
        setupPaperButtons(dialogView);

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(dialogView);
        Button btnSheetPrint = dialogView.findViewById(R.id.btnSheetPrint);
        Button btnSheetCancel = dialogView.findViewById(R.id.btnSheetCancel);
        if (btnSheetPrint != null) btnSheetPrint.setOnClickListener(v -> { sheet.dismiss(); doPrint(copies.get()); });
        if (btnSheetCancel != null) btnSheetCancel.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    /** 统计选中页中纵向和横向的数量 */
    private int[] countOrientationPages() {
        int portraitCount = 0;
        int landscapeCount = 0;
        for (PageGroup group : pageGroups) {
            for (int p = 0; p < group.pageCount; p++) {
                int globalPage = group.startPageIndex + p + 1;
                if (selectedPages.contains(globalPage)) {
                    if (group.orientations.get(p) == ORIENT_LANDSCAPE) landscapeCount++;
                    else portraitCount++;
                }
            }
        }
        return new int[]{portraitCount, landscapeCount};
    }

    /** 设置打印对话框的摘要信息 */
    private void setupPrintDialogSummary(View dialogView, int fileCount, int pageCount, int[] orientationCounts) {
        TextView tvFileCount = dialogView.findViewById(R.id.tvFileCount);
        TextView tvPageCount = dialogView.findViewById(R.id.tvPageCount);
        TextView tvOrientation = dialogView.findViewById(R.id.tvOrientation);
        TextView tvEstTime = dialogView.findViewById(R.id.tvEstTime);

        tvFileCount.setText(fileCount + " 个");
        tvPageCount.setText(pageCount + " 页");
        tvOrientation.setText("纵向 " + orientationCounts[0] + " 页，横向 " + orientationCounts[1] + " 页");

        int estSeconds = pageCount * 2;
        String estTime = estSeconds < 60 ? "约 " + estSeconds + " 秒" : "约 " + (estSeconds / 60) + " 分 " + (estSeconds % 60) + " 秒";
        tvEstTime.setText(estTime);
    }

    /** 设置打印份数加减按钮 */
    private void setupCopiesButtons(View dialogView, final java.util.concurrent.atomic.AtomicInteger copies) {
        final TextView tvCopies = dialogView.findViewById(R.id.tvCopies);
        Button btnMinus = dialogView.findViewById(R.id.btnMinus);
        Button btnPlus = dialogView.findViewById(R.id.btnPlus);

        btnMinus.setOnClickListener(v -> {
            if (copies.get() > MIN_PRINT_COPIES) {
                copies.decrementAndGet();
                tvCopies.setText(copies.get() + " 份");
            }
        });
        btnPlus.setOnClickListener(v -> {
            if (copies.get() < MAX_PRINT_COPIES) {
                copies.incrementAndGet();
                tvCopies.setText(copies.get() + " 份");
            }
        });
    }

    /** 设置纸张大小切换按钮 */
    private void setupPaperButtons(View dialogView) {
        final Button btnPaperA4 = dialogView.findViewById(R.id.btnPaperA4);
        final Button btnPaperA5 = dialogView.findViewById(R.id.btnPaperA5);

        btnPaperA4.setOnClickListener(v -> {
            paperSize = PAPER_A4;
            updatePaperButtonState(btnPaperA4, btnPaperA5);
        });
        btnPaperA5.setOnClickListener(v -> {
            paperSize = PAPER_A5;
            updatePaperButtonState(btnPaperA5, btnPaperA4);
        });

        // N合一省纸按钮
        final Button btnNup1 = dialogView.findViewById(R.id.btnNup1);
        final Button btnNup2 = dialogView.findViewById(R.id.btnNup2);
        final Button btnNup4 = dialogView.findViewById(R.id.btnNup4);
        View.OnClickListener nupListener = v -> {
            if (v.getId() == R.id.btnNup2) nupMode = 2;
            else if (v.getId() == R.id.btnNup4) nupMode = 4;
            else nupMode = 1;
            updateNupButtonState(btnNup1, btnNup2, btnNup4);
            LogUtils.i(this, "打印", "N合一已切换为 " + nupMode + "合1");
        };
        btnNup1.setOnClickListener(nupListener);
        btnNup2.setOnClickListener(nupListener);
        btnNup4.setOnClickListener(nupListener);
        updateNupButtonState(btnNup1, btnNup2, btnNup4);

        // 省墨模式按钮
        final Button btnEcoOff = dialogView.findViewById(R.id.btnEcoOff);
        final Button btnEcoOn = dialogView.findViewById(R.id.btnEcoOn);
        View.OnClickListener ecoListener = v -> {
            ecoMode = (v.getId() == R.id.btnEcoOn);
            updateEcoButtonState(btnEcoOff, btnEcoOn);
            LogUtils.i(this, "打印", "省墨模式 " + (ecoMode ? "开启" : "关闭"));
        };
        btnEcoOff.setOnClickListener(ecoListener);
        btnEcoOn.setOnClickListener(ecoListener);
        updateEcoButtonState(btnEcoOff, btnEcoOn);

        final Button btnDuplexOff = dialogView.findViewById(R.id.btnDuplexOff);
        final Button btnDuplexOn = dialogView.findViewById(R.id.btnDuplexOn);
        View.OnClickListener duplexListener = v -> {
            duplexMode = (v.getId() == R.id.btnDuplexOn);
            updateDuplexButtonState(btnDuplexOff, btnDuplexOn);
            LogUtils.i(this, "打印", "双面打印 " + (duplexMode ? "开启（打完一面按打印机提示翻纸）" : "关闭"));
        };
        btnDuplexOff.setOnClickListener(duplexListener);
        btnDuplexOn.setOnClickListener(duplexListener);
        updateDuplexButtonState(btnDuplexOff, btnDuplexOn);

        // 初始化状态
        if (paperSize == PAPER_A5) {
            updatePaperButtonState(btnPaperA5, btnPaperA4);
        } else {
            updatePaperButtonState(btnPaperA4, btnPaperA5);
        }
    }

    /** 更新纸张按钮的选中状态 */
    private void updatePaperButtonState(Button selected, Button unselected) {
        selected.setTextColor(C_WHITE);
        selected.setBackgroundResource(R.drawable.bg_button_primary);
        unselected.setTextColor(C_TEXT_SECONDARY);
        unselected.setBackgroundResource(R.drawable.bg_button_quantity);
    }

    /** 更新N合一按钮的选中状态 */
    private void updateNupButtonState(Button b1, Button b2, Button b4) {
        b1.setTextColor(nupMode == 1 ? C_WHITE : C_TEXT_SECONDARY);
        b1.setBackgroundResource(nupMode == 1 ? R.drawable.bg_button_primary : R.drawable.bg_button_quantity);
        b2.setTextColor(nupMode == 2 ? C_WHITE : C_TEXT_SECONDARY);
        b2.setBackgroundResource(nupMode == 2 ? R.drawable.bg_button_primary : R.drawable.bg_button_quantity);
        b4.setTextColor(nupMode == 4 ? C_WHITE : C_TEXT_SECONDARY);
        b4.setBackgroundResource(nupMode == 4 ? R.drawable.bg_button_primary : R.drawable.bg_button_quantity);
    }

    /** 更新省墨模式按钮的选中状态 */
    private void updateEcoButtonState(Button off, Button on) {
        off.setTextColor(ecoMode ? C_TEXT_SECONDARY : C_WHITE);
        off.setBackgroundResource(ecoMode ? R.drawable.bg_button_quantity : R.drawable.bg_button_primary);
        on.setTextColor(ecoMode ? C_WHITE : C_TEXT_SECONDARY);
        on.setBackgroundResource(ecoMode ? R.drawable.bg_button_primary : R.drawable.bg_button_quantity);
    }

    private void updateDuplexButtonState(Button off, Button on) {
        off.setTextColor(duplexMode ? C_TEXT_SECONDARY : C_WHITE);
        off.setBackgroundResource(duplexMode ? R.drawable.bg_button_quantity : R.drawable.bg_button_primary);
        on.setTextColor(duplexMode ? C_WHITE : C_TEXT_SECONDARY);
        on.setBackgroundResource(duplexMode ? R.drawable.bg_button_primary : R.drawable.bg_button_quantity);
    }

    private void doPrint(int copies) {
        btnSelect.setEnabled(false);
        btnPrint.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        lastPrintPlan = new JSONArray(); // 重置任务计划，printGroup 中逐任务登记

        final int totalTasks = countPrintTasks();
        final AtomicInteger completedTasks = new AtomicInteger(0);

        progressBar.setIndeterminate(false);
        progressBar.setMax(totalTasks);
        progressBar.setProgress(0);
        updatePrintProgress(0, totalTasks, 0);

        final long startTime = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                int[] result = executePrintLoop(copies, totalTasks, completedTasks);
                final int ok = result[0];
                final int total = result[1];
                final String err = result[2] > 0 ? "部分失败" : "";
                final String durationStr = formatDuration(System.currentTimeMillis() - startTime);
                mainHandler.post(() -> handlePrintResult(ok, total, err, durationStr));
            } catch (Exception e) {
                Log.e(TAG, "Print error", e);
                mainHandler.post(() -> handlePrintError(e));
            }
        });
    }

    /** 统计需要打印的任务组数（每个文件的纵向和横向各算一组） */
    private int countPrintTasks() {
        int totalTasks = 0;
        for (PageGroup group : pageGroups) {
            boolean hasPortrait = false;
            boolean hasLandscape = false;
            for (int p = 0; p < group.pageCount; p++) {
                int globalPage = group.startPageIndex + p + 1;
                if (selectedPages.contains(globalPage)) {
                    if (group.orientations.get(p) == ORIENT_LANDSCAPE) hasLandscape = true;
                    else hasPortrait = true;
                }
            }
            if (hasPortrait) totalTasks++;
            if (hasLandscape) totalTasks++;
        }
        return totalTasks;
    }

    /** 执行打印循环，返回[成功数, 总数, 失败数] */
    private int[] executePrintLoop(int copies, int totalTasks, AtomicInteger completedTasks) throws Exception {
        int successCount = 0;
        int totalCount = 0;
        int failCount = 0;
        for (PageGroup group : pageGroups) {
            final String currentFileName = group.fileName;
            mainHandler.post(() -> tvStatus.setText("正在打印: " + currentFileName + " (" + completedTasks.get() + "/" + totalTasks + " 组)"));
            int[] result = printGroup(group, copies);
            successCount += result[0];
            totalCount += result[1];
            if (result[1] > 0 && result[0] < result[1]) failCount++;
            final int completed = completedTasks.addAndGet(result[1]);
            final int percent = totalTasks > 0 ? (completed * 100 / totalTasks) : 0;
            mainHandler.post(() -> updatePrintProgress(completed, totalTasks, percent));
        }
        return new int[]{successCount, totalCount, failCount};
    }

    /** 更新打印进度显示 */
    private void updatePrintProgress(int completed, int total, int percent) {
        progressBar.setProgress(completed);
        tvStatus.setText("正在打印 " + completed + "/" + total + " 组 (" + percent + "%)...");
        tvStatus.setTextColor(Color.GRAY);
    }

    /** 处理打印结果 */
    private void handlePrintResult(int ok, int total, String err, String durationStr) {
        progressBar.setVisibility(View.GONE);
        btnPrint.setEnabled(true);
        btnSelect.setEnabled(true);
        boolean allSuccess = (ok == total && total > 0);

        if (allSuccess) {
            tvStatus.setText("打印完成！共 " + ok + " 组任务");
            tvStatus.setTextColor(C_GREEN);
            recordPrintHistory(true);
            showNotification("打印完成", "共 " + ok + " 组任务打印成功");
            LogUtils.i(this, "打印", "全部打印成功，耗时" + durationStr);
        } else {
            tvStatus.setText("打印完成: " + ok + "/" + total + " 成功");
            tvStatus.setTextColor(C_RED);
            recordPrintHistory(false);
            showNotification("打印部分失败", ok + "/" + total + " 组成功");
            LogUtils.e(this, "打印", "打印部分失败: " + err + "，耗时" + durationStr);
        }

        showPrintResultDialog(allSuccess, ok, total, err, durationStr);
    }

    /** 显示打印完成摘要弹窗 */
    private void showPrintResultDialog(boolean allSuccess, int ok, int total, String err, String durationStr) {
        String title = allSuccess ? "打印完成" : "打印部分完成";
        String message = "成功：" + ok + "/" + total + " 组\n耗时：" + durationStr + "\n" + (allSuccess ? "" : "错误：" + err + "\n");
        new AlertDialog.Builder(MainActivity.this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("查看历史", (d, w) -> startActivity(new Intent(MainActivity.this, HistoryActivity.class)))
            .setNegativeButton("确定", null)
            .show();
    }

    /** 处理打印错误 */
    private void handlePrintError(Exception e) {
        progressBar.setVisibility(View.GONE);
        btnPrint.setEnabled(true);
        btnSelect.setEnabled(true);
        tvStatus.setText("错误: " + e.getMessage());
        tvStatus.setTextColor(C_RED);
    }

    /** 格式化耗时 */
    private String formatDuration(long durationMs) {
        if (durationMs < 60000) return (durationMs / 1000) + " 秒";
        return (durationMs / 60000) + " 分 " + ((durationMs % 60000) / 1000) + " 秒";
    }

    /** 打印失败自动重试（最多重试1次） */
    private String printWithRetry(PageGroup group, String range, int orientation, int copies, String paper, int nup, boolean eco, boolean duplex) throws Exception {
        String result = (group.pdfId != null)
                ? api.printWithPdfId(group.pdfId, range, orientation, copies, paper, nup, eco, duplex, group.fileName)
                : api.uploadAndPrintImage(group.localImgPath, group.fileName, range, orientation, copies, paper, nup, eco, duplex, group.fileName);
        if (!api.isSuccessResponse(result)) {
            LogUtils.w(this, "打印", "打印失败，正在重试... 文件: " + group.fileName);
            try { Thread.sleep(PRINT_RETRY_DELAY); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            result = (group.pdfId != null)
                    ? api.printWithPdfId(group.pdfId, range, orientation, copies, paper, nup, eco, duplex, group.fileName)
                    : api.uploadAndPrintImage(group.localImgPath, group.fileName, range, orientation, copies, paper, nup, eco, duplex, group.fileName);
        }
        return result;
    }

    private int[] printGroup(PageGroup group, int copies) throws Exception {
        int successCount = 0;
        int totalCount = 0;
        ArrayList<Integer> selectedInGroup = new ArrayList<>();
        for (int p = 0; p < group.pageCount; p++) {
            int globalPage = group.startPageIndex + p + 1;
            if (selectedPages.contains(globalPage)) selectedInGroup.add(p + 1);
        }
        if (selectedInGroup.isEmpty()) return new int[]{0, 0};

        ArrayList<Integer> portraitPages = new ArrayList<>();
        ArrayList<Integer> landscapePages = new ArrayList<>();
        for (int p : selectedInGroup) {
            if (group.orientations.get(p - 1) == ORIENT_LANDSCAPE) landscapePages.add(p);
            else portraitPages.add(p);
        }

        String paperParam = paperSize == PAPER_A4 ? "A4" : "A5";
        final int nup = nupMode;
        final boolean eco = ecoMode;
        final boolean duplex = duplexMode;

        // 局域网直连（v1.78 引入，v1.80 停用）：2026-10-09 实测 Pantum P2200NW 三条直连通道全死——
        //   ① IPP Print-Job(octet-stream JPEG)：收进队列永不处理、永不回响应（120s 超时，短超时断连还会挂死单线程 IPP 栈）
        //   ② 9100 裸推 JPEG：不出纸  ③ 9100 裸推 PDF：不出纸
        //   根因（PPD 铁证）：*cupsFilter "application/vnd.cups-raster 0 pt2500Filter"，
        //   9100 只认 Pantum 专有栅格，通用格式全喂不进。唯一理论通道 = IPP image/urf（AirPrint），
        //   待可行性验证后恢复（IppDirectClient/isAlive 保留备用）。详见《开发框架.md》v1.80。
        if (!prefs.getString("printer_ip", "").isEmpty()) {
            LogUtils.i(this, "直连", "直连已停用（打印机仅支持专有栅格格式），走云端打印: " + group.fileName);
        }

        if (!portraitPages.isEmpty()) {
            totalCount++;
            String range = pagesToString(portraitPages);
            String result = printWithRetry(group, range, CUPS_PORTRAIT, copies, paperParam, nup, eco, duplex);
            if (group.pdfId != null) addPlanEntry(group.pdfId, group.fileName, range, CUPS_PORTRAIT, copies, paperParam, nup, eco, duplex);
            if (api.isSuccessResponse(result)) successCount++;
        }
        if (!landscapePages.isEmpty()) {
            totalCount++;
            String range = pagesToString(landscapePages);
            String result = printWithRetry(group, range, CUPS_LANDSCAPE, copies, paperParam, nup, eco, duplex);
            if (group.pdfId != null) addPlanEntry(group.pdfId, group.fileName, range, CUPS_LANDSCAPE, copies, paperParam, nup, eco, duplex);
            if (api.isSuccessResponse(result)) successCount++;
        }
        return new int[]{successCount, totalCount};
    }

    /** 登记一条打印任务到计划（仅PDF任务可重打；图片任务原文件不保留，跳过）。name 供重打时回传 job_name */
    private void addPlanEntry(String pdfId, String fileName, String range, int orientation, int copies, String paper, int nup, boolean eco, boolean duplex) {
        try {
            JSONObject e = new JSONObject();
            e.put("pdf_id", pdfId);
            e.put("name", fileName == null ? "" : fileName);
            e.put("ranges", range == null ? "" : range);
            e.put("orientation", orientation);
            e.put("copies", copies);
            e.put("paper", paper);
            e.put("nup", nup);
            e.put("eco", eco ? 1 : 0);
            e.put("duplex", duplex ? 1 : 0);
            lastPrintPlan.put(e);
        } catch (Exception ex) { Log.e(TAG, "addPlanEntry", ex); }
    }

    private String pagesToString(List<Integer> pages) {
        if (pages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = pages.get(0);
        int prev = start;
        for (int i = 1; i < pages.size(); i++) {
            int curr = pages.get(i);
            if (curr != prev + 1) {
                if (start == prev) sb.append(start).append(",");
                else sb.append(start).append("-").append(prev).append(",");
                start = curr;
            }
            prev = curr;
        }
        if (start == prev) sb.append(start);
        else sb.append(start).append("-").append(prev);
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < pendingBitmaps.size(); i++) {
            Bitmap bmp = pendingBitmaps.valueAt(i);
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        pendingBitmaps.clear();
        for (Bitmap bmp : rotatedBitmaps) {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
        rotatedBitmaps.clear();
        executor.shutdownNow();
        thumbnailExecutor.shutdownNow();
    }

}
