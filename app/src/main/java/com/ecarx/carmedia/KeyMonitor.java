package com.ecarx.carmedia;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 方向盘按键监听（纯 logcat 通道，支持动态按键映射）。
 * <p>
 * 按键事件由 LogcatKeyMonitor 从 logcat（InputManager tag + CAR.INPUT tag）解析后，
 * 通过 handleExternalKeyDown/Up 注入本类，走统一的单击/双击/长按判定与动作映射。
 * 反射 requestKeysInterception 方案因导致车机输入服务死循环音量乱跳已废弃。
 * <p>
 * 按键 -> 动作映射由调用方（CarMediaService）从 SharedPreferences 读取后传入，
 * 支持自定义任意按键码映射到任意动作；录制模式下按键只上报给 recorder。
 */
public class KeyMonitor {

    // 双击判定窗口（两次 keyDown 间隔小于此值视为双击）
    private static final long DOUBLE_CLICK_WINDOW_MS = 400L;

    // 默认物理键码（改回实车环境验证后的键码）：实车环境验证方向盘发的是 348/304/305/300，
    // 五位码 200110/200088/200087/200164 在该车不触发。
    // 348=模式键/304=上一曲/305=下一曲/300=静音（与之前旧默认一致）
    public static final int KEY_MODE = 348;
    public static final int KEY_PREV = 304;
    public static final int KEY_NEXT = 305;
    public static final int KEY_MUTE = 300;
    public static final int KEY_OK = 306;
    public static final int KEY_PLAYPAUSE = 200085;
    public static final int KEY_OLD_PREV = 200088;
    public static final int KEY_OLD_NEXT = 200087;
    public static final int KEY_OLD_MUTE = 200164;

    // 动作常量
    public static final String ACTION_MODE = "mode";       // 打开主界面（双击打开播放源）
    public static final String ACTION_HOME = "home";       // 打开主界面
    public static final String ACTION_PREV = "prev";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_MUTE = "mute";
    public static final String ACTION_PLAYPAUSE = "playpause";
    public static final String ACTION_SOURCE = "source";   // 打开当前播放源 App

    private static final String TAG = "CarMedia";

    private final Context context;
    private final Callback callback;

    private Map<String, String> actions = new HashMap<>();
    private KeyRecorder pendingRecorder;

    // 双击检测状态：keyCode -> 上次按下时间
    private final Map<Integer, Long> lastKeyDownTime = new HashMap<>();
    // 双击窗口内等待的单击任务（keyCode -> Runnable，可被双击取消）
    private final Map<Integer, Runnable> pendingSingleClick = new HashMap<>();
    private final Map<Integer, Boolean> longPressed = new HashMap<>();
    private final Map<Integer, Boolean> keyDownState = new HashMap<>();
    private final Map<Integer, Boolean> pendingSingleOnUp = new HashMap<>();
    private final Map<Integer, Runnable> longPressTasks = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long LONG_PRESS_THRESHOLD_MS = 600L;

    public KeyMonitor(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

 /** 更新按键映射。 */
    public synchronized void updateActions(Map<String, String> newActions) {
        actions = new HashMap<>();
        if (newActions != null) {
            actions.putAll(newActions);
        }
    }

    public synchronized Map<String, String> getActions() {
        return new HashMap<>(actions);
    }

 /** 进入录制模式：下一次按键（keyDown）上报给 recorder 后自动退出。 */
    public synchronized void startRecording(KeyRecorder recorder) {
        pendingRecorder = recorder;
    }

    public synchronized void stopRecording() {
        pendingRecorder = null;
    }

 /** 是否处于录制态——供 logcat 监听器在屏蔽三键直通之前放行按键。 */
    public synchronized boolean isRecording() {
        return pendingRecorder != null;
    }

 /**
 * 外部来源按键（如 logcat 监听的 HAL OK 键）入口：与 input 通道的按键走同一套
 * 单击/双击映射逻辑。录制模式下同样上报给 recorder。
 */
    public synchronized void handleExternalKeyDown(int keyCode) {
        KeyRecorder rec = pendingRecorder;
        if (rec != null) {
            pendingRecorder = null;
            rec.onKeyRecorded(keyCode);
            return;
        }
        keyDownState.put(keyCode, true);
        longPressed.put(keyCode, false);
        final int kc = keyCode;
        Runnable longPressTask = new Runnable() {
            @Override
            public void run() {
                if (Boolean.TRUE.equals(keyDownState.get(kc))) {
                    longPressed.put(kc, true);
                    String longAction = actions.get(kc + ":3");
                    if (longAction != null) {
                        cancelPendingSingle(kc);
                        pendingSingleOnUp.put(kc, false);
                        callback.onKeyAction(longAction, kc);
                    }
                }
            }
        };
        handler.postDelayed(longPressTask, LONG_PRESS_THRESHOLD_MS);
        longPressTasks.put(keyCode, longPressTask);
        handleKeyDown(keyCode);
    }

    public synchronized void handleExternalKeyUp(int keyCode) {
        keyDownState.put(keyCode, false);
        Runnable lpTask = longPressTasks.remove(keyCode);
        if (lpTask != null) {
            handler.removeCallbacks(lpTask);
        }
        if (Boolean.TRUE.equals(longPressed.get(keyCode))) {
            longPressed.put(keyCode, false);
            return;
        }
        if (Boolean.TRUE.equals(pendingSingleOnUp.get(keyCode))) {
            pendingSingleOnUp.put(keyCode, false);
            String single = actions.get(keyCode + ":1");
            if (single != null) {
                callback.onKeyAction(single, keyCode);
            }
        }
    }

 /** 按键按下分发：支持单击/双击映射（key = "code:tap"）。 */
    private void handleKeyDown(int keyCode) {
        String single = actions.get(keyCode + ":1");
        String dbl = actions.get(keyCode + ":2");
        // 无映射键:完全不参与双击判定,立即返回(不写 lastKeyDownTime、不挂延迟单击)。
        // 修复:音量键等未映射键快速连按时,第二下会被双击窗口吞掉/与相邻键串扰,
        // 且预注册的按键(如音量键)不应干扰系统原有行为。
        if (single == null && dbl == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long last = lastKeyDownTime.containsKey(keyCode) ? lastKeyDownTime.get(keyCode) : 0L;
        lastKeyDownTime.put(keyCode, now);

        if (dbl != null) {
            // 该键有双击映射：窗口内第二次按下 -> 双击；否则延迟单击（可被双击取消）
            if (last > 0 && now - last < DOUBLE_CLICK_WINDOW_MS) {
                cancelPendingSingle(keyCode);
                callback.onKeyAction(dbl, keyCode);
            } else {
                // 等双击窗口结束；若窗口内无第二次按下则执行单击
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        pendingSingleClick.remove(keyCode);
                        if (single != null) {
                            callback.onKeyAction(single, keyCode);
                        }
                    }
                };
                pendingSingleClick.put(keyCode, r);
                handler.postDelayed(r, DOUBLE_CLICK_WINDOW_MS);
            }
        } else if (single != null) {
            // 只有单击映射：直接执行
            cancelPendingSingle(keyCode);
            callback.onKeyAction(single, keyCode);
        }
    }

    private void cancelPendingSingle(int keyCode) {
        Runnable r = pendingSingleClick.remove(keyCode);
        if (r != null) {
            handler.removeCallbacks(r);
        }
    }

    public interface Callback {
        void onKeyAction(String action, int keyCode);

        void onMonitorStatus(String status);
    }

    public interface KeyRecorder {
        void onKeyRecorded(int keyCode);
    }
}
