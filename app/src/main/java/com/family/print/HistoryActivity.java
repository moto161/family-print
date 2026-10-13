package com.family.print;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends BaseActivity {
    private ListView lvHistory;
    private TextView tvEmpty;
    private List<HistoryItem> historyList = new ArrayList<>();
    private List<HistoryItem> filteredList = new ArrayList<>();
    private HistoryAdapter adapter;
    private EditText etSearch;

    // 打印队列卡片
    private View cardQueue;
    private TextView tvQueueHint;
    private android.widget.LinearLayout llQueueItems;
    private final List<org.json.JSONObject> queueJobs = new ArrayList<>();
    private android.os.Handler mainHandler;
    private static final int TIMEOUT_CONNECT = 10000;
    private static final int TIMEOUT_READ = 10000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        lvHistory = findViewById(R.id.lvHistory);
        tvEmpty = findViewById(R.id.tvEmpty);
        cardQueue = findViewById(R.id.cardQueue);
        tvQueueHint = findViewById(R.id.tvQueueHint);
        llQueueItems = findViewById(R.id.llQueueItems);
        mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClearHistory).setOnClickListener(v -> clearHistory());

        etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterHistory(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadHistory();
        setupBottomNav(R.id.nav_history);
        loadQueue();
    }

    private void loadHistory() {
        historyList.clear();
        SharedPreferences prefs = getSharedPreferences("print_history", MODE_PRIVATE);
        String json = prefs.getString("history", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            // 倒序遍历，最新的记录显示在最上面
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject obj = arr.getJSONObject(i);
                HistoryItem item = new HistoryItem();
                item.fileName = obj.optString("fileName", "");
                item.time = obj.optString("time", "");
                item.pages = obj.optString("pages", "");
                item.status = obj.optString("status", "");
                item.originalIndex = i;   // 记住在 SharedPreferences 数组中的真实下标
                item.plan = obj.optString("plan", "");   // 打印任务计划（重打用）
                historyList.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        filteredList.clear();
        filteredList.addAll(historyList);

        if (filteredList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvHistory.setVisibility(View.VISIBLE);
            if (adapter == null) {
                adapter = new HistoryAdapter();
                lvHistory.setAdapter(adapter);
            } else {
                adapter.notifyDataSetChanged();
            }
        }
    }

    /** 根据关键词过滤历史记录 */
    private void filterHistory(String keyword) {
        filteredList.clear();
        if (keyword == null || keyword.isEmpty()) {
            filteredList.addAll(historyList);
        } else {
            String lower = keyword.toLowerCase();
            for (HistoryItem item : historyList) {
                if (item.fileName.toLowerCase().contains(lower)) {
                    filteredList.add(item);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (filteredList.isEmpty()) {
            tvEmpty.setText("没有找到匹配的记录");
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        } else {
            tvEmpty.setText("暂无打印记录");
            tvEmpty.setVisibility(View.GONE);
            lvHistory.setVisibility(View.VISIBLE);
        }
    }

    private void clearHistory() {
        SharedPreferences prefs = getSharedPreferences("print_history", MODE_PRIVATE);
        prefs.edit().putString("history", "[]").apply();
        historyList.clear();
        filteredList.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(View.VISIBLE);
        lvHistory.setVisibility(View.GONE);
        Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show();
    }

    /**
     * 删除单条历史记录。
     * 必须用 HistoryItem.originalIndex 定位：搜索过滤后 filteredList 的下标与原始数组下标不对应，
     * 原实现用「数组长度 - 1 - 显示位置」反推，一搜索就会删错记录（2026-10-08 修复）。
     */
    private void deleteHistoryItem(int position) {
        if (position < 0 || position >= filteredList.size()) return;
        HistoryItem target = filteredList.get(position);
        SharedPreferences prefs = getSharedPreferences("print_history", MODE_PRIVATE);
        String json = prefs.getString("history", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            int originalIndex = target.originalIndex;
            if (originalIndex >= 0 && originalIndex < arr.length()) {
                JSONArray newArr = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != originalIndex) newArr.put(arr.get(i));
                }
                prefs.edit().putString("history", newArr.toString()).apply();
                // 删除后，排在它前面的记录下标全部前移一位
                for (HistoryItem item : historyList) {
                    if (item.originalIndex > originalIndex) item.originalIndex--;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        historyList.remove(target);
        filteredList.remove(target);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (filteredList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvHistory.setVisibility(View.GONE);
        }
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    private static class HistoryItem {
        String fileName;
        String time;
        String pages;
        String status;
        String plan;         // 打印任务计划 JSON（仅PDF任务，空=不可重打）
        int originalIndex;   // 在 SharedPreferences JSON 数组中的真实下标
    }

    /* ==================== 打印队列（jobs.php） ==================== */

    /** 解析当前主题属性色（v1.82：队列/状态色随主题换肤，夜间模式可读） */
    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private String getJobsUrl() {
        SharedPreferences p = getSharedPreferences("print_settings", MODE_PRIVATE);
        String base = p.getString("print_url", "http://192.0.2.100:8765/index.php");
        return base.replace("/index.php", "/jobs.php");
    }

    /** 拉取打印队列（失败静默：队列卡片保持隐藏，不影响历史记录浏览） */
    private void loadQueue() {
        new Thread(() -> {
            String result = null;
            int code = -1;
            try {
                java.net.URL url = new java.net.URL(getJobsUrl() + "?action=list");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(TIMEOUT_CONNECT);
                conn.setReadTimeout(TIMEOUT_READ);
                String token = getSharedPreferences("print_settings", MODE_PRIVATE)
                        .getString("auth_token", "");
                if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
                code = conn.getResponseCode();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    result = sb.toString();
                }
                conn.disconnect();
            } catch (Exception e) { /* 静默：网络不可用时不打扰用户 */ }

            final int finalCode = code;
            final String finalResult = result;
            mainHandler.post(() -> {
                queueJobs.clear();
                if (finalCode == 200 && finalResult != null) {
                    try {
                        org.json.JSONObject json = new org.json.JSONObject(finalResult);
                        org.json.JSONArray jobs = json.optJSONArray("jobs");
                        if (jobs != null) {
                            for (int i = 0; i < jobs.length(); i++) queueJobs.add(jobs.getJSONObject(i));
                        }
                    } catch (Exception ignored) { }
                }
                renderQueue();
            });
        }).start();
    }

    /** 渲染队列卡片。任务为空时整卡隐藏。 */
    private void renderQueue() {
        if (queueJobs.isEmpty()) {
            cardQueue.setVisibility(View.GONE);
            return;
        }
        cardQueue.setVisibility(View.VISIBLE);
        tvQueueHint.setText(queueJobs.size() + " 个任务");
        llQueueItems.removeAllViews();
        android.app.Activity activity = this;
        for (int i = 0; i < queueJobs.size(); i++) {
            final org.json.JSONObject job = queueJobs.get(i);
            final String id = job.optString("id", "");
            String name = job.optString("name", id);
            String state = job.optString("state", "pending");

            android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12, 0, 12);

            android.widget.TextView tvName = new android.widget.TextView(activity);
            tvName.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String shortName = name.length() > 20 ? name.substring(0, 20) + "…" : name;
            tvName.setText(shortName);
            tvName.setTextSize(13);
            tvName.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
            tvName.setMaxLines(1);
            row.addView(tvName);

            android.widget.TextView tvState = new android.widget.TextView(activity);
            tvState.setText("printing".equals(state) ? "打印中" : "排队中");
            tvState.setTextSize(12);
            tvState.setTextColor("printing".equals(state)
                    ? themeColor(R.attr.colorSuccess) : themeColor(R.attr.colorWarning));
            android.widget.LinearLayout.LayoutParams sp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.setMargins(12, 0, 12, 0);
            tvState.setLayoutParams(sp);
            row.addView(tvState);

            android.widget.Button btnCancel = new android.widget.Button(activity);
            btnCancel.setText("取消");
            btnCancel.setTextSize(12);
            btnCancel.setTextColor(themeColor(com.google.android.material.R.attr.colorError));
            btnCancel.setMinWidth(0);
            btnCancel.setMinHeight(0);
            btnCancel.setPadding(24, 8, 24, 8);
            btnCancel.setOnClickListener(v -> cancelJob(id));
            row.addView(btnCancel);

            llQueueItems.addView(row);
        }
    }

    /** 取消任务后刷新队列 */
    private void cancelJob(String jobId) {
        new AlertDialog.Builder(this)
                .setTitle("取消打印")
                .setMessage("确定取消该打印任务吗？")
                .setPositiveButton("取消任务", (d, w) -> doCancel(jobId))
                .setNegativeButton("返回", null)
                .show();
    }

    private void doCancel(String jobId) {
        new Thread(() -> {
            boolean ok = false;
            try {
                java.net.URL url = new java.net.URL(getJobsUrl() + "?action=cancel&id="
                        + java.net.URLEncoder.encode(jobId, "UTF-8"));
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(TIMEOUT_CONNECT);
                conn.setReadTimeout(TIMEOUT_READ);
                String token = getSharedPreferences("print_settings", MODE_PRIVATE)
                        .getString("auth_token", "");
                if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
                ok = (conn.getResponseCode() == 200);
                conn.disconnect();
            } catch (Exception ignored) { }
            final boolean finalOk = ok;
            mainHandler.post(() -> {
                Toast.makeText(this, finalOk ? "任务已取消" : "取消失败（可能已完成）", Toast.LENGTH_SHORT).show();
                if (finalOk) loadQueue();
            });
        }).start();
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override
        public int getCount() { return filteredList.size(); }

        @Override
        public Object getItem(int position) { return filteredList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(HistoryActivity.this).inflate(R.layout.item_history, parent, false);
            }
            HistoryItem item = filteredList.get(position);
            ((TextView) convertView.findViewById(R.id.tvFileName)).setText(item.fileName);
            ((TextView) convertView.findViewById(R.id.tvTime)).setText(item.time);
            ((TextView) convertView.findViewById(R.id.tvPages)).setText(item.pages);
            TextView tvStatus = convertView.findViewById(R.id.tvStatus);
            tvStatus.setText(item.status);
            if (item.status.contains("成功")) {
                tvStatus.setTextColor(themeColor(R.attr.colorSuccess));
            } else {
                tvStatus.setTextColor(themeColor(com.google.android.material.R.attr.colorError));
            }
            convertView.findViewById(R.id.btnDelete).setOnClickListener(v -> {
                deleteHistoryItem(position);
            });
            Button btnReprint = convertView.findViewById(R.id.btnReprint);
            boolean canReprint = item.plan != null && !item.plan.isEmpty();
            btnReprint.setVisibility(canReprint ? View.VISIBLE : View.GONE);
            if (canReprint) {
                btnReprint.setOnClickListener(v -> reprintConfirm(item));
            }
            return convertView;
        }
    }

    /* ==================== 一键重打 ==================== */

    private void reprintConfirm(HistoryItem item) {
        new AlertDialog.Builder(this)
                .setTitle("重新打印")
                .setMessage("按原参数重新提交：\n" + item.fileName + "（" + item.pages + "）")
                .setPositiveButton("重打", (d, w) -> doReprint(item))
                .setNegativeButton("返回", null)
                .show();
    }

    /** 按计划逐任务重新提交（失败的任务继续下一个，最后汇总） */
    private void doReprint(HistoryItem item) {
        Toast.makeText(this, "正在重新提交打印...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0, total = 0;
            String lastErr = null;
            try {
                JSONArray plan = new JSONArray(item.plan);
                SharedPreferences sp = getSharedPreferences("print_settings", MODE_PRIVATE);
                String printUrl = sp.getString("print_url", "http://192.0.2.100:8765/index.php");
                String token = sp.getString("auth_token", "");
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject e = plan.getJSONObject(i);
                    total++;
                    int code = submitReprint(printUrl, token, e);
                    if (code == 200) ok++;
                    else lastErr = "HTTP " + code + (code == 404 ? "（原文件已过期）" : "");
                }
            } catch (Exception ex) {
                lastErr = ex.getMessage();
            }
            final int fOk = ok, fTotal = total;
            final String fErr = lastErr;
            mainHandler.post(() -> {
                if (fTotal == 0) {
                    Toast.makeText(this, "无可靠打印任务（图片文件不保留，无法重打）", Toast.LENGTH_LONG).show();
                } else if (fOk == fTotal) {
                    Toast.makeText(this, "已重新提交 " + fOk + " 个打印任务", Toast.LENGTH_LONG).show();
                    loadQueue();
                } else {
                    Toast.makeText(this, "成功 " + fOk + "/" + fTotal + (fErr != null ? "：" + fErr : ""), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** 提交单个重打任务，返回 HTTP 状态码 */
    private int submitReprint(String printUrl, String token, JSONObject entry) throws Exception {
        java.net.URL url = new java.net.URL(printUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_CONNECT);
        conn.setReadTimeout(30000);
        conn.setDoInput(true); conn.setDoOutput(true); conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
        StringBuilder params = new StringBuilder();
        params.append("pdf_id=").append(java.net.URLEncoder.encode(entry.getString("pdf_id"), "UTF-8"));
        String ranges = entry.optString("ranges", "");
        if (!ranges.isEmpty()) params.append("&page_ranges=").append(java.net.URLEncoder.encode(ranges, "UTF-8"));
        params.append("&orientation=").append(entry.optInt("orientation", 3));
        params.append("&copies=").append(entry.optInt("copies", 1));
        params.append("&paper=").append(entry.optString("paper", "A4"));
        String jobName = entry.optString("name", "");
        if (!jobName.isEmpty()) params.append("&job_name=").append(java.net.URLEncoder.encode(jobName, "UTF-8"));
        int nup = entry.optInt("nup", 1);
        if (nup > 1) params.append("&nup=").append(nup);
        int eco = entry.optInt("eco", 0);
        if (eco > 0) params.append("&eco=").append(eco);
        int duplex = entry.optInt("duplex", 0);
        if (duplex > 0) params.append("&duplex=").append(duplex);
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(params.toString().getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }
}
