package com.ecarx.carmedia;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ui.html 热更新：GitHub 私有仓库
 * - 启动时后台检查最新 commit sha，与本地记录对比，有新版本则下载替换
 * - WebView 优先加载 filesDir 的 ui.html（热更新版），无则回退 assets 内置版
 * - 下载走 raw.githubusercontent.com + Authorization header（私有仓库需要 Token）
 * - 仓库归属与令牌由构建注入 BuildConfig；缺省占位值下不发起热更新请求
 */
public class UiHotUpdate {
    private static final String TAG = "UiHotUpdate";

    // ===== GitHub 配置（私有仓库；真实值由构建注入，缺省为占位符）=====
    private static final String GITHUB_TOKEN = BuildConfig.GITHUB_TOKEN;
    private static final String REPO_OWNER = BuildConfig.GITHUB_OWNER;
    private static final String REPO_NAME = BuildConfig.GITHUB_REPO;
 /** 远端文件路径（仓库内，与工程实际目录一致：app/src/main/assets/ui.html） */
    private static final String FILE_PATH = "app/src/main/assets/ui.html";
 /** 本地缓存路径（filesDir 下，WebView 实际加载的文件） */
    private static final String LOCAL_FILE = "ui.html";

 /** 远端最新 commit sha（版本对比锚点） */
    private static final String API_LATEST_COMMIT =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/commits?per_page=1";
 /** 远端文件 raw 下载（带 Authorization 访问私有仓库），国内常超时，作为回退 */
    private static final String RAW_URL =
            "https://raw.githubusercontent.com/" + REPO_OWNER + "/" + REPO_NAME + "/main/" + FILE_PATH;
 /** GitHub Contents API（走 api.github.com，与获取 sha 同域名，国内更稳定），返回 base64 内容 */
    private static final String CONTENTS_API =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/contents/" + FILE_PATH + "?ref=main";

    private static final String PREF = "carmedia_prefs";
    private static final String KEY_SHA = "ui_remote_sha";

    public interface Callback {
 /** @param updated true=已下载新版本 false=无更新或失败 */
        void onResult(boolean updated, String msg);
    }

