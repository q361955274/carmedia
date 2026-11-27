package com.ecarx.carmedia;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 应用内日志抓取引擎。
 *
 * <p>本应用为系统签名(sharedUserId=android.uid.system)且声明了 READ_LOGS，因此可在应用内直接
 * 拉取 logcat。按三种模式筛选：{@link Mode#FULL} 全量 / {@link Mode#WIDGET} 小组件 /
 * {@link Mode#KEYMAP} 按键映射。实时输出到 UI 监听器，同时全量落盘到应用私有目录
 * {@code files/logs/live.log}，避免大日志漏记。</p>
 *
 * <p>导入按钮把 live.log 拷贝到 /sdcard/Download/carmedia_log_&lt;ts&gt;_&lt;mode&gt;.log 并触发
 * MediaScanner 扫描；清空按钮停止并删除 live.log、清空 UI 缓冲。</p>
 */
public class LogCaptureManager {

    public enum Mode {
        FULL("全量日志"),
        WIDGET("小组件日志"),
        KEYMAP("按键映射日志");

        public final String label;
        Mode(String label) { this.label = label; }
    }

 /** UI 事件回调（均在主线程）。 */
    public interface Listener {
 /** 一批日志行（可能多行，末尾无换行）。 */
        void onLineBatch(String batch);
        void onStatus(String status);
    }

    private static final String TAG = "LogCapture";
    private static final String LIVE_DIR = "logs";
    private static final String LIVE_NAME = "live.log";
 /** UI 内存缓冲上限（行数），超出丢最旧，避免 OOM/卡顿。 */
    private static final int MAX_UI_LINES = 6000;
 /** UI 刷新节流（ms），高负载日志洪峰时避免主线程每行刷新。 */
    private static final long UI_FLUSH_MS = 150L;
 /** 全量模式 UI 刷新节流（ms）更大：真车系统日志洪峰（fota_service 等 ~600+行/s）时更克制，防 WebView 渲染线程被刷死（Bug#78 点结束卡死）。 */
    private static final long UI_FLUSH_MS_FULL = 350L;
 /** 待推 UI 批的最大字符数；洪峰时丢旧保新，防止主线程被超大串刷死（磁盘 live.log 始终全量）。 */
    private static final int MAX_PENDING_CHARS = 96 * 1024;
 /** 每次推给 UI 的最大行数；洪峰时只推最新的一批，旧行由磁盘兜底。 */
    private static final int MAX_UI_BATCH_LINES = 300;
 /** 全量模式每次推给 UI 的最大行数（更小，进一步压 WebView 单批 DOM 量）。 */
    private static final int MAX_UI_BATCH_LINES_FULL = 120;
 /** 导出幂等窗口（ms）：同模式短时间重复点「导出」返回同一文件，防真车触屏连点重复生成日志（Bug#78）。 */
    private static final long EXPORT_DEBOUNCE_MS = 1500L;

 /** 快照缓冲（有界队列，O(1) 增删，洪峰不卡）。 */
    private final Deque<String> buffer = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
 /** 待推 UI 批（有界，按字符上限丢旧保新）。 */
    private final Deque<String> pendingLines = new ArrayDeque<>();
    private int pendingChars = 0;

    private volatile boolean running;
 /** 会话代号：stop/clear 后 +1，让已排队的 UI 批次作废，防止"点结束"后旧日志洪峰继续刷 WebView（Bug#78）。 */
    private volatile int session;
    private Thread thread;
    private Process process;
    private File liveFile;
    private volatile Mode mode = Mode.FULL;
    private volatile Listener listener;
 /** 当前模式的 UI 刷新间隔与单批行数（start 时按模式设定）。 */
    private long uiFlushMs = UI_FLUSH_MS;
    private int maxUiBatchLines = MAX_UI_BATCH_LINES;
 /** 上次导出文件（同模式短窗口内幂等，防重复导出生成多个日志）。 */
    private File lastExportFile;
    private long lastExportTs;
    private Mode lastExportMode;

 /** 启动抓取（幂等）。 */
    public synchronized void start(Context ctx, Mode m, Listener l) {
        if (running) {
            return;
        }
        this.mode = m;
        this.listener = l;
        // 新会话代号：作废上一轮排队/残留的 UI 批次
        this.session++;
        this.running = true;
        // 全量模式洪峰大：UI 推送更克制，防 WebView 渲染线程被刷死
        if (m == Mode.FULL) {
            this.uiFlushMs = UI_FLUSH_MS_FULL;
            this.maxUiBatchLines = MAX_UI_BATCH_LINES_FULL;
        } else {
            this.uiFlushMs = UI_FLUSH_MS;
            this.maxUiBatchLines = MAX_UI_BATCH_LINES;
        }
        ensureLiveFile(ctx, m);
        thread = new Thread(() -> runLoop(ctx, m), "LogCapture-" + m.name());
        thread.setDaemon(true);
        thread.start();
        main.postDelayed(uiFlushRunnable, uiFlushMs);
        postStatus("已开始：" + m.label + "（输出 → " + liveFile.getAbsolutePath() + "）");
    }

 /** 停止抓取并释放 logcat 子进程（幂等）。 */
    public synchronized void stop() {
        running = false;
        // 作废所有已排队/未执行的 UI 批次，点「结束」立即停流，不再把积压日志继续刷 WebView（Bug#78 卡死根因）
        session++;
        main.removeCallbacks(uiFlushRunnable);
        synchronized (pendingLines) {
            pendingLines.clear();
            pendingChars = 0;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
            process = null;
        }
        if (listener != null) {
            postStatus("已停止：" + mode.label + "（共 " + buffer.size() + " 行）");
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Mode getMode() {
        return mode;
    }

 /** 清空：停止、删除 live.log、清空内存缓冲、清系统 logcat 缓冲，并通知 UI 清屏。 */
    public synchronized void clear(Context ctx) {
        stop();
        buffer.clear();
        if (liveFile != null && liveFile.exists()) {
            liveFile.delete();
        }
        clearSystemBuffer();
        if (listener != null) {
            main.post(() -> listener.onStatus("已清空（面板 + 临时文件 + 系统日志缓冲），下轮从头抓"));
        }
    }

 /** 执行 logcat -c 清空系统日志缓冲，让下一轮抓取从干净起点开始。 */
    private void clearSystemBuffer() {
        try {
            Process p = new ProcessBuilder("logcat", "-b", "all", "-c").redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable ignored) {
            try {
                Process p2 = new ProcessBuilder("logcat", "-c").redirectErrorStream(true).start();
                p2.waitFor();
            } catch (Throwable ignored2) {
            }
        }
    }

 /** 导出：把 live.log 拷贝到 /sdcard/Download 并扫描媒体库，返回导出文件（失败返回 null）。同模式短窗口内重复调用幂等返回同一文件。 */
    public synchronized File export(Context ctx) {
        if (liveFile == null || !liveFile.exists()) {
            postStatus("无已抓取日志，先点击开始录制");
            return null;
        }
        // 真车触屏卡顿时常连点/重复触发：同模式、短窗口内直接返回上次文件，不再生成重复日志（Bug#78）
        long now = System.currentTimeMillis();
        if (lastExportFile != null && lastExportMode == mode && now - lastExportTs < EXPORT_DEBOUNCE_MS) {
            postStatus("已导出 → " + lastExportFile.getAbsolutePath());
            return lastExportFile;
        }
        try {
            File dir = new File("/sdcard/Download");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "carmedia_log_" + ts + "_" + mode.name().toLowerCase(Locale.US) + ".log");
            copyFile(liveFile, out);
            MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, null, null);
            lastExportFile = out;
            lastExportTs = now;
            lastExportMode = mode;
            postStatus("已导出 → " + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            postStatus("导出失败: " + t.getMessage());
            return null;
        }
    }

 /** 当前 UI 缓冲快照（用于外部展示）。 */
    public List<String> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    // ==================== 内部实现 ====================

 /** 简单流式拷贝（避免依赖 java.nio.file，兼容性最稳）。 */
    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private void ensureLiveFile(Context ctx, Mode m) {
        File dir = new File(ctx.getExternalFilesDir(null), LIVE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        liveFile = new File(dir, LIVE_NAME);
        try {
            if (liveFile.exists()) {
                liveFile.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void runLoop(Context ctx, Mode m) {
        // 提升为系统身份无关——本进程即 system uid，直接 logcat 即可读全部缓冲
        ProcessBuilder pb = new ProcessBuilder(buildCmd(m));
        pb.redirectErrorStream(true);
        try {
            process = pb.start();
        } catch (Throwable t) {
            Log.w(TAG, "start logcat failed: " + t.getMessage());
            postStatus("启动 logcat 失败: " + t.getMessage());
            running = false;
            return;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            FileOutputStream fos = new FileOutputStream(liveFile, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            while (running && (line = br.readLine()) != null) {
                synchronized (buffer) {
                    buffer.addLast(line);
                    while (buffer.size() > MAX_UI_LINES) {
                        buffer.removeFirst();
                    }
                }
                osw.write(line);
                osw.write('\n');
                appendBatch(line);
            }
            try {
                osw.flush();
            } catch (Throwable ignored) {
            }
            try {
                osw.close();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "LogCapture loop error: " + t.getMessage());
                postStatus("抓取异常: " + t.getMessage());
            }
        } finally {
            running = false;
            main.removeCallbacks(uiFlushRunnable);
            flushBatchNow();
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
                process = null;
            }
        }
    }

    private List<String> buildCmd(Mode m) {
        List<String> cmd = new ArrayList<>();
        cmd.add("logcat");
        cmd.add("-v");
        cmd.add("threadtime");
        switch (m) {
            case FULL:
                // 全量：抓取所有缓冲（含 events/radio），磁盘 live.log 始终全量落盘；
                // 防卡死靠 UI 推流限流（pending 有界+每批限行），不靠砍缓冲
                cmd.add("-b");
                cmd.add("all");
                break;
            case WIDGET:
                cmd.add("-b");
                cmd.add("main");
                cmd.add("-b");
                cmd.add("system");
                cmd.add("-s");
                cmd.add("CarMediaWidget:V");
                cmd.add("CarMediaSkyWidget:V");
                cmd.add("CardActionReceiver:V");
                cmd.add("AppWidgetHostView:V");
                cmd.add("AppWidgetHost:V");
                cmd.add("AppWidgetServiceImpl:V");
                cmd.add("launcher3-LauncherAppWidgetHostView:V");
                cmd.add("launcher3-Launcher.Model:V");
                cmd.add("launcher3-LauncherImpl:V");
                break;
            case KEYMAP:
            default:
                cmd.add("-b");
                cmd.add("main");
                cmd.add("-b");
                cmd.add("system");
                cmd.add("-s");
                cmd.add("CarMedia:V");
                cmd.add("InputManager:V");
                cmd.add("CAR.INPUT:V");
                cmd.add("MediaSessionService:V");
                break;
        }
        return cmd;
    }

    // ---- UI 批量刷新（主线程节流，周期性自投递） ----

 /** 每次刷新后若仍在录制则再次延时投递，实现 150ms 周期性实时推送。 */
    private final Runnable uiFlushRunnable = new Runnable() {
        @Override
        public void run() {
            flushBatchNow();
            if (running) {
                main.postDelayed(this, uiFlushMs);
            }
        }
    };

    private synchronized void appendBatch(String line) {
        pendingLines.addLast(line);
        pendingChars += line.length() + 1;
        // 洪峰：超出字符上限丢最旧，保证 UI 每次收到的批有界
        while (pendingChars > MAX_PENDING_CHARS && !pendingLines.isEmpty()) {
            pendingChars -= pendingLines.removeFirst().length() + 1;
        }
    }

    private synchronized void flushBatchNow() {
        if (pendingLines.isEmpty()) {
            return;
        }
        // 已停止：直接丢弃积压，不再推 UI（点「结束」后旧日志洪峰立即停，不刷 WebView）
        if (!running) {
            pendingLines.clear();
            pendingChars = 0;
            return;
        }
        final int s = session;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        // 每次最多推 maxUiBatchLines 行（洪峰丢旧保新，磁盘已全量；FULL 模式单批更小）
        while (!pendingLines.isEmpty() && n < maxUiBatchLines) {
            String l = pendingLines.pollFirst();
            pendingChars -= l.length() + 1;
            sb.append(l).append('\n');
            n++;
        }
        // 若仍积压（洪峰远大于消费速度），清掉旧的保持 UI 响应
        if (!pendingLines.isEmpty()) {
            pendingLines.clear();
            pendingChars = 0;
        }
        final String batch = sb.toString();
        if (listener != null) {
            // 会话作废检查：若 stop/clear 已发生，这批发出去的批次直接丢弃
            main.post(() -> { if (s == session) { listener.onLineBatch(batch); } });
        }
    }

    private void postStatus(String s) {
        Log.i(TAG, s);
        if (listener != null) {
            main.post(() -> listener.onStatus(s));
        }
    }
}
