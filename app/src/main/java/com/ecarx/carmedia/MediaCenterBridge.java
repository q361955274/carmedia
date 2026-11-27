package com.ecarx.carmedia;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import com.ecarx.eas.sdk.ECarXApiClient;
import com.ecarx.eas.sdk.mediacenter.ExCallbackWrapper;
import com.ecarx.eas.sdk.mediacenter.MediaCenterAPI;
import com.ecarx.eas.sdk.mediacenter.MusicClientWrapper;
import com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import ecarx.xsf.mediacenter.IMediaCenterClientToken;
import ecarx.xsf.mediacenter.IMediaCenterSvc;
import ecarx.xsf.mediacenter.IMusicClient;

/**
 * EAS（eCarX 生态）媒体中心桥接。
 *
 * 完整实现（移植自原版 carmedia dex 反编译源码）：
 * MediaCenterAPI 初始化 + registerMusic 注册 + pushMetadata/pushLyric/pushPlayState/pushProgress
 * 小卡片与仪表盘推送。
 */
public class MediaCenterBridge {
    private static volatile String sControllerPkg;
    private static volatile CarMediaMusicClient.Controller sController;
    private static MediaCenterBridge sInstance;
    private MediaCenterAPI mApi;
    private Context mAppContext;
    private CarMediaMusicClient mClient;
    private boolean mInitStarted;
    private boolean mRegistered;
    private Object mToken;
    /** 最近一次成功提取的封面 URI（content:// 或 http(s)://），null=无封面。 */
    private String mLastArtworkUri;

    // ================= 移植 MediaBridge=================
 /** 收藏支持开关（由 CarMediaService 按音源设置：在线音乐源 true，蓝牙/USB/代理 false）。
 * 对应 MediaBridge 的 settingsManager.isLikeButtonEnabled() 语义。 */
    private volatile boolean mCollectEnabled = false;
 /** 当前歌曲收藏状态（本地维护 + 点击收藏后翻转，尽力与音源同步）。
 * 对应 MediaBridge 推送 MusicPlaybackInfo.setCollected()。 */
    private volatile boolean mCollected = false;
 /** 完整歌词内容（LRC 文本，由 CarMediaService 构建后注入）。
 * 对应 MediaBridge 的 setLyricContent()——XSFMediaCenter 内部消费该字段。 */
    private volatile String mFullLyricContent;
 /** 当前歌词句。对应 MediaBridge 的 setCurrentLyricSentence()。 */
    private volatile String mCurrentLyricSentence;
 /** 无元数据时的默认占位标题/歌手（MediaBridge SettingsManager 语义，避免卡片空字段）。 */
    private static final String DEFAULT_TITLE = "CarMedia";
    private static final String DEFAULT_ARTIST = "—";
 /** 无歌词时的占位内容（MediaBridge 推 3 个空格，系统按"无歌词"处理）。 */
    private static final String LYRIC_PLACEHOLDER = "   ";

    public static MediaCenterBridge get() {
        MediaCenterBridge mediaCenterBridge = sInstance;
        if (mediaCenterBridge != null) {
            return mediaCenterBridge;
        }
        MediaCenterBridge mediaCenterBridge2 = new MediaCenterBridge();
        sInstance = mediaCenterBridge2;
        return mediaCenterBridge2;
    }

 /**
 * 执行 EAS 媒体中心注册。
 *
 * ★ 12:41 用户真车反馈：无焦点注册（registerInMusic）导致蓝牙不播放、
 * 其他音源无 EAS 推送 → 已回滚为原始 registerMusic 注册（含 registerClientWithRequest）。
 * 蓝牙暂停问题的解决方案改为：蓝牙播放时禁用/杀掉多媒体（XCMedia2），
 * 见 CarMediaService.maybeKillMultimediaOnBluetoothPlay()。
 */
    public void performRegistration() {
        try {
            MediaCenterAPI mediaCenterAPI = this.mApi;
            if (mediaCenterAPI == null || !mediaCenterAPI.isReady()) {
                return;
            }
            // ★ 修复（Bug#64）：不再无条件信任 mRegistered——
            // EAS 服务重启/连接重建后旧 token 已失效，但 mRegistered 仍为 true，
            // 若此处跳过，推送会"假成功"（桥接层返回 true、EAS 层未送达）。
            // onAPIReady 到达即代表一次全新的连接会话，统一走"先注销旧 token、
            // 再重新注册"的幂等路径。
            if (this.mRegistered && this.mToken != null) {
                try {
                    mediaCenterAPI.unregister(this.mToken);
                    Log.i("CarMedia", ">>> EAS re-register: old token unregistered");
                } catch (Throwable t) {
                    Log.w("CarMedia", ">>> EAS re-register: unregister old token error: " + t.getMessage());
                }
                this.mRegistered = false;
                this.mToken = null;
            }
            CarMediaMusicClient carMediaMusicClient = new CarMediaMusicClient();
            CarMediaMusicClient.Controller controller = sController;
            if (controller != null) {
                carMediaMusicClient.setController(controller);
            }
            this.mClient = carMediaMusicClient;
            Object registerMusic = mediaCenterAPI.registerMusic("com.ecarx.carmedia", carMediaMusicClient);
            this.mToken = registerMusic;
            if (registerMusic != null) {
                this.mRegistered = true;
                // 注册音源类型：只注册 6=ONLINE（与原版 carmedia 一致，保证小卡片/仪表盘推送
                // 与控制回调正常）。语音唤起走 XSFMediaCenter handleCtrlApp(sourceType) 路径，
                // 曾尝试加 1/2 但导致小卡片按钮失效（声明与 getter 不一致破坏注册契约），已回滚。
                this.mApi.updateMediaSourceTypeList(this.mToken, new int[]{6});
                this.mApi.declareMediaCenterCapability(this.mToken, new int[]{0, 2, 3});
                this.mApi.declareSupportCollectTypes(this.mToken, new int[]{0, 3, 4});
                this.mApi.updateCurrentSourceType(this.mToken, 6);
            }
        } catch (Throwable th) {
            Log.e("CarMedia", ">>> EAS registration exception", th);
        }
    }

