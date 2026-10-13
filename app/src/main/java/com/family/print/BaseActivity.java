package com.family.print;

import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 所有页面的基类：在窗口创建前按用户偏好应用主题，实现三套皮肤全局切换。
 * 主题偏好存于 print_settings.theme_mode（modern / warm / pro / dark），默认 modern。
 */
public class BaseActivity extends AppCompatActivity {

    /** 当前页对应的导航项 id，供 onResume 重置高亮用 */
    private int navSelectedId;

    /** 本实例创建时应用的主题模式，onResume 对比偏好，变了则自重建换肤 */
    private String appliedMode;

    /** 根据偏好返回主题资源 id */
    public static int getThemeRes(android.content.Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("print_settings", MODE_PRIVATE);
        String mode = prefs.getString("theme_mode", "modern");
        if ("warm".equals(mode)) return R.style.Theme_Print_Warm;
        if ("pro".equals(mode)) return R.style.Theme_Print_Pro;
        if ("dark".equals(mode)) return R.style.Theme_Print_Dark;
        return R.style.Theme_Print_Modern;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedMode = getSharedPreferences("print_settings", MODE_PRIVATE)
                .getString("theme_mode", "modern");
        setTheme(getThemeRes(this));
        super.onCreate(savedInstanceState);
    }

    // ---- 版本号读取（v1.82 迁移：API33+ 走 PackageInfoFlags，旧系统回落 int 重载）----

    /** 当前 app 版本名，异常返回 "?" */
    protected String appVersionName() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            @SuppressWarnings("deprecation")
            android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 当前 app versionCode（PackageInfoCompat.getLongVersionCode），异常返回 -1 */
    protected long appVersionCode() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
                        pm.getPackageInfo(getPackageName(),
                                android.content.pm.PackageManager.PackageInfoFlags.of(0)));
            }
            @SuppressWarnings("deprecation")
            android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 初始化底部导航：高亮当前页 + 点击切换其它页（带 REORDER_TO_FRONT 避免重复实例） */
    public void setupBottomNav(int selectedId) {
        navSelectedId = selectedId;
        BottomNavigationView nav = findViewById(R.id.bottomNav);
        if (nav == null) return;
        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener((MenuItem item) -> {
            int id = item.getItemId();
            if (id == selectedId) return true;
            Class<?> target = null;
            if (id == R.id.nav_home) target = MainActivity.class;
            else if (id == R.id.nav_history) target = HistoryActivity.class;
            else if (id == R.id.nav_log) target = LogActivity.class;
            else if (id == R.id.nav_settings) target = SettingsActivity.class;
            if (target != null && !target.equals(this.getClass())) {
                Intent intent = new Intent(this, target);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            }
            return true;
        });
    }

    /**
     * 回到前台时重置底导高亮。
     * REORDER_TO_FRONT 会复用旧实例：离开本页时点击的项会把高亮动画过去，
     * 返回时若不重置，就会出现"首页页高亮着日志"这类错位（2026-10-08 v1.69 修复）。
     * 重置触发的监听回调 id==navSelectedId，会被守卫直接 return true，不会引起跳页。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 换肤传播：设置页只 recreate 自己，本页若复用旧实例（REORDER_TO_FRONT）
        // 会停留在旧主题——发现偏好已变就重建（2026-10-09 v1.81，夜间主题暴露的历史问题）
        String current = getSharedPreferences("print_settings", MODE_PRIVATE)
                .getString("theme_mode", "modern");
        if (appliedMode != null && !appliedMode.equals(current)) {
            recreate();
            return;
        }
        if (navSelectedId != 0) {
            BottomNavigationView nav = findViewById(R.id.bottomNav);
            if (nav != null) nav.setSelectedItemId(navSelectedId);
        }
    }
}
