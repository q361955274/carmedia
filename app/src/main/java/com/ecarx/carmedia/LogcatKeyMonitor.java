package com.ecarx.carmedia;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方向盘按键 logcat 监听器（全按键通道）。
 *
 * <p><b>背景（音量乱跳修复）</b>：原 KeyMonitor 通过
 * IInputManager.requestKeysInterception 独占拦截方向盘按键，会导致车机输入服务
 * ecarx.xsf.inputservice 的 injectInputEvent 被拦截后状态机死循环，疯狂调
 * setStreamVolume 造成音量乱跳（暂停也跳，只能重启车机）。
 * 改为完全靠 logcat 监听所有方向盘按键，与 OK 键(306) 同通道，从根上切断死循环。</p>
 *
 * <p> 星瑞兼容：同时监听 InputManager tag（IMS reportKeyToAdaptApi）和 CAR.INPUT tag（shouldCallback）。星瑞无 CAR.INPUT 日志，方控键仅在 InputManager tag；缤越两 tag 均有。CAR.INPUT 仅放行 OK 键(306)，方控键统一由 InputManager tag 解析，避免双 tag 同键导致连切2首。</p>
 * <p>监听日志格式：
 * <ol>
 * <li>方向盘键（InputManager tag）：{@code IMS reportKeyToAdaptApi : <code> press|release}
 * <br>真车实测：348=模式/304=上一曲/305=下一曲/300=静音/301=音量减/302=音量加</li>
 * <li>OK 键（CAR.INPUT tag）：{@code shouldCallback: code=<code> action=1}
 * <br>OK 键(306) 是 HAL 层键码，不注入 Android input 系统，只能靠 logcat。</li>
 * </ol></p>
 *
 * <p>同时响应按下（press / action=1）和抬起（release / action=0）事件。
 * 按下经 80ms 去重后回调 {@link Listener#onKeyDown(int)}；抬起不去重直接透传
 * {@link Listener#onKeyUp(int)}（用于长按检测，抬起回调为新增）。
 * 由调用方（CarMediaService）注入 KeyMonitor 走单击/双击/长按判定与映射。</p>
 *
 * <p><b>Bug#51 修复（连切2首）</b>：CAR.INPUT tag 的
 * {@code shouldCallback: code=XXX action=1} 日志不仅包含 OK 键(306)，也包含
 * 304/305/348/300 等方向盘键（这些键码都是 CAR.INPUT 层定义的私有键码）。
 * 原 OK_KEY_PATTERN 不限制键码，导致同一物理按键被 InputManager tag 和
 * CAR.INPUT tag 各解析一次 → 两次 onKeyDown → KeyMonitor 对只有单击映射的
 * 304/305 直接执行两次 onKeyAction → 连切2首。修复：CAR.INPUT tag 只接受
 * KEY_OK(306)，其他方向盘键一律由 InputManager tag 处理。</p>
 */
public class LogcatKeyMonitor {

    private static final String TAG = "CarMedia";


 /** 方控键按下日志：CAR.INPUT tag，格式 "shouldCallback: code=XXX action=1"（改为接受所有键码，InputManager tag会触发音量bug） */
    private static final Pattern OK_KEY_PATTERN =
            Pattern.compile("shouldCallback:\\s*code\\s*=\\s*(\\d+)\\s+action\\s*=\\s*1",
                    Pattern.CASE_INSENSITIVE);

 /** 方控键抬起日志：CAR.INPUT tag，格式 "shouldCallback: code=XXX action=0" */
    private static final Pattern OK_KEY_RELEASE_PATTERN =
            Pattern.compile("shouldCallback:\\s*code\\s*=\\s*(\\d+)\\s+action\\s*=\\s*0",
                    Pattern.CASE_INSENSITIVE);

 /** 方控键按下日志：InputManager tag，格式 "IMS reportKeyToAdaptApi : XXX press"（星瑞/缤越均有） */
    private static final Pattern INPUT_MANAGER_DOWN_PATTERN =
            Pattern.compile("IMS\\s+reportKeyToAdaptApi\\s*:\\s*(\\d+)\\s+press",
                    Pattern.CASE_INSENSITIVE);

 /** 方控键抬起日志：InputManager tag，格式 "IMS reportKeyToAdaptApi : XXX release" */
    private static final Pattern INPUT_MANAGER_UP_PATTERN =
            Pattern.compile("IMS\\s+reportKeyToAdaptApi\\s*:\\s*(\\d+)\\s+release",
                    Pattern.CASE_INSENSITIVE);

 /**
 * 系统媒体键路由日志（MediaSessionService tag，Bug#53）：
 * "Sending KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_MEDIA_NEXT, ... } to <pkg>/<component> (userId=0)"
 * 这是"系统注入 A 是否真正直达某 app"的直接证据——比 dumpsys media_session 的
 * "Media button session is <pkg>" 判断可靠：车机版 app（QQ/酷我等）可能占据
 * media button session 位置，但系统媒体键被车机媒体中心拦截、不注入 MediaSessionService，
 * dumpsys 会误判。只匹配 ACTION_DOWN（ACTION_UP 是同一按键的抬起，重复）。
 */
    private static final Pattern MEDIA_KEY_ROUTE_PATTERN =
            Pattern.compile("Sending\\s+KeyEvent\\s*\\{.*?action=ACTION_DOWN.*?keyCode=(KEYCODE_MEDIA_NEXT|KEYCODE_MEDIA_PREVIOUS).*?\\}\\s*to\\s+([\\w.]+)/",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

 /**
 * OK 键码（HAL 层私有键，不注入 Android input 系统，只能靠 CAR.INPUT tag 监听）。
 * CAR.INPUT tag 仅放行 OK 键(306)，方控键(300/304/305/348等)
 * 统一由 InputManager tag（IMS reportKeyToAdaptApi）解析，避免双 tag 同键连切2首。
 */
    private static final int KEY_OK = 306;

 /**
 * 每个键码的最小触发间隔（去重，防 HAL/InputManager 日志重复打印）。
 * 设为 80ms：远小于 400ms 双击窗口，不影响双击判定；又能过滤掉同一次按键
 * 可能产生的重复日志行。
 */
    private static final long MIN_TRIGGER_INTERVAL_MS = 80L;

    public interface Listener {
 /** 按键按下（press / action=1），经 80ms 去重后回调。
 * physTimeMs：该次按压在 InputManager 日志行中的物理时刻（epoch ms）。
 * logcat 解析可能延迟数百 ms~1s+（车机高负载），物理时刻才是去重窗口的可靠基准，
 * CarMedia 据此区分"本次按压的副本"与"用户真实再次按压"。解析失败时回退为解析时刻。 */
        void onKeyDown(int keyCode, long physTimeMs);

 /** 按键抬起（release / action=0），不去重直接透传（新增长按检测） */
        void onKeyUp(int keyCode);

 /** 系统把媒体键(KEYCODE_MEDIA_NEXT/PREVIOUS)实际路由到 pkg（Bug#53，系统注入 A 的直接证据） */
        void onMediaButtonRouted(int keyCode, String pkg);
    }

    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private Process process;
 /** 每个键码上次触发时间（去重用） */
    private final Map<Integer, Long> lastTriggerAt = new HashMap<>();

    public LogcatKeyMonitor(Listener listener) {
        this.listener = listener;
    }

 /** 启动监听线程（幂等）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "LogcatKeyMonitor");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "LogcatKeyMonitor started (all wheel keys via logcat)");
    }

 /** 停止监听并销毁子进程（幂等）。 */
    public synchronized void stop() {
        running = false;
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
        Log.i(TAG, "LogcatKeyMonitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            try {
                // 同时过滤 InputManager（方向盘键）、CAR.INPUT（OK 键）、MediaSessionService
                // （系统媒体键路由，Bug#53）三个 tag，避免不过滤 tag 导致日志量巨大影响性能。
                // -T 1: 跳过历史缓冲区,从最后1行之后开始实时输出。
                // 不加 -T 时 logcat 进程先dump缓冲区历史日志(车机上可能几秒),
                // 期间实时按键日志读不到 → 首次按键 onKeyAction 不触发 → mark 没打 →
                // MDC 转发 onNextCommand 不被去重 → 连切2首。
                // -b all 会把 main buffer(app 日志,含 CarMedia 自身刷屏)全部拖进
                // 管道,-s 只是接收后丢弃。真车日志实测:车机高负载(如 fota_service 异常刷屏)时
                // logcat 全量 1.2s 达 1600+ 行,logd 队列积压 → InputManager 按键行解析延迟 1245ms
                // → 连切2首。目标 tag(InputManager/CAR.INPUT/MediaSessionService)全是系统进程
                // (system_server/车机系统服务)→ 大部分在 system buffer。
                // 修复:改回同时读 system + main 两个 buffer——部分车型(
                // 1.4.9 用 -b all 能录静音键 300/OK 键 306,1.5.2 用 -b system 录不到)的方向盘
                // 按键日志落在 main buffer(同一 InputManager/CAR.INPUT tag,车机系统日志缓冲
                // 配置不同; 真车日志确认按键行全在 system 段,begSys=506)。
                // 不读 radio/events/crash(洪水仍比 -b all 少一半);高负载解析延迟导致的连切2首
                // 已由 物理时刻去重补丁(wasThisPhysicalPressHandledByLogcat)兜底。
                Process p = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-b", "system", "-b", "main", "-T", "1", "-s",
                                "InputManager:V", "CAR.INPUT:V", "MediaSessionService:V"});
                synchronized (this) {
                    process = p;
                }
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = br.readLine()) != null) {
                    // Bug#53:系统媒体键路由日志(系统注入 A 直达某 app 的直接证据)——优先解析
                    // contains 预判先于 DOTALL 正则,绝大多数行直接跳过(降 CPU)
                    if (line.contains("Sending KeyEvent")) {
                    Matcher rm = MEDIA_KEY_ROUTE_PATTERN.matcher(line);
                    if (rm.find()) {
                        String kc = rm.group(1);
                        String pkg = rm.group(2);
                        int keyCode = "KEYCODE_MEDIA_NEXT".equalsIgnoreCase(kc)
                                ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                                : android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                        Log.i(TAG, "Media key routed: " + kc + " -> " + pkg);
                        if (listener != null) {
                            try {
                                listener.onMediaButtonRouted(keyCode, pkg);
                            } catch (Throwable t) {
                                Log.w(TAG, "onMediaButtonRouted error: " + t.getMessage());
                            }
                        }
                        continue;
                    }
                    } // end contains("Sending KeyEvent")
                    int keyDown = parseKeyDown(line);
                    if (keyDown > 0) {
                        onKeyDownLine(keyDown, line);
                        continue;
                    }
                    int keyUp = parseKeyUp(line);
                    if (keyUp > 0) {
                        onKeyUpLine(keyUp, line);
                    }
                }
                // 流结束（logcat 被杀/重启）：等待后重连
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "LogcatKeyMonitor loop error: " + t.getMessage());
                if (!running) {
                    break;
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

 /**
 * 解析一行日志，返回按下的键码；非按下事件或不匹配返回 0。
 */
    private int parseKeyDown(String line) {
        // 星瑞兼容：CAR.INPUT(shouldCallback) 仅接受 OK 键(306)，
        // 方控键统一由 InputManager tag(IMS reportKeyToAdaptApi) 解析。
        // 星瑞无 CAR.INPUT 日志，仅 InputManager 有；缤越两 tag 均有，按此分流避免双发连切2首。
        // contains 预判先于正则,降每行正则成本
        if (line.contains("shouldCallback")) {
        Matcher m = OK_KEY_PATTERN.matcher(line);
        if (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1));
                if (code == KEY_OK) {
                    return code; // CAR.INPUT 只放行 OK 键
                }
            } catch (NumberFormatException ignored) {
            }
        }
        }
        if (line.contains("reportKeyToAdaptApi")) {
        Matcher im = INPUT_MANAGER_DOWN_PATTERN.matcher(line);
        if (im.find()) {
            try {
                return Integer.parseInt(im.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        }
        return 0;
    }

 /**
 * 解析一行日志，返回抬起的键码；非抬起事件或不匹配返回 0。
 * 新增：用于长按检测（KeyMonitor.handleExternalKeyUp）。
 */
    private int parseKeyUp(String line) {
        // 星瑞兼容：CAR.INPUT 仅接受 OK 键(306)，方控键走 InputManager tag
        // contains 预判先于正则,降每行正则成本
        if (line.contains("shouldCallback")) {
        Matcher m = OK_KEY_RELEASE_PATTERN.matcher(line);
        if (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1));
                if (code == KEY_OK) {
                    return code;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        }
        if (line.contains("reportKeyToAdaptApi")) {
        Matcher im = INPUT_MANAGER_UP_PATTERN.matcher(line);
        if (im.find()) {
            try {
                return Integer.parseInt(im.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        }
        return 0;
    }

 /** logcat 行首时间戳，格式 "MM-dd HH:mm:ss.SSS"（InputManager/CAR.INPUT 均带标准前缀）。 */
    private static final Pattern LINE_TS_PATTERN =
            Pattern.compile("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})");

 /** 解析日志行时间戳为当天 epoch ms（物理按压时刻）；解析失败返回 fallback。 */
    private long parsePhysTimeMs(String line, long fallback) {
        Matcher m = LINE_TS_PATTERN.matcher(line);
        if (!m.find()) {
            return fallback;
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime t = LocalDateTime.of(
                    today.getYear(),
                    Integer.parseInt(m.group(1)), // 月
                    Integer.parseInt(m.group(2)), // 日
                    Integer.parseInt(m.group(3)), // 时
                    Integer.parseInt(m.group(4)), // 分
                    Integer.parseInt(m.group(5)), // 秒
                    Integer.parseInt(m.group(6)) * 1_000_000); // 毫秒→纳秒
            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return fallback;
        }
    }

 /** 按下事件去重 + 透传回调（80ms 内同键只触发一次）。 */
    private void onKeyDownLine(int keyCode, String line) {
        long now = System.currentTimeMillis();
        Long last = lastTriggerAt.get(keyCode);
        if (last != null && now - last < MIN_TRIGGER_INTERVAL_MS) {
            return;
        }
        lastTriggerAt.put(keyCode, now);
        long phys = parsePhysTimeMs(line, now);
        Log.i(TAG, "Wheel key DOWN " + keyCode + " from logcat: " + line.trim());
        if (listener != null) {
            try {
                listener.onKeyDown(keyCode, phys);
            } catch (Throwable t) {
                Log.w(TAG, "onKeyDown error: " + t.getMessage());
            }
        }
    }

 /** 抬起事件直接透传（不去重，供长按检测）。 */
    private void onKeyUpLine(int keyCode, String line) {
        Log.i(TAG, "Wheel key UP " + keyCode + " from logcat: " + line.trim());
        if (listener != null) {
            try {
                listener.onKeyUp(keyCode);
            } catch (Throwable t) {
                Log.w(TAG, "onKeyUp error: " + t.getMessage());
            }
        }
    }
}