 /**
 * 彻底销毁 EAS 连接：注销音乐客户端 + 释放 Service 绑定 + 重置全部状态。
 *
 * ⚠ （Bug#64）：开关切换不再调用本方法（"自动仪表推送"关闭时只
 * 停推送链路 + 推空内容，EAS 连接保持存活，见 CarMediaService.setDimAutoPush）。
 * destroy + 重开 re-init 在真车上会因旧 binder 死亡通知/注册状态残留导致
 * 重开后推送永久失效。本方法保留仅用于"彻底释放"的完整拆解场景。
 *
 * 幂等：未注册/未初始化时直接跳过，不抛异常。
 */
    public void destroy() {
        try {
            // 1. 注销音乐客户端（必须在 destroy 之前，destroy 会清空 binder 引用）
            if (mApi != null && mRegistered && mToken != null) {
                try {
                    boolean ok = mApi.unregister(mToken);
                    Log.i("CarMedia", ">>> EAS unregister result=" + ok);
                } catch (Throwable t) {
                    Log.w("CarMedia", ">>> EAS unregister error: " + t.getMessage());
                }
            }
            // 2. 释放 EAS 连接（解绑 Service + 清空内部状态）
            if (mApi != null) {
                try {
                    mApi.destroy();
                    Log.i("CarMedia", ">>> EAS API destroyed");
                } catch (Throwable t) {
                    Log.w("CarMedia", ">>> EAS API destroy error: " + t.getMessage());
                }
            }
        } finally {
            // 3. 重置桥接层状态，允许后续重新 init()
            mRegistered = false;
            mToken = null;
            mClient = null;
            mInitStarted = false;
            mApi = null;
            mLastArtworkUri = null;
        }
    }
 /** 注入 EAS 控制回调（由 CarMediaService.onCreate 调用）。 */
    public static void setController(CarMediaMusicClient.Controller controller) {
        sController = controller;
    }

    public static void setControllerPkg(String str) {
        sControllerPkg = str;
    }

 /** 蓝牙会话是否存在(已连接/有 MediaSession)。CarMediaService 在 isBluetoothSessionActive()
 * 时同步更新。蓝牙连接期间 CarMedia 一律不抢 EAS 播放焦点——否则 XCMedia2 的
 * bMediaCenterFocus=false,蓝牙一播放就被它 "no focus to pause" 暂停(真车日志
 * car (5).log 18:48:39 确认:QQ 播放时 controllerPkg=QQ,isBluetoothMode()=false
 * → CarMedia requestPlay 抢走焦点 → 蓝牙播放循环 no-focus 暂停)。 */
    private static volatile boolean sBluetoothSessionPresent = false;

    public static void setBluetoothSessionPresent(boolean present) {
        sBluetoothSessionPresent = present;
    }

 /** 当前控制器是否蓝牙(A2DP sink)。 */
    public static boolean isBluetoothMode() {
        return "com.android.bluetooth".equals(sControllerPkg);
    }

