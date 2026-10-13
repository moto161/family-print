package com.family.print;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志持久化。
 *
 * 2026-10-08 修复：
 *   1. SimpleDateFormat 原为静态单例，非线程安全。日志会从 executor 后台线程写入
 *      （如打印重试），并发 format() 会抛异常或产出错误时间戳 → 改 ThreadLocal。
 *   2. 原实现把日志拼成单个字符串用 \n 分割存储，而服务端返回的完整 JSON 错误信息
 *      本身含换行 → 一条日志被拆成多条 → 改用 JSONArray 存储。
 *   3. 读-改-写存在竞态，并发写会丢日志 → 加类锁。
 */
public class LogUtils {
    private static final String TAG = "FamilyPrint";
    private static final String PREFS_NAME = "app_logs";
    private static final String KEY_LOGS = "log_list";
    private static final int MAX_LOGS = 200;

    private static final Object LOCK = new Object();

    /** SimpleDateFormat 非线程安全，每个线程一份 */
    private static final ThreadLocal<SimpleDateFormat> SDF = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        }
    };

    public static void d(Context context, String tag, String message) {
        addLog(context, "DEBUG", tag, message);
    }

    public static void i(Context context, String tag, String message) {
        addLog(context, "INFO", tag, message);
    }

    public static void w(Context context, String tag, String message) {
        addLog(context, "WARN", tag, message);
    }

    public static void e(Context context, String tag, String message) {
        addLog(context, "ERROR", tag, message);
    }

    private static void addLog(Context context, String level, String tag, String message) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                JSONArray arr = readArray(prefs);

                JSONObject obj = new JSONObject();
                obj.put("time", SDF.get().format(new Date()));
                obj.put("level", level);
                obj.put("tag", tag == null ? "" : tag);
                obj.put("msg", message == null ? "" : message);
                arr.put(obj);

                // 只保留最近 MAX_LOGS 条
                if (arr.length() > MAX_LOGS) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = arr.length() - MAX_LOGS; i < arr.length(); i++) {
                        trimmed.put(arr.get(i));
                    }
                    arr = trimmed;
                }
                // 用 commit 同步落盘：apply 是异步的，下一次读可能读到旧值
                prefs.edit().putString(KEY_LOGS, arr.toString()).commit();
            } catch (Exception e) {
                Log.e(TAG, "addLog failed", e);
            }
        }
    }

    /**
     * 取全部日志（格式化为可读文本）。
     * 兼容旧格式：若存储的是纯文本（非 JSON 数组），原样返回。
     */
    public static String getLogs(Context context) {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String raw = prefs.getString(KEY_LOGS, "");
                if (raw == null || raw.isEmpty() || raw.equals("暂无日志")) return "";

                JSONArray arr = readArray(prefs);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    sb.append("[")
                      .append(o.optString("time", ""))
                      .append("] [").append(o.optString("level", ""))
                      .append("] [").append(o.optString("tag", ""))
                      .append("] ").append(o.optString("msg", ""))
                      .append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                Log.e(TAG, "getLogs failed", e);
                return "";
            }
        }
    }

    public static void clearLogs(Context context) {
        synchronized (LOCK) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LOGS, "[]").commit();
            } catch (Exception e) {
                Log.e(TAG, "clearLogs failed", e);
            }
        }
    }

    private static JSONArray readArray(SharedPreferences prefs) {
        String raw = prefs.getString(KEY_LOGS, "");
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try {
            JSONArray arr = new JSONArray(raw);
            return arr;
        } catch (Exception e) {
            // 旧版本存的纯文本日志：整块作为一条保留，不丢数据
            JSONArray arr = new JSONArray();
            JSONObject o = new JSONObject();
            try {
                o.put("time", "--");
                o.put("level", "INFO");
                o.put("tag", "legacy");
                o.put("msg", raw);
                arr.put(o);
            } catch (Exception ignored) { }
            return arr;
        }
    }
}