 /** 启动时调用：后台线程检查更新并下载 */
    public static void checkAndUpdate(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 缺省占位值 / 未配置：不发起任何网络请求，直接按"无更新"回退内置 ui.html
                if (isPlaceholder(REPO_OWNER) || isPlaceholder(REPO_NAME) || isPlaceholder(GITHUB_TOKEN)) {
                    Log.w(TAG, "github config not set (placeholder), skip hot update");
                    if (cb != null) cb.onResult(false, "未配置热更新");
                    return;
                }
                try {
                    String remoteSha = fetchLatestCommitSha();
                    if (remoteSha == null || remoteSha.isEmpty()) {
                        if (cb != null) cb.onResult(false, "获取版本失败");
                        return;
                    }
                    String localSha = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .getString(KEY_SHA, "");
                    if (remoteSha.equals(localSha)) {
                        if (cb != null) cb.onResult(false, "已是最新");
                        return;
                    }
                    // 有新版本：下载（优先 Contents API 走 api.github.com，失败回退 raw.githubusercontent.com）
                    byte[] data = downloadViaContentsApi();
                    if (data == null || data.length < 1000) {
                        Log.w(TAG, "contents api download failed or too small, fallback to raw");
                        data = downloadRaw();
                    }
                    if (data == null || data.length < 1000) {
                        if (cb != null) cb.onResult(false, "下载失败");
                        return;
                    }
                    // ★ 结构校验：仓库 ui.html 若与工程不同步（旧版）或下载被截断/损坏，
                    // 覆盖到车机会导致 UI 渲染不全、版本号显示异常。校验不通过直接丢弃，
                    // 保留现有 UI（车机继续用内置版或上一版热更新文件）。
                    if (!looksLikeCarMediaUi(data)) {
                        Log.w(TAG, "downloaded ui.html failed structural check, discard (bytes=" + data.length + ")");
                        if (cb != null) cb.onResult(false, "下载内容校验失败，保留现有 UI");
                        return;
                    }
                    File out = new File(ctx.getFilesDir(), LOCAL_FILE);
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(data);
                    fos.flush();
                    fos.close();
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString(KEY_SHA, remoteSha).apply();
                    Log.i(TAG, "ui.html updated, sha=" + remoteSha + ", bytes=" + data.length);
                    if (cb != null) cb.onResult(true, "已更新 UI v" + data.length);
                } catch (Exception e) {
                    Log.w(TAG, "checkAndUpdate error", e);
                    if (cb != null) cb.onResult(false, "检查失败");
                }
            }
        }).start();
    }

 /** 获取最新 commit sha（GitHub API） */
    private static String fetchLatestCommitSha() throws Exception {
        HttpURLConnection conn = openConn(API_LATEST_COMMIT);
        try {
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            // 解析 JSON: [{"sha":"xxx",...}]
            String json = sb.toString();
            int i = json.indexOf("\"sha\"");
            if (i < 0) return null;
            i = json.indexOf('"', i + 6);
            int j = json.indexOf('"', i + 1);
            return json.substring(i + 1, j);
        } finally {
            conn.disconnect();
        }
    }

 /** 通过 GitHub Contents API 下载文件（走 api.github.com，国内比 raw.githubusercontent.com 稳定） */
    private static byte[] downloadViaContentsApi() throws Exception {
        HttpURLConnection conn = openConn(CONTENTS_API);
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "contents api response code: " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String json = sb.toString();
            // 解析 content 字段（base64 编码）
            int i = json.indexOf("\"content\"");
            if (i < 0) return null;
            i = json.indexOf('"', i + 9);
            int j = json.indexOf('"', i + 1);
            if (i < 0 || j < 0) return null;
            String base64 = json.substring(i + 1, j);
            // GitHub 返回的 base64 含 \n 转义，需清理
            base64 = base64.replace("\\n", "").replace("\n", "").replace("\r", "");
            return android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        } finally {
            conn.disconnect();
        }
    }

 /** 下载 ui.html 原文（raw.githubusercontent.com，国内常超时，作为回退） */
    private static byte[] downloadRaw() throws Exception {
        HttpURLConnection conn = openConn(RAW_URL);
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.raw");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConn(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
        conn.setRequestProperty("User-Agent", "carmedia-hotupdate");
        return conn;
    }

 /** 判定配置值是否为空或缺省占位符（未真正配置） */
    private static boolean isPlaceholder(String value) {
        return value == null || value.isEmpty() || value.startsWith("YOUR_");
    }

 /** WebView 应加载的文件：优先加载 filesDir 热更新版，无则回退 assets 内置版 */
    public static String resolveUiUrl(Context ctx) {
        // 恢复热更新：优先加载 filesDir/ui.html（checkAndUpdate 下载的最新版），
        // 无热更新文件时回退 assets 内置版。注意：仓库内 ui.html 必须与工程同步（v1.4.8+），
        // 否则车机启动会被拉成旧 UI。
        // 加固：
        // 1) 加载前校验结构完整性——历史下载的旧版/截断文件不允许进入渲染；
        // 2) 热更新文件 UI_VERSION 若早于内置版（仓库未同步/拉错版本），直接回退内置版，
        // 避免"热更新后渲染不全/版本号变日期"。
        File f = new File(ctx.getFilesDir(), LOCAL_FILE);
        if (f.exists() && f.length() > 1000) {
            byte[] cached = readFile(f);
            if (looksLikeCarMediaUi(cached)) {
                String cachedVer = extractUiVersion(new String(cached, StandardCharsets.UTF_8));
                String builtinVer = extractUiVersion(readBuiltinAsset(ctx));
                if (!cachedVer.isEmpty() && !builtinVer.isEmpty()
                        && compareUiVersion(cachedVer, builtinVer) < 0) {
                    Log.w(TAG, "cached ui.html version " + cachedVer + " older than built-in "
                            + builtinVer + " (仓库未同步?), fallback to built-in asset");
                    return "file:///android_asset/ui.html";
                }
                return "file://" + f.getAbsolutePath();
            }
            Log.w(TAG, "cached ui.html failed structural check, fallback to built-in asset");
        }
        return "file:///android_asset/ui.html";
    }

 /** 读取内置 assets/ui.html 内容；失败返回空串 */
    private static String readBuiltinAsset(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("ui.html");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "read builtin ui.html failed: " + t.getMessage());
            return "";
        }
    }

 /** 提取 ui.html 中的 UI_VERSION 日期字符串（如 "2026.09.15"）；失败返回 "" */
    private static String extractUiVersion(String s) {
        try {
            int i = s.indexOf("var UI_VERSION");
            if (i < 0) return "";
            i = s.indexOf('"', i);
            if (i < 0) return "";
            int j = s.indexOf('"', i + 1);
            if (j < 0) return "";
            return s.substring(i + 1, j);
        } catch (Throwable t) {
            return "";
        }
    }

 /** 日期版本比较（按 YYYYMMDD 数值）；任一方无法解析时返回 0（不阻断加载） */
    private static int compareUiVersion(String a, String b) {
        try {
            String na = a.replace(".", "");
            String nb = b.replace(".", "");
            if (na.isEmpty() || nb.isEmpty()) return 0;
            return Integer.parseInt(na) - Integer.parseInt(nb);
        } catch (Throwable t) {
            return 0;
        }
    }

 /**
 * 结构完整性校验：下载/缓存的 ui.html 必须包含 CarMedia UI 的关键结构
 * （版本元素、状态渲染函数、JS 桥对象、boot 入口），防止旧版/截断文件覆盖渲染。
 */
    private static boolean looksLikeCarMediaUi(byte[] data) {
        try {
            String s = new String(data, StandardCharsets.UTF_8);
            return s.contains("authorVer") && s.contains("function renderState")
                    && s.contains("CarMediaUI") && s.contains("function boot");
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "read cached ui.html failed: " + t.getMessage());
            return new byte[0];
        }
    }
}