 /**
 * 多媒体(XCMedia2, com.ecarx.multimedia)是否已启动并持有 EAS 播放焦点。
 * 刚开机时多媒体可能还没被拉起:此时蓝牙播放需要 CarMedia 抢占 EAS 焦点
 * 才有通路(实车环境验证:不抢焦点蓝牙不播,必须打开多媒体播放蓝牙才行)。
 *
 * ⚠️ 不能用 getRunningAppProcesses() 检测进程:API 22+ 该方法只返回调用者
 * 自己的进程,永远查不到其他应用(真车上恒 false → 蓝牙时也抢焦点 → 跟
 * 多媒体抢焦点)。改用 EAS 真实状态:queryCurrentFocusClient 返回
 * "com.ecarx.multimedia" 即多媒体活着且持有焦点。
 */
    public boolean isMultimediaHoldingFocus() {
        try {
            if (this.mApi == null || !this.mRegistered || this.mToken == null) {
                return false;
            }
            String focus = this.mApi.queryCurrentFocusClient(this.mToken);
            boolean running = "com.ecarx.multimedia".equals(focus);
            Log.i("CarMedia", ">>> isMultimediaHoldingFocus: focus=" + focus
                    + " -> " + running);
            return running;
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> isMultimediaHoldingFocus error: " + t.getMessage());
        }
        return false;
    }

 /**
 * 触发一次 EAS 焦点仲裁,把播放焦点交还给多媒体(XCMedia2)。
 *
 * 背景 .log 确认):CarMedia 在非蓝牙控制器时推送
 * (shouldGrabEasFocus()=true)会 requestPlay 抢走 EAS 播放焦点 → XCMedia2 的
 * BTMusicStateManagerP 内部 bMediaCenterFocus 被置 false。此时蓝牙一播放,
 * XCMedia2 的 onA2dpStateChanged 检查 isMediaCenterFocus()==false →
 * "no focus to pause" → AVRCP 暂停蓝牙,第一次点播放必失败。
 *
 * 恢复机制:CarMedia 以"注册推送模式"(不抢焦点)调用一次 updateMusicPlaybackState,
 * MDC 检测到 CarMedia 的 client token 未 requestPlay → 自动 requestPlay 把焦点
 * 给回 com.ecarx.multimedia → XCMedia2 bMediaCenterFocus=true → 蓝牙播放不再暂停。
 *
 * 这里复用 mClient 缓存的上次推送内容(切蓝牙前通常是 QQ 的歌曲),内容与卡片
 * 当前显示一致,只触发焦点仲裁、不改变卡片显示。
 */
    public void arbitrateFocusToMultimedia() {
        try {
            if (this.mApi == null || !this.mRegistered || this.mToken == null
                    || this.mClient == null) {
                return;
            }
            MusicPlaybackInfo info = this.mClient.getMusicPlaybackInfo();
            if (info == null) {
                Log.d("CarMedia", ">>> arbitrate focus: no cached playback info, skip");
                return;
            }
            this.mApi.updateMusicPlaybackState(this.mToken, info);
            Log.i("CarMedia", ">>> EAS arbitrate focus to multimedia (re-push cached info)");
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> arbitrateFocusToMultimedia error: " + t.getMessage());
        }
    }

 /**
 * 是否应抢占 EAS 播放焦点（修复 v8 回归）:
 * - 蓝牙"真实在播"（蓝牙 MediaSession 实时 state==3）→ 不抢。抢焦点会让
 * XCMedia2 bMediaCenterFocus=false → "no focus to pause" 暂停蓝牙
 * （真车日志 car(5).log 18:48:39 确认）。
 * - 当前控制器是蓝牙但未在播 → 仅当多媒体(XCMedia2)未启动时才抢（给蓝牙
 * 留 EAS 通路，防止"不打开多媒体蓝牙就没通路"）；多媒体在跑时焦点留给它。
 * - 非蓝牙控制器（QQ/网易云/酷我等在播）→ 一律抢。v8 曾一刀切"蓝牙会话
 * 存在一律不抢"，导致手机连着蓝牙时 QQ/酷我的 EAS 推送永远显示不出来，
 * 小卡片/仪表盘一直被 XCMedia2 的多媒体信息占着（实车环境验证回归）。
 */
    public boolean shouldGrabEasFocus() {
        if (isBluetoothReallyPlaying()) {
            return false;
        }
        if (isBluetoothMode()) {
            return !isMultimediaHoldingFocus();
        }
        return true;
    }

