package com.family.print;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 打印服务网络客户端（v1.82 从 MainActivity 抽出，纯网络层，无 UI 依赖）。
 * 职责：URL 组装、认证头（X-Print-Token）、multipart 编码、
 * 打印 / 预览 / 重打请求的提交与响应读取。
 * 超时常量与 MainActivity 原值一致（2026-10-09 修复沿用）。
 */
public class PrintApiClient {
    private static final String TAG = "PrintApi";

    public static final int TIMEOUT_CONNECT = 10000;
    public static final int TIMEOUT_READ = 15000;

    private final SharedPreferences prefs;

    public PrintApiClient(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public String getPrintUrl() {
        return prefs.getString("print_url", "http://192.0.2.100:8765/index.php");
    }

    public String getPreviewUrl() {
        return getPrintUrl().replace("/index.php", "/preview.php");
    }

    /** 服务端访问令牌（设置页填写），与服务端 /etc/print-service/token 一致 */
    public String getAuthToken() {
        return prefs.getString("auth_token", "");
    }

    /** 给连接加上令牌头。服务端无令牌会返回 401。 */
    public void applyAuth(HttpURLConnection conn) {
        String token = getAuthToken();
        if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Print-Token", token);
    }

    /** 解析服务端 JSON 判定成功，不再用字符串 contains（服务端改格式就会误判） */
    public boolean isSuccessResponse(String body) {
        if (body == null) return false;
        try {
            return new JSONObject(body).optBoolean("success", false);
        } catch (Exception e) {
            return body.contains("\"success\":true");
        }
    }

    /** 从服务端错误响应中提取 error 字段 */
    public static String extractError(String body) {
        if (body == null) return "服务无响应";
        try {
            return new JSONObject(body).optString("error", "未知错误");
        } catch (Exception e) {
            return "响应异常";
        }
    }

    public String readResponse(HttpURLConnection conn) throws Exception {
        final int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    /** 创建multipart连接并写入文件，返回已写入文件部分的DataOutputStream（调用方继续写其他字段） */
    public HttpURLConnection createMultipartConnection(String urlStr, String boundary) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_CONNECT);
        conn.setReadTimeout(TIMEOUT_READ);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
        applyAuth(conn);
        return conn;
    }

    /** 向multipart流写入一个文件字段 */
    public void writeFileField(DataOutputStream dos, String boundary, String fieldName, String fileName, File file) throws Exception {
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        dos.writeBytes(twoHyphens + boundary + lineEnd);
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\";filename=\"" + fileName + "\"" + lineEnd);
        dos.writeBytes(lineEnd);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) dos.write(buffer, 0, len);
        }
        dos.writeBytes(lineEnd);
    }

    /** 向multipart流写入一个文本字段 */
    public void writeTextField(DataOutputStream dos, String boundary, String fieldName, String value) throws Exception {
        String lineEnd = "\r\n";
        String twoHyphens = "--";
        dos.writeBytes(twoHyphens + boundary + lineEnd);
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"" + lineEnd);
        dos.writeBytes(lineEnd);
        dos.writeBytes(value);
        dos.writeBytes(lineEnd);
    }

    /** 结束multipart流 */
    public void finishMultipart(DataOutputStream dos, String boundary) throws Exception {
        dos.writeBytes("--" + boundary + "--\r\n");
        dos.flush();
    }

    public String uploadForPreview(File file, String fileName) throws Exception {
        String boundary = "*****" + System.currentTimeMillis();
        HttpURLConnection conn = createMultipartConnection(getPreviewUrl(), boundary);
        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
            writeFileField(dos, boundary, "file", fileName, file);
            finishMultipart(dos, boundary);
        }
        return readResponse(conn);
    }

    /**
     * 按 pdf_id 提交打印（重打 / PDF 直印）。
     * @param jobName 队列显示名（原文件名），服务端用于 lp -t 与队列映射，可为空
     */
    public String printWithPdfId(String pdfId, String pageRanges, int orientation, int copies,
                                 String paper, int nup, boolean eco, boolean duplex,
                                 String jobName) throws Exception {
        URL url = new URL(getPrintUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_CONNECT);
        conn.setReadTimeout(TIMEOUT_READ);
        conn.setDoInput(true); conn.setDoOutput(true); conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        applyAuth(conn);
        String params = "pdf_id=" + URLEncoder.encode(pdfId, "UTF-8");
        if (pageRanges != null && !pageRanges.isEmpty()) params += "&page_ranges=" + URLEncoder.encode(pageRanges, "UTF-8");
        params += "&orientation=" + orientation;
        params += "&copies=" + copies;
        params += "&paper=" + paper;
        if (nup > 1) params += "&nup=" + nup;
        if (eco) params += "&eco=1";
        if (duplex) params += "&duplex=1";
        if (jobName != null && !jobName.isEmpty()) params += "&job_name=" + URLEncoder.encode(jobName, "UTF-8");
        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
            dos.writeBytes(params);
            dos.flush();
        }
        return readResponse(conn);
    }

    /**
     * 上传图片并提交打印。
     * @param jobName 队列显示名，这里即上传的原文件名本身
     */
    public String uploadAndPrintImage(String localPath, String fileName, String pageRanges, int orientation,
                                      int copies, String paper, int nup, boolean eco, boolean duplex,
                                      String jobName) throws Exception {
        String boundary = "*****" + System.currentTimeMillis();
        HttpURLConnection conn = createMultipartConnection(getPrintUrl(), boundary);
        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
            writeFileField(dos, boundary, "file", fileName, new File(localPath));
            writeTextField(dos, boundary, "page_ranges", pageRanges);
            writeTextField(dos, boundary, "orientation", String.valueOf(orientation));
            writeTextField(dos, boundary, "copies", String.valueOf(copies));
            writeTextField(dos, boundary, "paper", paper);
            if (nup > 1) writeTextField(dos, boundary, "nup", String.valueOf(nup));
            if (eco) writeTextField(dos, boundary, "eco", "1");
            if (duplex) writeTextField(dos, boundary, "duplex", "1");
            if (jobName != null && !jobName.isEmpty()) writeTextField(dos, boundary, "job_name", jobName);
            finishMultipart(dos, boundary);
        }
        return readResponse(conn);
    }
}
