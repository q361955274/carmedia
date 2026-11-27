package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 通知监听服务。
 *
 * 作用：让系统把本应用视为"通知监听器"，从而：
 * - MediaSessionManager.addOnActiveSessionsChangedListener 需要传入本组件做过滤；
 * - 系统会保证本组件在 enabled_notification_listeners 中（配合自动授权）；
 * - onNotificationPosted 解析音乐通知的 title/text，补全歌手（通知是音乐 App
 * 自发的权威完整数据，如 QQ 音乐的 text=姜添&听潮阁）。
 */
public class MediaNotificationListener extends NotificationListenerService {

    private static final String TAG = "CarMedia_NL";

 /** 供 CarMediaService 构造 ComponentName 使用。 */
    public static volatile ComponentName COMPONENT_NAME = null;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // EAS 桥接初始化与 CarMediaService.onCreate 一致，受 dim_auto_push 门控。
        // 背景：Bug#67 自 kill 原靠禁用本服务（保活锚点）阻止 EAS 客户端复活，代价是
        // 关推送后服务不自启/不被保活 → 方控（完全依赖服务 logcat 通道）直到打开主界面才恢复。
        // 现在锚点始终启用，由这里代替"禁用组件"保证：关闭推送时，经本服务（通知保活）
        // 重启的进程不会重新注册 EAS 客户端（仪表盘保持清空），同时服务保活/方控不受影响。
        try {
            boolean dimPush = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("dim_auto_push", true);
            if (dimPush) {
                MediaCenterBridge.get().init(this);
                Log.i(TAG, "NL onCreate: MediaCenterBridge initialized");
            } else {
                Log.i(TAG, "NL onCreate: dim_auto_push OFF, skip EAS bridge init (DIM stays clear)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "NL onCreate: MediaCenterBridge init failed: " + t.getMessage());
        }
        // 保活自动授权：通知监听服务由系统绑定管理，进程被杀后系统会在
        // ~1s 内自动重启本服务（Scheduling restart of crashed service），
        // 因此在这里执行授权，实现“保活即自动开启通知监听权限”。
        // 幂等：已授权时 isNotificationListenerEnabled 直接返回 true，无副作用。
        try {
            boolean ok = NotificationPermissionHelper
                    .grantNotificationListenerPermission(this);
            Log.i(TAG, "NL onCreate: grant result=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "NL onCreate: grant failed: " + t.getMessage());
        }
        COMPONENT_NAME = new ComponentName(this, getClass());
        // 保活锚点：本服务由系统绑定保活（进程被杀后 ~1s 自动重启）。
        // 若主服务 CarMediaService 已死（sInstance==null），立即拉起它，
        // 保证通知监听、小卡片/仪表盘推送、按键监控等全部功能恢复。
        ensureMainServiceAlive("NL onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        COMPONENT_NAME = null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        Log.i(TAG, "NotificationListener connected");
        COMPONENT_NAME = new ComponentName(this, getClass());
        ensureMainServiceAlive("NL connected");
        // Bug#70 :系统真正绑定本监听器的时刻 = MediaSessionManager
        // 承认本应用为可信监听者、控制器扫描可用的权威信号。首次安装时
        // onCreate 里的会话监听注册/初始扫描可能因权限未生效而失败，
        // 这里补注册 + 重扫控制器列表，消除"首装控制器找不到音源"。
        CarMediaService svc = CarMediaService.sInstance;
        if (svc != null) {
            svc.onNotificationListenerConnected();
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "NotificationListener disconnected");
        COMPONENT_NAME = null;
    }


    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        try {
            if (sbn == null || sbn.getNotification() == null) {
                return;
            }
            String pkg = sbn.getPackageName();
            if ("com.ecarx.carmedia".equals(pkg)) {
                return;
            }
            // 当前控制器的通知被移除（音源停止/通知被划掉）：清空通知权威快照，
            // 防止 notifActive()/getPlaybackState() 用旧数据继续报播放中。
            CarMediaService svc = CarMediaService.sInstance;
            if (svc != null) {
                svc.onNotificationRemoved(pkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationRemoved error: " + t.getMessage());
        }
    }
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        try {
            if (sbn == null || sbn.getNotification() == null) {
                return;
            }
            String pkg = sbn.getPackageName();
            // 自身通知不解析（避免循环）；其余包名在 CarMediaService.applyNotificationMeta
            // 内二次过滤（仅当前控制器或已知音乐源才接受），这里只排除自身减少无关解析
            if ("com.ecarx.carmedia".equals(pkg)) {
                return;
            }
            Bundle extras = sbn.getNotification().extras;
            if (extras == null) {
                return;
            }
            CharSequence titleCs = extras.getCharSequence("android.title");
            CharSequence textCs = extras.getCharSequence("android.text");
            String title = titleCs != null ? titleCs.toString() : "";
            String artist = textCs != null ? textCs.toString() : "";
            // 内嵌歌词：音乐 App 会把自己的歌词塞进通知/媒体会话（如 QQ 车机版
            // 的 qq_lyric_line、标准 android.media.metadata.LYRIC）。与桥接.apk
            // 的 appendEmbeddedLyrics 同款 key 列表，按顺序取第一个非空。
            // 以 [ 开头或含换行 → 完整 LRC；否则 → 单行歌词。
            String lyric = "";
            try {
                String[] lyricKeys = {
                        "qq_lyric_line", "lyric_line", "current_lyric", "cur_lyric",
                        "android.media.metadata.LYRIC", "android.intent.extra.LYRIC",
                        "netease_lyric", "lyric", "lyrics", "lrc", "currentLine",
                        "oneLineLyric", "line", "android.media.metadata.LYRICS"
                };
                for (String k : lyricKeys) {
                    CharSequence cs = extras.getCharSequence(k);
                    if (cs != null) {
                        String v = cs.toString();
                        if (v != null && !v.trim().isEmpty()) {
                            lyric = v;
                            break;
                        }
                    }
                }
            } catch (Throwable le) {
                Log.w(TAG, "extract lyric failed: " + le.getMessage());
            }
            // 播放状态从通知 actions 按钮判定（MediaStyle 按钮随状态切换）：
            // 含 "Pause" 按钮 → 播放中（state=3）；含 "Play" 按钮 → 暂停（state=2）
            // 无法判定 → -1（不覆盖）
            int state = -1;
            try {
                android.app.Notification.Action[] acts =
                        sbn.getNotification().actions;
                if (acts != null) {
                    for (android.app.Notification.Action a : acts) {
                        if (a == null || a.title == null) {
                            continue;
                        }
                        String t = a.title.toString();
                        if ("Pause".equals(t) || "暂停".equals(t)) {
                            state = 3;
                        } else if ("Play".equals(t) || "播放".equals(t)) {
                            state = 2;
                        }
                    }
                }
            } catch (Throwable te) {
                Log.w(TAG, "parse actions failed: " + te.getMessage());
            }
            Log.d(TAG, "onNotificationPosted: pkg=" + pkg + " title=" + title
                    + " artist=" + artist + " state=" + state
                    + " lyric=" + (lyric.length() > 40 ? lyric.substring(0, 40) + "..." : lyric));
            CarMediaService svc = CarMediaService.sInstance;
            if (svc == null) {
                // 主服务已死：通知到达是强信号，先拉起再处理（保活锚点）
                Log.i(TAG, "onNotificationPosted: main service dead, restarting it");
                ensureMainServiceAlive("notification posted");
                svc = CarMediaService.sInstance;
            }
            if (svc != null) {
                // 非当前控制器的音乐通知 → 触发重新选源。
                // 触发条件:state==3(通知带Pause按钮,明确在播) 或 state==-1但带title+artist
                // (酷我等车机版用自定义RemoteViews通知,无标准Notification.Action按钮,
                // state恒为-1,但有歌名/歌手说明是音乐通知,可能刚起播/切歌)。
                // 酷我等已注册 MediaSession 从暂停变播放时不触发 onActiveSessionsChanged,
                // applyNotificationMeta 又忽略非当前控制器通知,导致 controller 卡死不切换。
                //
                // 切源策略:先检查通知来源是否真在播,在播就直接切过去(绕过通用选源逻辑,
                // 避免蓝牙还在播时通用选源先遍历到蓝牙就 break 选了蓝牙,轮不到酷我);
                // 不在播则走通用 refreshController 重新扫描。
                String curPkg = svc.getControllerPkg();
                boolean isMusicNotif = (state == 3)
                        || (state == -1 && title != null && !title.trim().isEmpty()
                            && artist != null && !artist.trim().isEmpty());
                // ★ (Bug#72G):USB 音源激活时不因外部通知切源——与
                // sessionChangedListener/updateAvailableControllers 对齐。否则旧源
                // (酷我/汽水等)一条通知就把控制器从 USB 切回("切 USB 页面又被自动切回音源页面")。
                if (!svc.isUsbRouteArmed() && isMusicNotif && curPkg != null && !pkg.equals(curPkg)) {
                    if (svc.isSourceReallyPlaying(pkg)) {
                        Log.i(TAG, "onNotificationPosted: " + pkg + " is really playing (state=" + state
                                + "), directly switch from " + curPkg);
                        svc.switchToController(pkg);
                    } else {
                        Log.i(TAG, "onNotificationPosted: " + pkg + " music notification (state=" + state
                                + ") while " + curPkg + " is current, not playing yet, refresh controller");
                        svc.refreshController();
                    }
                }
                svc.applyNotificationMeta(title, artist, pkg, state, lyric);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationPosted error: " + t.getMessage());
        }
    }

 /**
 * 主动读取已存在的通知(不依赖 onNotificationPosted 回调)。
 * 用于 ghost 控制器选中后,酷我通知早已 posted,不会重新触发回调,
 * 需要主动 getActiveNotifications 拉取并解析。
 */
    public static void requestActiveNotifications(final String targetPkg) {
        try {
            MediaNotificationListener instance = getCurrentInstance();
            if (instance == null) {
                Log.w(TAG, "requestActiveNotifications: no instance");
                return;
            }
            StatusBarNotification[] all = instance.getActiveNotifications();
            if (all == null || all.length == 0) {
                Log.d(TAG, "requestActiveNotifications: no active notifications");
                return;
            }
            Log.d(TAG, "requestActiveNotifications: total=" + all.length + " target=" + targetPkg);
            for (StatusBarNotification sbn : all) {
                if (sbn == null || sbn.getNotification() == null) continue;
                String pkg = sbn.getPackageName();
                if ("com.ecarx.carmedia".equals(pkg)) continue;
                if (targetPkg != null && !targetPkg.equals(pkg)) continue;
                // 复用 onNotificationPosted 的解析逻辑
                instance.onNotificationPosted(sbn);
                Log.d(TAG, "requestActiveNotifications: dispatched " + pkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestActiveNotifications error: " + t.getMessage());
        }
    }

    private static volatile MediaNotificationListener sInstance;

    private static MediaNotificationListener getCurrentInstance() {
        return sInstance;
    }

 /**
 * 保活锚点：若主服务 CarMediaService 已死（sInstance==null），
 * 立即 startForegroundService 拉起。本服务（通知监听）由系统绑定保活，
 * 进程被杀后系统自动重启本服务 → 这里把主服务一并拉起，
 * 实现“停止后保活重新拉起前台服务”，恢复小卡片/仪表盘推送、按键监控等全部功能。
 */
    private void ensureMainServiceAlive(String reason) {
        try {
            if (CarMediaService.sInstance == null) {
                Intent svc = new Intent(this, CarMediaService.class);
                startForegroundService(svc);
                Log.i(TAG, "ensureMainServiceAlive: restarting CarMediaService (reason=" + reason + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureMainServiceAlive failed (reason=" + reason + "): " + t.getMessage());
        }
    }
}