 /**
 * 蓝牙是否真实在播:与 CarMediaService.isBluetoothReallyPlaying 口径一致
 * （A2DP sink 的 AudioTrack 常驻 started 不可靠,暂停时仍可能报 started,
 * 一律以蓝牙 MediaSession 实时 state==3 为准,与 Bug#25/#39 同口径）。
 */
    private boolean isBluetoothReallyPlaying() {
        try {
            Context ctx = this.mAppContext;
            if (ctx == null) {
                return false;
            }
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return false;
            }
            List<MediaController> sessions = msm.getActiveSessions(null);
            if (sessions == null) {
                return false;
            }
            for (MediaController c : sessions) {
                if (c != null && "com.android.bluetooth".equals(c.getPackageName())) {
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> isBluetoothReallyPlaying error: " + t.getMessage());
        }
        return false;
    }

 /**
 * 反射注册（不请求 EAS 焦点）：
 * 1) 反射拿 MediaCenterAPI.mediaCenterSvc（IMediaCenterSvc）
 * 2) registerInMusic(pkg, MusicClientWrapper) 拿 token —— 纯注册，无焦点
 * 3) 反射调 sendStrMsgAndBinderForStr("registerEx", ...) 保留扩展回调注册
 * 跳过 registerClientWithRequest（该命令 = 注册并请求焦点，是蓝牙冲突根源）。
 */

    public void init(Context context) {
        if (this.mInitStarted) {
            return;
        }
        this.mInitStarted = true;
        this.mAppContext = context.getApplicationContext();
        MediaCenterAPI mediaCenterAPI = MediaCenterAPI.get(context);
        this.mApi = mediaCenterAPI;
        mediaCenterAPI.init(context, new ECarXApiClient.Callback() {
            @Override // com.ecarx.eas.sdk.ECarXApiClient.Callback
            public void onAPIReady(boolean z) {
                if (!z) {
                    Log.e("CarMedia", ">>> EAS onAPIReady: FAILED");
                } else {
                    MediaCenterBridge.this.performRegistration();
                    Log.i("CarMedia", ">>> EAS onAPIReady: registered");
                    // 注册完成后补推一次当前状态：服务激活时的首推若因注册未完成
                    // 被静默丢弃，这里补上，保证小卡片/仪表盘启动后立即出现
                    try {
                        CarMediaService service = CarMediaService.sInstance;
                        if (service != null) {
                            service.onEasRegistered();
                        }
                    } catch (Throwable th) {
                        Log.w("CarMedia", ">>> EAS onAPIReady re-push error", th);
                    }
                }
            }
        });
    }

 /**
 * EAS 连接是否存活且已注册（mApi 非空 + 已注册 + API 就绪 + binder 存活）。
 * 开关重开时用：连接还活着就直接复用，避免无谓的重建。
 */
    public boolean isAlive() {
        try {
            return mApi != null && mRegistered && mApi.isReady() && mApi.isServiceAlive();
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> EAS isAlive error: " + t.getMessage());
            return false;
        }
    }

 /**
 * 确保 EAS 已初始化并注册；连接已失效时重建（异步）。
 * 重建后由 onAPIReady → performRegistration（强制重新注册）完成注册，
 * 注册完成回调 onEasRegistered 会再次武装首推重试。
 * 仅当连接确实失效时才重建，避免 destroy/re-init 的旧状态残留问题（Bug#64）。
 */
    public void ensureAlive(Context context) {
        if (isAlive()) {
            return;
        }
        mInitStarted = false;
        init(context);
    }

 /**
 * 注入当前封面 URI（由 CarMediaService.extractArtworkAndPush 调用）。
 * 小卡片/仪表盘 setArtwork 用最近一次封面；null 表示无封面（推 null）。
 */
    public void setArtworkUri(String uri) {
        this.mLastArtworkUri = uri;
    }

    // ================= 移植 MediaBridge：收藏 / 完整歌词 / 默认占位 =================

 /** 设置收藏支持开关（pushMetadata 组装 setSupportCollect 用）。 */
    public void setCollectEnabled(boolean enabled) {
        this.mCollectEnabled = enabled;
    }

    public boolean isCollectEnabled() {
        return this.mCollectEnabled;
    }

 /** 设置当前歌曲收藏状态（setCollected 推送用；点击收藏后由 CarMediaService 翻转）。 */
    public void setCollected(boolean collected) {
        this.mCollected = collected;
    }

 /** 注入完整歌词（LRC 文本 + 当前句）。content 为 null 时推送 " " 占位（MediaBridge 同款）。 */
    public void setFullLyric(String content, String sentence) {
        this.mFullLyricContent = content;
        this.mCurrentLyricSentence = sentence;
    }

 /** 清空歌词注入（切歌/清空时调用）。 */
    public void clearFullLyric() {
        this.mFullLyricContent = null;
        this.mCurrentLyricSentence = null;
    }

 /** 解析音源 App 图标：优先反射 getApplicationIcon → 落盘 content URI；失败用自身 mipmap。 */
    private String resolveAppIcon(String pkg) {
        Context ctx = this.mAppContext;
        if (ctx == null || pkg == null) {
            return null;
        }
        String fallback = null;
        try {
            int iconRes = ctx.getApplicationInfo().icon;
            if (iconRes != 0) {
                fallback = "android.resource://" + ctx.getPackageName() + "/" + iconRes;
            }
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> resolve own icon fallback failed", t);
        }
        try {
            return AppIconResolver.resolve(ctx, pkg, fallback);
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> AppIconResolver failed", t);
            return fallback;
        }
    }

    public void pushLyric(String str) {
        MediaCenterAPI mediaCenterAPI = this.mApi;
        if (mediaCenterAPI == null || !this.mRegistered || str == null) {
            return;
        }
        mediaCenterAPI.updateCurrentLyric(this.mToken, str);
    }

    public void pushLyric(String str, int i, String str2) {
        pushLyric(str);
    }

    public boolean pushMetadata(String str, String str2, String str3, long j, int i, boolean z, String str4, long pos) {
        ApplicationInfo applicationInfo;
        int i2;
        Intent launchIntentForPackage;
        Context context;
        try {
            if (this.mApi == null || !this.mRegistered) {
                return false;
            }
            com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo musicPlaybackInfo = new com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo();
            // 移植 MediaBridge：空元数据时用默认占位（SettingsManager 语义），避免卡片空字段
            // (Bug#77):空 title 占位——原 "—" 显示成"-"。
            // 盒子信息源(com.aufn.carplay/com.carletter.ceb)不推歌曲信息 → 显示 "CarPlay"；
            // 其它音源 → 显示 "CarMedia"(DEFAULT_TITLE)。
            boolean carplayBoxInfo = (str4 != null && ("com.aufn.carplay".equals(str4)
                    || "com.carletter.ceb".equals(str4)));
            String fallbackTitle = carplayBoxInfo ? "CarPlay" : DEFAULT_TITLE;
            musicPlaybackInfo.setTitle((str == null || str.isEmpty()) ? fallbackTitle : str);
            if (str2 != null) {
                musicPlaybackInfo.setArtist((str2.isEmpty()) ? DEFAULT_ARTIST : str2);
            }
            if (str3 != null) {
                musicPlaybackInfo.setAlbum(str3);
            }
            musicPlaybackInfo.setDuration(j);
            // EAS 语义：playbackStatus 只推 0/1（0=暂停/非播放，1=播放）。
            // 车机协议确认（MediaCenterUi AbstractMusicPlaybackInfo）：
            // PLAYBACK_STATUS_PAUSED=0、PLAYING=1、INTERRUPT=2、PREPARE=3
            // 小卡片渲染 refreshPlayPause: i==1 → 暂停按钮(播放中)，其他 → 播放按钮。
            // 推 3/2（MediaSession 常量）会被 EAS 当作 PREPARE/INTERRUPT 导致显示错乱。
            musicPlaybackInfo.setPlaybackStatus(i == 1 ? 1 : 0);
            musicPlaybackInfo.setSourceType(6);
            musicPlaybackInfo.setPackageName("com.ecarx.carmedia");
            if (str4 != null && (context = this.mAppContext) != null) {
                // 真车日志：虚拟 USB 音源包(com.ecarx.carmedia.usb)不存在 →
                // getApplicationInfo 抛 NameNotFoundException → 主线程每 1s 打一次异常栈。
                // try-catch 兜底：包不存在时用 CarMedia 名称（与下方 setAppIcon 分支一致）。
                try {
                    ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(str4, 0);
                    CharSequence label = context.getPackageManager().getApplicationLabel(appInfo);
                    musicPlaybackInfo.setAppName(label != null ? label.toString() : "CarMedia");
                } catch (Throwable th) {
                    musicPlaybackInfo.setAppName("CarMedia");
                }
            }
            // ===== EAS 推送外观设置：图标/封面来源模式 =====
            // eas_icon_mode / eas_cover_mode：source=当前播放源（默认）、none=不推送、custom=自定义图片
            // eas_custom_icon / eas_custom_cover：自定义图片的 content:// URI（filesDir/artwork 稳定文件）
            String easIconMode = "source";
            String easCoverMode = "source";
            String easCustomIcon = null;
            String easCustomCover = null;
            // 无封面兜底:音源无封面时 empty=推空封面 / fixed=推固定图片(eas_fallback_cover)
            String easCoverFallback = "empty";
            String easFallbackCover = null;
            try {
                android.content.SharedPreferences easSp = this.mAppContext
                        .getSharedPreferences("carmedia_prefs", Context.MODE_PRIVATE);
                easIconMode = easSp.getString("eas_icon_mode", "source");
                easCoverMode = easSp.getString("eas_cover_mode", "source");
                easCustomIcon = easSp.getString("eas_custom_icon", "");
                easCustomCover = easSp.getString("eas_custom_cover", "");
                easCoverFallback = easSp.getString("eas_cover_fallback", "empty");
                easFallbackCover = easSp.getString("eas_fallback_cover", "");
            } catch (Throwable ignored) {
            }
            String iconToSet = null;
            try {
                if ("custom".equals(easIconMode)) {
                    // 自定义图标：直接用用户选定的图片 URI
                    iconToSet = (easCustomIcon != null && !easCustomIcon.isEmpty()) ? easCustomIcon : null;
                } else if ("source".equals(easIconMode) && str4 != null) {
                    Context context2 = this.mAppContext;
                    // getApplicationInfo 对不存在的"虚拟包名"(如 com.ecarx.carmedia.usb)
                    // 必然抛 NameNotFoundException,外层 catch 会打 10 行堆栈,每次 EAS push 刷屏
                    // → logcat 洪水 → 按键行解析延迟 → 连切2首根因之一。虚拟包名无图标是正常
                    // 情况,单独 try-catch 静默(不抛给外层)。
                    android.content.pm.ApplicationInfo appInfo = null;
                    if (context2 != null) {
                        try {
                            appInfo = context2.getPackageManager().getApplicationInfo(str4, 0);
                        } catch (Throwable ignored) {
                            appInfo = null;
                        }
                    }
                    if (appInfo != null && appInfo.icon != 0) {
                        // 图标：走 AppIconResolver（反射 getApplicationIcon → 落盘 content:// URI）。
                        // 比 android.resource:// 直连可靠：system uid APK / 部分音源资源 URI 系统 UI 解析不了。
                        String resolvedIcon = resolveAppIcon(str4);
                        if (resolvedIcon != null) {
                            iconToSet = resolvedIcon;
                        } else {
                            iconToSet = "android.resource://" + str4 + "/" + appInfo.icon;
                        }
                    }
                }
                // "none" 模式 → iconToSet 保持 null → 不 setAppIcon（EAS 不推图标）
                if (iconToSet != null) {
                    musicPlaybackInfo.setAppIcon(iconToSet);
                }
            } catch (Throwable th) {
                Log.w("CarMedia", ">>> setAppIcon failed", th);
            }
            musicPlaybackInfo.setMediaType("music");
            musicPlaybackInfo.setSupportLoopModeSwitch(true);
            musicPlaybackInfo.setSupportVrCtrlPlayStatus(true);
            // 移植 MediaBridge：收藏支持开关 + 当前收藏状态（由 CarMediaService 注入，
            // 在线音源 true；蓝牙/USB/代理 false → 小卡片隐藏收藏按钮）
            musicPlaybackInfo.setSupportCollect(this.mCollectEnabled);
            if (this.mCollectEnabled) {
                musicPlaybackInfo.setCollected(this.mCollected);
            }
            musicPlaybackInfo.setPlayingMediaListId("com.ecarx.carmedia-play-list-common");
            StringBuilder sb = new StringBuilder();
            if (str != null) {
                sb.append(str);
            }
            sb.append("|");
            if (str2 != null) {
                sb.append(str2);
            }
            musicPlaybackInfo.setUuid(sb.toString());
            // 移植 MediaBridge：完整歌词 LRC + 当前句塞进 PlaybackInfo（setLyricContent/
            // setCurrentLyricSentence/setLyric(Uri.EMPTY)），XSFMediaCenter 内部消费；
            // 无歌词推 3 空格占位（MediaBridge 同款语义）
            String fullLyric = this.mFullLyricContent;
            String curSentence = this.mCurrentLyricSentence;
            musicPlaybackInfo.setLyricContent(fullLyric != null ? fullLyric : LYRIC_PLACEHOLDER);
            musicPlaybackInfo.setCurrentLyricSentence((curSentence != null && !curSentence.isEmpty())
                    ? curSentence : LYRIC_PLACEHOLDER);
            musicPlaybackInfo.setLyric(Uri.EMPTY);
            // 封面：有封面推 content:// URI（或直传 http(s)），无封面推 null
            // 封面来源模式化（source=当前播放源封面 / none=不推封面 / custom=自定义封面）
            // source 模式且音源无封面时按 eas_cover_fallback 兜底：
            // empty=推空封面（默认）；fixed=推用户选定的固定图片 eas_fallback_cover
            String coverToSet = null;
            if ("custom".equals(easCoverMode)) {
                coverToSet = (easCustomCover != null && !easCustomCover.isEmpty()) ? easCustomCover : null;
            } else if ("source".equals(easCoverMode)) {
                coverToSet = mLastArtworkUri;
                if (coverToSet == null || coverToSet.isEmpty()) {
                    if ("fixed".equals(easCoverFallback)
                            && easFallbackCover != null && !easFallbackCover.isEmpty()) {
                        coverToSet = easFallbackCover;
                    }
                }
            }
            if (coverToSet != null && !coverToSet.isEmpty()) {
                musicPlaybackInfo.setArtwork(Uri.parse(coverToSet));
            } else {
                musicPlaybackInfo.setArtwork(null);
            }
            // 诊断:每次 pushMetadata 打印实际推送的图标/封面来源,便于确认设置生效
            try {
                Log.i("CarMedia", ">>> EAS push iconMode=" + easIconMode + " icon="
                        + (iconToSet != null ? iconToSet : "(none)")
                        + " coverMode=" + easCoverMode + " cover="
                        + ((coverToSet != null && !coverToSet.isEmpty()) ? coverToSet : "(none)")
                        + " sourceCover=" + mLastArtworkUri + " fb=" + easCoverFallback
                        + " fbCover=" + ((easFallbackCover != null && !easFallbackCover.isEmpty()) ? easFallbackCover : "(none)"));
            } catch (Throwable ignored) {
            }
            Context context3 = this.mAppContext;
            if (context3 != null) {
                if (str4 == null || (launchIntentForPackage = context3.getPackageManager().getLaunchIntentForPackage(str4)) == null) {
                    Intent intent = new Intent(this.mAppContext, (Class<?>) MainActivity.class);
                    intent.addFlags(268435456);
                    PendingIntent activity = PendingIntent.getActivity(this.mAppContext, 0, intent, 0);
                    musicPlaybackInfo.setLaunchIntent(activity);
                    musicPlaybackInfo.setPlayerIntent(activity);
                } else {
                    launchIntentForPackage.addFlags(268435456);
                    PendingIntent activity2 = PendingIntent.getActivity(this.mAppContext, 0, launchIntentForPackage, 201326592);
                    musicPlaybackInfo.setLaunchIntent(activity2);
                    musicPlaybackInfo.setPlayerIntent(activity2);
                }
            }
            CarMediaMusicClient carMediaMusicClient = this.mClient;
            if (carMediaMusicClient != null) {
                carMediaMusicClient.setPlaybackInfo(musicPlaybackInfo);
            }
            // EAS 播放焦点抢占(条件化):
            // 1) 非蓝牙控制器 → 抢(requestPlay + updateCurrentSourceType(6))。
            // 不抢焦点时 EAS 不知道当前音源是 CarMedia,小卡片/仪表盘一直
            // 显示旧源(蓝牙)的信息(实车环境验证)。
            // 2) 蓝牙 + 多媒体(XCMedia2)已启动 → 不抢。抢焦点会让 XCMedia2 的
            // queryIfHasFocus() 判定"焦点不在我"→ 蓝牙一播放就被 AVRCP 暂停
            // (no focus to pause)。多媒体在跑时它自己会处理蓝牙通路。
            // 3) 蓝牙 + 多媒体未启动(刚开机) → 抢。否则蓝牙不播放,必须打开
            // 多媒体播放蓝牙才行(实车环境验证)。
            try {
                if (this.mApi != null && this.mToken != null) {
                    if (shouldGrabEasFocus()) {
                        if (!"com.ecarx.carmedia".equals(this.mApi.queryCurrentFocusClient(this.mToken))) {
                            Log.i("CarMedia", ">>> EAS grab focus (bt=" + isBluetoothMode()
                                    + " multimedia=" + isMultimediaHoldingFocus() + ")");
                            this.mApi.requestPlay(this.mToken);
                            this.mApi.updateCurrentSourceType(this.mToken, 6);
                        }
                    } else {
                        Log.d("CarMedia", ">>> EAS skip focus grab (bluetooth + multimedia running)");
                    }
                }
            } catch (Throwable t) {
                Log.w("CarMedia", ">>> EAS focus grab failed: " + t.getMessage());
            }
            this.mApi.updateMusicPlaybackState(this.mToken, musicPlaybackInfo);
            this.mApi.updateCurrentProgress(this.mToken, pos);
            // 播放状态同步：MediaBridge 语义（播放=1，其他=0），避免推 2 导致 EAS 误判暂停
            this.mApi.updatePlayState("com.ecarx.carmedia", i == 1 ? 1 : 0, 0);
            return true;
        } catch (Throwable th2) {
            Log.e("CarMedia", ">>> EAS pushMetadata exception", th2);
            return false;
        }
    }

 /**
 * 推送"无内容/已清空"状态（开关关闭时调用，Bug#65）。
 * <p>
 * ★ 为什么不能复用 pushMetadata("", "")：旧空推送把 sourceType 硬编码为 6(ONLINE)、
 * UUID 硬编码为 "|"(非空)——XSFMediaCenter 原样转发给小卡片后，MusicPlayInfoHolder
 * 走"非空"分支（UUID 非空/包名非空/sourceType≠-1），UnfoldWidgetRemoteViews 走
 * "歌曲信息"渲染分支 → 卡片外壳保留、只显示空标题/歌手（用户实测"空内容但 UI 还在"）。
 * <p>
 * ★ 车机 ROM 确认的官方清空通道（MediaCenterUi 反编译，本地 ROM 解包目录）：
 * 1) MusicPlayInfoHolder.setMusicPlayInfoAndNotify(info)：仅当 info==null 或
 * info.getCurrentSourceType()==-1 才走清空分支（清 UUID/launchIntent/playerIntent、
 * currentSourceType=-1）；非 null 则全部写入。
 * 2) LocalBroadcastHelper.sendClearOrUpdateBroadcast()：currentSourceType==-1 →
 * 发 LOCAL_CLEAR，否则发 LOCAL_UPDATE。
 * 3) WidgetChannelImpl.getUnfoldWidgetRemoteView()：包名为空 或 sourceType==-1 →
 * UnfoldNoClientWidgetRemoteViews（"无客户端"态：默认音乐/暂无客户端/去客户端），
 * 小卡片进入系统设计的"关闭"展示。
 * 4) 兜底：即使 XSF 改写 sourceType，UUID 为空 → UnfoldWidgetRemoteViews.refreshTitle()
 * 走"暂无音乐"(no_music) 占位分支，也不会再显示空字段歌曲信息。
 * <p>
 * ★ 不抢 EAS 焦点、不 updateCurrentSourceType(6)：清空态不注册 DIM 音源显示。
 */
    public boolean pushClearedToCard() {
        try {
            if (this.mApi == null || !this.mRegistered) {
                return false;
            }
            com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo info =
                    new com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo();
            info.setTitle("");
            info.setArtist("");
            info.setDuration(0L);
            info.setPlaybackStatus(0);        // EAS 语义 0=暂停/非播放（MediaCenterUi 确认 0/1）
            info.setSourceType(-1);           // ★ 清空判定：holder 视为无客户端 → LOCAL_CLEAR
            info.setPackageName("com.ecarx.carmedia");
            info.setMediaType("music");
            info.setUuid("");                 // 空 UUID：sourceType 被 XSF 改写时兜底"暂无音乐"
            info.setSupportLoopModeSwitch(true);
            info.setSupportVrCtrlPlayStatus(true);
            info.setSupportCollect(false);
            info.setCollected(false);
            info.setLyricContent(LYRIC_PLACEHOLDER);
            info.setCurrentLyricSentence(LYRIC_PLACEHOLDER);
            info.setLyric(Uri.EMPTY);
            info.setPlayingMediaListId("");
            info.setArtwork(null);
            this.mApi.updateMusicPlaybackState(this.mToken, info);
            this.mApi.updateCurrentProgress(this.mToken, 0L);
            this.mApi.updatePlayState("com.ecarx.carmedia", 0, 0);
            return true;
        } catch (Throwable t) {
            Log.e("CarMedia", ">>> EAS pushClearedToCard exception", t);
            return false;
        }
    }

    public void pushPlayState(int i, int i2) {
        MediaCenterAPI mediaCenterAPI = this.mApi;
        if (mediaCenterAPI == null || !this.mRegistered) {
            return;
        }
        mediaCenterAPI.updatePlayState("com.ecarx.carmedia", i, i2);
    }

    public void pushProgress(long j) {
        MediaCenterAPI mediaCenterAPI = this.mApi;
        if (mediaCenterAPI == null || !this.mRegistered) {
            return;
        }
        mediaCenterAPI.updateCurrentProgress(this.mToken, j);
    }

 /**
 * 主动抢占 EAS 播放焦点(requestPlay + 声明当前音源类型)。
 * ★已废弃 :抢焦点会触发 XCMedia2 的"无焦点就暂停蓝牙"
 * 霸权逻辑(BTMusicStateManagerP.handPlayStateChange: status=10 PLAY 事件时
 * queryIfHasFocus() 判定焦点不在自己 → AVRCP PAUSE 蓝牙,日志
 * "onA2dpStateChanged : no focus to pause!!!")。CarMedia 抢到焦点反而
 * 让 XCMedia2 永远判定"没焦点"→ 蓝牙永远被暂停。正确做法是 CarMedia
 * 不持有焦点,焦点保持空 → XCMedia2 判定空=自己有焦点 → 不暂停蓝牙。
 * 本方法保留但不再被调用(调用方已全部移除)。
 */
    public void ensureFocus() {
        try {
            if (this.mApi == null || !this.mRegistered || this.mToken == null) {
                return;
            }
            String focus = this.mApi.queryCurrentFocusClient(this.mToken);
            Log.i("CarMedia", ">>> ensureFocus(DEPRECATED): focus=" + focus + " -> 不抢焦点,保持空让 XCMedia2 判定自己有焦点");
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> ensureFocus failed: " + t.getMessage());
        }
    }

 /**
 * 观察日志:打印当前 EAS 焦点归属(不抢焦点)。用于验证"CarMedia 不持焦点 →
 * XCMedia2 判定自己有焦点 → 不暂停蓝牙"的修复是否生效。
 */
    public void logFocusOnce() {
        try {
            if (this.mApi == null || !this.mRegistered || this.mToken == null) {
                return;
            }
            String focus = this.mApi.queryCurrentFocusClient(this.mToken);
            Log.i("CarMedia", ">>> focus observer: focus=" + focus);
        } catch (Throwable t) {
            Log.w("CarMedia", ">>> logFocusOnce failed: " + t.getMessage());
        }
    }
}
