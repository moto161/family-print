package com.family.print;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

public class ImageViewerActivity extends BaseActivity {
    public static ArrayList<Integer> sSelectedPages = null;
    private ImageView ivFullImage;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private Matrix matrix = new Matrix();
    private float minScale = 1.0f;
    private float currentScale = 1.0f;
    private PointF lastTouch = new PointF();
    private boolean isDragging = false;
    private int currentPage = 0;
    // 页来源：下标严格等于全局页索引（MainActivity 保证），0=本地文件 1=网络URL
    private String[] pageRefs = null;
    private int[] pageTypes = null;
    private ArrayList<Integer> selectedPages = new ArrayList<>();
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v1.82 迁移：onBackPressed 已弃用，系统返回键改走 OnBackPressedDispatcher
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                LogUtils.i(ImageViewerActivity.this, "预览", "按返回键，关闭放大查看");
                finishWithResult();
            }
        });
        setContentView(R.layout.activity_image_viewer);

        ivFullImage = findViewById(R.id.ivFullImage);
        TextView tvTitle = findViewById(R.id.tvTitle);
        Button btnSelectPage = findViewById(R.id.btnSelectPage);

        String title = getIntent().getStringExtra("title");
        currentPage = getIntent().getIntExtra("page", 0);
        pageRefs = getIntent().getStringArrayExtra("pageRefs");
        pageTypes = getIntent().getIntArrayExtra("pageTypes");
        selectedPages = getIntent().getIntegerArrayListExtra("selectedPages");
        if (currentPage < 0) currentPage = 0;

        if (title != null) tvTitle.setText(title);
        if (selectedPages == null) selectedPages = new ArrayList<>();

        findViewById(R.id.btnBack).setOnClickListener(v -> { LogUtils.i(ImageViewerActivity.this, "预览", "点击返回按钮"); finishWithResult(); });
        btnSelectPage.setOnClickListener(v -> toggleSelectPage());

        // 等待ImageView布局完成后再加载图片
        ivFullImage.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ivFullImage.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                loadCurrentImage();
            }
        });

        // 缩放手势
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (currentBitmap == null) return false;
                float scaleFactor = detector.getScaleFactor();
                float newScale = currentScale * scaleFactor;
                newScale = Math.max(minScale, Math.min(newScale, 5.0f));
                float ratio = newScale / currentScale;
                matrix.postScale(ratio, ratio, detector.getFocusX(), detector.getFocusY());
                currentScale = newScale;
                ivFullImage.setImageMatrix(matrix);
                return true;
            }
        });

        // 双击和滑动手势
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (currentBitmap == null) return false;
                if (currentScale > minScale * 1.1f) {
                    resetMatrix();
                } else {
                    currentScale = minScale * 2.0f;
                    matrix.setScale(currentScale, currentScale, e.getX(), e.getY());
                    ivFullImage.setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (currentScale <= minScale * 1.05f && Math.abs(velocityX) > 100) {
                    if (velocityX > 0 && currentPage > 0) {
                        currentPage--;
                        loadCurrentImage();
                    } else if (velocityX < 0 && currentPage < getTotalCount() - 1) {
                        currentPage++;
                        loadCurrentImage();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 单击不关闭，避免误触；使用返回按钮或返回键关闭
                return true;
            }
        });

        ivFullImage.setScaleType(ImageView.ScaleType.MATRIX);
        ivFullImage.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            // 拖拽
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouch.set(event.getX(), event.getY());
                    isDragging = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isDragging && currentScale > minScale * 1.1f) {
                        float dx = event.getX() - lastTouch.x;
                        float dy = event.getY() - lastTouch.y;
                        matrix.postTranslate(dx, dy);
                        ivFullImage.setImageMatrix(matrix);
                        lastTouch.set(event.getX(), event.getY());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    isDragging = false;
                    break;
            }
            return true;
        });

        updateSelectButton();
    }

    private int getTotalCount() {
        return (pageRefs == null) ? 1 : pageRefs.length;
    }

    /** 当前页是否有可用图源 */
    private boolean hasSource(int page) {
        return pageRefs != null && page >= 0 && page < pageRefs.length && pageRefs[page] != null;
    }

    private void loadCurrentImage() {
        currentScale = 1.0f;
        matrix.reset();
        ivFullImage.setImageMatrix(matrix);

        if (!hasSource(currentPage)) {
            ivFullImage.setImageBitmap(null);
            Toast.makeText(this, "该页暂无预览图", Toast.LENGTH_SHORT).show();
            return;
        }

        int maxSide = Math.max(ivFullImage.getWidth(), ivFullImage.getHeight());
        if (maxSide <= 0) maxSide = 2048;   // 布局尚未测量时的兜底

        boolean isLocal = pageTypes != null && currentPage < pageTypes.length
                && pageTypes[currentPage] == MainActivity.PAGE_SRC_LOCAL;

        if (isLocal) {
            File file = new File(pageRefs[currentPage]);
            if (!file.exists()) {
                Toast.makeText(this, "文件已不存在，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }
            Bitmap bmp = decodeSampledFile(file.getAbsolutePath(), maxSide);
            if (bmp != null) {
                setCurrentBitmap(bmp);
            } else {
                Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show();
            }
        } else {
            final int page = currentPage;
            final int target = maxSide;
            final String url = pageRefs[currentPage];
            new Thread(() -> {
                try {
                    Bitmap bmp = decodeSampledUrl(url, target);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            if (page != currentPage) return;   // 已翻页，丢弃过期结果
                            setCurrentBitmap(bmp);
                        });
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }

    /** 设置新 bitmap 前先回收旧图，避免逐页浏览大文档时旧图堆积导致 OOM */
    private void setCurrentBitmap(Bitmap bmp) {
        if (bmp == null) return;
        Bitmap old = currentBitmap;
        currentBitmap = bmp;
        ivFullImage.setImageBitmap(bmp);
        ivFullImage.post(() -> initMatrix());
        if (old != null && old != bmp && !old.isRecycled()) old.recycle();
    }

    /**
     * 按视图尺寸采样解码本地文件。
     * 原实现直接 decodeStream 不采样：4000×3000 照片解码后约 48MB，必 OOM。
     */
    private Bitmap decodeSampledFile(String path, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, maxSide);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            LogUtils.e(this, "预览", "解码失败: " + e.getMessage());
            return null;
        }
    }

    /** 网络图两遍解码：先取尺寸算采样比，再正式解码（InputStream 不可回退，只能开两次连接） */
    private Bitmap decodeSampledUrl(String urlStr, int maxSide) throws Exception {
        int sample;
        java.net.HttpURLConnection c1 = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        c1.setConnectTimeout(15000); c1.setReadTimeout(30000);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(c1.getInputStream(), null, bounds);
            sample = calcSampleSize(bounds.outWidth, bounds.outHeight, maxSide);
        } finally { c1.disconnect(); }

        java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        c2.setConnectTimeout(15000); c2.setReadTimeout(30000);
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(c2.getInputStream(), null, opts);
        } finally { c2.disconnect(); }
    }

    private int calcSampleSize(int w, int h, int maxSide) {
        if (w <= 0 || h <= 0) return 1;
        int longer = Math.max(w, h);
        int sample = 1;
        while (longer / sample > maxSide * 2) sample <<= 1;   // 留 2 倍余量供放大查看
        return sample;
    }

    private void initMatrix() {
        if (currentBitmap == null || ivFullImage.getWidth() == 0) return;
        float imgWidth = currentBitmap.getWidth();
        float imgHeight = currentBitmap.getHeight();
        float viewWidth = ivFullImage.getWidth();
        float viewHeight = ivFullImage.getHeight();
        float scale = Math.min(viewWidth / imgWidth, viewHeight / imgHeight);
        minScale = scale;
        currentScale = scale;
        float dx = (viewWidth - imgWidth * scale) / 2f;
        float dy = (viewHeight - imgHeight * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        ivFullImage.setImageMatrix(matrix);
    }

    private void resetMatrix() {
        initMatrix();
    }

    private void toggleSelectPage() {
        int pageNum = currentPage + 1;
        if (selectedPages.contains(pageNum)) {
            selectedPages.remove(Integer.valueOf(pageNum));
        } else {
            selectedPages.add(pageNum);
        }
        java.util.Collections.sort(selectedPages);
        updateSelectButton();
    }

    private void updateSelectButton() {
        Button btnSelectPage = findViewById(R.id.btnSelectPage);
        int pageNum = currentPage + 1;
        if (selectedPages.contains(pageNum)) {
            btnSelectPage.setText("取消选择");
            btnSelectPage.setTextColor(0xFFF44336);
        } else {
            btnSelectPage.setText("选择此页");
            btnSelectPage.setTextColor(0xFF4CAF50);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
        currentBitmap = null;
    }

    private void finishWithResult() {
        // 使用静态变量保存选中页码，避免startActivityForResult返回时MainActivity被回收
        sSelectedPages = new ArrayList<>(selectedPages);
        finish();
    }

}
