package com.ecarx.carmedia;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.service.notification.StatusBarNotification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
/**
 * CarMedia 核心服务(前台服务,START_STICKY)。
 *
 * 设计原则(方案 v1):
 * - 本服务是唯一事实源(UI 只调 public 方法,不持业务状态)");
 * - 一功能一方法,按阶段推进:P1 常驻+通知监听授权 → P2 控制器 → P3 方向盘键
 * → P4 歌词 → P5 EAS 推送 → P6 收尾。
 *
 * P2 已实现:控制器管理(updateAvailableControllers/selectController/handleMetadata/
 * updatePlaybackStatus/sendMediaAction)与 UI 轮询所需 public 访问器。
 */
public class CarMediaService extends Service implements CarMediaMusicClient.Controller {
 /** 供 MediaNotificationListener 等外部组件直接调用的服务实例(onCreate 赋值)。 */
    public static volatile CarMediaService sInstance;
    public static final String TAG = "CarMedia";
    public static final String CHANNEL_ID = "carmedia_service";
    public static final long LYRIC_POLL_INTERVAL_MS = 800L; // 歌词轮询+微件刷新间隔；原 500ms 太频繁，800ms 歌词跟唱不受影响、微件刷新量降约40%
    public static final int NOTIFICATION_ID = 1;
    // 媒体包名常量
    public static final String PKG_QQ_MUSIC = "com.tencent.qqmusiccar";
    public static final String PKG_KUWO = "cn.kuwo.kwmusiccar";
 /** 酷狗音乐车机版(com.kugou.android.auto)。MediaSession 状态不准确(active=false 且 PlaybackState 永远 PAUSED), * 控制器选择和 UI 显示需依赖 AudioTrack 真实状态(isAudioReallyPlaying)而非 MediaSession。 */
    public static final String PKG_KUGOU = "com.kugou.android.auto";
 /** 酷狗音乐概念版(com.kugou.android.lite)。与车机版同族:MediaSession 回调不可靠,
 * 切歌后 onMetadataChanged 可能不触发,需切歌后主动刷新元数据(歌名/歌手)。 */
    public static final String PKG_KUGOU_LITE = "com.kugou.android.lite";
    // 酷狗内部广播防抖动:两次广播之间需要间隔至少400毫秒(反编译a$a.smali确认)
    private long lastKugouKeyEventTime = 0;
 /** GKUI 一卡通定制版酷我(真车吉利车机,版本 GKUI_yikatong2.6.8.3,包名 kwmusiccas)。 */
    public static final String PKG_KUWO_CAS = "cn.kuwo.kwmusiccas";
    public static final String PKG_BLUETOOTH = "com.android.bluetooth";
 /** XSFMediaCenter 系统媒体中心/手机互联代理控制器包名(CarPlay/HiCar/CarLife 等的 MediaSession 代理)。 */
    public static final String PKG_MEDIACENTER = "ecarx.xsf.mediacenter";
 /** CarLife 车端应用包名(百度CarLife 车机版)。
 * 真车日志确认(20:20:24 "【媒体会话】onConnectionDetached: MediaSession 已释放")
 * 该 app 连接手机时**自建 MediaSession**,并非代码早期注释假设的"CarLife 不创建 MediaSession、
 * 播放控制全走 mediacenter 代理"。
 * ⚠ (Bug#77) 更正:早期结论"CarLife 自带 transportControls、应直接作为普通第三方
 * 音源选中"被真车日志推翻——com.baidu.carlifevehicle / com.muyetuge.carlifevehicle 的
 * MediaSession 是投屏代理会话,只上报元数据(歌名/歌手/进度/封面),**不响应标准
 * transportControls.skipToNext/skipToPrevious**(真车 18:32 日志:切歌指令走了
 * dispatchMediaAction 第2级后歌曲位置弹回原歌、歌名歌词不变)。CarLife 必须与 CarPlay
 * 盒子同类处理:方控三键走 MediaSession 路由/注入标准媒体键 87/88/85(见
 * dispatchMediaAction 的 carlifeSelected 直通分支)。
 * 包名兼容集合:不同车型/版本实装包名不同(CarFull 曾见 com.muyetuge.carlifevehicle,
 * 本次真车日志实为 com.baidu.carlifevehicle),判定统一走 isCarlifePkg()。 */
    public static final String PKG_CARLIFE = "com.muyetuge.carlifevehicle";
 /** CarLife 车机版已知包名集合(兼容多车型:CarFull 曾用 muyetuge,本次真车实为 baidu)。 */
    public static final java.util.Set<String> CARLIFE_PACKAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("com.muyetuge.carlifevehicle", "com.baidu.carlifevehicle"));
 /**
 * 改前缀匹配——魔改/共存版在标准包名后追加后缀(如 .beta/.test),用前缀兼容
 * 全部变体,避免每出一个魔改版都要补包名。带点边界(equals 或 startsWith(base+".")):
 * com.muyetuge.carlifevehicle / .beta / .test 都命中;无点后缀(如 xxxvehiclebeta)不误配。
 */
    private static boolean isCarlifePkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String base : CARLIFE_PACKAGES) {
            if (pkg.equals(base) || pkg.startsWith(base + ".")) {
                return true;
            }
        }
        return false;
    }
 /**
 * CarPlay 虚拟包名。
 * 与 USB 虚拟包 com.ecarx.carmedia.usb 同风格:真实盒子包名(E-Box 等)不进入
 * CarMedia 任何 UI/JSON,统一用本虚拟包名标识 CarPlay 控制器条目。
 * 注入 87/88/85 对所有盒子通用,本常量仅用于 UI 条目识别(pushAll/confirmControllerSelectionByPkg),
 * 与方控注入无关。
 */
    public static final String PKG_CARPLAY_BOX = "com.ecarx.carmedia.carplay";
 /**
 * CarPlay 盒子真实包名集合。
 * E-Box 等 CarPlay 盒子连接手机后注册 MediaSession("MediaButtonManager")并
 * setActive(true),其 PlaybackState.actions(5170/5172)不含 SKIP_TO_NEXT/PREVIOUS,
 * transportControls 切歌无效——不能作为"可切歌控制器"被自动选中/独立使用,
 * 但可携带真实歌曲信息(歌名/歌手/封面)。合并模式:用户选中 CarPlay(注入 87/88
 * 切歌)+ 合并盒子真实 MediaSession(显示歌曲信息)。
 * 注意:本集合仅用于 ①列表可合并展示 ②自动选源排除,不参与注入逻辑
 * (注入 87/88 对所有盒子通用,不做任何检测)。
 */
 /** (Bug#76):MediaSession 能被系统媒体键路由的盒子包名。
 * 真车日志确认 MediaSessionService 把注入的 87 路由给 com.aufn.carplay/CoreService
 * (E-Box com.carletter.ceb 的 MediaSession actions 无 SKIP 位,系统不路由 87/88)。
 * 这类盒子用 AudioManager.dispatchMediaKeyEvent 走 MediaSession 路由单通道切歌
 * (盒子无需在前台)");其它盒子只能注入前台窗口。 */
    private static final String CARPLAY_BOX_PKG_MEDIASESSION_ROUTABLE = "com.aufn.carplay";
    private static final java.util.Set<String> CARPLAY_BOX_REAL_PACKAGES = new java.util.HashSet<>(
            java.util.Arrays.asList("com.carletter.ceb", "com.aufn.carplay"));
 /** 用户在控制器列表手动选中 CarPlay 的状态(纯 UI 选中态,与连接无关)。
 * 列表无条件常驻显示 CarPlay 项,点击即切换选中;选中后方控三键
 * 默认注入 87/88/85。用户切到其他音源(真实源/ghost 源)时清除。 */
    private volatile boolean carplayBoxSelected = false;
 /** CarPlay 合并的信息源包名 :选中 CarPlay 后,用户再点一个盒子
 * 真实 MediaSession 条目合并——CarPlay 负责切歌(注入 87/88),盒子 MediaSession
 * 负责显示歌曲信息(歌名/歌手/封面)。null=未合并(纯 CarPlay,显示固定文案)。
 * 盒子会话消失时保留标记但 isCarplayBoxMergedActive() 返回 false,显示回退。 */
    private volatile String carplayMergedPkg = null;
 /** 用户主动取消合并后置 true :自动合并不再触发,直到用户重新选中
 * CarPlay 或手动点盒子条目合并。防止"取消一次又被自动合并回来"。 */
    private volatile boolean carplayMergeDismissed = false;
 /** HiCar 连接状态系统属性(HiCarHMI smali 确认: sys.hicar.connected, 值 "1"=已连接)。 */
    private static final String HICAR_CONNECTED_PROP = "sys.hicar.connected";
    public static final String PKG_NETEASE = "com.netease.cloudmusic";
    public static final String PKG_NETEASE_IOT = "com.netease.cloudmusic.iot";
 /** 汽水音乐车机版(com.luna.music.car)与手机版(com.luna.music)。 */
    public static final String PKG_SODA_CAR = "com.luna.music.car";
    public static final String PKG_SODA = "com.luna.music";
    public static final String PKG_SELF = "com.ecarx.carmedia";
 /** 是否为酷我(标准版 kwmusiccar 或 GKUI 定制版 kwmusiccas),两者 MediaSession/AIDL 同源。 */
    public static boolean isKuwoPkg(String pkg) {
        return PKG_KUWO.equals(pkg) || PKG_KUWO_CAS.equals(pkg);
    }
 /** 是否为汽水音乐(车机版 com.luna.music.car / 手机版 com.luna.music),歌词走汽水官方接口。 */
    public static boolean isSodaPkg(String pkg) {
        return PKG_SODA_CAR.equals(pkg) || PKG_SODA.equals(pkg);
    }
    // 系统媒体中心/EAS 宿主黑名单:这些包不是真实第三方音源,不应被选为 CarMedia 控制器。
    // 1 ecarx.xsf.mediacenter:系统媒体中心/EAS 宿主,常驻 MediaSession/AudioTrack 会误导选源。
    // 2 com.ecarx.multimedia(XCMedia2 酷我车机版):语音唤醒后会通过 EAS MediaCenterAPI
    // registerMusic + requestPlay 抢占焦点并成为焦点客户端(实测禁用后 CarMedia 不再误切)。
    // 两处均非用户主动使用的第三方播放器,自动选源时必须跳过。
    private static final Set<String> SYSTEM_MEDIA_BLACKLIST = new HashSet<>(Arrays.asList(
            "ecarx.xsf.mediacenter",
            "com.ecarx.multimedia"
    ));
 /** 是否系统媒体中心/宿主包(非真实音源,自动选源跳过)。
 * 用于候选过滤/真实在播判断/自动切换等场景——这些场景下 mediacenter 常驻
 * AudioTrack 会误导选源,必须排除。但 selectController 硬闸门不再用它(见 isControlBlocked)。 */
    private static boolean isSystemMediaPackage(String pkg) {
        return pkg != null && SYSTEM_MEDIA_BLACKLIST.contains(pkg);
    }
 /** 控制硬阻塞:只有 XCMedia2(酷我车机版,com.ecarx.multimedia)会通过 EAS MediaCenterAPI
 * registerMusic + requestPlay 抢占焦点并成为焦点客户端,必须阻塞。
 * mediacenter(ecarx.xsf.mediacenter)不阻塞——无真实第三方音源时作为手机互联 app
 * (CarPlay/HiCar/CarLife 等)的系统代理控制器,这些 app 自己不创建 MediaSession,
 * 播放控制全走 mediacenter 代理。UI 列表仍由 isHiddenController 隐藏 mediacenter。 */
    private static boolean isControlBlocked(String pkg) {
        return "com.ecarx.multimedia".equals(pkg);
    }
 /** 通话管理包名(通话中成为控制器暂停音乐,挂断后恢复)。 */
    public static final String PKG_TELECOM = "com.android.server.telecom";
 /**
 * 是否从控制器 UI 列表隐藏的包。
 * 
 * 1 ecarx.xsf.mediacenter(系统媒体中心):始终隐藏——作为手机互联 app(CarPlay/HiCar/CarLife
 * 等)的系统代理控制器,由 CarMedia 内部自动 fallback 选中,用户无需在 UI 列表手动选择。
 * 2 com.android.server.telecom(通话管理):仅通话中显示,否则隐藏——日常列表出现通话管理无意义。
 * 通话状态通过 isTelecomSessionActive() 独立查询(getActiveSessions(null)),不依赖
 * availableControllers,隐藏后通话检测/挂断恢复不受影响。
 * 注意:此处仅控制 UI 列表显示;内部代理逻辑不受影响——findActiveMediacenterController()
 * 直接查 getActiveSessions(null);selectController 对 telecom 无硬闸门,通话中仍可自动选中。
 */
    private boolean isHiddenController(String pkg) {
        if (pkg == null) {
            return false;
        }
        // CarPlay 盒子真实包名不再隐藏——合并模式下作为"信息源"候选显示在
        // 列表,用户选中 CarPlay 后再点它合并(显示盒子歌曲信息)。不能作为普通控制器
        // 独立切歌(actions 无 SKIP 位),由 isCarplayBoxRealPkg 在自动选源中排除。
        // 系统媒体中心:始终隐藏
        if (PKG_MEDIACENTER.equals(pkg)) {
            return true;
        }
        // 通话管理:仅通话中显示,其余时间隐藏
        if (PKG_TELECOM.equals(pkg)) {
            return !isTelecomSessionActive();
        }
        return false;
    }
    // 媒体状态常量
    public static final int STATE_PAUSED = 2;
    public static final int STATE_PLAYING = 3;
    // EAS 小卡片协议状态值(车机 MediaCenterUi 确认):
    // PLAYBACK_STATUS_PAUSED=0、PLAYING=1、INTERRUPT=2、PREPARE=3
    // 推送只能用 0/1:1=播放中,0=暂停/非播放。
    // 注意与 MediaSession 的 STATE_*(2=PAUSED/3=PLAYING)区分!
    public static final int EAS_STATUS_PAUSED = 0;
    public static final int EAS_STATUS_PLAYING = 1;
    // 数据源类型
    public static final int SOURCE_TYPE_NONE = 0;
    public static final int SOURCE_TYPE_QQ = 1;
    public static final int SOURCE_TYPE_NETEASE = 2;
 /** 通用源(非 QQ/网易云的其他控制器):歌词走通用抓取器(网易云→XMF2 兜底)。 */
    public static final int SOURCE_TYPE_GENERIC = 3;
 /** 汽水音乐源:歌词走 SodaMusicLyricFetcher(汽水官方搜索+SEO 歌词接口)。 */
    public static final int SOURCE_TYPE_SODA = 4;
 /** (A1 显式音源状态机):USB 音源类型。USB 激活/自愈/恢复时同步设置,
 * 与 usbRouteArmed 一起构成"显式音源闸门"——外部回调(蓝牙状态/会话变化/
 * AudioTrack 变化)先过闸门,不再靠推断谁在播。 */
    public static final int SOURCE_TYPE_USB = 5;
 /** 酷我音源类型(标准版 kwmusiccar / GKUI 定制版 kwmusiccas):
 * 歌词走 KuwoLyricFetcher(酷我官方 r.s 搜索 + songinfoandlrc 歌词),失败回退 XMF2。 */
    public static final int SOURCE_TYPE_KUWO = 6;
 /** 酷狗音源类型(com.kugou.android.auto):
 * 歌词走 KugouLyricFetcher(酷狗官方 mobilecdn 搜索 + lyrics.kugou.com 歌词),失败回退 XMF2。 */
    public static final int SOURCE_TYPE_KUGOU = 7;
    // 元数据 key
    public static final String METADATA_TITLE = "android.media.metadata.TITLE";
    public static final String METADATA_ARTIST = "android.media.metadata.ARTIST";
    public static final String METADATA_DURATION = "android.media.metadata.DURATION";
    public static final String METADATA_ALBUM = "android.media.metadata.ALBUM"; // flicker 真切歌判据
    private final LocalBinder binder = new LocalBinder();
    // ---- 控制器状态 ----
    private List<MediaController> availableControllers = new ArrayList<>();
    private MediaController mediaController;
    private String controllerPkg;
    private boolean controllerStale;
    private boolean switchingController; // 防重入:推送触发的自动切换
    private String currentTitle;
    // 酷我真实进度 AIDL 客户端 :酷我 MediaSession position 恒 0 不更新,
    // 绑定其 exported RemoteService 周期轮询 getCurrentPos(1) 拿与 UI 一致的实时位置。
    private KuwoAidlClient kuwoAidlClient;
    // 酷狗封面 AIDL 客户端 :酷狗 MediaSession 无 ART 字段拿不到封面,
    // 绑定其 exported AutoSdkAIDLRemoteService,executeAction("PLAYER_GET_PLAYING_MUSIC")
    // 返回 KG_MUSIC.albumImagePath 封面 URL(反编译确认)。
    private KugouCoverProvider kugouCoverProvider;
 /** 酷狗封面 URL -> 本地 content URI 映射（每首歌只下载一次）。 */
    private final java.util.Map<String, String> mKugouLocalArt = new java.util.concurrent.ConcurrentHashMap<>();
    private String currentArtist;
    // ================= USB 本地音乐音源 =================
    private UsbSourceController usbSourceController;
    private volatile boolean usbRouteArmed = false; // USB 音源是否激活
 /** (Bug#72G):激活 USB 时正在播的真实源——切 USB 后不被当作"新源"自动切回。 */
    private volatile String usbActivatePrevPkg = null;
 /** 最近一次 USB 方向盘/UI 切歌时间（handlePlaybackConfigChanged 保护窗口用）。 */
    private volatile long lastUsbKeyEventTime = 0L;
    private static final long USB_SWITCH_PROTECT_MS = 3000L;
    // ---- USB 歌词来源与内嵌歌词缓存----
 /** 当前 USB 歌词来源（embedded/netease/soda/fallback）。 */
    private String usbLyricSource = "auto";
 /** 内嵌歌词行缓存（同名 .lrc 解析结果，已去掉时间戳）。 */
    private volatile java.util.List<String> usbEmbeddedLyricLines = new java.util.ArrayList<>();
 /** USB 内嵌歌词每行的时间戳（毫秒），与 usbEmbeddedLyricLines 一一对应。 */
    private volatile java.util.List<Long> usbEmbeddedLyricTimestamps = new java.util.ArrayList<>();
 /** 上次触发歌词抓取的歌曲标识（title||artist），避免重复抓取。 */
    private String lastUsbLyricSongKey = "";
 /** USB 内嵌歌词为空 → 已兜底云歌词(网易云)。期间歌词取云抓取器。 */
    private volatile boolean usbEmbeddedFallback = false;
    // 上一首已处理的歌名/歌手/音源(切歌检测用:handleMetadata 每秒重复触发,同歌同音源不重置 API 状态灯)
    private String lastMetadataTitle = "";
    private String lastMetadataArtist = "";
    private String lastMetadataPkg = "";
    private String lastMetadataControllerPkg = "";
    // ================= titleFlickerDetection（歌名闪烁检测，仅蓝牙） =================
    // 部分手机音乐 App 经 AVRCP 把"歌词行"放进 TITLE 字段持续推送，歌名每 1~2 秒变化一次。
    // CarMedia 若把每行歌词当"切歌"处理 → 每行触发 XMF2 备用歌词接口 + CoverFallback 封面兜底，
    // 网络/主线程洪泛压垮车机 → "封面一直变 + 方控卡"（真车反馈，屏蔽歌词可缓解）。
    // 指引 §5.7 设计了本检测但早期工程未实现：歌手不变 + 歌名在 30s 窗口内变化 ≥2 次
    // → 判定"音源自带歌词"（titleFlickerLyricActive=true）→ 跳过 XMF2 与封面兜底。
    // 注意：仅蓝牙音源检测，其他音源不做（避免误伤正常切歌的同歌手连播）。
    private static final long TITLE_FLICKER_WINDOW_MS = 30_000L;
    private static final int TITLE_FLICKER_THRESHOLD = 2;
 /** 闪烁窗口起点（elapsedRealtime）。 */
    private long titleFlickerWindowStart = 0L;
 /** 窗口内歌名变化次数。 */
    private int titleFlickerCount = 0;
 /** 歌名变化基线歌手（歌手变化 = 真切歌，复位）。 */
    private String titleFlickerArtist = null;
 /** 上一次参与计数的歌名（同歌名重复推送不计数）。 */
    private String lastFlickerTitle = null;
 /** v2:歌名闪烁基线时长(playbackDuration)。duration 相同=同一首歌(歌词行
 * 洪泛),duration 变化=真切歌(复位)。替代旧"歌手变化=真切歌"——真车 logcat 确认
 * AVRCP 的 artist 字段也随歌词行变化("歌名-歌手"组合),旧判定永远不命中。 */
    private long lastFlickerDuration = 0L;
 /** 闪烁窗口内专辑(album)。AVRCP 把歌词行当歌名推时,title 每行变
 * 但 album 稳定(同一首歌);真切歌(即使两首 duration 碰巧相同)时 album 变化。
 * 用它作为"duration 判真切歌"失效时的补充判据,修复"EAS 显示旧歌名"。 */
    private volatile String lastFlickerAlbum = null;
 /** 已判定音源自带歌词 → 跳过 XMF2 / CoverFallback。 */
    private volatile boolean titleFlickerLyricActive = false;
 /** 当前闪烁窗口的"首样本歌名"(窗口起点/真切歌时记录)。
 * 切歌后 AVRCP 通常先推真实歌名再滚歌词行 → 基线即真实歌名,
 * flicker 命中后 EAS 用它替代每行变化的 currentTitle。 */
    private volatile String titleFlickerBaselineTitle = null;
 /** flicker 命中时记录的"稳定歌名/歌手"(EAS 推送用)。
 * 命中后 currentTitle 每 1~2s 随歌词行变化,直接推 EAS 会让去重恒真(洪泛)。
 * 用基线稳定值后 trackChanged=false → 只推 2s 进度,状态变化照常全量推。
 * 真切歌/切控制器时随 resetTitleFlicker 清空,恢复真实推送。 */
    private volatile String flickerStableTitle = null;
    private volatile String flickerStableArtist = null;
 /** QQ 完整歌手已就绪(onQqLyricReady 从 LRC 提取到含 & 等多歌手)。
 * 为 true 时 handleMetadata 不再用 QQ 主歌手覆盖,避免竞态冲掉完整歌手。 */
    private boolean qqArtistComplete;
 /** 通知栏权威数据快照(来源 pkg + 歌名 + 歌手)。
 * 通知是音乐 App 自发的权威完整数据;这些字段非空且 pkg 匹配当前控制器时,
 * 其他来源(metadata/LRC/搜索)不得覆盖,实现"通知栏永远优先"。 */
    private String notifPkg;
    private String notifTitle;
    private String notifArtist;
    private long playbackDuration;
    private long frozenPlaybackPosition; // 暂停时冻结的进度(MediaSession position 暂停后仍会跑)
    // 蓝牙进度累加:蓝牙 AVRCP 的 position 通常不实时更新(只在播放/暂停/seek 时发事件),
    // 导致进度条卡住。播放中用本地时间差累加,让进度条平滑走动并同步手机实际位置。
    private long lastBtPositionMs = 0;       // 最后一次从蓝牙读到的有效 position(>0)
    private long lastBtPositionTime = 0;     // 读到该 position 的时间(elapsedRealtime)
    // 非蓝牙进度累加 :QQ/酷我等车机版音源的 MediaSession position
    // 播放中不实时更新(只在切歌/seek/暂停时更新),裸 getPosition() 会卡住 →
    // 歌词永远取第一行、进度条不动。与蓝牙同款"本地时间差累加 + PlaybackState 校准"。
    private long lastNonBtPositionMs = 0;    // 非蓝牙累加起点位置
    private long lastNonBtPositionTime = 0;  // 非蓝牙累加起点时间(elapsedRealtime)
    private int lastPushedPlayState; // 上次成功推送给 EAS 的播放状态(防"偶发推暂停"兜底用)
    private String lastPushedTitle;   // 上次推送给 EAS 的歌名(MediaBridge 式去重用)
    private String lastPushedArtist;  // 上次推送给 EAS 的歌手(MediaBridge 式去重用)
    private String lastPushedArtworkUri; // 上次推送给 EAS 的封面 URI(封面变化也触发全量推送)
    private boolean firstPushPending = true; // 服务激活后首推未完成:启动即推一次,不等音源回调
 /** "USB armed but not playing" 分支日志节流（30ms 级高频调用时防刷屏）。 */
    private long lastArmedNotPlayingLogMs;
    // EAS 播放焦点守卫(已废弃 22:54):抢焦点会触发 XCMedia2 的
    // "无焦点就暂停蓝牙"霸权逻辑,焦点保持空反而让 XCMedia2 判定自己有焦点、
    // 不暂停蓝牙。守卫保留字段但不再启动/反抢。
    private final android.os.Handler easFocusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable easFocusRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                // 已废弃:不再抢焦点。仅保留日志观察焦点归属,便于排查。
                if (MediaCenterBridge.get() != null) {
                    MediaCenterBridge.get().logFocusOnce();
                }
                // 蓝牙播放时杀掉多媒体(XCMedia2),防止其"无焦点就暂停蓝牙"霸权逻辑。
                // 根因 :XCMedia2 的 BTMusicStateManagerP 在蓝牙 PLAY 时查
                // 内部 bMediaCenterFocus,false 则发 AVRCP 暂停。杀光它的进程后暂停逻辑
                // 不再执行;若它重启,register+requestPlay 在无焦点注册方案下也能抢到
                // 焦点(bMediaCenterFocus=true),蓝牙同样不暂停。两种结局都安全。
                maybeKillMultimediaOnBluetoothPlay();
            } catch (Throwable t) {
                Log.w(TAG, "easFocusGuard error: " + t.getMessage());
            } finally {
                easFocusHandler.postDelayed(this, 5000L);
            }
        }
    };
 /**
 * 蓝牙播放中且多媒体(XCMedia2)进程存活 → force-stop 它。
 * CarMedia 是 sharedUserId=android.uid.system(uid 1000),天然持有
 * FORCE_STOP_PACKAGES 权限,可终止任意应用(含预装应用)。
 * 防抖 30s:避免每次轮询都杀。
 */
    private volatile long lastMultimediaKillMs = 0L;
    private static final long MULTIMEDIA_KILL_COOLDOWN_MS = 30_000L;
    private static final String PKG_MULTIMEDIA = "com.ecarx.multimedia";
 /** 多媒体在 CarMedia 本次禁用前就已处于用户主动禁用状态 → 后续不解禁。 */
    private volatile boolean wasMultimediaDisabled = false;
 /** CarMedia 本次是否已成功禁用多媒体(幂等标记,防止第二次调用把自己设的
 * DISABLED_USER 误判为"用户本来就禁用",导致后续永久不解禁)。
 * ⚠ 必须持久化:进程重启(保活/划任务/系统回收)后内存字段丢失,会把自己
 * 之前设的 DISABLED_USER 误判为用户主动禁用,导致蓝牙结束后永久不解禁。 */
    private volatile boolean weDisabledMultimedia = false;
    private static final String PREF_KEY_WE_DISABLED_MM = "multimedia_we_disabled";
    // ================= 蓝牙焦点持有方案 =================
    // 替代"整体禁用 XCMedia2"防它暂停蓝牙：蓝牙播放时用 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    // 请求标准音频焦点 → 系统给原持有方 XCMedia2 下发 LOSS_TRANSIENT_CAN_DUCK(-3) →
    // XCMedia2 蓝牙模块(BTMusicStateManager.onAudioFocusChange)对 -3 直接忽略、不暂停蓝牙。
    // 好处:①"蓝牙焦点"明确归属 CarMedia;②不用整体禁用 XCMedia2,蓝牙电话 HFP 音频不受影响。
    // 回退:若真车实测 XCMedia2 仍被暂停(宿主把 MAY_DUCK 非标准映射成 -2/-1),
    // 置 USE_BLUETOOTH_FOCUS_HOLD=false,自动回退到 disableMultimedia() 旧方案。
    private static final boolean USE_BLUETOOTH_FOCUS_HOLD = true;
 /** 是否已持有蓝牙 MAY_DUCK 焦点(幂等,避免重复请求)。 */
    private volatile boolean btFocusHeld = false;
    private final AudioManager.OnAudioFocusChangeListener btFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    // CarMedia 是纯控制方、不实际发声;收到 LOSS 无需动作,仅记录日志便于排障。
    // WidgetBlueToothReceiver → VR_CONTROL_PLAY_BT → forceToSwitchBt()(activeExternal=BT),
    // 让它一直认为自己在蓝牙音源,互斥分支不触发,蓝牙不被暂停。
                    Log.i(TAG, ">>> bluetooth MAY_DUCK focus change: " + focusChange);
                }
            };
    // ================= 保持 XCMedia2 在蓝牙音源 =================
    // 根因:XCMedia2 的 handPlayStateChange 音源互斥——非蓝牙音源页(activeExternal≠BT)时,
    // 蓝牙 A2DP 一播放就无条件 AVRCP 暂停。
    // 解法:蓝牙在播时周期性发 ECARX_WIDGET_BLUETOOTH_PLAY 广播 → XCMedia2 的
    // WidgetBlueToothReceiver → VR_CONTROL_PLAY_BT → forceToSwitchBt()(activeExternal=BT),
    // 让它一直认为自己在蓝牙音源,互斥分支不触发,蓝牙不被暂停。
    // 真车实测 :该广播可后台切到蓝牙播放,不弹 UI。
 /** 蓝牙在播时把 XCMedia2 锚定在蓝牙音源的广播间隔(防抖,避免高频轰炸)。 */
    private static final long BT_SOURCE_KEEP_INTERVAL_MS = 10_000L;
    private volatile long lastBtSourceKeepMs = 0L;
 /** 上次 maybeKill 轮询到的蓝牙播放状态(用于"暂停→播放"上升沿检测,只在进入播放时发一次广播)。 */
    private boolean lastBtPlayingForKeep = false;
    // ================= 蓝牙首次播放被暂停的自愈 =================
    // 根因(bl_full(2).log 确认):Android 系统蓝牙 A2dpSinkStreamHandler 的启动时序 bug——
    // 手机首次推流(SRC_STR_START)瞬间蓝牙还没完成 requestAudioFocus(audioFocus=0)，
    // 触发 DELAYED_PAUSE/sendAvrcpPause 把蓝牙暂停；焦点(+0.9s)才被授予(audioFocus=1)，
    // 第二次播放即正常。这是系统服务行为，CarMedia 只能兜底：检测"蓝牙刚进入播放后
    // 极短时间内掉回非播放"(偶发早暂停)且非用户主动暂停，自动补一次 play。
 /** 蓝牙最近一次进入 PLAYING 的时间戳(用于检测"刚播放就掉回"的早暂停)。 */
    private volatile long lastBtPlayRisingMs = 0L;
 /** 早暂停补 play 的冷却截止(补一次后 8s 内不再补,防与用户手动暂停打架/防循环)。 */
    private volatile long btRecoverCoolUntil = 0L;
 /** 蓝牙进入播放后,在该窗口内掉回非播放视为"系统早暂停"(需自愈)。 */
    private static final long BT_EARLY_PAUSE_WINDOW_MS = 3_000L;
 /** 早暂停补 play 的冷却时间。 */
    private static final long BT_RECOVER_COOLDOWN_MS = 8_000L;
 /** 蓝牙播放时被 CarMedia 主动暂停的其他音源(QQ 音乐等),离开蓝牙时恢复。 */
    private final List<String> pausedOtherSources = new ArrayList<>();
 /** 蓝牙播放时周期仲裁 EAS 播放焦点给 XCMedia2 的间隔(防抖)。 */
    private static final long EAS_FOCUS_ARBITRATE_INTERVAL_MS = 5000L;
    private volatile long lastEasArbitrateMs = 0L;
    // ================= v10: 双通道按键去重 =================
    // 问题:同一物理按键在真车上会同时触发两条独立通道——
    // ① LogcatKeyMonitor(logcat 监听) → onKeyAction → sendNext/sendPrevious/sendPlayPause
    // ② MDC 转发 EAS ctrl(MediaKeyReceiver → onNextCommand 等)
    // 两条都处理 → 蓝牙/QQ 收到两次切歌命令 → "连切2首"(v7 确认双切来源之一)。
    // 去重策略:logcat 通道(物理按键)处理三键时打时间戳 markLogcatKeyHandle();
    // EAS ctrl 到达时,若 500ms 内 logcat 刚处理过 → 视为"同一按键的第二份转发"直接忽略。
    // - 用户物理按键(logcat)永不丢:每次按键都 mark,下一次按键距上次 >500ms 不受影响;
    // - 用户快速连按两次(间隔 <500ms):第一次 logcat 处理→EAS ctrl(1)忽略;
    // 第二次 logcat 处理→EAS ctrl(2)忽略 → 正确切 2 首;
    // - 纯 EAS 界面按钮(无 logcat 触发):lastLogcatKeyHandleMs 是旧值 → 不去重 → 正常。
    // logcat 305/304(InputManager tag) → MediaSession 系统媒体键 87/88 → MEDIA_BUTTON
    // 广播 87/88 → MDC EAS ctrl,正常时延 36~144ms,500ms 短窗口平时够用;
    // MediaSession(包名 com.ecarx.carmedia),而 controllerPkg 是虚拟包
    // com.ecarx.carmedia.usb,原比较必不相等 → 兜底对 USB 恒失效。
    private static final long KEY_CHANNEL_DEDUP_MS = 500L;
    // ---- vX: "偶尔连切2首"残余修复(真车日志实证) ----
    // 真车一次方向盘按压会同时产生 4 条通道(9-07 kg_3/kugou_2 日志逐条核对):
    // logcat 305/304(InputManager tag) → MediaSession 系统媒体键 87/88 → MEDIA_BUTTON
    // 广播 87/88 → MDC EAS ctrl,正常时延 36~144ms,500ms 短窗口平时够用;
    // 但车机高负载时(USB 大列表扫描/WebView 渲染/主线程卡顿)某条"副本通道"可能
    // >500ms 才到达 → 500ms 窗口漏掉 → 副本再切一次 = 连切2首.
    // 修复策略(三件套):
    // ① 副本通道(MediaSession/MEDIA_BUTTON 广播/MDC)对"logcat 真实按键已处理"的吸收
    // 窗口加长到 2s——副本永远只是同一物理按键的第二份,不是用户新按键;
    // ② 去重标记按方向(下一曲/上一曲/播放暂停)隔离——长窗口下若全局单时间戳,
    // 用户"按下一曲后 1s 内按上一曲",上一曲副本会被下一曲旧 mark 误吞;
    // ③ Bug#53 路由兜底加长到 2s 并修复 USB 包名:USB 模式下媒体键路由到 CarMedia 自身
    // MediaSession(包名 com.ecarx.carmedia),而 controllerPkg 是虚拟包
    // com.ecarx.carmedia.usb,原比较必不相等 → 兜底对 USB 恒失效。
    // 保留 500ms 短窗口给 logcat 通道自身(logcat keyDown 即真实按键,若也用长窗口,
    // 用户 2s 内快速连按两次会被第二次 keyDown 被第一次的旧 mark 误吞 → 不切歌)。
    private static final long KEY_CHANNEL_DEDUP_LONG_MS = 2000L;
    // 去重标记来源:标记是谁打的,决定它能不能当"长窗口吸收依据"。
    // - SRC_LOGCAT:方向盘物理按键 logcat 通道执行(真实按键,副本通道可放心长吸收)");
    // - SRC_MEDIASESSION:MediaSession 代理 / MEDIA_BUTTON 广播(副本通道自身执行),
    // 只做短窗口互斥,防止"副本通道自己的旧 mark 吞掉下一次真实按键的副本"。
    private static final int SRC_LOGCAT = 1;
    private static final int SRC_MEDIASESSION = 2;
    private static final int DIR_NEXT = 0;
    private static final int DIR_PREV = 1;
    private static final int DIR_PLAYPAUSE = 2;
    private static final int DIR_COUNT = 3;
    private final long[] lastLogcatKeyHandleMs = new long[DIR_COUNT];
    private final int[] lastLogcatKeyHandleSrc = new int[DIR_COUNT];
 /** 各方向最近一次接收器兜底发送时刻,防双通道(proxy+logcat)各兜底一次。 */
    private final long[] lastReceiverFallbackMs = new long[DIR_COUNT];
 /** 动作名 → 方向(仅三键;其他返回 -1) */
    private int dirOfAction(String action) {
        if ("next".equals(action)) return DIR_NEXT;
        if ("prev".equals(action)) return DIR_PREV;
        if ("playpause".equals(action) || "play".equals(action) || "pause".equals(action)) return DIR_PLAYPAUSE;
        return -1;
    }
 /** 键码 → 方向(仅三键;其他返回 -1)。305/87=下一曲,304/88=上一曲,300/85/126/127=播放暂停 */
    private int dirOfKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyMonitor.KEY_NEXT:
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                return DIR_NEXT;
            case KeyMonitor.KEY_PREV:
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return DIR_PREV;
            case KeyMonitor.KEY_MUTE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                return DIR_PLAYPAUSE;
            default:
                return -1;
        }
    }
    private void markLogcatKeyHandle(int dir, int src) {
        if (dir < 0 || dir >= DIR_COUNT) return;
        lastLogcatKeyHandleMs[dir] = System.currentTimeMillis();
        lastLogcatKeyHandleSrc[dir] = src;
    }
 /** logcat 通道自身判断:500ms 内副本通道(MediaSession/MDC)是否已处理同一方向。
 * 仅时间判断、不限来源——副本先到时它打的是 mark,logcat 后到要能吸收(Bug#52 双向去重)。
 * 窗口保持 500ms 短窗口:logcat keyDown 是真实按键,长窗口会吞用户快速连按。 */
    private boolean isEasFromRecentLogcat(int dir) {
        return dir >= 0 && dir < DIR_COUNT
                && System.currentTimeMillis() - lastLogcatKeyHandleMs[dir] < KEY_CHANNEL_DEDUP_MS;
    }
 /** 副本通道(MediaSession/MEDIA_BUTTON/MDC)判断是否应吸收当前动作:
 * ① 2s 内"真实按键通道(logcat)"已处理同方向 → 吸收(长窗口,覆盖副本通道晚到 >500ms);
 * ② 500ms 内其他副本通道已处理同方向 → 吸收(副本之间互斥)");
 * ③ 副本通道自己 >500ms 前的旧标记不吸收(避免吞掉下一次真实按键的副本)。 */
    private boolean shouldCopyChannelAbsorb(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return false;
        long now = System.currentTimeMillis();
        long t = lastLogcatKeyHandleMs[dir];
        if (t != 0L) {
            if (lastLogcatKeyHandleSrc[dir] == SRC_LOGCAT && now - t < KEY_CHANNEL_DEDUP_LONG_MS) return true;
            if (lastLogcatKeyHandleSrc[dir] != SRC_LOGCAT && now - t < KEY_CHANNEL_DEDUP_MS) return true;
        }
        return now - lastMdcKeyHandleMs[dir] < KEY_CHANNEL_DEDUP_MS;
    }
    // 自触发去重——CarMedia 通过 dispatchMediaKey 发 ACTION_MEDIA_BUTTON 广播给
    // mediacenter 后,mediacenter 处理媒体键会再次回调 CarMedia 的 onNextCommand/onPreviousCommand/
    // onPlayCommand/onPauseCommand,形成死循环(真车日志 car_full4.log 确认:18:22:18 一秒内
    // onNextCommand 被调用 30+ 次,间隔 6-20ms)。现有去重(isEasFromRecentLogcat/Bug#53)都
    // 检查不到这种自触发场景,因此新增自触发时间戳,发广播后 500ms 内的 EAS 回调直接忽略。
    private static final long SELF_DISPATCH_DEDUP_MS = 500L;
    private volatile long lastSelfMediaKeyDispatchMs = 0L;
    private void markSelfMediaKeyDispatch() {
        lastSelfMediaKeyDispatchMs = System.currentTimeMillis();
    }
 /** EAS 回调是否来自 CarMedia 自己发广播触发的(自触发死循环防护) */
    private boolean isEasFromSelfDispatch() {
        return System.currentTimeMillis() - lastSelfMediaKeyDispatchMs < SELF_DISPATCH_DEDUP_MS;
    }
 /** 系统是否刚把媒体键 keyCode 实际路由到当前控制器(Bug#53:系统注入 A 直达控制器的直接证据)。
 * LogcatKeyMonitor 监听到 MediaSessionService "Sending KeyEvent ... to <pkg>" 时记录
 * (lastMediaSessionRoute*);MDC 转发到达时若 500ms 内系统已把对应媒体键路由到控制器,
 * 说明系统注入已让控制器切歌,这份 MDC 转发是重复的,应忽略(交给系统注入单通道) → 防连切2首。
 * ★ 车机版 app(QQ/酷我):系统媒体键被车机媒体中心拦截、不注入 MediaSessionService,
 * 不会有路由日志 → 恒 false → MDC 转发照常处理,不会误伤(替代被废弃的
 * isControllerMediaButtonSession dumpsys 判断)。 */
    private boolean isSystemMediaKeyRoutedToController(int keyCode) {
        if (controllerPkg == null || controllerPkg.isEmpty()) {
            return false;
        }
        if (lastMediaSessionRouteKeyCode != keyCode || lastMediaSessionRoutePkg == null) {
            return false;
        }
        // USB 模式下系统媒体键路由到 CarMedia 自身 MediaSession(真实包名
        // com.ecarx.carmedia),而 controllerPkg 是虚拟包 com.ecarx.carmedia.usb——直接比较
        // 必不相等,Bug#53 兜底对 USB 恒失效 → MDC 晚到副本无法被兜底吸收。路由到自身 =
        // 系统注入已由 CarMedia 自己的 MediaSession 处理(onMediaButtonEvent),这份 MDC
        // 转发是重复的,应忽略。
        boolean routedToSelfUsb = usbRouteArmed && mediaButtonViaSession
                && PKG_SELF.equals(lastMediaSessionRoutePkg);
        if (!routedToSelfUsb && !lastMediaSessionRoutePkg.equals(controllerPkg)) {
            return false;
        }
        // 窗口从 500ms 加长到 2s——路由记录只在系统真正注入媒体键时写入
        // (方向盘按压注入),EAS 卡片按钮不注入媒体键、不产生路由记录,长窗口不会误吞卡片按钮。
        return System.currentTimeMillis() - lastMediaSessionRouteAt < KEY_CHANNEL_DEDUP_LONG_MS;
    }
 /** MDC 转发(EAS ctrl)处理按键时打 mark,供 logcat 通道后到达时去重。
 * 原设计只有 logcat->MDC 单向去重,但 MDC 链路更短(输入服务->MDC->CarMedia 回调)
 * 可能先到达,导致 MDC 切一次 + logcat 再切一次 = 连切2首(Bug#52)。
 * 改为双向去重:不管谁先到,第一个处理的打 mark,第二个被去重。 */
    private final long[] lastMdcKeyHandleMs = new long[DIR_COUNT];
    // ---- Bug#53 : 系统媒体键路由信号(替代 dumpsys media button session 判断) ----
    // 原方案用 dumpsys media_session "Media button session is <pkg>" 判断控制器是否会被
    // 系统注入直达,但车机版 app(QQ/酷我等)可能占据该位置而系统媒体键被车机媒体中心拦截
    // 不注入(走 EAS 链路)——dumpsys 会误判为"系统注入直达"→ MDC 转发被忽略 → 直接不切歌
    // (用户指正)。改为监听 MediaSessionService "Sending KeyEvent ... to <pkg>" 日志:
    // 只有系统真正把媒体键路由到该 app 时才记录,这才是"系统注入 A 直达控制器"的直接证据。
    // 车机版 app(QQ/酷我)无路由日志 → 恒 false → MDC 转发照常处理,不误伤。
    private volatile int lastMediaSessionRouteKeyCode = -1;
    private volatile String lastMediaSessionRoutePkg;
    private volatile long lastMediaSessionRouteAt = 0L;
    private void markMdcKeyHandle(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return;
        lastMdcKeyHandleMs[dir] = System.currentTimeMillis();
    }
 /** logcat 通道是否刚被 MDC 转发处理过同一方向(500ms 窗口) */
    private boolean isMdcHandledRecently(int dir) {
        return dir >= 0 && dir < DIR_COUNT
                && System.currentTimeMillis() - lastMdcKeyHandleMs[dir] < KEY_CHANNEL_DEDUP_MS;
    }
    // ================= : 非 USB 音源"偶尔连切2首"修复(让位系统通道) =================
    // 真车 MEDIA_BUTTON 是普通广播(ordered=false),abortBroadcast() 无效(9-05 诊断确认),
    // 第三方音乐 App 自己的 MediaButtonReceiver 在"未屏蔽三键"时处于启用状态 → 广播直达 App
    // 自行切歌。若 CarMedia 的 logcat/MDC 通道再发一次命令 → 双发连切2首。
    // 反转:CarMedia 注册 FLAG_HANDLES_MEDIA_BUTTONS 媒体键代理(方案A)后,
    // 系统媒体键被 MediaSessionService 直接 dispatch 到 CarMedia 代理,播放器自身的
    // MediaButtonReceiver 收不到同一按键(真车日志确认),"让位系统通道"已无意义
    // (让位=键被吞,见 日志 13:32:39~13:33:13 八次切歌丢失)。
    // shouldCarMediaOwnKey() 现为通用接管语义,副本去重由 shouldCopyChannelAbsorb/
    // wasCopyHandledNearThisPhysicalPress 负责,不再需要"让位区分"逻辑。
    private final long[] lastLogcatKeyDownSeenMs = new long[DIR_COUNT];
 /** logcat 通道刚看到该方向的物理按键(无论是否执行)——供 MDC 区分
 * "方向盘按压副本"(应随 CarMedia 让位)与"EAS 卡片按钮"(无 logcat 信号,必须处理)。
 * 参数改为物理按压时刻(InputManager 日志行时间戳),logcat 解析延迟不影响基准。
 * 保留 mark(LogcatKeyMonitor 解析写入),读取方已改用
 * wasThisPhysicalPressHandledByLogcat 的物理时刻窗口语义。 */
    private void markLogcatKeyDownSeen(int dir, long physMs) {
        if (dir < 0 || dir >= DIR_COUNT) return;
        lastLogcatKeyDownSeenMs[dir] = physMs;
    }
    // ---- : logcat 解析延迟 >500ms 时"副本先执行"的补丁 ----
    // 真车日志实证(07:20:21 序列):物理按压 21.130 → MDC 广播 21.274 / EAS ctrl 21.275 先执行
    // → logcat 21.554 才解析(延迟 422ms),靠 isMdcHandledRecently 短窗口恰好吸收,没双切。
    // 07:20:28 序列(双切确认):物理按压 28.141 → MDC/EAS 28.315 先执行 play(34)
    // → logcat 29.388 才解析(延迟 1245ms!)→ 短窗口(500ms)失效 → logcat 再执行 play(35)
    // = 一次按压连切2首。车机高负载(歌词云请求/U盘扫描)时 logcat 管道延迟达 1s+,
    // 这正是"偶尔"连切2首(只发生在负载高时刻)的根因。
    // 修复:以"物理按压时刻"(InputManager 日志行时间戳,LogcatKeyMonitor 解析)为去重基准——
    // 副本处理 mark(MDC mark / MediaSession mark)落在 [物理时刻-150ms, 物理时刻+800ms]
    // → 是"本次按压的副本、只是先于 logcat 解析到达" → 本次 logcat 必须吸收。
    // 无论 logcat 解析延迟 422ms 还是 1245ms 都能正确识别;用户真实再次按压的物理时刻
    // 距上次副本 mark 通常 >1s → 不在窗口内 → 不误吸。
    // (间隔<800ms)且第二次副本未到时会被上次副本 mark 误吸收 → 已移除,只用物理时刻基准。
    // 窗口依据:副本执行时刻 M≈物理按压+150ms(广播→EAS 执行),下界取 0(副本必晚于物理按压,
    // 收紧后不误吞间隔>150ms 的连按/双击——KeyMonitor 双击窗口 400ms,间隔 150~400ms 的
    // 双击动作不再被 A 的副本 mark 吸收;上界 +800 覆盖副本延迟)。
    private static final long PHYS_COPY_WINDOW_BEFORE_MS = 0;
    private static final long PHYS_COPY_WINDOW_AFTER_MS = 800;
 /** 物理按压时刻基准的副本判据:本次按压的副本(先于 logcat 解析执行)应被 logcat 吸收。 */
    private boolean wasCopyHandledNearThisPhysicalPress(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return false;
        long phys = lastLogcatKeyDownSeenMs[dir];
        if (phys <= 0) return false;
        long lo = phys - PHYS_COPY_WINDOW_BEFORE_MS;
        long hi = phys + PHYS_COPY_WINDOW_AFTER_MS;
        long mdc = lastMdcKeyHandleMs[dir];
        if (mdc >= lo && mdc <= hi) return true;
        if (lastLogcatKeyHandleSrc[dir] == SRC_MEDIASESSION) {
            long ls = lastLogcatKeyHandleMs[dir];
            if (ls >= lo && ls <= hi) return true;
        }
        return false;
    }
 /** MDC 转发侧镜像判据(logcat 先执行、MDC 副本后到):最近一次物理按压是否已被 logcat 通道
 * 执行过切歌。logcat 执行 mark(仅 SRC_LOGCAT)落在 [物理时刻, 物理时刻+800ms] →
 * 该按压已由 logcat 切歌 → 这份 MDC 转发是重复副本 → 吸收。与 wasCopyHandledNearThisPhysicalPress
 * 对称,覆盖"MDC 副本晚到 >500ms 导致 isEasFromRecentLogcat 失效"的镜像双切。
 * 仅认 SRC_LOGCAT(MediaSession 路由 mark 不代表 logcat 执行了切歌,Bug#53 另有兜底)");
 * 转发时刻必须距该物理按压 2s 内(EAS 卡片按钮点击时无新 keyDown,phys 是旧按压,
 * now-phys>2s 即不检查,防误吞方向盘按压后点 EAS 按钮)。 */
    private boolean wasThisPhysicalPressHandledByLogcat(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return false;
        if (lastLogcatKeyHandleSrc[dir] != SRC_LOGCAT) return false;
        long phys = lastLogcatKeyDownSeenMs[dir];
        if (phys <= 0) return false;
        if (System.currentTimeMillis() - phys > 2000) return false;
        long ls = lastLogcatKeyHandleMs[dir];
        return ls >= phys - PHYS_COPY_WINDOW_BEFORE_MS && ls <= phys + PHYS_COPY_WINDOW_AFTER_MS;
    }
 /** 当前按键是否应由 CarMedia 自己的通道处理(而非让位系统通道)。
 * true=CarMedia 接管;false=让位。
 * 反转:由"白名单接管(仅酷狗)"改为"通用接管(仅 USB/蓝牙/屏蔽分支前置排除)"。
 * 依据:CarMedia 注册了 FLAG_HANDLES_MEDIA_BUTTONS 媒体键代理(方案A, )后,
 * 系统媒体键被 MediaSessionService 直接 dispatch 到 CarMedia,播放器自身的
 * MediaButtonReceiver 收不到同一按键(真车日志确认:MEDIA_BUTTON received 在 CarMedia,
 * 酷我等播放器零响应)。旧"让位"逻辑假设"系统通道会把键送到播放器"已不成立,
 * 让位 = 键被 CarMedia 吞掉( 日志 13:32:39~13:33:13 未勾选屏蔽三键时
 * 8 次切歌全部丢失)。因此除系统/蓝牙/USB 外,任何第三方媒体会话统一接管,
 * 由 dispatchMediaAction 四级通道控制——新播放器无需维护白名单即自动支持。 */
    private boolean shouldCarMediaOwnKey() {
        // 模拟器:无车机输入服务/无 MEDIA_BUTTON 广播链路,系统通道不存在,CarMedia 必须接管,
        // 否则开发机上非 USB 音源按键"没反应"(真车才有广播直达接收器)。
        if (isEmulator()) return true;
        // armed 残留但 USB 实际失效 → 复位,避免方控被 USB 残留劫持;有效武装时接管按键
        resetUsbRouteIfDead("shouldCarMediaOwnKey");
        if (usbRouteArmed) return true;
        if (isKeyBlockedSetting()) return true;
        // 蓝牙:CarMedia 始终接管三键——未勾选屏蔽三键时系统 MediaKeyReceiver 会把媒体键
        // 路由到"默认/活跃会话"(常是抢焦点的 QQ),用户听蓝牙时切歌没反应。
        if (isBluetoothController()) return true;
        // 通用接管:所有第三方媒体会话(含系统媒体中心代理 mediacenter/XCMedia2)统一接管。
        // 手机互联(CarPlay/HiCar)经 mediacenter 代理时 executeTransportAction 有专门
        // dispatchMediaButtonEvent 路径,接管后仍正确;XCMedia2 不会被自动选为控制器(黑名单),
        // 此分支几乎不触发。
        return true;
    }
    private boolean isEmulator() {
        try {
            return "goldfish".equalsIgnoreCase(android.os.Build.HARDWARE)
                    || "ranchu".equalsIgnoreCase(android.os.Build.HARDWARE)
                    || android.os.Build.FINGERPRINT != null
                    && android.os.Build.FINGERPRINT.startsWith("generic");
        } catch (Throwable t) {
            return false;
        }
    }
 /** 切换控制器到蓝牙且蓝牙当前未播放时,抑制向 EAS 卡片/仪表盘推送。
 * 蓝牙 MediaSession 常驻上一首歌的元数据,切过去立即推会把"旧歌 + 暂停态"送上卡片;
 * 用户要求:点播放后才推送变化。蓝牙真正播放(onPlaybackStateChanged 收到 PLAYING)时解除。 */
    private volatile boolean suppressBtPushUntilPlay = false;
 /** 服务启动时从 SharedPreferences 恢复 weDisabledMultimedia,
 * 防止进程重启后把自己之前设的 DISABLED_USER 误判为用户主动禁用。 */
    private void restoreMultimediaDisabledState() {
        try {
            weDisabledMultimedia = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean(PREF_KEY_WE_DISABLED_MM, false);
            if (weDisabledMultimedia) {
                Log.i(TAG, "restoreMultimediaDisabledState: 恢复 weDisabledMultimedia=true"
                        + " (上次蓝牙播放时由 CarMedia 禁用,重启后保持)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "restoreMultimediaDisabledState error: " + t.getMessage());
        }
    }
 /** 服务启动时从 SharedPreferences 恢复 USB 歌词来源。 */
    private void restoreUsbLyricSource() {
        try {
            String saved = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getString("usb_lyric_source", "auto");
            if (saved != null && !saved.isEmpty()) {
                // USB 歌词来源三档 auto/embedded/fallback，legacy 值 netease/soda 归并为 fallback。
                if (!"auto".equals(saved) && !"embedded".equals(saved) && !"fallback".equals(saved)) saved = "fallback";
                usbLyricSource = saved;
                Log.i(TAG, "restoreUsbLyricSource: restored " + saved);
            }
        } catch (Throwable t) {
            Log.w(TAG, "restoreUsbLyricSource error: " + t.getMessage());
        }
    }
    private void maybeKillMultimediaOnBluetoothPlay() {
        try {
            // v3:蓝牙"真正播放"判定改用 controller 实时会话 state,
            // 不再用 getAudioRealPlayState()——蓝牙 A2DP sink 的 AudioTrack 常驻
            // started,蓝牙未播放时 audioReal 也误判在播,导致 maybeKill 在蓝牙
            // 未播放时提前 request MAY_DUCK 焦点,把正在播的 QQ 音乐 duck 压低
            // (用户真车反馈 bt (3).log)。controller 实时 state==3 才是真播放。
            boolean btPlayingNow = isBluetoothController()
                    && mediaController != null
                    && mediaController.getPlaybackState() != null
            // UI 音源(如 musichall/QQ)又切回去 → AVRCP PLAY/PAUSE 拉锯(真车日志
            // bt.txt 18:13:13-25 交替确认)。只在蓝牙播放期锚定,用户暂停后 btPlayingNow
            // =false 自动停止,不会把暂停的蓝牙拉起。
            // bt.txt 18:13:13-25 交替确认)。只在蓝牙播放期锚定,用户暂停后 btPlayingNow
                    && mediaController.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
            // v2:只在"蓝牙刚进入播放"的上升沿发一次广播锚定 XCMedia2 音源,
            // 播放期间不再周期轰炸——否则用户手动暂停后,延迟执行的广播命令
            // (CallExApi 跨进程异步 → forceToSwitchBt → play)会把蓝牙重新拉起。
            // v6:蓝牙在播期间每轮(5s)做两件事:
            // ① keepXcmediaOnBluetoothSource():周期广播锚定 XCMedia2 蓝牙音源(10s 防抖)。
            // 根因:XCMedia2 音源互斥("bt pause but current external is not bt")在
            // activeExternal≠BT 时暂停蓝牙;广播 forceToSwitchBt 切回蓝牙音源,但 XCMedia2
            // UI 音源(如 musichall/QQ)又切回去 → AVRCP PLAY/PAUSE 拉锯(真车日志
            // bt.txt 18:13:13-25 交替确认)。只在蓝牙播放期锚定,用户暂停后 btPlayingNow
            // =false 自动停止,不会把暂停的蓝牙拉起。
            // ② pauseOtherSourcesOnBluetoothPlay():周期暂停其他在播音源(QQ 音乐等)。
            // 根因:QQ 被暂停/抢焦点后自动恢复(baseStart+重新请求 FULL 焦点)与蓝牙 A2DP
            // 焦点拉锯 → 蓝牙反复暂停。仅"进入播放时暂停一次"不够,须持续压制到蓝牙停止。
            if (btPlayingNow) {
                keepXcmediaOnBluetoothSource();
                pauseOtherSourcesOnBluetoothPlay();
                // v7:蓝牙在播期间周期把 EAS 播放焦点交还 XCMedia2(5s 防抖),
                // 否则 XCMedia2 bMediaCenterFocus=false → "no focus to pause" 暂停蓝牙
                // (car.txt 18:32:27.416 确认)。
                arbitrateEasFocusToMultimedia();
            }
            lastBtPlayingForKeep = btPlayingNow;
            if (!isBluetoothController()) {
                return;
            }
            if (!btPlayingNow) {
                return;
            }
            // v4:废弃 MAY_DUCK 焦点方案——反编译 ROM 确认 XCMedia2 的
            // BTMusicStateManagerP.onAudioFocusChange 是空实现,根本不处理音频焦点;
            // 它的蓝牙暂停判定只依赖 EAS 播放焦点 bMediaCenterFocus(见 arbitrate)。
            // MAY_DUCK 不仅拦不住暂停,反而把蓝牙 A2DP 自己 duck 成 -3(没声音,
            // bt (3).log 13:22:35.547 focuschange -3),还会 duck 正在播的 QQ。
            // XCMedia2 的 no-focus 暂停由 EAS 焦点仲裁(arbitrateFocusToMultimedia)
            // 解决,蓝牙播放时让蓝牙自己持 GAIN 焦点(有声)。
            if (USE_BLUETOOTH_FOCUS_HOLD) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastMultimediaKillMs < MULTIMEDIA_KILL_COOLDOWN_MS) {
                return;
            }
            // 注意:isProcessAlive 在用户层应用不可靠(getRunningAppProcesses 只返回
            // 自身进程),这里不再依赖它 -- 禁用(disable)自带幂等,重复禁用无副作用。
            lastMultimediaKillMs = now;
            disableMultimedia();
        } catch (Throwable t) {
            Log.w(TAG, "maybeKillMultimediaOnBluetoothPlay error: " + t.getMessage());
        }
    }
 /**
 * 蓝牙"首次播放被系统早暂停"的自愈兜底。
 * 系统蓝牙 A2dpSinkStreamHandler 的启动时序 bug:手机首次推流时蓝牙还没拿到
 * audio focus(audioFocus=0)→ DELAYED_PAUSE/sendAvrcpPause 把蓝牙暂停;焦点随后
 * 才授予(audioFocus=1),第二次播放即正常(bl_full(2).log 19:26:38.223/.725 确认)。
 * CarMedia 兜底:蓝牙 controller 的 PlaybackState 从 PLAYING 掉回非 PLAYING,且
 * 距"刚进入播放"不到 BT_EARLY_PAUSE_WINDOW_MS,判定为系统早暂停(非用户手动
 * 暂停——用户手动暂停通常会先 PAUSED 持续一段时间),自动补一次 play。
 * 防循环:补一次后 BT_RECOVER_COOLDOWN_MS 内不重复补;补 play 后若蓝牙被系统
 * 再次早暂停(理论不再发生),冷却过后才可能再补一次。
 */
    private void maybeRecoverBluetoothEarlyPause(int newState) {
        try {
            if (!isBluetoothController()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (newState == PlaybackState.STATE_PLAYING) {
                // 记录蓝牙刚进入播放的时间点
                lastBtPlayRisingMs = now;
                return;
            }
            // 非播放态:若"刚进入播放后 BT_EARLY_PAUSE_WINDOW_MS 内掉回"且冷却已过 → 早暂停,自愈
            if (lastBtPlayRisingMs > 0
                    && (now - lastBtPlayRisingMs) < BT_EARLY_PAUSE_WINDOW_MS
                    && now > btRecoverCoolUntil) {
                Log.i(TAG, "bt early pause detected (played " + (now - lastBtPlayRisingMs)
                        + "ms ago), auto recover with play");
                MediaController bt = findControllerByPackage(PKG_BLUETOOTH);
                if (bt != null) {
                    btRecoverCoolUntil = now + BT_RECOVER_COOLDOWN_MS;
                    lastBtPlayRisingMs = 0;      // 复位,避免重复判定
                    executeTransportAction(bt, "play");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "maybeRecoverBluetoothEarlyPause error: " + t.getMessage());
        }
    }
 /**
 * 禁用多媒体(XCMedia2):杀进程+防自启,等效用户手动"停止运行/禁用"。
 * 实车环境验证:禁用多媒体后蓝牙完全正常。
 * 蓝牙播放结束/切走音源时由 enableMultimedia() 恢复。
 * 注意:若多媒体在本次禁用前已被用户/系统禁用(wasMultimediaDisabled=true),
 * 说明用户可能本来就打算一直禁用,则后续不再自动解禁。 */
    private void disableMultimedia() {
        try {
            // 幂等:CarMedia 已成功禁用过,不再重复检查状态(否则会把自己设的
            // DISABLED_USER 误判为"用户本来就禁用",导致后续永久不解禁)。
            if (weDisabledMultimedia) {
                return;
            }
            // 先查多媒体当前启用状态:若已处于真实禁用态(DISABLED/DISABLED_USER,
            // 不含 DISABLED_UNTIL_USED 系统临时停止态),记录"原本就禁用",
            // 后续 enableMultimedia() 不再解禁(用户可能本来就打算一直禁用)。
                Log.i(TAG, ">>> 多媒体本来已禁用(用户主动),记录状态,后续不解禁");
            if (isMultimediaDisabled()) {
                Log.i(TAG, ">>> 多媒体本来已禁用(用户主动),记录状态,后续不解禁");
                wasMultimediaDisabled = true;
                return;
            }
            // 多媒体当前是启用的 → 说明用户之前没有禁用(或已解禁),
            // 重置 wasMultimediaDisabled(该标记一旦设为 true 不会自动重置,
            // 若不重置,本次 CarMedia 自己禁用的多媒体在 enableMultimedia 时
            // 会被误判为"用户主动禁用"而永久不解禁)。
            Log.i(TAG, ">>> 蓝牙播放时禁用多媒体" + PKG_MULTIMEDIA + ") 防其暂停蓝牙");
            wasMultimediaDisabled = false;
            Log.i(TAG, ">>> 蓝牙播放中,禁用多媒体(" + PKG_MULTIMEDIA + ") 防其暂停蓝牙");
            PackageManager pm = getPackageManager();
            pm.setApplicationEnabledSetting(PKG_MULTIMEDIA,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
            weDisabledMultimedia = true;
            // 持久化:进程重启后仍能识别"这是我们设的禁用",避免误判为用户主动禁用
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE).edit()
                    .putBoolean(PREF_KEY_WE_DISABLED_MM, true).apply();
            Log.i(TAG, ">>> 多媒体已禁用(蓝牙播放期间保持)");
        } catch (Throwable t1) {
            Log.w(TAG, ">>> setApplicationEnabledSetting 禁用失败,尝试 force-stop: " + t1.getMessage());
            forceStopMultimedia();
        }
    }
 /** 多媒体当前是否已处于用户主动禁用状态(DISABLED/DISABLED_USER)。
 * 注意:不含 DISABLED_UNTIL_USED(系统强制停止后的临时状态,用户打开即恢复),
 * 那不是用户主动禁用,不应阻止 CarMedia 自动解禁。 */
    private boolean isMultimediaDisabled() {
        try {
            PackageManager pm = getPackageManager();
            int state = pm.getApplicationEnabledSetting(PKG_MULTIMEDIA);
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (Throwable t) {
            Log.w(TAG, "isMultimediaDisabled error: " + t.getMessage());
            return false;
        }
    }
 /** 解禁多媒体(XCMedia2):蓝牙播放结束/离开蓝牙后恢复可用。
 * 若多媒体在 CarMedia 禁用前本来就已被用户主动禁用(wasMultimediaDisabled=true),
 * 保持禁用,不自动解禁;若 CarMedia 没禁用过(weDisabledMultimedia=false),也不解禁。 */
    private void enableMultimedia() {
        try {
                Log.i(TAG, ">>> 多媒体原本就禁用(用户主动),保持禁用,不解禁)");
            if (wasMultimediaDisabled) {
                Log.i(TAG, ">>> 多媒体原本就禁用(用户主动),保持禁用,不解禁");
                return;
            }
            Log.i(TAG, ">>> 蓝牙结束,解禁多媒体" + PKG_MULTIMEDIA + ")");
            if (!weDisabledMultimedia) {
                return; // CarMedia 没禁用过,不需要解禁
            }
            Log.i(TAG, ">>> 蓝牙结束,解禁多媒体(" + PKG_MULTIMEDIA + ")");
            PackageManager pm = getPackageManager();
            pm.setApplicationEnabledSetting(PKG_MULTIMEDIA,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            weDisabledMultimedia = false;
            // 清除持久化标记
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE).edit()
                    .remove(PREF_KEY_WE_DISABLED_MM).apply();
            Log.i(TAG, ">>> 多媒体已解禁");
        } catch (Throwable t1) {
            Log.w(TAG, ">>> setApplicationEnabledSetting 解禁失败: " + t1.getMessage());
        }
    }
 /** force-stop 多媒体(XCMedia2),防其"无焦点就暂停蓝牙"。
 * 用户层应用可能无 FORCE_STOP_PACKAGES 权限(仅 system uid),
 * 失败时静默,由 disableMultimedia 兜底。 */
    private void forceStopMultimedia() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            Method m = ActivityManager.class.getMethod("forceStopPackage", String.class);
            m.invoke(am, PKG_MULTIMEDIA);
            Log.i(TAG, ">>> force-stop 多媒体成功");
        } catch (Throwable t1) {
            Log.w(TAG, ">>> forceStopPackage 反射失败: " + t1.getMessage());
        }
    }
 /** 蓝牙真正播放时:请求 MAY_DUCK 音频焦点(蓝牙焦点归 CarMedia)。
 * 系统会把原持有方 XCMedia2 的焦点变化置为 LOSS_TRANSIENT_CAN_DUCK(-3),
 * 而 XCMedia2 对 -3 免疫、不暂停蓝牙 → 无需再整体禁用 XCMedia2。
 * v2:调用时机从"切蓝牙/A2DP 连接"收紧为"蓝牙真正播放时"
 * (sendPlayPause play 分支 / onPlaybackStateChanged PLAYING / maybeKill 上升沿)——
 * 蓝牙未播放时提前拿 MAY_DUCK 会把正在播的 QQ 等音源 duck 压低音量(用户实测反馈)。 */
    private void requestBluetoothFocusIfNeeded() {
        if (!USE_BLUETOOTH_FOCUS_HOLD || btFocusHeld) {
            return;
        }
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) {
            return;
        }
        try {
            int r = audioManager.requestAudioFocus(btFocusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            btFocusHeld = (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            Log.i(TAG, ">>> request BT MAY_DUCK focus, result=" + r + ", held=" + btFocusHeld);
        } catch (Throwable t) {
            Log.w(TAG, "requestBluetoothFocusIfNeeded error: " + t.getMessage());
        }
    }
 /** 离开蓝牙/蓝牙停止时:释放 MAY_DUCK 焦点,恢复系统默认焦点归属。 */
    private void abandonBluetoothFocus() {
        if (!btFocusHeld) {
            return;
        }
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(btFocusListener);
            Log.i(TAG, ">>> abandon BT MAY_DUCK focus");
        } catch (Throwable t) {
            Log.w(TAG, "abandonBluetoothFocus error: " + t.getMessage());
        }
        btFocusHeld = false;
    }
 /** 蓝牙在播时,周期性发 ECARX_WIDGET_BLUETOOTH_PLAY 广播,把 XCMedia2 锚定在蓝牙音源,
 * 避免它因"非蓝牙音源页"的音源互斥逻辑暂停蓝牙。 */
    private void keepXcmediaOnBluetoothSource() {
        long now = System.currentTimeMillis();
        if (now - lastBtSourceKeepMs < BT_SOURCE_KEEP_INTERVAL_MS) {
            return;
        }
        try {
            Intent i = new Intent("ecarx.intent.broadcast.action.ECARX_WIDGET_BLUETOOTH_PLAY");
            i.setPackage(PKG_MULTIMEDIA);   // Android 8+ 定向投递,命中 XCMedia2 动态 receiver
            sendBroadcast(i);
            lastBtSourceKeepMs = now;
            Log.i(TAG, ">>> keep XCMedia2 on BT source (ECARX_WIDGET_BLUETOOTH_PLAY)");
        } catch (Throwable t) {
            Log.w(TAG, "keepXcmediaOnBluetoothSource error: " + t.getMessage());
        }
    }
 /** 蓝牙会话是否存在(com.android.bluetooth 的 MediaSession 出现在活跃列表)。
 * 用于三键接管判断:只要蓝牙连接/会话在,方向盘三键由 CarMedia 接管直达蓝牙,
 * 不受"当前 mediaController 是否是蓝牙"限制(蓝牙暂停时控制器常被切到别的源)。 */
    private boolean isBluetoothSessionActive() {
        boolean present = false;
        try {
            present = findControllerByPackage(PKG_BLUETOOTH) != null;
        } catch (Throwable t) {
            present = false;
        }
        // v8:同步给 MediaCenterBridge——蓝牙会话存在时 CarMedia 一律不抢 EAS 焦点
        // (否则 XCMedia2 判定蓝牙 no-focus → 蓝牙播放被暂停,见 shouldGrabEasFocus)。
        MediaCenterBridge.setBluetoothSessionPresent(present);
        return present;
    }
 /** 蓝牙播放时周期触发 EAS 焦点仲裁,把播放焦点交还 XCMedia2。
 * 根因:CarMedia 在非蓝牙控制器推送时 requestPlay 抢走 EAS 焦点,XCMedia2 的
 * bMediaCenterFocus=false,蓝牙播放瞬间被它 "no focus to pause" 暂停
 * (真车日志 car.txt 18:32:27.416 确认)。MDC 检测到 CarMedia 未 requestPlay 后
 * 会自动把焦点给回 com.ecarx.multimedia → 蓝牙播放不再被暂停。 */
    private void arbitrateEasFocusToMultimedia() {
        long now = System.currentTimeMillis();
        if (now - lastEasArbitrateMs < EAS_FOCUS_ARBITRATE_INTERVAL_MS) {
            return;
        }
        try {
            MediaCenterBridge bridge = MediaCenterBridge.get();
            if (bridge == null) {
                return;
            }
            // (car_full(7).log 确认蓝牙反复暂停):原逻辑每5s无条件仲裁,
            // 即使 queryCurrentFocusClient 已返回 com.ecarx.multimedia 仍 requestPlay
            // 重新切给 XCMedia2 → 每次切换触发 switchEngineByExternal: bt → 蓝牙暂停→恢复。
            // 修复:焦点已在 XCMedia2 时直接跳过,不再制造多余切换;仅焦点被抢(不在
            // multimedia)时才仲裁还回去(保留 no-focus 兜底)。EAS 未注册时 isMultimediaHoldingFocus()
            // 返回 false → 仍走仲裁,行为与原先一致,安全。
            if (bridge.isMultimediaHoldingFocus()) {
                return;
            }
            bridge.arbitrateFocusToMultimedia();
            lastEasArbitrateMs = now;
        } catch (Throwable t) {
            Log.w(TAG, "arbitrateEasFocusToMultimedia error: " + t.getMessage());
        }
    }
 /** 蓝牙真正播放时,主动暂停其他在播的音乐音源(QQ 音乐等),避免双音源同时放。
 * 原因:焦点方案用 MAY_DUCK,QQ 音乐收到 CAN_DUCK(-3) 通常只降音量不暂停。 */
    private void pauseOtherSourcesOnBluetoothPlay() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return;
            }
            List<MediaController> sessions = msm.getActiveSessions(null);
            if (sessions == null) {
                return;
            }
            for (MediaController c : sessions) {
                String pkg = c.getPackageName();
                // 排除自身、蓝牙、系统媒体包/通话管理(不可暂停通话)
                if (pkg == null || PKG_SELF.equals(pkg) || PKG_BLUETOOTH.equals(pkg)
                        || isHiddenController(pkg)) {
                    continue;
                }
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    Log.i(TAG, ">>> pause other source while BT playing: " + pkg);
                    executeTransportAction(c, "pause");
                    if (!pausedOtherSources.contains(pkg)) {
                        pausedOtherSources.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pauseOtherSourcesOnBluetoothPlay error: " + t.getMessage());
        }
    }
 /** 离开蓝牙(切走/停止)时,恢复之前被 CarMedia 暂停的其他音源(若当前未在播)。 */
    private void resumeOtherSourcesOnBluetoothStop() {
        try {
            if (pausedOtherSources.isEmpty()) {
                return;
            }
            // Bug#47:离开蓝牙(切到非蓝牙)时,若当前控制器已在真实播放
            // (如用户主动切到酷我且正在播放),说明蓝牙是被新音源抢焦点而停止的,
            // 用户已有明确播放选择 → 不应恢复旧 pausedOtherSources,否则会把播放权
            // 抢回旧源(真车实测:QQ→蓝牙→酷我播放 1 秒后突然跳回 QQ 音乐)。
            if (isControllerReallyPlaying()) {
                pausedOtherSources.clear();
                return;
            }
            List<String> toResume = new ArrayList<>(pausedOtherSources);
            pausedOtherSources.clear();
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return;
            }
            List<MediaController> sessions = msm.getActiveSessions(null);
            if (sessions == null) {
                return;
            }
            for (MediaController c : sessions) {
                String pkg = c.getPackageName();
                // 跳过"用户刚选中的控制器"——切换控制器只是"选中",
                // 不应自动播放。实车环境验证:蓝牙播放暂停 QQ 后,在主界面切控制器到
                // QQ,QQ 立刻被这里 resume 的 play 拉起(用户期望切过去保持暂停、
                // 点播放才播)。蓝牙自然停止(未手动选中)时 controllerPkg 不等于该源,
                // 仍会正常恢复被暂停的音源。
                if (pkg != null && toResume.contains(pkg)
                        && !pkg.equals(controllerPkg)) {
                    PlaybackState ps = c.getPlaybackState();
                    if (ps == null || ps.getState() != PlaybackState.STATE_PLAYING) {
                        Log.i(TAG, ">>> resume other source after BT stop: " + pkg);
                        executeTransportAction(c, "play");
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resumeOtherSourcesOnBluetoothStop error: " + t.getMessage());
        }
    }
    // 自动切源延迟重试:AudioTrack 已 started 但 MediaSession 尚未 PLAYING 时,
    // 不直接放弃,延迟后重试一次(等音源 MediaSession 状态异步更新到位)。
    // 典型场景:刚点网易云播放,AudioTrack 立刻出声,但 MediaSession state 延迟几百毫秒才变 PLAYING。
    private final android.os.Handler autoSwitchRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String pendingAutoSwitchPkg = null;
    private final Runnable autoSwitchRetryRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                String pkg = pendingAutoSwitchPkg;
                pendingAutoSwitchPkg = null;
                if (pkg == null) return;
                if (pkg.equals(controllerPkg)) {
                    Log.d(TAG, "auto-switch retry: " + pkg + " already current controller, give up");
                    return;
                }
                MediaController target = findControllerByPackage(pkg);
                if (target == null) {
                    Log.i(TAG, "auto-switch retry: " + pkg + " no active media session anymore, give up");
                    return;
                }
                if (!isSessionReallyPlaying(target)) {
                    Log.i(TAG, "auto-switch retry: " + pkg + " still not PLAYING after delay, give up");
                    return;
                }
                if (!isAudioReallyPlaying(pkg)) {
                    Log.i(TAG, "auto-switch retry: " + pkg + " audio track stopped, give up");
                    return;
                }
                Log.i(TAG, "auto-switch retry: " + pkg + " now PLAYING, switching");
                lastAutoSwitchTime = System.currentTimeMillis();
                pauseCurrentController();
                selectController(target);
            } catch (Throwable t) {
                Log.w(TAG, "auto-switch retry error: " + t.getMessage());
            }
        }
    };
    private final android.os.Handler firstPushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable firstPushRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (!firstPushPending || !isDimPushEnabled()) {
                    return;
                }
                firstPushRetryCount++;
                // Bug#Q2：开机/服务激活首推门控——没放歌就不推 EAS（车机刚开机
                // 就有 EAS 推送，应该放歌才推）。只有真实音源在播时才执行首推；
                // 没放歌直接停止重试循环，等真实播放事件自行推送。
                if (!isAnySourcePlaying()) {
                    Log.i(TAG, "first push skip: nothing playing, no EAS push on boot");
                    firstPushHandler.removeCallbacks(this);
                    return;
                }
                pushMetadataToCard();
                if (firstPushPending && mediaController == null) {
                    pushStoppedToCard();
                }
                if (firstPushPending) {
                    if (firstPushRetryCount < 60) {
                        Log.i(TAG, "first push retry #" + firstPushRetryCount
                                + ": EAS 注册未完成,首推未送达,继续重试");
                        firstPushHandler.postDelayed(this, 800L);
                        Log.w(TAG, "first push retry: 60 次未送达,暂时放弃(后续音源回调/轮询仍会推送");
                    } else {
                        Log.w(TAG, "first push retry: 60 次未送达,暂时放弃(后续音源回调/轮询仍会推送)");
                    }
                } else {
                    Log.i(TAG, "first push delivered (service activated)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "first push retry error: " + t.getMessage());
            }
        }
    };
    private int firstPushRetryCount;
    private volatile Thread mSelfKillThread; // Bug#67 自杀式清空：延迟自杀线程(可被重开中断)
    private boolean positionTickerRunning; // 进度推送循环(仿 MediaBridge 2s ticker)
    private static final long POSITION_TICKER_INTERVAL_MS = 2000L;
    private final Runnable positionTickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!positionTickerRunning) {
                return;
            }
            try {
                // 根因修复:USB 播放时以 usbSourceController 状态为权威。
                // 虚拟包名 com.ecarx.carmedia.usb 无真实 UID → getPackageUid 返回 -1 →
                // getAudioRealPlayState() 恒返 0 → 原判定"不在播"把 ticker 停掉 →
                // USB 切歌后无触发源 → 仪表盘冻结在上一首(用户实测"一直显示第一首")。
                boolean usbPlaying = usbRouteArmed && usbSourceController != null
                        && usbSourceController.getPlayState() == UsbMusicPlayer.STATE_PLAYING;
                // 播放中才推进度;暂停时停止 ticker(MediaBridge 同款逻辑:
                // z6 = state==3,播放中才启动 positionTicker)
                if (usbPlaying || getAudioRealPlayState() == STATE_PLAYING) {
                    // 播放中但没有歌名时,主动刷新元数据。
                    // 酷狗等播放器 MediaSession inactive 时收不到 onMetadataChanged 回调,
                    // UI 一直显示"等待音乐播放"。每 2 秒轮询一次,有歌名就停止刷新。
                    if (currentTitle == null || currentTitle.isEmpty()) {
                        refreshMetadataIfMissing();
                    }
                    // 进度推送同时检测内容变化:歌名/歌词/歌手/状态任一变化
                    // 都全量推送(pushMetadataToCard 内部有 MediaBridge 式去重,
                    // 内容没变只推进度,不会刷屏)。
                    checkContentChangedAndPush();
                } else {
                    positionTickerRunning = false;
                    Log.d(TAG, "position ticker stopped (not playing)");
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "position ticker error: " + t.getMessage());
            }
            lyricPollHandler.postDelayed(this, POSITION_TICKER_INTERVAL_MS);
        }
    };
    private int currentSourceType = SOURCE_TYPE_NONE;
 /** 当前歌曲收藏状态（本地维护 + 点击收藏后翻转；尽力与音源同步）。移植 MediaBridge setCollected。 */
    private volatile boolean mCollected = false;
    private PlaybackState lastPlaybackState;
    // ---- 自动切换(新音源开始播放时切过去)----
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback audioPlaybackCallback;
    private String lastAudioPlayingPkg; // 上次检测到的在播包名(去抖/变化检测)
    private long lastAutoSwitchTime;    // 上次自动切换时间(冷却)
 /** seek 保护窗截止时间(ms)。seek 命令瞬间音源 AudioTrack 可能短暂
 * 闪断/重启,后台常驻 AudioTrack 的包(汽水/QQ 车机版等)会被 handlePlaybackConfigChanged
 * 误判为"新在播源"自动切走控制器,表现为"拖进度条就切歌"。seek 成功后 2 秒内禁止自动切换。 */
    private volatile long seekProtectUntil = 0;
    private static final long AUTO_SWITCH_COOLDOWN_MS = 3000L;
    // ---- VOIP 通话检测(微信电话等挂断后自动续播蓝牙音乐)----
    private boolean voipActive = false; // 当前是否有 VOIP 通话进行中
    // ---- 通话中断控制器恢复 ----
    // 场景:酷我在播 → 来电 → 通话管理(com.android.server.telecom)抢走控制器
    // → 挂断 → telecom 会话消失 → 重新选源时被蓝牙残留 AudioTrack 抢先选中,
    // 酷我(后台暂停,无 started AudioTrack)再也回不来。
    // 方案:通话开始时记住通话前的控制器包名,通话结束(telecom 会话消失)后
    // 优先切回它;保护期内忽略"蓝牙残留 AudioTrack"诱发的自动切换。
    private String preCallControllerPkg;   // 通话开始前的控制器包名
    private boolean inTelecomCall;         // 当前是否处于 telecom 通话(com.android.server.telecom 为控制器)
    private long telecomEndProtectUntil = 0; // 通话结束后保护期截止时间戳
    private static final long TELECOM_END_PROTECT_MS = 5000L; // 通话结束后保护期 5 秒
    private BroadcastReceiver a2dpSinkReceiver; // A2DP sink 连接状态监听
 /**
 * A2DP sink 是否真实连接。
 * 用于区分"真实蓝牙连接"与"幽灵蓝牙 MediaSession"——本车机 com.android.bluetooth 的
 * MediaSession 常驻(即使没连手机也存在于会话列表,真车日志 13:48:32 确认),
 * 控制器自动选择不能只凭会话存在就把蓝牙选为控制器。
 * 由 A2DP CONNECTION_STATE_CHANGED 广播维护;服务启动时用 getProfileConnectionState 查一次初始值。
 */
    private volatile boolean a2dpSinkConnected = false;
    // ---- P1 生命周期字段 ----
    private MediaSession mediaSession;
    private MediaSessionManager sessionManager;
 /** 会话变化监听是否注册成功（Bug#70）。
 * 首次安装时通知监听权限尚未启用，addOnActiveSessionsChangedListener(cn)
 * 会抛 SecurityException 注册失败 → 之后音源变化永远不触发自动刷新，
 * 控制器列表停在空。授权后由 retryInitSessionManager/onNotificationListenerConnected
 * 重试补注册。 */
    private volatile boolean sessionListenerRegistered = false;
 /** 会话监听注册失败的重试计数（上限 5 次，间隔 2s）。 */
    private volatile int sessionListenerRetryCount = 0;
 /** 方案A :CarMedia 自建 MediaSession 作为媒体键代理是否就绪。
 * true=系统媒体键经 MediaSession.Callback.onMediaButtonEvent 直达 CarMedia,
 * 不再发 ACTION_MEDIA_BUTTON 全局广播,酷狗等静态接收器收不到按键;
 * LogcatKeyMonitor 通道需跳过 prev/next/playpause 防双触发。
 * false=MediaSession 创建失败或未激活,回退到 LogcatKeyMonitor + 广播拦截器。 */
    private volatile boolean mediaButtonViaSession = false;
    private boolean dimAutoPush;
    // 歌词屏蔽列表:勾选后该控制器不再调用任何歌词 API(含兜底),UI 直接显示歌名/歌手
    // 持久化键 lyric_blocked_pkgs(逗号分隔包名)");判断入口 isLyricBlocked(pkg)
    private final java.util.Set<String> lyricBlockedPkgs = new java.util.HashSet<>();
    // ---- P4 歌词字段 ----
    private CloudLyricFetcher cloudLyricFetcher;
    private QQMusicLyricFetcher qqMusicFetcher;
    private SodaMusicLyricFetcher sodaMusicFetcher;
 /** 酷我专属歌词抓取器(酷我官方 r.s 搜索 + songinfoandlrc 歌词)。 */
    private KuwoLyricFetcher kuwoLyricFetcher;
 /** 酷狗专属歌词抓取器(酷狗官方 mobilecdn 搜索 + lyrics.kugou.com 歌词)。 */
    private KugouLyricFetcher kugouLyricFetcher;
 /** 授权流程消息回调(无 root 等提示),由 MainActivity 注册后转发到 JS toast。 */
    public interface AuthMessageListener {
        void onAuthMessage(String msg);
    }
    private AuthMessageListener mAuthListener;
    public void setAuthMessageListener(AuthMessageListener l) {
        mAuthListener = l;
    }
    private android.os.Handler lyricPollHandler;
    private long lastLyricPosition = -1L;
    private String lastUiLyricLine;
    private String lastPushedLyricLine;
    // 通用歌词自动补抓节流状态:任何音源只要当前歌的歌词抓取器未就绪,
    // 轮询就自动补抓(不依赖 handleMetadata 回调,兼容 MediaSession 不可靠音源如酷狗)。
    // 同一首歌有限重试,防"该歌确实无歌词"时无限刷网络;切歌/换源时按歌名重置计数。
    private static final long AUTO_LYRIC_RETRY_MS = 5000L;      // 两次补抓最小间隔
    private static final int AUTO_LYRIC_RETRY_MAX = 4;          // 同一首歌最多补抓次数
    private long lastAutoLyricRetryAt = 0L;
    private int autoLyricRetryCount = 0;
    private String autoLyricRetryTitle = "";
    private int lastPushedLyricState;
    private String lastPushedLyricForTitle; // EAS 标题推歌词行时的去重基准
    // USB 推送去重的权威索引——切歌必推,不被 title 污染/相同歌名影响
    private int lastPushedUsbIndex = -1;
    // 切歌日志去重(仅索引变化时打一条,防 progress 每秒刷屏)
    private int lastUsbLogIndex = -2;
    /** 最近一次成功提取的封面 URI(content:// 或 http(s)://),null=无封面(EAS 封面推送用)。 */
    private String currentArtworkUri;
 /** 封面保底:当前封面对应的 歌名|艺术家。
 * 音源切歌瞬间连续推多条 metadata(先错后对,ART 异步加载),中间某条缺封面
 * 会把已有封面清空(用户实测"封面1秒不到闪没"+触发 CoverFallback 换图)。
 * 同歌 metadata 抖动(提取到 null 但歌未变)保留旧封面,真实切歌才清空。
 * extractArtworkAndPush 是网易云/QQ/酷我/酷狗/蓝牙等全部 metadata 音源的
 * 统一封面入口,此保底对全部音源通用;EAS 封面共用 currentArtworkUri,同步保底。 */
    private String coverSongKey = "";
 /** USB 封面保底:异步补读(ensureUsbCoverAsync)完成前保留旧封面,
 * 宽限到期仍未读到新封面才清空——有封面不闪没,无封面防错封面残留。 */
    private static final long USB_COVER_GRACE_MS = 1200L;
    private Runnable usbCoverGraceRunnable;
 /** 扫描提速配套:按需补封面去重锁(同一首歌只启动一次异步读取)。 */
    private java.util.Set<String> coverLoadingKeys;
 /** 封面延迟重试(音源常先发 title/artist,ART 位图异步加载后才到位且不更新 metadata,
 * 导致小卡片/仪表盘首次无封面,要重新播放一次才出)。重试防抖 + 限次。 */
    private boolean artworkRetryScheduled = false;
    private int artworkRetryCount = 0;
    // ---- 网易云广播内嵌歌词(桥接.apk 同款机制)----
    // 网易云 IoT 播放时会广播 metachanged/lyricchanged,intent extra 直接带歌词
    // 内容(lyric/lyrics/lrc/currentLyric/line 等 key),本地直取,无需调 API。
    // 优先级高于网络抓取:广播有歌词 → 直接用;无歌词 → 走 CloudLyricFetcher。
    private static final String[] NETEASE_LYRIC_KEYS = {
            "lyric", "lyrics", "lrc", "currentLyric", "currentLine",
            "current_line", "oneLineLyric", "line", "netease_lyric", "curLyric"
    };
    private BroadcastReceiver neteaseLyricReceiver;
    private String broadcastLyricPkg;   // 广播来源包名(仅当前控制器接受)
    private String broadcastLyricTitle; // 广播歌曲名(防串歌)
    private String broadcastLyricLrc;   // 广播内嵌歌词(完整 LRC 或单行)
    private String broadcastLyricLine;  // 广播内嵌当前歌词行
    private BroadcastReceiver createNeteaseLyricReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    String action = intent != null ? intent.getAction() : null;
                    if (action == null) {
                        return;
                    }
                    String pkg = intent.getStringExtra("package");
                    if (pkg == null) {
                        pkg = intent.getStringExtra("pkg");
                    }
                    if (pkg == null) {
                        pkg = intent.getStringExtra("packagename");
                    }
                    if (pkg == null) {
                        // 没有显式包名:尝试从 intent component 推断
                        if (intent.getComponent() != null) {
                            pkg = intent.getComponent().getPackageName();
                        }
                    }
                    // 只接受当前控制器的广播(防串台:其他 App 广播/残留广播不得覆盖)
                    if (pkg == null || !pkg.equals(controllerPkg)) {
                        Log.d(TAG, "netease broadcast ignored: pkg=" + pkg
                                + " controller=" + controllerPkg);
                        return;
                    }
                    Bundle extras = intent.getExtras();
                    if (extras == null) {
                        return;
                    }
                    // 歌名(防串歌:广播歌词必须属于当前歌)
                    String bTitle = firstString(extras, "title", "songName", "songname",
                            "track", "name", "android.media.metadata.TITLE");
                    if (bTitle == null || bTitle.isEmpty()) {
                        bTitle = currentTitle;
                    }
                    // 歌词内容:优先完整 LRC(带 [mm:ss]),否则单行歌词
                    String lrc = null;
                    String line = null;
                    for (String key : NETEASE_LYRIC_KEYS) {
                        Object v = extras.get(key);
                        if (v == null) {
                            continue;
                        }
                        String s = v.toString().trim();
                        if (s.isEmpty()) {
                            continue;
                        }
                        if (s.indexOf('[') == 0 || s.indexOf('\n') >= 0) {
                            // 完整 LRC(首字符 [ 或含换行)
                            lrc = s;
                            break;
                        } else {
                            // 单行歌词
                            line = s;
                        }
                    }
                    if (lrc == null && line == null) {
                        // 广播无歌词内容(纯 metachanged/playstatechanged):仅更新元数据
                        applyBroadcastMetadata(extras, bTitle, pkg);
                        return;
                    }
                    // 归属校验:广播歌名与当前歌名一致才接受歌词
                    if (!titlesMatch(bTitle, currentTitle)) {
                        Log.d(TAG, "netease broadcast lyric skipped (title mismatch): "
                                + bTitle + " vs " + currentTitle);
                        return;
                    }
                    broadcastLyricPkg = pkg;
                    broadcastLyricTitle = currentTitle;
                    broadcastLyricLrc = lrc;
                    broadcastLyricLine = line;
                    // 广播歌词就绪:立即推送一次,UI 直接进入歌词模式
                    if (lrc != null && !lrc.isEmpty()) {
                        Log.i(TAG, "Netease broadcast LRC received: " + lrc.length() + " chars");
                        pushBroadcastLyric(lrc, true);
                    } else if (line != null && !line.isEmpty()) {
                        Log.i(TAG, "Netease broadcast line: " + line);
                        pushBroadcastLyric(line, false);
                    }
                    // 元数据更新(title/artist 同步)
                    applyBroadcastMetadata(extras, bTitle, pkg);
                } catch (Throwable t) {
                    Log.w(TAG, "netease broadcast error: " + t.getMessage());
                }
            }
        };
    }
 /** 从 Bundle 中取第一个非空字符串 extra。 */
    private static String firstString(Bundle b, String... keys) {
        for (String k : keys) {
            Object v = b.get(k);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
 /** 归一化比较:去空白/全半角括号/分隔符/大小写(与归属校验同款)。 */
    private static boolean titlesMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return TextUtil.normalize(a).equals(TextUtil.normalize(b));
    }
 /** 广播元数据应用到当前显示(歌名/歌手,通知权威同语义)。 */
    private void applyBroadcastMetadata(Bundle extras, String bTitle, String pkg) {
        try {
            if (!pkg.equals(controllerPkg)) {
                return;
            }
            String artist = firstString(extras, "artist", "singer", "author",
                    "android.media.metadata.ARTIST");
            if (bTitle != null && !bTitle.isEmpty() && titlesMatch(bTitle, currentTitle)) {
                currentTitle = bTitle;
            }
            if (artist != null && !artist.isEmpty()) {
                currentArtist = artist;
                qqArtistComplete = true;
            }
            if (isDimPushEnabled()) {
                pushMetadataToCard();
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyBroadcastMetadata error: " + t.getMessage());
        }
    }
 /** 推送广播歌词(完整 LRC 交给 LyricPoll 按进度取行;单行直接推)。 */
    private void pushBroadcastLyric(String content, boolean isLrc) {
        try {
            // 屏蔽列表命中:广播歌词也不进歌词缓存/不直接推(UI 保持歌名/歌手)
            if (isLyricBlocked(controllerPkg)) {
                Log.d(TAG, "pushBroadcastLyric: lyric blocked for " + controllerPkg + ", ignore broadcast lyric");
                return;
            }
            if (isLrc) {
                // 完整 LRC:缓存到 cloudLyricFetcher 的 LRC 槽位,LyricPoll 按进度取行
                if (cloudLyricFetcher != null) {
                    cloudLyricFetcher.setBroadcastLrc(content, currentTitle, currentArtist);
                    Log.i(TAG, "Broadcast LRC bound to fetcher, starting poll");
                    startLyricPoll();
                }
            } else {
                // 单行歌词:直接推送(去重:同一行不重复推,防通知每秒重复触发)
                if (content.equals(lastPushedLyricLine)) {
                    return;
                }
                String display = currentTitle;
                if (currentArtist != null && !currentArtist.isEmpty()) {
                    display = display + " - " + currentArtist;
                }
                lastPushedLyricLine = content;
                lastPushedLyricState = STATE_PLAYING;
                Log.i(TAG, "LyricPoll line=" + content);
                // ★ 修复:单行广播歌词实际可用也同步接口状态,避免状态灯停在空闲黄。
                if (cloudLyricFetcher != null) {
                    cloudLyricFetcher.markBroadcastLyricSuccess();
                }
                try {
                    MediaCenterBridge.get().pushLyric(display, 0, content);
                } catch (Throwable t) {
                    Log.e(TAG, "pushLyric exception: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pushBroadcastLyric error: " + t.getMessage());
        }
    }
    private void registerNeteaseLyricReceiver() {
        try {
            if (neteaseLyricReceiver != null) {
                return;
            }
            neteaseLyricReceiver = createNeteaseLyricReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.netease.cloudmusic.metachanged");
            filter.addAction("com.netease.cloudmusic.lyricchanged");
            filter.addAction("com.android.music.metachanged");
            filter.addAction("com.android.music.lyricchanged");
            filter.addAction("com.android.music.playstatechanged");
            // Android 13+ 需要动态注册(接收器非导出)
            registerReceiver(neteaseLyricReceiver, filter);
            Log.i(TAG, "netease lyric receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "register netease receiver failed: " + t.getMessage());
        }
    }
    private void unregisterNeteaseLyricReceiver() {
        if (neteaseLyricReceiver != null) {
            try {
                unregisterReceiver(neteaseLyricReceiver);
            } catch (Throwable t) {
                Log.w(TAG, "unregister netease receiver error: " + t.getMessage());
            }
            neteaseLyricReceiver = null;
        }
    }
    // ================= A2DP Sink 连接状态监听 =================
    // 监听蓝牙 A2DP sink 连接/断开:重连后主动刷新控制器列表,
    // 解决手机断开重连后无法直接播放(必须在车机点播放)的问题。
    private void registerA2dpSinkReceiver() {
        try {
            if (a2dpSinkReceiver != null) {
                return;
            }
            // 初始化 A2DP 连接状态。服务启动时蓝牙可能已连接,之后不会有
            // 状态变更广播;用 getProfileConnectionState 查一次初始值,供控制器自动选择
            // 区分"真实蓝牙连接"与"幽灵蓝牙 MediaSession"(未连手机也存在)。
            try {
                android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (ba != null) {
                    // 注:BluetoothProfile.A2DP_SINK=11 / STATE_CONNECTED=2
                    // (部分系统 jar 存根不含 A2DP_SINK 常量,直接写字面值)
                    a2dpSinkConnected = ba.getProfileConnectionState(11) == 2;
                    Log.i(TAG, "a2dp-sink initial state: connected=" + a2dpSinkConnected);
                }
            } catch (Throwable t) {
                Log.w(TAG, "a2dp-sink initial state query failed: " + t.getMessage());
            }
            a2dpSinkReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (action == null) return;
                    int state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                    // 维护 A2DP 连接标志(STATE_CONNECTED=2 / STATE_DISCONNECTED=0)
                    if (state == 2) {
                        a2dpSinkConnected = true;
                    } else if (state == 0) {
                        a2dpSinkConnected = false;
                    }
                    Log.i(TAG, "a2dp-sink state changed: " + state + " connected=" + a2dpSinkConnected);
                    // STATE_CONNECTED = 2
                    if (state == 2) {
                        // A2DP 连接:立即禁用多媒体(XCMedia2),防止其
                        // "无焦点就暂停蓝牙"霸权逻辑在蓝牙播放瞬间触发。
                        // 实车环境验证:手动禁用多媒体后蓝牙播放完全正常。
                        // 蓝牙一连接就禁用,比等播放更早,不给暂停逻辑机会。
                        // 优先 MAY_DUCK 焦点持有方案(不整体禁用 XCMedia2,
                        // 避免破坏通话 HFP);开关关闭时回退到禁用旧方案。
                        // 注意:这里不再发"播放蓝牙"广播锚定音源——蓝牙刚连接不一定播放,
                        // 发广播会主动拉起蓝牙,和正在播的 QQ 音乐/收音机形成双音源;
                        // 锚定交给 maybeKill 的"蓝牙真正播放"上升沿处理。
                        // v2:蓝牙连接(未播放)时也**不再** request MAY_DUCK 焦点——
                        // 连接瞬间拿焦点同样会 duck 当前正在播的 QQ/收音机(声音被压低)。
                        // MAY_DUCK 改到 sendPlayPause 蓝牙 play 分支(真正播放时才拿)。
                        if (!USE_BLUETOOTH_FOCUS_HOLD) {
                            disableMultimedia();
                        }
                        // A2DP 重连:延迟 3 秒后刷新控制器列表
                        // (蓝牙栈需要时间重建 MediaSession)
                        firstPushHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Log.i(TAG, "a2dp-sink reconnected, refresh controllers");
                                refreshControllerList();
                                // 如果当前没有选中蓝牙控制器,且蓝牙有活跃会话,切过去
                                // USB 音源激活时不切——蓝牙"连接"不代表要播,
                                // 防止 USB 播放中被 A2DP 连接事件切到蓝牙。
                                if (!usbRouteArmed && !isBluetoothController()) {
                                    // ★ (修复):放宽为 isBluetoothSelectable——蓝牙
                                    // 物理就绪(A2DP+AVRCP)且无其他真实音源在播即可切到蓝牙。
                                    // 原 isBluetoothActuallyPlaying(双连接+会话PLAYING)要求过高,
                                    // 开机时手机刚连上/未放/刚暂停(state≠3)永远切不过去,
                                    // 导致控制器不选蓝牙→系统不给 A2DP sink 音频焦点→蓝牙被暂停。
                                    // 下方 findReallyPlayingController 仍会挡掉"其他源真在播"场景,
                                    // 不会回归 QQ 在播时中途连蓝牙被误切的旧 Bug。
                                    if (!isBluetoothSelectable()) {
                                        Log.i(TAG, "a2dp-sink: connected but bluetooth not selectable (not ready or other source playing), keep current controller");
                                        return;
                                    }
                                    // ★ 方案A :蓝牙只是"连上了",若已有其他音源
                                    // (网易云/QQ/酷我等)真实在播,不抢切到蓝牙--避免语音唤醒/
                                    // 蓝牙连接瞬间把播放中的网易云误切到蓝牙控制器。
                                    // 判定:findReallyPlayingController 返回非空且不是蓝牙自己。
                                    MediaController reallyPlaying = findReallyPlayingController();
                                    if (reallyPlaying != null
                                            && !PKG_BLUETOOTH.equals(reallyPlaying.getPackageName())) {
                                        Log.i(TAG, "a2dp-sink: another source really playing ("
                                                + reallyPlaying.getPackageName()
                                                + "), keep current controller, don't switch to bluetooth");
                                        return;
                                    }
                                    MediaController btController = findControllerByPackage("com.android.bluetooth");
                                    if (btController != null) {
                                        Log.i(TAG, "a2dp-sink: select bluetooth controller (bluetooth really playing)");
                                        selectController(btController);
                                    }
                                }
                            }
                        }, 3000L);
                    } else {
                        // A2DP 断开(STATE_DISCONNECTED=0):蓝牙不播了,
                        // 延迟 5 秒解禁多媒体,恢复其可用。
                        Log.i(TAG, "a2dp-sink disconnected, schedule re-enable multimedia in 5s");
                        firstPushHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                abandonBluetoothFocus();
                                enableMultimedia();
                            }
                        }, 5000L);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            registerReceiver(a2dpSinkReceiver, filter);
            Log.i(TAG, "a2dp-sink receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "register a2dp-sink receiver failed: " + t.getMessage());
        }
    }
    private void unregisterA2dpSinkReceiver() {
        if (a2dpSinkReceiver != null) {
            try {
                unregisterReceiver(a2dpSinkReceiver);
            } catch (Throwable t) {
                Log.w(TAG, "unregister a2dp-sink receiver error: " + t.getMessage());
            }
            a2dpSinkReceiver = null;
        }
    }
    // ================= VOIP 通话挂断后自动续播 =================
    // 微信电话等 VOIP 通话结束后,延迟 2 秒尝试恢复蓝牙音乐播放。
    // 触发条件:AudioPlaybackCallback 检测到 USAGE_VOICE_COMMUNICATION 从有到无。
    private void tryResumeAfterVoip() {
        firstPushHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    // 确保当前是蓝牙控制器(主要场景:蓝牙音乐被 VOIP 打断)
                    if (!isBluetoothController()) {
                        Log.d(TAG, "voip-resume: not bluetooth controller, skip");
                        return;
                    }
                    if (mediaController == null) {
                        Log.d(TAG, "voip-resume: no controller, skip");
                        return;
                    }
                    // 检查当前是否已经在播放(避免重复 play)
                    PlaybackState ps = mediaController.getPlaybackState();
                    if (ps != null && ps.getState() == STATE_PLAYING) {
                        Log.d(TAG, "voip-resume: already playing, skip");
                        return;
                    }
                    // 确认 VOIP 确实结束了(不再有 VOIP AudioTrack)
                    if (voipActive) {
                        Log.d(TAG, "voip-resume: voip still active, skip");
                        return;
                    }
                    Log.i(TAG, "voip-resume: calling play() on " + controllerPkg);
                    executeTransportAction(mediaController, "play");
                } catch (Throwable t) {
                    Log.w(TAG, "voip-resume error: " + t.getMessage());
                }
            }
        }, 2000L);
    }
    // ---- 回调 ----
    private final MediaController.Callback controllerCallback =
            new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    if (metadata != null) {
                        handleMetadata(metadata);
                    }
                }
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    if (state != null) {
                        // 蓝牙真正进入播放 → 解除"切到蓝牙未播放"的推送抑制,
                        // 让本次 updatePlaybackStatus 正常把蓝牙歌曲/播放态推上卡片。
                        if (state.getState() == PlaybackState.STATE_PLAYING) {
                            suppressBtPushUntilPlay = false;
                        }
                        updatePlaybackStatus(state);
                        // 蓝牙"首次播放被系统早暂停"自愈兜底。蓝牙刚进入
                        // PLAYING 后极短时间内掉回非播放(系统 A2DP sink 焦点未就绪
                        // 触发的早暂停,bl_full(2).log 确认),非用户主动暂停,自动补 play。
                        // 内部自带"刚播放就掉回"窗口 + 补 play 冷却,不会误拉起用户手动暂停。
                        maybeRecoverBluetoothEarlyPause(state.getState());
                        // 蓝牙控制器进入"播放"时,即时发广播锚定 XCMedia2 在蓝牙音源
                        // (forceToSwitchBt→activeExternal=BT),防止它因音源互斥(其他页面)暂停蓝牙。
                        // 比 maybeKill 的 5s 轮询更及时,能赶在互斥暂停前/后用广播恢复。
                        // 用户手动暂停时状态是 PAUSED,不触发 → 不会被拉起。
                        if (isBluetoothController()
                                && state.getState() == PlaybackState.STATE_PLAYING) {
                            // v4:废弃 MAY_DUCK(反编译确认 XCMedia2 不处理音频
                            // 焦点,MAY_DUCK 只会把蓝牙 A2DP duck 成没声音)。蓝牙播放时
                            // 蓝牙自己持 GAIN 焦点;XCMedia2 的 no-focus 暂停由切蓝牙时
                            // 的 EAS 焦点仲裁(arbitrateFocusToMultimedia)解决。
                            keepXcmediaOnBluetoothSource();
                            // 蓝牙播放时暂停其他在播音源(QQ 音乐),避免双音源。
                            pauseOtherSourcesOnBluetoothPlay();
                            // v7:蓝牙播放时也即时仲裁 EAS 焦点交还 XCMedia2
                            // (解决 no focus 暂停;maybeKill 5s 轮询 + 此处即时双保险)。
                            arbitrateEasFocusToMultimedia();
                        }
                    }
                }
                @Override
                public void onSessionDestroyed() {
                    Log.w(TAG, "MediaController session destroyed");
                    // 音源被停止/杀掉:先推送停止状态(防止小卡片残留"播放中"),再重新选择
                    pushStoppedToCard();
                    refreshController();
                }
            };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    // 双音源并发时会话变化事件很频繁(每个音源 metadata/state 更新
                    // 都可能触发),无条件 refreshController 会导致控制器来回跳
                    // (曾实测 1 秒内切换 4 次),并产生歌名/歌手混搭的中间态。
                    // 修复:当前控制器仍活跃时只刷新列表不切换;
                    // 仅当当前控制器失效/不在列表时才重新选择。
                    try {
                        // 选中 CarPlay 时,白名单盒子 MediaSession 出现即自动合并
                        // (盒子连接/手机连接触发会话变化,免手动点条目)
                        autoMergeCarplayBoxIfPresent();
                        // USB 音源激活时，禁止自动切源逻辑把 USB 播放切走
                        // USB 播放时 CarMedia 自己的 AudioTrack 会被误判为新音源
                        if (usbRouteArmed) {
                            Log.d(TAG, "session changed: usb route armed, skip auto-switch");
                            return;
                        }
                        // ghost 控制器保护:用户选了暂停态会话(如酷我),
                        // mediaController 置空是预期行为,不要自动重选。
                        // 保护窗口化(ghostProtectUntil),窗口外允许
                        // re-select,否则 ghost 永久挡住会话变化事件,新音源出现
                        // 无法自动跟随,控制器/歌词停在旧源。
                        if (ghostControllerPkg != null && System.currentTimeMillis() < ghostProtectUntil) {
                            Log.d(TAG, "active sessions changed, ghost controller active, skip re-select");
                            return;
                        }
                        if (mediaController == null || controllerStale) {
                            Log.i(TAG, "active sessions changed, re-select controller");
                            refreshController();
                            return;
                        }
                        String curPkg = mediaController.getPackageName();
                        boolean stillActive = false;
                        if (controllers != null) {
                            for (MediaController c : controllers) {
                                if (c != null && curPkg != null
                                        && curPkg.equals(c.getPackageName())) {
                                    stillActive = true;
                                    break;
                                }
                            }
                        }
                        if (!stillActive) {
                            Log.i(TAG, "active sessions changed, current controller gone, re-select");
                            // 仅当活跃会话一个都不剩(音源真停了)才推停止态;
                            // 若还有别的会话(如网易云切到 QQ),交给 refreshController
                            // 直接选新源,避免小卡片闪一下"停止"再变"播放"
                            boolean anyOtherSession = false;
                            if (controllers != null) {
                                for (MediaController c : controllers) {
                                    if (c != null && !PKG_SELF.equals(c.getPackageName())) {
                                        anyOtherSession = true;
                                        break;
                                    }
                                }
                            }
                            if (!anyOtherSession) {
                                pushStoppedToCard();
                            }
                            refreshController();
                            return;
                        }
                        // 通用切源检测(不针对任何特定 App):
                        // 当前控制器仍活跃时,检查活跃会话里是否有"真实在播"
                        // (AudioTrack started,反射 AudioPlaybackConfiguration)且
                        // 不是当前控制器的--有则切过去。
                        // 场景:网易云在播时用户打开 QQ 播放--网易云会话仍活跃,
                        // 但真实音频已切到 QQ,此时必须切走(否则仪表盘一直推旧源)。
                        try {
                            // 排除当前控制器自身找"新的真实在播源":当前控制器是蓝牙时,
                            // 蓝牙 A2DP sink AudioTrack 常驻 started,会被 findReallyPlayingController
                            // 当"真在播"顶掉 QQ,导致打开 QQ 播放不切(偶发漏切)。
                            MediaController playing = findReallyPlayingController(curPkg);
                            // ★ 蓝牙例外 :语音唤醒/任何会话变化
                            // 事件都会触发本回调。蓝牙 A2DP sink 的 AudioTrack 常驻 started,
                            // 会被 findReallyPlayingController 误判为"真实在播",导致手动暂停
                            // QQ 后一唤醒语音就被强行切到蓝牙控制器。
                            // 修复:当前控制器不是蓝牙时,蓝牙不作为自动切源候选。
                            if (playing != null && !curPkg.equals(playing.getPackageName())
                                    // (USB/蓝牙自动播放开关):开关关闭时蓝牙不作为自动切源
                                    // 目标(即使真在播也不切),避免开机/中途被自动接管;开启保持原行为。
                                    && !(PKG_BLUETOOTH.equals(playing.getPackageName())
                                         && (!PKG_BLUETOOTH.equals(curPkg) || !isUsbAutoResumeEnabled()))) {
                                Log.i(TAG, "active sessions changed, real playing "
                                        + playing.getPackageName() + " != current " + curPkg
                                        + ", switch");
                                pauseCurrentController();
                                selectController(playing);
                                return;
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "session changed real-playing check error: " + t.getMessage());
                        }
                        // 无真实在播切换需求:仅刷新列表,不动当前选择
                        refreshControllerList();
                    } catch (Throwable t) {
                        Log.w(TAG, "session changed refresh error: " + t.getMessage());
                    }
                }
            };
    public class LocalBinder extends Binder {
        public CarMediaService getService() {
            return CarMediaService.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "CarMediaService onCreate");
            CarMediaWidget.updateAll(this);
            CarMediaSkyWidget.updateAll(this);
        startForegroundNotification();
        // Bug#67: auto-reopen main UI only for the imminent self-kill restart.
        // Timestamp window (10s) guards against late keep-alive relaunches popping the UI.
        try {
            long reopenAt = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getLong("ui_reopen_at", 0L);
            if (reopenAt > 0L && System.currentTimeMillis() - reopenAt < 10000L) {
                getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                        .edit().remove("ui_reopen_at").apply();
                easFocusHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "onCreate: auto reopen main UI after self-kill restart");
                        openMainUi();
                    }
                }, 1500L);
            } else if (reopenAt > 0L) {
                // stale mark from a self-kill whose restart happened too late:
                // drop it silently, never pop the UI unexpectedly
                getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                        .edit().remove("ui_reopen_at").apply();
                Log.i(TAG, "onCreate: stale ui_reopen_at ignored (>10s), UI not reopened");
            }
        } catch (Throwable ignored) {
        }
        // Bug#70 :通知监听授权必须先于会话监听注册与初始扫描。
        // 首次安装时权限未启用，addOnActiveSessionsChangedListener(cn) 会抛
        // SecurityException；先授权（Settings.Secure 写入）再注册才能成功，
        // 否则会话变化事件永不触发、控制器列表永远不自动刷新（只能靠手动刷新恢复）。
        grantNotificationPermission();
        initSessionManager();
        // 酷我真实进度 AIDL 客户端(切到酷我控制器时 start/切走 stop)
        kuwoAidlClient = new KuwoAidlClient(this);
        kuwoAidlClient.setOnPosListener(new KuwoAidlClient.OnPosListener() {
            @Override
            public void onPosReady() {
                // AIDL 首个真实位置就绪:重置非蓝牙累加器,让下一次 getPlaybackPosition()
                // 直接以 AIDL 真实位置初始化(重开酷我后 MediaSession position 恒 0,避免
                // 进度条从 0 累加一段再校准)。
                lastNonBtPositionMs = 0;
                lastNonBtPositionTime = 0;
                Log.i(TAG, "kuwo AIDL pos ready, reset non-bt accumulator");
            }
        });
        // 酷狗封面 AIDL 客户端(不立即 bind,选中酷狗控制器时 start)
        kugouCoverProvider = new KugouCoverProvider(this);
        // USB 本地音乐控制器初始化
        usbSourceController = UsbSourceController.getInstance();
        usbSourceController.init(this);
        // (Bug#72D) + :USB/蓝牙开机自动播放由设置项控制(默认开,
        // 存 carmedia_prefs)。init 里 USB 默认启用,此处按设置实时覆盖;扫描完成前生效。
        // 蓝牙侧由 isBluetoothSelectable() 读同一开关(关闭时不自动接管蓝牙控制器)。
        usbSourceController.setAutoResumeEnabled(isUsbAutoResumeEnabled());
        usbSourceController.setUsbStateCallback(new UsbSourceController.UsbStateCallback() {
            @Override
            public void onStateChanged() {
                // USB 状态变化：更新 MediaSession + 推送 UI
                // USB unmount: auto reset usbRouteArmed so BT/QQ music scan resumes
                // 修复(USB切歌偶发切到蓝牙根因):复位判断不再依赖 isUsbMounted()——
                // 它走 AdaptAPI 反射,首次反射失败/缓存为空时误判未挂载 → armed 偶发复位
                // → 下次切歌 dispatchMediaAction 走外部逻辑切到蓝牙。改用真实拔出事件标志
                // (UsbSourceController.isUnmountedEventPending,onUsbUnmounted 置位),与反射完全解耦,
                // 也不会误伤扫描中/列表为空的正常挂载场景。
                if (usbRouteArmed && usbSourceController != null
                        && usbSourceController.isUnmountedEventPending()) {
                    usbRouteArmed = false;
                    currentSourceType = SOURCE_TYPE_NONE; // (A1)
                    // 拔出后 controllerPkg 可能残留 USB(USB 播放分流 :4134 改过)——
                    // 清空触发重选,否则蓝牙 metadata 被来源校验拦截(蓝牙页面显示 USB 旧歌)。
                    if (UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)) {
                        controllerPkg = null;
                        Log.i(TAG, ">>> USB unmount event: clear residual controllerPkg=USB");
                    }
                    Log.i(TAG, ">>> USB unmount event, reset usbRouteArmed, resume external controller scan");
                    updateAvailableControllers();
                }
                // ★ 兜底（问题②③手动播放场景根因）：USB 正在播放/准备中
                // 但 usbRouteArmed 被外部事件误复位（旧版：蓝牙 A2DP 常驻 started 被
                // handlePlaybackConfigChanged 误判为"新在播源"→ selectController(蓝牙)
                // → armed=false → 主界面标题断链(问题②)、仪表盘退回蓝牙信息(问题③)）。
                // USB 在播即重新武装；用户主动切走音源时 deactivate() 已 player.release()
                // → playState=IDLE，不会抢回；U 盘拔出时 isUsbMounted()=false，也不触发。
                // 去掉 isUsbMounted() 条件——反射不可靠;播放器在播(PREPARING/PLAYING)
                // 本身就证明 U 盘还在。拔出后播放器已 IDLE,不会误 re-arm。
                if (usbSourceController != null
                        && (usbSourceController.getPlayState() == UsbMusicPlayer.STATE_PLAYING
                            || usbSourceController.getPlayState() == UsbMusicPlayer.STATE_PREPARING)
                        && !usbRouteArmed
                        // (Bug#72E):已有真实外部非蓝牙音源在播(如汽水)时不 re-arm——
                        // 否则 USB 被暂停/被焦点恢复拉起后,onStateChanged(PLAYING) 会把
                        // usbRouteArmed 重新武装,按键全被 USB 分支抢走("汽水在播,按切歌却切了 USB")。
                        // 只认非蓝牙:蓝牙 A2DP 常驻 started+session 残留 PLAYING 会被
                        // isBluetoothReallyPlaying 误判(09-12 场景),算进去会挡住 USB 切歌
                        // 时该有的 re-arm,回归旧 Bug。
                        && !isRealNonBluetoothSourcePlaying()) {
                    usbRouteArmed = true;
                    currentSourceType = SOURCE_TYPE_USB; // (A1)
                    Log.i(TAG, "USB playing but usbRouteArmed reset, re-arm (event-driven)");
                }
                // 修复：切歌瞬间立即同步主界面标题/歌手。
                // 之前 currentTitle 只在 triggerUsbLyricFetch(需 state==PLAYING)时更新，
                // PREPARING 阶段前端/仪表盘拿到的是旧歌名；且被误切走后标题断链停在上一首。
                if (usbRouteArmed && usbSourceController != null) {
                    UsbMusicItem usbIt = usbSourceController.getCurrentItem();
                    if (usbIt != null) {
                        currentTitle = usbIt.title != null ? usbIt.title : "未知歌曲";
                        currentArtist = usbIt.artist != null ? usbIt.artist : "未知歌手";
                        // 真车定位日志——仅切歌(索引变化)时打,确认索引与歌名同步
                        int usbNowIdx = usbSourceController.getCurrentIndex();
                        if (usbNowIdx != lastUsbLogIndex) {
                            lastUsbLogIndex = usbNowIdx;
                            Log.i(TAG, "usb onStateChanged: idx=" + usbNowIdx
                                    + " title=" + currentTitle + " artist=" + currentArtist);
                        }
                    }
                }
                // 开机自动恢复——扫描完成后若记忆了上次歌曲且 USB 挂载，
                // 自动激活 USB 音源并播放上次歌曲（一次性，consume 后不再触发）。
                if (usbSourceController != null && usbSourceController.consumeAutoResumePending()
                        && usbSourceController.isUsbMounted()) {
                    // 自动恢复不抢播——插入 U 盘时若蓝牙/QQ 等真实音源正在
                    // 播放,不自动切到 USB(否则会打断用户正在听的音乐)");此时仅保留记忆,
                    // 用户手动切到 USB tab 时仍从记忆索引继续。USB 自身的 AudioTrack
                    // 在 getRealPlayingMusicPkg 中被 PKG_SELF 过滤,不会误判为"其他源"。
                    if (isOtherSourceReallyPlaying()) {
                        Log.i(TAG, "USB auto-resume skipped: another source is really playing");
                    } else {
                        int savedIdx = usbSourceController.getSavedLastIndex();
                        // 越界保护：换了 U 盘/歌曲变少时 clamp 到末尾（列表空则跳过）
                        int maxIdx = usbSourceController.getMusicCount() - 1;
                        if (savedIdx > maxIdx) {
                            savedIdx = maxIdx;
                        }
                        // (用户需求):无记忆(-1,从未播放过)时播第一首——开机自动
                        // 续播不再要求"有记忆",与 UsbSourceController 置 pending 的放宽配套。
                        if (savedIdx < 0) {
                            savedIdx = 0;
                        }
                        if (savedIdx >= 0) {
                            if (!usbRouteArmed) {
                                usbRouteArmed = true;
                                currentSourceType = SOURCE_TYPE_USB; // (A1)
                                usbSourceController.activate();
                            }
                            // (同 activateUsbSource):开机自动续播时同样把 controllerPkg
                            // 切到 USB + 清 suppressBtPushUntilPlay + 同步 EAS bridge,
                            // 否则 USB 已开机在播但 controllerPkg 卡蓝牙 → 歌词/推送/EAS 焦点全错。
                            if (!UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)) {
                                controllerPkg = UsbSourceController.SOURCE_PKG_USB;
                                MediaCenterBridge.setControllerPkg(controllerPkg);
                            }
                            suppressBtPushUntilPlay = false;
                            usbSourceController.play(savedIdx);
                            Log.i(TAG, "USB auto-resume: playing saved index " + savedIdx);
                        }
                    }
                }
                updateUsbMediaSession();
                // USB 状态变化时按当前来源触发歌词抓取（triggerUsbLyricFetch 内部有 songKey 去重,
                // 同歌不重复加载,零开销)。 :去掉 PLAYING/PREPARING 条件——
                // 默认内嵌歌词首次播放偶发不加载(状态回调时序/条件未满足),现在任何状态变化
                // (含暂停/停止/切歌)都会触发,只要 currentItem 存在就尝试加载。
                if (usbRouteArmed && usbSourceController != null
                        && usbSourceController.getCurrentItem() != null) {
                    triggerUsbLyricFetch();
                }
                // 修复:切歌后前端封面串上一首(模拟器复现:Barium 无封面却显示
                // 上一首 cov_test 的雪地封面;用户真车:"同一首歌有时显示有时不显示")。
                // 根因:onStateChanged 推前端前从未更新 currentArtworkUri,它仍指向上一首歌
                // 的封面 URI。这里按当前歌重新解析——有缓存立即给;无缓存清空旧封面(防残留)
                // 并异步补读(ensureUsbCoverAsync 内部 coverLoadingKeys 去重,零重复)。
                if (usbRouteArmed && usbSourceController != null) {
                    UsbMusicItem curArtItem = usbSourceController.getCurrentItem();
                    if (curArtItem != null) {
                        String artNow = resolveUsbArtworkCached(curArtItem);
                        if (artNow != null) {
                            currentArtworkUri = artNow;
                        } else {
                            // USB 封面保底:异步补读完成前保留旧封面(不闪没),
                            // 宽限到期仍未读到才清空(防错封面残留,原 防串歌语义保留)。
                            String prevArt = currentArtworkUri;
                            if (prevArt == null) {
                                currentArtworkUri = null;
                            } else {
                                scheduleUsbCoverGrace(prevArt);
                            }
                            ensureUsbCoverAsync(curArtItem);
                        }
                    }
                }
                // 通知 MainActivity 刷新（通过 sInstance 切主线程调 pushAllPublic）
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (MainActivity.sInstance != null) {
                                MainActivity.sInstance.pushAllPublic();
                            }
                        }
                    });
                }
                // ★ (Bug: USB 暂停 EAS 仍显示播放)：原 USB 状态变化(尤其暂停,
                // setState=3 索引不变 onStateChanged 不打索引日志)后没有 EAS 推送触发点，
                // pushMetadataToCard 只在 PLAYING/PREPARING 的 USB 分支被外部调用方触发，
                // 暂停时无人调用 → EAS 小卡片停在"播放中"。这里补一个 USB 状态变化推送：
                // pushMetadataToCard 内部有去重(usbTrackChanged/usbStateChanged)与
                // USB 分流条件(刚已放宽到 PAUSED+controllerPkg==USB)，暂停会因
                // easState PLAYING→PAUSED 变化触发全量推送，播放/切歌照常，不会刷屏。
                if (usbRouteArmed && usbSourceController != null && isDimPushEnabled()) {
                    pushMetadataToCard();
                }
            }
        });
        // 方案A：移除 CarMedia 自建 MediaSession。
        // 该 Session 声明了 HANDLES_MEDIA_BUTTONS 但无 Callback，会抢占系统媒体键/蓝牙 AVRCP
        // 路由，导致开机蓝牙播放不了（命令被吞），打开 MainActivity 后因前台 Activity 优先级
        // 高于后台 Session 才恢复。EAS 推送走 MediaCenterBridge，不依赖此 Session。
        initMediaSession(); // 方案A :激活自建 MediaSession 作为媒体键代理,拦截酷狗抢方控
        initLyricFetchers();
        initKeyMonitor();
        restoreDimAutoPush();
        restoreLyricBlockedList();
        restoreMultimediaDisabledState();
        restoreUsbLyricSource();
        initAudioPlaybackMonitor();
        registerA2dpSinkReceiver();
        // 网易云广播内嵌歌词监听(桥接.apk 同款机制):网易云播放时广播直接带歌词
        registerNeteaseLyricReceiver();
        // 注入 EAS 控制回调:小卡片/仪表盘按钮 → 本服务的真实媒体控制
        try {
            // Bug#67(A) : when the toggle is OFF, do NOT register as an EAS
            // client at all. A live client (even one that pushes nothing) keeps XSF
            // "has client" -> DIM card stays. Skipping init means a process relaunch
            // (alarm UI reopen -> BIND_AUTO_CREATE, task-removed restart, etc.) cannot
            // bring the card back; reopening the toggle re-inits via ensureAlive().
            if (isDimPushEnabled()) {
                MediaCenterBridge.get().init(this);
                MediaCenterBridge.setController(this);
            } else {
                Log.i(TAG, "dim_auto_push OFF: skip EAS bridge init (no client registered, DIM stays clear)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "EAS bridge init failed: " + t.getMessage());
        }
        refreshController();
        // Bug#70 :首次安装自愈——授权后系统绑定通知监听器需要时间，
        // 会话监听注册与初始扫描都可能失败。延迟重试注册 + 重扫控制器列表，
        // 即使 onListenerConnected 未及时到达也能恢复"首装控制器找不到音源"。
        retryInitSessionManager();
        firstPushHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "onCreate: delayed controller rescan after listener grant");
                refreshControllerList();
                if (mediaController == null || controllerStale) {
                    refreshController();
                }
            }
        }, 1500L);
        // (Bug#72B):开机补扫选中控制器——车机冷启动时音源(酷我/酷狗等)
        // MediaSession 可能注册晚于开机扫描,1.5s 重扫后仍可能没选中(用户实测:音源在播
        // 但控制器没选上,打开主界面后要手动选)。在 mediaController==null 期间按
        // 3s/8s/18s/38s 递增补扫 refreshController(),选中即停。
        scheduleBootControllerRescan(3000L, 4);
        // 前台服务激活即推送一次:启动首推重试循环(EAS 注册完成前推送被静默丢弃,
        // 循环等注册完成后送达即停,不等音源回调)
        startFirstPushRetry();
        // 已废弃 :不再抢 EAS 播放焦点。抢焦点会让 XCMedia2
        // 判定"焦点不在我"→ 蓝牙一播放就被它 AVRCP 暂停。焦点保持空反而让
        // XCMedia2 判定自己有焦点、不暂停蓝牙。仅保留观察日志循环(5s 周期)。
        startEasFocusGuard();
        Log.i(TAG, "CarMediaService onCreate done");
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: START_STICKY");
        // 调试/验证入口:直接按 track_id 抓汽水歌词(模拟器端到端验证用)
        if (intent != null && "com.ecarx.carmedia.DEBUG_SODA_LYRIC".equals(intent.getAction())) {
            String tid = intent.getStringExtra("track_id");
            if (tid != null && !tid.isEmpty() && sodaMusicFetcher != null) {
                sodaMusicFetcher.fetchByTrackId(tid, currentTitle, currentArtist);
                Log.i(TAG, "onStartCommand: DEBUG_SODA_LYRIC track_id=" + tid);
            }
            return START_STICKY;
        }
        // 调试/验证入口 - 直接按歌名/歌手抓酷我歌词(模拟器端到端验证用)
        if (intent != null && "com.ecarx.carmedia.DEBUG_KUWO_LYRIC".equals(intent.getAction())) {
            String t = intent.getStringExtra("title");
            String a = intent.getStringExtra("artist");
            if (t != null && !t.isEmpty() && kuwoLyricFetcher != null) {
                kuwoLyricFetcher.search(t, a);
                Log.i(TAG, "onStartCommand: DEBUG_KUWO_LYRIC title=" + t + " artist=" + a);
            }
            return START_STICKY;
        }
        // 调试/验证入口 - 直接按歌名/歌手抓酷狗歌词(模拟器端到端验证用)
        if (intent != null && "com.ecarx.carmedia.DEBUG_KUGOU_LYRIC".equals(intent.getAction())) {
            String t = intent.getStringExtra("title");
            String a = intent.getStringExtra("artist");
            if (t != null && !t.isEmpty() && kugouLyricFetcher != null) {
                kugouLyricFetcher.search(t, a);
                Log.i(TAG, "onStartCommand: DEBUG_KUGOU_LYRIC title=" + t + " artist=" + a);
            }
            return START_STICKY;
        }
        // 调试入口 - 直接触发媒体控制(模拟器端到端验证用,避免UI点击位置不准)
        if (intent != null) {
            String act = intent.getAction();
            if ("com.ecarx.carmedia.DEBUG_NEXT".equals(act)) {
                Log.i(TAG, ">>> DEBUG_NEXT: controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null")
                        + " stale=" + controllerStale);
                sendNext();
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_PREV".equals(act)) {
                Log.i(TAG, ">>> DEBUG_PREV: controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
                sendPrevious();
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_PLAYPAUSE".equals(act)) {
                Log.i(TAG, ">>> DEBUG_PLAYPAUSE: controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
                sendPlayPause();
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_DUMP".equals(act)) {
                Log.i(TAG, ">>> DEBUG_DUMP: controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null")
                        + " stale=" + controllerStale
                        + " currentTitle=" + currentTitle);
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_SET_KUGOU".equals(act)) {
                Log.i(TAG, ">>> DEBUG_SET_KUGOU: directly set ghost controller to kugou");
                setGhostControllerPkg("com.kugou.android.auto");
                Log.i(TAG, ">>> DEBUG_SET_KUGOU done: controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_SET_KUWO".equals(act)) {
                Log.i(TAG, ">>> DEBUG_SET_KUWO: directly set ghost controller to kuwo");
                setGhostControllerPkg("cn.kuwo.kwmusiccar");
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_SEND_MEDIA_ACTION".equals(act)) {
                String ma = intent.getStringExtra("action");
                Log.i(TAG, ">>> DEBUG_SEND_MEDIA_ACTION: action=" + ma
                        + " controllerPkg=" + controllerPkg
                        + " ghostPkg=" + ghostControllerPkg
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null")
                        + " stale=" + controllerStale);
                if (ma != null) {
                    sendMediaAction(ma);
                }
                return START_STICKY;
            } else if ("com.ecarx.carmedia.WIDGET_LYRIC_DEC".equals(act)
                    || "com.ecarx.carmedia.WIDGET_LYRIC_INC".equals(act)) {
                // 小部件字号 -/+ 按钮
                android.content.SharedPreferences sp = getSharedPreferences("widget_cfg", MODE_PRIVATE);
                int mode = sp.getInt("lyric_size_mode", 1);
                if ("com.ecarx.carmedia.WIDGET_LYRIC_INC".equals(act)) {
                    if (mode < 3) mode++;
                } else {
                    if (mode > 0) mode--;
                }
                sp.edit().putInt("lyric_size_mode", mode).apply();
                sendBroadcast(new Intent("com.ecarx.carmedia.ACTION_WIDGET_UPDATE").setPackage(getPackageName()));
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_DISPATCH_KEY".equals(act)) {
                // 用 MediaController.dispatchMediaButtonEvent() 发送媒体键
                int keyCode = intent.getIntExtra("keyCode", 85);
                Log.i(TAG, ">>> DEBUG_DISPATCH_KEY: keyCode=" + keyCode
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
                if (mediaController != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    mediaController.dispatchMediaButtonEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
                    mediaController.dispatchMediaButtonEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
                    Log.i(TAG, ">>> DEBUG_DISPATCH_KEY: dispatched to " + mediaController.getPackageName());
                }
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_TOGGLE_DIM".equals(act)) {
                // 调试入口:直接切换 EAS 推送开关(模拟器端到端验证用,避免UI点击不准)
                // extra: value=true/false;不带则取当前状态取反
                boolean v = intent.getBooleanExtra("value", !isDimPushEnabled());
                Log.i(TAG, ">>> DEBUG_TOGGLE_DIM value=" + v + " (current=" + isDimPushEnabled() + ")");
                setDimAutoPush(v);
                return START_STICKY;
            } else if ("com.ecarx.carmedia.DEBUG_PENDING_INTENT".equals(act)) {
                // 用反射获取 MediaController.getMediaButtonReceiver() 然后 PendingIntent.send()
                int keyCode = intent.getIntExtra("keyCode", 85);
                Log.i(TAG, ">>> DEBUG_PENDING_INTENT: keyCode=" + keyCode
                        + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
                if (mediaController != null) {
                    try {
                        java.lang.reflect.Method m = android.media.session.MediaController.class.getMethod("getMediaButtonReceiver");
                        Object pi = m.invoke(mediaController);
                        if (pi instanceof android.app.PendingIntent) {
                            android.app.PendingIntent pendingIntent = (android.app.PendingIntent) pi;
                            Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                            long now = android.os.SystemClock.uptimeMillis();
                            keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
                            pendingIntent.send(CarMediaService.this, 0, keyIntent);
                            keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
                            pendingIntent.send(CarMediaService.this, 0, keyIntent);
                            Log.i(TAG, ">>> DEBUG_PENDING_INTENT: sent to " + mediaController.getPackageName());
                        } else {
                            Log.w(TAG, ">>> DEBUG_PENDING_INTENT: no PendingIntent, got " + pi);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, ">>> DEBUG_PENDING_INTENT error: " + e.getMessage());
                    }
                }
                return START_STICKY;
            }
        }
        // START_STICKY 重启时 intent 可能为 null,onCreate 已做完整初始化,这里仅确认
        if (intent == null) {
            Log.i(TAG, "onStartCommand: restarted by system (intent==null), service already initialized in onCreate");
        }
        // 前台服务激活即推送一次:onCreate 已启动首推重试循环(EAS 注册完成前
        // 推送会被静默丢弃,循环在注册完成后送达即停)
        startFirstPushRetry();
        return START_STICKY;
    }
 /**
 * EAS 注册完成回调(由 MediaCenterBridge.onAPIReady 调用):
 * 服务激活时的首推若因注册未完成被丢弃,注册完成后补推一次,
 * 保证小卡片/仪表盘在服务启动后立即出现,不等音源回调。
 */
    public void onEasRegistered() {
        // 注册完成:重置并立即再试一次首推(重试循环由 onCreate/onStartCommand
        // 启动;post 到主线程执行,避免与 onCreate 的控制器初始化并发竞争)
        startFirstPushRetry();
    }
 /** 启动 EAS 焦点守卫循环(2s 周期,onCreate 调用一次即可)。 */
    private void startEasFocusGuard() {
        easFocusHandler.removeCallbacks(easFocusRunnable);
        easFocusHandler.postDelayed(easFocusRunnable, 2000L);
    }
 /**
 * 是否有真实音源正在播放（Bug#Q2 首推门控用）。
 * 用于"开机/服务激活首推"：只有真实音源在播才推 EAS，没放歌就不推。
 * 覆盖 USB / 蓝牙 / 常规音源三种在播判定。
 */
    private boolean isAnySourcePlaying() {
        try {
            if (usbRouteArmed && usbSourceController != null
                    && usbSourceController.getPlayState() == UsbMusicPlayer.STATE_PLAYING) {
                return true;
            }
            if (isBluetoothController() && mediaController != null) {
                PlaybackState ps = mediaController.getPlaybackState();
                return ps != null && ps.getState() == STATE_PLAYING;
            }
            if (mediaController == null) {
                return false;
            }
            return getAudioRealPlayState() == STATE_PLAYING;
        } catch (Throwable t) {
            return false;
        }
    }
 /**
 * EAS 焦点被其他客户端抢走时回调(由 CarMediaMusicClient.onMediaCenterFocusChanged
 * 触发)。★已废弃 :不再反抢焦点。反抢会触发 XCMedia2 的
 * "无焦点就暂停蓝牙"霸权逻辑;焦点保持空反而让 XCMedia2 判定自己有焦点、不暂停蓝牙。
 * 仅保留日志观察。
 */
    public void onEasFocusLost() {
        try {
            if (MediaCenterBridge.get() != null) {
                MediaCenterBridge.get().logFocusOnce();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onEasFocusLost error: " + t.getMessage());
        }
    }
 /** 前台服务激活即推送一次:EAS 注册完成前推送会被静默丢弃,
 * 用主线程重试循环兜底(注册完成后下一次尝试即送达并停止)。
 * onEasRegistered(注册完成回调)会重置并立即再试。 */
    private void startFirstPushRetry() {
        firstPushRetryCount = 0;
        firstPushHandler.removeCallbacks(firstPushRunnable);
        if (firstPushPending && isDimPushEnabled()) {
            firstPushHandler.post(firstPushRunnable);
        }
    }
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务划掉本应用:前台服务会被系统停止。
        // 重新拉起自己,保证小卡片/仪表盘推送、按键监控等后台能力不被用户误杀。
        Log.i(TAG, "CarMediaService onTaskRemoved: restarting foreground service");
        try {
            Intent restart = new Intent(getApplicationContext(), CarMediaService.class);
            startForegroundService(restart);
        } catch (Throwable t) {
            Log.w(TAG, "onTaskRemoved restart failed: " + t.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }
    @Override
    public void onDestroy() {
        Log.i(TAG, "CarMediaService onDestroy");
        sInstance = null;
        unregisterAudioPlaybackMonitor();
        unregisterA2dpSinkReceiver();
        releaseMediaSession();
        // USB 音源资源释放
        if (usbSourceController != null) {
            usbSourceController.release();
        }
        unregisterSessionChangedListener();
        unregisterControllerCallback();
        keyMonitor = null; // requestKeysInterception 已删除，KeyMonitor 无系统资源需释放
        stopLogcatKeyMonitor();
        unregisterMediaButtonBlocker();
        unbindQqMusicFetcher();
        if (kuwoAidlClient != null) {
            kuwoAidlClient.stop();
            kuwoAidlClient = null;
        }
        if (kugouCoverProvider != null) {
            kugouCoverProvider.stop();
            kugouCoverProvider = null;
        }
        stopLyricPoll();
        stopPositionTicker();
        unregisterNeteaseLyricReceiver();
        abandonBluetoothFocus();
        // 释放媒体键注入后台线程(防 ANR 修复的一部分)
        synchronized (mediaKeyThreadLock) {
            if (mediaKeyHandler != null) {
                mediaKeyHandler.removeCallbacksAndMessages(null);
                mediaKeyHandler = null;
            }
            if (mediaKeyThread != null) {
                mediaKeyThread.quitSafely();
                mediaKeyThread = null;
            }
        }
        autoSwitchRetryHandler.removeCallbacks(autoSwitchRetryRunnable);
        pendingAutoSwitchPkg = null;
        firstPushHandler.removeCallbacks(firstPushRunnable);
        super.onDestroy();
    }
    // ================= P1: 前台通知 =================
    private void startForegroundNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "CarMedia Service", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("CarMedia Foreground Service");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
            Intent main = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, main,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.service_notification_title))
                    .setContentText(getString(R.string.service_notification_text))
                    .setSmallIcon(R.mipmap.ic_notification)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundNotification failed: " + t.getMessage());
        }
    }
    // ================= P1: 会话监听 =================
 /** 注册会话变化监听（带 cn 过滤，需要通知监听权限已启用）。
 * 返回是否注册成功；首次安装时权限未就绪会抛 SecurityException，
 * 由 retryInitSessionManager / onNotificationListenerConnected 重试补注册。 */
    private boolean initSessionManager() {
        try {
            sessionManager = (MediaSessionManager) getSystemService("media_session");
            if (sessionManager != null) {
                ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
                sessionManager.addOnActiveSessionsChangedListener(sessionChangedListener, cn);
                sessionListenerRegistered = true;
                Log.i(TAG, ">>> session change listener registered");
                return true;
            }
        } catch (Throwable t) {
            sessionListenerRegistered = false;
            Log.w(TAG, ">>> session listener register failed: " + t.getMessage());
        }
        return false;
    }
 /** 会话监听注册失败后的延迟重试（Bug#70）。
 * 首次安装：onCreate 授权后系统绑定通知监听器需要时间，此刻注册仍可能失败，
 * 每 2s 重试一次（上限 5 次）；MediaNotificationListener.onListenerConnected
 * 是系统给出"监听器已就绪"的权威信号，到达时也会走 onNotificationListenerConnected
 * 即时补注册，这里只是兜底。 */
    private void retryInitSessionManager() {
        if (sessionListenerRegistered) {
            return;
        }
        try {
            if (initSessionManager()) {
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "retryInitSessionManager error: " + t.getMessage());
        }
        if (sessionListenerRetryCount++ < 5) {
            firstPushHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryInitSessionManager();
                }
            }, 2000L);
        }
    }
 /** 通知监听器连接就绪回调（Bug#70）。
 * 由 MediaNotificationListener.onListenerConnected 调用——这是系统真正绑定
 * 通知监听器、MediaSessionManager 承认本应用为可信监听者的权威时刻。
 * 首次安装"控制器找不到音源"的根因链：
 * ①initSessionManager 先于授权执行 → 注册失败，会话变化事件永不触发自动刷新；
 * ②初始 refreshController 扫描时系统尚未绑定监听器 → getActiveSessions(cn)/
 * getSessions 返回空 → availableControllers 被重置为空；
 * ③无任何自愈 → 只能靠用户手动刷新（晴空主题切 USB 变通路径）恢复。
 * 本回调在监听器就绪瞬间：补注册会话监听 + 重扫控制器列表 + 重选，一次自愈。 */
    public void onNotificationListenerConnected() {
        firstPushHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "notification listener connected, re-check controller scan");
                if (!sessionListenerRegistered) {
                    initSessionManager();
                }
                refreshControllerList();
                if (mediaController == null || controllerStale) {
                    refreshController();
                }
            }
        });
    }
    private void unregisterSessionChangedListener() {
        try {
            if (sessionManager != null && sessionChangedListener != null) {
                sessionManager.removeOnActiveSessionsChangedListener(sessionChangedListener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "session listener unregister failed: " + t.getMessage());
        }
    }
    // ================= P1: MediaSession =================
    private void initMediaSession() {
        try {
            mediaSession = new MediaSession(this, "CarMediaSession");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            // 方案A :设置 Callback 作为媒体键代理。
            // 系统媒体键经 MediaSessionService 直接 dispatch 到本 Callback,
            // 不再发 ACTION_MEDIA_BUTTON 全局广播 → 酷狗等静态接收器收不到按键,
            // 从根源杜绝酷狗抢方控。Callback 收到后转发给当前控制器。
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    if (mediaButtonIntent == null) return false;
                    // 吸收 CarMedia 自派发的媒体键(dispatchSystemMediaKey 第4级 fallback),
                    // 防止"无控制器时媒体键路由回自身代理 → 再触发 sendXxx → 第4级"的死循环。
                    // ( Bug#76:注入键的吸收窗口已移除——CarPlay 注入改走
                    // MediaSession 路由单通道/前台窗口单通道,不再依赖代理吸收路由键;
                    // dispatchMediaKeyToSession 发送前代理让位,路由目标不会是 CarMedia。)
                    if (isEasFromSelfDispatch()) {
                        Log.d(TAG, "MediaSession onMediaButtonEvent: self-originated media key, absorb to break loop");
                        return true;
                    }
                    KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (ke == null) return false;
                    int code = ke.getKeyCode();
                    int action = ke.getAction();
                    int dir = dirOfKeyCode(code);
                    // 删除旧"让位系统通道"分支。
                    // 依据:CarMedia 媒体键代理(方案A)是系统媒体键的唯一接收者,播放器自身的
                    // MediaButtonReceiver 收不到同一按键(真车日志确认:MEDIA_BUTTON received
                    // 在 CarMedia,酷我等零响应),"让位"=把键吞掉无人处理( 日志
                    // 13:32:39~13:33:13 未勾选屏蔽三键时 8 次切歌全部丢失)。
                    // shouldCarMediaOwnKey() 已改为通用接管,此通道收到即转发给当前控制器。
                    // 本通道是"同一物理按键的副本通道",用长窗口吸收——
                    // logcat 真实按键已处理(2s 内,含副本晚到 >500ms 的残余场景)或
                    // 其他副本 500ms 内已处理,都直接忽略,防"偶尔连切2首"。
                    // 系统媒体键只来自方向盘/硬按键注入,不是 EAS 卡片按钮,长窗口不会误吞卡片。
                    if (shouldCopyChannelAbsorb(dir)) {
                        Log.d(TAG, "MediaSession onMediaButtonEvent: absorbed (logcat/other copy handled same press)");
                        return true;
                    }
                    Log.i(TAG, "MediaSession onMediaButtonEvent: keyCode=" + code
                            + " action=" + action + " controller=" + controllerPkg
                            + " -> route to KeyMonitor (normalized)");
                    // 媒体键接入 KeyMonitor 统一判定(单击/双击/长按/自定义映射全生效)。
                    // 归一化到方向盘私有码(87->305 下一曲 / 88->304 上一曲 / 85/126/127->300 播放暂停),
                    // 复用现有映射表;DOWN/UP 喂给 KeyMonitor,由它做单击/双击/长按判定后回调 onKeyAction。
                    // 同一物理媒体键 logcat 通道已跳过喂入(见 startLogcatKeyMonitor),此处是唯一喂入方,不会双触发。
                    // 喂入前清掉 onMediaButtonRouted 打的 SRC_MEDIASESSION 路由 mark,避免 onKeyAction 的
                    // wasCopyHandledNearThisPhysicalPress 把"本通道自己喂入的按键"当成已处理副本而吸收(丢键)。
                    int privCode = normalizeMediaKeyCode(code);
                    if (privCode < 0) {
                        return false; // 非可归一化媒体键,不处理
                    }
                    if (keyMonitor == null) {
                        return true;
                    }
                    clearDirCopyMark(dir);
                    if (action == KeyEvent.ACTION_DOWN) {
                        // 与 logcat 通道对称,MediaSession 喂入时也主动记录物理按压时刻(epoch ms),
                        // 使 onKeyAction 的 wasCopyHandledNearThisPhysicalPress 有物理基准可用,即使该媒体键
                        // 未产生 CAR.INPUT 日志(logcat 无记录)。用 KeyEvent 下按时刻换算 epoch,与
                        // LogcatKeyMonitor 的 parsePhysTimeMs 同基准(System.currentTimeMillis 系)。
                        long evt = ke.getEventTime();
                        if (evt > 0) {
                            long physEpoch = System.currentTimeMillis() - (SystemClock.uptimeMillis() - evt);
                            if (physEpoch > 0) {
                                markLogcatKeyDownSeen(dir, physEpoch);
                            }
                        }
                        keyMonitor.handleExternalKeyDown(privCode);
                    } else if (action == KeyEvent.ACTION_UP) {
                        keyMonitor.handleExternalKeyUp(privCode);
                    }
                    // 不在此直接执行/mark:KeyMonitor 判定后经 onKeyAction 统一执行并打去重 mark。
                    return true;
                }
            });
            PlaybackState state = new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, -1L, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .build();
            mediaSession.setPlaybackState(state);
            mediaSession.setActive(true);
            mediaButtonViaSession = true;
            Log.i(TAG, "MediaSession registered & active (media button proxy, kugou blocked from MEDIA_BUTTON broadcast)");
        } catch (Throwable t) {
            mediaButtonViaSession = false;
            Log.w(TAG, "initMediaSession failed: " + t.getMessage());
        }
    }
    private void releaseMediaSession() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        mediaButtonViaSession = false;
    }
    // ================= P1: 通知监听自动授权 =================
    private void grantNotificationPermission() {
        boolean ok = NotificationPermissionHelper.grantNotificationListenerPermission(this);
        Log.i(TAG, "grantNotificationListenerPermission result=" + ok);
    }
    // ================= P1: 偏好设置 =================
    private void restoreDimAutoPush() {
        try {
            dimAutoPush = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("dim_auto_push", true);
        } catch (Throwable t) {
            dimAutoPush = true;
        }
    }
    // ================= 歌词屏蔽列表 =================
 /** 恢复持久化的歌词屏蔽列表(服务启动时调用)。 */
    private void restoreLyricBlockedList() {
        try {
            String s = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getString("lyric_blocked_pkgs", "");
            lyricBlockedPkgs.clear();
            if (s != null && !s.isEmpty()) {
                String[] arr = s.split(",");
                for (String p : arr) {
                    p = p.trim();
                    if (!p.isEmpty()) {
                        lyricBlockedPkgs.add(p);
                    }
                }
            }
            Log.i(TAG, "restoreLyricBlockedList: " + lyricBlockedPkgs);
        } catch (Throwable t) {
            Log.w(TAG, "restoreLyricBlockedList error: " + t.getMessage());
        }
    }
 /** 当前控制器是否被屏蔽歌词(UI 显示歌名/歌手,不调用任何歌词 API)。 */
    public boolean isLyricBlocked(String pkg) {
        if (pkg == null) {
            return false;
        }
        return lyricBlockedPkgs.contains(pkg);
    }
 /** 设置某控制器的歌词屏蔽状态(持久化)。 */
    public void setLyricBlocked(String pkg, boolean blocked) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (blocked) {
            lyricBlockedPkgs.add(pkg);
        } else {
            lyricBlockedPkgs.remove(pkg);
        }
        // 当前控制器被屏蔽:清掉已抓取的歌词,避免 UI 残留歌词行;
        // 解除屏蔽:立即重新抓取当前歌曲歌词(handleMetadata 同歌不重入,需手动触发)
        if (pkg.equals(controllerPkg)) {
            if (blocked) {
                clearCurrentLyrics();
            } else {
                refetchLyricsForCurrent();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : lyricBlockedPkgs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        try {
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .edit().putString("lyric_blocked_pkgs", sb.toString()).apply();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "setLyricBlocked: " + pkg + " -> " + blocked + ", list=" + lyricBlockedPkgs);
    }
 /** 清空当前已绑定的歌词缓存(屏蔽/解除时保证 UI 立即回到歌名/歌手)。 */
    private void clearCurrentLyrics() {
        try {
            if (cloudLyricFetcher != null) {
                cloudLyricFetcher.clearBroadcastLrc();
                cloudLyricFetcher.clearLoadedLrc();
                cloudLyricFetcher.resetApiStatus();
            }
            if (qqMusicFetcher != null) {
                qqMusicFetcher.resetApiStatus();
            }
            if (sodaMusicFetcher != null) {
                sodaMusicFetcher.resetApiStatus();
            }
            if (kuwoLyricFetcher != null) {
                kuwoLyricFetcher.resetApiStatus();
            }
            if (kugouLyricFetcher != null) {
                kugouLyricFetcher.resetApiStatus();
            }
            lastPushedLyricLine = null;
            lastPushedLyricForTitle = null;
        } catch (Throwable t) {
            Log.w(TAG, "clearCurrentLyrics error: " + t.getMessage());
        }
    }
 /** ★ 切源/切歌时重置全部歌词抓取器接口状态(api-strip 状态灯回空闲)。
 * 旧源的接口成功/错误状态不在新源继续常亮;新源抓取开始后其接口灯重新点亮。
 * 仅用于 USB 激活(不经过 handleMetadata 的切源重置);普通音源切源由
 * handleMetadata(songChanged/sourceChanged 重置全部)覆盖。 */
    private void resetAllLyricApiStatus() {
        try {
            if (cloudLyricFetcher != null) cloudLyricFetcher.resetApiStatus();
            if (qqMusicFetcher != null) qqMusicFetcher.resetApiStatus();
            if (sodaMusicFetcher != null) sodaMusicFetcher.resetApiStatus();
            if (kuwoLyricFetcher != null) kuwoLyricFetcher.resetApiStatus();
            if (kugouLyricFetcher != null) kugouLyricFetcher.resetApiStatus();
        } catch (Throwable t) {
            Log.w(TAG, "resetAllLyricApiStatus error: " + t.getMessage());
        }
    }
 /**
 * 解除屏蔽后立即重新抓取当前歌曲歌词。
 * handleMetadata 对同歌不重复触发抓取(CloudLyricFetcher 同标题去重),
 * 屏蔽解除后必须手动重新走一遍正常分派,否则歌词一直不回来。
 */
    private void refetchLyricsForCurrent() {
        try {
            if (currentTitle == null || currentTitle.isEmpty()) {
                return;
            }
            if (isLyricBlocked(controllerPkg)) {
                return; // 仍被屏蔽(理论上不会走到这)
            }
            Log.i(TAG, "refetchLyricsForCurrent: title=" + currentTitle + " artist=" + currentArtist
                    + " sourceType=" + currentSourceType);
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                qqMusicFetcher.bind();
                qqMusicFetcher.fetchCurrentSong();
            } else if (currentSourceType == SOURCE_TYPE_NETEASE && cloudLyricFetcher != null) {
                cloudLyricFetcher.search(currentTitle, currentArtist);
            } else if (currentSourceType == SOURCE_TYPE_GENERIC && cloudLyricFetcher != null) {
                cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
            } else if (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null) {
                kuwoLyricFetcher.search(currentTitle, currentArtist);
            } else if (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null) {
                kugouLyricFetcher.search(currentTitle, currentArtist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "refetchLyricsForCurrent error: " + t.getMessage());
        }
    }
    // ================= P4: 歌词抓取器 =================
    private void initLyricFetchers() {
        try {
            cloudLyricFetcher = new CloudLyricFetcher(new CloudLyricCallback());
            qqMusicFetcher = new QQMusicLyricFetcher(this, new QQMusicLyricCallback());
            sodaMusicFetcher = new SodaMusicLyricFetcher(new SodaMusicLyricCallback());
            // 酷我/酷狗专属歌词抓取器(官方接口),失败时回调内回退 XMF2。
            // 酷我 = 改版 APK 同款 AIDL+mobi.s 方案(需要 Context 绑定酷我 RemoteService)。
            kuwoLyricFetcher = new KuwoLyricFetcher(this, new KuwoLyricCallback());
            kugouLyricFetcher = new KugouLyricFetcher(this, new KugouLyricCallback());
            lyricPollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            // Bug#Q1：开机即探测一次 QQ AIDL 授权（bind→onServiceConnected→fetchCurrentSong）。
            // 让设置栏授权状态无论放没放歌都反映真实授权（code=0 已授权 / code=5 未授权），
            // 不再"没放歌默认已授权"掩盖真实未授权。
            if (qqMusicFetcher != null && qqMusicFetcher.isQqMusicInstalled()) {
                qqMusicFetcher.bind();
            }
            Log.i(TAG, "initLyricFetchers done");
        } catch (Throwable t) {
            Log.w(TAG, "initLyricFetchers failed: " + t.getMessage());
        }
    }
 /** 汽水音乐歌词回调:不覆盖歌名/歌手(metadata 已提供),仅确认歌词已加载。 */
    private class SodaMusicLyricCallback implements SodaMusicLyricFetcher.Callback {
        @Override
        public void onLyricReady(String lyric, String title, String artist) {
            try {
                onSodaLyricReady(lyric, title, artist);
            } catch (Throwable t) {
                Log.w(TAG, "onSodaLyricReady error: " + t.getMessage());
            }
        }
        @Override
        public void onError(String message) {
            Log.w(TAG, "SodaMusic lyric error: " + message);
            // 抓取失败也推一次(清空旧歌词显示),USB 场景。
            requestUsbFullPush();
        }
    }
    private void onSodaLyricReady(String lyric, String title, String artist) {
        if (isLyricBlocked(controllerPkg)) {
            Log.d(TAG, "onSodaLyricReady: lyric blocked for " + controllerPkg + ", ignore callback");
            return;
        }
        Log.i(TAG, "SODA lyric loaded: title=" + title + " artist=" + artist
                + " pkg=" + controllerPkg + " sourceType=" + currentSourceType);
        // 汽水歌词抓取完成 → 强制全量推送。
        requestUsbFullPush();
    }
 /** 酷我歌词回调:歌词就绪时全量推送;失败时回退 XMF2(与 QQ 失败回退同款)。 */
    private class KuwoLyricCallback implements KuwoLyricFetcher.Callback {
        @Override
        public void onLyricReady(String lyric, String title, String artist) {
            try {
                onKuwoLyricReady(lyric, title, artist);
            } catch (Throwable t) {
                Log.w(TAG, "onKuwoLyricReady error: " + t.getMessage());
            }
        }
        @Override
        public void onError(String message) {
            Log.w(TAG, "Kuwo lyric error: " + message);
            fallbackKuwoLyricToXmf2();
        }
    }
    private void onKuwoLyricReady(String lyric, String title, String artist) {
        if (isLyricBlocked(controllerPkg)) {
            Log.d(TAG, "onKuwoLyricReady: lyric blocked for " + controllerPkg + ", ignore callback");
            return;
        }
        Log.i(TAG, "KUWO lyric loaded: title=" + title + " artist=" + artist
                + " pkg=" + controllerPkg + " sourceType=" + currentSourceType);
        requestUsbFullPush();
    }
 /** 酷我专属接口失败 → 回退 XMF2(仅当当前控制器是酷我且未屏蔽/无歌词时触发)。 */
    private void fallbackKuwoLyricToXmf2() {
        try {
            if (isKuwoPkg(controllerPkg)
                    && currentSourceType == SOURCE_TYPE_KUWO
                    && cloudLyricFetcher != null
                    && currentTitle != null && !currentTitle.isEmpty()
                    && !isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "Kuwo lyric failed, fallback to xmf2: " + currentTitle);
                cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fallbackKuwoLyricToXmf2 error: " + t.getMessage());
        }
    }
 /** 酷狗歌词回调:歌词就绪时全量推送;失败时回退 XMF2(与 QQ 失败回退同款)。 */
    private class KugouLyricCallback implements KugouLyricFetcher.Callback {
        @Override
        public void onLyricReady(String lyric, String title, String artist) {
            try {
                onKugouLyricReady(lyric, title, artist);
            } catch (Throwable t) {
                Log.w(TAG, "onKugouLyricReady error: " + t.getMessage());
            }
        }
        @Override
        public void onError(String message) {
            Log.w(TAG, "Kugou lyric error: " + message);
            fallbackKugouLyricToXmf2();
        }
    }
    private void onKugouLyricReady(String lyric, String title, String artist) {
        if (isLyricBlocked(controllerPkg)) {
            Log.d(TAG, "onKugouLyricReady: lyric blocked for " + controllerPkg + ", ignore callback");
            return;
        }
        Log.i(TAG, "KUGOU lyric loaded: title=" + title + " artist=" + artist
                + " pkg=" + controllerPkg + " sourceType=" + currentSourceType);
        requestUsbFullPush();
    }
 /** 酷狗专属接口失败 → 回退 XMF2(仅当当前控制器是酷狗且未屏蔽/无歌词时触发)。 */
    private void fallbackKugouLyricToXmf2() {
        try {
            if (PKG_KUGOU.equals(controllerPkg)
                    && currentSourceType == SOURCE_TYPE_KUGOU
                    && cloudLyricFetcher != null
                    && currentTitle != null && !currentTitle.isEmpty()
                    && !isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "Kugou lyric failed, fallback to xmf2: " + currentTitle);
                cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fallbackKugouLyricToXmf2 error: " + t.getMessage());
        }
    }

 /** 聚合所有歌词 API 状态(供 UI 显示:{api: {status, msg}})。
 * ★ (最终版·确定性按源接口灯):只点亮当前音源所属接口族,
 * 其余接口族一律置空闲(0)——旧源状态天然不残留,与 handleMetadata 重置时序解耦,
 * 根治"切源残留上一个音源的歌词状态灯 / 当前源灯不显示"。
 * 族归属(controllerPkg/currentSourceType):QQ→qq_aidl;汽水→soda;酷我→kuwo;
 * 酷狗→kugou;其余(网易云/通用/USB/蓝牙/其它)→cloud 族,并细分:网易云源只亮
 * 网易云搜索/网易云歌词,其余 cloud 源只亮备用(XMF2)。严格只亮当前源接口,不因
 * XMF2 兜底额外点亮 cloud 族。
 * 当前族的灯:抓取器已归属当前歌(ownership 匹配=已成功识别并加载歌词)→
 * SUCCESS(3,绿常亮);否则显示真实状态(抓取中=1绿闪/失败=2红/空闲=0橙)。 */
    public JSONObject getApiStatus() {
        JSONObject o = new JSONObject();
        try {
            // 屏蔽歌词的源:不启用任何歌词接口 → 全部置空闲(0),避免残留 getSongTitle 归属
            // 导致接口灯误亮绿(用户实测"屏蔽源切走后接口灯不对")。
            boolean blocked = isLyricBlocked(controllerPkg);
            boolean qqFamily = currentSourceType == SOURCE_TYPE_QQ || PKG_QQ_MUSIC.equals(controllerPkg);
            boolean sodaFamily = currentSourceType == SOURCE_TYPE_SODA || isSodaPkg(controllerPkg);
            boolean kuwoFamily = currentSourceType == SOURCE_TYPE_KUWO
                    || (controllerPkg != null && isKuwoPkg(controllerPkg));
            boolean kugouFamily = currentSourceType == SOURCE_TYPE_KUGOU || PKG_KUGOU.equals(controllerPkg);
            boolean cloudFamily = !qqFamily && !sodaFamily && !kuwoFamily && !kugouFamily;
            // cloud 族内部细分 :网易云源只亮 网易云搜索/网易云歌词;
            // 其余 cloud 源(通用/USB/蓝牙)只亮 备用(XMF2)。不移除兜底提升,酷狗/QQ 等专属源
            // 即使走 XMF2 兜底也只亮其专属灯——严格"只显示当前源接口状态"。
            boolean useNetease = currentSourceType == SOURCE_TYPE_NETEASE
                    || (controllerPkg != null && (PKG_NETEASE_IOT.equals(controllerPkg) || PKG_NETEASE.equals(controllerPkg)));
            boolean useXmf2 = !useNetease;
            // "当前源已成功提供歌词"判据:抓取器归属(ownership)与当前歌匹配
            boolean cloudLoaded = !blocked && cloudLyricFetcher != null && currentTitle != null && !currentTitle.isEmpty()
                    && lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle);
            boolean qqLoaded = !blocked && qqMusicFetcher != null && currentTitle != null && !currentTitle.isEmpty()
                    && lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle);
            boolean sodaLoaded = !blocked && sodaMusicFetcher != null && currentTitle != null && !currentTitle.isEmpty()
                    && lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle);
            boolean kuwoLoaded = !blocked && kuwoLyricFetcher != null && currentTitle != null && !currentTitle.isEmpty()
                    && lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle);
            boolean kugouLoaded = !blocked && kugouLyricFetcher != null && currentTitle != null && !currentTitle.isEmpty()
                    && lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle);
            if (cloudLyricFetcher != null) {
                JSONObject c = cloudLyricFetcher.getApiStatus();
                java.util.Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject s = c.optJSONObject(k);
                    String keyN = k;
                    boolean lit = cloudFamily
                            && ((useNetease && (keyN.equals("netease_search") || keyN.equals("netease_lyric")))
                                    || (useXmf2 && keyN.equals("xmf2")));
                    if (blocked || !lit) {
                        s = new JSONObject();
                        s.put("status", 0);
                        s.put("msg", "");
                    } else if (cloudLoaded) {
                        s = new JSONObject();
                        s.put("status", 3);
                        s.put("msg", "");
                    }
                    o.put(k, s);
                }
            }
            if (qqMusicFetcher != null) {
                JSONObject q = qqMusicFetcher.getApiStatus();
                JSONObject qq = new JSONObject();
                qq.put("status", (blocked || !qqFamily) ? 0 : (qqLoaded ? 3 : q.optInt("status")));
                qq.put("msg", (blocked || !qqFamily) ? "" : (qqLoaded ? "" : q.optString("msg")));
                o.put("qq_aidl", qq);
            }
            if (sodaMusicFetcher != null) {
                JSONObject s = new JSONObject();
                s.put("status", (blocked || !sodaFamily) ? 0
                        : (sodaLoaded ? 3 : sodaMusicFetcher.getApiState()));
                s.put("msg", (blocked || !sodaFamily) ? ""
                        : (sodaLoaded ? ""
                                : (sodaMusicFetcher.getApiMsg() != null ? sodaMusicFetcher.getApiMsg() : "")));
                o.put("soda", s);
            }
            if (kuwoLyricFetcher != null) {
                JSONObject k = new JSONObject();
                k.put("status", (blocked || !kuwoFamily) ? 0
                        : (kuwoLoaded ? 3 : kuwoLyricFetcher.getApiState()));
                k.put("msg", (blocked || !kuwoFamily) ? ""
                        : (kuwoLoaded ? ""
                                : (kuwoLyricFetcher.getApiMsg() != null ? kuwoLyricFetcher.getApiMsg() : "")));
                o.put("kuwo", k);
            }
            if (kugouLyricFetcher != null) {
                JSONObject k = new JSONObject();
                k.put("status", (blocked || !kugouFamily) ? 0
                        : (kugouLoaded ? 3 : kugouLyricFetcher.getApiState()));
                k.put("msg", (blocked || !kugouFamily) ? ""
                        : (kugouLoaded ? ""
                                : (kugouLyricFetcher.getApiMsg() != null ? kugouLyricFetcher.getApiMsg() : "")));
                o.put("kugou", k);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getApiStatus error: " + t.getMessage());
        }
        return o;
    }

 /** 切源/兜底抓取:按 controllerPkg 分派到当前音源所属歌词抓取器,
 * 若该抓取器对当前歌"未就绪"(未加载出歌词:isReady()=false 或 归属不匹配),主动触发抓取。
 * 不依赖 handleMetadata 是否回调/时序——兼容 MediaSession 回调不可靠的音源。
 * 触发点:selectControllerInner(切源/重选) + pollLyricOnce(autoRetryCurrentSourceLyricIfNeeded)常驻兜底。
 * "未就绪"而非"归属不匹配":失败态下抓取器 getSongTitle 为乐观设置、归属仍匹配,
 * 若只判归属,暂停重播/重装后失败残留会让兜底失效。 */
    private void ensureCurrentSourceLyricFetch() {
        try {
            if (currentTitle == null || currentTitle.isEmpty()) return;
            if (isLyricBlocked(controllerPkg)) return;
            if (PKG_QQ_MUSIC.equals(controllerPkg)) {
                if (qqMusicFetcher != null
                        && !(qqMusicFetcher.isReady() && lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle))) {
                    qqMusicFetcher.bind();
                    qqMusicFetcher.fetchCurrentSong();
                    startLyricPoll();
                }
            } else if (PKG_NETEASE_IOT.equals(controllerPkg) || PKG_NETEASE.equals(controllerPkg)) {
                if (cloudLyricFetcher != null
                        && !(cloudLyricFetcher.isReady() && lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle))) {
                    cloudLyricFetcher.search(currentTitle, currentArtist);
                    startLyricPoll();
                }
            } else if (isSodaPkg(controllerPkg)) {
                if (sodaMusicFetcher != null
                        && !(sodaMusicFetcher.isReady() && lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle))) {
                    sodaMusicFetcher.search(currentTitle, currentArtist);
                    startLyricPoll();
                }
            } else if (isKuwoPkg(controllerPkg)) {
                if (kuwoLyricFetcher != null
                        && !(kuwoLyricFetcher.isReady() && lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle))) {
                    kuwoLyricFetcher.search(currentTitle, currentArtist);
                    startLyricPoll();
                }
            } else if (PKG_KUGOU.equals(controllerPkg)) {
                if (kugouLyricFetcher != null
                        && !(kugouLyricFetcher.isReady() && lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle))) {
                    kugouLyricFetcher.search(currentTitle, currentArtist);
                    startLyricPoll();
                }
            } else {
                // 通用/其它(网易云除外):XMF2 兜底(蓝牙内嵌歌词/屏蔽已在上方排除)
                if (cloudLyricFetcher != null
                        && !(cloudLyricFetcher.isReady() && lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle))) {
                    cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
                    startLyricPoll();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureCurrentSourceLyricFetch error: " + t.getMessage());
        }
    }
 /** QQ 音乐歌词回调:显示名 + LRC 首行提取歌名(原版逻辑)。 */
    private class QQMusicLyricCallback implements QQMusicLyricFetcher.Callback {
        @Override
        public void onLyricReady(String lyric, String title, String artist) {
            try {
                onQqLyricReady(lyric, title, artist);
            } catch (Throwable t) {
                Log.w(TAG, "onQqLyricReady error: " + t.getMessage());
            }
        }
        @Override
        public void onError(String message) {
            Log.w(TAG, "QQMusic lyric error: " + message);
            // QQ AIDL 失败(未绑定/未授权/接口异常)→ 直连 XMF2 兜底(通用接口,灯只亮 XMF2);
            // 仅在当前控制器是 QQ 且无歌词时触发;屏蔽列表命中时跳过兜底。
            // 已拿到歌词(hasLyric)或 code=5 自愈重启中(isRestarting)时不触发,
            // 避免 QQ AIDL 可用时瞬态失败误触发备用歌词。
            boolean qqHasLyric = qqMusicFetcher != null && qqMusicFetcher.hasLyric();
            boolean qqRestarting = qqMusicFetcher != null && qqMusicFetcher.isRestarting();
            if (PKG_QQ_MUSIC.equals(controllerPkg)
                    && currentSourceType == SOURCE_TYPE_QQ
                    && cloudLyricFetcher != null
                    && currentTitle != null && !currentTitle.isEmpty()
                    && !isLyricBlocked(controllerPkg)
                    && !qqHasLyric
                    && !qqRestarting) {
                Log.i(TAG, "QQ lyric failed, fallback to xmf2: " + currentTitle);
                cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
            }
        }
    }
 /** 原版 onQqLyricReady:显示名=歌名[ - 歌手],歌名从 LRC 首行有效行提取。 */
 /**
 * 通知栏元数据应用(通知监听权限的用途之一)。
 *
 * 音乐 App 自己发的通知里 title/text 是权威完整数据(如 QQ 音乐的
 * text=姜添&听潮阁),而 MediaMetadata/getCurrentSong 接口只推主歌手。
 * 当通知歌手比当前更完整(或当前为空)时覆盖;反之保留当前完整值。
 *
 * @param title 通知标题(歌名),可为 null/空
 * @param artist 通知文本(歌手),可为 null/空
 * @param pkg 通知来源包名
 */
 /**
 * 通知栏元数据应用(通知监听权限的用途之一)。
 *
 * 通知是音乐 App 自发的权威完整数据(如 QQ 音乐的 text=姜添&听潮阁),
 * 而 MediaMetadata/getCurrentSong 接口只推主歌手。因此:
 * - 歌名:只有通知标题与当前歌名【一致】时才接受(歌名来源单一,
 * 通知只做确认,不引入"网易云音乐正在运行中/点击进入"这类后台状态文本)");
 * - 歌手:仅在真实歌曲通知(标题一致或带播放/暂停媒体按钮)时接受,
 * 空闲状态通知文本不得污染副标题;
 * - 同时记录 notifPkg/notifTitle/notifArtist 快照,其他来源
 * (metadata/LRC/搜索)在快照有效期间不得覆盖(见 handleMetadata/
 * onQqLyricReady/onCloudLyricReady 的 notifActive() 判断)。
 *
 * @param title 通知标题(歌名),可为 null/空
 * @param artist 通知文本(歌手),可为 null/空
 * @param pkg 通知来源包名
 */
 /**
 * 通知权威数据(歌名/歌手/播放状态)应用到当前显示与 EAS 推送。
 * @param state 从通知 actions 按钮判定的播放状态:3=播放中,2=暂停,-1=无法判定不覆盖
 */
    public void applyNotificationMeta(String title, String artist, String pkg, int state, String lyric) {
        try {
            if (pkg == null || pkg.equals(PKG_SELF)) {
                return; // 忽略自身与空来源
            }
            // 只接受"当前控制器自己"的通知:
            // 通知数据(歌名/歌手/状态)必须与当前锁定的音源一致,
            // 避免其他 App 的通知把歌名/歌手覆盖成别家的(错配)。
            // 不做硬编码包名白名单--任何音源只要成为当前控制器,
            // 它的通知就是权威数据源(歌名/歌手/播放状态)。
            // 如果 ghostControllerPkg 不为 null(用户主动选择了暂停态会话,如酷狗),
            // 则以 ghostControllerPkg 为准。因为 controllerPkg 可能被自动切换逻辑临时修改,
            // 但 ghostControllerPkg 是用户明确选择的目标,不应被其他播放器的通知覆盖。
            String authoritativePkg = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
            if (!pkg.equals(authoritativePkg)) {
                Log.d(TAG, "applyNotificationMeta: ignore notification from " + pkg
                        + " (authoritative controller is " + authoritativePkg
                        + ", controllerPkg=" + controllerPkg
                        + ", ghostPkg=" + ghostControllerPkg + ")");
                return; // 非当前控制器的通知不接收(含其他音乐 App)
            }
            String t = (title != null) ? title.trim() : "";
            String a = (artist != null) ? artist.trim() : "";
            if (t.isEmpty() && a.isEmpty()) {
                return;
            }
            // 更新权威快照:只要通知带了歌名或歌手,就认为该 pkg 的通知数据有效
            notifPkg = pkg;
            boolean changed = false;
            // 歌名:只有通知标题与当前歌名【一致】时才接受(用户要求:歌名来源单一,
            // 通知只做确认,不引入"网易云音乐正在运行中/点击进入"这类后台状态文本)
            boolean titleAccepted = !t.isEmpty()
                    && (currentTitle == null
                        || t.equals(currentTitle)
                        || "未知歌曲".equals(currentTitle)
                        || "Unknown".equalsIgnoreCase(currentTitle));
            if (titleAccepted) {
                currentTitle = t;
                notifTitle = t;
                changed = true;
            }
            // 真实歌曲通知判定:标题与当前歌名一致,或通知带播放/暂停媒体按钮
            // (网易云 IoT 空闲通知没有任何媒体按钮,state=-1)
            boolean realSongNotif = titleAccepted || state > 0;
            // 歌手:仅在真实歌曲通知时接受(通知文本是权威完整歌手,如 QQ"姜添&听潮阁");
            // 空闲状态通知文本(如"点击进入")不得污染副标题
            if (realSongNotif && !a.isEmpty()) {
                currentArtist = a;
                notifArtist = a;
                // 通知歌手是权威完整数据:后续 handleMetadata 不再用主歌手覆盖
                qqArtistComplete = true;
                changed = true;
            }
            if (changed) {
                Log.i(TAG, "applyNotificationMeta: title=" + currentTitle
                        + " artist=" + currentArtist + " pkg=" + pkg
                        + " (notification authoritative)");
                // 原版语义:metadata 推送受"自动仪表推送"开关控制(smali L1395-1399)
                if (isDimPushEnabled()) {
                    pushMetadataToCard();
                }
                // 歌词恢复:App 重启后 metadata 可能为空("未知歌曲"),此时通知的
                // 真实歌名/歌手会走到这里。只有通知确认了真实歌曲(titleAccepted 或
                // 带媒体按钮)才触发歌词搜索,否则空闲通知也会触发无谓的网络请求。
                if (realSongNotif && lyric == null && notifActive()) {
                    Log.i(TAG, "applyNotificationMeta: refetch lyrics for " + currentTitle);
                    refetchLyricsForCurrent();
                }
            }
            // 播放状态:通知优先(用户要求:播放状态与歌名/歌手一样,以通知栏为准)。
            // MediaStyle 通知的 Play/Pause 按钮随真实状态切换,比 MediaSession state 可靠
            // (部分音乐 App 如汽水,暂停时 MediaSession state 不更新,但通知会更新)。
            // 条件:当前控制器的通知 且 解析出 Pause/Play 信号(state>0)。
            if (state > 0) {
                int old = lastPlaybackState != null ? lastPlaybackState.getState() : -1;
                if (old != state) {
                    Log.i(TAG, "applyNotificationMeta: playback state " + old
                            + " -> " + state + " (notification authoritative)");
                    lastPlaybackState = new android.media.session.PlaybackState.Builder()
                            .setState(state, -1L, 1.0f).build();
                }
            }
            // 内嵌歌词:音乐 App 通知自带的歌词(QQ 车机版 qq_lyric_line 等)。
            // 完整 LRC([ 开头/含换行)→ 绑定到 fetcher 走 LyricPoll 按进度取行;
            // 单行 → 直接推送。这是零网络、零授权的本地歌词来源(同桥接.apk 机制)。
            if (lyric != null && !lyric.trim().isEmpty()) {
                boolean isLrc = lyric.startsWith("[") || lyric.contains("\n");
                pushBroadcastLyric(lyric, isLrc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyNotificationMeta error: " + t.getMessage());
        }
    }
 /**
 * 通知权威数据是否对当前控制器生效。
 * 快照有效 = 通知歌手/歌名至少一个存在,且通知来源与当前控制器一致。
 */
    private boolean notifActive() {
        if (notifArtist == null && notifTitle == null) {
            return false;
        }
        return notifPkg.equals(controllerPkg);
    }
 /**
 * 当前控制器的通知被移除(音源停止 / 通知被划掉)时清空通知权威快照,
 * 防止 notifActive() 用旧数据继续报上一首歌/播放中。
 */
    public void onNotificationRemoved(String pkg) {
        try {
            if (pkg == null) {
                return;
            }
            if (!pkg.equals(controllerPkg) && !pkg.equals(notifPkg)) {
                return;
            }
            Log.i(TAG, "notification removed: pkg=" + pkg + ", clearing notif snapshot");
            notifPkg = null;
            notifTitle = null;
            notifArtist = null;
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationRemoved error: " + t.getMessage());
        }
    }
    private void onQqLyricReady(String lyric, String title, String artist) {
        // 屏蔽列表命中:丢弃 QQ 歌词回调(即使已抓到也不显示/不推)
        if (isLyricBlocked(controllerPkg)) {
            Log.d(TAG, "onQqLyricReady: lyric blocked for " + controllerPkg + ", discard");
            return;
        }
        // QQ AIDL 返回空歌词(未授权/无 LRC/接口失败)→ 直连 XMF2 兜底
        if (lyric == null || lyric.isEmpty()) {
            if (currentTitle != null && !currentTitle.isEmpty()
                    && cloudLyricFetcher != null
                    && !isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "QQ lyric empty, fallback to xmf2: " + currentTitle);
                cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
            }
            return;
        }
        // 显示名 = 歌名[ - 歌手](歌名空用 ?)
        String displayName = (title != null && !title.isEmpty()) ? title : "?";
        if (artist != null && !artist.isEmpty()) {
            displayName = displayName + " - " + artist;
        }
        // 从 LRC 首行有效歌词提取歌名/歌手(跳过元信息行)。
        // 注意:QQ 歌词首行有效文本通常是 "歌名 - 完整歌手"(含 & 等多歌手),
        // 而 getCurrentSong 接口只返回主歌手,所以优先用 LRC 首行拆分补全。
        String lrcTitle = "";
        String lrcArtist = "";
        if (lyric != null && !lyric.isEmpty()) {
            String[] rows = lyric.split("\n");
            for (String row : rows) {
                String s = row.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.startsWith("[ti:") || s.startsWith("[ar:") || s.startsWith("[al:")
                        || s.startsWith("[by:") || s.startsWith("[offset:")) {
                    continue;
                }
                int close = s.indexOf(']');
                if (close >= 0) {
                    String v = s.substring(close + 1).trim();
                    if (!v.isEmpty()) {
                        // 尝试按 "歌名 - 歌手" 拆分
                        int sep = v.lastIndexOf(" - ");
                        if (sep > 0) {
                            lrcTitle = v.substring(0, sep).trim();
                            lrcArtist = v.substring(sep + 3).trim();
                        } else {
                            lrcTitle = v;
                        }
                    }
                }
                if (!lrcTitle.isEmpty()) {
                    break;
                }
            }
        }
        if (lrcTitle.isEmpty()) {
            lrcTitle = (title != null && !title.isEmpty()) ? title : "";
        }
        // 歌手:优先 LRC 首行拆分出的完整歌手(含 & 等多歌手),否则用回调 artist
        if (lrcArtist.isEmpty() && artist != null && !artist.isEmpty()) {
            lrcArtist = artist;
        }
        // 歌名:通知栏优先;通知无效时用 LRC/回调值
        if (!notifActive() || notifTitle == null || notifTitle.isEmpty()) {
            currentTitle = lrcTitle;
        }
        Log.d(TAG, "QQ lyric loaded for title=" + currentTitle);
        if (!lrcArtist.isEmpty()) {
            // 通知栏优先:通知权威数据有效时,LRC 拆分的歌手不覆盖
            if (!notifActive()) {
                currentArtist = lrcArtist;
                // QQ 完整歌手已就绪:后续 handleMetadata 不再用主歌手覆盖
                qqArtistComplete = true;
            }
        } else {
            currentArtist = displayName;
            qqArtistComplete = false;
        }
        Log.i(TAG, "onQqLyricReady: title=" + currentTitle + " artist=" + currentArtist);
        // 歌词就绪后重新推送小卡片(修正歌名),开关关闭则跳过(原版 smali L2182-2186)
        if (isDimPushEnabled()) {
            pushMetadataToCard();
        }
    }
 /** 网易云歌词回调:歌词就绪时补全歌手(joinedArtist)并可选推送。 */
    private class CloudLyricCallback implements CloudLyricFetcher.Callback {
        @Override
        public void onLyricReady(String lyric, String title, String artist) {
            try {
                onCloudLyricReady(lyric, title, artist);
            } catch (Throwable t) {
                Log.w(TAG, "onCloudLyricReady error: " + t.getMessage());
            }
        }
        @Override
        public void onError(String message) {
            Log.w(TAG, "Netease lyric error: " + message);
            // 抓取失败也推一次(清空旧歌词显示),USB 场景。
            requestUsbFullPush();
        }
    }
 /** 原版 onCloudLyricReady:artist 覆盖(joinedArtist 完整多歌手),歌名防旧回调覆盖。 */
    private void onCloudLyricReady(String lyric, String title, String artist) {
        // 屏蔽列表命中:忽略网络歌词回调(不覆盖歌名/歌手,UI 保持 metadata 歌名/歌手)
        if (isLyricBlocked(controllerPkg)) {
            Log.d(TAG, "onCloudLyricReady: lyric blocked for " + controllerPkg + ", ignore callback");
            return;
        }
        if (artist != null && !notifActive()) {
            // 与 handleMetadata 一致:反斜杠转正斜杠 + 移除控制字符
            String clean = artist.replace('\\', '/');
            StringBuilder sb = new StringBuilder(clean.length());
            for (int i = 0; i < clean.length(); i++) {
                char c = clean.charAt(i);
                if (c >= 32 && c != 127) {
                    sb.append(c);
                }
            }
            currentArtist = sb.toString();
            Log.i(TAG, "CLOUD_ARTIST=" + currentArtist);
        }
        // 歌名仅在与当前曲目一致时更新(防旧回调覆盖新曲目)");通知有效时不覆盖
        if (title != null && title.equals(currentTitle) && !notifActive()) {
            currentTitle = title;
        }
        if (title != null && title.equals(currentTitle)) {
            Log.d(TAG, "CLOUD lyric loaded for title=" + title);
        }
        // 云歌词抓取完成 → 强制全量推送,前端立即刷新 USB 歌词面板。
        requestUsbFullPush();
    }
    // ================= P4: 歌词轮询 =================
    private final Runnable lyricPollRunnable = new Runnable() {
        @Override
        public void run() {
            boolean ok = pollLyricOnce();
            // 桌面小部件刷新（新增：晴空主题多媒体卡片）。
            // 独立于 EAS 门控——小卡片始终跟随服务最新状态；
            // updateAll 内部按内容去重，歌词/歌名/封面/播放态变化才重写 RemoteViews。
            try {
                CarMediaWidget.updateAll(CarMediaService.this);
                CarMediaSkyWidget.updateAll(CarMediaService.this);
            } catch (Throwable t) {
                Log.w(TAG, "widget update error: " + t.getMessage());
            }
            // 修正(原版 smali 移植 bug):pollLyricOnce 返回 true 表示"应继续轮询"
            // (正常播放中:无播放状态/位置未变/无歌词/成功推送),返回 false 表示
            // "停止"(控制器不支持/controller null)。
            // 原实现 if(!ok) 才重排导致成功一次后轮询永久停止--歌词/歌名变化
            // 永远检测不到,这就是"歌名/歌词变了不推送"的根因。
            if (ok) {
                scheduleLyricPoll(this);
            }
        }
    };
    private void startLyricPoll() {
        if (lyricPollHandler == null || lyricPollRunnable == null) {
            return;
        }
        lyricPollHandler.removeCallbacks(lyricPollRunnable);
        lyricPollHandler.postDelayed(lyricPollRunnable, LYRIC_POLL_INTERVAL_MS);
    }
    private void stopLyricPoll() {
        if (lyricPollHandler == null || lyricPollRunnable == null) {
            return;
        }
        lyricPollHandler.removeCallbacks(lyricPollRunnable);
    }
    private void unbindQqMusicFetcher() {
        if (qqMusicFetcher != null) {
            try {
                qqMusicFetcher.unbind();
            } catch (Throwable t) {
                Log.w(TAG, "unbind qqMusicFetcher error: " + t.getMessage());
            }
        }
    }
    public void scheduleLyricPoll(Runnable r) {
        if (lyricPollHandler != null && r != null) {
            lyricPollHandler.postDelayed(r, LYRIC_POLL_INTERVAL_MS);
        }
    }
 /** 单次轮询:按播放进度取当前歌词行,变化时推送(原版逻辑)。 */
 /** 通用歌词自动补抓(节流+有限重试)。任何音源只要当前歌的歌词抓取器未就绪
 * (未加载出歌词),轮询就主动补抓——不依赖 handleMetadata 是否回调,兼容
 * MediaSession 回调不可靠的音源(酷狗 active=false/PlaybackState 恒 PAUSED,
 * 暂停重播/重装后 handleMetadata 常不触发 → search 无人调用 → 无歌词+灯卡黄闪)。
 * 就绪后自动停;同一首歌达到重试上限(可能真无歌词)后停手,切歌/换源按歌名重置。
 * 补抓动作统一走 ensureCurrentSourceLyricFetch(按 controllerPkg 分派到正确抓取器)。 */
    private void autoRetryCurrentSourceLyricIfNeeded() {
        try {
            if (currentTitle == null || currentTitle.isEmpty()) return;
            if (isLyricBlocked(controllerPkg)) return;
            // 蓝牙内嵌歌词(闪烁)源:歌词行在 TITLE 里且歌名持续变化,补抓 XMF2 只会网络
            // 洪泛(与 handleMetadata 的 titleFlickerLyricActive 跳过逻辑一致)。
            if (titleFlickerLyricActive) return;
            // 仅对"能驱动歌词抓取器"的音乐源补抓;排除通话/盒子(信息源)/未知控制器,
            // 避免对非歌词源反复发起网络抓取。
            if (!canDriveCurrentSourceLyric()) return;
            // 当前歌歌词已就绪(归属匹配且抓取器已加载)→ 无需补抓
            if (isCurrentSourceLyricReady()) return;
            // 歌名变化/换歌 → 重置重试计数
            String title = currentTitle;
            if (!title.equals(autoLyricRetryTitle)) {
                autoLyricRetryTitle = title;
                autoLyricRetryCount = 0;
            }
            if (autoLyricRetryCount >= AUTO_LYRIC_RETRY_MAX) return; // 该歌已重试上限,可能真无歌词
            long now = SystemClock.elapsedRealtime();
            if (now - lastAutoLyricRetryAt < AUTO_LYRIC_RETRY_MS) return; // 节流
            lastAutoLyricRetryAt = now;
            autoLyricRetryCount++;
            Log.i(TAG, "autoRetryLyric: source=" + controllerPkg + " lyric not ready, retry #"
                    + autoLyricRetryCount + "/" + AUTO_LYRIC_RETRY_MAX + ": " + title);
            ensureCurrentSourceLyricFetch();
            startLyricPoll();
        } catch (Throwable t) {
            Log.w(TAG, "autoRetryLyric error: " + t.getMessage());
        }
    }

 /** 当前源是否能驱动歌词抓取器(存在对应抓取器)。sourceType 优先;metadata 为 null 时
 * sourceType 可能为 NONE,再用 controllerPkg 兜底。排除通话/盒子/USB/未知控制器等非歌词源。 */
    private boolean canDriveCurrentSourceLyric() {
        if (isCarplayBoxRealPkg(controllerPkg)) return false;
        boolean byType =
                (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null)
                || ((currentSourceType == SOURCE_TYPE_NETEASE || currentSourceType == SOURCE_TYPE_GENERIC) && cloudLyricFetcher != null)
                || (currentSourceType == SOURCE_TYPE_SODA && sodaMusicFetcher != null)
                || (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null)
                || (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null);
        if (byType) return true;
        boolean byPkg =
                (PKG_QQ_MUSIC.equals(controllerPkg) && qqMusicFetcher != null)
                || ((PKG_NETEASE_IOT.equals(controllerPkg) || PKG_NETEASE.equals(controllerPkg)) && cloudLyricFetcher != null)
                || (isSodaPkg(controllerPkg) && sodaMusicFetcher != null)
                || (isKuwoPkg(controllerPkg) && kuwoLyricFetcher != null)
                || (PKG_KUGOU.equals(controllerPkg) && kugouLyricFetcher != null);
        return byPkg;
    }

 /** 当前源对应的歌词抓取器是否已就绪当前歌歌词(=归属匹配 且 已加载)。
 * 通用判定,不针对任何特定音源;sourceType→抓取器 是数据模型映射,非兼容白名单。 */
    private boolean isCurrentSourceLyricReady() {
        try {
            if (currentTitle == null || currentTitle.isEmpty()) return false;
            boolean matched;
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                matched = lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle);
                return matched && qqMusicFetcher.isReady();
            } else if ((currentSourceType == SOURCE_TYPE_NETEASE || currentSourceType == SOURCE_TYPE_GENERIC)
                    && cloudLyricFetcher != null) {
                matched = lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle);
                return matched && cloudLyricFetcher.isReady();
            } else if (currentSourceType == SOURCE_TYPE_SODA && sodaMusicFetcher != null) {
                matched = lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle);
                return matched && sodaMusicFetcher.isReady();
            } else if (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null) {
                matched = lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle);
                return matched && kuwoLyricFetcher.isReady();
            } else if (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null) {
                matched = lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle);
                return matched && kugouLyricFetcher.isReady();
            }
        } catch (Throwable t) {
            Log.w(TAG, "isCurrentSourceLyricReady error: " + t.getMessage());
        }
        return false;
    }

    private boolean pollLyricOnce() {
        // 歌词抓取现为通用:QQ 走 AIDL 抓取器,网易云/通用源走 cloudLyricFetcher
        // (网易云→XMF2 兜底),任何控制器都有备用歌词能力。
        // 内容变化检测(歌名/歌词/状态→pushMetadataToCard)不限音源,见方法尾部。
        boolean lyricSupported = true;
        if (!lyricSupported) {
            Log.d(TAG, "LyricPoll: stopped (controller not supported for lyric)");
            // 非歌词音源:仍做内容变化检测(歌名/歌手变化推送不依赖歌词抓取器)
            checkContentChangedAndPush();
            return false;
        }
        if (mediaController == null) {
            // ★ (Bug: USB 小组件永不刷新):mediaController 是外部音源的控制器，
            // USB 是虚拟包(com.ecarx.carmedia.usb)没有真实 MediaController → 原实现这里
            // 直接 return false → lyricPollRunnable 的 `if(ok) scheduleLyricPoll` 不成立
            // → 轮询停止 → CarMediaWidget.updateAll(每秒)不再被调 → 小组件图标/歌名/歌词
            // 永远不刷新。修复:USB 在播时保持轮询(return true),让 updateAll 持续刷新小组件。
            // ★ 修正:return true 前必须直接 return——下方 3229 行仍访问
            // mediaController.getPlaybackState(),mediaController 为 null 会 NPE,导致
            // 轮询异常、小组件刷新中断。USB 场景由 onStateChanged/checkContentChangedAndPush
            // (pushMetadataToCard 的 USB 分流,走 usbSourceController 不碰 mediaController)负责推送。
            if (usbRouteArmed && usbSourceController != null) {
                checkContentChangedAndPush();
                return true;
            }
            // ★ 修复:即使未建立 MediaController 连接,只要有歌词来源
            // (网易云广播歌词 / 云歌词抓取器已绑定当前歌)就保持轮询,
            // 否则轮询停止 → CarMediaWidget.updateAll 不再被调 → 小组件歌词卡住。
            if (hasActiveLyricSource()) {
                return true;
            }
            return false;
        }
        // 通用歌词自动补抓:任何音源只要当前歌歌词抓取器未就绪,轮询常驻
        // 每 5s 主动补抓一次(有限重试)。兼容 MediaSession 回调不可靠的音源(酷狗等),
        // 覆盖"暂停重播/重装后 handleMetadata 不触发 → search 无人调用 → 无歌词"的场景,
        // 不再需要手动重选控制器,也不做音源白名单特判。就绪后自动停,成功/切歌/换源归位。
        autoRetryCurrentSourceLyricIfNeeded();
        PlaybackState ps = mediaController.getPlaybackState();
        if (ps == null) {
            // 无播放状态:保持轮询
            return true;
        }
        long position = ps.getPosition();
        int state = ps.getState();
        // 歌词取行改用 getPlaybackPosition()——蓝牙 AVRCP / position 更新
        // 不频繁的音源,裸 ps.getPosition() 返回"最近一次更新时的位置"(常卡 0/旧值),
        // LrcUtil.getCurrentLine 永远选 t=0 第一行 → 歌词只显示第一句不刷新。
        // 与进度条 Bug#31 同根因,进度条早已用 getPlaybackPosition()(蓝牙本地累加+校准)。
        position = getPlaybackPosition();
        // 位置未变时不再提前跳过:暂停/卡住时 position 不变,但歌词归属/首行歌词
        // 仍需检查(否则"暂停状态重启/切歌后一直无歌词")。
        // pushLyric 有行级去重,重复推同一行无害;内容变化检测也会去重。
        if (position == lastLyricPosition) {
            Log.d(TAG, "LyricPoll: pos unchanged, still checking lyric");
        } else {
            lastLyricPosition = position;
        }
        // 按播放进度取当前歌词行:无歌词/位置未就绪时 getBoundLyricLine 返回 null,
        // 跳过歌词推送;下方 checkContentChangedAndPush 仍做内容变化检测(歌名/状态推送不依赖歌词)。
        String line = getBoundLyricLine();
        if (line == null) {
            // 歌词未就绪/未绑定(切歌瞬间或新歌歌词加载中)
            Log.d(TAG, "LyricPoll: lyric not bound yet (switching?), skip lyric push");
            // 仍做内容变化检测(歌名/状态变化推送不依赖歌词)
            checkContentChangedAndPush();
            return true;
        }
        if (line.isEmpty() || currentTitle == null || currentTitle.isEmpty()) {
            return true;
        }
        // 轻量即时推送：歌词行变化立即更新主界面(晴空)歌词，不等每秒全量 pushAll。背景：EAS 是 500ms pollLyricOnce、UI 全量是 1s poll
        // 防抖)，UI 歌词常比 EAS 慢 0.5~1s。这里与 EAS 同源(getBoundLyricLine)同拍
        // 推送，独立去重 lastUiLyricLine，不依赖 isDimPushEnabled，也不替代每秒全量推送。
        try {
            if (!line.equals(lastUiLyricLine)) {
                lastUiLyricLine = line;
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (MainActivity.sInstance != null) {
                                MainActivity.sInstance.pushLyricLineLight();
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "LyricPoll ui-light push exception: " + t.getMessage());
        }
        // 去重:行变化 或 同为播放态时也推(原版逻辑)
        boolean shouldPush;
        if (lastPushedLyricLine == null) {
            shouldPush = true;
        } else if (!line.equals(lastPushedLyricLine)) {
            shouldPush = true;
        } else if (lastPushedLyricState == STATE_PLAYING && state == STATE_PLAYING) {
            shouldPush = true;
        } else {
            shouldPush = false;
        }
        if (shouldPush && isDimPushEnabled()) {
            lastPushedLyricLine = line;
            lastPushedLyricState = state;
            String display = currentTitle;
            if (currentArtist != null && !currentArtist.isEmpty()) {
                display = display + " - " + currentArtist;
            }
            Log.i(TAG, "LyricPoll line=" + line);
            try {
                MediaCenterBridge.get().pushLyric(display, 0, line);
            } catch (Throwable t) {
                Log.e(TAG, "LyricPoll pushLyric exception: " + t.getMessage());
            }
        }
        // 内容变化检测:歌名/歌词/状态任一变化都推(pushMetadataToCard 内部去重),
        // 不依赖 onMetadataChanged 回调(真车可能不触发),歌词滚动时 EAS 标题跟随变化。
        checkContentChangedAndPush();
        return true;
    }

 /** ★ 修复:是否有可用歌词来源(网易云广播歌词 / 云歌词抓取器已绑定当前歌)。
 * 用于 mediaController 为 null 时保持轮询,防止 CarMediaWidget 停止刷新而卡住。 */
    private boolean hasActiveLyricSource() {
        try {
            if (broadcastLyricLrc != null && !broadcastLyricLrc.isEmpty()) {
                return true;
            }
            if (cloudLyricFetcher != null) {
                String st = cloudLyricFetcher.getSongTitle();
                if (st != null && !st.isEmpty() && lyricTitleMatches(st, currentTitle)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
 /** 当前歌词行(UI 轮询用)。 */
 /**
 * 主界面歌词显示:与推送完全同源(getBoundLyricLine 按播放进度取当前歌词行)。
 */
    public String getCurrentLyricLine() {
        try {
            // 蓝牙 flicker 场景（AVRCP 把歌词行塞进 TITLE）：currentTitle 本身就是歌词行，
            // 直接作为当前歌词返回，让 UI 按歌词样式显示（带发光阴影），而不是当歌名处理。
            if (titleFlickerLyricActive && currentTitle != null && !currentTitle.isEmpty()) {
                return currentTitle;
            }
            return getBoundLyricLine();
        } catch (Exception e) {
            Log.w(TAG, "getCurrentLyricLine error: " + e.getMessage());
        }
        return null;
    }
 /**
 * 主界面显示标题(与 EAS 推送同源,见 buildLyricPushPair):
 * 有歌词时返回当前歌词行,无歌词时返回歌名。
 */
    public String getDisplayTitle() {
        // USB 音源激活时返回 USB 当前歌曲标题
        if (usbRouteArmed && usbSourceController != null) {
            UsbMusicItem item = usbSourceController.getCurrentItem();
            if (item != null && item.title != null && !item.title.isEmpty()) {
                return item.title;
            }
        }
        try {
            String line = getBoundLyricLine();
            if (line != null && !line.isEmpty()) {
                return line;
            }
        } catch (Exception e) {
            Log.w(TAG, "getDisplayTitle error: " + e.getMessage());
        }
        // 蓝牙 flicker 场景：currentTitle 就是歌词行（AVRCP 推的），
        // 直接返回它作为"歌词行"语义，与正常 LRC 有歌词时 title=歌词行 保持一致。
        // 真实歌名/歌手存在 flickerStableTitle/Artist，由 getDisplayArtist() 拼成"歌名 - 歌手"。
        return currentTitle;
    }
 /**
 * 桌面小部件用：当前歌曲真实歌名（metadata 源 currentTitle）。
 * 新增——getDisplayTitle 有歌词时返回歌词行（主界面语义），
 * 小部件上方"歌曲信息"需要真实歌名/歌手，与下方歌词不重复。
 */
    public String getMetaTitle() {
        return currentTitle;
    }

 /** 桌面小部件用：当前歌曲真实歌手（metadata 源 currentArtist）。 */
    public String getMetaArtist() {
        return currentArtist;
    }

 /**
 * 主界面显示歌手(与 EAS 推送同源,见 buildLyricPushPair):
 * 有歌词时返回"歌名 - 歌手",无歌词时返回歌手。
 */
    public String getDisplayArtist() {
        // USB 音源激活时返回 USB 当前歌曲歌手
        if (usbRouteArmed && usbSourceController != null) {
            UsbMusicItem item = usbSourceController.getCurrentItem();
            if (item != null) {
                String a = item.artist != null ? item.artist : "";
                String al = item.album != null ? item.album : "";
                if (!al.isEmpty()) return a + " · " + al;
                return a;
            }
        }
        // 蓝牙 flicker 场景：currentTitle 是歌词行，不能用来拼"歌名 - 歌手"。
        // JS 逻辑：非 USB 有歌词时第一行显示 artist 字段（= "歌名 - 歌手"拼接串），
        // 所以这里必须拼回 "flickerStableTitle - flickerStableArtist"，与正常 LRC 有歌词时的格式一致。
        if (titleFlickerLyricActive && flickerStableTitle != null && !flickerStableTitle.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(flickerStableTitle);
            sb.append(" - ");
            if (flickerStableArtist != null && !flickerStableArtist.isEmpty()) {
                sb.append(flickerStableArtist);
            } else if (currentArtist != null && !currentArtist.isEmpty()) {
                sb.append(currentArtist);
            }
            return sb.toString();
        }
        try {
            String line = getBoundLyricLine();
            if (line != null && !line.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (currentTitle != null) {
                    sb.append(currentTitle);
                }
                sb.append(" - ");
                if (currentArtist != null) {
                    sb.append(currentArtist);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "getDisplayArtist error: " + e.getMessage());
        }
        return currentArtist;
    }
    // ================= P2: 控制器管理 =================
 /** 刷新控制器:置脏后重新扫描可用会话并选择。 */
    public void refreshController() {
        // ghost 控制器保护:不要在 ghost 模式下自动重选
        // 保护窗口化(ghostProtectUntil),窗口外允许重选,否则 ghost
        // 永久挡住 refreshController,通知触发切源永远失败。
        if (ghostControllerPkg != null && System.currentTimeMillis() < ghostProtectUntil) {
            Log.d(TAG, "refreshController: ghost controller active, skip");
            return;
        }
        controllerStale = false;
        unregisterControllerCallback();
        mediaController = null;
        updateAvailableControllers();
    }

 /**
 * Bug#72B :开机补扫选中控制器。
 * 车机冷启动时音源(酷我/酷狗等)MediaSession 可能注册晚于开机扫描,导致
 * "音源在播但控制器没选上"(用户实测:打开主界面后控制器仍未选中,需手动选)。
 * 在 mediaController==null 期间按递增间隔补扫 refreshController(),选中即停。
 * 仅"未选中"状态生效,无副作用(没源时 refreshController 保持 null 并安全返回;
 * ghost 保护窗口内 refreshController 自己会跳过)。
 */
    private void scheduleBootControllerRescan(final long delayMs, final int remainingTries) {
        if (remainingTries <= 0) {
            return;
        }
        firstPushHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mediaController != null && !controllerStale) {
                        return; // 已选中,停止补扫
                    }
                    Log.i(TAG, "boot controller rescan: not selected yet, re-scan (delay="
                            + delayMs + "ms, remaining=" + remainingTries + ")");
                    refreshController();
                    scheduleBootControllerRescan(delayMs * 2 + 2000L, remainingTries - 1);
                } catch (Throwable t) {
                    Log.w(TAG, "boot controller rescan error: " + t.getMessage());
                }
            }
        }, delayMs);
    }
 /**
 * 检查指定包名是否真实在播(用于通知触发切源时优先判断)。
 * 蓝牙播放中打开酷我时,通用 refreshController 的选源逻辑会先遍历到蓝牙
 * (当前控制器在列表前面),isBluetoothReallyPlaying() 返回 true 就 break 选了蓝牙,
 * 轮不到检查酷我。因此通知触发时先判断通知来源是否真在播,在播就直接切过去。
 */
    public boolean isSourceReallyPlaying(String pkg) {
        return isAudioReallyPlaying(pkg);
    }
 /**
 * 直接切换到指定包名的控制器(如果该包名有活跃会话)。
 * 用于通知触发切源时绕过通用选源逻辑,直接切到用户刚播放的音源。
 */
    public boolean switchToController(String pkg) {
        MediaController ctrl = findControllerByPackage(pkg);
        if (ctrl != null) {
            Log.i(TAG, "switchToController: directly switch to " + pkg);
            selectController(ctrl);
            return true;
        }
        Log.w(TAG, "switchToController: no active session for " + pkg);
        return false;
    }
 /**
 * 第三方广播入口：按包名切换到指定音源（蓝牙/酷我/酷狗/QQ/网易云…），
 * 与 UI 点击选源 confirmControllerSelectionByPkg 同一套逻辑。
 *
 * XCMedia2 兼容性（验证）：
 * - 本方法只切换控制器(selectController/ghost)，不发任何系统广播，
 * 因此不受 XCMedia2 启用/禁用影响。
 * - XCMedia2 启用(当前主场景,USE_BLUETOOTH_FOCUS_HOLD=true)：切到蓝牙后
 * 蓝牙真实播放时由既有周期任务 maybeKillMultimediaOnBluetoothPlay
 * (5s easFocusGuard)自动锚定+焦点仲裁，XCMedia2 不会暂停蓝牙，无需本方法处理。
 * - XCMedia2 禁用(历史/手动)：互斥不存在，selectController 直接生效；
 * 若此后蓝牙播放触发 keepXcmediaOnBluetoothSource 发广播给已禁用应用，
 * 系统按 setPackage 定向丢弃，无害。
 * 注意：不要在蓝牙分支主动发 ECARX_WIDGET_BLUETOOTH_PLAY——那会把暂停的
 * 蓝牙误拉起(见 maybeKillMultimediaOnBluetoothPlay v2 注释)，交给周期任务
 * 按"蓝牙真实播放"状态处理最安全。
 */
 /** 广播完美版：切到蓝牙源时置位，等焦点仲裁后补发 play（两段式延迟补播）。 */
    public volatile boolean pendingBtAutoPlay;

    public boolean switchToSource(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        // 广播完美版：请求切到蓝牙时先举"自动播放"旗。直接 play 发太早无效
        // （控制器刚注册/焦点未就绪），故两段式：置旗 → 焦点仲裁后补发 play。
        if (PKG_BLUETOOTH.equals(pkg)) {
            pendingBtAutoPlay = true;
            Log.i(TAG, "switchToSource: bluetooth requested, arm auto-play flag");
        }
        // USB 音源激活先停用（与 confirmControllerSelectionByPkg 一致）
        if (isUsbRouteArmed()) {
            try {
                deactivateUsbSource();
            } catch (Throwable t) {
                Log.w(TAG, "switchToSource: deactivateUsb err " + t.getMessage());
            }
        }
        if (PKG_CARPLAY_BOX.equals(pkg)) {
            // 与 UI 一致：点击 CarPlay 条目 = 手动切换选中
            setCarplayBoxSelected(true);
            autoMergeCarplayBoxIfPresent();
            return true;
        }
        if (isCarplayBoxRealPkg(pkg)) {
            // 合并/取消合并盒子真实 MediaSession
            if (isCarplayBoxSelected() && isCarplayBoxMerged(pkg)) {
                dismissCarplayMerge();
                return true;
            }
            setCarplayBoxSelected(true);
            setCarplayBoxMerged(pkg);
            if (!switchToController(pkg)) {
                Log.w(TAG, "switchToSource: box session not found " + pkg);
            }
            return true;
        }
        // 真实音源（活跃会话）：蓝牙/酷我/酷狗/QQ 都在这里
        if (switchToController(pkg)) {
            setCarplayBoxSelected(false);
            return true;
        }
        // ghost（暂停会话，如暂停的酷我）
        List<String> ghosts = getGhostControllerPkgs();
        if (ghosts.contains(pkg)) {
            setCarplayBoxSelected(false);
            setGhostControllerPkg(pkg);
            // 广播完美版：蓝牙 ghost 分支，立即补发一次 play（第二道保险）。
            // switchToController 失败时仍保证切蓝牙能自动播放。
            if (PKG_BLUETOOTH.equals(pkg)) {
                try {
                    MediaController bt = findControllerByPackage(PKG_BLUETOOTH);
                    if (bt != null) {
                        executeTransportAction(bt, "play");
                        Log.i(TAG, "switchToSource: bluetooth branch, play sent");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "switchToSource: bluetooth play error: " + t.getMessage());
                }
            }
            return true;
        }
        Log.w(TAG, "switchToSource: unknown source " + pkg);
        return false;
    }
 /**
 * 广播完美版：消费 pendingBtAutoPlay 标记，向蓝牙控制器补发一次 play。
 * 在"切蓝牙未播放"的延迟焦点仲裁（arbitrateFocusToMultimedia）之后调用，
 * 此时 EAS 播放焦点已交还 XCMedia2，补 play 不会被"no focus to pause"暂停。
 */
    public void consumeBtAutoPlayIfPending() {
        try {
            if (!pendingBtAutoPlay) {
                return;
            }
            pendingBtAutoPlay = false;
            MediaController bt = findControllerByPackage(PKG_BLUETOOTH);
            if (bt == null) {
                Log.w(TAG, "consumeBtAutoPlay: bt controller not found, skip");
                return;
            }
            executeTransportAction(bt, "play");
            Log.i(TAG, "consumeBtAutoPlay: play sent to bluetooth");
        } catch (Throwable t) {
            Log.w(TAG, "consumeBtAutoPlay error: " + t.getMessage());
        }
    }
 /**
 * 只刷新可用控制器列表,不改变当前选中的控制器(无副作用)。
 * 用于 UI 打开选择对话框前刷新列表,避免"点一下按钮就自动切换"的问题。
 */
    public void refreshControllerList() {
        MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
        if (msm == null) {
            return;
        }
        List<MediaController> fresh = new ArrayList<>();
        // cn/null 查询各自独立 try-catch——原实现整个方法一个大 try,
        // getActiveSessions 抛 SecurityException/监听未就绪异常时直接跳出,后面的
        // mergeKnownControllers(getSessions 全量,含 active=false 的 QQ 音乐车机版)
        // 不会执行 → 弹层列表缺 QQ;而"刷新"按钮走 updateAvailableControllers
        // (cn 查询局部 catch,known/null 兜底链照常跑)能扫到 → 造成
        // "打开列表没有、点刷新才有"的不一致。现在两条路径兜底链对齐。
        try {
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            List<MediaController> sessions = msm.getActiveSessions(cn);
            if (sessions != null) {
                for (MediaController c : sessions) {
                    String pkg = c.getPackageName();
                    // 系统媒体中心(XSFMediaCenter 等)与通话管理(telecom)不出现在控制器列表
                    if (pkg != null && !pkg.equals(PKG_SELF) && !isHiddenController(pkg)) {
                        fresh.add(c);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshControllerList cn query error: " + t.getMessage());
        }
        if (fresh.isEmpty()) {
            try {
                List<MediaController> all = msm.getActiveSessions(null);
                if (all != null) {
                    for (MediaController c : all) {
                        String pkg = c.getPackageName();
                        if (pkg != null && !pkg.equals(PKG_SELF) && !isHiddenController(pkg)) {
                            fresh.add(c);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "refreshControllerList null query error: " + t.getMessage());
            }
        }
        // 合并全量会话(含 active=false 的暂停/非活跃会话,如暂停中的酷我车机版、
        // active 标志异常的 QQ 音乐车机版):getActiveSessions 只返回 active=true,
        // 这类 App 不会出现在 getActiveSessions 结果里,必须用 getSessions 全量兜底。
        try {
            mergeKnownControllers(fresh);
        } catch (Throwable t) {
            Log.w(TAG, "refreshControllerList merge error: " + t.getMessage());
        }
        if (!fresh.isEmpty()) {
            availableControllers = fresh;
        }
    }
 /**
 * 获取所有已知媒体会话(含 active=false 的暂停会话,如暂停中的酷我车机版)。
 * getActiveSessions 只返回 active=true 的会话;getSessions 返回全量会话,
 * 但它是 @hide API,需反射调用。CarMedia 已申请通知监听权限,
 * 系统会把它当作可信监听者,getSessions 对 notification listener 可用。
 */
    private List<MediaController> getKnownControllers() {
        List<MediaController> result = new ArrayList<>();
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return result;
            }
            Object sessions = null;
            // 1) 优先:API 29+ 公开 getSessions(ComponentName) / getSessions()
            try {
                Method m = MediaSessionManager.class.getMethod("getSessions", ComponentName.class);
                sessions = m.invoke(msm, (ComponentName) null);
            } catch (Throwable t1) {
                try {
                    Method m2 = MediaSessionManager.class.getMethod("getSessions");
                    sessions = m2.invoke(msm);
                } catch (Throwable t2) {
                    // 正常降级路径：Android 9(API28) 无公开 getSessions(API29+ 才有)，走 binder 兜底。
                    // 降 D 级，避免把必然的降级误当成错误、误导排查(原 W 每5秒刷一条)。
                    Log.d(TAG, "getKnownControllers: public getSessions unavailable, fallback to binder: "
                            + t2.getMessage());
                }
            }
            if (sessions == null) {
                // 2) API 21-28 无公开 getSessions:反射 MediaSessionManager.mService
                // (IMediaSessionManager) 直接调 getSessions。底层接口对已授权的
                // 通知监听器返回全量会话(含 active=false 的暂停会话,如暂停中的酷我)。
                sessions = getSessionsViaBinder(msm);
            }
            if (sessions instanceof List) {
                for (Object o : (List<?>) sessions) {
                    if (o == null) {
                        continue;
                    }
                    if (o instanceof MediaController) {
                        result.add((MediaController) o);
                    } else if (o instanceof android.media.session.MediaSession.Token) {
                        // 部分 ROM 返回 List<MediaSession.Token>
                        try {
                            result.add(new MediaController(this,
                                    (android.media.session.MediaSession.Token) o));
                        } catch (Throwable t) {
                            Log.w(TAG, "getKnownControllers: token->controller fail: " + t.getMessage());
                        }
                    } else if (o instanceof android.os.IBinder) {
                        // API 28 ISessionManager.getSessions 返回 List<IBinder>
                        // (每个元素是 ISessionController 的 binder 句柄),需转成
                        // MediaController 才能用 getPackageName/transportControls 等。
                        MediaController mc = controllerFromBinder((android.os.IBinder) o);
                        if (mc != null) {
                            result.add(mc);
                            Log.d(TAG, "getKnownControllers: binder->controller pkg="
                                    + mc.getPackageName());
                        }
                    } else {
                        Log.d(TAG, "getKnownControllers: unknown element class="
                                + o.getClass().getName());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getKnownControllers error: " + t.getMessage());
        }
        // 同包名智能去重 :CarPlay/手机互联等应用可能注册多个 MediaSession
        // (一个媒体播放 + 一个语音/导航辅助,或重复注册),UI 列表只显示一个。
        // 保留策略:优先保留有 metadata/PLAYING 状态的媒体会话,过滤掉语音/导航辅助会话。
        try {
            java.util.Map<String, MediaController> dedup = new java.util.LinkedHashMap<>();
            for (MediaController c : result) {
                if (c == null) continue;
                String pkg = c.getPackageName();
                if (pkg == null) {
                    dedup.put("__null__" + System.identityHashCode(c), c);
                    continue;
                }
                if (!dedup.containsKey(pkg)) {
                    dedup.put(pkg, c);
                } else {
                    MediaController existing = dedup.get(pkg);
                    // 新会话更像媒体则替换
                    if (isMoreMediaLike(c, existing)) {
                        dedup.put(pkg, c);
                    }
                }
            }
            if (dedup.size() < result.size()) {
                Log.d(TAG, "getKnownControllers: dedup " + result.size() + " -> " + dedup.size());
                result = new ArrayList<>(dedup.values());
            }
        } catch (Throwable t) {
            Log.w(TAG, "getKnownControllers dedup error: " + t.getMessage());
        }
        // 盒子真实 MediaSession 不再过滤——合并模式下需出现在列表
        // 供用户选择为信息源(getControllerMetadata 读取歌曲信息)。其切歌能力由
        // isCarplayBoxRealPkg 在自动选源/独立控制路径中排除,不参与 transportControls。
        return result;
    }

 /**
 * 判断 a 是否比 b 更像媒体播放会话(同包名多会话去重时选择保留哪个)。
 * 比较优先级:有 metadata > state=PLAYING/PAUSED > 有 play/pause actions > 第一个
 */
    private boolean isMoreMediaLike(MediaController a, MediaController b) {
        if (a == null) return false;
        if (b == null) return true;
        // 1. 有 metadata(title 非空)的更像媒体
        boolean aHasMeta = false;
        boolean bHasMeta = false;
        try {
            MediaMetadata am = a.getMetadata();
            MediaMetadata bm = b.getMetadata();
            aHasMeta = (am != null && am.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                    && !am.getString(MediaMetadata.METADATA_KEY_TITLE).isEmpty());
            bHasMeta = (bm != null && bm.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                    && !bm.getString(MediaMetadata.METADATA_KEY_TITLE).isEmpty());
        } catch (Exception e) {
            // ignore
        }
        if (aHasMeta != bHasMeta) return aHasMeta;
        // 2. state 为 PLAYING/PAUSED 的更像媒体(语音会话通常 state=NONE 或快速变化)
        int aState = -1;
        int bState = -1;
        try {
            PlaybackState aps = a.getPlaybackState();
            PlaybackState bps = b.getPlaybackState();
            if (aps != null) aState = aps.getState();
            if (bps != null) bState = bps.getState();
        } catch (Exception e) {
            // ignore
        }
        boolean aMediaState = (aState == PlaybackState.STATE_PLAYING || aState == PlaybackState.STATE_PAUSED);
        boolean bMediaState = (bState == PlaybackState.STATE_PLAYING || bState == PlaybackState.STATE_PAUSED);
        if (aMediaState != bMediaState) return aMediaState;
        // 3. 有 play/pause actions 的更像媒体
        long aActions = 0;
        long bActions = 0;
        try {
            PlaybackState aps2 = a.getPlaybackState();
            PlaybackState bps2 = b.getPlaybackState();
            if (aps2 != null) aActions = aps2.getActions();
            if (bps2 != null) bActions = bps2.getActions();
        } catch (Exception e) {
            // ignore
        }
        boolean aHasActions = (aActions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)) != 0;
        boolean bHasActions = (bActions & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)) != 0;
        if (aHasActions != bHasActions) return aHasActions;
        // 4. 都差不多,保留第一个(返回 false 表示不替换)
        return false;
    }
 /**
 * 把 ISessionController 的 binder 句柄转成 MediaController。
 * 走 Parcelable 序列化路径:把 binder 写进 Parcel,再经 Token.CREATOR.createFromParcel
 * 反序列化得到 Token,最后 new MediaController(context, token) --
 * 全部走系统公开/可反射路径,不依赖被擦除的隐藏构造。
 */
    private MediaController controllerFromBinder(android.os.IBinder binder) {
        try {
            // 1) ISessionController binder -> Parcel
            android.os.Parcel p = android.os.Parcel.obtain();
            try {
                p.writeStrongBinder(binder);
                p.setDataPosition(0);
                // 2) Parcel -> MediaSession.Token (经 CREATOR)
                android.media.session.MediaSession.Token token =
                        android.media.session.MediaSession.Token.CREATOR.createFromParcel(p);
                // 3) Token -> MediaController (公开构造)
                return new MediaController(this, token);
            } finally {
                p.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "controllerFromBinder error: " + t.getMessage());
            return null;
        }
    }
 /**
 * 反射 MediaSessionManager.mService (IMediaSessionManager) 调 getSessions。
 * 底层 binder 接口对已授权的通知监听器返回全量会话(含 active=false 的暂停会话),不受
 * getActiveSessions 的 active 过滤影响。API 28 返回 List<IBinder>(ISessionController)。
 */
    // 卡死加固：getSessionsViaBinder 结果 5s 缓存（成功/失败都缓存）。
    // 之前 warmup 扫描/未授权环境下每次推送都反射失败并打 W 日志（每秒 1 次），
    // logcat 洪泛 + 重复反射会拖慢车机。缓存后 5 秒内不再重复反射。
    private volatile Object sessionsBinderCache;
    private volatile long sessionsBinderCacheTime = 0L;
    private static final long SESSIONS_BINDER_CACHE_MS = 5000L;
    private static final Object SESSIONS_BINDER_FAIL = new Object();

    private Object getSessionsViaBinder(MediaSessionManager msm) {
        long sbn = android.os.SystemClock.elapsedRealtime();
        if (sessionsBinderCache != null && sbn - sessionsBinderCacheTime < SESSIONS_BINDER_CACHE_MS) {
            return (sessionsBinderCache == SESSIONS_BINDER_FAIL) ? null : sessionsBinderCache;
        }
        try {
            java.lang.reflect.Field f = MediaSessionManager.class.getDeclaredField("mService");
            f.setAccessible(true);
            Object svc = f.get(msm);
            Log.d(TAG, "getSessionsViaBinder: mService=" + svc);
            if (svc == null) {
                return null;
            }
            try {
                Method m = svc.getClass().getMethod("getSessions", ComponentName.class, int.class);
                int userId = 0;
                try {
                    Method myUserId = android.os.UserHandle.class.getMethod("myUserId");
                    userId = (Integer) myUserId.invoke(null);
                } catch (Throwable ue) {
                    Log.w(TAG, "getSessionsViaBinder: myUserId reflect fail, use 0: " + ue.getMessage());
                }
                Object r = m.invoke(svc, (ComponentName) null, userId);
                Log.d(TAG, "getSessionsViaBinder: getSessions(null," + userId + ") -> "
                        + (r == null ? "null" : r.getClass().getName() + " size=" + ((List<?>) r).size()));
                if (r != null) { sessionsBinderCache = r; sessionsBinderCacheTime = sbn; }
                return r;
            } catch (Throwable t1) {
                Log.w(TAG, "getSessionsViaBinder: getSessions(ComponentName,int) fail: " + t1.getMessage());
            }
            try {
                Method m = svc.getClass().getMethod("getSessions", ComponentName.class);
                Object r = m.invoke(svc, (ComponentName) null);
                Log.d(TAG, "getSessionsViaBinder: getSessions(null) -> "
                        + (r == null ? "null" : r.getClass().getName() + " size=" + ((List<?>) r).size()));
                if (r != null) { sessionsBinderCache = r; sessionsBinderCacheTime = sbn; }
                return r;
            } catch (Throwable t2) {
                Log.w(TAG, "getSessionsViaBinder: getSessions(ComponentName) fail: " + t2.getMessage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSessionsViaBinder error: " + t.getMessage());
        }
        // 失败也缓存（5s），避免每秒推送重复反射失败 + 日志洪泛
        sessionsBinderCache = SESSIONS_BINDER_FAIL;
        sessionsBinderCacheTime = sbn;
        return null;
    }
 /** 把全量会话(含暂停会话)合并进控制器列表,已存在的包名不重复。 */
    private void mergeKnownControllers(List<MediaController> list) {
        try {
            List<MediaController> known = getKnownControllers();
            if (known.isEmpty()) {
                return;
            }
            for (MediaController kc : known) {
                String kp = kc != null ? kc.getPackageName() : null;
                if (kp == null || kp.equals(PKG_SELF) || isHiddenController(kp)) {
                    continue;
                }
                boolean exists = false;
                for (MediaController c : list) {
                    if (c != null && kp.equals(c.getPackageName())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    list.add(kc);
                    Log.d(TAG, "mergeKnownControllers: added paused/inactive session " + kp);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "mergeKnownControllers error: " + t.getMessage());
        }
    }
 /** 扫描可用媒体会话并自动选择最优控制器。 */
    private void updateAvailableControllers() {
        // armed 残留但 USB 实际已失效(拔出事件丢失)→ 复位,恢复外部扫描
        // (否则蓝牙会话永远不被发现:控制器列表缺失、controllerPkg 卡 USB 旧歌)。
        resetUsbRouteIfDead("updateAvailableControllers");
        // USB 音源激活时(仍有效)不扫描外部控制器
        if (usbRouteArmed) {
            Log.d(TAG, "updateAvailableControllers: usb route armed, skip external scan");
            return;
        }
        availableControllers = new ArrayList<>();
        MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
        if (msm == null) {
            Log.w(TAG, "updateAvailableControllers: msm is null");
            return;
        }
        List<MediaController> sessions = null;
        try {
            ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
            sessions = msm.getActiveSessions(cn);
        } catch (SecurityException e) {
            Log.w(TAG, "getActiveSessions security exception: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "getActiveSessions error: " + e.getMessage());
        }
        if (sessions == null || sessions.isEmpty()) {
            // 活跃会话为空不代表没有媒体会话:暂停中的 App(如酷我车机版)active=false,
            // 不在 getActiveSessions 返回列表里,会导致暂停的酷我既进不了控制器列表、
            // 也无法被重新选为控制器。用 getSessions(全量会话)兜底。
            List<MediaController> known = getKnownControllers();
            if (!known.isEmpty()) {
                Log.d(TAG, "updateAvailableControllers: active sessions empty, use known sessions fallback ("
                        + known.size() + ")");
                sessions = known;
            } else {
                // Bug#70 :通知监听器未就绪时 getActiveSessions(cn) 与
                // getSessions(getKnownControllers) 都会被系统拒绝返回空。最后再用
                // getActiveSessions(null) 兜底一次——本应用有 MEDIA_CONTENT_CONTROL
                // 权限 + sharedUserId=android.uid.system，null 查询不依赖通知监听器
                // 状态，能拿到全部活跃会话，避免"首装控制器列表为空"后直接放弃。
                try {
                    List<MediaController> all0 = msm.getActiveSessions(null);
                    if (all0 != null && !all0.isEmpty()) {
                        Log.d(TAG, "updateAvailableControllers: getActiveSessions(null) fallback ("
                                + all0.size() + ")");
                        sessions = all0;
                    }
                } catch (Throwable t0) {
                    Log.w(TAG, "updateAvailableControllers: getActiveSessions(null) fallback error: "
                            + t0.getMessage());
                }
                if (sessions == null || sessions.isEmpty()) {
                    Log.w(TAG, "updateAvailableControllers: no sessions");
                    if (mediaController != null) {
                        controllerStale = true;
                    }
                    // 活跃会话一个都没有 = 音源已停止:推送停止状态(幂等,只推一次),
                    // 防止小卡片/仪表盘一直残留最后一次"播放中"状态
                    pushStoppedToCard();
                    return;
                }
            }
        }
        for (MediaController c : sessions) {
            String pkg = c.getPackageName();
            // 隐藏列表过滤:telecom 不入 availableControllers,但保留下方通话结束恢复判断
            // (该判断扫描的是会话列表 sessions,不受此过滤影响)
            if (pkg != null && !pkg.equals(PKG_SELF) && !isHiddenController(pkg)) {
                availableControllers.add(c);
            }
        }
        // 增强:cn 查询仅返回自身时用 null 查询兜底(不影响真车行为,仅模拟器兼容)
        if (availableControllers.isEmpty()) {
            try {
                List<MediaController> all = msm.getActiveSessions(null);
                if (all != null) {
                    for (MediaController c : all) {
                        String pkg = c.getPackageName();
                        if (pkg != null && !pkg.equals(PKG_SELF) && !isHiddenController(pkg)) {
                            availableControllers.add(c);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getActiveSessions(null) fallback error: " + e.getMessage());
            }
        }
        // 增强 2:合并全量会话(暂停的 App active=false 不在活跃列表,如酷我车机版暂停态;
        // getSessions 对已授权的通知监听器可用),保证暂停的酷我出现在控制器列表、
        // 也能被重新选为控制器。
        mergeKnownControllers(availableControllers);
        if (availableControllers.isEmpty()) {
            if (mediaController != null) {
                controllerStale = true;
            }
            pushStoppedToCard();
            return;
        }
        // 通话结束恢复:通话管理(com.android.server.telecom)会话消失后,
        // 优先切回通话前的控制器(酷我等),避免蓝牙残留 AudioTrack 抢先被选。
        if (inTelecomCall) {
            // 注意:telecom 已从 availableControllers 隐藏过滤,不能扫该列表判断通话是否仍在
            // (否则恒为 gone 误判通话结束),改为独立查询 telecom 会话是否存活。
            boolean telecomGone = !isTelecomSessionActive();
            if (telecomGone) {
                inTelecomCall = false;
                telecomEndProtectUntil = System.currentTimeMillis() + TELECOM_END_PROTECT_MS;
                Log.i(TAG, "telecom call ended (session gone), restore pre-call controller=" + preCallControllerPkg);
                if (preCallControllerPkg != null) {
                    MediaController restore = findControllerByPackage(preCallControllerPkg);
                    if (restore != null) {
                        Log.i(TAG, "telecom restore: switching back to " + preCallControllerPkg);
                        selectController(restore);
                        return;
                    }
                    Log.w(TAG, "telecom restore: pre-call controller " + preCallControllerPkg
                            + " no longer active, fall through to normal selection");
                }
            }
        }
        // 选择逻辑:优先选真实音频在播(AudioTrack started)的会话--
        // MediaSession state=3 可能陈旧(如汽水暂停后残留),活跃栈序也不可靠。
        MediaController selected = null;
        // 排除系统媒体中心/EAS宿主(非真实音源,常驻 MediaSession/AudioTrack 会误导选源)
        List<MediaController> candidates = new ArrayList<>();
        for (MediaController c : availableControllers) {
            String pkg = c.getPackageName();
            // CarPlay 盒子真实会话也排除——它只有歌曲信息、无切歌能力
            // (actions 无 SKIP 位),自动选中会让方控落空;合并模式下由用户手动选择。
            if (!isSystemMediaPackage(pkg) && !isCarplayBoxRealPkg(pkg)) {
                candidates.add(c);
            }
        }
        // HiCar 手机互联场景 :HiCar 不创建自己的 MediaSession,
        // 音乐播放通过 ecarx.xsf.mediacenter 代理。但 mediacenter 在 SYSTEM_MEDIA_BLACKLIST
        // 中被过滤,且 HiCar 连接时蓝牙 MediaSession 常驻导致 candidates 非空,
        // 永远走不到下方 candidates.isEmpty() 的 mediacenter fallback 分支。
        // 检测到 HiCar 已连接且 mediacenter 有活跃在播会话时,优先选中 mediacenter 代理,
        // 绕过蓝牙候选干扰。当前控制器已是 mediacenter 时不重复切换。
        if (isHicarConnected() && !PKG_MEDIACENTER.equals(controllerPkg)) {
            MediaController mcProxy = findActiveMediacenterController();
            if (mcProxy != null) {
                PlaybackState mcPs = mcProxy.getPlaybackState();
                boolean mcPlaying = mcPs != null && mcPs.getState() == STATE_PLAYING;
                if (mcPlaying) {
                    Log.i(TAG, "updateAvailableControllers: HiCar connected, mediacenter proxy playing, switch to " + PKG_MEDIACENTER);
                    selectController(mcProxy);
                    return;
                }
            }
        }
        // CarLife 手机互联场景 :这台车(CarFull)的 CarLife 是
        // com.muyetuge.carlifevehicle(百度CarLife 车机版),连接手机时**自建 MediaSession**
        // (真车日志 20:20:24 "【媒体会话】onConnectionDetached: MediaSession 已释放"确认),
        // 并非下方 candidates.isEmpty() 注释假设的"CarLife 不创建 MediaSession、走 mediacenter 代理"。
        // 若不选中 carlife 自身会话:controllerPkg 停在酷狗/蓝牙 → 元数据推"—"(无歌曲信息),
        // 方控 onNextCommand 转发到错误目标(切错源/落空)。与 HiCar 分支不同,这里优先直接选中
        // carlife 自身会话(自带元数据+transportControls),选不中时下方 fallback 仍可兜底 mediacenter。
        // 仅在 carlife 真实在播(或 MediaSession state=PLAYING)时强制切换,避免误抢蓝牙/酷狗在播。
        if (isCarlifeSessionActive() && !isCarlifePkg(controllerPkg)) {
            // (Bug#77):按真实会话包名查找(baidu/muyetuge 两种都兼容),
            // 不能用固定 PKG_CARLIFE 常量——真车实装 com.baidu.carlifevehicle 时
            // findControllerByPackage(PKG_CARLIFE) 会找不到会话导致 CarLife 不被选中。
            MediaController carlifeCtrl = null;
            for (MediaController c : getKnownControllers()) {
                if (c != null && isCarlifePkg(c.getPackageName())) {
                    carlifeCtrl = c;
                    break;
                }
            }
            if (carlifeCtrl == null) {
                carlifeCtrl = findControllerByPackage(PKG_CARLIFE);
            }
            if (carlifeCtrl != null) {
                String clPkg = carlifeCtrl.getPackageName();
                boolean clRealPlaying = isAudioReallyPlaying(clPkg);
                PlaybackState clPs = carlifeCtrl.getPlaybackState();
                boolean clPlaying = clRealPlaying
                        || (clPs != null && clPs.getState() == STATE_PLAYING);
                if (clPlaying) {
                    Log.i(TAG, "updateAvailableControllers: CarLife session active & playing (real="
                            + clRealPlaying + "), switch to " + clPkg);
                    selectController(carlifeCtrl);
                    return;
                }
            }
        }
        if (candidates.isEmpty()) {
            // 无真实第三方音源 → 检查 mediacenter 是否有活跃会话(手机互联场景:
            // CarPlay/HiCar/CarLife 等 app 自己不创建 MediaSession,播放控制全走 mediacenter 代理)。
            // 有则选中 mediacenter 作为代理控制器;无则保持现有控制器不动。
            MediaController mcProxy = findActiveMediacenterController();
            if (mcProxy != null) {
                Log.i(TAG, "updateAvailableControllers: no real source, using mediacenter as proxy controller");
                selectController(mcProxy);
                return;
            }
            // mediacenter 也没有会话 → 保持现有控制器不动,不自动切到系统媒体中心
            if (mediaController != null && !isSystemMediaPackage(mediaController.getPackageName())) {
                return;
            }
        }
        // 第一遍:真实音频 started 的会话(唯一真正在播的)
        boolean realPlayingSelected = false;
        for (MediaController c : candidates) {
            // ★ 蓝牙例外 :蓝牙 A2DP sink 的 AudioTrack
            // 常驻 started,会被误判为"真实在播"。当前控制器非蓝牙时跳过蓝牙,
            // 避免手动暂停 QQ 后一唤醒语音/服务重启就把控制器抢到蓝牙。
            if (PKG_BLUETOOTH.equals(c.getPackageName())
                    && (mediaController == null
                        || !PKG_BLUETOOTH.equals(mediaController.getPackageName()))) {
                continue;
            }
            if (isAudioReallyPlaying(c.getPackageName())) {
                selected = c;
                realPlayingSelected = true;
                break;
            }
        }
        // 第二遍:无真实在播时,退回原版逻辑(state∈{2,3} 优先,否则第一个)
        if (selected == null) {
            for (MediaController c : candidates) {
                // ★ 第2遍同样跳过"未真实播放且未连接"的蓝牙。本车机蓝牙
                // A2DP sink 的 MediaSession 常驻(即使没连手机也存在于会话列表),state
                // 残留 PAUSED/PLAYING,会被第2遍/第3遍误选为控制器(真车日志 13:48:32
                // "updateAvailableControllers: selected com.android.bluetooth" 确认,当时
                // 未连接蓝牙)。收紧:蓝牙仅在"物理就绪(A2DP+AVRCP)且会话
                // 真在播(有声音)"时才允许被自动选中(isBluetoothActuallyPlaying);连接但
                // 手机没放歌不算在播,不切;否则保持现状,避免方控/小卡片命令全部落到
                // "幽灵蓝牙/未播蓝牙"上没反应。
                // ★ 蓝牙"连上了"≠"在放音乐"。播放 QQ/落雪音乐时
                // 中途连上蓝牙(手机没放歌没声音),控制器不应被切到蓝牙。蓝牙自动选中
                // 仅当"物理就绪且会话真在播"两条件同时成立(见 isBluetoothActuallyPlaying)。
                if (PKG_BLUETOOTH.equals(c.getPackageName())
                        && !isBluetoothSelectable()) {
                    continue;
                }
                PlaybackState ps = c.getPlaybackState();
                if (ps != null) {
                    int s = ps.getState();
                    if (s == STATE_PLAYING || s == STATE_PAUSED) {
                        selected = c;
                        break;
                    }
                }
            }
        }
        if (selected == null && !candidates.isEmpty()) {
            for (MediaController c : candidates) {
                // ★ 蓝牙"连上了"≠"在放音乐"。播放 QQ/落雪音乐时
                // 中途连上蓝牙(手机没放歌没声音),控制器不应被切到蓝牙。蓝牙自动选中
                // 仅当"物理就绪且会话真在播"两条件同时成立(见 isBluetoothActuallyPlaying)。
                if (PKG_BLUETOOTH.equals(c.getPackageName())
                        && !isBluetoothSelectable()) {
                    continue;
                }
                selected = c;
                break;
            }
        }
        if (selected == null) {
            return;
        }
        // ghost 模式或用户刚选择控制器时,不自动切换。
        // 场景:用户选酷狗→点播放→酷狗 MediaSession 未 active→updateAvailableControllers
        // 被 OnActiveSessionsChanged 回调触发→选中正在播的汽水/酷我→selectController
        // 清除 ghostControllerPkg→handleMetadata 接受汽水/酷我的元数据→UI 显示错误歌名。
        // 修复:ghostControllerPkg!=null 或 controllerJustSelected=true 时,跳过自动切换,
        // 保持用户选择的控制器不动。
        // 修复:controllerJustSelected 已有 5s 自动重置;ghost 保护窗口化
        // (ghostProtectUntil)。窗口过期且出现"真实在播新源"时允许自动切换,否则
        // ghost/just-selected 永久挡住切源,打开新音源 App 播放时控制器/歌词停在旧源。
        // 用户手动选中 CarPlay(合并模式)时不自动切走——盒子会话在播时
        // 保持 CarPlay;但若用户另开真实音源播放(汽水/网易云 AudioTrack started),
        // 应自动切回该音源(selectController 内部清除 CarPlay 选中态与合并)。
        if (carplayBoxSelected) {
            autoMergeCarplayBoxIfPresent();
            MediaController otherPlaying = null;
            for (MediaController c : candidates) {
                if (c != null && c.getPackageName() != null
                        && isAudioReallyPlaying(c.getPackageName())) {
                    otherPlaying = c;
                    break;
                }
            }
            if (otherPlaying != null) {
                Log.i(TAG, "updateAvailableControllers: CarPlay selected but real source playing "
                        + otherPlaying.getPackageName() + ", switch back");
                selectController(otherPlaying);
                return;
            }
            Log.d(TAG, "updateAvailableControllers: CarPlay selected, skip auto-switch");
            return;
        }
        if (controllerJustSelected) {
            // (Q1):与 handlePlaybackConfigChanged 对齐——用户选的源真在播才尊重,
            // 没在播则按"谁在播放控制器就是谁"放行(selected 是候选里真实在播/在播态)。
            if (isControllerReallyPlaying()) {
                Log.d(TAG, "updateAvailableControllers: just-selected mode, current really playing, skip to "
                        + selected.getPackageName());
                return;
            }
            Log.d(TAG, "updateAvailableControllers: just-selected but current not really playing, allow switch to "
                    + selected.getPackageName());
        }
        if (ghostControllerPkg != null
                && (!realPlayingSelected || System.currentTimeMillis() < ghostProtectUntil)) {
            Log.d(TAG, "updateAvailableControllers: ghost protect, skip auto-switch to " + selected.getPackageName()
                    + " (ghostPkg=" + ghostControllerPkg
                    + ", realPlayingSelected=" + realPlayingSelected
                    + ", ghostProtectRemain=" + Math.max(0L, ghostProtectUntil - System.currentTimeMillis())
                    + ", currentControllerPkg=" + controllerPkg + ")");
            return;
        }
        Log.i(TAG, "updateAvailableControllers: selected " + selected.getPackageName());
        selectController(selected);
    }
 /** 切换/绑定指定控制器。 */
 /**
 * 自动切源前暂停当前控制器。
 * 场景:检测到新音源真实在播(AudioTrack started + MediaSession PLAYING)时,
 * 先给当前控制器发暂停命令,再切换控制器,避免两个播放器同时在播。
 * 仅在自动切源时调用;用户手动选择控制器时不自动暂停(用户可能想手动控制)。
 */
    private void pauseCurrentController() {
        if (mediaController != null) {
            try {
                String curPkg = mediaController.getPackageName();
                executeTransportAction(mediaController, "pause");
                Log.d(TAG, "pauseCurrentController: paused " + curPkg);
            } catch (Exception e) {
                Log.w(TAG, "pauseCurrentController failed: " + e.getMessage());
            }
        }
    }

    public void selectController(MediaController controller) {
        if (controller == null) {
            return;
        }
        // 硬闸门:仅 XCMedia2(酷我车机版)不可被选为控制器(会抢焦点误导选源)。
        // mediacenter 允许选中——无真实音源时作为手机互联 app 的系统代理控制器。
        if (isControlBlocked(controller.getPackageName())) {
            Log.i(TAG, "selectController: blocked control package " + controller.getPackageName());
            return;
        }
        // 自动切到非盒子真实音源(汽水/网易云等)时退出 CarPlay 模式——
        // 用户选中 CarPlay 后另开音源播放,应自动切回该音源;CarPlay 选中态与
        // 合并信息源一并清除(手动合并盒子不会命中:盒子包名在白名单内)。
        if (carplayBoxSelected && !isCarplayBoxRealPkg(controller.getPackageName())) {
            Log.i(TAG, "selectController: leaving CarPlay mode -> " + controller.getPackageName());
            setCarplayBoxSelected(false);
        }
        switchingController = true; // 防重入:推送触发的自动切换不再重复触发
        try {
            selectControllerInner(controller);
        } finally {
            switchingController = false;
        }
    }
 /**
 * 通话管理(com.android.server.telecom)会话是否存活(正在通话中)。
 * 独立查询不依赖 availableControllers(telecom 已被隐藏过滤)。
 */
    private boolean isTelecomSessionActive() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return false;
            }
            List<MediaController> all = msm.getActiveSessions(null);
            if (all != null) {
                for (MediaController c : all) {
                    if (c != null && PKG_TELECOM.equals(c.getPackageName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isTelecomSessionActive error: " + e.getMessage());
        }
        return false;
    }
    private void selectControllerInner(MediaController controller) {
        if (controller == null) {
            return;
        }
        // 切源时记录"上一个控制器/包",用于切走后暂停它(避免双音源同播)。
        MediaController prevController = mediaController;
        String prevPkg = controllerPkg;
        // 用户通过 UI 主动选择控制器:设置标志,dispatchMediaAction 第一次操作不自动切换
        // (自动切换也会走这里,但自动切换时 controllerJustSelected 已被 dispatchMediaAction 重置为 false,
        // 且自动切换后用户的第一次操作仍应尊重自动切换结果,所以这里统一设 true 是合理的)
        // (Q1):白名单 CarPlay 盒子(com.carletter.ceb/com.aufn.carplay)是"信息源",
        // 选中/合并它只是让 UI 显示盒子歌曲信息,切歌走 CarPlay 注入,不算"用户主动选源切歌",
        // 不设 just-selected 保护——否则合并后立即播放汽水/网易云,新源事件被 5s 保护吞掉,
        // 之后无事件重试,控制器永久卡在盒子上(用户实测"切不走")。同时清除任何残留保护。
        if (isCarplayBoxRealPkg(controller.getPackageName())) {
            controllerJustSelected = false;
            if (lyricPollHandler != null) {
                lyricPollHandler.removeCallbacks(resetControllerJustSelectedRunnable);
            }
            Log.i(TAG, "selectControllerInner: box info-source selected, clear just-selected protect");
        } else {
            controllerJustSelected = true;
            // 修复:5 秒后自动重置保护标志(与 dispatchMediaAction 4540 一致)。
            // 原实现只 removeCallbacks 不 postDelayed,标志永久为 true,
            // 导致用户选源后打开/播放其他音源 App 时,updateAvailableControllers/
            // dispatchMediaAction/handlePlaybackConfigChanged 全部 skip 自动切源,
            // 控制器和歌词停在旧源,主界面看起来"卡住",只能靠重新选控制器恢复。
            if (lyricPollHandler != null) {
                lyricPollHandler.removeCallbacks(resetControllerJustSelectedRunnable);
                lyricPollHandler.postDelayed(resetControllerJustSelectedRunnable, 5000);
            }
        }
        String newPkg = controller.getPackageName();
        // 通话中断控制器恢复:进入通话管理时记住通话前的控制器(等挂断后切回)
        if ("com.android.server.telecom".equals(newPkg)) {
            // 仅当之前不是通话管理时才记录(避免来电→通话→来电连续通话时覆盖掉
            // 更早的控制器;同一通电话内不重复记录)
            if (!"com.android.server.telecom".equals(controllerPkg)) {
                preCallControllerPkg = controllerPkg;
                Log.i(TAG, "telecom call: entering call, remember pre-call controller=" + preCallControllerPkg);
            }
            inTelecomCall = true;
        } else {
            // 离开通话管理:结束通话/切换,进入保护期,防止蓝牙残留 AudioTrack 抢走
            if (inTelecomCall) {
                telecomEndProtectUntil = System.currentTimeMillis() + TELECOM_END_PROTECT_MS;
                Log.i(TAG, "telecom call ended, protect until " + telecomEndProtectUntil);
            }
            inTelecomCall = false;
        }
        currentTitle = null;
        currentArtist = null;
        qqArtistComplete = false; // 切换控制器后重新等待 QQ 完整歌手
        frozenPlaybackPosition = 0; // 切换控制器后重置冻结进度
        playbackDuration = 0;       // 重置进度条总长( :切到酷我时若沿用旧歌
                                    // duration,进度条显示上一个音乐的刻度,直到 handleMetadata 更新新歌时长)
        lastBtPositionMs = 0;       // 重置蓝牙进度累加起点
        lastBtPositionTime = 0;
        lastNonBtPositionMs = 0;    // 重置非蓝牙进度累加起点 
        lastNonBtPositionTime = 0;
        // 切换控制器:旧源的广播歌词不再适用,清空标记
        if (cloudLyricFetcher != null) {
            cloudLyricFetcher.clearBroadcastLrc();
        }
        // 切换后的控制器在屏蔽列表:清空旧歌词缓存(含网络搜索结果),确保 UI 显示歌名/歌手
        if (isLyricBlocked(controller.getPackageName())) {
            clearCurrentLyrics();
        }
        // 切换控制器:旧源的通知权威数据不再适用,清空快照
        notifPkg = null;
        notifTitle = null;
        notifArtist = null;
        unregisterControllerCallback();
        mediaController = controller;
        controllerPkg = controller.getPackageName();
        // 修复：切到非 USB 控制器时强制复位 USB 路由。
        // 堵住"U 盘面板激活后切回 QQ（走 selectController 而非 onDeactivateUsbSource）、
        // usbRouteArmed 残留导致 QQ 歌词被 U 盘内嵌歌词抢走"的洞。
        if (usbRouteArmed && !UsbSourceController.SOURCE_PKG_USB.equals(controller.getPackageName())) {
            // (Bug#72C):切离 USB 时暂停 USB 播放器("上一个音源暂停")。
            // 之前只复位 usbRouteArmed,USB 播放器继续播 → 目标源+USB 双音源。
            // pause() 仅实际播放时生效(UsbMusicPlayer.pause 幂等安全),
            // PREPARING(切歌空窗)时置 PAUSED,不会误触发播放。
            if (usbSourceController != null) {
                try {
                    int usbSt = usbSourceController.getPlayState();
                    if (usbSt == UsbMusicPlayer.STATE_PLAYING || usbSt == UsbMusicPlayer.STATE_PREPARING) {
                        // (Bug#72E):用 pauseForSwitch(暂停+清焦点恢复标记)。
                        // 旧 pause() 不清 pausedByFocusLoss:若 USB 曾因焦点丢失而暂停
                        // (汽水打开抢焦点),之后 focus GAIN 会 handleAudioFocusChange 自动
                        // resume → USB 恢复播放 → onStateChanged 触发 re-arm → 按键被 USB
                        // 抢走("汽水在播,按切歌却切了 USB")。
                        usbSourceController.pauseForSwitch();
                        Log.i(TAG, "selectControllerInner: switched to non-USB controller, pause USB player (state=" + usbSt + ")");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "selectControllerInner: pause USB error: " + t.getMessage());
                }
            }
            usbRouteArmed = false;
            Log.i(TAG, "selectControllerInner: switched to non-USB controller, reset usbRouteArmed");
        }
        // 切换控制器时必须清除 ghostControllerPkg!
        // 否则 dispatchMediaAction() 中 targetPkg = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg
        // 会返回旧的 ghost 控制器(如酷狗),导致播放酷我/汽水时切歌控制了酷狗!
        if (ghostControllerPkg != null && !ghostControllerPkg.equals(controllerPkg)) {
            Log.i(TAG, "selectControllerInner: clearing ghostControllerPkg " + ghostControllerPkg
                    + " (switched to " + controllerPkg + ")");
            ghostControllerPkg = null;
        }
        // 酷我音源启动 AIDL 真实进度客户端(重开/切歌后 MediaSession position
        // 恒 0,靠 AIDL getCurrentPos(1) 拿与 UI 一致的实时位置)");非酷我立即停止。
        if (kuwoAidlClient != null) {
            if (isKuwoPkg(controllerPkg)) {
                kuwoAidlClient.start();
            } else {
                kuwoAidlClient.stop();
            }
        }
        // 酷狗音源启动封面 AIDL 客户端(controller 或 ghost 选中酷狗时)");
        // 非酷狗立即停止(避免常驻绑定拉起酷狗进程)。
        if (kugouCoverProvider != null) {
            if (PKG_KUGOU.equals(controllerPkg) || PKG_KUGOU.equals(ghostControllerPkg)) {
                kugouCoverProvider.start();
            } else {
                kugouCoverProvider.stop();
            }
        }
        MediaCenterBridge.setControllerPkg(controllerPkg);
        // 切到不同第三方音源时暂停"上一个在播控制器",避免双音源同播。
        // 必须在 applyControllerReceiverPolicyOnSwitch(会禁用旧控制器接收器)之前执行——
        // 概念版等 transportControls 空操作的靠 MEDIA_BUTTON 接收器暂停,那时接收器仍启用。
        pausePreviousSourceOnSwitch(prevController, prevPkg, newPkg);
        // 切源后重设第三方接收器策略——确保新控制器(概念版等)自带接收器被启用,
        // 否则屏蔽三键开启时它接收器是禁用态 → transportControls 空操作 → 按键没反应。
        applyControllerReceiverPolicyOnSwitch();
        // 切换控制器:蓝牙音源→禁用多媒体(防其暂停蓝牙)");
        // 离开蓝牙→解禁多媒体(恢复可用)。
        if (isBluetoothController()) {
            // 优先 MAY_DUCK 焦点持有方案(蓝牙焦点归 CarMedia,
            // XCMedia2 收 -3 不暂停,也不破坏通话 HFP);开关关闭时回退禁用旧方案。
            // 注意:这里不再发"播放蓝牙"广播——切到蓝牙控制器不等于蓝牙在播,
            // 发广播会主动拉起蓝牙,和正在播的 QQ 音乐等形成双音源;
            // 锚定交给 maybeKill 的"蓝牙真正播放"上升沿处理。
            // v2:蓝牙"未播放"时**不再** request MAY_DUCK 焦点——
            // 切蓝牙瞬间拿焦点会把正在播的 QQ 音乐 duck 压低音量(用户实测反馈)。
            // MAY_DUCK 改到 sendPlayPause 蓝牙 play 分支(真正播放时才拿)。
            if (!USE_BLUETOOTH_FOCUS_HOLD) {
                disableMultimedia();
            }
            // 切到蓝牙且蓝牙当前未播放时,抑制向 EAS 卡片/仪表盘推送
            // (蓝牙 MediaSession 常驻上一首歌元数据,立即推会把"旧歌 + 暂停态"送上卡片)。
            // 用户要求:点播放后才推送变化。蓝牙真正播放后 onPlaybackStateChanged 收到
            // PLAYING 会清除该标志并正常推送。
            PlaybackState curPs = controller.getPlaybackState();
            suppressBtPushUntilPlay =
                    (curPs == null || curPs.getState() != PlaybackState.STATE_PLAYING);
            // v3:切蓝牙未播放时,延迟触发一次 EAS 焦点仲裁把播放焦点交还
            // 给 XCMedia2——CarMedia 之前在非蓝牙控制器推送时 requestPlay 抢走了 EAS
            // 焦点,XCMedia2 的 bMediaCenterFocus=false,蓝牙一播放就被它 "no focus to
            // pause" 暂停(真车日志 bt (3).log 13:22:29.855 确认)。恢复机制:CarMedia
            // 以不抢焦点模式再调一次 updateMusicPlaybackState → MDC 自动 requestPlay
            // 把焦点给回 com.ecarx.multimedia → XCMedia2 抢回焦点 → 蓝牙播放不暂停。
            // 复用缓存内容(切蓝牙前通常为 QQ 的歌),不改变卡片显示。延迟 400ms 等
            // EAS/MDC 处理;用户点播放前若已完成仲裁,第一次播放即成功(极端快操作
            // 由 sendPlayPause 的延迟自愈兜底)。
            if (suppressBtPushUntilPlay) {
                firstPushHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MediaCenterBridge.get().arbitrateFocusToMultimedia();
                            // 广播完美版：焦点仲裁（交还 XCMedia2）后补发 play，让切蓝牙自动播。
                            consumeBtAutoPlayIfPending();
                        } catch (Throwable t) {
                            Log.w(TAG, "arbitrate focus on bt switch error: " + t.getMessage());
                        }
                    }
                }, 400L);
            }
        } else {
            suppressBtPushUntilPlay = false;
            abandonBluetoothFocus();
            enableMultimedia();
            // 离开蓝牙,恢复之前被暂停的其他音源(QQ 音乐等)。
            resumeOtherSourcesOnBluetoothStop();
        }
        controllerStale = false;
        try {
            controller.registerCallback(controllerCallback);
        } catch (Exception e) {
            Log.w(TAG, "registerCallback error: " + e.getMessage());
        }
        // 非 QQ 音乐时停止 QQ 歌词轮询(P4 完整实现,此处占位)
        if (!PKG_QQ_MUSIC.equals(controllerPkg)) {
            currentSourceType = SOURCE_TYPE_NONE;
            Log.i(TAG, "Switched controller, reset currentSourceType");
        }
        MediaMetadata meta = controller.getMetadata();
        if (meta != null) {
            handleMetadata(meta);
        } else if (PKG_QQ_MUSIC.equals(controllerPkg) && qqMusicFetcher != null) {
            // 重启/绑定场景:QQ 车机版 metadata 可能为空(媒体会话未恢复),
            // 但 QQ 正在播放--此时 handleMetadata 永不触发,QQ AIDL 永不绑定,
            // 歌词停留在停掉前那一句。主动绑定并拉取当前歌曲(AIDL 自带歌名/歌手/歌词)。
            Log.i(TAG, "selectControllerInner: QQ metadata null, proactive bind+fetch");
            currentSourceType = SOURCE_TYPE_QQ;
            if (!isLyricBlocked(controllerPkg)) {
                qqMusicFetcher.bind();
                qqMusicFetcher.fetchCurrentSong();
            }
            startLyricPoll();
        } else if (isDimPushEnabled()) {
            // 无 metadata 时兜底推源信息;同样受"自动仪表推送"开关控制
            pushSourceSwitch();
        }
        // 切源兜底:当前音源所属歌词抓取器若尚未归属当前歌,主动触发抓取,
        // 保证切到任意音源后接口状态灯能上行到 SUCCESS(绿)。归属已匹配则跳过(幂等)。
        ensureCurrentSourceLyricFetch();
        PlaybackState ps = controller.getPlaybackState();
        if (ps != null) {
            updatePlaybackStatus(ps);
        }
        // 切换控制器时复位歌名闪烁检测（避免上一音源的判定残留）
        resetTitleFlicker();
        // 控制器选中后立即刷一次桌面小卡片（不单靠 500ms 歌词轮询兜底），
        // 覆盖"开机/覆盖安装后控制器已选中但卡片仍停在初始化状态"的窗口。
        try {
            CarMediaWidget.updateAll(this);
            CarMediaSkyWidget.updateAll(this);
        } catch (Throwable t) {
            Log.w(TAG, "widget update after controller select error: " + t.getMessage());
        }
    }
    private void unregisterControllerCallback() {
        try {
            if (mediaController != null && controllerCallback != null) {
                mediaController.unregisterCallback(controllerCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "unregister controller callback error: " + e.getMessage());
        }
    }
    // ================= titleFlickerDetection（歌名闪烁检测，仅蓝牙） =================
 /**
 * 歌名闪烁检测（指引 §5.7 设计并实现）：
 * 部分手机音乐 App 经 AVRCP 把"歌词行"放进 TITLE 字段持续推送（歌名每 1~2 秒变一次）。
 * CarMedia 若把它当"切歌"处理 → 每行触发 XMF2 备用歌词接口 + CoverFallback 封面兜底，
 * 网络/主线程洪泛压垮车机 → "封面一直变 + 方控卡"（真车反馈）。
 * 判定规则（仅蓝牙音源，其他音源不做检测）：歌手不变 + 歌名在 TITLE_FLICKER_WINDOW_MS
 * 窗口内变化 ≥ TITLE_FLICKER_THRESHOLD 次 → titleFlickerLyricActive=true（音源自带歌词）。
 * 命中后由 handleMetadata 通用源分支 / extractArtworkAndPush 跳过 XMF2 与封面兜底。
 * 复位：歌手变化（真切歌）/ 切控制器 / 非蓝牙音源。
 */
    private void updateTitleFlickerDetection(String title, String artist, String album) {
        try {
            if (!isBluetoothController() || title == null || title.isEmpty() || "未知歌曲".equals(title)) {
                // 非蓝牙或无有效歌名：不检测并复位残留判定
                if (titleFlickerLyricActive || titleFlickerCount != 0 || lastFlickerTitle != null) {
                    resetTitleFlicker();
                }
                return;
            }
            String art = (artist != null) ? artist : "";
            long now = android.os.SystemClock.elapsedRealtime();
            long dur = playbackDuration; // v2:用 duration 判断真切歌
            // v2:真车 logcat 确认——AVRCP 把歌词/制作信息行塞进 TITLE 时,
            // artist 字段也随行变化("歌名-歌手"组合),旧逻辑"歌手变化=真切歌复位"导致
            // 永远不命中(XMF2/CoverFallback 每行疯狂执行,封面乱变)。改为 duration:
            // duration 相同=同一首歌 → 歌名变化累计;duration 变化=真切歌 → 复位重开窗。
            if (lastFlickerDuration != 0L && lastFlickerDuration != dur) {
                // 真切歌(duration 变化):复位并重新起窗
                resetTitleFlicker();
                titleFlickerArtist = art;
                lastFlickerDuration = dur;
                lastFlickerAlbum = album;
                titleFlickerWindowStart = now;
                lastFlickerTitle = title;
                titleFlickerBaselineTitle = title; // 新窗首样本(通常=真实歌名)
                return;
            }
            // (EAS 显示旧歌名根因):duration 相同但 album 变化 = 真切歌。
            // AVRCP 歌词行闪烁(title 每行变)时 album 稳定(如 log#9《雨爱》= "雨爱");
            // 真切歌即使两首 duration 碰巧相同(如《雨爱》与《SuNian》都 60000),album 也变化
            // (切到 SuNian → "Void")。仅靠 duration 判切歌在这种场景失效 → flickerStableTitle
            // 停在旧歌稳定名 → pushMetadataToCard 用旧 effTitle 去重 → EAS 小卡片停留旧歌名。
            // 修复:album 变化同样按真切歌复位,让 EAS 用 currentTitle(新歌)全量推送。
            if (lastFlickerDuration != 0L && lastFlickerAlbum != null
                    && album != null && !album.equals(lastFlickerAlbum)) {
                resetTitleFlicker();
                titleFlickerArtist = art;
                lastFlickerDuration = dur;
                lastFlickerAlbum = album;
                titleFlickerWindowStart = now;
                lastFlickerTitle = title;
                titleFlickerBaselineTitle = title;
                return;
            }
            if (lastFlickerDuration == 0L) {
                // 首个样本:记录基线并起窗
                titleFlickerArtist = art;
                lastFlickerDuration = dur;
                lastFlickerAlbum = album;
                titleFlickerWindowStart = now;
                lastFlickerTitle = title;
                titleFlickerBaselineTitle = title; // 新窗首样本(通常=真实歌名)
                return;
            }
            if (now - titleFlickerWindowStart > TITLE_FLICKER_WINDOW_MS) {
                // 窗口超时未达阈值：重开窗口继续观察（不复位已命中的 active）
                titleFlickerWindowStart = now;
                titleFlickerCount = 0;
                lastFlickerTitle = title;
                return;
            }
            // 同首歌(duration 未变)、窗口内：歌名与上次样本不同才计数（同歌名重复推送不计数）
            if (lastFlickerTitle == null || !lastFlickerTitle.equals(title)) {
                lastFlickerTitle = title;
                titleFlickerCount++;
                if (titleFlickerCount >= TITLE_FLICKER_THRESHOLD && !titleFlickerLyricActive) {
                    titleFlickerLyricActive = true;
                    // 记录 EAS 推送用稳定歌名/歌手——flicker 期间
                    // currentTitle 每行变化不能直接推 EAS;基线=窗口首样本
                    // (切歌后 AVRCP 先推真实歌名再滚歌词行,通常即真实歌名)。
                    flickerStableTitle = titleFlickerBaselineTitle;
                    flickerStableArtist = titleFlickerArtist;
                    Log.i(TAG, "titleFlickerDetection: 蓝牙 TITLE 持续变化(疑似歌词当歌名)"
                            + ", title=" + title + " artist=" + art
                            + " -> skip XMF2/CoverFallback");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateTitleFlickerDetection error: " + t.getMessage());
        }
    }

 /** 复位歌名闪烁检测全部状态。 */
    private void resetTitleFlicker() {
        titleFlickerWindowStart = 0L;
        titleFlickerCount = 0;
        titleFlickerArtist = null;
        lastFlickerTitle = null;
        lastFlickerDuration = 0L; // v2
        lastFlickerAlbum = null;  // 
        titleFlickerLyricActive = false;
        titleFlickerBaselineTitle = null; // 
        flickerStableTitle = null;        // 
        flickerStableArtist = null;       // 
    }

 /**
 * 蓝牙歌词闪烁判定当前是否命中（供 MainActivity.buildMetaSig 使用——
 * 命中期间歌名/歌手随歌词滚动变化,不能参与全量推送签名,否则每秒全量推送卡死 UI）。
 */
    public boolean isTitleFlickerLyricActive() {
        return titleFlickerLyricActive;
    }

    // ================= P2: 元数据处理 =================
    private void handleMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            return;
        }
        // 元数据来源校验!只接受当前控制器(或 ghost 控制器)的元数据。
        // 场景:用户选酷狗→点播放→酷狗 MediaSession 未 active→mediaController 可能被
        // 其他回调(如 OnActiveSessionsChanged)临时设置为汽水/酷我→handleMetadata 被
        // 汽水/酷我的元数据触发→currentTitle 被覆盖成汽水/酷我的歌名→UI 显示错误。
        // 修复:检查 mediaController.getPackageName(),如果不是 controllerPkg 或
        // ghostControllerPkg,就忽略这条元数据。
        String metadataSourcePkg = (mediaController != null) ? mediaController.getPackageName() : null;
        String authoritativePkg = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
        // USB 残留例外——controllerPkg 残留 USB(USB 播放分流 :4134 改过,拔出后
        // 无恢复路径)但 USB 实际未播放(IDLE,已拔/未选歌)时,不拦截真实来源(蓝牙)的
        // metadata,否则蓝牙页面永远显示 USB 旧歌/歌手(用户:插 USB 后蓝牙显示 USB 歌,拔了仍如此)。
        boolean usbResidue = UsbSourceController.SOURCE_PKG_USB.equals(authoritativePkg)
                && (usbSourceController == null
                    || usbSourceController.getPlayState() == UsbMusicPlayer.STATE_IDLE);
        // ★ (USB 歌词归属被外部源污染):USB 路由已武装且播放器已选中歌曲
        // (非 IDLE,含 PLAYING/PREPARING/PAUSED)时,忽略所有非 USB 来源的 metadata 回调。
        // 竞态场景:USB 在播/准备(usbRouteArmed=true),用户又播汽水/QQ,其 metadata 回调
        // 通过下方校验 → handleMetadata 把 currentSourceType 覆盖成 SODA/QQ →
        // getFullLyricLines/getLyricLine 走外部源分支归属失败 → 桌面小组件歌词/歌名
        // 永远空白不更新(日志确认 "getFullLyricLines: SODA ownership fail songTitle=
        // '你为什么和他发生关系了' currentTitle='0662.蓝心羽 - 樱木町.')。
        // 不用 isUsbPlaying()(仅 PLAYING):USB 扫描/PREPARING 窗口内 controllerPkg
        // 仍是旧源,外部 metadata 同样会覆盖 sourceType,故放宽到"非 IDLE"。
        boolean usbHasSelection = usbRouteArmed && usbSourceController != null
                && usbSourceController.getPlayState() != UsbMusicPlayer.STATE_IDLE;
        if (usbHasSelection && metadataSourcePkg != null
                && !UsbSourceController.SOURCE_PKG_USB.equals(metadataSourcePkg)) {
            Log.w(TAG, "handleMetadata: USB route armed & playing, ignore external metadata from "
                    + metadataSourcePkg + " (keep USB sourceType)");
            return;
        }
        if (metadataSourcePkg != null && authoritativePkg != null
                && !metadataSourcePkg.equals(authoritativePkg) && !usbResidue) {
            Log.w(TAG, "handleMetadata: ignore metadata from " + metadataSourcePkg
                    + " (authoritative controller is " + authoritativePkg
                    + ", controllerPkg=" + controllerPkg
                    + ", ghostPkg=" + ghostControllerPkg + ")");
            return;
        }
        CharSequence titleCs = metadata.getText(METADATA_TITLE);
        CharSequence artistCs = metadata.getText(METADATA_ARTIST);
        // 歌名来源单一(metadata/歌词):通知标题只做确认(见 applyNotificationMeta 守卫),
        // 不在这里用 notifTitle 覆盖 metadata 歌名,避免切歌瞬间通知快照未更新导致标题卡旧歌
        boolean notifActive = notifActive();
        String newTitleRaw = titleCs != null ? titleCs.toString() : "";
        if (newTitleRaw == null || newTitleRaw.isEmpty()) {
            newTitleRaw = "未知歌曲";
        }
        currentTitle = newTitleRaw;
        String newArtist = artistCs != null ? artistCs.toString() : null;
        if (newArtist != null) {
            // 数据清洗(与 onCloudLyricReady 一致):
            // 1) 反斜杠转正斜杠,避免控制字符导致 UI 显示异常
            // 2) 移除所有控制字符(含 \r \n \t \0 等)--网易云 IoT 多歌手数据
            // 偶发携带换行/回车分隔,TextView 换行后会被卡片高度裁切,表现为
            // "歌手显示不全";清洗后始终单行完整显示
            newArtist = newArtist.replace('\\', '/');
            StringBuilder sb = new StringBuilder(newArtist.length());
            for (int i = 0; i < newArtist.length(); i++) {
                char c = newArtist.charAt(i);
                if (c >= 32 && c != 127) {
                    sb.append(c);
                }
            }
            newArtist = sb.toString();
        }
        if (newArtist != null && !newArtist.isEmpty()) {
            // 仅在拿到有效歌手时覆盖;切歌瞬间网易云 IoT 偶发 artist 缺失(null/空),
            // 此时保留旧值,避免 UI 副信息出现"歌名 - "(歌手空白)。
            // 下一条完整 metadata(或 P4 歌词回调)会覆盖为正确值。
            // 通知栏优先:通知权威数据有效时,任何来源都不得覆盖歌手;
            // 否则 QQ 场景若 onQqLyricReady 已从 LRC 提取到完整多歌手(含 & 等),
            // 不用 QQ metadata 的主歌手覆盖(避免竞态冲掉"姜添/听潮阁")。
            if (!notifActive && !(PKG_QQ_MUSIC.equals(controllerPkg) && qqArtistComplete)) {
                currentArtist = newArtist;
            }
        }
        playbackDuration = metadata.getLong(METADATA_DURATION);
        // 歌名闪烁检测（仅蓝牙）——音源把歌词行当歌名持续推时，
        // 每行都会让 songChanged=true 触发 XMF2/封面兜底，先判定再走歌词分支。
        // 传入 album——作为"真切歌"的补充判据(duration 相同但 album 变化)。
        String metaAlbum = null;
        try {
            CharSequence albumCs = metadata.getText(METADATA_ALBUM);
            metaAlbum = albumCs != null ? albumCs.toString() : null;
        } catch (Throwable ignored) {
        }
        updateTitleFlickerDetection(currentTitle, currentArtist, metaAlbum);
        Log.i(TAG, "handleMetadata: title=" + currentTitle + " artist=" + currentArtist
                + " duration=" + playbackDuration
                + " controllerPkg=" + controllerPkg
                + " mediaControllerPkg=" + (mediaController != null ? mediaController.getPackageName() : "null")
                + " ghostPkg=" + ghostControllerPkg);
        // 封面提取(每次 metadata 变化都尝试:切歌/封面变化时刷新小卡片封面)
        extractArtworkAndPush(metadata);
        // 切歌/切音源检测:与上一首不同(歌名/歌手变化,或音源包名变化)才重置 API 状态灯
        // (handleMetadata 每秒重复触发,同歌同音源不重置,避免打断进行中的调用)
        // fix: 同时检查 controllerPkg 变化，避免 ghostControllerPkg 残留导致 sourceChanged 误判
        boolean sourceChanged = (authoritativePkg != null && !authoritativePkg.equals(lastMetadataPkg))
                || (controllerPkg != null && !controllerPkg.equals(lastMetadataControllerPkg));
        boolean songChanged = !currentTitle.equals(lastMetadataTitle)
                || (currentArtist != null && !currentArtist.equals(lastMetadataArtist));
        if (songChanged || sourceChanged) {
            lastMetadataTitle = currentTitle;
            lastMetadataArtist = currentArtist;
            lastMetadataPkg = authoritativePkg;
            lastMetadataControllerPkg = controllerPkg;
            // 自然切歌(播完自动下一首/手动切歌)时重置进度累加器。
            // 旧歌播完 position 已累加/冻结到旧歌末尾(≈旧 duration),而 playbackDuration
            // 刚被换成新歌时长 → getPlaybackPosition() 的 "result > duration → clamp
            // duration" 会直接输出新歌 duration → 新歌进度条满格 100%(用户实测)。
            // 与 Bug#44 playbackDuration=0、Bug#43 暂停/切控制器/ghost 重置累加器对齐:
            // 切歌即清零,等新歌 PlaybackState 到位后重新 init,进度条从 0 起步。
            // 排除蓝牙歌词闪烁期(titleFlickerLyricActive:音源把歌词行当歌名推,每行
            // songChanged=true)——闪烁期重置会让进度条每行跳回 0。
            if (songChanged && !titleFlickerLyricActive) {
                lastNonBtPositionMs = 0;
                lastNonBtPositionTime = 0;
                lastBtPositionMs = 0;
                lastBtPositionTime = 0;
                frozenPlaybackPosition = 0;
                Log.d(TAG, "handleMetadata: song changed, reset position accumulators (title="
                        + currentTitle + ")");
            }
            if (cloudLyricFetcher != null) {
                cloudLyricFetcher.resetApiStatus();
            }
            if (qqMusicFetcher != null) {
                qqMusicFetcher.resetApiStatus();
            }
            if (sodaMusicFetcher != null) {
                sodaMusicFetcher.resetApiStatus();
            }
            if (kuwoLyricFetcher != null) {
                kuwoLyricFetcher.resetApiStatus();
            }
            if (kugouLyricFetcher != null) {
                kugouLyricFetcher.resetApiStatus();
            }
        }
        // 修复:歌词抓取器归属不一致 → 强制重新搜索。
        // 场景:切 USB 播放时 triggerUsbLyricFetch 的云歌词兜底把 cloudLyricFetcher
        // 占用为 USB 歌名(或 embedded 空时被 clearLoadedLrc 清空)");切回落雪等
        // 通用源且恰好是同一首(songChanged=false)时不触发搜索 → fetcher 残留 USB 歌名
        // → getFullLyricLines/getLyricLine 归属失败 → 歌词空白(用户实测"切回没歌词")。
        boolean lyricStale = cloudLyricFetcher != null
                && !lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle);
        boolean sodaLyricStale = sodaMusicFetcher != null
                && !lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle);
        boolean qqLyricStale = qqMusicFetcher != null
                && !lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle);
        // 酷我/酷狗专属歌词抓取器归属不一致(切 USB 后切回同歌)时强制重新搜索
        boolean kuwoLyricStale = kuwoLyricFetcher != null
                && !lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle);
        boolean kugouLyricStale = kugouLyricFetcher != null
                && !lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle);
        if (PKG_QQ_MUSIC.equals(controllerPkg)) {
            currentSourceType = SOURCE_TYPE_QQ;
            if (isLyricBlocked(controllerPkg)) {
                // 屏蔽列表命中:不调用 QQ AIDL / XMF2 任何歌词接口,UI 显示歌名/歌手
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip QQ lyric fetch");
                startLyricPoll();
            } else if (qqMusicFetcher != null) {
                qqMusicFetcher.bind();
                // 仅切歌时重新拉取,同一首歌重复 metadata 回调不打断 AIDL 歌词推送。
                // 抓取器归属不一致(切 USB 后切回同歌)时也强制拉取。
                if (songChanged || sourceChanged || qqLyricStale) {
                    qqMusicFetcher.fetchCurrentSong();
                }
                startLyricPoll();
            }
        } else if (PKG_NETEASE_IOT.equals(controllerPkg) || PKG_NETEASE.equals(controllerPkg)) {
            currentSourceType = SOURCE_TYPE_NETEASE;
            if (isLyricBlocked(controllerPkg)) {
                // 屏蔽列表命中:不调用网易云搜索 / XMF2 兜底,UI 显示歌名/歌手
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip netease search");
                startLyricPoll();
            } else if (cloudLyricFetcher != null) {
                // P4:搜索补全歌手+抓歌词;仅切歌时触发,同一首歌重复回调不重新获取。
                // 抓取器归属不一致(切 USB 后切回同歌)时也强制搜索。
                if (songChanged || sourceChanged || lyricStale) {
                    cloudLyricFetcher.search(currentTitle, currentArtist);
                }
                startLyricPoll();
            }
        } else if (isSodaPkg(controllerPkg)) {
            currentSourceType = SOURCE_TYPE_SODA;
            // 优先从 metadata 直接读 track_id（汽水搜索接口对 app 进程返回"无歌词降级候选"，
            // 只有绕过搜索、拿到真实 track_id 才能命中 seo_track 歌词）。MEDIA_ID 通常即 track_id。
            String sodaTrackId = null;
            try {
                String mid = metadata.getString("android.media.metadata.MEDIA_ID");
                if (mid != null && !mid.isEmpty() && mid.matches("[0-9]{10,}")) {
                    sodaTrackId = mid;
                } else if (mid != null) {
                    Log.d(TAG, "handleMetadata: soda MEDIA_ID not numeric: " + mid);
                }
            } catch (Throwable t) {
                Log.w(TAG, "handleMetadata: soda MEDIA_ID read fail: " + t.getMessage());
            }
            if (sodaTrackId != null) {
                Log.i(TAG, "handleMetadata: soda MEDIA_ID=" + sodaTrackId + " fetchByTrackId bypass search");
            }
            if (isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip soda search");
                startLyricPoll();
            } else if (sodaMusicFetcher != null) {
                // 仅切歌时触发歌词获取,同一首歌重复 metadata 回调不重新 fetch(避免打断已加载歌词)。
                // 抓取器归属不一致(切 USB 后切回同歌)时也强制获取。
                if (songChanged || sourceChanged || sodaLyricStale) {
                    if (sodaTrackId != null) {
                        sodaMusicFetcher.fetchByTrackId(sodaTrackId, currentTitle, currentArtist);
                    } else {
                        sodaMusicFetcher.search(currentTitle, currentArtist);
                    }
                }
                startLyricPoll();
            }
        } else if (isKuwoPkg(controllerPkg)) {
            // 酷我音源——专属歌词抓取器(酷我官方 r.s 搜索 + songinfoandlrc 歌词),
            // 失败时回调回退 XMF2。metadata 自带歌名/歌手,不走网易云搜索。
            currentSourceType = SOURCE_TYPE_KUWO;
            if (titleFlickerLyricActive) {
                Log.i(TAG, "handleMetadata: titleFlickerLyricActive, skip kuwo lyric (bt built-in lyrics)");
                startLyricPoll();
            } else if (isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip kuwo lyric");
                startLyricPoll();
            } else if (kuwoLyricFetcher != null) {
                if (songChanged || sourceChanged || kuwoLyricStale) {
                    kuwoLyricFetcher.search(currentTitle, currentArtist);
                }
                startLyricPoll();
            }
        } else if (PKG_KUGOU.equals(controllerPkg)) {
            // 酷狗音源——专属歌词抓取器(酷狗官方 mobilecdn 搜索 + lyrics.kugou.com 歌词),
            // 失败时回调回退 XMF2。metadata 自带歌名/歌手,不走网易云搜索。
            currentSourceType = SOURCE_TYPE_KUGOU;
            if (titleFlickerLyricActive) {
                Log.i(TAG, "handleMetadata: titleFlickerLyricActive, skip kugou lyric (bt built-in lyrics)");
                startLyricPoll();
            } else if (isLyricBlocked(controllerPkg)) {
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip kugou lyric");
                startLyricPoll();
            } else if (kugouLyricFetcher != null) {
                if (songChanged || sourceChanged || kugouLyricStale) {
                    kugouLyricFetcher.search(currentTitle, currentArtist);
                }
                startLyricPoll();
            }
        } else {
            // 通用源(汽水音乐等非 QQ/网易云控制器):metadata 自带歌名/歌手,
            // 直接查通用歌词接口 XMF2(不走网易云搜索),灯只亮 XMF2。
            currentSourceType = SOURCE_TYPE_GENERIC;
            if (titleFlickerLyricActive) {
                // 蓝牙音源自带歌词（AVRCP 把歌词行放进 TITLE，titleFlickerDetection 判定）：
                // 跳过 XMF2 备用歌词接口，避免每行歌词触发一次网络搜索洪泛
                Log.i(TAG, "handleMetadata: titleFlickerLyricActive, skip xmf2 (bt built-in lyrics)");
                startLyricPoll();
            } else if (isLyricBlocked(controllerPkg)) {
                // 屏蔽列表命中:不调用 XMF2,UI 显示歌名/歌手
                Log.i(TAG, "handleMetadata: lyric blocked for " + controllerPkg + ", skip xmf2");
                startLyricPoll();
            } else if (cloudLyricFetcher != null) {
                // 仅切歌时触发 XMF2 搜索,同一首歌重复回调不重新获取。
                // 抓取器归属不一致(切 USB 后切回同歌,如落雪《会长大的幸福》)
                // 时也强制搜索——否则 fetcher 残留 USB 歌名,归属失败 → 无歌词。
                if (songChanged || sourceChanged || lyricStale) {
                    cloudLyricFetcher.searchXmf2Only(currentTitle, currentArtist);
                }
                startLyricPoll();
            }
        }
        // 切歌时推送元数据到小卡片,受"自动仪表推送"开关控制(原版 smali L1395-1399)
        // 蓝牙歌词闪烁期不推——歌词行每行变化会触发 EAS 卡片/仪表盘洪泛,
        // 方控卡的另一半根因;真切歌(歌手变化)时 flicker 已复位,恢复推送。
        // 放开 flicker 门——pushMetadataToCard 内部已用稳定歌名
        // (flickerStableTitle/Artist)去重:flicker 期间每行 metadata 只推进度,
        // 命中瞬间立刻把卡片从"歌词行"刷成稳定歌名,不再整段停推。
        if (isDimPushEnabled()) {
            pushMetadataToCard();
        }
    }
 /**
 * 从音源 metadata 提取封面并推给 EAS(小卡片/仪表盘封面)。
 *
 * 取值优先级(与 MediaBridge 同款):
 * 1. ART_URI / ALBUM_ART_URI:http(s) 直传(小卡片自行加载)");content/file 落盘后走 content URI
 * 2. ART / ALBUM_ART bitmap:落盘后走 content URI
 * 提取成功 → currentArtworkUri + bridge.setArtworkUri;失败/无封面 → null(清空旧封面)。
 * 注意:酷狗等部分音源 metadata 无任何封面字段(dumpsys 实测 size=7 无 ART),
 * 此时拿不到封面是数据源问题,非本链路问题。
 */
    private void extractArtworkAndPush(MediaMetadata metadata) {
        try {
            String uriStr = metadata.getString("android.media.metadata.ART_URI");
            if (uriStr == null) {
                uriStr = metadata.getString("android.media.metadata.ALBUM_ART_URI");
            }
            Uri artworkUri = null;
            if (uriStr == null) {
                Bitmap bmp = metadata.getBitmap("android.media.metadata.ART");
                if (bmp == null) {
                    bmp = metadata.getBitmap("android.media.metadata.ALBUM_ART");
                }
                if (bmp != null) {
                    artworkUri = ArtworkHelper.saveArtwork(this, bmp);
                }
            } else {
                String lower = uriStr.toLowerCase();
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    // 网络图直传:小卡片支持则自行加载(MediaBridge 同款,避免主线程下载)
                    artworkUri = Uri.parse(uriStr);
                } else {
                    // content/file URI:读 bitmap 后落盘,转成我们自己的 content URI
                    Bitmap bmp = ArtworkHelper.loadBitmapFromUri(this, Uri.parse(uriStr));
                    if (bmp != null) {
                        artworkUri = ArtworkHelper.saveArtwork(this, bmp);
                    }
                }
            }
            // 封面保底(所有 metadata 音源通用:网易云/QQ/酷我/酷狗/蓝牙):
            // 同歌 metadata 抖动(提取到 null 但歌未变)时保留旧封面,
            // 避免切歌瞬间缺 ART 的中间态把已显示封面清空(闪没)。
            // 真实切歌(歌名/歌手变化)才清空,等新歌封面或 CoverFallback。
            String songKey = (currentTitle == null ? "" : currentTitle) + "|"
                    + (currentArtist == null ? "" : currentArtist);
            if (artworkUri != null) {
                currentArtworkUri = artworkUri.toString();
                coverSongKey = songKey;
            } else if (songKey.equals(coverSongKey)) {
                Log.d(TAG, "extractArtworkAndPush: same song metadata flicker, keep previous artwork");
            } else {
                currentArtworkUri = null;
                coverSongKey = songKey;
            }
            MediaCenterBridge.get().setArtworkUri(currentArtworkUri);
            Log.i(TAG, "extractArtworkAndPush: uri=" + currentArtworkUri);
            // 封面延迟重试 :
            // 音源(QQ/酷我等)常先推 title/artist,ART 位图异步加载完才更新自身 metadata;
            // CarMedia 拿到的第一份 metadata 没有 ART → 推 null → 小卡片/仪表盘首次无封面,
            // 重新播放(触发新的 metadata 回调)才出现封面。
            // 这里提取到 null 时延迟 1.5s 从当前控制器重新 getMetadata() 再提取一次(限 3 次),
            // 拿到封面后由 checkContentChangedAndPush 的 artwork 去重维度触发全量推送。
            if (currentArtworkUri != null) {
                artworkRetryCount = 0;
            } else {
                // ★移植 MediaBridge：CoverFallback 封面兜底（网易云/QQ 搜索 + 下载落盘）。
                // 仅当元数据已带标题（离线/蓝牙无标题不搜），且不重复请求同一首歌。
                String fbTitle = currentTitle;
                String fbArtist = currentArtist;
                // 蓝牙歌词闪烁期（titleFlickerLyricActive）跳过封面兜底——
                // 每行歌词都是新 key，若照搜会每行下载一张随机封面（封面一直变 + 网络洪泛）
                if (fbTitle != null && !fbTitle.isEmpty() && !"未知歌曲".equals(fbTitle)
                        && !titleFlickerLyricActive) {
                    // 酷狗音源优先走官方 AIDL 封面(同源 URL,命中率最高,
                    // 无网络搜索随机图问题)");AIDL 不可用/被防抖跳过时回退 CoverFallback。
                    if (PKG_KUGOU.equals(controllerPkg) && kugouCoverProvider != null) {
                        String cachedUrl = KugouCoverProvider.getCachedUrl(fbTitle, fbArtist);
                        if (cachedUrl != null) {
                            // 已下载过的封面直接注入本地 content URI(WebView 可加载)");
                            // 未下载则走 onKugouCoverResolved 后台下载转 content URI(幂等,映射复用)
                            String localArt = mKugouLocalArt.get(cachedUrl);
                            if (localArt != null) {
                                MediaCenterBridge.get().setArtworkUri(localArt);
                                currentArtworkUri = toWebArtworkUri(localArt);
                                Log.i(TAG, "kugou cover cache hit: " + currentArtworkUri);
                            } else {
                                onKugouCoverResolved(cachedUrl);
                            }
                        } else {
                            boolean accepted = kugouCoverProvider.request(fbTitle, fbArtist,
                                    new KugouCoverProvider.CoverCallback() {
                                        @Override
                                        public void onResolved(String url) {
                                            onKugouCoverResolved(url);
                                        }
                                    });
                            if (!accepted) {
                                // AIDL 冷却中/单飞中/酷狗未装 → 回退在线搜图兜底
                                CoverFallback.request(this, fbTitle, fbArtist, new CoverFallback.ResolveCallback() {
                                    @Override
                                    public void onResolved(Uri uri) {
                                        onCoverFallbackResolved(uri);
                                    }
                                });
                            }
                        }
                    } else {
                        CoverFallback.request(this, fbTitle, fbArtist, new CoverFallback.ResolveCallback() {
                            @Override
                            public void onResolved(Uri uri) {
                                onCoverFallbackResolved(uri);
                            }
                        });
                    }
                }
            }
            if (currentArtworkUri != null) {
                artworkRetryCount = 0;
            } else if (!artworkRetryScheduled && artworkRetryCount < 3) {
                artworkRetryCount++;
                artworkRetryScheduled = true;
                firstPushHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        artworkRetryScheduled = false;
                        try {
                            MediaMetadata cur = (mediaController != null)
                                    ? mediaController.getMetadata() : null;
                            if (cur != null) {
                                extractArtworkAndPush(cur);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "artwork retry error: " + t.getMessage());
                        }
                    }
                }, 1500L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "extractArtworkAndPush failed: " + t.getMessage());
        }
    }

 /**
 * USB 封面保底(通用):异步补读完成前保留旧封面,不闪没。
 * prevUri=清空前旧封面;宽限到期时若 currentArtworkUri 仍是 prevUri
 * (未被 ensureUsbCoverAsync 异步替换)→ 清空(该歌无封面,防错封面残留)。
 * 期间切歌/再次 miss 会重新调度,旧 grace 自动取消,只保留最新一次。
 */
    private void scheduleUsbCoverGrace(final String prevUri) {
        if (usbCoverGraceRunnable != null) {
            firstPushHandler.removeCallbacks(usbCoverGraceRunnable);
        }
        usbCoverGraceRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (currentArtworkUri != null && currentArtworkUri.equals(prevUri)) {
                        currentArtworkUri = null;
                        MediaCenterBridge.get().setArtworkUri(null);
                        Log.d(TAG, "usb cover grace expired (no art yet), clear stale artwork");
                        if (isDimPushEnabled()) pushMetadataToCard();
                        if (MainActivity.sInstance != null) {
                            MainActivity.sInstance.pushAllPublic();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "usb cover grace error: " + t.getMessage());
                }
            }
        };
        firstPushHandler.postDelayed(usbCoverGraceRunnable, USB_COVER_GRACE_MS);
    }
    private void updatePlaybackStatus(PlaybackState state) {
        // 播放状态以 controller PlaybackState 为权威(实时同步,MediaController 直接回调),
        // 不要被通知状态覆盖--通知 actions 可能滞后/不更新,导致"通知栏暂停但这里显示播放"。
        lastPlaybackState = state;
        // seek/切歌后 onPlaybackStateChanged 回调携带新 position,必须同步更新冻结进度,
        // 否则后续 getPlaybackState() 短暂返回 null 时会退回旧 frozenPlaybackPosition,
        // 表现为"拖动进度条后不同步"。蓝牙 AVRCP seek 后尤其明显。
        if (state != null) {
            long newPos = state.getPosition();
            boolean isBt = isBluetoothController();
            int s = state.getState();
            boolean playing = (s == PlaybackState.STATE_PLAYING);
            // 计算基于 PlaybackState 的实时位置(播放中 = position + (now - lastUpdateTime))
            long now = android.os.SystemClock.elapsedRealtime();
            long realPos = newPos;
            long updateTime = state.getLastPositionUpdateTime();
            if (playing && updateTime > 0 && newPos >= 0) {
                realPos = newPos + (now - updateTime);
            }
            // 蓝牙 AVRCP 在状态切换(暂停→播放/seek)时可能短暂返回 position=0,
            // 此时不能用 0 覆盖已有进度,否则进度条"从0开始"。
            boolean shouldUpdate = !(isBt && newPos == 0 && frozenPlaybackPosition > 0);
            if (shouldUpdate) {
                frozenPlaybackPosition = playing ? realPos : newPos;
            }
            // 蓝牙播放时:seek 触发 onPlaybackStateChanged 回调,用实时位置校准累加起点
            // (差距>500ms 才校准,避免陈旧值把进度条拉回去)
            if (isBt && playing && newPos >= 0 && lastBtPositionTime > 0) {
                long estimated = lastBtPositionMs + (now - lastBtPositionTime);
                if (Math.abs(realPos - estimated) > 500) {
                    lastBtPositionMs = realPos;
                    lastBtPositionTime = now;
                    Log.d(TAG, "bt calibrate in callback: realPos=" + realPos
                            + " base=" + newPos + " estimated=" + estimated
                            + " diff=" + Math.abs(realPos - estimated));
                }
            }
            // 蓝牙播放时,如果累加起点未初始化但有 frozen 值,用 frozen 作为累加起点
            // (暂停→播放后 AVRCP 还没返回有效 position 时,从暂停位置继续累加)
            if (isBt && playing && lastBtPositionTime == 0 && frozenPlaybackPosition > 0) {
                lastBtPositionMs = frozenPlaybackPosition;
                lastBtPositionTime = now;
                Log.d(TAG, "bt resume: init accumulator from frozen=" + frozenPlaybackPosition);
            }
        }
        Log.i(TAG, ">>> playback state=" + (state != null ? state.getState() : -1)
                + " pos=" + (state != null ? state.getPosition() : -1)
                + " frozen=" + frozenPlaybackPosition);
        // 播放状态变化也推送(内容变化驱动:歌名/歌词/状态任一变化都推)。
        // pushMetadataToCard 内部有 stateChanged 去重,只有真变化才全量推送,
        // 状态未变只推进度,不会刷屏。
        if (isDimPushEnabled()) {
            pushMetadataToCard();
        }
    }
 /** 推送元数据到 EAS 小卡片(对齐 MediaBridge 语义 + 去重)。 */
    private void pushMetadataToCard() {
        try {
            // 蓝牙歌词闪烁期不再整方法 return——改为在下方用稳定歌名
            // (flickerStableTitle/Artist)参与去重与推送:flicker 期间 currentTitle
            // 每行变化会让去重恒真(每行推仪表盘→EAS 洪泛→方控卡),用稳定值后
            // trackChanged=false → 只推 2s 进度;播放/暂停状态变化照常全量推(标题稳定)");
            // 真切歌(duration 变化)复位后恢复真实推送。
            // 总开关:未勾选"自动仪表盘/小卡片推送"一律不推(防调用方漏判)
            if (!isDimPushEnabled()) {
                return;
            }
            // 切换控制器到蓝牙且蓝牙未播放时,抑制推送——
            // 蓝牙 MediaSession 常驻上一首歌元数据,切过去立即推会把"旧歌+暂停态"送上卡片。
            // 用户要求:点播放后才推送变化。蓝牙真正播放后标志清除。
            if (suppressBtPushUntilPlay) {
                Log.d(TAG, "pushMetadataToCard: bt pending play, skip card push");
                return;
            }
            // ================= CarPlay 分流=================
            // CarPlay 是纯媒体键注入、无法监听盒子音乐(盒子不提供会话/元数据)——
            // 选中后若沿用 currentTitle/currentArtist 缓存,EAS 小卡片/仪表盘会
            // 残留旧源(网易云/汽水)的歌名、播放态与封面。直接推固定来源说明
            // "CarPlay 手机音乐"并清空封面;仅当盒子会话成为当前控制器
            // (controllerPkg==CarPlay,真实元数据接管)时才走下方外部逻辑。
            // 合并模式(合并且盒子会话存在)不推固定文案——盒子 MediaSession
            // 携带真实歌曲信息,由下方外部逻辑正常推送歌名/封面。
            if (carplayBoxSelected && !isCarplayBoxMergedActive()
                    && !PKG_CARPLAY_BOX.equals(controllerPkg)) {
                stopPositionTicker();
                boolean alreadyCarPlay = "CarPlay 手机音乐".equals(lastPushedTitle)
                        && lastPushedPlayState == EAS_STATUS_PAUSED && !firstPushPending;
                if (alreadyCarPlay) {
                    return; // 已推过 CarPlay 固定信息,避免旧源事件反复触发 EAS 洪泛
                }
                MediaCenterBridge.get().setCollectEnabled(false);
                MediaCenterBridge.get().setFullLyric("", "");
                MediaCenterBridge.get().setArtworkUri(null);
                boolean cpDelivered = MediaCenterBridge.get().pushMetadata(
                        "CarPlay 手机音乐", "", null, 0L,
                        EAS_STATUS_PAUSED, true, PKG_CARPLAY_BOX, 0L);
                if (cpDelivered) {
                    lastPushedTitle = "CarPlay 手机音乐";
                    lastPushedArtist = "";
                    lastPushedArtworkUri = null;
                    lastPushedPlayState = EAS_STATUS_PAUSED;
                    firstPushPending = false;
                }
                Log.d(TAG, "pushMetadataToCard: CarPlay selected, push fixed source info");
                return;
            }
            // ================= USB 音源分流=================
            // USB 激活时 mediaController 可能为 null（updateAvailableControllers 跳过了外部扫描），
            // 直接从 usbSourceController 获取状态推送 EAS，不依赖外部 MediaController。
            if (usbRouteArmed && usbSourceController != null) {
                UsbMusicItem usbItem = usbSourceController.getCurrentItem();
                int usbState = usbSourceController.getPlayState();
                long usbPos = usbSourceController.getPosition();
                // 修复：仅 USB 真在播放时才允许 USB 分流覆盖控制器/元数据。
                // U 盘激活但未播放（无选中歌曲/暂停）时落回外部控制器逻辑——
                // 堵住"从 U 盘面板切回 QQ 未调 onDeactivateUsbSource，usbRouteArmed 残留，
                // 导致 QQ 歌词被 U 盘内嵌歌词（无 LRC→两行/备用歌词）抢走"的洞。
                // ★ (Bug: USB 暂停 EAS 仍显示播放)：原条件只认 PLAYING/PREPARING，
                // 用户暂停(setState=3 PAUSED)时落回外部逻辑，而外部控制器是 USB 虚拟包(null)，
                // 推不出暂停态 → EAS 小卡片永远停在"播放中"。修复：USB 暂停且当前控制器仍是
                // USB(用户未切走)时，也走 USB 分支推送 EAS_STATUS_PAUSED。仅当 controllerPkg
                // 已切到其他源(QQ/汽水)才落回外部，保住原"防 USB 残留抢占"语义。
                if (usbItem != null && (usbState == UsbMusicPlayer.STATE_PLAYING
                        || usbState == UsbMusicPlayer.STATE_PREPARING
                        || (usbState == UsbMusicPlayer.STATE_PAUSED
                            && UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)))) {
                    // 放宽到 PREPARING——切歌瞬间(AudioTrack 空窗)也推 USB
                    // 歌曲信息,避免仪表盘/小卡片闪蓝牙旧歌;PREPARING 映射为暂停态。
                    // 更新当前元数据（供 buildLyricPushPair / 去重判断使用）
                    currentTitle = usbItem.title != null ? usbItem.title : "未知歌曲";
                    currentArtist = usbItem.artist != null ? usbItem.artist : "未知歌手";
                    // USB 嵌入封面：仅查缓存文件(无解码,快)");缺失时后台按需补读单首封面。
                    // 扫描提速:File 兜底扫描不再预先解码全部歌曲封面,这里补。
                    // USB 封面保底:缓存 miss 保留旧封面,异步读完成替换;到期清空。
                    // ★ (EAS/DIM USB 无封面根因):原实现只更新 currentArtworkUri
                    // (主界面晴空主题读它 → 有封面),但漏调 MediaCenterBridge.setArtworkUri
                    // (EAS 小卡片/DIM 读 mLastArtworkUri → 永远旧值/null → 无封面)。
                    // 用户实测:主界面有 USB 封面、系统小卡片/DIM 无封面。外部音源分支
                    // (5409)和 CoverFallback 回调(5409)都注入 setArtworkUri,唯 USB 分支遗漏。
                    // 修复:更新 currentArtworkUri 后同步注入 bridge,确保 EAS 立即拿到封面;
                    // 异步补读成功(ensureUsbCoverAsync 5825)后再次注入并强制重推。
                    String prevUsbArt = currentArtworkUri;
                    currentArtworkUri = resolveUsbArtworkCached(usbItem);
                    if (currentArtworkUri == null) {
                        if (prevUsbArt != null) {
                            currentArtworkUri = prevUsbArt;
                            scheduleUsbCoverGrace(prevUsbArt);
                        }
                        ensureUsbCoverAsync(usbItem);
                    }
                    MediaCenterBridge.get().setArtworkUri(currentArtworkUri);
                    playbackDuration = usbItem.duration;
                    controllerPkg = UsbSourceController.SOURCE_PKG_USB;
                    // 转 EAS 状态
                    int easState = (usbState == UsbMusicPlayer.STATE_PLAYING)
                            ? EAS_STATUS_PLAYING : EAS_STATUS_PAUSED;
                    // 播放中启动进度 ticker，暂停停止
                    if (easState == EAS_STATUS_PLAYING) {
                        startPositionTicker();
                    } else {
                        stopPositionTicker();
                    }
                    // 去重判断（与外部音源逻辑一致）
                    String[] usbLyricPair = buildLyricPushPair();
                    String usbLyricForDedup = usbLyricPair[2];
                    boolean usbTrackChanged = !equalsSafe(currentTitle, lastPushedTitle)
                            || !equalsSafe(currentArtist, lastPushedArtist)
                            || !equalsSafe(currentArtworkUri, lastPushedArtworkUri)
                            || (usbLyricForDedup != null && !usbLyricForDedup.equals(lastPushedLyricForTitle))
                            // 播放索引权威去重——即使 title 被外部污染/同名歌曲,
                            // 索引一变(切歌)也强制全量推送,仪表盘不可能再冻结上一首。
                            || usbSourceController.getCurrentIndex() != lastPushedUsbIndex;
                    boolean usbStateChanged = (easState != lastPushedPlayState);
                    if (!usbTrackChanged && !usbStateChanged && !firstPushPending) {
                        // 元数据与状态都没变：只推进度
                        if (usbPos > 0) {
                            MediaCenterBridge.get().pushProgress(usbPos);
                        }
                        return;
                    }
                    // ★移植 MediaBridge：USB 支持收藏（isCurrentFav/toggleFav 已内置）
                    // + 完整歌词注入（setLyricContent/setCurrentLyricSentence/setLyric）
                    MediaCenterBridge.get().setCollectEnabled(true);
                    MediaCenterBridge.get().setCollected(usbSourceController.isCurrentFav());
                    MediaCenterBridge.get().setFullLyric(buildFullLrcContent(), usbLyricPair[0]);
                    // 全量推送
                    boolean usbDelivered = MediaCenterBridge.get().pushMetadata(
                            usbLyricPair[0], usbLyricPair[1], null,
                            playbackDuration, easState, true, controllerPkg, usbPos);
                    if (usbDelivered) {
                        lastPushedTitle = currentTitle;
                        lastPushedArtist = currentArtist;
                        lastPushedArtworkUri = currentArtworkUri;
                        lastPushedPlayState = easState;
                        if (usbLyricForDedup != null) {
                            lastPushedLyricForTitle = usbLyricForDedup;
                        }
                        lastPushedUsbIndex = usbSourceController.getCurrentIndex();
                        firstPushPending = false;
                    }
                    Log.d(TAG, "pushMetadataToCard: USB route pushed, state=" + easState
                            + ", title=" + currentTitle + ", pos=" + usbPos);
                    // ★ (Bug: USB 播放时桌面小组件不更新):
                    // 兜底保证——即使 lyricPollRunnable 因故未跑,USB 每次切歌/状态变化
                    // 走到全量推送时也直接刷新桌面小组件(EAS 推送与桌面小组件互不依赖)。
                    // updateAll 内部按内容去重,同歌/同状态不会重复重写 RemoteViews。
                    try {
                        CarMediaWidget.updateAll(this);
                        CarMediaSkyWidget.updateAll(this);
                    } catch (Throwable t) {
                        Log.w(TAG, "widget update after USB push error: " + t.getMessage());
                    }
                    return;
                }
                // USB 未在播放：不抢控制器/元数据，落回外部控制器逻辑。
                if (SystemClock.elapsedRealtime() - lastArmedNotPlayingLogMs > 3000) {
                    lastArmedNotPlayingLogMs = SystemClock.elapsedRealtime();
                    Log.d(TAG, "pushMetadataToCard: USB armed but not playing (item="
                            + (usbItem != null) + " state=" + usbState
                            + "), skip USB branch, keep current controller");
                }
            }
            // ================= USB 分流结束 =================
            // 防错配:若当前控制器失效(stale)或为 null,刷新选择;
            // 否则保持用户当前选择的控制器不动--不自动切换。
            if (controllerStale || mediaController == null) {
                // ghost 模式下(用户选择了暂停态会话,如酷狗)不调用 refreshController(),
                // 因为它会把 mediaController 重置为 null,导致 UI 一直显示"等待音乐播放"。
                // ghost 模式下尝试通过 findControllerByPackage() 获取 mediaController,
                // 如果还是 null,就继续推送(因为元数据可能已经通过 handleMetadata/通知获取了)。
                if (ghostControllerPkg != null) {
                    Log.d(TAG, "pushMetadataToCard: ghost mode, trying to find controller for " + ghostControllerPkg);
                    MediaController ghostCtrl = findControllerByPackage(ghostControllerPkg);
                    if (ghostCtrl != null) {
                        mediaController = ghostCtrl;
                        controllerStale = false;
                        try {
                            ghostCtrl.registerCallback(controllerCallback);
                        } catch (Exception e) {
                            Log.w(TAG, "pushMetadataToCard: register callback failed: " + e.getMessage());
                        }
                        Log.i(TAG, "pushMetadataToCard: ghost controller found: " + ghostControllerPkg);
                    } else {
                        Log.w(TAG, "pushMetadataToCard: ghost controller not found, continue push anyway (metadata may be available)");
                    }
                } else {
                    refreshController();
                    if (mediaController == null) {
                        Log.w(TAG, "pushMetadataToCard: no controller after refresh, skip");
                        return;
                    }
                }
            }
            int state = EAS_STATUS_PLAYING; // EAS 语义:默认播放
            long pos = 0L;
            int rawState = -1; // 来源原始 Android 状态,判定真停止用
            // 真实音频状态优先(MediaSession 可能陈旧错误,如汽水暂停仍报 3);
            // 通知判定其次;最后才用 controller 回调缓存。
            // 例外:蓝牙(com.android.bluetooth)的 A2DP sink AudioTrack 常驻,
            // audioReal 对蓝牙不可靠(暂停时仍可能报 started,导致永远推播放态)");
            // 蓝牙一律以 controller 实时会话状态为准(桌面原版:3→播放,其余→停止)。
            int audioState = getAudioRealPlayState();
            if (isBluetoothController() && mediaController != null) {
                try {
                    PlaybackState live = mediaController.getPlaybackState();
                    if (live != null) {
                        rawState = live.getState();
                        state = (rawState == STATE_PLAYING) ? EAS_STATUS_PLAYING : EAS_STATUS_PAUSED;
                        // 蓝牙裸 getPosition() 不实时更新(只在播放/暂停/seek 时
                        // 发事件),EAS/仪表盘进度会卡住不动。改用 getPlaybackPosition()——
                        // 蓝牙本地时间差累加 + AVRCP PlaybackState 实时位置校准,与主界面
                        // 进度条同源(主界面一直正常),保证 EAS 进度平滑走动、去重分支能推
                        // 有效进度(否则 pos=0/陈旧,去重分支不推进度)。
                        pos = getPlaybackPosition();
                    }
                } catch (Exception ignored) {
                }
            } else if (audioState > 0) {
                // getAudioRealPlayState 返回 Android 语义(3=播/2=暂停)→ 转 EAS 0/1
                state = (audioState == STATE_PLAYING) ? EAS_STATUS_PLAYING : EAS_STATUS_PAUSED;
                if (mediaController != null && mediaController.getPlaybackState() != null) {
                    pos = mediaController.getPlaybackState().getPosition();
                }
            } else if (notifActive() && lastPlaybackState != null) {
                rawState = lastPlaybackState.getState();
                // 走到这里 audioState==0(真实音频无该源任何 started/paused AudioTrack):
                // 播放中几乎必然有 started 的 AudioTrack,audioState==0 = 源未在播。
                // lastPlaybackState 可能是 MediaSession 残留的 PLAYING(汽水暂停不更新
                // state)--不可信,一律按暂停推(避免"实际暂停却显示播放")。
                state = EAS_STATUS_PAUSED;
                pos = lastPlaybackState.getPosition();
            } else if (lastPlaybackState != null) {
                rawState = lastPlaybackState.getState();
                // audioState==0(真实音频无信号):MediaSession 残留 PLAYING 不可信,
                // 一律暂停(播放几乎必然有 started AudioTrack,不会走到这里)。
                state = EAS_STATUS_PAUSED;
                pos = lastPlaybackState.getPosition();
            }
            // 防偶发推播放:audioState==0(真实音频无该源任何 started/paused 的
            // AudioTrack)时,MediaSession/通知快照可能残留 PLAYING(汽水暂停不更新
            // state)--上一级已把 state 定为暂停。播放中几乎必然有 started 的
            // AudioTrack,audioState==0 = 源未在播(暂停/停止),直接推暂停态。
            // 例外:来源明确是停止(STATE_STOPPED=1 / STATE_NONE=0,含 controller
            // 实时状态)时音源真的停了,必须推停止态--与桌面原版一致(非播放一律
            // 推 EAS 0),否则小卡片/仪表盘会一直残留播放中(用户实测)。
            if (audioState <= 0 && state != EAS_STATUS_PLAYING) {
                boolean definitelyStopped = (rawState == PlaybackState.STATE_STOPPED
                        || rawState == PlaybackState.STATE_NONE);
                if (!definitelyStopped && mediaController != null) {
                    try {
                        PlaybackState live = mediaController.getPlaybackState();
                        if (live != null) {
                            int s = live.getState();
                            if (s == PlaybackState.STATE_STOPPED || s == PlaybackState.STATE_NONE) {
                                definitelyStopped = true;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (definitelyStopped || isBluetoothController()) {
                    Log.i(TAG, "pushMetadataToCard: source stopped, push EAS status 0 (paused/stopped)");
                    // 继续走下方全量推送:状态从播放变停止
                } else if (firstPushPending) {
                    // Bug#Q2：开机首推不再推"未知状态=停止"——没放歌就不推 EAS，
                    // 等真实播放事件自行推送（用户要求：放歌才推送，没放歌不要推送）。
                    Log.d(TAG, "pushMetadataToCard: first push but nothing playing, skip EAS push");
                    return;
                } else {
                    // audioState==0 且非首次推送:真实音频无任何信号 = 源未在播,
                    // 直接推暂停(不沿用旧播放态--汽水暂停后 MediaSession 残留
                    // PLAYING 会导致误报播放,用户实测)。
                    Log.d(TAG, "pushMetadataToCard: audio unknown (no AudioTrack), push paused");
                    state = EAS_STATUS_PAUSED;
                }
            }
            // 与主界面显示一致:有歌词时,EAS 标题推歌词行、歌手推"歌名 - 歌手"
            // (主界面 refreshUiState:有歌词 tvPrimaryInfo=歌词、tvSecondaryInfo=歌名 - 歌手)
            // 先取歌词对(含歌词行),歌词行变化也视为内容变化(标题跟随歌词滚动)
            // flicker 期间 EAS 标题/歌手用稳定值(命中前基线),不用每行变化的歌词行。
            String effTitle = (titleFlickerLyricActive && flickerStableTitle != null)
                    ? flickerStableTitle : currentTitle;
            String effArtist = (titleFlickerLyricActive && flickerStableArtist != null)
                    ? flickerStableArtist : currentArtist;
            String[] lyricPair = buildLyricPushPair(effTitle, effArtist);
            String lyricForDedup = lyricPair[2];
            // MediaBridge 式去重:曲目/状态/歌词行未变时只更新进度,不重复全量推元数据。
            // (MediaBridge updateSystemMetadata:z3=曲目变、z4=状态变,全 false 只推进度)
            boolean trackChanged = !equalsSafe(effTitle, lastPushedTitle)
                    || !equalsSafe(effArtist, lastPushedArtist)
                    || !equalsSafe(currentArtworkUri, lastPushedArtworkUri) // 封面变化也推(MediaBridge 同款:artwork 是去重维度之一)
                    || (lyricForDedup != null && !lyricForDedup.equals(lastPushedLyricForTitle));
            boolean stateChanged = (state != lastPushedPlayState);
            // 播放中 → 启动进度 ticker(MediaBridge 同款)");暂停 → 停止
            // 注意:ticker 管理必须在去重 return 之前,否则去重分支永远启动不了 ticker
            if (state == EAS_STATUS_PLAYING) {
                startPositionTicker();
            } else {
                stopPositionTicker();
            }
            if (!trackChanged && !stateChanged && !firstPushPending) {
                // 元数据与状态都没变:只推进度(MediaBridge 的 POSITION_UPDATE 分支)
                if (pos > 0) {
                    MediaCenterBridge.get().pushProgress(pos);
                }
                return;
            }
            // 与主界面显示一致:有歌词时,EAS 标题推歌词行、歌手推"歌名 - 歌手"
            // (主界面 refreshUiState:有歌词 tvPrimaryInfo=歌词、tvSecondaryInfo=歌名 - 歌手)
            // 歌词行变化也视为内容变化(标题跟随歌词滚动)--上面已取 lyricPair/lyricForDedup
            // ★移植 MediaBridge：收藏支持（在线音乐源 true，蓝牙/USB/代理 false）+ 当前收藏状态
            // + 完整歌词注入（setLyricContent 完整 LRC / setCurrentLyricSentence 当前句 / setLyric Uri.EMPTY）
            MediaCenterBridge.get().setCollectEnabled(isCollectableSource(controllerPkg));
            MediaCenterBridge.get().setCollected(mCollected);
            MediaCenterBridge.get().setFullLyric(buildFullLrcContent(), lyricPair[0]);
            boolean delivered = MediaCenterBridge.get().pushMetadata(lyricPair[0], lyricPair[1], null,
                    playbackDuration, state, true, controllerPkg, pos);
            if (delivered) {
                lastPushedTitle = effTitle;
                lastPushedArtist = effArtist;
                lastPushedArtworkUri = currentArtworkUri;
                lastPushedPlayState = state;
                if (lyricForDedup != null) {
                    lastPushedLyricForTitle = lyricForDedup;
                }
                firstPushPending = false;
            }
        } catch (Throwable t) {
            Log.w(TAG, ">>> EAS pushMetadata exception: " + t.getMessage());
        }
    }
 /**
 * 音源停止(会话销毁 / 无活跃会话)时推送停止状态到小卡片/仪表盘。
 *
 * 背景:音乐 App 被停止/杀掉后,MediaController 回调消失,若不再推送,
 * 小卡片会一直残留最后一次"播放中"状态(用户实测"APP 停止后还显示播放中");
 * 桌面原版在音源停止后会把播放状态推成非播放。这里只在之前推过播放态时
 * 推一次停止态(幂等),并停掉进度 ticker 与歌词轮询,避免旧回调复活。
 */
    private void pushStoppedToCard() {
        try {
            if (!isDimPushEnabled()) {
                return;
            }
            // 幂等:之前没推过播放态(含已推过停止态)→ 无需重复推;
            // Bug#Q2:移除"服务刚激活的首推也推停止态"——没放歌就不推 EAS，
            // 只有从"播放中"变停止时才推停止态(放歌后才会有卡片)。
            if (lastPushedPlayState != EAS_STATUS_PLAYING) {
                return;
            }
            stopPositionTicker();
            stopLyricPoll();
            // 控制器已失效:通知快照与状态缓存一并作废,防止 notifActive()/
            // getPlaybackState() 用旧数据继续误报"播放中"
            notifPkg = null;
            notifTitle = null;
            notifArtist = null;
            lastPlaybackState = null;
            // 无控制器时 getBoundLyricLine 返回 null → buildLyricPushPair 退回歌名/歌手
            String[] pair = buildLyricPushPair();
            // ★移植 MediaBridge：停止时清除收藏按钮/收藏状态/歌词（与 pushClearedToCard 同步）
            MediaCenterBridge.get().setCollectEnabled(false);
            MediaCenterBridge.get().setCollected(false);
            MediaCenterBridge.get().clearFullLyric();
            boolean delivered = MediaCenterBridge.get().pushMetadata(pair[0], pair[1], null,
                    playbackDuration, EAS_STATUS_PAUSED, true, controllerPkg, 0L);
            if (delivered) {
                lastPushedTitle = currentTitle;
                lastPushedArtist = currentArtist;
                lastPushedArtworkUri = null;
                lastPushedPlayState = EAS_STATUS_PAUSED;
                firstPushPending = false;
            }
            Log.i(TAG, "pushStoppedToCard: source stopped, pushed EAS status 0 (paused/stopped)");
        } catch (Throwable t) {
            Log.w(TAG, "pushStoppedToCard error: " + t.getMessage());
        }
    }
 /**
 * 内容变化检测推送(通用,不限音源):
 * 歌名/歌手/歌词行/播放状态任一变化 → pushMetadataToCard 全量推送;
 * 都没变 → 只推进度(pushMetadataToCard 内部 MediaBridge 式去重)。
 * 由歌词轮询(pollLyricOnce)和进度 ticker(positionTickerRunnable)共同调用,
 * 不依赖 MediaController 的 onMetadataChanged/onPlaybackStateChanged 回调
 * (真车蓝牙等音源回调可能不触发)。
 */
    private void checkContentChangedAndPush() {
        if (isDimPushEnabled()) {
            pushMetadataToCard();
        }
    }
 /**
 * 构建 EAS 推送的标题/歌手对(与主界面显示一致):
 * 有歌词时 [0]=歌词行、[1]="歌名 - 歌手";无歌词时 [0]=歌名、[1]=歌手。
 * 返回 String[3],[2]=歌词行(无歌词时 null,用于去重判断)。
 *
 * ★移植 MediaBridge：CoverFallback 解析成功后回调（主线程）→ 注入封面并重推全量。
 */
    public void onCoverFallbackResolved(Uri uri) {
        try {
            if (uri == null) {
                return;
            }
            currentArtworkUri = uri.toString();
            MediaCenterBridge.get().setArtworkUri(currentArtworkUri);
            Log.i(TAG, "cover fallback resolved: " + currentArtworkUri);
            // 强制推一次 EAS(与 injectKugouCover 对称)。
            // 根因:酷狗冷启动时 AIDL 未绑定 → 走 CoverFallback → 旧逻辑只受 isDimPushEnabled 门控,
            // 门控关时 EAS 小卡片/仪表盘收不到封面,只有主界面有(currentArtworkUri 直接读)。
            // 修复:和酷狗 AIDL 封面路径一样,绕过门控强制推一次,确保 EAS 立即收到封面。
            try {
                String[] pair = buildLyricPushPair(
                        titleFlickerLyricActive && flickerStableTitle != null
                                ? flickerStableTitle : currentTitle,
                        titleFlickerLyricActive && flickerStableArtist != null
                                ? flickerStableArtist : currentArtist);
                MediaCenterBridge.get().pushMetadata(
                        pair[0], pair[1], null,
                        0L, EAS_STATUS_PLAYING, true, controllerPkg, 0L);
                lastPushedArtworkUri = currentArtworkUri;
                Log.i(TAG, "cover fallback forced push EAS done: " + currentArtworkUri);
            } catch (Throwable te) {
                Log.w(TAG, "cover fallback forced push EAS failed: " + te.getMessage());
            }
            if (isDimPushEnabled()) {
                pushMetadataToCard();
            }
        } catch (Throwable t2) {
            Log.w(TAG, "onCoverFallbackResolved error: " + t2.getMessage());
        }
    }

 /**
 * ★酷狗 AIDL 封面解析成功后回调→ 注入封面并重推全量。
 * 归属校验：回调回来时当前控制器仍是酷狗才注入（异步期间用户可能已切走，
 * 防串源——把酷狗封面贴到酷我/汽水/蓝牙上）。
     * http URL 下载落盘转 content:// 再注入——
     * WebView(file:// 页面)默认混合内容策略拒绝 http 图片(onerror 静默回退 SVG)，
     * 而 content://com.ecarx.carmedia.artwork 已被 shouldInterceptRequest 拦截返回流，
 * 主界面/EAS 小卡片都能稳定加载（CoverFallback 同款已验证）。
 * 下载在 kugou-cover 工作线程执行，注入切主线程 handler。
 */
    public void onKugouCoverResolved(String url) {
        try {
            if (url == null || url.isEmpty()) {
                return;
            }
            if (!PKG_KUGOU.equals(controllerPkg)) {
                Log.d(TAG, "kugou cover resolved but controller switched to "
                        + controllerPkg + ", skip");
                return;
            }
            String lower = url.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                // 已有本地版(本会话下载过)直接用;否则后台下载转 content URI
                String local = mKugouLocalArt.get(url);
                if (local != null) {
                    injectKugouCover(local);
                    return;
                }
                final String fUrl = url;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String localUri = downloadKugouCoverToLocal(fUrl);
                        if (localUri != null) {
                            mKugouLocalArt.put(fUrl, localUri);
                        }
                        injectKugouCover(localUri != null ? localUri : fUrl);
                    }
                });
                t.setName("kugou-cover-local");
                t.setDaemon(true);
                t.start();
            } else {
                injectKugouCover(url);
            }
        } catch (Throwable t2) {
            Log.w(TAG, "onKugouCoverResolved error: " + t2.getMessage());
        }
    }

    /** 酷狗封面 http URL 下载落盘转 content:// 返回；失败返回 null（保留原 URL 用）。 */
    private String downloadKugouCoverToLocal(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            java.io.InputStream is = conn.getInputStream();
            android.graphics.Bitmap bmp = null;
            try {
                bmp = android.graphics.BitmapFactory.decodeStream(is);
            } finally {
                try { is.close(); } catch (Throwable ignored) { }
                conn.disconnect();
            }
            android.net.Uri uri = (bmp != null) ? ArtworkHelper.saveArtwork(this, bmp) : null;
            if (uri != null) {
                Log.i(TAG, "kugou cover downloaded -> " + uri);
                return uri.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "kugou cover download failed, keep http: " + t.getMessage());
        }
        return null;
    }

 /** 主线程注入封面并重推(归属校验防串源)。 */
    private void injectKugouCover(final String contentUri) {
        try {
            final String webUri = toWebArtworkUri(contentUri);
            firstPushHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!PKG_KUGOU.equals(controllerPkg)) {
                            Log.d(TAG, "kugou cover resolved late but controller switched to "
                                    + controllerPkg + ", skip");
                            return;
                        }
                        // EAS(仪表盘/小卡片,跨进程)用 content://;主界面 WebView 用 file://
                        // (content:// 在 WebView 渲染进程加载失败,file:// 已验证可稳定显示)
                        MediaCenterBridge.get().setArtworkUri(contentUri);
                        currentArtworkUri = webUri;
                        Log.i(TAG, "kugou cover resolved: " + currentArtworkUri);
                        // 强制推一次 EAS(绕过 isDimPushEnabled 门控 + pushMetadataToCard 去重),
                        // 确保小卡片/仪表盘立即收到 content:// 封面(否则异步下载完后 EAS 不刷新)
                        try {
                            String[] pair = buildLyricPushPair(
                                    titleFlickerLyricActive && flickerStableTitle != null
                                            ? flickerStableTitle : currentTitle,
                                    titleFlickerLyricActive && flickerStableArtist != null
                                            ? flickerStableArtist : currentArtist);
                            MediaCenterBridge.get().pushMetadata(
                                    pair[0], pair[1], null,
                                    0L, EAS_STATUS_PLAYING, true, PKG_KUGOU, 0L);
                            lastPushedArtworkUri = contentUri;
                            Log.i(TAG, "kugou cover forced push EAS done: " + contentUri);
                        } catch (Throwable te) {
                            Log.w(TAG, "kugou cover forced push EAS failed: " + te.getMessage());
                        }
                    } catch (Throwable t2) {
                        Log.w(TAG, "onKugouCoverResolved error: " + t2.getMessage());
                    }
                }
            });
        } catch (Throwable t2) {
            Log.w(TAG, "injectKugouCover error: " + t2.getMessage());
        }
    }

    /** content://artwork/xxx -> file://filesDir/artwork/xxx(WebView 可加载)");非本 provider URI 原样返回。 */
    private String toWebArtworkUri(String contentUri) {
        try {
            if (contentUri != null && contentUri.startsWith("content://com.ecarx.carmedia.artwork/")) {
                String name = contentUri.substring(contentUri.lastIndexOf('/') + 1);
                java.io.File f = new java.io.File(ArtworkHelper.getArtworkDir(this), name);
                return android.net.Uri.fromFile(f).toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "toWebArtworkUri error: " + t.getMessage());
        }
        return contentUri;
    }

 /**
 * 歌词-歌名绑定校验:歌词抓取是异步的,切歌瞬间 fetcher 的 mParsedLrc
 * 可能还是旧歌歌词(新歌 search 尚未完成),此时不能把旧歌词行当新歌标题推--
 * 只有 fetcher 当前加载的歌名与 currentTitle 一致才用歌词行,否则退回歌名/歌手,
 * 避免小卡片显示"新歌名 + 旧歌词"错配。
 */
    private String[] buildLyricPushPair() {
        return buildLyricPushPair(currentTitle, currentArtist);
    }

 /** 带稳定标题/歌手的重载 :flicker 期间 EAS 推稳定歌名而非每行歌词。 */
    private String[] buildLyricPushPair(String title, String artist) {
        String line = getBoundLyricLine();
        if (line != null && !line.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (title != null) {
                sb.append(title);
            }
            sb.append(" - ");
            if (artist != null) {
                sb.append(artist);
            }
            return new String[]{line, sb.toString(), line};
        }
        return new String[]{title, artist, null};
    }
 /**
 * 取当前歌词行(主界面/推送共用,同源):
 * 按播放进度从歌词抓取器取当前行(QQ/网易云),无则 null。
 * 不做歌名绑定校验--切歌瞬间短暂错配由抓取器异步更新自愈;
 * 加校验反而会导致有歌词时推送永远 fallback 歌名。
 */
    private String getBoundLyricLine() {
        return getLyricLine(false);
    }
 /** 下一句歌词行(UI "下一句" 标签用,与当前行同源)。 */
    public String getNextBoundLyricLine() {
        return getLyricLine(true);
    }
 /** 主屏 UI 当前歌词行(带提前量版):仅"氛围歌词"主题加提前量,其他主题原拍。
        EAS/桌面小组件继续用 getCurrentLyricLine(),保持原拍,不受影响。 */
    public String getUiCurrentLyricLine() {
        try {
            if (titleFlickerLyricActive && currentTitle != null && !currentTitle.isEmpty()) {
                return currentTitle;
            }
            return getLyricLine(false, true);
        } catch (Exception e) {
            Log.w(TAG, "getUiCurrentLyricLine error: " + e.getMessage());
        }
        return null;
    }
 /** 主屏 UI 下一句(带提前量版):与 getUiCurrentLyricLine 同源,neon 下同样提前。 */
    public String getUiNextLyricLine() {
        return getLyricLine(true, true);
    }

 /** 备用歌词接口(网易云/XMF2)全量兜底：归属校验通过才返回，否则 null。
 * 与 getLyricLine 的 cloud 兜底对称——QQ AIDL 取词失败/切歌卡旧歌时，
 * 全量歌词也能从 cloudLyricFetcher 已解析好的 LRC 拿到（晴空主题不再回退两行）。 */
    private java.util.List<String> getCloudFullLyricLines() {
        if (cloudLyricFetcher == null) return null;
        if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) return null;
        return cloudLyricFetcher.getAllLyricLines();
    }

 /** 备用歌词接口当前行索引兜底（与 getCloudFullLyricLines 行序一致；无则 -1）。 */
    private int getCloudLyricIndex(long position) {
        if (cloudLyricFetcher == null) return -1;
        if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) return -1;
        return cloudLyricFetcher.getCurrentLyricIndex(position);
    }

 /** 全量歌词行（按当前源分派，带归属校验与屏蔽检查；无则空列表）。 */
    public java.util.List<String> getFullLyricLines() {
        // USB 内嵌歌词：直接返回缓存，不走抓取器归属校验。
        // usbEmbeddedFallback(内嵌为空已兜底云歌词)时跳过本分支走下方云歌词分派。
        if (usbRouteArmed && ("embedded".equals(usbLyricSource) || "auto".equals(usbLyricSource)) && !usbEmbeddedFallback) {
            // 归属校验,旧歌残留不返回(前端歌词区防串歌)
            if (!isUsbLyricForCurrent()) {
                return new java.util.ArrayList<>();
            }
            return new java.util.ArrayList<>(usbEmbeddedLyricLines);
        }
        java.util.List<String> empty = new java.util.ArrayList<>();
        try {
            if (currentTitle == null || currentTitle.isEmpty()) return empty;
            if (isLyricBlocked(controllerPkg)) return empty;
            java.util.List<String> l = null;
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                if (lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle)) {
                    l = qqMusicFetcher.getAllLyricLines();
                }
                // QQ 归属校验失败(切歌卡旧歌)或 QQ 全量为空(无 LRC)→ 备用接口全量兜底
                if (l == null || l.isEmpty()) {
                    l = getCloudFullLyricLines();
                }
            } else if (currentSourceType == SOURCE_TYPE_NETEASE && cloudLyricFetcher != null) {
                if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) {
                    // 诊断:晴空无歌词时区分"归属失败"与"歌词为空"(logcat 过滤 CarMediaService)
                    Log.d(TAG, "getFullLyricLines: NETEASE ownership fail songTitle='"
                            + cloudLyricFetcher.getSongTitle() + "' currentTitle='" + currentTitle + "'");
                    return empty;
                }
                l = cloudLyricFetcher.getAllLyricLines();
            } else if (currentSourceType == SOURCE_TYPE_SODA && sodaMusicFetcher != null) {
                if (!lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle)) {
                    Log.d(TAG, "getFullLyricLines: SODA ownership fail songTitle='"
                            + sodaMusicFetcher.getSongTitle() + "' currentTitle='" + currentTitle + "'");
                    return empty;
                }
                l = sodaMusicFetcher.getAllLyricLines();
            } else if (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null) {
                if (!lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle)) {
                    Log.d(TAG, "getFullLyricLines: KUWO ownership fail songTitle='"
                            + kuwoLyricFetcher.getSongTitle() + "' currentTitle='" + currentTitle + "'");
                    return empty;
                }
                l = kuwoLyricFetcher.getAllLyricLines();
                // 酷我专属接口无歌词(空) → 备用接口全量兜底(与 QQ 分支对称)
                if (l == null || l.isEmpty()) {
                    l = getCloudFullLyricLines();
                }
            } else if (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null) {
                if (!lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle)) {
                    Log.d(TAG, "getFullLyricLines: KUGOU ownership fail songTitle='"
                            + kugouLyricFetcher.getSongTitle() + "' currentTitle='" + currentTitle + "'");
                    return empty;
                }
                l = kugouLyricFetcher.getAllLyricLines();
                // 酷狗专属接口无歌词(空) → 备用接口全量兜底(与 QQ 分支对称)
                if (l == null || l.isEmpty()) {
                    l = getCloudFullLyricLines();
                }
            } else if (currentSourceType == SOURCE_TYPE_GENERIC && cloudLyricFetcher != null) {
                if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) {
                    Log.d(TAG, "getFullLyricLines: GENERIC ownership fail songTitle='"
                            + cloudLyricFetcher.getSongTitle() + "' currentTitle='" + currentTitle + "'");
                    return empty;
                }
                l = cloudLyricFetcher.getAllLyricLines();
            }
            return l == null ? empty : l;
        } catch (Throwable t) {
            Log.w(TAG, "getFullLyricLines error: " + t.getMessage());
        }
        return empty;
    }

 /** 当前歌词行索引（与 getFullLyricLines 的行序一致；无则 -1）。 */
    public int getCurrentLyricIndex() {
        // USB 内嵌歌词：按播放进度估算索引。
        // usbEmbeddedFallback(内嵌为空已兜底云歌词)时跳过本分支走下方云歌词分派。
        if (usbRouteArmed && ("embedded".equals(usbLyricSource) || "auto".equals(usbLyricSource)) && !usbEmbeddedFallback) {
            // 归属校验,旧歌残留索引不返回
            if (!isUsbLyricForCurrent()) return -1;
            return getUsbEmbeddedLyricIndex();
        }
        try {
            if (currentTitle == null || currentTitle.isEmpty()) return -1;
            if (isLyricBlocked(controllerPkg)) return -1;
            long position = -1L;
            try { position = getPlaybackPosition(); } catch (Exception ignored) {}
            if (position < 0) return -1;
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                if (lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle)) {
                    int idx = qqMusicFetcher.getCurrentLyricIndex(position);
                    if (idx >= 0) return idx;
                }
                // 与 getFullLyricLines 同步兜底：QQ 不可用/为空时用备用接口索引
                return getCloudLyricIndex(position);
            } else if (currentSourceType == SOURCE_TYPE_NETEASE && cloudLyricFetcher != null) {
                if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) return -1;
                return cloudLyricFetcher.getCurrentLyricIndex(position);
            } else if (currentSourceType == SOURCE_TYPE_SODA && sodaMusicFetcher != null) {
                if (!lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle)) return -1;
                return sodaMusicFetcher.getCurrentLyricIndex(position);
            } else if (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null) {
                if (lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle)) {
                    int idx = kuwoLyricFetcher.getCurrentLyricIndex(position);
                    if (idx >= 0) return idx;
                }
                // 与 getFullLyricLines 同步兜底：酷我无歌词时用备用接口索引
                return getCloudLyricIndex(position);
            } else if (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null) {
                if (lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle)) {
                    int idx = kugouLyricFetcher.getCurrentLyricIndex(position);
                    if (idx >= 0) return idx;
                }
                // 与 getFullLyricLines 同步兜底：酷狗无歌词时用备用接口索引
                return getCloudLyricIndex(position);
            } else if (currentSourceType == SOURCE_TYPE_GENERIC && cloudLyricFetcher != null) {
                if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) return -1;
                return cloudLyricFetcher.getCurrentLyricIndex(position);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getCurrentLyricIndex error: " + t.getMessage());
        }
        return -1;
    }

    /** 当前歌曲封面 URI（content://，经 ArtworkContentProvider 提供；无则 null）。 */
    public String getCurrentArtworkUri() {
        return currentArtworkUri;
    }

    /** 仅查已缓存的 USB 封面文件(无解码,快)");返回 file:// URI 或 null。
 * 兼容旧版扫描已落盘的封面缓存(按 path.hashCode 命名)。 */
    private String resolveUsbArtworkCached(UsbMusicItem item) {
        try {
            if (item == null || item.path == null || item.path.isEmpty()) return null;
            if (item.coverPath != null && !item.coverPath.isEmpty()) {
                // ★ coverPath 现在优先存 content:// URI(走 ArtworkContentProvider,
                // 跨进程授权读取,无 EACCES)——直接返回。
                if (item.coverPath.startsWith("content://")) {
                    return item.coverPath;
                }
                java.io.File f = new java.io.File(item.coverPath);
                if (f.exists() && f.length() > 0) {
                    return "file://" + item.coverPath;
                }
            }
            // ★ (EAS/DIM USB 封面 EACCES 根因):优先读公共可读路径
            // (/sdcard/Pictures/carmedia_usb_covers/)——系统小卡片/MDW 进程才能读到。
            // 旧实现只读 getCacheDir()/usb_covers(私有目录) → DIM 加载 EACCES → 无封面。
            java.io.File publicDir = com.ecarx.carmedia.UsbMusicScanner
                    .getUsbCoverPublicDir(getApplicationContext());
            java.io.File pubFile = new java.io.File(publicDir,
                    Integer.toHexString(item.path.hashCode()) + ".jpg");
            if (pubFile.exists() && pubFile.length() > 0) {
                item.coverPath = pubFile.getAbsolutePath();
                return "file://" + pubFile.getAbsolutePath();
            }
            java.io.File cached = new java.io.File(getApplicationContext().getCacheDir(),
                    "usb_covers/" + Integer.toHexString(item.path.hashCode()) + ".jpg");
            if (cached.exists() && cached.length() > 0) {
                item.coverPath = cached.getAbsolutePath();
                return "file://" + cached.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveUsbArtworkCached error: " + t.getMessage());
        }
        return null;
    }

 /** ★ (EAS/DIM USB 封面 EACCES 根因·content URI 方案):
     *  内嵌封面字节 → Bitmap → ArtworkHelper.saveArtwork → content:// URI。
     *  与其它音源(QQ/网易云等落盘后走 content://)完全同一套跨进程授权机制:
 * DIM 进程经 ArtworkContentProvider.openFile 拿到 fd,无需自身访问私有目录权限,
     *  彻底消除裸 file://(私有 cache)的 EACCES。返回 content:// 字符串,失败返回 null。 */
    private String usbCoverSaveToContentUri(byte[] art, String songPath) {
        try {
            if (art == null || art.length == 0) return null;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.length);
            if (bmp == null) return null;
            android.net.Uri uri = ArtworkHelper.saveArtwork(this, bmp);
            if (uri == null) return null;
            Log.i(TAG, "usb cover saved to content URI: " + uri + " for " + songPath);
            return uri.toString();
        } catch (Throwable t) {
            Log.w(TAG, "usbCoverSaveToContentUri error: " + t.getMessage());
            return null;
        }
    }

 /** 播放时按需异步补读 USB 嵌入封面(单首,后台线程,不阻塞主线程/推送链路)。 */
    private void ensureUsbCoverAsync(final UsbMusicItem item) {
        if (item == null || item.path == null || item.path.isEmpty()) return;
        try {
            final String lockKey = "usbcover:" + item.path;
            if (coverLoadingKeys == null) coverLoadingKeys = new java.util.HashSet<String>();
            if (coverLoadingKeys.contains(lockKey)) return;
            coverLoadingKeys.add(lockKey);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] art = null;
                        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                        try {
                            mmr.setDataSource(item.path);
                            art = mmr.getEmbeddedPicture();
                        } finally {
                            try { mmr.release(); } catch (Throwable ignored) {}
                        }
                        if (art == null || art.length == 0) {
                            // 无内嵌封面 → 在线兜底搜索(网易云/QQ),成功注入封面
                            // (归属校验防串歌;失败进 CoverFallback 60s 冷却,不刷屏)
                            final String t = item.title;
                            if (t != null && !t.isEmpty()) {
                                final String a = item.artist == null ? "" : item.artist;
                                CoverFallback.request(getApplicationContext(), t, a,
                                    new CoverFallback.ResolveCallback() {
                                        @Override
                                        public void onResolved(android.net.Uri uri) {
                                            try {
                                                if (uri == null) return;
                                                final android.os.Handler h2 = lyricPollHandler;
                                                if (h2 == null) return;
                                                h2.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            if (usbSourceController != null
                                                                    && usbSourceController.getCurrentItem() == item) {
                                                                currentArtworkUri = uri.toString();
                                                                MediaCenterBridge.get().setArtworkUri(currentArtworkUri);
                                                                Log.i(TAG, "usb cover fallback resolved: " + currentArtworkUri);
                                                                if (isDimPushEnabled()) pushMetadataToCard();
                                                                if (MainActivity.sInstance != null) {
                                                                    MainActivity.sInstance.pushAllPublic();
                                                                }
                                                            }
                                                        } catch (Throwable t3) {
                                                            Log.w(TAG, "usb cover fallback apply error: " + t3.getMessage());
                                                        }
                                                    }
                                                });
                                            } catch (Throwable t3) {
                                                Log.w(TAG, "usb cover fallback cb error: " + t3.getMessage());
                                            }
                                        }
                                    });
                            }
                            return;
                        }
                        final String coverUriStr = usbCoverSaveToContentUri(art, item.path);
                        if (coverUriStr == null) return;
                        final android.os.Handler h = lyricPollHandler;
                        if (h == null) return;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (usbSourceController != null
                                            && usbSourceController.getCurrentItem() == item) {
                                        item.coverPath = coverUriStr;
                                        currentArtworkUri = coverUriStr;
                                        if (isDimPushEnabled()) pushMetadataToCard();
                                        if (MainActivity.sInstance != null) {
                                            MainActivity.sInstance.pushAllPublic();
                                        }
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "ensureUsbCover apply error: " + t.getMessage());
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.w(TAG, "ensureUsbCover read error: " + t.getMessage());
                    } finally {
                        if (coverLoadingKeys != null) coverLoadingKeys.remove(lockKey);
                    }
                }
            }, "UsbCoverLoad").start();
        } catch (Throwable t) {
            Log.w(TAG, "ensureUsbCoverAsync start error: " + t.getMessage());
        }
    }
 /**
 * 统一歌词行取值(当前行/下一句合并):
 * 取 position → 按源分派 → 取行 → 空判断 → 归属校验 → 返回。
 * QQ 源无歌词时自动回退到 cloudLyricFetcher 兜底(网易云→XMF2)。
 *
 * @param next false=当前行, true=下一句
 */
 /** USB 内嵌歌词归属校验——当前歌曲与已加载歌词的歌曲一致才可用。
 * 防止切歌后旧歌词缓存残留被推到仪表盘/UI(旧歌歌词串歌、仪表盘永远显示第一首)。 */
    private boolean isUsbLyricForCurrent() {
        if (usbSourceController == null) return false;
        UsbMusicItem cur = usbSourceController.getCurrentItem();
        if (cur == null) return false;
        String curKey = (cur.title != null ? cur.title : "") + "||"
                + (cur.artist != null ? cur.artist : "");
        return curKey.equals(lastUsbLyricSongKey);
    }
 /** 歌词提前量(ms):仅"氛围歌词"主题(skin-neon)生效。该主题车机 WebView 渲染/推送有延迟
        (UI 全量轮询 1s + 推送链路 + 切句渐变动画~340ms),歌词显示常"慢半拍"。
        提前量必须**盖过渐变时长**才能让主屏肉眼可见地领先仪表盘:
        可见提前 ≈ 提前量 − 渐变时长。故默认 700ms(700−340≈360ms 可见领先);
        觉得太早就下调,觉得还慢就上调(建议 500~900)。其他主题/仪表盘/小组件不受影响。 */
    private static final long LYRIC_LOOKAHEAD_MS = 700L;

    private String getLyricLine(boolean next) {
        return getLyricLine(next, false);
    }
 /** 带"UI 是否提前"标记的重载:uiLookahead=true 且当前皮肤为 skin-neon 时给解析进度加提前量
        (主屏"氛围歌词"主题专用);其他主题/仪表盘/桌面小组件传 false 保持原拍。 */
    private String getLyricLine(boolean next, boolean uiLookahead) {
        // USB 内嵌歌词：按估算索引取行（当前行/下一句）。
        // usbEmbeddedFallback(内嵌为空已兜底云歌词)时跳过本分支走云歌词。
        if (usbRouteArmed && ("embedded".equals(usbLyricSource) || "auto".equals(usbLyricSource)) && !usbEmbeddedFallback) {
            // 归属校验——旧歌残留缓存不得串推
            if (!isUsbLyricForCurrent()) return null;
            java.util.List<String> lines = usbEmbeddedLyricLines;
            if (lines == null || lines.isEmpty()) return null;
            int idx = getUsbEmbeddedLyricIndex();
            if (idx < 0) return null;
            int pick = next ? idx + 1 : idx;
            if (pick < 0 || pick >= lines.size()) return null;
            return lines.get(pick);
        }
        try {
            // USB 播放时云端歌词归属以 usbItem 为权威——
            // 不依赖 currentTitle(可能被外部回调覆盖/切歌未同步),防止备用歌词
            // (XMF2/网易云)残留上一首歌词被推到仪表盘/UI。
            // usbEmbeddedFallback 期间放行(云歌词归属仍按 currentTitle 校验)。
            if (usbRouteArmed && !usbEmbeddedFallback && !isUsbLyricForCurrent()) {
                return null;
            }
            if (currentTitle == null || currentTitle.isEmpty()) {
                return null;
            }
            if (isLyricBlocked(controllerPkg)) {
                return null;
            }
            long position = -1L;
            try {
                // 改用 getPlaybackPosition()(蓝牙本地累加/AVRCP 校准,与
                // 进度条同源)。裸 PlaybackState.getPosition() 在蓝牙/部分音源下不实时
                // 更新 → 歌词永远选第一行,只显示第一句不刷新(Bug#31 同根因)。
                position = getPlaybackPosition();
            } catch (Exception ignored) {
            }
            if (position < 0) {
                return null;
            }
            // 仅"氛围歌词(skin-neon)"主题提前显示下一句——当前 UI 皮肤为 neon 时给解析进度
            // 加提前量,当前句/下一句均按更靠前的进度取行,抵消车机 WebView 轮询+推送+切句动画延迟造成
            // 的"慢半拍"。其他主题/仪表盘/桌面小组件保持原拍不提前。皮肤由 MainActivity.uiSkin 门控
            // (JS onSetSkin 同步);进度条仍用原位置,不受影响。
            if (uiLookahead && "neon".equals(MainActivity.uiSkin)) {
                position += LYRIC_LOOKAHEAD_MS;
            }
            // 按源分派:QQ → 网易云 → 通用(XMF2)
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                String line = next ? qqMusicFetcher.getNextLyricLine(position)
                                   : qqMusicFetcher.getCurrentLyricLine(position);
                if (line == null || line.trim().isEmpty()) {
                    // QQ AIDL 无歌词(未授权/未绑定/无 LRC)→ 通用抓取器兜底
                    return getGenericLyricLine(position, next);
                }
                // 歌词归属校验:归一化比较(防切歌瞬间旧歌 LRC 残留串歌)
                if (!lyricTitleMatches(qqMusicFetcher.getSongTitle(), currentTitle)) {
                    return null;
                }
                return line;
            } else if (currentSourceType == SOURCE_TYPE_NETEASE && cloudLyricFetcher != null) {
                String line = next ? cloudLyricFetcher.getNextLyricLine(position)
                                   : cloudLyricFetcher.getCurrentLyricLine(position);
                if (line == null || line.trim().isEmpty()) {
                    return null;
                }
                if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) {
                    return null;
                }
                return line;
            } else if (currentSourceType == SOURCE_TYPE_SODA && sodaMusicFetcher != null) {
                String line = next ? sodaMusicFetcher.getNextLyricLine(position)
                                   : sodaMusicFetcher.getCurrentLyricLine(position);
                if (line == null || line.trim().isEmpty()) {
                    return null;
                }
                if (!lyricTitleMatches(sodaMusicFetcher.getSongTitle(), currentTitle)) {
                    return null;
                }
                return line;
            } else if (currentSourceType == SOURCE_TYPE_KUWO && kuwoLyricFetcher != null) {
                String line = next ? kuwoLyricFetcher.getNextLyricLine(position)
                                   : kuwoLyricFetcher.getCurrentLyricLine(position);
                if (line == null || line.trim().isEmpty()) {
                    // 酷我 AIDL 无歌词(未绑定/无 LRC)→ 通用 XMF2 兜底(与 QQ 分支对称)
                    return getGenericLyricLine(position, next);
                }
                if (!lyricTitleMatches(kuwoLyricFetcher.getSongTitle(), currentTitle)) {
                    return null;
                }
                return line;
            } else if (currentSourceType == SOURCE_TYPE_KUGOU && kugouLyricFetcher != null) {
                String line = next ? kugouLyricFetcher.getNextLyricLine(position)
                                   : kugouLyricFetcher.getCurrentLyricLine(position);
                if (line == null || line.trim().isEmpty()) {
                    // 酷狗 AIDL 无歌词(冷启动未绑定/无 LRC)→ 通用 XMF2 兜底(与 QQ 分支对称)
                    return getGenericLyricLine(position, next);
                }
                if (!lyricTitleMatches(kugouLyricFetcher.getSongTitle(), currentTitle)) {
                    return null;
                }
                return line;
            } else if (currentSourceType == SOURCE_TYPE_GENERIC && cloudLyricFetcher != null) {
                return getGenericLyricLine(position, next);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getLyricLine(" + (next ? "next" : "cur") + ") error: " + t.getMessage());
        }
        return null;
    }
 /** 通用歌词兜底:从 cloudLyricFetcher 取行,带归属校验。 */
    private String getGenericLyricLine(long position, boolean next) {
        if (cloudLyricFetcher == null) {
            return null;
        }
        String line = next ? cloudLyricFetcher.getNextLyricLine(position)
                           : cloudLyricFetcher.getCurrentLyricLine(position);
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        if (!lyricTitleMatches(cloudLyricFetcher.getSongTitle(), currentTitle)) {
            return null;
        }
        return line;
    }
 /** 歌词归属校验：歌词抓取器的歌名与当前播放歌名归一化后比较，不一致返回 false。 */
    private static boolean lyricTitleMatches(String lyricTitle, String currentTitle) {
        if (lyricTitle == null || lyricTitle.isEmpty()) {
            return true; // 无歌名信息时不拦截
        }
        return TextUtil.normalize(lyricTitle).equals(TextUtil.normalize(currentTitle));
    }
 /** null 安全的字符串相等比较。 */
    private static boolean equalsSafe(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
 /** 启动进度推送循环(MediaBridge positionTicker 同款:播放中每 2s 推一次进度)。 */
    private void startPositionTicker() {
        if (positionTickerRunning) {
            return;
        }
        positionTickerRunning = true;
        lyricPollHandler.postDelayed(positionTickerRunnable, POSITION_TICKER_INTERVAL_MS);
        Log.d(TAG, "position ticker started");
    }
 /** 停止进度推送循环。 */
    private void stopPositionTicker() {
        positionTickerRunning = false;
        if (lyricPollHandler != null) {
            lyricPollHandler.removeCallbacks(positionTickerRunnable);
        }
    }
 /**
 * 监听系统音频播放变化:检测到新的音源真正开始播放(AudioTrack started)时,
 * 若它有活跃媒体会话且不是当前控制器,自动切换过去。
 * 场景:网易云在播 → 用户打开 QQ 音乐开始播放 → carmedia 自动切到 QQ。
 * 同音源切歌(AudioTrack 短暂重建但包名相同)不会误切。
 */
    private void initAudioPlaybackMonitor() {
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            audioPlaybackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    handlePlaybackConfigChanged(configs);
                }
            };
            // API 26+,minSdk 28 原生支持;handler 用 null 走主线程回调
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, null);
            Log.i(TAG, "audio playback monitor registered");
        } catch (Throwable t) {
            Log.w(TAG, "initAudioPlaybackMonitor failed: " + t.getMessage());
        }
    }
    private void unregisterAudioPlaybackMonitor() {
        try {
            if (audioManager != null && audioPlaybackCallback != null) {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback);
                audioPlaybackCallback = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "unregisterAudioPlaybackMonitor failed: " + t.getMessage());
        }
    }
    private void handlePlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        // ★ 修复：USB 音源激活时禁止自动切源（与 sessionChangedListener/
        // updateAvailableControllers 一致）。USB 切歌时 CarMedia 自己的 AudioTrack 空窗
        // (停止→准备)会触发本回调，此时蓝牙 A2DP sink 常驻 started + MediaSession 残留
        // PLAYING，会被误判为"新在播源"切走——方向盘切歌切到蓝牙的根因。
        // (Bug#72C):放开 USB 闸门——"谁在播放就切谁"。
        // 原实现 USB 激活时整段 return:打开/播放其他 App(如汽水音乐)永远不会切走 USB,
        // 实车环境验证"打开汽水音乐,音源还在 USB"。
        // 现在改为:允许继续扫描,检测到真实外部音源(非自建/非系统/非蓝牙误报/
        // MediaSession 真在播)时自动切到它;selectControllerInner 会暂停 USB
        // ("上一个音源暂停")。USB 自身 AudioTrack 属 PKG_SELF,扫描循环会过滤,
        // 不会把 USB 自己当"新源"。seek/USB 切歌 3s/来电保护/冷却窗口全部保留。
        boolean usbArmed = usbRouteArmed;
        // seek 保护窗——seek 命令瞬间酷我/在线源 AudioTrack 短暂闪断,
        // 后台常驻 AudioTrack(汽水/QQ 车机版)会被误判为"新在播源"自动切走控制器,
        // 表现为"点击/拖动进度条就切歌"。seek 后 2 秒内禁止自动切换。
        if (System.currentTimeMillis() < seekProtectUntil) {
            Log.d(TAG, "handlePlaybackConfigChanged: in seek protect window, skip auto-switch");
            return;
        }
        // 二次保险：USB 切歌后 3 秒内（即使 armed 意外复位）也不自动切源，与 kugouSwitchProtect 同款窗口。
        if (System.currentTimeMillis() - lastUsbKeyEventTime < USB_SWITCH_PROTECT_MS) {
            Log.d(TAG, "handlePlaybackConfigChanged: usb switch protect window, skip auto-switch");
            return;
        }
        try {
            if (configs == null || configs.isEmpty()) {
                // 配置全空:如果之前有 VOIP,说明通话刚结束
                if (voipActive) {
                    Log.i(TAG, "voip: configs empty, VOIP ended");
                    voipActive = false;
                    tryResumeAfterVoip();
                }
                return;
            }
            // 检测 VOIP 通话状态:扫描 USAGE_VOICE_COMMUNICATION 的 started 配置
            boolean voipNow = false;
            // 找出当前真实在播(started)的音乐类配置
            String playingPkg = null;
            for (Object confObj : configs) {
                try {
                    Method getStateM = confObj.getClass().getMethod("getPlayerState");
                    int pstate = (Integer) getStateM.invoke(confObj);
                    boolean started = (pstate == 1 || pstate == 2); // AOSP=1,定制ROM=2
                    Object attrsObj = null;
                    try {
                        Method getAttrsM = confObj.getClass().getMethod("getAudioAttributes");
                        attrsObj = getAttrsM.invoke(confObj);
                    } catch (Exception e) {
                        // 拿不到属性时放行
                    }
                    int usage = -1;
                    if (attrsObj != null) {
                        try {
                            Method getUsageM = attrsObj.getClass().getMethod("getUsage");
                            usage = (Integer) getUsageM.invoke(attrsObj);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    // VOIP 检测:USAGE_VOICE_COMMUNICATION=2 + USAGE_VOICE_COMMUNICATION_SIGNALLING=3
                    if (started && (usage == 2 || usage == 3)) {
                        voipNow = true;
                    }
                    // XSFMediaCenter(ecarx.xsf.mediacenter)是系统媒体中心/EAS宿主,
                    // 常驻 MediaSession 且会随小卡片点击/系统事件产生 AudioTrack;
                    // 但它不是真实音源 App,不应触发自动切换。
                    Method getUidM = confObj.getClass().getMethod("getClientUid");
                    int uid = (Integer) getUidM.invoke(confObj);
                    String pkg = uidToPackage(uid);
                    if (isSystemMediaPackage(pkg)) {
                        if (started) {
                            Log.d(TAG, "auto-switch: skip system media package audio track: " + pkg);
                        }
                        continue;
                    }
                    if (!started) {
                        continue;
                    }
                    if (usage != 1) { // AudioAttributes.USAGE_MEDIA=1
                        continue; // 只关心音乐媒体播放,忽略导航/铃声/通知音等
                    }
                    // ★ 蓝牙例外 :A2DP sink 的 AudioTrack
                    // 常驻 started,暂停时仍可能报 started,会被误判为"新在播源"抢走
                    // 控制器。蓝牙只有 MediaSession 实时状态==PLAYING 才算真在播。
                    // (USB/蓝牙自动播放开关):开关关闭时不自动接管蓝牙——
                    // 蓝牙即使真在播也不自动切过去(开机/中途都不抢)。开启(默认)保持原行为。
                    if (PKG_BLUETOOTH.equals(pkg)
                            && (!isBluetoothReallyPlaying() || !isUsbAutoResumeEnabled())) {
                        Log.d(TAG, "auto-switch: bluetooth skip (not really playing or auto-play off)");
                        continue;
                    }
                    // (Bug#72C):USB 在播时蓝牙不参与自动接管——蓝牙是 A2DP
                    // 常驻被动源,暂停时 MediaSession 可能残留 PLAYING,USB 切歌空窗期
                    // 会被误判为"新在播源"切走(原 USB 闸门防护的根因)。USB 场景要切
                    // 走的只可能是用户主动打开的第三方 App(汽水/酷我/QQ 等),蓝牙只能手动选。
                    if (usbArmed && PKG_BLUETOOTH.equals(pkg)) {
                        Log.d(TAG, "auto-switch: usb armed, bluetooth not auto-takeover, skip");
                        continue;
                    }
                    // 排除 CarPlay 盒子——盒子只是信息源(切歌走注入),
                    // 不参与"新在播源"自动切换,防止盒子 AudioTrack 常驻 started 时
                    // 被误判为车机新音源,自动切到盒子(无切歌能力,方控落空)。
                    if (pkg != null && isCarplayBoxRealPkg(pkg)) {
                        continue;
                    }
                    if (pkg != null && !pkg.equals(PKG_SELF)
                            && !pkg.equals(controllerPkg)) {
                        // 跳过当前控制器自身(如蓝牙 A2DP sink AudioTrack 常驻 started):
                        // 找"新的真实在播源"时才不会先扫到当前控制器就短路,
                        // 导致打开 QQ 播放时蓝牙挡住切源(偶发漏切)。
                        playingPkg = pkg;
                        break;
                    }
                } catch (Exception e) {
                    // 单条配置解析失败跳过
                }
            }
            // VOIP 状态转换检测
            if (voipActive && !voipNow) {
                // VOIP 刚结束
                Log.i(TAG, "voip: call ended, try resume");
                voipActive = false;
                tryResumeAfterVoip();
            }
            voipActive = voipNow;
            if (playingPkg == null) {
                return;
            }
            // 变化检测:同音源切歌/状态抖动,不处理。
            // 但仅当"该源已是当前控制器"或"当前控制器自己真在播"时才跳过——
            // 若控制器已漂移到别处(如被手动/误切到蓝牙),即使 lastAudioPlayingPkg 相同
            // 也必须切回真实在播源(否则音源已变酷我/QQ,控制器还一直显示蓝牙,
            // 切歌也切到蓝牙)。
            if (playingPkg.equals(lastAudioPlayingPkg)
                    && (playingPkg.equals(controllerPkg) || isControllerReallyPlaying())) {
                return;
            }
            // ★ (Bug#72G):USB 激活后,激活前正在播的源(usbActivatePrevPkg)不是"新源"。
            // 用户刚切到 USB 时旧源 AudioTrack 可能还在 started(暂停命令异步生效/不响应焦点),
            // 会被当成"新在播源"auto-switch 切回。只拦"激活前就在播的同一个源",
            // 保留 72C 语义:用户打开/播放"新"App(≠usbActivatePrevPkg)仍能自动切走 USB。
            if (usbArmed && usbActivatePrevPkg != null && playingPkg.equals(usbActivatePrevPkg)) {
                Log.i(TAG, "auto-switch: usb armed, " + playingPkg + " was playing before USB, skip");
                return;
            }
            lastAudioPlayingPkg = playingPkg;
            // 已经是当前控制器 → 不用切
            if (playingPkg.equals(controllerPkg)) {
                return;
            }
            // 通话结束保护期:挂断后 5 秒内,蓝牙 A2DP sink 的 AudioTrack 常驻
            // (暂停时仍可能报 started),它会在通话结束后立即被误认为"新在播源"
            // 抢走控制器。保护期内除了恢复目标(通话前控制器)外一律不自动切。
            if (System.currentTimeMillis() < telecomEndProtectUntil) {
                Log.i(TAG, "auto-switch: " + playingPkg + " in telecom-end protect window, skip");
                return;
            }
            // 冷却:避免来回跳
            long now = System.currentTimeMillis();
            if (now - lastAutoSwitchTime < AUTO_SWITCH_COOLDOWN_MS) {
                Log.i(TAG, "auto-switch: " + playingPkg + " in cooldown, skip");
                return;
            }
            // 用户刚主动选择控制器时,不自动切换。
            // 场景:用户选回酷狗后点播放,酷狗 MediaSession 还没来得及 active,
            // 此时酷我若也在播,handlePlaybackConfigChanged 会误切到酷我,
            // 导致歌名显示酷我的而不是酷狗的。给用户选的控制器足够启动时间。
            // (Q1):保护窗口只尊重"用户选的源真在播"——若用户选的源
            // 没有真实在播(AudioTrack/蓝牙均未播),而 playingPkg 是真实新在播源,
            // 按"谁在播放控制器就是谁"放行切换,不再无条件卡 5 秒。
            if (controllerJustSelected) {
                if (isControllerReallyPlaying()) {
                    Log.i(TAG, "auto-switch: controllerJustSelected=true and current really playing, skip to "
                            + playingPkg);
                    return;
                }
                Log.i(TAG, "auto-switch: controllerJustSelected but current not really playing, allow switch to "
                        + playingPkg);
            }
            // 酷狗切歌保护窗口:切歌后3秒内 AudioTrack 空窗期不触发自动切源
            if (PKG_KUGOU.equals(controllerPkg)
                    && (System.currentTimeMillis() - lastKugouKeyEventTime) < 3000) {
                Log.i(TAG, "auto-switch: kugou switch protect window (3s), skip auto-switch to " + playingPkg);
                return;
            }
            // 确认该包名有活跃媒体会话(否则没法控制,也不该切)
            MediaController target = findControllerByPackage(playingPkg);
            if (target == null) {
                Log.i(TAG, "auto-switch: " + playingPkg + " has no active media session, skip");
                return;
            }
            // 双重判断 :AudioTrack started 不够,MediaSession 也必须是 PLAYING。
            // CarPlay 暂停后每隔约 8 秒创建一个新的音乐流 AudioTrack 并保持 started(僵尸行为),
            // 但 MediaSession state 已变为 PAUSED/NONE。此处过滤掉这类僵尸 AudioTrack,
            // 防止当前音源暂停时被误切到 CarPlay,同时避免误暂停当前控制器。
            if (!isSessionReallyPlaying(target)) {
                // 刚点播放时 AudioTrack 立刻 started,但 MediaSession state 异步更新有延迟。
                // 不直接放弃,延迟 1.5s 后重试一次(等网易云 IoT 等车机版 MediaSession 就绪),
                // 否则第一次检测太早被 skip 后再也不会重试,CarMedia 永远停在旧音源。
                Log.i(TAG, "auto-switch: " + playingPkg + " audio track started but MediaSession not PLAYING, schedule retry in 1.5s");
                autoSwitchRetryHandler.removeCallbacks(autoSwitchRetryRunnable);
                pendingAutoSwitchPkg = playingPkg;
                autoSwitchRetryHandler.postDelayed(autoSwitchRetryRunnable, 1500L);
                return;
            }
            Log.i(TAG, "auto-switch: new source playing: " + playingPkg + ", switching"
                    + (usbArmed ? " (away from USB, USB will be paused)" : ""));
            lastAutoSwitchTime = now;
            pauseCurrentController();
            selectController(target);
        } catch (Throwable t) {
            Log.w(TAG, "handlePlaybackConfigChanged error: " + t.getMessage());
        }
    }
 /** 通过 uid 反查包名。 */
    private String uidToPackage(int uid) {
        try {
            String pkg = getPackageManager().getNameForUid(uid);
            // 过滤无效包名。
            // 系统 UID(如 1000)的 getNameForUid 返回 "android.uid.system:1000",
            // 这不是一个真实的应用包名,但会被 getRealPlayingMusicPkg/handlePlaybackConfigChanged
            // 误认为是真实在播音源,导致自动切换到假音源、歌名显示异常、控制器乱跳。
            // 过滤规则:包含 ":"(UID 后缀格式)、以 "android.uid." 开头、
            // 或不是有效的 Java 包名(只含字母/数字/点/下划线)。
            if (pkg == null) return null;
            if (pkg.contains(":")) return null;
            if (pkg.startsWith("android.uid.")) return null;
            if (!pkg.matches("[a-zA-Z0-9_.]+")) return null;
            return pkg;
        } catch (Exception e) {
            return null;
        }
    }
 /** 在活跃媒体会话里按包名找控制器。 */
    private MediaController findControllerByPackage(String pkg) {
        try {
            if (pkg == null) {
                return null;
            }
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return null;
            }
            List<MediaController> sessions = null;
            try {
                ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
                sessions = msm.getActiveSessions(cn);
            } catch (Exception e) {
                sessions = null;
            }
            if (sessions == null || sessions.isEmpty()) {
                try {
                    sessions = msm.getActiveSessions(null);
                } catch (Exception e2) {
                    sessions = null;
                }
            }
            if (sessions == null) {
                return null;
            }
            for (MediaController c : sessions) {
                if (pkg.equals(c.getPackageName())) {
                    return c;
                }
            }
            // 兜底:全量会话(含 active=false 的暂停会话,如暂停中的酷我),
            // 保证暂停的酷我也能被重新选为控制器/恢复播放。
            for (MediaController c : getKnownControllers()) {
                if (pkg.equals(c.getPackageName())) {
                    return c;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findControllerByPackage error: " + t.getMessage());
        }
        return null;
    }
 /**
 * 查找系统媒体中心(ecarx.xsf.mediacenter)的活跃会话控制器。
 * 用于手机互联场景:CarPlay/HiCar/CarLife/Android Auto 等 app 自己不创建 MediaSession,
 * 播放控制全走 mediacenter 代理。无真实第三方音源时 fallback 到它。
 * 注意:这里用 getActiveSessions(null) 直接查,不走 isHiddenController 过滤,
 * 因为 mediacenter 在 UI 列表隐藏但仍可作为控制目标。
 */
    private MediaController findActiveMediacenterController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) return null;
            List<MediaController> all = msm.getActiveSessions(null);
            if (all != null) {
                for (MediaController c : all) {
                    if (c != null && "ecarx.xsf.mediacenter".equals(c.getPackageName())) {
                        return c;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findActiveMediacenterController error: " + t.getMessage());
        }
        return null;
    }
 /**
 * 检测 HiCar 是否已连接(读取系统属性 sys.hicar.connected)。
 * HiCarHMI(com.ecarx.hmi) smali 确认使用该属性标记连接状态。
 * 用反射调用 SystemProperties.get(隐藏 API),避免普通应用编译失败。
 * @return true=HiCar 已连接(属性值为 "1" 或 "true")
 */
    private boolean isHicarConnected() {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method get = clazz.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, HICAR_CONNECTED_PROP, "0");
            return "1".equals(val) || "true".equalsIgnoreCase(val);
        } catch (Throwable t) {
            Log.w(TAG, "isHicarConnected failed: " + t.getMessage());
            return false;
        }
    }
 /**
 * 检测 CarLife 车端应用(com.muyetuge.carlifevehicle)是否有媒体会话(连接手机中)。
 * 新增:这台车的 CarLife 连接手机时**自建 MediaSession**(真车日志 20:20:24
 * "【媒体会话】onConnectionDetached: MediaSession 已释放"确认,连接时创建/断开时释放),
 * 与 HiCar(不自建会话、走 mediacenter 代理)不同,应直接作为普通第三方音源控制。
 * 活跃会话查不到时(可能 active=false)再用全量会话 getKnownControllers() 兜底。
 * @return true=CarLife 已连接且存在媒体会话
 */
    private boolean isCarlifeSessionActive() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return false;
            }
            // 1) 活跃会话
            List<MediaController> active = null;
            try {
                active = msm.getActiveSessions(null);
            } catch (Throwable t) {
                active = null;
            }
            if (active != null) {
                for (MediaController c : active) {
                    if (c != null && isCarlifePkg(c.getPackageName())) {
                        return true;
                    }
                }
            }
            // 2) 全量会话兜底(active=false 的会话,如连接中但未播放)
            for (MediaController c : getKnownControllers()) {
                if (c != null && isCarlifePkg(c.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "isCarlifeSessionActive failed: " + t.getMessage());
        }
        return false;
    }
 /**
 * 通用切源检测:从所有活跃媒体会话中找出"真实在播"(AudioTrack started)
 * 且不是 carmedia 自己的控制器。不针对任何特定 App--任何音乐源在播都能识别。
 * 多个在播时取第一个;没有则返回 null。
 * excludePkg 非空时跳过该包名(用于找"不同于当前控制器"的新源:当前控制器自身
 * 常驻 started 的 AudioTrack(如蓝牙 A2DP sink)会顶掉对其他真实在播源的检测,
 * 导致打开 QQ 播放时蓝牙挡住切源、偶发漏切)。
 */
    private MediaController findReallyPlayingController() {
        return findReallyPlayingController(null);
    }
    private MediaController findReallyPlayingController(String excludePkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService("media_session");
            if (msm == null) {
                return null;
            }
            List<MediaController> sessions = null;
            try {
                ComponentName cn = new ComponentName(this, MediaNotificationListener.class);
                sessions = msm.getActiveSessions(cn);
            } catch (Exception e) {
                sessions = null;
            }
            if (sessions == null || sessions.isEmpty()) {
                try {
                    sessions = msm.getActiveSessions(null);
                } catch (Exception e2) {
                    sessions = null;
                }
            }
            if (sessions == null) {
                return null;
            }
            for (MediaController c : sessions) {
                if (c == null) {
                    continue;
                }
                String pkg = c.getPackageName();
                // 双重判断 :AudioTrack started + MediaSession PLAYING。
                // 仅 AudioTrack started 不够——CarPlay 暂停后 AudioTrack 仍常驻 started,
                // 会被误判为"真实在播"抢走控制器。必须同时 MediaSession state==PLAYING。
                if (pkg != null && !pkg.equals(PKG_SELF)
                        && (excludePkg == null || !excludePkg.equals(pkg))
                        && isAudioReallyPlaying(pkg)
                        && isSessionReallyPlaying(c)) {
                    return c;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findReallyPlayingController error: " + t.getMessage());
        }
        return null;
    }
 /**
 * 播放中但没有歌名时,主动刷新元数据。
 * 酷狗等播放器 MediaSession inactive 时收不到 onMetadataChanged 回调,
 * UI 一直显示"等待音乐播放"。通过此方法主动读取 MediaController 的元数据。
 * 刷新成功(currentTitle 不为空)后停止刷新,避免不必要的轮询开销。
 */
    private void refreshMetadataIfMissing() {
        try {
            // 已经有歌名了,不需要刷新
            if (currentTitle != null && !currentTitle.isEmpty()) {
                return;
            }
            Log.d(TAG, "refreshMetadataIfMissing: currentTitle null, trying to refresh for " + controllerPkg);
            // 1. 如果当前 mediaController 可用,直接读取
            if (mediaController != null && !controllerStale) {
                try {
                    MediaMetadata meta = mediaController.getMetadata();
                    if (meta != null) {
                        handleMetadata(meta);
                        Log.i(TAG, "refreshMetadataIfMissing: loaded metadata from current controller " + controllerPkg);
                    }
                    PlaybackState ps = mediaController.getPlaybackState();
                    if (ps != null) {
                        updatePlaybackStatus(ps);
                    }
                    if (currentTitle != null && !currentTitle.isEmpty()) {
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refreshMetadataIfMissing: current controller error: " + t.getMessage());
                }
            }
            // 2. 尝试重新获取 MediaController(包括 inactive 的会话,如暂停后的酷狗)
            MediaController ctrl = findControllerByPackage(controllerPkg);
            if (ctrl != null) {
                try {
                    // 先更新当前控制器,再读取元数据!
                    // 否则 handleMetadata() 内部调用 pushMetadataToCard() 时,
                    // mediaController 还是 null,导致 "no controller after refresh, skip",
                    // UI 一直显示"等待音乐播放"。
                    if (mediaController == null || controllerStale
                            || (mediaController != null && !mediaController.getPackageName().equals(controllerPkg))) {
                        mediaController = ctrl;
                        controllerStale = false;
                        try {
                            ctrl.registerCallback(controllerCallback);
                        } catch (Exception e) {
                            Log.w(TAG, "refreshMetadataIfMissing: register callback failed: " + e.getMessage());
                        }
                        Log.i(TAG, "refreshMetadataIfMissing: updated mediaController to " + controllerPkg);
                    }
                    MediaMetadata meta = ctrl.getMetadata();
                    if (meta != null) {
                        handleMetadata(meta);
                        Log.i(TAG, "refreshMetadataIfMissing: loaded metadata from reconnected controller " + controllerPkg);
                    }
                    PlaybackState ps = ctrl.getPlaybackState();
                    if (ps != null) {
                        updatePlaybackStatus(ps);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refreshMetadataIfMissing: reconnected controller error: " + t.getMessage());
                }
            } else {
                Log.d(TAG, "refreshMetadataIfMissing: no controller found for " + controllerPkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshMetadataIfMissing error: " + t.getMessage());
        }
    }

 /**
 * 强制刷新元数据(不检查 currentTitle 是否为空)。
 * 用于酷狗切歌后主动刷新元数据。
 * refreshMetadataIfMissing() 只在 currentTitle 为空时刷新,但切歌后
 * currentTitle 还是上一首歌(不为空),导致不会主动刷新。
 * 本方法临时清空 currentTitle 后调用 refreshMetadataIfMissing(),
 * 若刷新失败则恢复旧值,避免标题永久为空。
 */
    private void forceRefreshMetadata() {
        String savedTitle = currentTitle;
        String savedArtist = currentArtist;
        currentTitle = "";
        try {
            refreshMetadataIfMissing();
        } catch (Throwable t) {
            Log.w(TAG, "forceRefreshMetadata error: " + t.getMessage());
        } finally {
            // 刷新失败(currentTitle仍为空)时恢复旧值
            if (currentTitle == null || currentTitle.isEmpty()) {
                currentTitle = savedTitle;
                currentArtist = savedArtist;
            }
        }
    }

 /**
 * 获取当前真实在播的音乐播放器包名(AudioTrack started + USAGE_MEDIA)。
 * 用于 dispatchMediaAction 中当前控制器不在播时自动切换到真实在播源。
 * 过滤系统媒体中心/蓝牙常驻等非真实音源。
 */
    private String getRealPlayingMusicPkg() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            Method m = AudioManager.class.getMethod("getActivePlaybackConfigurations");
            Object result = m.invoke(am);
            if (!(result instanceof List<?>)) return null;
            List<?> configs = (List<?>) result;
            int playerStartedState = 1;
            try {
                java.lang.reflect.Field f = Class.forName("android.media.AudioPlaybackConfiguration").getField("PLAYER_STATE_STARTED");
                playerStartedState = f.getInt(null);
            } catch (Exception e) { }
            for (Object conf : configs) {
                try {
                    Method getState = conf.getClass().getMethod("getPlayerState");
                    int pstate = (Integer) getState.invoke(conf);
                    if (pstate != playerStartedState) continue;
                    // 过滤非媒体音频
                    try {
                        Object attrs = conf.getClass().getMethod("getAudioAttributes").invoke(conf);
                        Method getUsage = attrs.getClass().getMethod("getUsage");
                        int usage = (Integer) getUsage.invoke(attrs);
                        if (usage != 1) continue; // USAGE_MEDIA=1
                    } catch (Exception e) { }
                    Method getUid = conf.getClass().getMethod("getClientUid");
                    int uid = (Integer) getUid.invoke(conf);
                    String pkg = uidToPackage(uid);
                    if (pkg == null) continue;
                    // 过滤系统媒体中心/EAS宿主
                    if (isSystemMediaPackage(pkg)) continue;
                    // 过滤 CarMedia 自己
                    if (PKG_SELF.equals(pkg)) continue;
                    // 过滤 CarPlay 盒子(com.carletter.ceb/com.aufn.carplay)——
                    // 盒子只是信息源(切歌走 CarPlay 注入 87/88),不是车机真实音源,
                    // 不进"谁在播"自动切换候选,避免 auto-switch 误切到盒子。
                    if (isCarplayBoxRealPkg(pkg)) continue;
                    // (Bug#77 补充):过滤 CarLife 投屏代理(com.muyetuge.carlifevehicle
                    // /com.baidu.carlifevehicle)——与 CarPlay 盒子同性质:它是投屏代理会话,
                    // AudioTrack 常驻 started 但**不响应 transportControls 切歌**(Bug#77 已确认,
                    // CarLife 切歌必须走 dispatchMediaKeyToSession 注入 87/88,且仅在用户主动
                    // 选中 CarLife 时走 isCarlifePkg(controllerPkg) 直通分支,不依赖此"谁在播"判定)。
                    // 若不排除,手机连着 CarLife 时其残留 AudioTrack 会被 isOtherSourceReallyPlaying()
                    // 误判为"另一源真在播",USB 在播时方向盘切歌被拦(key NOT routed to USB),
                    // 命令又发给残留 mediaController=CarLife transportControls 而无效 → USB 切歌无反应
                    // (真车 22:30 日志 27 次 PREV 全被拦确认)。排除后 USB 分支正常接管。
                    if (isCarlifePkg(pkg)) continue;
                    // 蓝牙例外:蓝牙 A2DP sink 常驻 started,需 MediaSession 真在播才算
                    if (PKG_BLUETOOTH.equals(pkg) && !isBluetoothReallyPlaying()) continue;
                    return pkg;
                } catch (Exception e) { }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRealPlayingMusicPkg failed: " + t.getMessage());
        }
        return null;
    }

 /**
 * 判断指定包名是否真的有音频在播放(AudioTrack started)。
 * 反射 AudioManager.getActivePlaybackConfigurations() 遍历。
 * XSFMediaCenter(ecarx.xsf.mediacenter)是系统媒体中心/EAS宿主,
 * 常驻 AudioTrack 但非真实音源,直接视为不在播(与 handlePlaybackConfigChanged 黑名单一致)。
 */
    private boolean isAudioReallyPlaying(String pkg) {
        if (pkg == null) {
            return false;
        }
        // 黑名单:系统媒体中心/EAS宿主不是真实播放器,常驻 AudioTrack 会误导自动选源。
        // 含 XCMedia2(com.ecarx.multimedia):语音唤醒后注册 EAS 焦点客户端并播放,
        // 但它是系统多媒体,不应被 CarMedia 当作第三方音源选为控制器。
        if (isSystemMediaPackage(pkg)) {
            return false;
        }
        // ★ 蓝牙例外 :A2DP sink 的 AudioTrack 常驻 started,
        // 暂停/停止时仍可能报 started,isAudioReallyPlaying 对蓝牙不可靠。
        // 一律以蓝牙 MediaSession 实时状态为准:state==3(PLAYING)才算真在播。
        // 否则蓝牙会一直顶掉真实在播的酷我/QQ,导致控制器卡在蓝牙上
        // (表现为:音源已是酷我/QQ,控制器还显示蓝牙)。
        if (PKG_BLUETOOTH.equals(pkg)) {
            return isBluetoothReallyPlaying();
        }
        // 反射获取 PLAYER_STATE_STARTED 真实值(车机ROM=2,标准AOSP=1),
        // 避免硬编码 pstate==2||pstate==1 把 AOSP 的 PAUSED(=2) 误判为播放,
        // 导致酷狗暂停时 CarMedia 仍显示播放中(状态按钮不切换)。
        int playerStartedState = 1;
        try {
            java.lang.reflect.Field f = Class.forName("android.media.AudioPlaybackConfiguration").getField("PLAYER_STATE_STARTED");
            playerStartedState = f.getInt(null);
        } catch (Exception e) {
            // fallback AOSP default
        }
        boolean mechanismFailed = false;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                mechanismFailed = true;
            } else {
                Method m = AudioManager.class.getMethod("getActivePlaybackConfigurations");
                Object result = m.invoke(am);
                if (!(result instanceof List<?>)) {
                    mechanismFailed = true;
                } else {
                    int targetUid = getPackageUid(pkg);
                    if (targetUid < 0) {
                        mechanismFailed = true;
                    } else {
                        for (Object conf : (List<?>) result) {
                            try {
                                Method getUid = conf.getClass().getMethod("getClientUid");
                                Method getState = conf.getClass().getMethod("getPlayerState");
                                int uid = (Integer) getUid.invoke(conf);
                                int pstate = (Integer) getState.invoke(conf);
                                if (uid == targetUid) {

                                    // 只认 PLAYER_STATE_STARTED(ROM=2,AOSP=1 自动适配),
                                    // 之前 pstate==2||pstate==1 会把 AOSP 的 PAUSED(=2) 误判为播放
                                    if (pstate == playerStartedState) {
                                        return true;
                                    }
                                }
                            } catch (Exception e) {
                                // 单条配置反射失败跳过
                            }
                        }
                        // 机制正常且遍历完:确实没有 started 配置 → 不在播
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isAudioReallyPlaying failed: " + e.getMessage());
            mechanismFailed = true;
        }
        // (Q7):反射检测机制不可用(部分 ROM/API 差异、权限问题)时,
        // 退化为 MediaSession state==PLAYING 判断,避免"真实在播检测恒 false"
        // 导致自动切源全部失效(控制器卡在旧源)。注意不能调 isSessionReallyPlaying——
        // 它对酷狗分支会递归调回本方法,造成死循环。
        if (mechanismFailed) {
            try {
                MediaController c = findControllerByPackage(pkg);
                if (c != null) {
                    PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                        Log.i(TAG, "isAudioReallyPlaying: mechanism failed, fallback MediaSession PLAYING for " + pkg);
                        return true;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "isAudioReallyPlaying fallback error: " + t.getMessage());
            }
        }
        return false;
    }
    private void pushSourceSwitch() {
        try {
            // 总开关:未勾选"自动仪表盘/小卡片推送"一律不推(防调用方漏判)
            if (!isDimPushEnabled()) {
                return;
            }
            // 切到蓝牙且未播放时,不推源信息(同 pushMetadataToCard 抑制逻辑)。
            if (suppressBtPushUntilPlay) {
                Log.d(TAG, "pushSourceSwitch: bt pending play, skip");
                return;
            }
            // 源切换:推当前真实状态(EAS 语义 0/1),不硬编码播放。
            int audioState = getAudioRealPlayState();
            int easState = (audioState == STATE_PLAYING) ? EAS_STATUS_PLAYING : EAS_STATUS_PAUSED;
            // 与主界面显示一致:有歌词时,EAS 标题推歌词行、歌手推"歌名 - 歌手"
            // (主界面 refreshUiState:有歌词 tvPrimaryInfo=歌词、tvSecondaryInfo=歌名 - 歌手)
            String[] lyricPair = buildLyricPushPair();
            MediaCenterBridge.get().pushMetadata(lyricPair[0], lyricPair[1], null,
                    0L, easState, true, controllerPkg, 0L);
        } catch (Throwable t) {
            Log.w(TAG, ">>> pushSourceSwitch failed: " + t.getMessage());
        }
    }
    // ================= P2: 媒体控制 =================
    public void sendMediaAction(String action) {
        // armed 残留但 USB 实际失效(拔出事件丢失)→ 复位,放行外部控制器(蓝牙)
        resetUsbRouteIfDead("sendMediaAction");
        // USB 音源激活时走 USB 播放器控制
        if (usbRouteArmed && usbSourceController != null) {
            if ("playpause".equals(action)) usbSourceController.playPause();
            else if ("next".equals(action)) usbSourceController.next();
            else if ("prev".equals(action)) usbSourceController.prev();
            return;
        }
        // ghost 控制器处理:酷我等暂停态会话 active=false,拿不到 MediaController,
        // 用 dispatchMediaKeyEvent 发 media key 激活播放。
        if (ghostControllerPkg != null && (mediaController == null
                || (mediaController != null && ghostControllerPkg.equals(controllerPkg)
                    && !ghostControllerPkg.equals(mediaController.getPackageName())))) {
            Log.i(TAG, "sendMediaAction: ghost controller " + ghostControllerPkg
                    + " action=" + action + ", dispatching media key"
                    + " (mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null") + ")");
            dispatchMediaKeyForGhost(action);
            return;
        }
        // stale/无控制器时也要真正发出命令:先刷新拿到控制器,再执行(不能静默 return)。
        // 旧逻辑 stale 时只 refreshController() 就 return,用户点播放按钮被静默丢弃
        // (XCMedia2/系统媒体频繁抢焦点导致会话抖动 → controllerStale=true → 点了没反应)。
        if (mediaController == null || controllerStale) {
            refreshController();
            if (mediaController == null) {
                Log.w(TAG, "sendMediaAction: no controller after refresh, action=" + action + " dropped");
                return;
            }
        }
        try {
            if ("playpause".equals(action)) {
                Log.i(TAG, "sendMediaAction: playpause -> controller=" + controllerPkg
                        + " stale=" + controllerStale);
                sendPlayPause();
            } else if ("next".equals(action)) {
                sendNext();
            } else if ("prev".equals(action)) {
                sendPrevious();
            }
        } catch (Exception e) {
            Log.w(TAG, "sendMediaAction error: " + e.getMessage());
            refreshController();
        }
    }

 /** USB 播放时更新 MediaSession 的 metadata 和 playbackState（只动 metadata/state，不动 flags/callback）。 */
    private void updateUsbMediaSession() {
        if (mediaSession == null || usbSourceController == null) return;
        try {
            UsbMusicItem item = usbSourceController.getCurrentItem();
            if (item != null) {
                MediaMetadata.Builder mb = new MediaMetadata.Builder();
                mb.putString(MediaMetadata.METADATA_KEY_TITLE, item.title != null ? item.title : "未知歌曲");
                mb.putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist != null ? item.artist : "未知歌手");
                mb.putString(MediaMetadata.METADATA_KEY_ALBUM, item.album != null ? item.album : "");
                mb.putLong(MediaMetadata.METADATA_KEY_DURATION, item.duration);
                mediaSession.setMetadata(mb.build());
            }
            int state = usbSourceController.getPlayState();
            int pbState = PlaybackState.STATE_NONE;
            if (state == UsbMusicPlayer.STATE_PLAYING) pbState = PlaybackState.STATE_PLAYING;
            else if (state == UsbMusicPlayer.STATE_PAUSED) pbState = PlaybackState.STATE_PAUSED;
            else if (state == UsbMusicPlayer.STATE_COMPLETED) pbState = PlaybackState.STATE_NONE;
            PlaybackState.Builder pb = new PlaybackState.Builder()
                    .setState(pbState, usbSourceController.getPosition(), 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO);
            mediaSession.setPlaybackState(pb.build());
        } catch (Throwable t) {
            Log.w(TAG, "updateUsbMediaSession error: " + t.getMessage());
        }
    }

    // ================= USB 音源 public 接口（供 JsBridge 调用） =================
    public void activateUsbSource() {
        // ★ (Bug#72G):先记录"切 USB 前正在播的源",供 handlePlaybackConfigChanged
        // 判定"不是新源、不自动切回";必须在暂停前抓取(AudioTrack 停后 isControllerReallyPlaying 变 false)。
        usbActivatePrevPkg = (controllerPkg != null
                && !UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)
                && isControllerReallyPlaying()) ? controllerPkg : null;
        // ★ (Bug#72G):切到 USB 先暂停上一个真实音源(mediaController 仍指向旧源)。
        // 旧源 AudioTrack 常驻 started + MediaSession 残留 PLAYING 会被 auto-switch 判定为
        // "新在播源"切回("切 USB 页面又被自动切回音源页面"根因)");同时武装两个保护窗
        // (controllerJustSelected 5s / lastUsbKeyEventTime 3s),与 selectControllerInner 用户选源保护对齐。
        pauseCurrentController();
        usbRouteArmed = true;
        currentSourceType = SOURCE_TYPE_USB; // (A1)
        // ★ (Bug: 默认主题切 USB 歌词不重置/不更新)
        // 与 selectControllerInner 切其他音源对齐:重置歌词状态。
        // 原实现只武装路由/切 controllerPkg,不清歌词——旧源(QQ/网易云/落雪等)的
        // cloudLyricFetcher 缓存、歌词推送去重(lastPushedLyricLine/lastUiLyricLine/
        // lastPushedLyricForTitle)与 USB 侧缓存(usbEmbeddedLyricLines/lastUsbLyricSongKey/
        // usbEmbeddedFallback)全部残留 → 切到 USB 后歌词区仍显示旧源歌词、新歌不刷新。
        try {
            if (cloudLyricFetcher != null) {
                cloudLyricFetcher.clearBroadcastLrc();
                cloudLyricFetcher.clearLoadedLrc();
            }
        } catch (Throwable t) {
            Log.w(TAG, "activateUsbSource clear cloud lyric err: " + t.getMessage());
        }
        // ★ (Bug: 切 USB 不清上一个音源的歌词接口状态):
        // 与 selectControllerInner 一致,重置全部歌词抓取器接口状态,
        // 旧源(QQ/网易云等)的接口灯回空闲,USB 实际歌词源抓取后再点亮。
        // ★ 修正:此处**不**清空 usbEmbeddedLyricLines/ushEmbeddedTimestamps/
        // usbEmbeddedFallback——切 USB 瞬间清空内嵌歌词会造成 getCurrentLyricLine 空窗,
        // 默认主题 hero 在「歌词行 / 歌名+歌手」间来回闪(hasLyric 反复翻转)。
        // 只重置 lastUsbLyricSongKey(去重),让 triggerUsbLyricFetch 按当前 USB 歌
        // 重新 loadEmbeddedLyric(内部自会清空并重建),无空窗。
        lastUsbLyricSongKey = "";
        // 歌词推送/UI 行级去重重置:旧源歌词不残留,新歌词(即使同文本)也能立即上屏
        lastPushedLyricLine = null;
        lastUiLyricLine = null;
        lastPushedLyricForTitle = null;
        // ★ (Bug: 切 USB 不清上一个音源的歌词接口状态):
        // 与 selectControllerInner 一致,重置全部歌词抓取器接口状态,
        // 旧源(QQ/网易云等)的接口灯回空闲,USB 实际歌词源抓取后再点亮。
        resetAllLyricApiStatus();
        // (Bug: USB 音源 controllerPkg 卡蓝牙 → 歌词被当蓝牙丢弃 + EAS 被
        // suppressBtPushUntilPlay 拦截 + 不抢 EAS 焦点)。USB 成为在播音源时,把 controllerPkg
        // 真正切到 USB(之前只在 pushMetadataToCard USB 分支被动赋值,开机窗口被 suppress 挡住
        // 永不执行) + 清掉"切蓝牙未播放"推送抑制 + 同步 EAS bridge 的 isBluetoothMode。
        if (!UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)) {
            controllerPkg = UsbSourceController.SOURCE_PKG_USB;
            MediaCenterBridge.setControllerPkg(controllerPkg);
        }
        suppressBtPushUntilPlay = false;
        controllerJustSelected = true;
        lastUsbKeyEventTime = System.currentTimeMillis();
        if (lyricPollHandler != null) {
            lyricPollHandler.removeCallbacks(resetControllerJustSelectedRunnable);
            lyricPollHandler.postDelayed(resetControllerJustSelectedRunnable, 5000);
        }
        if (usbSourceController != null) {
            usbSourceController.activate();
        }
        // ★ 切 USB 后立即触发歌词抓取与全量推送。
        // 不依赖 onStateChanged 时序(扫描/自动播放完成前 currentItem 可能为 null,
        // triggerUsbLyricFetch 内部自会跳过;有当前歌曲则立即按重置后的缓存重抓),
        // 并强制 UI/EAS 刷新清掉旧源残留歌词。
        triggerUsbLyricFetch();
        requestUsbFullPush();
        // ★ (Bug: USB 播放时桌面小组件不更新 / 残留上一首歌信息):
        // lycicPollRunnable 是 CarMediaWidget.updateAll 的唯一周期驱动(每 800ms)。
        // 激活 USB 时会 pauseCurrentController() 暂停上一个真实音源,若其走
        // pushStoppedToCard() 会 stopLyricPoll();而 USB 自身(triggerUsbLyricFetch /
        // pushMetadataToCard)走的是独立定时/回调,不会重启这个轮询 → 轮询停掉后
        // 桌面小组件永远停在上一首歌/旧信息,只有点字号按钮(WIDGET_LYRIC_DEC/INC
        // 或 ACTION_WIDGET_UPDATE)强制 updateAll 才会刷新。
        // 修复:激活 USB 后显式重启轮询,让 updateAll 持续刷新桌面小组件(歌名/歌词/状态)。
        startLyricPoll();
        Log.i(TAG, "USB source activated");
    }

    public void deactivateUsbSource() {
        usbRouteArmed = false;
        usbActivatePrevPkg = null; // (Bug#72G):切走 USB 后不再当作"激活前在播源"
        currentSourceType = SOURCE_TYPE_NONE; // (A1):由后续控制器选择再设置
        if (usbSourceController != null) {
            usbSourceController.deactivate();
        }
        // v2:用户手动切走 USB 时清 controllerPkg 残留(USB 播放分流 :4134 改过,
        // 旧版不恢复)。真车 logcat 确认:deactivated 后 handleMetadata 持续
        // "ignore metadata from com.android.bluetooth (authoritative is ...usb)" →
        // 蓝牙页面显示 USB 旧歌。
        if (UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg)) {
            controllerPkg = null;
            Log.i(TAG, "deactivateUsbSource: clear residual controllerPkg=USB");
        }
        Log.i(TAG, "USB source deactivated");
    }

    public boolean isUsbRouteArmed() { return usbRouteArmed; }

 /**
 * 需求:U 盘是否已挂载(不要求 USB 音源已激活)。供 MainActivity 判断
 * 是否在控制器列表常驻显示 "USB音乐" 条目——挂载即显示,无需切晴空主题。
 */
    public boolean isUsbMounted() {
        return usbSourceController != null && usbSourceController.isUsbMounted();
    }

 /**
 * USB 路由是否已"实际失效"——armed 残留但播放器 IDLE 且 U 盘已拔/无列表。
 * 真车拔出事件偶发丢失 → usbRouteArmed 残留 true → 蓝牙被劫持:播放/方控/标题全走
 * USB 分支、控制器扫描跳过蓝牙(蓝牙 tab 弹层空),表现"插上 USB 后蓝牙不行、拔了也不行"。
 * 满足此条件时调用方应复位 armed 并放行外部控制器。
 */
    private boolean isUsbRouteEffectivelyDead() {
        if (!usbRouteArmed || usbSourceController == null) return false;
        try {
            // ★ (Bug#72G):扫描中列表未就绪不算失效——否则切到 USB 的扫描窗口内
            // (IDLE + musicCount=0) 按键/媒体操作/控制器刷新会把 usbRouteArmed 误复位,
            // 表现"切 USB 又被切回音源页面"。
            if (usbSourceController.isScanning()) return false;
            if (usbSourceController.getPlayState() != UsbMusicPlayer.STATE_IDLE) return false;
            if (usbSourceController.getMusicCount() > 0 && usbSourceController.isUsbMounted()) {
                return false; // 有列表且 U 盘仍挂载:USB 可用(如刚激活未选歌),不视为失效
            }
        } catch (Throwable t) {
            return true; // 状态读取异常按失效处理,放行外部
        }
        return true;
    }

 /** armed 残留且 USB 实际失效时复位(供播放/方控/控制器扫描等关键路径调用)。 */
    private void resetUsbRouteIfDead(String from) {
        if (isUsbRouteEffectivelyDead()) {
            usbRouteArmed = false;
            currentSourceType = SOURCE_TYPE_NONE;
            Log.i(TAG, from + ": usb armed but effectively dead, reset usbRouteArmed");
        }
    }

 /** 卡死修复：USB 音源是否正在真实播放（供 MainActivity 轮询跳闸）。 */
    public boolean isUsbPlaying() {
        return usbRouteArmed && usbSourceController != null
                && usbSourceController.getPlayState() == UsbMusicPlayer.STATE_PLAYING;
    }

 /** 卡死加固：USB 音源是否正在扫描（含未激活的 warmup 扫描）。 */
    public boolean isUsbScanning() {
        return usbSourceController != null && usbSourceController.isScanning();
    }

    // USB 列表 JSON 缓存——getUsbMusicListJson 由 JS 切 USB tab 同步桥接调用,
    // 每次全量序列化 298 首 JSONObject 在真车弱 SoC 上主线程阻塞(来回切蓝牙/USB 卡顿根因之一)。
    // 列表引用 + 数量 + fav 集合 hash 不变则直接返回缓存串。
    private String usbListJsonCache;
    private int usbListJsonRef = -1;
    private int usbListJsonSize = -1;
    private int usbListJsonFavHash = 0;
    public String getUsbMusicListJson() {
        if (usbSourceController == null) return "[]";
        try {
            java.util.List<UsbMusicItem> list = usbSourceController.getMusicList();
            java.util.Set<String> favs = usbSourceController.getFavPaths();
            int ref = System.identityHashCode(list);
            int favHash = favs == null ? 0 : favs.hashCode();
            if (usbListJsonCache != null && ref == usbListJsonRef
                    && list.size() == usbListJsonSize && favHash == usbListJsonFavHash) {
                return usbListJsonCache;
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (UsbMusicItem item : list) {
                org.json.JSONObject o = item.toJson();
                o.put("fav", favs != null && favs.contains(item.path));
                arr.put(o);
            }
            String s = arr.toString();
            usbListJsonCache = s;
            usbListJsonRef = ref;
            usbListJsonSize = list.size();
            usbListJsonFavHash = favHash;
            return s;
        } catch (Throwable t) { return "[]"; }
    }

    public void playUsbMusic(int index) {
        playUsbMusic(index, false);
    }

 /** favMode=true 表示从收藏列表点歌——播放器进入收藏序列模式,
 * next/prev/自动下一首按收藏列表顺序切。 */
    public void playUsbMusic(int index, boolean favMode) {
        if (usbSourceController == null) return;
        // favMode=false 也必须明确退出收藏序列模式——
        // 否则收藏 tab 点过歌后 favMode 残留,歌曲 tab 点歌/方向盘切歌仍按收藏列表切(真车反馈)
        usbSourceController.setFavMode(favMode);
        usbSourceController.play(index);
    }

    public void usbPlayPause() {
        if (usbSourceController != null) usbSourceController.playPause();
    }

    public void usbNext() {
        if (usbSourceController != null) usbSourceController.next();
    }

    public void usbPrev() {
        if (usbSourceController != null) usbSourceController.prev();
    }

    public void usbSeekTo(long pos) {
        if (usbSourceController != null) usbSourceController.seekTo(pos);
    }

 /**
 * Unified seek entry (UI seekbar click/drag + EAS SEEK command).
 * Routing: USB -> built-in player; Kuwo car -> AIDL seek(0xa, verified);
 * standard MediaSession sources (QQ/Netease/Soda/Luoxue...) -> always transportControls.seekTo
 * (Bug#Q3:不再按 ACTION_SEEK_TO 硬拒——QQ 部分歌曲 actions 不声明 seek 但实际可拖);
 * Kugou / CarPlay / unknown -> unsupported (returns false).
 * After a successful seek the local accumulators are calibrated immediately so the
 * progress bar does not snap back before the source callback arrives.
 */
    public boolean seekTo(long posMs) {
        try {
            if (posMs < 0) {
                posMs = 0;
            }
            // USB built-in player
            if (usbRouteArmed && usbSourceController != null) {
                long usbDur = usbSourceController.getDuration();
                if (usbDur > 3000 && posMs > usbDur - 3000) {
                    posMs = usbDur - 3000;
                }
                usbSourceController.seekTo(posMs);
                onSeekLocalCalibrate(posMs);
                return true;
            }
            // CarPlay proxy: media keys only, no seek channel
            if (isCarplayBoxSelected() || PKG_CARPLAY_BOX.equals(controllerPkg)) {
                Log.d(TAG, "seekTo: CarPlay unsupported");
                notifySeekUnsupported();
                return false;
            }
            // Kugou car edition: verified all 4 channels have no seek
            // (AutoSdk action table, startService commands, MediaSession has no setCallback, NIO unused)
            if (PKG_KUGOU.equals(controllerPkg)) {
                Log.d(TAG, "seekTo: Kugou unsupported");
                notifySeekUnsupported();
                return false;
            }
            // Kuwo car edition: AIDL seek(0xa)
            if (isKuwoPkg(controllerPkg) && kuwoAidlClient != null && kuwoAidlClient.isAvailable()) {
                boolean ok = kuwoAidlClient.seek(posMs);
                if (ok) {
                    onSeekLocalCalibrate(posMs);
                } else {
                    notifySeekUnsupported();
                }
                return ok;
            }
            // Standard MediaSession sources (QQ/Netease/Soda/Luoxue 等)
            if (mediaController == null || controllerStale) {
                refreshController();
            }
            if (mediaController == null) {
                notifySeekUnsupported();
                return false;
            }
            // Bug#Q3：取消 ACTION_SEEK_TO 硬性拒绝。
            // QQ 音乐部分歌曲显示"不支持拖动进度条"、部分又可以，模拟器复现不出——
            // 根因是标准 MediaSession 的 actions 按歌/按播放状态动态变化，部分歌曲（或瞬态）
            // 不声明 ACTION_SEEK_TO，但实际支持 seek。一律尝试 transportControls.seekTo：
            // 真正不支持的歌曲（如电台/直播）会被应用忽略（位置不动），不再误报"不支持拖动进度"。
            // CarPlay/酷狗等确无 seek 通道的音源已在上方单独拦截。
            if (playbackDuration > 3000 && posMs > playbackDuration - 3000) {
                posMs = playbackDuration - 3000;
            }
            mediaController.getTransportControls().seekTo(posMs);
            onSeekLocalCalibrate(posMs);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "seekTo error: " + t.getMessage());
            return false;
        }
    }

 /**
 * After a seek, immediately move the frozen position and the accumulator
 * start point to the target so the UI progress bar does not snap back
 * during the window before the source callback reports the new position.
 */
    private void onSeekLocalCalibrate(long posMs) {
        frozenPlaybackPosition = posMs;
        // seek 成功立即开 2 秒保护窗:防止 seek 瞬间音频闪断被自动切源逻辑误判(拖进度条就切歌)
        seekProtectUntil = System.currentTimeMillis() + 2000L;
        long now = android.os.SystemClock.elapsedRealtime();
        if (isBluetoothController()) {
            lastBtPositionMs = posMs;
            lastBtPositionTime = now;
        } else {
            lastNonBtPositionMs = posMs;
            lastNonBtPositionTime = now;
        }
    }

 /** Toast the UI that the current source does not support seeking. */
    private void notifySeekUnsupported() {
        AuthMessageListener l = mAuthListener;
        if (l != null) {
            try {
                l.onAuthMessage("当前音源不支持拖动进度");
            } catch (Throwable ignored) {
            }
        }
    }

    public void rescanUsb() {
        if (usbSourceController != null) usbSourceController.rescan();
    }

    public void setUsbPlayMode(int mode) {
        if (usbSourceController != null) usbSourceController.setPlayMode(mode);
    }

    public int getUsbPlayMode() {
        return usbSourceController == null ? 0 : usbSourceController.getPlayMode();
    }

 /** 轻量 USB getter（供推送字段级节流签名使用，不反射不构建 JSON）。 */
    public int getUsbCurrentIndex() {
        return usbSourceController == null ? -1 : usbSourceController.getCurrentIndex();
    }

 /** 轻量 USB 播放状态 getter。 */
    public int getUsbPlayState() {
        return usbSourceController == null ? 0 : usbSourceController.getPlayState();
    }

    public String getUsbStateJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("activated", usbRouteArmed);
            o.put("mounted", usbSourceController != null && usbSourceController.isUsbMounted());
            o.put("songCount", usbSourceController == null ? 0 : usbSourceController.getMusicCount());
            int _idx = usbSourceController == null ? -1 : usbSourceController.getCurrentIndex();
            int _state = usbSourceController == null ? 0 : usbSourceController.getPlayState();
            o.put("currentIndex", _idx);
            o.put("playState", _state);
            o.put("playMode", getUsbPlayMode());
            o.put("scanning", usbSourceController != null && usbSourceController.isScanning());
            int[] prog = usbSourceController == null ? new int[]{0,0} : usbSourceController.getScanProgress();
            o.put("scanScanned", prog[0]);
            o.put("scanTotal", prog[1]);
            o.put("position", usbSourceController == null ? 0 : usbSourceController.getPosition());
            o.put("duration", usbSourceController == null ? 0 : usbSourceController.getDuration());
            // 设备容量（已用/总）
            try {
                o.put("capacity", usbSourceController == null ? ""
                        : usbSourceController.getDeviceInfo().optString("capacity", ""));
            } catch (Throwable ignored) {}
            o.put("fav", usbSourceController != null && usbSourceController.isCurrentFav());
            // USB 歌词来源：推"选中模式"(auto/embedded/fallback)。下拉固定由用户手动选择，
            // 不被自动逻辑改掉（auto 内部兜底到备用时，下拉仍显示"自动选择"）。
            o.put("lyricSource", usbLyricSource);
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    public UsbMusicItem getUsbCurrentItem() {
        return usbSourceController == null ? null : usbSourceController.getCurrentItem();
    }


    // ================= USB 收藏 =================

 /** 切换当前 USB 歌曲收藏状态，然后刷新 UI。 */
    public void toggleUsbFav() {
        if (usbSourceController == null) return;
        UsbMusicItem item = usbSourceController.getCurrentItem();
        if (item == null || item.path == null) return;
        usbSourceController.toggleFav(item.path);
        notifyUsbUiChanged();
    }

 /** 当前 USB 歌曲是否已收藏。 */
    public boolean isUsbFav() {
        return usbSourceController != null && usbSourceController.isCurrentFav();
    }

    // ================= USB 歌词来源 =================

 /**
 * 设置 USB 歌词来源（embedded/netease/soda/fallback），清空旧缓存并触发重新抓取。
 */
    public void setUsbLyricSource(String source) {
        if (source == null) source = "auto";
        // USB 歌词来源三档 auto/embedded/fallback，网易云/汽水已移除；
        // legacy 值 netease/soda 统一归并为 fallback(备用)。
        if (!"auto".equals(source) && !"embedded".equals(source) && !"fallback".equals(source)) source = "fallback";
        Log.w(TAG, "DEBUG setUsbLyricSource: " + source + " (was " + this.usbLyricSource + ")");
        this.usbLyricSource = source;
        // 持久化歌词源——服务重启后 restoreUsbLyricSource 能恢复,
        // 避免 Java 端(SharedPreferences)与前端(localStorage)不一致导致状态灯回退。
        try {
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .edit().putString("usb_lyric_source", source).apply();
        } catch (Throwable ignored) {
        }
        // 清空旧歌词缓存，强制重新抓取
        usbEmbeddedLyricLines = new java.util.ArrayList<>();
        usbEmbeddedLyricTimestamps = new java.util.ArrayList<>();
        lastUsbLyricSongKey = "";
        triggerUsbLyricFetch();
        // 歌词源切换后立即请求强制全量——embedded 同步加载立即生效,
        // 云源由异步抓取回调再推一次(新歌词)。
        requestUsbFullPush();
    }

 /**
 * 按当前 usbLyricSource 触发 USB 歌词抓取/加载。
 * 非 embedded 来源按 title||artist 去重，同歌不重复抓取。
 */
    private void triggerUsbLyricFetch() {
        try {
            if (!usbRouteArmed || usbSourceController == null) return;
            UsbMusicItem item = usbSourceController.getCurrentItem();
            if (item == null) return;
            // ★ (Bug#81 对齐他人已验证方案):U 盘 onStateChanged 每秒回调本方法,
            // 每次实际取词都确保歌词轮询在跑(startLyricPoll 幂等)——开机自动续播/下一首/换歌
            // 等场景轮询即使被 stopLyricPoll 停掉也会被这里拉起来,保证桌面小组件持续刷新。
            startLyricPoll();
            String title = item.title != null ? item.title : "";
            String artist = item.artist != null ? item.artist : "";
            String songKey = title + "||" + artist;
            // 诊断日志:USB 歌词加载触发点(真车定位"默认内嵌无歌词"用)
            Log.i(TAG, "UsbLyric: trigger source=" + usbLyricSource + " song=" + title
                    + " key=" + songKey + " (was " + lastUsbLyricSongKey + ")");
            // 卡死修复：所有来源统一按 songKey 去重。
            // 之前 embedded 每次状态变化（进度轮询 1s 一次）都在主线程重读整个 .lrc 文件
            // 并重建歌词数组，289 首场景下主线程被 IO 拖垮。现在只有切歌时才加载。
            if (songKey.equals(lastUsbLyricSongKey)) {
                return;
            }
            lastUsbLyricSongKey = songKey;
            // 同步当前元数据（供 UI/歌词归属使用）
            currentTitle = title;
            currentArtist = artist;
            if ("auto".equals(usbLyricSource) || "embedded".equals(usbLyricSource)) {
                loadEmbeddedLyric();
                // 清空云歌词抓取器缓存，避免旧歌残留
                if (cloudLyricFetcher != null) cloudLyricFetcher.clearLoadedLrc();
                if (usbEmbeddedLyricLines == null || usbEmbeddedLyricLines.isEmpty()) {
                    if ("auto".equals(usbLyricSource)) {
                        // auto(默认)：有内嵌用内嵌，内嵌为空 → 兜底"备用"(xmf2)。网易云/汽水已移除。
                        Log.i(TAG, "UsbLyric: auto: embedded empty, fallback to fallback(xmf2) lyric for " + title);
                        usbEmbeddedFallback = true;
                        currentSourceType = SOURCE_TYPE_GENERIC;
                        if (cloudLyricFetcher != null) cloudLyricFetcher.searchXmf2Only(title, artist);
                    } else {
                        // embedded-only：强制只用内嵌，内嵌为空 → 显示"暂无歌词"，不兜底备用。
                        Log.i(TAG, "UsbLyric: embedded-only: no embedded lyric, show none for " + title);
                        usbEmbeddedFallback = false;
                        currentSourceType = SOURCE_TYPE_USB;
                    }
                } else {
                    usbEmbeddedFallback = false;
                    currentSourceType = SOURCE_TYPE_USB;
                }
            } else { // fallback（网易云/汽水已移除；legacy 值 netease/soda 一律并入备用兜底）
                currentSourceType = SOURCE_TYPE_GENERIC;
                if (cloudLyricFetcher != null) cloudLyricFetcher.searchXmf2Only(title, artist);
            }
            // 卡死修复：删除 notifyUsbUiChanged()——triggerUsbLyricFetch 由
            // UsbStateCallback.onStateChanged() 调用，后者已统一推送，这里再推会造成
            // 每秒 2 次全量 pushAllPublic（叠加 MainActivity 1s 轮询 = 每秒 3 次），车机卡死。
        } catch (Throwable t) {
            Log.w(TAG, "UsbLyric: triggerUsbLyricFetch error: " + t.getMessage());
        }
    }

 /**
 * 读取当前 USB 歌曲同名 .lrc 文件，解析为纯文本歌词行（去掉 [mm:ss.xx] 时间戳）。
 * 文件不存在或读取失败时清空缓存。
 */
    private void loadEmbeddedLyric() {
        usbEmbeddedLyricLines = new java.util.ArrayList<>();
        usbEmbeddedLyricTimestamps = new java.util.ArrayList<>();
        if (usbSourceController == null) return;
        UsbMusicItem item = usbSourceController.getCurrentItem();
        if (item == null || item.path == null) return;
        // ★ (双点 .lrc 找不到):原实现用 item.path 截最后一个 '.' 再拼 ".lrc"。
        // 歌名/文件名本身结尾带点(如 "0661.蒋雪儿 - 燕无歇.")时,截出的是
        // "0661.蒋雪儿 - 燕无歇."+".lrc" → "0661.蒋雪儿 - 燕无歇..lrc"(双点),U 盘实际
        // 文件名是单点 "0661.蒋雪儿 - 燕无歇.lrc" → FileReader No such file → 内嵌歌词空。
        // 修复:尝试多个候选路径(去尾点 + 原样),File.exists() 命中才读;都不存在走异常清空。
        java.util.List<String> lrcCandidates = new java.util.ArrayList<>();
        String lrcBase = item.path;
        String rawLrc = lrcBase + ".lrc"; // 原样 + .lrc(适配文件名即歌名,无扩展名的场景)
        // 去掉最后一个 '.' 及之后(音频扩展名),再拼 .lrc
        int dotIdx = lrcBase.lastIndexOf('.');
        String noExtLrc = (dotIdx > 0 ? lrcBase.substring(0, dotIdx) : lrcBase) + ".lrc";
        lrcCandidates.add(noExtLrc);
        lrcCandidates.add(rawLrc);
        // 去尾点候选:若 noExt 本身以 '.' 结尾(歌名带点),再去一次尾点
        String strippedDot = noExtLrc;
        if (strippedDot.endsWith(".lrc")) {
            String pre = strippedDot.substring(0, strippedDot.length() - 4);
            if (pre.endsWith(".")) {
                lrcCandidates.add(pre.substring(0, pre.length() - 1) + ".lrc");
            }
        }
        String lrcPath = null;
        for (String cand : lrcCandidates) {
            try {
                if (new java.io.File(cand).exists()) {
                    lrcPath = cand;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        java.io.BufferedReader reader = null;
        String lrcText = null;
        String srcDesc = "sidecar";
        try {
            if (lrcPath != null) {
                reader = new java.io.BufferedReader(new java.io.FileReader(lrcPath));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                lrcText = sb.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "UsbLyric: loadEmbeddedLyric sidecar read failed: " + t.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
        }
        // ★ 旁车 .lrc 缺失/为空时，改读音频文件内部元数据内嵌歌词
        // （FLAC VORBIS_COMMENT / MP3 ID3 USLT / M4A ©lyr / OGG / WMA / APE，见 EmbeddedLyricReader）。
        // 之前只读旁车 .lrc，像"我们-许巍-441794-2000.flac"这种歌词内嵌在文件元数据里、
        // 又没有同名 .lrc 的歌，loadEmbeddedLyric 直接落空 → 兜底到云端歌词。
        if (lrcText == null || lrcText.trim().isEmpty()) {
            String inFile = EmbeddedLyricReader.readLyrics(item.path);
            if (inFile != null && !inFile.trim().isEmpty()) {
                lrcText = inFile;
                srcDesc = "in-file";
            }
        }
        if (lrcText != null && !lrcText.trim().isEmpty()) {
            // ★ (Bug:USB 歌词提前走) 修复：
            // 直接走 LrcUtil.parse（多时间戳行展开 + 按时间戳升序），与云端歌词同源同规则。
            // 注：只跳过真正空白行，含文本的行（作词/作曲/歌名等）全部保留——符合文首铁律。
            try {
                java.util.List<LrcUtil.Line> parsedLines = LrcUtil.parse(lrcText);
                for (LrcUtil.Line ln : parsedLines) {
                    usbEmbeddedLyricLines.add(ln.text);
                    usbEmbeddedLyricTimestamps.add(ln.timeMs);
                }
                Log.i(TAG, "UsbLyric: embedded lyric loaded lines=" + usbEmbeddedLyricLines.size()
                        + " src=" + srcDesc + " path=" + item.path);
            } catch (Throwable t) {
                Log.w(TAG, "UsbLyric: loadEmbeddedLyric parse failed: " + t.getMessage());
                usbEmbeddedLyricLines = new java.util.ArrayList<>();
                usbEmbeddedLyricTimestamps = new java.util.ArrayList<>();
            }
        } else {
            Log.w(TAG, "UsbLyric: loadEmbeddedLyric failed: " + item.path
                    + " (no sidecar .lrc nor in-file lyric)");
            usbEmbeddedLyricLines = new java.util.ArrayList<>();
            usbEmbeddedLyricTimestamps = new java.util.ArrayList<>();
        }
    }

 /** 返回内嵌歌词行缓存（无则空列表）。 */
    private java.util.List<String> getUsbEmbeddedLyricLines() {
        return usbEmbeddedLyricLines;
    }

 /**
 * USB 内嵌歌词：按时间戳精确匹配当前行索引（找到最大的时间戳 <= 当前播放位置的行）。
 * 无歌词返回 -1，播放位置在第一行时间戳之前返回 0（显示第一行）。
 */
    private int getUsbEmbeddedLyricIndex() {
        try {
            java.util.List<String> lines = usbEmbeddedLyricLines;
            java.util.List<Long> timestamps = usbEmbeddedLyricTimestamps;
            if (lines == null || lines.isEmpty()) return -1;
            if (usbSourceController == null) return 0;
            long position = usbSourceController.getPosition();
            if (position < 0) position = 0;
            // 时间戳匹配：找到最大的 timestamp <= position 的行
            int idx = -1;
            if (timestamps != null && timestamps.size() == lines.size()) {
                for (int i = 0; i < timestamps.size(); i++) {
                    if (timestamps.get(i) != null && timestamps.get(i) <= position) {
                        idx = i;
                    } else {
                        break; // 时间戳递增，后面的都大于 position
                    }
                }
            }
            if (idx < 0) idx = 0; // 播放位置在第一行之前，显示第一行
            if (idx >= lines.size()) idx = lines.size() - 1;
            return idx;
        } catch (Throwable t) {
            return 0;
        }
    }

 /**
 * 云歌词抓取完成/歌词源切换后 → 请求一次强制全量推送。
 * 之前异步抓取(onLyricReady)只更新缓存不推送,前端要等下一次 onStateChanged
 * (暂停/播放/切歌)才拿到新歌词——改歌词源/点其他歌都不生效。
 * fix: 去掉 !usbRouteArmed 守卫——网易云/汽水/蓝牙(XMF2)的异步歌词
 * 就绪回调同样走这里。非 USB 音源时原守卫直接 return:晴空主题全量歌词列表
 * (d.lyrics)只随全量推送更新,歌词就绪不推 → "从 USB 切到某音源后没歌词"
 * (默认主题靠轻量 updateLyricLine 正常;蓝牙闪烁期签名被 "~" 屏蔽时晴空更持久)。
 */
    private void requestUsbFullPush() {
        if (MainActivity.sInstance == null) return;
        MainActivity.sInstance.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (MainActivity.sInstance != null) MainActivity.sInstance.requestFullPush();
            }
        });
    }

 /** USB 状态变化后切主线程通知 MainActivity 刷新 UI。 */
    private void notifyUsbUiChanged() {
        if (MainActivity.sInstance == null) return;
        MainActivity.sInstance.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.pushAllPublic();
                }
            }
        });
    }

 /**
 * 返回指定索引 USB 歌曲信息 JSON（供前端"更多"菜单使用）。
 * 字段：title/artist/album/duration(ms)/path/folderName/format/size。
 * index 越界或无列表返回 "{}"。
 */
    public String getUsbSongInfoJson(int index) {
        if (usbSourceController == null) return "{}";
        try {
            List<UsbMusicItem> list = usbSourceController.getMusicList();
            if (list == null || index < 0 || index >= list.size()) return "{}";
            UsbMusicItem item = list.get(index);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("title", item.title == null ? "" : item.title);
            o.put("artist", item.artist == null ? "" : item.artist);
            o.put("album", item.album == null ? "" : item.album);
            o.put("duration", item.duration);
            o.put("path", item.path == null ? "" : item.path);
            o.put("folderName", item.folderName == null ? "" : item.folderName);
            // format：从 path 提取扩展名（大写，如 MP3）
            String fmt = "";
            long size = 0;
            if (item.path != null) {
                int dot = item.path.lastIndexOf('.');
                if (dot > 0 && dot < item.path.length() - 1) {
                    fmt = item.path.substring(dot + 1).toUpperCase(java.util.Locale.US);
                }
                try { size = new java.io.File(item.path).length(); } catch (Throwable ignored) {}
            }
            o.put("format", fmt);
            o.put("size", size);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }


    // ================= CarPlay 盒子直通（路径B） =================
    // 极简模型 :不做任何盒子检测(不查包名/前台/连接/音频),
    // 注入 87/88/85 对所有 CarPlay 盒子通用(盒子默认就认标准媒体键,无需学习)。
    // 用户手动选中 CarPlay 条目 → 方控三键无条件注入标准媒体键。
 /** 用户是否已在控制器列表手动选中 CarPlay(纯选中态,与连接无关)。 */
    public boolean isCarplayBoxSelected() {
        return carplayBoxSelected;
    }

 /** 设置 CarPlay 的手动选中态(UI 控制器列表点击切换用)。 */
    public void setCarplayBoxSelected(boolean selected) {
        if (carplayBoxSelected != selected) {
            carplayBoxSelected = selected;
            Log.i(TAG, "setCarplayBoxSelected: " + selected);
        }
        // 取消 CarPlay 选中时同步清除合并信息源(切到其他音源=退出合并模式)");
        // 重新选中 CarPlay 时解除"取消合并"抑制(允许再次自动合并)
        if (!selected && carplayMergedPkg != null) {
            carplayMergedPkg = null;
            Log.i(TAG, "setCarplayBoxSelected: clear merged info source");
        }
        if (selected && carplayMergeDismissed) {
            carplayMergeDismissed = false;
            Log.i(TAG, "setCarplayBoxSelected: re-enable auto merge");
        }
    }
 /** 用户手动取消合并:抑制后续自动合并(直到重新选 CarPlay 或手动再合并)。 */
    public void dismissCarplayMerge() {
        carplayMergedPkg = null;
        carplayMergeDismissed = true;
        Log.i(TAG, "dismissCarplayMerge: auto merge disabled until reselect CarPlay");
    }

    // ================= CarPlay 合并信息源 =================
 /** 是否为 CarPlay 盒子真实包名(可合并的信息源候选,不可作为独立切歌控制器)。 */
    public static boolean isCarplayBoxRealPkg(String pkg) {
        return pkg != null && CARPLAY_BOX_REAL_PACKAGES.contains(pkg);
    }
 /** 设置 CarPlay 合并的盒子信息源包名(用户选中 CarPlay 后再点盒子条目合并)。 */
    public void setCarplayBoxMerged(String pkg) {
        if (pkg != null && !isCarplayBoxRealPkg(pkg)) {
            Log.w(TAG, "setCarplayBoxMerged: reject non-box pkg " + pkg);
            return;
        }
        if (carplayMergedPkg != null && (pkg == null || !carplayMergedPkg.equals(pkg))) {
            Log.i(TAG, "setCarplayBoxMerged: clear " + carplayMergedPkg + " -> " + pkg);
        }
        carplayMergedPkg = pkg;
        Log.i(TAG, "setCarplayBoxMerged: " + pkg);
    }
 /** 当前 CarPlay 合并的信息源包名(可能为 null=未合并)。 */
    public String getCarplayBoxMergedPkg() {
        return carplayMergedPkg;
    }
 /** 指定包名是否为当前合并的信息源。 */
    public boolean isCarplayBoxMerged(String pkg) {
        return pkg != null && pkg.equals(carplayMergedPkg);
    }
 /** 合并是否"生效":已合并且盒子 MediaSession 仍存在(会话消失时回退显示)。 */
    public boolean isCarplayBoxMergedActive() {
        if (carplayMergedPkg == null) {
            return false;
        }
        return findControllerByPackage(carplayMergedPkg) != null;
    }
 /** 在白名单盒子会话中找当前存在的一个(优先更像媒体的;getKnownControllers 全量,
 * 含 active=false 的暂停会话)。 */
    private MediaController findCarplayBoxController() {
        MediaController best = null;
        try {
            for (MediaController c : getKnownControllers()) {
                if (c == null || c.getPackageName() == null) continue;
                if (isCarplayBoxRealPkg(c.getPackageName())) {
                    if (best == null) {
                        best = c;
                    } else if (isMoreMediaLike(c, best)) {
                        best = c;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findCarplayBoxController error: " + t.getMessage());
        }
        return best;
    }
 /**
 * 自动合并:选中 CarPlay 后,白名单盒子 MediaSession 一出现即自动合并
 * ——CarPlay 负责切歌(注入 87/88),盒子 MediaSession 负责显示歌曲信息。
 * 调用点:会话变化回调/控制器列表刷新(盒子连接后自动触发,无需手动点条目)。
 * 已合并且会话仍存在时幂等跳过;盒子断开后自动合并等下次出现。
 */
    public void autoMergeCarplayBoxIfPresent() {
        try {
            if (carplayMergeDismissed) {
                return; // 用户取消过合并,不自动合并(手动点条目仍可合并)
            }
            if (carplayMergedPkg != null && isCarplayBoxMergedActive()) {
                return; // 已合并生效,幂等
            }
            MediaController box = findCarplayBoxController();
            if (box == null) {
                // 盒子会话消失 → 若缓存里曾带 CarPlay,作废旧 ghost 缓存及时隐藏。
                // 缓存里可能残留 CarPlay(未合并也按白名单盒子出现而显示过),故直接看缓存内容。
                synchronized (ghostCacheLock) {
                    if (cachedGhostPkgs != null && cachedGhostPkgs.contains(PKG_CARPLAY_BOX)) {
                        cachedGhostPkgs = null;
                        ghostCacheTime = 0L;
                        Log.d(TAG, "autoMergeCarplayBox: box gone, invalidate ghost cache (hide CarPlay)");
                    }
                }
                return;
            }
            String bp = box.getPackageName();
            // 双向自动合并:白名单盒子会话出现/被选到即自动合并 CarPlay,
            // 无需先手动选中 CarPlay。但"谁在播放控制器就是谁"——若已有其他真实
            // 音源在播(汽水/酷我等),不自动抢,等用户主动操作;盒子在播/无音源在播
            // 时自动进入 CarPlay 合并模式(替代应用管家的自动体验)。
            if (!carplayBoxSelected) {
                String otherPlaying = getRealPlayingMusicPkg();
                if (otherPlaying != null && !isCarplayBoxRealPkg(otherPlaying)) {
                    Log.i(TAG, "autoMergeCarplayBox: real source playing " + otherPlaying
                            + ", skip auto CarPlay (who-plays-owns)");
                    return;
                }
                Log.i(TAG, "autoMergeCarplayBox: auto select CarPlay + merge box " + bp);
                carplayBoxSelected = true;
            }
            if (carplayMergedPkg == null || !carplayMergedPkg.equals(bp)) {
                Log.i(TAG, "autoMergeCarplayBox: merge CarPlay + box " + bp);
                carplayMergedPkg = bp;
            }
            if (!bp.equals(controllerPkg)) {
                selectController(box); // 绑定元数据管道(控制仍走 CarPlay 注入 87/88)
            }
            // 通知主界面刷新(盒子出现合并态/歌名)
            // 盒子会话出现 → 作废旧 ghost 缓存,让 CarPlay 列表项及时出现
            invalidateGhostCache();
            try {
                if (MainActivity.sInstance != null) {
                    MainActivity.sInstance.pushAllPublic();
                }
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "autoMergeCarplayBox error: " + t.getMessage());
        }
    }

 /**
 * 防 ANR:InputManager.injectInputEvent(WAIT_FOR_FINISH)
 * 是同步 Binder 调用,系统 InputDispatcher 忙时会阻塞数秒——ANR 现场:
 * 主线程卡在 IPCThreadState.talkWithDriver 6.5s+ → "Input dispatching timed out"
 * → ANR 对话框 → 用户关闭 = 观感"闪退"。注入挪到独立单线程后台执行,
 * 主线程(UI/JS 桥)永不阻塞;单线程 Handler 保持 down/up 顺序与连续按键顺序。
 * 真车语义不变:仍走 WAIT_FOR_FINISH + MediaSession 让位/恢复。
 */
    private final Object mediaKeyThreadLock = new Object();
    private android.os.HandlerThread mediaKeyThread = null;
    private android.os.Handler mediaKeyHandler = null;

    private void injectMediaKeyReal(int keyCode) {
        final int kc = keyCode;
        try {
            android.os.Handler h;
            synchronized (mediaKeyThreadLock) {
                if (mediaKeyHandler == null) {
                    mediaKeyThread = new android.os.HandlerThread("mediaKeyInject");
                    mediaKeyThread.start();
                    mediaKeyHandler = new android.os.Handler(mediaKeyThread.getLooper());
                }
                h = mediaKeyHandler;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    injectMediaKeyRealOnWorker(kc);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "injectMediaKeyReal queue fail: " + t.getMessage());
        }
    }

 /**
 * (Bug#76):MediaSession 路由单通道发送媒体键(com.aufn.carplay 专用)。
 * 真车日志确认:系统把媒体键路由给 com.aufn.carplay/CoreService 的 MediaSession
 * (MediaSessionService: Sending KeyEvent 87 to CoreService)。AudioManager
 * dispatchMediaKeyEvent 只走 MediaSession 路由、不经前台窗口 → 盒子 CoreService
 * 收 1 次即转发 iPhone,切 1 首、盒子无需在前台。发送前 CarMedia 自己的 MediaSession
 * 让位(setActive(false)),确保路由目标是盒子而非 CarMedia 媒体键代理。
 *
 * (Bug#77 补充,CarLife 与 com.aufn.carplay 后台切歌通用):
 * 真车日志确认(10-17 CarLife/10-18 盒子同样):投屏类会话(com.muyetuge.carlifevehicle /
 * com.aufn.carplay)在后台时,系统会把"媒体按钮会话"从投屏会话切到 CarMedia 自己的
 * MediaSession(MediaSessionService: Media button session is changed to
 * com.ecarx.carmedia/CarMediaSession)。此后注入 87/88 被系统 Sending 回 CarMedia
 * 自身,又经 onMediaButtonEvent:absorbed(logcat/other copy) 自吸收,投屏会话收不到 →
 * 后台切歌失效,仅其前台时(媒体按钮会话=投屏会话)能切。
 *
 * 根因:让位(setActive(false))后**立即** dispatch,MediaSessionService 尚未重评
 * "媒体按钮会话",注入键仍按旧路由(CarMedia)回自身被自吸收。投屏会话的 MediaSession
 * 仍在 active 列表(getSessions 可查到),只是被 CarMedia 抢占为媒体按钮会话。
 *
 * 修复:让位后 postDelayed 250ms 再 dispatch,给 MediaSessionService 时间把媒体按钮
 * 会话重选回投屏会话(它是 active 列表里的媒体会话),注入键即可路由到它;注入完成后
 * 恢复 CarMedia 自己的 MediaSession active(仍需作为媒体键代理拦截其他音源)。
 *
 * @param keyCode 87=MEDIA_NEXT / 88=MEDIA_PREVIOUS / 85=MEDIA_PLAY_PAUSE
 */
    private void dispatchMediaKeyToSession(final int keyCode) {
        final boolean sessionWasActive;
        if (mediaSession != null && mediaButtonViaSession) {
            try {
                sessionWasActive = mediaSession.isActive();
                mediaSession.setActive(false); // 让位:给 MediaSessionService 重评媒体按钮会话的时间
            } catch (Throwable ignore) {
                return; // 拿不到自身会话状态,无法安全让位,放弃本次
            }
        } else {
            sessionWasActive = false;
        }
        // 延迟注入:等待系统把媒体按钮会话从 CarMedia 重选回目标投屏会话(CarLife/com.aufn.carplay)
        if (lyricPollHandler != null) {
            lyricPollHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am != null) {
                            long now = android.os.SystemClock.uptimeMillis();
                            android.view.KeyEvent down = new android.view.KeyEvent(
                                    now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                            android.view.KeyEvent up = new android.view.KeyEvent(
                                    now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                            am.dispatchMediaKeyEvent(down);
                            am.dispatchMediaKeyEvent(up);
                            Log.i(TAG, "dispatchMediaKeyToSession: MediaSession routing dispatched "
                                    + "keyCode=" + keyCode + " (delayed yield)");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "dispatchMediaKeyToSession error: " + t.getMessage());
                    } finally {
                        if (sessionWasActive) {
                            try {
                                mediaSession.setActive(true); // 注入完成,恢复 CarMedia 代理会话
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
            }, 250);
        } else {
            // 无主线程 handler,退回同步让位注入
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.KeyEvent down = new android.view.KeyEvent(
                            now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                    android.view.KeyEvent up = new android.view.KeyEvent(
                            now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                    am.dispatchMediaKeyEvent(down);
                    am.dispatchMediaKeyEvent(up);
                    Log.i(TAG, "dispatchMediaKeyToSession: MediaSession routing dispatched keyCode=" + keyCode);
                }
            } catch (Throwable t) {
                Log.w(TAG, "dispatchMediaKeyToSession error: " + t.getMessage());
            } finally {
                if (sessionWasActive) {
                    try {
                        mediaSession.setActive(true);
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
    }

 /**
 * (Bug#77 补充,CarLife 与 com.aufn.carplay 后台切歌专用)。
 * 统一入口:CarLife 直通与 com.aufn.carplay 盒子走 MediaSession 路由时,后台切歌
 * 因 CarMedia 抢占"媒体按钮会话"而失效,需"让位→延迟→注入→恢复"。
 * 本方法与 dispatchMediaKeyToSession 逻辑一致,直接委托给后者。
 *
 * @param keyCode 87=MEDIA_NEXT / 88=MEDIA_PREVIOUS / 85=MEDIA_PLAY_PAUSE
 */
    private void dispatchMediaKeyToSessionForCarlife(int keyCode) {
        dispatchMediaKeyToSession(keyCode);
    }

 /**
 * (Bug#77 终修):直接给 CarLife 车机端投递 ACTION_MEDIA_BUTTON 广播切歌。
 * 逆向 CarLife APK(com.baidu.carlifevehicle 2024-11-29)实证其切歌链路:
 * ACTION_MEDIA_BUTTON + EXTRA_KEY_EVENT → ButtonReceiver(静态 priority=100000)
 * → VehicleService.onStartCommand → EmptyMediaHelper.getMediaSession()(CarlifeMedia)
 * → MediaControllerCompat.dispatchMediaButtonEvent(KeyEvent) → 投屏转发手机切歌。
 * 与 CarLife 前后台无关,是原版 1.3.4~1.6.4 能后台切歌的本质通道。
 * 用 sendBroadcast + setPackage(controllerPkg) 定向:广播只进 CarLife 包,CarMedia 自己的
 * MediaButtonBlocker(动态注册)收不到,不冲突;CarLife 的 ButtonReceiver 收到后自会
 * startForegroundService 到 VehicleService。发 DOWN+UP 模拟真实单击。
 * @param keyCode 87=MEDIA_NEXT / 88=MEDIA_PREVIOUS / 85=MEDIA_PLAY_PAUSE
 * @return true=广播已投递
 */
    private boolean sendMediaButtonToCarlife(int keyCode) {
        try {
            String pkg = controllerPkg;
            if (!isCarlifePkg(pkg)) {
                return false;
            }
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent down = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
            android.view.KeyEvent up = new android.view.KeyEvent(
                    now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
            Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
            i.setPackage(pkg);   // 定向到 CarLife 包
            ComponentName btn = findCarlifeMediaButtonReceiver(pkg);
            if (btn != null) {
                i.setComponent(btn);   // 精确到 ButtonReceiver,避免被同包其它 receiver 分走
            }
            i.putExtra(Intent.EXTRA_KEY_EVENT, down);
            sendBroadcast(i);
            i.putExtra(Intent.EXTRA_KEY_EVENT, up);
            sendBroadcast(i);
            Log.i(TAG, "sendMediaButtonToCarlife: broadcast ACTION_MEDIA_BUTTON keyCode=" + keyCode
                    + " to " + pkg + " component=" + btn);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendMediaButtonToCarlife error: " + t.getMessage());
            return false;
        }
    }

 /** 查找 CarLife 包内处理 ACTION_MEDIA_BUTTON 的静态 receiver(ButtonReceiver)。 */
    private ComponentName findCarlifeMediaButtonReceiver(String pkg) {
        try {
            Intent probe = new Intent(Intent.ACTION_MEDIA_BUTTON);
            probe.setPackage(pkg);
            List<android.content.pm.ResolveInfo> rs =
                    getPackageManager().queryBroadcastReceivers(probe, 0);
            if (rs != null) {
                for (android.content.pm.ResolveInfo r : rs) {
                    if (r.activityInfo != null) {
                        return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findCarlifeMediaButtonReceiver error: " + t.getMessage());
        }
        return null;
    }

 /**
 * 真实注入标准媒体键(路径B核心,所有 CarPlay 盒子通用)。
 * 首选 InputManager.injectInputEvent 反射(系统 uid + INJECT_EVENTS):
 * InputDispatcher 分发给前台窗口——E-Box 切歌唯一通路( 真车实证:
 * dispatchMediaKeyEvent 被 E-Box MediaSession 拒绝,其 PlaybackState.actions
 * 5170/5172 无 SKIP_TO_NEXT/PREVIOUS 位,系统媒体键路由不转发 87/88;
 * 日志亦实证系统注入 87 只到前台窗口 CarletterActivity.onKeyDown)。
 * 兜底 AudioManager.dispatchMediaKeyEvent(仅注入 API 异常时,MediaSession 路由
 * 对其他盒子可能有效)。
 * 注入前临时让 CarMedia 自己的 MediaSession 让位(setActive(false)),
 * 防止系统把媒体键路由给 CarMedia 媒体键代理;注入后恢复。
 *
 * @param keyCode 87=MEDIA_NEXT / 88=MEDIA_PREVIOUS / 85=MEDIA_PLAY_PAUSE
 */
    private void injectMediaKeyRealOnWorker(int keyCode) {
        // (Bug#76):撤销 Bug#75 的"置顶吸收"——CarPlay 界面不一定在前台,
        // 注入键被 CarMedia 代理吸收后,盒子只剩前台窗口一条路,盒子不在前台就完全
        // 收不到键(真车反馈"不切歌")。恢复注入前让 CarMedia 代理让位(setActive(false)),
        // 使系统媒体键路由目标不是 CarMedia。本方法负责的盒子(E-Box com.carletter.ceb
        // 及未知盒子)MediaSession 无 SKIP 位系统不路由 87/88,注入键只走前台窗口
        // 一条路 → 切 1 首(com.aufn.carplay 改走 dispatchMediaKeyToSession 单通道)。
        boolean sessionWasActive = false;
        try {
            if (mediaSession != null && mediaButtonViaSession) {
                try {
                    sessionWasActive = mediaSession.isActive();
                    mediaSession.setActive(false); // 让位:系统媒体键路由目标不是 CarMedia 代理
                } catch (Throwable ignore) {
                }
            }
            // 首选:InputManager.injectInputEvent 反射(系统 uid + INJECT_EVENTS,前台窗口分发)
            try {
                Class<?> imClass = Class.forName("android.hardware.input.InputManager");
                java.lang.reflect.Method getInstance = imClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object im = getInstance.invoke(null);
                java.lang.reflect.Method inject = imClass.getDeclaredMethod(
                        "injectInputEvent", android.view.InputEvent.class, int.class);
                inject.setAccessible(true);
                long now = android.os.SystemClock.uptimeMillis();
                android.view.KeyEvent down = new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                android.view.KeyEvent up = new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                inject.invoke(im, down, 2); // INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                inject.invoke(im, up, 2);
                Log.i(TAG, "injectMediaKeyReal: InputManager injected keyCode=" + keyCode);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "injectMediaKeyReal InputManager fail, fallback AudioManager: "
                        + t.getMessage());
            }
            // 兜底:AudioManager.dispatchMediaKeyEvent(MediaSession 路由,仅注入 API 异常时)
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                long now = android.os.SystemClock.uptimeMillis();
                android.view.KeyEvent down = new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                android.view.KeyEvent up = new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                am.dispatchMediaKeyEvent(down);
                am.dispatchMediaKeyEvent(up);
                Log.i(TAG, "injectMediaKeyReal: AudioManager dispatched keyCode=" + keyCode);
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectMediaKeyReal error: " + t.getMessage());
        } finally {
            if (sessionWasActive) {
                try {
                    mediaSession.setActive(true);
                } catch (Throwable ignore) {
                }
            }
        }
    }


 /**
 * 统一媒体控制入口 + 四级 fallback(全兼容方案)
 *
 * 背景:不同播放器行为差异巨大:
 * - 酷狗:暂停后 MediaSession active=false,mediaController=null;
 * transportControls 不可用;系统媒体键 85/127 无效;只有 startService(KugouPlaybackService) 有效。
 * - 酷我:暂停后 MediaSession active=false,mediaController=null;
 * transportControls 不可用;只有自定义广播(kuwo.musichd_car.*)有效。
 * - 汽水音乐:暂停后 MediaSession active=true,mediaController 不为 null;
 * 但 transportControls 完全无效!只有系统媒体键(input keyevent 85/87)有效。
 * - 其他播放器:可能 transportControls 有效,也可能无效。
 *
 * 方案:四级 fallback,逐级尝试,直到成功:
 * 1. 已知播放器特殊处理(酷狗 startService、酷我广播)——已验证有效,优先使用
 * 2. 标准 mediaController.transportControls——大部分播放器适用
 * 3. 尝试重新获取 mediaController(findControllerByPackage)——播放器版本变化时自动适配
 * 4. AudioManager.dispatchMediaKeyEvent() 发送系统媒体键——通用 fallback,对汽水音乐有效
 *
 * @param action "play"/"pause"/"playpause"/"next"/"prev"
 */
    private void dispatchMediaAction(String action) {
        // (Bug#74):切歌动作(UI/方向盘/EAS 卡片/方控统一入口)主动清零进度累加器。
        // 蓝牙 AVRCP 切歌时若新歌 duration 未变/不推,updateTitleFlickerDetection 判定
        // "同一首歌"不复位 flicker → handleMetadata 的 "songChanged && !titleFlickerLyricActive"
        // 清零门不成立 → 进度条从旧歌位置继续走(切歌不清零)。切歌命令本身是 CarMedia
        // 发出的最可靠"真切歌"信号:无论 AVRCP 后续推什么,新歌进度都应从 0 起步。
        // 非蓝牙音源同样安全:下次 getPlaybackPosition() 会用新 PlaybackState 重新初始化。
        if ("next".equals(action) || "prev".equals(action)) {
            lastBtPositionMs = 0;
            lastBtPositionTime = 0;
            lastNonBtPositionMs = 0;
            lastNonBtPositionTime = 0;
            frozenPlaybackPosition = 0;
            Log.d(TAG, "dispatchMediaAction: " + action + " -> reset position accumulators (track change)");
        }
        // ★★ CarPlay 直通（路径B 定稿）★★
        // 极简模型:不做任何盒子检测,选中即注入——用户手动选中 CarPlay 后,
        // 方控三键默认注入标准媒体键 87/88/85(所有 CarPlay 盒子通用,盒子默认就认)。
        // 盒子在前台时收到真实 KeyEvent 自行转发手机切歌;未选中时走正常音源逻辑。
        if (carplayBoxSelected) {
            // (Bug#73 回退):保持"控制器选谁,按键就给谁"——用户选中 CarPlay
            // 按键就注入 87/88,不在按键路径检测真实在播/切走控制器(自动切源是
            // updateAvailableControllers/handlePlaybackConfigChanged 的职责,不混入按键)。
            int boxKey;
            if ("next".equals(action)) {
                boxKey = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;              // 87
            } else if ("prev".equals(action)) {
                boxKey = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;          // 88
            } else if ("playpause".equals(action) || "play".equals(action) || "pause".equals(action)) {
                boxKey = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;        // 85
            } else {
                Log.i(TAG, "dispatchMediaAction: CarPlay selected, unsupported action " + action);
                return;
            }
            // (Bug#76):按盒子的"收键通道"分派,不再一律注入:
            // - com.aufn.carplay 的 MediaSession(CoreService)能被系统媒体键路由(真车日志
            // MediaSessionService: Sending 87 to com.aufn.carplay/CoreService 确认),
            // 且 CarPlay 界面不一定在前台(后台时注入的前台窗口通道收不到)→ 走
            // AudioManager.dispatchMediaKeyEvent 的 MediaSession 路由单通道,盒子
            // CoreService 收 1 次即转发 iPhone,切 1 首、无需盒子在前台。
            // - com.carletter.ceb(E-Box)MediaSession 无 SKIP 位系统不路由 87/88,
            // 只能注入前台窗口(E-Box 使用场景盒子界面在前台,CarletterActivity
            // onKeyDown 收到即转发,切 1 首)。其它未知盒子默认走注入。
            String mergedBoxPkg = (carplayMergedPkg != null) ? carplayMergedPkg : controllerPkg;
            if (CARPLAY_BOX_PKG_MEDIASESSION_ROUTABLE.equals(mergedBoxPkg)) {
                Log.i(TAG, "dispatchMediaAction: CarPlay selected, media-session-routable box "
                        + mergedBoxPkg + ", dispatch keyCode=" + boxKey + " action=" + action);
                dispatchMediaKeyToSession(boxKey);
            } else {
                Log.i(TAG, "dispatchMediaAction: CarPlay selected, inject real media key keyCode=" + boxKey
                        + " action=" + action + " box=" + mergedBoxPkg);
                injectMediaKeyReal(boxKey);
            }
            return;
        }
        // ★★ CarLife 直通（方案B，Bug#77）★★
        // 百度 CarLife 车机版(com.baidu.carlifevehicle / com.muyetuge.carlifevehicle)连接手机时
        // 自建 MediaSession,但它是**投屏代理会话**:只上报元数据(歌名/歌手/进度/封面,主界面、
        // EAS 正常),**不响应标准 MediaController.transportControls 的 skipToNext/skipToPrevious**
        // ——真车 18:32 日志确认:dispatchMediaAction 走了第2级(transportControls)后,歌名歌词
        // 不变、歌曲位置弹回原歌(切歌无效)。与 CarPlay 盒子 com.aufn.carplay 同属"投屏类",
        // 必须走 MediaSession 路由注入标准媒体键 87/88/85(dispatchMediaKeyToSession,已验证:
        // AudioManager.dispatchMediaKeyEvent 只走 MediaSession 路由,系统把键路由给 CarLife
        // 会话后由其投屏通道转发手机端切歌,无需 CarLife 界面在前台)。
        // 仅当"当前控制器就是 CarLife"时走此直通,保持"控制器选谁,按键就给谁"。
        if (isCarlifePkg(controllerPkg)) {
            int carlifeKey;
            if ("next".equals(action)) {
                carlifeKey = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;              // 87
            } else if ("prev".equals(action)) {
                carlifeKey = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;          // 88
            } else if ("playpause".equals(action) || "play".equals(action) || "pause".equals(action)) {
                carlifeKey = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;        // 85
            } else {
                Log.i(TAG, "dispatchMediaAction: CarLife selected, unsupported action " + action);
                return;
            }
            Log.i(TAG, "dispatchMediaAction: CarLife selected, dispatch media-session key keyCode="
                    + carlifeKey + " action=" + action + " pkg=" + controllerPkg);
            // (Bug#77 终修·真车日志 + 逆向 CarLife APK 实证):CarLife 车机端
            // 真正切歌**不走 MediaSession transportControls**(其 onSkipToNext/onSkipToPrevious
            // 是空壳,只打日志不转发手机——真车日志:前台 onSkipToNext 后有"触发单击/任务/发送
            // 按键消息16",后台只打 onSkipToNext 到此为止),而走:
            // ACTION_MEDIA_BUTTON + EXTRA_KEY_EVENT → ButtonReceiver(静态 priority=100000)
            // → VehicleService.onStartCommand → EmptyMediaHelper.getMediaSession()(CarlifeMedia)
            // → MediaControllerCompat.dispatchMediaButtonEvent(KeyEvent) → 投屏转发手机切歌。
            // 该链路与 CarLife 前后台无关(EmptyMediaHelper 会话常驻,服务在跑就能转发),这正是
            // 原版 CarMedia 1.3.4~1.6.4 能后台切歌的本质通道(逆向确认)。故 CarLife 切歌改为
            // **广播投递到 CarLife 包**:sendBroadcast(ACTION_MEDIA_BUTTON + EXTRA_KEY_EVENT +
            // setPackage(carlifePkg)),CarLife 的 ButtonReceiver 收到后自会转发 VehicleService;
            // setPackage 限定广播只进 CarLife 包,CarMedia 自己的 MediaButtonBlocker 收不到,不冲突。
            // 广播异常时才回退 dispatchMediaKeyToSessionForCarlife(让位→延迟注入,仅能触发空壳)。
            if (!sendMediaButtonToCarlife(carlifeKey)) {
                dispatchMediaKeyToSessionForCarlife(carlifeKey);
            }
            return;
        }
        // ★★ USB 音源激活时直连 USB 播放器（修复 USB 播放时方向盘切歌/播放暂停无效）★★
        // USB 是 CarMedia 自建播放器，虚拟包名 com.ecarx.carmedia.usb 无真实外部 MediaSession：
        // 走下方四级 fallback 会全部失效——auto-switch 把"当前控制器不在播"误判后可能切到
        // 其它在播源/自身；第2级 mediaController 为 null 或残留（把切歌发给错误目标）；
        // 第3级 findControllerByPackage 找不到虚拟包；第4级系统媒体键被自身
        // MediaSession.onMediaButtonEvent 自吸收（isEasFromSelfDispatch）→ 无动作。
        // 必须在 auto-switch 之前拦截，方向盘/MDC/EAS 小卡片/MediaSession 代理/UI 统一生效。
        if (usbRouteArmed && usbSourceController != null) {
            // (Bug#72F):只有"当前控制器就是 USB"时按键才直连 USB。
            // 用户要求:控制器没选 USB(哪怕 usbRouteArmed 因 re-arm/activate 残留为 true),
            // 就算没有任何音源在播,也不能播放/切歌 USB——"控制器选谁,按键就给谁"。
            // controllerPkg==SOURCE_PKG_USB 由 pushMetadataToCard 的 USB 分支在
            // USB PLAYING/PREPARING 时维护;USB 暂停后不清(按播放仍能恢复),
            // 切走 USB 时才清(deactivateUsbSource/selectControllerInner)。
            boolean usbIsController = UsbSourceController.SOURCE_PKG_USB.equals(controllerPkg);
            // (Bug#72E):控制器是 USB 但已有真实外部音源在播(如汽水)时,
            // 按键也不能喂给 USB——谁在播放就给谁,落回下方 auto-switch/controller 逻辑,
            // 由严谨的 getRealPlayingMusicPkg + findControllerByPackage 切到真实在播源
            // (并暂停 USB,与用户确认的"谁在播放就切谁,上一个音源暂停"一致)。
            if (!usbIsController) {
                Log.i(TAG, "dispatchMediaAction: usb armed but controller is not USB ("
                        + controllerPkg + "), key NOT routed to USB");
            } else if (isOtherSourceReallyPlaying()) {
                Log.i(TAG, "dispatchMediaAction: usb is controller but another source really playing, "
                        + "key NOT routed to USB (fall through to switch to real source)");
            } else {
                int usbState = usbSourceController.getPlayState();
                // 记录 USB 切歌/操作时间,handlePlaybackConfigChanged 切歌保护窗口用
                lastUsbKeyEventTime = System.currentTimeMillis();
                if ("next".equals(action)) {
                    usbSourceController.next();
                } else if ("prev".equals(action)) {
                    usbSourceController.prev();
                } else if ("playpause".equals(action)) {
                    usbSourceController.playPause();
                } else if ("play".equals(action)) {
                    if (usbState != UsbMusicPlayer.STATE_PLAYING) {
                        usbSourceController.playPause();
                    }
                } else if ("pause".equals(action)) {
                    if (usbState == UsbMusicPlayer.STATE_PLAYING) {
                        usbSourceController.playPause();
                    }
                }
                Log.i(TAG, "dispatchMediaAction: USB route handled action=" + action
                        + " usbState=" + usbState);
                return;
            }
        }
        // 如果当前控制器不在播放,但有其他播放器在播放,自动切换到正在播放的播放器。
        // 场景:用户 setGhostControllerPkg(酷狗)后,打开汽水音乐播放,但自动切换没触发,
        // ghostControllerPkg 还是酷狗,此时切歌会错误地控制酷狗。
        // 例外:controllerJustSelected=true 时不自动切换——用户刚主动选了控制器,
        // 第一次操作应控制用户选的控制器(如播放酷狗),而不是切到其他在播源。
        // 酷狗切歌保护窗口:切歌后3秒内 AudioTrack 空窗期不触发自动切源
        // (Bug#72B):保持 controllerPkg != null 门槛——"没选中控制器时补扫选源"
        // 只由开机定时补扫(scheduleBootControllerRescan)承担,不在按键路径触发。
        // 按键时刻音频状态可能不稳定(切歌空窗/A2DP 常驻误报),即时扫源易选错目标;
        // 定时补扫用 updateAvailableControllers 的严谨选源(真实 AudioTrack + 蓝牙例外)。
        boolean kugouSwitchProtect = PKG_KUGOU.equals(controllerPkg)
                && (System.currentTimeMillis() - lastKugouKeyEventTime) < 3000;
        if (!controllerJustSelected && !kugouSwitchProtect && controllerPkg != null && !isAudioReallyPlaying(controllerPkg)) {
            String realPlayingPkg = getRealPlayingMusicPkg();
            if (realPlayingPkg != null && !realPlayingPkg.equals(controllerPkg)) {
                Log.i(TAG, "dispatchMediaAction: current controller " + controllerPkg
                        + " not playing, auto-switch to real playing source " + realPlayingPkg);
                MediaController target = findControllerByPackage(realPlayingPkg);
                if (target != null) {
                    selectController(target);
                } else {
                    // 找不到控制器,至少更新 controllerPkg 和 ghostControllerPkg
                    controllerPkg = realPlayingPkg;
                    ghostControllerPkg = realPlayingPkg;
                }
            }
        }
        // 用户刚主动选了控制器:第一次操作后不立即重置,而是延迟 3 秒后重置。
        // 给酷狗等播放器足够的启动时间(MediaSession active),避免 handlePlaybackConfigChanged
        // 在启动窗口期内误切到其他在播源。3 秒后酷狗 MediaSession 应该已经 active,
        // 此时即使自动切换也会因为"当前控制器真在播"而跳过。
        if (controllerJustSelected) {
            Log.i(TAG, "dispatchMediaAction: controllerJustSelected=true, honor user selection " + controllerPkg
                    + " (will reset after 3s)");
            final android.os.Handler h = lyricPollHandler;
            if (h != null) {
                h.removeCallbacks(resetControllerJustSelectedRunnable);
                h.postDelayed(resetControllerJustSelectedRunnable, 5000);
            } else {
                controllerJustSelected = false;
            }
            // 用户正在操作 ghost 源(选酷狗后点播放/切歌),同步延长 ghost
            // 保护窗口,给酷狗等播放器足够的 MediaSession 激活时间,防止启动窗口期
            // 被 handlePlaybackConfigChanged/updateAvailableControllers 误切到其他在播源。
            if (ghostControllerPkg != null) {
                ghostProtectUntil = System.currentTimeMillis() + 5000L;
            }
        }
        String targetPkg = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;

        // 把 "playpause" 转换为 "play" 或 "pause"
        // executeTransportAction() 不支持 "playpause",只支持 "play"/"pause"/"next"/"prev"
        // 用真实音频状态判断当前是否在播,汽水等 App 暂停时 MediaSession state 残留 PLAYING,
        // 不能用 mediaController.getPlaybackState() 判断。
        if ("playpause".equals(action)) {
            int audioReal = getAudioRealPlayState();
            boolean isPlaying = (audioReal == STATE_PLAYING);
            action = isPlaying ? "pause" : "play";
            Log.i(TAG, "dispatchMediaAction: playpause resolved to " + action
                    + " (audioReal=" + audioReal + ")");
        }

        Log.i(TAG, "dispatchMediaAction: action=" + action + " targetPkg=" + targetPkg
                + " mediaController=" + (mediaController != null ? mediaController.getPackageName() : "null")
                + " ghostPkg=" + ghostControllerPkg);

        // ===== 第1级:已知播放器特殊处理(已验证有效,优先使用) =====
        if (handleKnownPlayerSpecial(targetPkg, action)) {
            Log.i(TAG, "dispatchMediaAction: handled by known player special (level 1)");
            // 酷狗播放后 MediaSession 可能延迟 active,positionTickerRunnable
            // 可能还没启动(因为 pushMetadataToCard 依赖 currentTitle)。
            // 主动延迟刷新元数据,确保歌名歌手能显示,不需要用户重新选控制器。
            // 酷狗 play/next/prev 后都主动刷新元数据
            // (酷狗 MediaSession 回调不可靠,切歌后 onMetadataChanged 可能不触发)
            if ((PKG_KUGOU.equals(targetPkg) || PKG_KUGOU_LITE.equals(targetPkg))
                    && ("play".equals(action) || "next".equals(action) || "prev".equals(action))) {
                scheduleKugouMetadataRefresh();
            }
            return;
        }

        // ===== 第2级:标准 mediaController.transportControls =====
        if (mediaController != null && !controllerStale) {
            try {
                executeTransportAction(mediaController, action);
                Log.i(TAG, "dispatchMediaAction: handled by transportControls (level 2)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "dispatchMediaAction: transportControls failed: " + t.getMessage());
            }
        }

        // ===== 第3级:尝试重新获取 mediaController =====
        Log.i(TAG, "dispatchMediaAction: trying to reconnect mediaController for " + targetPkg);
        MediaController reconnected = findControllerByPackage(targetPkg);
        if (reconnected != null) {
            mediaController = reconnected;
            controllerStale = false;
            try {
                executeTransportAction(mediaController, action);
                Log.i(TAG, "dispatchMediaAction: handled by reconnected transportControls (level 3)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "dispatchMediaAction: reconnected transportControls failed: " + t.getMessage());
            }
        }

        // ===== 第4级:AudioManager.dispatchMediaKeyEvent() 发送系统媒体键(通用 fallback) =====
        Log.i(TAG, "dispatchMediaAction: fallback to system media key (level 4)");
        dispatchSystemMediaKey(action);
    }

 /**
 * 已知播放器特殊处理(第1级 fallback)
 * @return true 表示已处理,不需要继续 fallback
 */
    private boolean handleKnownPlayerSpecial(String pkg, String action) {
        if (pkg == null) return false;

        // 酷狗:直接 startService 启动 KugouPlaybackService
        if ("com.kugou.android.auto".equals(pkg)) {
            String kugouCommand;
            int kugouKeyCode;
            if ("next".equals(action)) {
                kugouCommand = "next";
                kugouKeyCode = 87;
            } else if ("prev".equals(action)) {
                kugouCommand = "previous";
                kugouKeyCode = 88;
            } else if ("play".equals(action) || "pause".equals(action) || "playpause".equals(action)) {
                kugouCommand = "togglepause";
                kugouKeyCode = 85;
            } else {
                return false;
            }
            try {
                Intent kgIntent = new Intent();
                kgIntent.setClassName("com.kugou.android.auto",
                        "com.kugou.framework.service.KugouPlaybackService");
                kgIntent.putExtra("command", kugouCommand);
                kgIntent.putExtra("keycode", kugouKeyCode);
                startService(kgIntent);
                // 记录酷狗按键时间,切歌后3秒保护窗口
                // (酷狗切歌 AudioTrack 空窗期会被误判为不在播,导致自动切到其他音源)
                lastKugouKeyEventTime = System.currentTimeMillis();
                Log.i(TAG, "handleKnownPlayerSpecial: kugou startService command=" + kugouCommand + " keycode=" + kugouKeyCode);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "handleKnownPlayerSpecial kugou error: " + t.getMessage());
                return false;
            }
        }

        // 酷我:双通道路由 
        // uid1000 身份→旧广播 kuwo.musichd_car.*(manifest receiver,冷启也能送达)
        // 普通身份→新广播 cn.kuwo.kwmusicauto.action.MEDIA_BUTTON(runtime-exported,普通应用可送达)
        // 入口 isKuwoPkg 放行 kwmusiccar/kwmusiccas 多包名;pkg 由调用方传实际安装包
        if (isKuwoPkg(pkg)) {
            boolean handled = dispatchKuwoControl(pkg, action);
            if (handled) {
                Log.i(TAG, "handleKnownPlayerSpecial: kuwo handled (level 1) action=" + action);
                return true;
            }
            // 未命中命令:交回四级 fallback
        }

        // 其他播放器:不处理,继续 fallback
        // 注意:汽水音乐(com.luna.music.car)不需要特殊处理,transportControls 方式正常工作
        return false;
    }

 /**
 * 酷我控制双通道路由(去 sharedUserId 后非导出广播失效的替代方案)。
 * - uid1000 身份(sharedUserId=android.uid.system):旧广播 kuwo.musichd_car.* → NotificationReceiver
 * (manifest receiver,AMS 冷启也能拉起酷我投递,更稳;但仅 uid1000 可送达非导出 receiver)
 * - 普通身份(uid=100xx):新广播 cn.kuwo.kwmusicauto.action.MEDIA_BUTTON → 运行时注册的
 * KwCarPlay.a$h(runtime receiver,两参 registerReceiver 默认导出,普通应用可送达;酷我进程须存活)
 * - 多包名兼容:入口 isKuwoPkg 放行 kwmusiccar/kwmusiccas;pkg 由调用方传实际安装包。
 * @return true 表示已发出命令(是否送达取决于身份/进程,无法在此确认)
 */
    private boolean dispatchKuwoControl(String pkg, String action) {
        boolean systemUid = (android.os.Process.myUid() == android.os.Process.SYSTEM_UID);
        if (systemUid && sendKuwoCarBroadcast(pkg, action)) {
            Log.i(TAG, "dispatchKuwoControl: system-uid, kuwo car broadcast " + action + " -> " + pkg);
            return true;
        }
        if (systemUid) {
            Log.w(TAG, "dispatchKuwoControl: system-uid kuwo car broadcast failed, fallback to MediaButton");
        }
        return sendKuwoMediaButton(pkg, action);
    }

 /**
 * 旧通道:kuwo.musichd_car.* → cn.kuwo.mod.notification.NotificationReceiver。
 * 该 receiver 为 manifest 声明且 exported=false,仅 uid1000(SYSTEM)/同 uid 可送达;
 * manifest receiver 由 AMS 投递,酷我进程冷启时也会被拉起。
 */
    private boolean sendKuwoCarBroadcast(String pkg, String action) {
        try {
            String broadcastAction = null;
            if ("playpause".equals(action) || "play".equals(action) || "pause".equals(action)) {
                broadcastAction = "kuwo.musichd_car.playing";
            } else if ("next".equals(action)) {
                broadcastAction = "kuwo.musichd_car.next";
            } else if ("prev".equals(action)) {
                broadcastAction = "kuwo.musichd_car.pre";
            }
            if (broadcastAction == null) return false;
            Intent intent = new Intent(broadcastAction);
            intent.setPackage(pkg);
            intent.setComponent(new android.content.ComponentName(
                    pkg, "cn.kuwo.mod.notification.NotificationReceiver"));
            sendBroadcast(intent);
            Log.i(TAG, "sendKuwoCarBroadcast: " + broadcastAction + " -> " + pkg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendKuwoCarBroadcast error: " + t.getMessage());
            return false;
        }
    }

 /**
 * 新通道:cn.kuwo.kwmusicauto.action.MEDIA_BUTTON → 运行时注册的 KwCarPlay.a$h。
 * 两参 registerReceiver(默认导出,无权限门槛),普通应用 uid 即可送达;
 * 命令为 string extra "EXTRA"(勿设 Hardware_MediaButton=true,否则走硬件过滤分支)。
 */
    private boolean sendKuwoMediaButton(String pkg, String action) {
        try {
            String cmd = null;
            if ("next".equals(action)) {
                cmd = "MEDIA_NEXT";
            } else if ("prev".equals(action)) {
                cmd = "MEDIA_PRE";
            } else if ("playpause".equals(action)) {
                cmd = "MEDIA_PLAY_PAUSE";
            } else if ("play".equals(action)) {
                cmd = "MEDIA_PLAY";
            } else if ("pause".equals(action)) {
                cmd = "MEDIA_PAUSE";
            }
            if (cmd == null) return false;
            Intent intent = new Intent("cn.kuwo.kwmusicauto.action.MEDIA_BUTTON");
            intent.setPackage(pkg);               // 不写死组件名,兼容 kwmusiccas 变体
            intent.putExtra("EXTRA", cmd);
            sendBroadcast(intent);
            Log.i(TAG, "sendKuwoMediaButton: " + cmd + " -> " + pkg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "sendKuwoMediaButton error: " + t.getMessage());
            return false;
        }
    }

 /**
 * 定向发送媒体键给目标播放器自己的 MediaButtonReceiver。
 * 背景:CarMedia 注册 FLAG_HANDLES_MEDIA_BUTTONS 媒体键代理(方案A)后,系统媒体键被
 * MediaSessionService 直接 dispatch 到 CarMedia 代理,播放器自身的 MediaButtonReceiver
 * 收不到(AudioManager.dispatchMediaKeyEvent 发的键也会被 onMediaButtonEvent 的
 * self-dispatch 吸收)。对未知播放器(非酷狗/酷我等有专属通道的),用反射拿
 * MediaController.getMediaButtonReceiver() 的 PendingIntent 直接定向 send(),
 * 绕过系统路由直达播放器。成功返回 true。
 */
    private boolean dispatchMediaButtonToReceiver(MediaController target, String action) {
        try {
            int keyCode;
            if ("next".equals(action)) {
                keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
            } else if ("prev".equals(action)) {
                keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            } else {
                keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            }
            java.lang.reflect.Method m = android.media.session.MediaController.class.getMethod("getMediaButtonReceiver");
            Object pi = m.invoke(target);
            if (pi instanceof android.app.PendingIntent) {
                android.app.PendingIntent pendingIntent = (android.app.PendingIntent) pi;
                Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                long now = android.os.SystemClock.uptimeMillis();
                keyIntent.putExtra(Intent.EXTRA_KEY_EVENT,
                        new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
                pendingIntent.send(CarMediaService.this, 0, keyIntent);
                keyIntent.putExtra(Intent.EXTRA_KEY_EVENT,
                        new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
                pendingIntent.send(CarMediaService.this, 0, keyIntent);
                Log.i(TAG, "dispatchMediaButtonToReceiver: sent keyCode=" + keyCode
                        + " to " + target.getPackageName());
                return true;
            }
            Log.d(TAG, "dispatchMediaButtonToReceiver: no MediaButtonReceiver for " + target.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "dispatchMediaButtonToReceiver error: " + t.getMessage());
        }
        return false;
    }

 /**
 * 第4级 fallback:AudioManager.dispatchMediaKeyEvent() 发送系统媒体键
 * 对汽水音乐等 transportControls 无效但响应系统媒体键的播放器有效
 */
    private void dispatchSystemMediaKey(String action) {
        try {
            // 防自触发死循环——无控制器/无 ghost 时,派发的系统媒体键会路由回
            // CarMedia 自建 MediaSession 的媒体键代理 Callback(onMediaButtonEvent → sendXxx →
            // dispatchMediaAction → 第4级 → 本方法),形成无休止 ping-pong。
            // 此时没有任何真实音源可控制,直接放弃。
            if (controllerPkg == null && ghostControllerPkg == null) {
                Log.w(TAG, "dispatchSystemMediaKey: no controller/ghost, skip (loop guard)");
                return;
            }
            // 标记自触发:派发后若系统把媒体键路由回 CarMedia 自身的媒体键代理 Callback,
            // onMediaButtonEvent 会据此吸收该键,打断循环(见 onMediaButtonEvent)。
            markSelfMediaKeyDispatch();
            // 优先定向发送目标播放器自己的 MediaButtonReceiver(绕过系统路由)。
            // 系统媒体键会被 CarMedia 媒体键代理吸收,AudioManager.dispatchMediaKeyEvent 发的键
            // 同样回到代理被 self-dispatch 吸收;定向 PendingIntent 直达播放器,对未知播放器
            // (非酷狗/酷我)是唯一可靠的第4级兜底。
            MediaController btTarget = (mediaController != null && !controllerStale)
                    ? mediaController
                    : findControllerByPackage(controllerPkg != null ? controllerPkg : ghostControllerPkg);
            if (btTarget != null && dispatchMediaButtonToReceiver(btTarget, action)) {
                return;
            }
            int keyCode;
            if ("next".equals(action)) {
                keyCode = 87; // KEYCODE_MEDIA_NEXT
            } else if ("prev".equals(action)) {
                keyCode = 88; // KEYCODE_MEDIA_PREVIOUS
            } else {
                keyCode = 85; // KEYCODE_MEDIA_PLAY_PAUSE
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                long now = android.os.SystemClock.uptimeMillis();
                android.view.KeyEvent down = new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                android.view.KeyEvent up = new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                am.dispatchMediaKeyEvent(down);
                am.dispatchMediaKeyEvent(up);
                Log.i(TAG, "dispatchSystemMediaKey: keyCode=" + keyCode + " action=" + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatchSystemMediaKey error: " + t.getMessage());
        }
    }

 /**
 * ghost 控制器(暂停态会话)的媒体控制:发 media key 激活酷我等。
 * 酷我 active=false 拿不到 MediaController,dispatchMediaKeyEvent 发到系统,
 * MediaSession 服务会把 key 路由给最近的 media button session(即酷我)。
 */
    private void dispatchMediaKeyForGhost(String action) {
        try {
            // 酷狗使用内部广播 media.button.keyevent!
            // 反编译 MediaButtonIntentReceiver.smali 确认:
            // FromMediaSession=true 分支发送 action="media.button.keyevent", extra="key_media"=keyCode
            // 这个广播绕过了 MediaButtonIntentReceiver 的单击/双击/三击计数器逻辑,直接发给酷狗内部组件。
            // 之前用 AudioManager.dispatchMediaKeyEvent() 发送 85 时,酷狗会误判为下一首(计数器bug),
            // 用内部广播可以精确控制播放/暂停、上一首、下一首。
            if ("com.kugou.android.auto".equals(ghostControllerPkg)) {
                // 酷狗直接 startService 启动 KugouPlaybackService!
                // 反编译 c.smali 确认:所有媒体键命令最终都是通过
                // intent.setClass(context, KugouPlaybackService.class) + startService(intent) 处理的。
                // 不需要 mediaController,不需要媒体键,直接 startService 即可!
                String kugouCommand;
                int kugouKeyCode;
                if ("next".equals(action)) {
                    kugouCommand = "next";
                    kugouKeyCode = 87;
                } else if ("prev".equals(action)) {
                    kugouCommand = "previous";
                    kugouKeyCode = 88;
                } else if ("playpause".equals(action)) {
                    // 酷狗用 togglepause+85 切换播放/暂停(始终有效)
                    kugouCommand = "togglepause";
                    kugouKeyCode = 85;
                } else {
                    Log.w(TAG, "dispatchMediaKeyForGhost: unsupported action " + action + " for kugou");
                    return;
                }
                try {
                    Intent kgIntent = new Intent();
                    kgIntent.setClassName("com.kugou.android.auto",
                            "com.kugou.framework.service.KugouPlaybackService");
                    kgIntent.putExtra("command", kugouCommand);
                    kgIntent.putExtra("keycode", kugouKeyCode);
                    startService(kgIntent);
                    Log.i(TAG, "dispatchMediaKeyForGhost: kugou startService KugouPlaybackService command="
                            + kugouCommand + " keycode=" + kugouKeyCode);
                } catch (Throwable t) {
                    Log.w(TAG, "dispatchMediaKeyForGhost kugou startService error: " + t.getMessage());
                }
                return;
            }
            // 酷我:双通道路由 
            // 非导出 NotificationReceiver 广播仅 uid1000 身份可送达;MediaButton 广播普通身份可送达
            if (ghostControllerPkg == null) return;
            if (isKuwoPkg(ghostControllerPkg)) {
                dispatchKuwoControl(ghostControllerPkg, action);
            }
            // 发完后:不清 ghost,延迟检查酷我是否变 active=true
            // 如果变 active 了,清 ghost 并正常选 controller;如果没变,保持 ghost 不切走
            easFocusHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (ghostControllerPkg == null) return;
                    MediaController ctrl = findControllerByPackage(ghostControllerPkg);
                    if (ctrl != null) {
                        Log.i(TAG, "dispatchMediaKeyForGhost: " + ghostControllerPkg + " now active, clearing ghost");
                        ghostControllerPkg = null;
                        controllerStale = true;
                        updateAvailableControllers();
                    } else {
                        Log.d(TAG, "dispatchMediaKeyForGhost: " + ghostControllerPkg + " still inactive, keep ghost");
                    }
                }
            }, 1500L);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchMediaKeyForGhost error: " + t.getMessage());
        }
    }
    private void sendPlayPause() {
        // 非蓝牙目标统一走 dispatchMediaAction(),四级 fallback 全兼容
        // 蓝牙目标保留特殊处理(音频焦点管理、XCMedia2 no-focus pause 兜底等)
        MediaController target = keyTargetController();
        boolean btTarget = (target != null && PKG_BLUETOOTH.equals(target.getPackageName()));
        if (!btTarget) {
            dispatchMediaAction("playpause");
            return;
        }
        // 以下为蓝牙目标的特殊处理逻辑
        if (target == null) {
            Log.w(TAG, "sendPlayPause: no controller");
            return;
        }
        // 判定当前是否在播:用真实音频状态,不用 MediaSession state。
        // 汽水等 App 暂停时 MediaSession state 残留 PLAYING(不更新),
        // 用 ps.getState()==PLAYING 判断会把"暂停"误判成"播放"→ 点播放按钮
        // 实际调 pause() 无效(用户实测:暂停后播放按钮没反应)。
        // 播放中几乎必然有 started 的 AudioTrack:audioReal==PLAYING 才 pause,
        // 否则 play。蓝牙例外:A2DP sink AudioTrack 常驻,audioReal 不可靠
        // (暂停时仍可能报 started)--蓝牙以 controller 实时会话状态为准。
        boolean shouldPause;
        if (btTarget) {
            PlaybackState ps = target.getPlaybackState();
            shouldPause = (ps != null && ps.getState() == STATE_PLAYING);
            Log.i(TAG, "sendPlayPause: bluetooth controller, psState=" + (ps != null ? ps.getState() : -1)
                    + " -> " + (shouldPause ? "pause" : "play"));
            // (蓝牙要点2次才播放修复):蓝牙"要播放"前,先释放 CarMedia 自己持有的
            // MAY_DUCK 焦点。蓝牙 A2DP 播放瞬间 A2dpSinkStreamHandler 也要请求音频焦点,
            // 但焦点还握在 CarMedia 手里(切蓝牙时 requestBluetoothFocusIfNeeded 已持有),
            // 焦点转移未完成时 XCMedia2 的 BTMusicStateManagerP.onA2dpStateChanged 检测到
            // "蓝牙在播但 no focus" 会主动 AVRCP 暂停蓝牙(日志确认:bt (2).log
            // 10:22:57.943 "no focus to pause!!!" → 10:22:58.010 A2dpMediaBrowserService onPause),
            // 导致第一次点播放必被暂停,焦点转移完成后第二次点才成功。
            // 播放前 abandon → 蓝牙无竞争者立即获得焦点 → XCMedia2 不暂停。
            if (!shouldPause) {
                // ① 释放 CarMedia 可能残留的 MAY_DUCK 焦点(废弃 MAY_DUCK 方案的残留清理,
                // 防止历史版本/蓝牙连接期残留的焦点把蓝牙 A2DP duck 成没声音)
                abandonBluetoothFocus();
                // ② 播放前主动暂停其他在播音源(QQ 等),让它们释放音频焦点——
                // 否则 QQ 正播持有 GAIN 时蓝牙 play 抢焦点有转移延迟,延迟窗口内
                // XCMedia2 的 EAS 焦点判定(bMediaCenterFocus=false)又会暂停蓝牙
                // (用户实测:QQ 播放中切回蓝牙要点 2 次才播)。
                // ( v4:不再 request MAY_DUCK——反编译确认 XCMedia2 不处理
                // 音频焦点,MAY_DUCK 只把蓝牙 A2DP duck 成没声音。蓝牙播放时由蓝牙
                // 自己持 GAIN 焦点。)
                pauseOtherSourcesOnBluetoothPlay();
            }
        } else {
            int audioReal = getAudioRealPlayState();
            shouldPause = (audioReal == STATE_PLAYING);
            Log.i(TAG, "sendPlayPause: audioReal=" + audioReal + " (3=playing) -> "
                    + (shouldPause ? "pause" : "play"));
        }
        if (shouldPause) {
            executeTransportAction(target, "pause");
        } else {
            executeTransportAction(target, "play");
            // v3(蓝牙点一次就播的兜底):XCMedia2 可能因 EAS 焦点不在
            // 媒体中心(bMediaCenterFocus=false)在蓝牙播放瞬间 AVRCP 暂停蓝牙
            // (no focus to pause)。它抢回 EAS 焦点需要 ~1s(bt (3).log:
            // 13:22:29.942 暂停 → 13:22:30.104 抢回焦点)。延迟 ~800ms 检查一次,
            // 若蓝牙仍非 PLAYING(被暂停),自动补一次 play——等效"自动点第二次",
            // 用户只点一次即可听到蓝牙音乐。此时 XCMedia2 已抢回焦点,补的 play 成功。
            if (btTarget) {
                final MediaController c = target;
                firstPushHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (c == null || !isBluetoothSessionActive()) {
                                return;
                            }
                            PlaybackState ps = c.getPlaybackState();
                            if (ps == null || ps.getState() != PlaybackState.STATE_PLAYING) {
                                Log.i(TAG, "sendPlayPause: bt not playing after XCMedia2"
                                        + " no-focus pause, auto re-play once");
                                executeTransportAction(c, "play");
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "sendPlayPause bt replay error: " + t.getMessage());
                        }
                    }
                }, 800L);
            }
        }
    }
 /**
 * 方向盘三键的目标控制器。
 * v8:直接返回当前控制器 mediaController——三键控制"当前正在控制的音源"。
 * 撤销 v7 的"蓝牙会话存在即蓝牙优先"劫持:那会让用户在听 QQ 时按三键却切到蓝牙
 * (真车反馈"除了蓝牙所有音源都不能按键切歌"),也让蓝牙暂停时按播放/暂停拉起蓝牙。
 * 蓝牙在播时 mediaController 本来就是蓝牙 → 三键天然直达蓝牙;QQ 在播时 mediaController
 * 是 QQ → 切 QQ。未勾选屏蔽三键时三键由系统路由活跃会话,本方法只在勾选(系统通道禁用)
 * 时被 CarMedia 使用。
 */
 /** 方控按键的目标控制器。
 * 优先用当前 mediaController;stale/null 时先刷新;仍无控制器时 fallback 到
 * mediacenter 代理(手机互联场景:CarPlay/HiCar/CarLife 等无自己的 MediaSession,
 * 播放控制全走 mediacenter)。这层兜底确保即使 updateAvailableControllers 因时序
 * 问题没选中 mediacenter,方控切歌也能拿到目标。 */
    private MediaController keyTargetController() {
        if (mediaController != null && !controllerStale) {
            return mediaController;
        }
        refreshController();
        if (mediaController != null) {
            return mediaController;
        }
        // 仍无控制器 → fallback 到 mediacenter 代理(手机互联场景)
        MediaController mcProxy = findActiveMediacenterController();
        if (mcProxy != null) {
            Log.i(TAG, "keyTargetController: fallback to mediacenter proxy");
            return mcProxy;
        }
        return null;
    }

 /**
 * 统一传输控制命令分发。
 * mediacenter(ecarx.xsf.mediacenter)作为手机互联 app 的系统代理控制器时,
 * 其 MediaSession 回调只处理 dispatchMediaButtonEvent(标准 KeyEvent),不响应
 * transportControls.skipToNext()/play() 等命令(日志确认系统通过 MediaSessionService
 * "Sending KeyEvent ... to ecarx.xsf.mediacenter" 来控制,不是 skipToNext)。
 * 因此 mediacenter 场景下用 dispatchMediaButtonEvent 发送 KEYCODE_MEDIA_NEXT/PREVIOUS/PLAY/PAUSE;
 * 其他控制器(QQ/网易云/酷我/蓝牙等)继续用 transportControls 命令。
 */
    private void executeTransportAction(MediaController target, String action) {
                // (用户实测:QQ 播放中切回蓝牙要点 2 次才播)。
                // XCMedia2 的 EAS 焦点判定(bMediaCenterFocus=false)又会暂停蓝牙
        if (target == null) {
            Log.w(TAG, "executeTransportAction: target is null, action=" + action);
            return;
        }
        if ("ecarx.xsf.mediacenter".equals(target.getPackageName())) {
            int keyCode;
            if ("next".equals(action)) keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
            else if ("prev".equals(action)) keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            else if ("play".equals(action)) keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
            else if ("pause".equals(action)) keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
            else {
                Log.w(TAG, "executeTransportAction: unsupported action " + action + " for mediacenter");
                return;
            }
            dispatchMediaKey(target, keyCode);
            return;
        }
        // 方案B修复 :酷狗(com.kugou.android.auto)的媒体键控制完全靠
        // BroadcastReceiver(com.kugou.framework.service.headset.MediaButtonIntentReceiver,
        // priority=Integer.MAX_VALUE),其 MediaSession.Callback 不处理 onMediaButtonEvent。
        // 禁用其 Receiver 后 dispatchMediaButtonEvent 无效(日志确认:系统无 Sending KeyEvent 分发)。
        // 正确方式:通过 MediaController.getMediaButtonReceiver() 拿到 PendingIntent,直接 send()
        // 酷狗必须用 AudioManager.dispatchMediaKeyEvent()!
        // 反编译+实测结论:
        // 1. setComponent 定向广播 -> 酷狗 MediaButtonIntentReceiver 收不到(进程无日志)
        // 2. MediaController.dispatchMediaButtonEvent() -> 酷狗无响应
        // 3. 系统媒体键(input keyevent KEYCODE_MEDIA_NEXT) -> 酷狗能切歌!
        // 4. 系统媒体键(input keyevent KEYCODE_MEDIA_PLAY_PAUSE) -> 酷狗能收到但不暂停(酷狗自身bug)
        // 结论:只有 AudioManager.dispatchMediaKeyEvent() 路径和系统媒体键完全一致,
        // 会把媒体键交给 MediaSessionService 分发给当前媒体按钮会话(酷狗)。
        // 酷狗的播放/暂停不生效是酷狗自身的计数器bug,不是 CarMedia 的问题。
        // 切歌(next/previous)已验证能生效。
                // 酷狗使用内部广播 media.button.keyevent!
        // 反编译确认:a$a.onReceive() 接收此广播:
        // - keyCode=85 -> 播放/暂停切换(调用 a.d())
        // - keyCode=87 -> 下一首(调用 UltimateSongPlayer.next())
        // - keyCode=88 -> 上一首(调用 i0.I0())
        // 防抖动:两次广播之间需要间隔至少400毫秒
        // 之前用 AudioManager.dispatchMediaKeyEvent() 发送系统媒体键时,
        // 酷狗的 MediaButtonIntentReceiver 对 85 走延迟计数器分支,会误判为下一首。
        // 用内部广播可以精确控制,绕过计数器逻辑。
        if ("com.kugou.android.auto".equals(target.getPackageName())) {
            // 酷狗直接 startService 启动 KugouPlaybackService!
            // 反编译 c.smali 确认:所有媒体键命令最终都是通过
            // intent.setClass(context, KugouPlaybackService.class) + startService(intent) 处理的。
            // Intent 中包含 command("play"/"pause"/"next"/"previous") 和 keycode。
            // 不需要模拟点击,不需要媒体键,直接 startService 即可!
            String kugouCommand;
            int kugouKeyCode;
            if ("next".equals(action)) {
                kugouCommand = "next";
                kugouKeyCode = 87;
            } else if ("prev".equals(action)) {
                kugouCommand = "previous";
                kugouKeyCode = 88;
            } else if ("play".equals(action) || "pause".equals(action) || "playpause".equals(action)) {
                // 酷狗用 togglepause+85 切换播放/暂停!
                // 实测确认:play+126/pause+127 在酷狗暂停后KugouPlaybackService被杀死时无效,
                // 但 togglepause+85 始终有效(不管播放还是暂停状态)。
                kugouCommand = "togglepause";
                kugouKeyCode = 85;
            } else {
                Log.w(TAG, "executeTransportAction: unsupported action " + action + " for kugou");
                return;
            }
            try {
                Intent kgIntent = new Intent();
                kgIntent.setClassName("com.kugou.android.auto",
                        "com.kugou.framework.service.KugouPlaybackService");
                kgIntent.putExtra("command", kugouCommand);
                kgIntent.putExtra("keycode", kugouKeyCode);
                startService(kgIntent);
                Log.i(TAG, "executeTransportAction: kugou startService KugouPlaybackService command="
                        + kugouCommand + " keycode=" + kugouKeyCode);
            } catch (Throwable t) {
                Log.w(TAG, "executeTransportAction kugou startService error: " + t.getMessage());
            }
            return;
        }
// 其他普通控制器:用 transportControls 命令
        try {
            if ("next".equals(action)) target.getTransportControls().skipToNext();
            else if ("prev".equals(action)) target.getTransportControls().skipToPrevious();
            else if ("play".equals(action)) target.getTransportControls().play();
            else if ("pause".equals(action)) target.getTransportControls().pause();
        } catch (Throwable t) {
            Log.w(TAG, "executeTransportAction error: " + t.getMessage());
        }
    }

 /** 用 dispatchMediaButtonEvent 向控制器发送标准媒体键(down+up)。
 * 用于 mediacenter 代理场景——其 MediaSession 回调只处理 KeyEvent,不响应 transportControls 命令。 */
    private void dispatchMediaKey(MediaController controller, int keyCode) {
        // 记录自触发时间戳,防止 mediacenter 收到广播后再次回调 CarMedia 的 EAS 接口形成死循环
        markSelfMediaKeyDispatch();
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent down = new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
            android.view.KeyEvent up = new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
            // 不通过 MediaSession 回调(dispatchMediaButtonEvent),而是发送 ACTION_MEDIA_BUTTON 广播,
            // 直接指定 mediacenter 的 MediaKeyReceiver。日志确认 XCInputService 通过 InputManagerHelper.sendBroadcastMedia
            // 发送广播(调用栈:ECarXKeyActionBase.injectInputEvent → InputManagerHelper.injectInputEvent →
            // InputManagerHelper.sendBroadcastMedia → ContextImpl.sendBroadcast),mediacenter.MediaKeyReceiver
            // 收到后转发给手机互联 app;而 MediaSession 回调被 mediacenter 忽略(真车日志 car_full2.log 确认:
            // dispatchMediaButtonEvent 发了 40+ 次,mediacenter 进程无任何输出,CarPlay 不切歌)。
            android.content.ComponentName mcReceiver = new android.content.ComponentName(
                    "ecarx.xsf.mediacenter", "ecarx.xsf.mediacenter.MediaKeyReceiver");
            Intent downIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            downIntent.setComponent(mcReceiver);
            downIntent.putExtra(Intent.EXTRA_KEY_EVENT, down);
            sendBroadcast(downIntent);
            Intent upIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            upIntent.setComponent(mcReceiver);
            upIntent.putExtra(Intent.EXTRA_KEY_EVENT, up);
            sendBroadcast(upIntent);
            Log.i(TAG, "dispatchMediaKey: sent ACTION_MEDIA_BUTTON broadcast keyCode=" + keyCode
                    + " to ecarx.xsf.mediacenter.MediaKeyReceiver");
        } catch (Throwable t) {
            Log.w(TAG, "dispatchMediaKey error: " + t.getMessage());
        }
    }

 /** 目标包是否自带"可用"的 ACTION_MEDIA_BUTTON 静态接收器(runtime 检测,无白名单)。
 * 有 → 物理方向盘键的广播(非有序、优先级 MAX 拦不住)本来就必达该接收器并切歌;
 * CarMedia 再 transportControls 就是第二刀 → 连切2首(酷狗概念版确认)。
 * 此时 CarMedia 让位给 App 接收器(physicalSend 跳过),单切。
 * 无 → 该包没有自己的媒体键接收器,CarMedia 必须 transportControls 接管(蓝牙等)。
 * queryBroadcastReceivers(flags=0) 默认只返回"当前可用(未禁用)"的组件:
 * 接收器被禁用 → 返回 false → CarMedia 自己切(单切);
 * 接收器可用 → 返回 true → CarMedia 让位(单切)。天然自平衡,两态都不会连切。 */
    private boolean hasOwnMediaButtonReceiver(String pkg) {
        if (pkg == null) return false;
        try {
            java.util.List<ResolveInfo> q = getPackageManager()
                    .queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0);
            if (q != null) {
                for (ResolveInfo ri : q) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai != null && pkg.equals(ai.packageName) && ai.name != null) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "hasOwnMediaButtonReceiver(" + pkg + ") failed: " + t.getMessage());
        }
        return false;
    }
 /** 向指定包自带的 MEDIA_BUTTON 接收器发标准媒体键(down+up)显式定向广播。
 * 用于 transportControls 空操作的 App(酷狗概念版等)切源离开时暂停。
 * 显式 setComponent 只达目标包,不广播给其他已启用接收器。 */
    private void sendMediaButtonToPkg(String pkg, int keyCode) {
        try {
            java.util.List<ResolveInfo> q = getPackageManager()
                    .queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0);
            if (q != null) {
                for (ResolveInfo ri : q) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai == null || ai.name == null) continue;
                    if (!pkg.equals(ai.packageName)) continue;
                    markSelfMediaKeyDispatch();
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.KeyEvent down = new android.view.KeyEvent(now, now,
                            android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                    android.view.KeyEvent up = new android.view.KeyEvent(now, now,
                            android.view.KeyEvent.ACTION_UP, keyCode, 0);
                    ComponentName comp = new ComponentName(pkg, ai.name);
                    Intent di = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    di.setComponent(comp);
                    di.putExtra(Intent.EXTRA_KEY_EVENT, down);
                    sendBroadcast(di);
                    Intent ui = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    ui.setComponent(comp);
                    ui.putExtra(Intent.EXTRA_KEY_EVENT, up);
                    sendBroadcast(ui);
                    Log.i(TAG, "sendMediaButtonToPkg: sent keyCode=" + keyCode
                            + " to " + pkg + "/" + ai.name);
                    return;
                }
            }
            Log.w(TAG, "sendMediaButtonToPkg: no enabled MEDIA_BUTTON receiver for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "sendMediaButtonToPkg error: " + t.getMessage());
        }
    }
 /** 切到不同第三方音源时暂停"上一个控制器",避免双音源同播(概念版切走后仍在播)。
 * 通用、无白名单:transportControls 对概念版空操作 → 补发 KEYCODE_MEDIA_PAUSE 到其
 * 接收器(反编译确认 0x7f 映射 "pause");网易云/酷我/QQ 等 transportControls 即可。
 * 幂等安全:已暂停的 App 重复 pause 无副作用;PAUSE(非 PLAY_PAUSE)不会误触发播放。 */
    private void pausePreviousSourceOnSwitch(MediaController prevController, String prevPkg, String newPkg) {
        if (prevController == null || prevPkg == null) return;
        if (prevPkg.equals(newPkg)) return;
        // 系统/蓝牙/USB/通话/盒子信息源不在"可暂停第三方播放器"范围(USB 切走已单独处理)
        if (PKG_BLUETOOTH.equals(prevPkg)
                || "com.android.server.telecom".equals(prevPkg)
                || UsbSourceController.SOURCE_PKG_USB.equals(prevPkg)
                || isHiddenController(prevPkg)
                || isCarplayBoxRealPkg(prevPkg)) {
            return;
        }
        try {
            executeTransportAction(prevController, "pause");
            // 概念版等 transportControls 空操作:再向它接收器补发 PAUSE。
            // 用 KEYCODE_MEDIA_PAUSE 而非 PLAY_PAUSE——幂等,绝不会把已暂停的 App 误切回播放。
            if (hasOwnMediaButtonReceiver(prevPkg)) {
                sendMediaButtonToPkg(prevPkg, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);
                Log.i(TAG, "switch source: pause previous also via MEDIA_BUTTON " + prevPkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pausePreviousSourceOnSwitch error: " + t.getMessage());
        }
    }
 /** 物理方向盘键统一出口:目标 App 自带可用 MEDIA_BUTTON 接收器时让位给它,
 * 不自己 transportControls——否则"CarMedia 切 + 车广播到其接收器切"= 连切2首。
 * 仅物理键通道(logcat / blocked-3keys / MEDIA_BUTTON blocker)调用;
 * EAS 卡片 / MDC / UI 无车广播,仍走 sendNext,不受影响。 */
    private void physicalSend(String action) {
        String target = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
        if (("next".equals(action) || "prev".equals(action)
                || "play".equals(action) || "pause".equals(action) || "playpause".equals(action))
                && hasOwnMediaButtonReceiver(target)) {
            Log.i(TAG, "physicalSend: defer " + action + " to " + target
                    + "'s own MEDIA_BUTTON receiver (防连切2首)");
            // 概念版切歌走"让位给接收器"(不经 dispatchMediaAction),而概念版
            // MediaSession 回调不可靠、onMetadataChanged 可能不触发 → 歌名/歌手卡旧值。
            // 让位后主动按 250ms/1/3/6/10s 拉取最新元数据,确保主界面/EAS 歌名歌手更新。
            if (PKG_KUGOU.equals(target) || PKG_KUGOU_LITE.equals(target)) {
                scheduleKugouMetadataRefresh();
            }
            // 让位后若接收器窗口内没真切歌(接收器 inert,如网易云 IOT 只认
            // MediaSession transportControls),由 CarMedia 兜底发送命令切歌。通用自校准、
            // 非白名单:任何 App 广播切不到它 CarMedia 就接管。窗口 900ms 给概念版
            // 元数据刷新留时间——若元数据在窗口内已变化(接收器真切)则放弃兜底防双切。
            scheduleReceiverCutFallback(action);
            return;
        }
        if ("next".equals(action)) sendNext();
        else if ("prev".equals(action)) sendPrevious();
        else sendPlayPause();
    }
 /** 让位给接收器/系统注入后的兜底:窗口内若接收器没真切歌(接收器存在但 inert,如网易云 IOT
 * 只认 MediaSession transportControls;或广播未达接收器),由 CarMedia 接管发送命令切歌。
 * 通用自校准:任何 App 广播切不到它 CarMedia 就接管,无需白名单、无需为每个播放器反编译。
 * 防双切:①窗口内若歌曲元数据已变化(接收器已真切歌)则放弃兜底;②同方向窗口内只兜底一次。 */
    private void scheduleReceiverCutFallback(final String action) {
        int dir = dirOfAction(action);
        if (dir < 0 || dir >= DIR_COUNT) return;
        final String sig = currentTitle + "|" + currentArtist + "|" + playbackDuration;
        easFocusHandler.postDelayed(new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                if (lastReceiverFallbackMs[dir] > 0 && now - lastReceiverFallbackMs[dir] < 800) {
                    return; // 同方向窗口内已兜底过(防双通道各兜底一次)
                }
                if ((currentTitle + "|" + currentArtist + "|" + playbackDuration).equals(sig)) {
                    lastReceiverFallbackMs[dir] = now;
                    Log.i(TAG, "receiver-cut fallback: 让位后窗口内未切歌(" + action + "), CarMedia 接管切歌");
                    markLogcatKeyHandle(dir, SRC_LOGCAT);
                    dispatchMediaAction(action);
                }
            }
        }, 900);
    }
 /** 酷狗系(车机版/概念版)切歌后主动刷新元数据:MediaSession 回调不可靠,onMetadataChanged
 * 可能不触发,歌名/歌手会卡旧值。切歌后(无论 CarMedia 发送还是让位给接收器)按
 * 1/3/6/10s 延迟拉取 MediaController 最新元数据。 */
    private void scheduleKugouMetadataRefresh() {
        final android.os.Handler h = lyricPollHandler;
        if (h == null) return;
        // 新增 250ms 档——兜底窗口 900ms 内需看到元数据变化才能判断接收器已切,
        // 若只等 1s 档,兜底会在 900ms 时误判"没切"→ 概念版连切2首。
        h.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "proactive metadata refresh for kugou (250ms)");
                forceRefreshMetadata();
            }
        }, 250);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "proactive metadata refresh for kugou (1s)");
                forceRefreshMetadata();
            }
        }, 1000);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "proactive metadata refresh for kugou (3s)");
                forceRefreshMetadata();
            }
        }, 3000);
        // 冷启动场景:酷狗进程未运行/接收器拉起 + MediaSession 注册 + metadata 准备可能 5-10s。
        h.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "proactive metadata refresh for kugou (6s)");
                forceRefreshMetadata();
            }
        }, 6000);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "proactive metadata refresh for kugou (10s)");
                forceRefreshMetadata();
            }
        }, 10000);
    }
 /** 物理方向盘键在本方向"在途"(最近 2s 内 LogcatKeyMonitor 已解析该物理按压)。
 * 用于区分"MDC/媒体中心转发的物理键"(应让位给 App 自身)与"EAS 卡片按钮"
 * (无物理键信号,必须由 CarMedia 处理)。物理按压解析 markLogcatKeyDownSeen 在
 * LogcatKeyMonitor 解析阶段写入(不论 logcat 通道是否让位/执行),此处稳定可读。 */
    private boolean physicalWheelKeyInFlight(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return false;
        long phys = lastLogcatKeyDownSeenMs[dir];
        return phys > 0 && System.currentTimeMillis() - phys < 2000;
    }
 /** EAS/MDC 转发是否应让位:物理方向盘键在途,且 ①系统会注入媒体键直达控制器
 * (未屏蔽 + 控制器真在播)或 ②目标 App 自带可用 MEDIA_BUTTON 接收器(车广播非有序、
 * 优先级 MAX 拦不住,必达其接收器自行切歌)。让位后只剩 App 侧单通道切歌,
 * 否则 CarMedia transportControls + App 自身切 = 连切2首(酷狗概念版确认)。
 * EAS 卡片按钮无物理键信号(physicalWheelKeyInFlight=false)→ 不命中,照常处理。 */
    private boolean easForwardShouldDefer(int dir) {
        if (!physicalWheelKeyInFlight(dir)) return false;
        if (systemMediaKeyHitsController()) return true;
        String target = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
        return hasOwnMediaButtonReceiver(target);
    }
    private void sendNext() {
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("next");
    }
    private void sendPrevious() {
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("prev");
    }
    // ================= EAS 控制回调(CarMediaMusicClient.Controller) =================
    // 小卡片/仪表盘按钮 → 真实媒体控制(仿 MediaBridge MusicClientImpl 语义)
    @Override
    public void onPlayCommand() {
        // v10:双通道去重——若 500ms 内 logcat 通道已处理同一物理按键,
        // MDC 转发的这份是"第二份",忽略防双切。纯 EAS 界面按钮不受影响。
        if (isEasFromSelfDispatch()) {
            Log.d(TAG, "EAS ctrl dedup (play): self-triggered by dispatchMediaKey, ignore to break loop");
            return;
        }
        // 物理方向盘键在途 + 系统注入直达控制器/目标自带接收器 → 让位,防连切2首
        if (easForwardShouldDefer(DIR_PLAYPAUSE)) {
            Log.i(TAG, "EAS ctrl dedup (play): physical wheel key in flight, defer CarMedia (防连切2首)");
            return;
        }
        // 删除旧"让位系统通道"副本跳过块(shouldCarMediaOwnKey 已改为通用接管,
        // 物理键由 logcat 通道统一处理,副本去重由下方 isEasFromRecentLogcat 分支负责)。
        if (isEasFromRecentLogcat(DIR_PLAYPAUSE) || wasThisPhysicalPressHandledByLogcat(DIR_PLAYPAUSE)) {
            Log.d(TAG, "EAS ctrl dedup (play): logcat 通道刚处理同按键,忽略防双切");
            return;
        }
        Log.i(TAG, "EAS ctrl: PLAY");
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("play");
    }
    @Override
    public void onPauseCommand() {
        if (isEasFromSelfDispatch()) {
            Log.d(TAG, "EAS ctrl dedup (pause): self-triggered by dispatchMediaKey, ignore to break loop");
            return;
        }
        // 物理方向盘键在途 + 系统注入直达控制器/目标自带接收器 → 让位,防连切2首
        if (easForwardShouldDefer(DIR_PLAYPAUSE)) {
            Log.i(TAG, "EAS ctrl dedup (pause): physical wheel key in flight, defer CarMedia (防连切2首)");
            return;
        }
        // 删除旧"让位系统通道"副本跳过块,副本去重由下方分支负责。
        if (isEasFromRecentLogcat(DIR_PLAYPAUSE) || wasThisPhysicalPressHandledByLogcat(DIR_PLAYPAUSE)) {
            Log.d(TAG, "EAS ctrl dedup (pause): logcat 通道刚处理同按键,忽略防双切");
            return;
        }
        Log.i(TAG, "EAS ctrl: PAUSE");
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("pause");
    }
    @Override
    public void onNextCommand() {
        if (isEasFromSelfDispatch()) {
            Log.d(TAG, "EAS ctrl dedup (next): self-triggered by dispatchMediaKey, ignore to break loop");
            return;
        }
        // 物理方向盘键在途 + 系统注入直达控制器/目标自带接收器 → 让位,防连切2首
        if (easForwardShouldDefer(DIR_NEXT)) {
            Log.i(TAG, "EAS ctrl dedup (next): physical wheel key in flight, defer CarMedia (防连切2首)");
            return;
        }
        // 删除旧"让位系统通道"副本跳过块——物理键由 logcat 通道统一处理,
        // 副本去重由下方 isEasFromRecentLogcat/wasThisPhysicalPressHandledByLogcat 负责。
        if (isEasFromRecentLogcat(DIR_NEXT) || wasThisPhysicalPressHandledByLogcat(DIR_NEXT)) {
            Log.d(TAG, "EAS ctrl dedup (next): logcat 通道刚处理同按键,忽略防双切");
            return;
        }
        // Bug#53:未屏蔽三键时若当前控制器就是系统媒体按键会话(落雪等第三方音源),
        // 车机注入的 KEYCODE_MEDIA_NEXT 会直达该控制器切歌(绕过 CarMedia,现有去重拦不住),
        // MDC 转发这份是重复的,忽略交给系统注入单通道 -> 防连切2首。
        // 路由兜底窗口 500ms→2s(路由记录只在系统真注入媒体键时写入,
        // EAS 卡片按钮不注入 → 不会误吞),并修复 USB 虚拟包名匹配(isSystemMediaKeyRoutedToController 内)。
        if (!isKeyBlockedSetting() && isSystemMediaKeyRoutedToController(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)) {
            Log.i(TAG, "EAS ctrl dedup (next): system routed media key to controller, inject handles it (Bug#53)");
            return;
        }
        Log.i(TAG, "EAS ctrl: NEXT");
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("next");
        markMdcKeyHandle(DIR_NEXT);  // MDC 先到达时打 mark,让后到达的 logcat 通道去重(Bug#52)
    }
    @Override
    public void onPreviousCommand() {
        if (isEasFromSelfDispatch()) {
            Log.d(TAG, "EAS ctrl dedup (previous): self-triggered by dispatchMediaKey, ignore to break loop");
            return;
        }
        // 物理方向盘键在途 + 系统注入直达控制器/目标自带接收器 → 让位,防连切2首
        if (easForwardShouldDefer(DIR_PREV)) {
            Log.i(TAG, "EAS ctrl dedup (previous): physical wheel key in flight, defer CarMedia (防连切2首)");
            return;
        }
        // 删除旧"让位系统通道"副本跳过块,副本去重由下方分支负责。
        if (isEasFromRecentLogcat(DIR_PREV) || wasThisPhysicalPressHandledByLogcat(DIR_PREV)) {
            Log.d(TAG, "EAS ctrl dedup (previous): logcat 通道刚处理同按键,忽略防双切");
            return;
        }
        // Bug#53:未屏蔽三键时若当前控制器就是系统媒体按键会话(落雪等第三方音源),
        // 车机注入的 KEYCODE_MEDIA_PREVIOUS 会直达该控制器切歌,MDC 转发这份是重复的,忽略。
        if (!isKeyBlockedSetting() && isSystemMediaKeyRoutedToController(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            Log.i(TAG, "EAS ctrl dedup (previous): system routed media key to controller, inject handles it (Bug#53)");
            return;
        }
        Log.i(TAG, "EAS ctrl: PREVIOUS");
        // 统一走 dispatchMediaAction(),四级 fallback 全兼容
        dispatchMediaAction("prev");
        markMdcKeyHandle(DIR_PREV);  // MDC 先到达时打 mark,让后到达的 logcat 通道去重(Bug#52)
    }
    @Override
    public void onSeekCommand(long pos) {
        Log.i(TAG, "EAS ctrl: SEEK " + pos);
        // 统一走 seekTo(USB/酷我/标准源自动分流;酷狗/CarPlay 不支持时内部 toast)
        seekTo(pos);
    }
    @Override
    public void onStopCommand() {
        Log.i(TAG, "EAS ctrl: STOP");
        if (mediaController != null) {
            mediaController.getTransportControls().stop();
        }
    }
    @Override
    public void onRewindCommand() {
        Log.i(TAG, "EAS ctrl: REWIND");
        if (mediaController != null) {
            mediaController.getTransportControls().rewind();
        }
    }
    @Override
    public void onForwardCommand() {
        Log.i(TAG, "EAS ctrl: FAST_FORWARD");
        if (mediaController != null) {
            mediaController.getTransportControls().fastForward();
        }
    }
    // ================= 移植 MediaBridge：收藏命令（EAS 小卡片收藏按钮）=================
    @Override
    public void onCollectCommand(boolean like) {
        try {
            Log.i(TAG, "EAS ctrl: COLLECT like=" + like + " pkg=" + controllerPkg);
            // USB：直接走内置收藏（isCurrentFav 与目标不一致才翻转）
            if (usbRouteArmed && usbSourceController != null
                    && usbSourceController.getCurrentItem() != null) {
                if (usbSourceController.isCurrentFav() != like) {
                    usbSourceController.toggleFav(usbSourceController.getCurrentItem().path);
                }
                mCollected = like;
                MediaCenterBridge.get().setCollected(like);
                if (isDimPushEnabled()) {
                    pushMetadataToCard();
                }
            Log.d(TAG, "EAS ctrl dedup (previous): logcat 通道刚处理同按键,忽略防双击)");
                return;
            }
            // 在线音源：优先从当前控制器通知匹配"收藏/喜欢/红心" Action（各音乐 App 通知栏按钮）
            boolean handled = dispatchCollectToSource(like);
            if (!handled) {
                Log.w(TAG, "EAS collect: no collect action in notification for "
                        + controllerPkg + ", state toggled locally (card will follow)");
            }
            // 本地乐观反馈：立即重推，让卡片显示新收藏态（与音源同步结果由下次推送校正）
            mCollected = like;
            MediaCenterBridge.get().setCollected(like);
            if (isDimPushEnabled()) {
                pushMetadataToCard();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onCollectCommand error: " + t.getMessage());
        }
    }

 /** 该音源是否支持收藏按钮（决定小卡片是否显示收藏图标：isSupportCollect && UUID 非空）。 */
    private boolean isCollectableSource(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (PKG_BLUETOOTH.equals(pkg) || PKG_SELF.equals(pkg) || PKG_MEDIACENTER.equals(pkg)
                || PKG_TELECOM.equals(pkg) || PKG_MULTIMEDIA.equals(pkg)
                || UsbSourceController.SOURCE_PKG_USB.equals(pkg)) {
            return false;
        }
        // 其余均为在线音乐 App（QQ/网易云/酷我/酷狗/汽水/落雪等），支持收藏
        return true;
    }

 /** 从当前音源通知中匹配收藏类 Action 并发送（最通用通道：各音乐 App 通知栏的喜欢/收藏按钮）。 */
    private boolean dispatchCollectToSource(boolean like) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return false;
            }
            StatusBarNotification[] sbns = nm.getActiveNotifications();
            String pkg = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
            if (pkg == null || sbns == null) {
                return false;
            }
            for (StatusBarNotification sbn : sbns) {
                if (sbn == null || sbn.getPackageName() == null || !sbn.getPackageName().equals(pkg)) {
                    continue;
                }
                Notification n = sbn.getNotification();
                if (n == null || n.actions == null) {
                    continue;
                }
                for (Notification.Action a : n.actions) {
                    if (a == null || a.actionIntent == null) {
                        continue;
                    }
                    String label = (a.title != null) ? a.title.toString() : "";
                    String hay = label.toLowerCase(java.util.Locale.ROOT);
                    if (hay.contains("like") || hay.contains("collect") || hay.contains("favorite")
                            || hay.contains("favourite") || hay.contains("喜欢") || hay.contains("收藏")
                            || hay.contains("红心") || hay.contains("赞")) {
                        a.actionIntent.send();
                        Log.i(TAG, "EAS collect: sent notification action '" + label + "' to " + pkg);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatchCollectToSource error: " + t.getMessage());
        }
        return false;
    }

 /**
 * 构建完整歌词内容（LRC 文本，供 EAS setLyricContent）。
 * 优先原始 LRC（含时间戳，系统可解析）；无原始文本时用完整行列表拼接（仅文本行）。
 * 返回 null 表示无歌词（MediaCenterBridge 会推 " " 占位）。
 */
    private String buildFullLrcContent() {
        try {
            if (currentSourceType == SOURCE_TYPE_QQ && qqMusicFetcher != null) {
                String raw = qqMusicFetcher.getRawLrc();
                if (raw != null && !raw.isEmpty()) {
                    return raw;
                }
            } else if (currentSourceType == SOURCE_TYPE_NETEASE && cloudLyricFetcher != null) {
                String raw = cloudLyricFetcher.getRawLrc();
                if (raw != null && !raw.isEmpty()) {
                    return raw;
                }
            }
            java.util.List<String> lines = getFullLyricLines();
            if (lines != null && !lines.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String l : lines) {
                    sb.append(l).append('\n');
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "buildFullLrcContent error: " + t.getMessage());
        }
        return null;
    }
    // ================= P3: 方向盘按键监听 =================
    private KeyMonitor keyMonitor;
    private LogcatKeyMonitor logcatKeyMonitor;
 /** 方案H：CarPropertyManager 订阅 HW_KEY_INPUT，完整 DOWN/UP，长按可靠 */
    private CarPropertyKeyMonitor carPropertyKeyMonitor;
 /** 最高优先级 MEDIA_BUTTON 广播拦截器，防止酷狗(静态注册优先级=Integer.MAX_VALUE)抢方控 */
    private BroadcastReceiver mediaButtonBlockerReceiver;
    private boolean mediaButtonBlockerRegistered = false;
    private void initKeyMonitor() {
        try {
            keyMonitor = new KeyMonitor(this, new KeyMonitorCallback());
            // 默认键码:348=模式键/304=上一曲/305=下一曲/300=静音(真车实测旧码)
            keyMonitor.updateActions(loadKeyActions());
            // ★ 音量乱跳修复：不再调用 keyMonitor.register()（requestKeysInterception）。
            // 原 requestKeysInterception 独占拦截方向盘按键，会导致车机输入服务
            // ecarx.xsf.inputservice 的 injectInputEvent 被拦截后死循环，疯狂调
            // setStreamVolume 造成音量乱跳（暂停也跳，只能重启车机）。
            // 改为完全靠 LogcatKeyMonitor 监听所有方向盘按键（与 OK 键同通道）。
            // "屏蔽三键"效果仍由 setComponentEnabledSetting 禁用系统 MediaKeyReceiver 实现。
            // ★ v11:根据 key_blocked 持久化值恢复系统 MediaKeyReceiver 状态。
            // v9 的"无条件禁用 + blocked=false 时不恢复"导致进程重启后 UI 开关显示 OFF
            // 但实际系统 MediaKeyReceiver 已被禁用 → xsf.inputservice 的媒体键广播无人接收
            // → 蓝牙/酷狗等所有音源按键失效(真车日志 car_full2.log 18:09 确认)。
            // 改为始终按持久化值调用 setWheelKeysBlocked(),保证 UI 勾选状态与实际行为一致。
            boolean blocked = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("key_blocked", false);
            setWheelKeysBlocked(blocked);
            Log.i(TAG, "initKeyMonitor: restored key_blocked=" + blocked
                    + " (system MediaKeyReceiver " + (blocked ? "disabled" : "enabled") + ")");
            // ★ 方案H验证失败：HW_KEY_INPUT 是 InputHalService 内部属性，
            // PropertyHalService/CarPropertyService 白名单不包含它，第三方应用订阅被拒绝。
            // 改回日志通道（唯一可行方案）。
            startLogcatKeyMonitor();
            registerMediaButtonBlocker();
        } catch (Throwable t) {
            Log.e(TAG, "initKeyMonitor failed: " + t.getMessage());
        }
    }
    // ================= P3.1: MEDIA_BUTTON 广播拦截器 =================
 /**
 * 注册最高优先级 MEDIA_BUTTON 广播拦截器（新增）。
 *
 * 背景：车机输入服务 ecarx.xsf.inputservice 发送 ACTION_MEDIA_BUTTON 有序广播，
 * 酷狗车机版静态注册 MediaButtonIntentReceiver，优先级=Integer.MAX_VALUE(2147483647)，
 * 总是第一个收到广播并抢播（用户在听汽水，按方控切歌→酷狗响了）。
 *
 * 原理：CarMedia 动态注册同优先级(Integer.MAX_VALUE)的 Receiver。
 * Android 有序广播分发规则：同优先级下，动态注册的 Receiver 优先于静态注册的 Receiver。
 * 因此 CarMedia 先于酷狗收到广播，收到后调用 abortBroadcast() 截断，
 * 酷狗和系统 MediaKeyReceiver 均收不到，彻底杜绝抢方控。
 *
 * 截断后切歌由 CarMedia logcat 通道统一处理（LogcatKeyMonitor→KeyMonitor→onKeyAction），
 * 不再存在"CarMedia+系统通道双切"问题，因此 onKeyAction 中"未屏蔽三键时跳过 prev/next"
 * 的逻辑已同步移除（见 KeyMonitorCallback.onKeyAction）。
 */
    private void registerMediaButtonBlocker() {
        try {
            if (mediaButtonBlockerRegistered) {
                Log.i(TAG, "mediaButtonBlocker already registered, skip");
                return;
            // v9 的"无条件禁用 + blocked=false 时不恢复"导致进程重启后 UI 开关显示 OFF
            }
            mediaButtonBlockerReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                        return;
                    }
                    android.view.KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    int code = (ke != null) ? ke.getKeyCode() : -1;
                    int action = (ke != null) ? ke.getAction() : -1;
                    // 诊断：检测广播是否为有序广播（abortBroadcast 仅对有序广播有效）
                    boolean ordered = isOrderedBroadcast();
                    int flags = intent.getFlags();
                    Log.i(TAG, "MEDIA_BUTTON received: keyCode=" + code + " action=" + action
                            + " ordered=" + ordered + " flags=0x" + Integer.toHexString(flags)
                            + " controller=" + controllerPkg);
                    if (!ordered) {
                        Log.w(TAG, "!!! MEDIA_BUTTON is NOT an ordered broadcast — abortBroadcast() will NOT work, all receivers (incl. kugou) will receive it");
                    }
                    // 选中"通用媒体中心"(ecarx.xsf.mediacenter)时不拦截——
                    // 它负责把方控广播转发给手机互联(CarPlay/HiCar/CarLife)，拦截会导致手机互联方控失效。
                    // 选中其他音源(汽水/酷狗/QQ/蓝牙等)时拦截，防止酷狗等用最高优先级静态接收器抢方控。
                    boolean isMediaCenterSelected = "ecarx.xsf.mediacenter".equals(controllerPkg);
                    if (isMediaCenterSelected) {
                        Log.i(TAG, "MEDIA_BUTTON broadcast passthrough: keyCode=" + code + " action=" + action
                                + " (通用媒体中心选中,不拦截,转发给手机互联)");
                        // 不调用 abortBroadcast()，让广播继续传递给 XSFMediaCenter
                    } else {
                        Log.i(TAG, "MEDIA_BUTTON broadcast intercepted: keyCode=" + code + " action=" + action
                                + " (blocked from kugou/system MediaKeyReceiver, controller=" + controllerPkg + ")");
                        // 方案B :屏蔽三键开启时,第三方 Receiver 已被批量禁用,
                        // CarMedia 是唯一收到广播的,必须主动转发给当前控制器(否则按键没人处理)。
                        // 只处理 ACTION_DOWN,避免 down+up 双触发切两首。
                        if (isWheelKeysBlocked() && action == KeyEvent.ACTION_DOWN) {
                            // MEDIA_BUTTON 广播转发与 logcat 通道(handleBlockedThreeKeyDown)
                            // 是同一物理按键的两条通道。先到的处理并打 mark,后到的被去重,
                            // 否则屏蔽三键时按一次方向盘切歌 = 广播转发 + logcat 各切一次 → USB 连切2首。
                            // 本通道是副本通道,改用 shouldCopyChannelAbsorb——logcat
                            // 真实按键 2s 内已处理(覆盖负载高时广播晚到 >500ms 的"偶尔连切2首")或
                            // 其他副本 500ms 内已处理 → 吸收;MEDIA_BUTTON 广播只来自方向盘/硬按键
                            // 注入,不是 EAS 卡片按钮,长窗口不会误吞卡片。
                            int dir = dirOfKeyCode(code);
                            if (shouldCopyChannelAbsorb(dir)) {
                                Log.d(TAG, "MEDIA_BUTTON proxy dedup: 另一通道刚处理同按键,跳过转发");
                            } else {
                                switch (code) {
                                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                                        Log.i(TAG, "MEDIA_BUTTON proxy: dispatch NEXT to controller=" + controllerPkg);
                                        markLogcatKeyHandle(dir, SRC_MEDIASESSION);
                                        physicalSend("next");
                                        break;
                                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                        Log.i(TAG, "MEDIA_BUTTON proxy: dispatch PREVIOUS to controller=" + controllerPkg);
                                        markLogcatKeyHandle(dir, SRC_MEDIASESSION);
                                        physicalSend("prev");
                                        break;
                                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                        Log.i(TAG, "MEDIA_BUTTON proxy: dispatch PLAY_PAUSE to controller=" + controllerPkg);
                                        markLogcatKeyHandle(dir, SRC_MEDIASESSION);
                                        physicalSend("playpause");
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                        // 截断有序广播，酷狗/系统 MediaKeyReceiver 收不到
                        // 真车日志证实 MEDIA_BUTTON 广播 ordered=false(非有序)——
                        // abortBroadcast() 必然抛 RuntimeException 且 Android 框架打印 12 行堆栈
                        // (BroadcastReceiver trying to return result during a non-ordered broadcast),
                        // 每次切歌刷屏 → logcat 洪水 → 按键行解析延迟 → 连切2首根因之一。
                        // ordered=false 时 abort 本来就无效,直接跳过;只有 ordered 才调用。
                        if (ordered) {
                        Log.w(TAG, "!!! MEDIA_BUTTON is NOT an ordered broadcast — abortBroadcast() will NOT work, all receivers (incl. kugou) will receive it");
                        // 不调用 abortBroadcast()，让广播继续传递给 XSFMediaCenter
                            try {
                                abortBroadcast();
                            } catch (Throwable t) {
                                // 理论上有序广播 abort 不抛异常,防御性保留
                                Log.w(TAG, "abortBroadcast failed: " + t.getMessage());
                            }
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_BUTTON);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            filter.setPriority(Integer.MAX_VALUE); // 最高优先级，压过酷狗静态注册
            registerReceiver(mediaButtonBlockerReceiver, filter);
            mediaButtonBlockerRegistered = true;
            Log.i(TAG, "mediaButtonBlocker registered with priority=Integer.MAX_VALUE");
        } catch (Throwable t) {
            Log.e(TAG, "registerMediaButtonBlocker failed: " + t.getMessage());
        }
    }
    private void unregisterMediaButtonBlocker() {
        try {
            if (mediaButtonBlockerRegistered && mediaButtonBlockerReceiver != null) {
                unregisterReceiver(mediaButtonBlockerReceiver);
                mediaButtonBlockerRegistered = false;
                mediaButtonBlockerReceiver = null;
                Log.i(TAG, "mediaButtonBlocker unregistered");
            }
        } catch (Throwable t) {
            Log.w(TAG, "unregisterMediaButtonBlocker failed: " + t.getMessage());
        }
    }
    // ================= 按键录制 =================
    private Map<String, String> loadKeyActions() {
        // 默认映射仅首次注入并持久化,之后一律以 prefs 为准(用户删除后不再重生)
        String json = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                .getString("key_actions", null);
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) {
            // ★ 恢复纯日志通道默认映射：所有方向盘键走 InputManager/CAR.INPUT 日志键码，
            // 由 LogcatKeyMonitor 监听（InputManager tag + CAR.INPUT tag），命中后注入 KeyMonitor。
            // 长按依赖日志 release 事件（InputManager release / CAR.INPUT action=0）。
            map.put(KeyMonitor.KEY_MODE + ":1", "openapp:" + getPackageName());  // 模式键(348) 单击 -> 打开主界面
            map.put(KeyMonitor.KEY_MODE + ":2", KeyMonitor.ACTION_SOURCE);     // 模式键(348) 双击 -> 打开播放源
            map.put("305:1", KeyMonitor.ACTION_NEXT);                            // 下一曲(305)
            map.put("304:1", KeyMonitor.ACTION_PREV);                            // 上一曲(304)
            map.put(KeyMonitor.KEY_MUTE + ":1", KeyMonitor.ACTION_PLAYPAUSE);   // 静音(300) -> 播放/暂停
            saveKeyActions(map);
            return map;
        }
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                String action = obj.optString(k, "");
                // key 格式 "code:tap",如 "200110:1"
                if (k != null && k.indexOf(':') > 0 && !action.isEmpty()) {
                    // 旧版 OK 键映射迁移:45(KEYCODE_DPAD_CENTER 旧误用) → 306(实际 logcat 注入码)
                    if (k.startsWith("45:")) {
                        k = "306:" + k.substring(3);
                    }
                    // ★ AccessibilityService 方案迁移：旧广播通道码 → InputManager 键码
                    // 87(MEDIA_BUTTON 下一曲) → 305(AccessibilityService 下一曲)
                    if (k.startsWith("87:")) {
                        k = "305:" + k.substring(3);
                        Log.i(TAG, "key action migration: 87 -> 305 (broadcast MEDIA_BUTTON next -> A11y)");
                    }
                    // 88(MEDIA_BUTTON 上一曲) → 304(AccessibilityService 上一曲)
                    if (k.startsWith("88:")) {
                        k = "304:" + k.substring(3);
                        Log.i(TAG, "key action migration: 88 -> 304 (broadcast MEDIA_BUTTON prev -> A11y)");
                    }
                    // 89(MEDIA_BUTTON 上一曲长按) → 304(AccessibilityService 上一曲，长按由600ms计时器判定)
                    if (k.startsWith("89:")) {
                        k = "304:" + k.substring(3);
                        Log.i(TAG, "key action migration: 89 -> 304 (broadcast prev long -> A11y)");
                    }
                    // 90(MEDIA_BUTTON 下一曲长按) → 305(AccessibilityService 下一曲)
                    if (k.startsWith("90:")) {
                        k = "305:" + k.substring(3);
                        Log.i(TAG, "key action migration: 90 -> 305 (broadcast next long -> A11y)");
                    }
                    // 200164(ECARX 语义广播 静音) → 300(AccessibilityService 静音)
                    if (k.startsWith("200164:")) {
                        k = "300:" + k.substring(7);
                        Log.i(TAG, "key action migration: 200164 -> 300 (broadcast ECARX mute -> A11y)");
                    }
                    // 迁移：旧 mode 动作 -> openapp:<自身包名>（打开主界面统一走 openapp 通道）
                    if ("mode".equals(action)) {
                        action = "openapp:" + getPackageName();
                        Log.i(TAG, "key action migration: mode -> openapp (self) for key=" + k);
                    }
                    map.put(k, action);
                }
            }
            // 五位码默认 → 日志通道码迁移（修复，缤越E02适配）：
            // 真车实测缤越方向盘发的是 348/304/305/300（logcat通道），不发ECARX语义广播。
            // 若 prefs 里存的还是 ~08-19 期间的五位码默认（且无日志通道码条目），
            // 自动替换为日志通道码，避免升级后五位码残留导致方向盘不生效。
            // ★ 修复原条件互斥bug：原条件 !containsKey("200164:1") && containsKey("200164:1") 永假。
            // ★ 修复迁移目标：原 200088→88/200087→87 是MEDIA_BUTTON码，缤越E02不发该广播，
            // 直接转 304/305（日志通道码），避免二次迁移。
            if (map.isEmpty()
                    || (map.size() <= 5
                    && !map.containsKey("348:1") && !map.containsKey("304:1") && !map.containsKey("305:1") && !map.containsKey("300:1")
                    && (map.containsKey("200110:1") || map.containsKey("200088:1") || map.containsKey("200087:1") || map.containsKey("200164:1")))) {
                boolean replaced = false;
                if (map.containsKey("200110:1")) { map.put("348:1", map.remove("200110:1")); replaced = true; }
                if (map.containsKey("200110:2")) { map.put("348:2", map.remove("200110:2")); replaced = true; }
                if (map.containsKey("200088:1")) { map.put("304:1", map.remove("200088:1")); replaced = true; }
                if (map.containsKey("200087:1")) { map.put("305:1", map.remove("200087:1")); replaced = true; }
                if (map.containsKey("200164:1")) { map.put("300:1", map.remove("200164:1")); replaced = true; }
                if (replaced) {
                    Log.i(TAG, "key actions: 五位码默认迁移为日志通道码(348/304/305/300), 缤越E02适配");
                }
            }
            // 迁移后写回(去掉 45 残留,统一为 306)
            saveKeyActions(map);
        } catch (Throwable ignored) {
        }
        // 默认映射仅在 json==null 首次注入(见上方),用户删除后不再重生。
        // 此处不做兜底补回,否则 prev/next/mute/mode 等默认映射会被强制恢复,导致 UI 上"删不掉"。
        return map;
    }
    private void saveKeyActions(Map<String, String> map) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .edit().putString("key_actions", obj.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
    public Map<String, String> getKeyActions() {
        return loadKeyActions();
    }
 /** 录制完成:存 "code:tap" -> action(tap: 1=单击 2=双击) */
    public void setKeyAction(int keyCode, int tap, String action) {
        if (keyCode <= 0 || action == null || action.isEmpty()) {
            return;
        }
        String key = keyCode + ":" + tap;
        Map<String, String> map = loadKeyActions();
        map.put(key, action);
        saveKeyActions(map);
        if (keyMonitor != null) {
            keyMonitor.updateActions(map);
        }
    }
 /** 删除 "code:tap" 条目(默认映射也可删除,删除后持久化不再重生) */
    public void removeKeyAction(int keyCode, int tap) {
        String key = keyCode + ":" + tap;
        Map<String, String> map = loadKeyActions();
        map.remove(key);
        saveKeyActions(map);
        if (keyMonitor != null) {
            keyMonitor.updateActions(map);
        }
    }
 /** 启动按键录制模式(注入 KeyMonitor recorder)。 */
    public void startKeyRecording(KeyMonitor.KeyRecorder recorder) {
        if (keyMonitor != null) {
            keyMonitor.startRecording(recorder);
        }
    }
    public void stopKeyRecording() {
        if (keyMonitor != null) {
            keyMonitor.stopRecording();
        }
    }
    // ================= 自定义打开 App（新增） =================
 /** App 信息数据类（供 UI App 选择器使用） */
    public static class AppInfo {
        public final String packageName;
        public final String appName;
        public final String iconUri; // content:// URI（走 ArtworkContentProvider），可能为 null
        public AppInfo(String pkg, String name, String icon) {
            this.packageName = pkg;
            this.appName = name;
            this.iconUri = icon;
        }
    }
 /**
 * 枚举所有有主界面（ACTION_MAIN + CATEGORY_LAUNCHER）的 App，含系统 App 和自身。
 * 用于 UI 按键映射的"打开App"选择器。列表开头额外插入两个虚拟项：
 * "回到桌面"（__home__）和"CarMedia 主界面"（自身包名，若未在 LAUNCHER 列表中）。
 * @return 按应用名排序的 AppInfo 列表（虚拟项固定在最前）
 */
    public java.util.List<AppInfo> getLaunchableApps() {
        java.util.List<AppInfo> result = new java.util.ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> resolves = pm.queryIntentActivities(mainIntent, 0);
            java.util.Set<String> seenPkgs = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : resolves) {
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.isEmpty()) continue;
                // 同包名多 Activity 去重（不排除自身，用户可映射按键回到 CarMedia）
                if (!seenPkgs.add(pkg)) continue;
                String name = ri.loadLabel(pm).toString();
                if (name == null || name.isEmpty()) name = pkg;
                // 系统媒体中心原始名称不直观，统一显示为"通用媒体中心"（与 MainActivity.getAppDisplayName 对齐）
                if ("ecarx.xsf.mediacenter".equals(pkg)) {
                    name = "通用媒体中心";
                }
                // 直接从 ResolveInfo 拿图标，转 base64 data URI（WebView file:// 页面加载 content:// 会被拦截）
                String icon = AppIconResolver.drawableToDataUri(ri.loadIcon(pm));
                result.add(new AppInfo(pkg, name, icon));
            }
            java.util.Collections.sort(result, new java.util.Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    return a.appName.compareToIgnoreCase(b.appName);
                }
            });
            // 虚拟项固定在列表最前：回到桌面（__home__ 触发 HOME Intent，无真实图标）
            result.add(0, new AppInfo("__home__", "回到桌面", null));
            // 确保自身 App 在列表中（部分车机 CarMedia 的 LAUNCHER Activity 可能被 queryIntentActivities 过滤）
            boolean hasSelf = false;
            for (AppInfo ai : result) {
                if (getPackageName().equals(ai.packageName)) { hasSelf = true; break; }
            }
            if (!hasSelf) {
                String selfIcon = AppIconResolver.drawableToDataUri(getPackageManager().getApplicationIcon(getPackageName()));
            }
            Log.i(TAG, "getLaunchableApps: found " + result.size() + " launchable apps (incl. home + self)");
        } catch (Throwable t) {
            Log.e(TAG, "getLaunchableApps failed: " + t.getMessage());
        }
        return result;
    }
 /**
 * 按包名打开指定 App（新增）。
 * 包名无效或无启动 Intent 时静默失败（日志警告），不崩溃。
 * @param pkg 目标 App 包名
 */
    public void launchAppByPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "launchAppByPackage: empty package name");
            return;
        }
        try {
            // 虚拟项：回到桌面（发送 HOME Intent，不依赖具体 Launcher 包名）
            if ("__home__".equals(pkg)) {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(homeIntent);
                Log.i(TAG, "launchAppByPackage: returned to home (HOME intent)");
                return;
            }
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                Log.w(TAG, "launchAppByPackage: no launch intent for " + pkg);
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            Log.i(TAG, "launchAppByPackage: launched " + pkg);
        } catch (Throwable t) {
            Log.e(TAG, "launchAppByPackage failed: " + pkg + " - " + t.getMessage());
        }
    }
 /**
 * 启动 CarPropertyManager 方向盘按键监听（方案H）。
 * 订阅 VehicleProperty.HW_KEY_INPUT (0x287000fb)，直接从 Vehicle HAL 接收
 * 完整 DOWN/UP 事件，长按可靠，不依赖 logcat/XSFInputService，不会音量乱跳。
 */
    private void startCarPropertyKeyMonitor() {
        try {
            if (carPropertyKeyMonitor != null) {
                return;
            }
            carPropertyKeyMonitor = new CarPropertyKeyMonitor(this,
                new CarPropertyKeyMonitor.Listener() {
                    @Override
                    public void onKeyDown(int keyCode) {
                        if (keyMonitor != null) {
                            keyMonitor.handleExternalKeyDown(keyCode);
                        }
                    }
                    @Override
                    public void onKeyUp(int keyCode) {
                        if (keyMonitor != null) {
                            keyMonitor.handleExternalKeyUp(keyCode);
                        }
                    }
                },
                new CarPropertyKeyMonitor.StatusListener() {
                    @Override
                    public void onConnected() {
                        Log.i(TAG, "CarPropertyKeyMonitor connected, HW_KEY_INPUT active (long-press reliable)");
                    }
                    @Override
                    public void onFallbackToLogcat(String reason) {
                        Log.w(TAG, "CarPropertyKeyMonitor fallback to logcat: " + reason);
                        // CarProperty 方案不可用，自动降级到日志通道
                        startLogcatKeyMonitor();
                    }
                });
            carPropertyKeyMonitor.start();
        } catch (Throwable t) {
            Log.w(TAG, "startCarPropertyKeyMonitor error: " + t.getMessage() + ", fallback to logcat");
            startLogcatKeyMonitor();
        }
    }
    private void stopCarPropertyKeyMonitor() {
        if (carPropertyKeyMonitor != null) {
            try {
                carPropertyKeyMonitor.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stopCarPropertyKeyMonitor error: " + t.getMessage());
            }
            carPropertyKeyMonitor = null;
        }
    }
 /**
 * 启动全方向盘按键 logcat 监听（音量乱跳修复）。
 * 监听 InputManager tag（方向盘键：IMS reportKeyToAdaptApi : <code> press）
 * 和 CAR.INPUT tag（OK 键：shouldCallback: code=<code> action=1），
 * 命中后以外部按键方式注入 KeyMonitor（复用单击/双击映射与录制）。
 * 完全替代原 requestKeysInterception 通道，从根上切断输入服务死循环。
 */
    private void startLogcatKeyMonitor() {
        try {
            if (logcatKeyMonitor != null) {
                return;
            }
            logcatKeyMonitor = new LogcatKeyMonitor(new LogcatKeyMonitor.Listener() {
                @Override
                public void onKeyDown(int keyCode, long physTimeMs) {
                    // 方向盘三键 keyDown 即"物理按压已发生"信号,必须在映射判定
                    // 之前记录——即使 KeyMonitor 无映射(用户删了 304/305)或 CarMedia 让位,
                    // MDC 也能据此识别"这是方向盘按压副本"而随让位,防控制器接收器+MDC 双发。
                    // 以物理按压时刻(InputManager 日志时间戳)为去重基准,不受 logcat
                    // 解析延迟影响(真车实测延迟可达 1245ms,解析时刻基准窗口失效 → 连切2首)。
                    int downDir = dirOfKeyCode(keyCode);
                    if (downDir >= 0) {
                        markLogcatKeyDownSeen(downDir, physTimeMs);
                    }
                    // ★ 录制模式优先——录制中屏蔽三键直通让路,所有键(含 304/305)
                    // 必须进 KeyMonitor 供 recorder 上报。否则屏蔽三键开启时按 304/305 被
                    // 直通分支拦截(handleBlockedThreeKeyDown + return),KeyMonitor 收不到,
                    // 录制没反应(真车实测)。
                    if (keyMonitor != null && keyMonitor.isRecording()) {
                        keyMonitor.handleExternalKeyDown(keyCode);
                        return;
                    }
                    // 媒体键(下一曲/上一曲/播放暂停)已由 MediaSession 通道归一化喂
                    // KeyMonitor(见 onMediaButtonEvent,支持双击/长按/自定义映射)。logcat 通道不再
                    // 重复喂同一物理按键,否则 KeyMonitor 会收到同一物理键的两次 keyDown(标准码归一化
                    // + 私有码),双击判定误判/双执行。
                    // mediaButtonViaSession=false(MediaSession 未激活/创建失败)时回退,logcat 照常兜底。
                    // 录制模式已在上方优先放行;此跳过同时覆盖屏蔽三键分支(屏蔽期间媒体键仍由 MediaSession
                    // 通道→KeyMonitor 接管,与"CarMedia 完全接管三键"语义一致)。
                    // ★ 修复静音键300失效:此处必须用 isSystemMediaKeyCode(只跳标准媒体键
                    // 85-91,由 MediaSession 通道喂 KeyMonitor)而非 dirOfKeyCode>=0。dirOfKeyCode 把
                    // CAR.INPUT 私有码 300/304/305 也判为 media 方向,而私有码系统不路由到
                    // MediaSession.Callback → logcat 跳过 + MediaSession 收不到 = 静音键300 playpause 彻底丢失。
                    if (mediaButtonViaSession && isSystemMediaKeyCode(keyCode)) {
                        Log.i(TAG, "logcat keyDown " + keyCode + " handled by MediaSession->KeyMonitor, skip to avoid double-feed");
                        return;
                    }
                    // ★ 屏蔽三键开启时,三键(304/305/300 及其标准媒体键码)由
                    // CarMedia 直接从 logcat 通道接管,绕过按键映射表。
                    // 原因:屏蔽三键时系统 MediaKeyReceiver 被禁用,MDC/EAS 转发链路断开;
                    // 若用户删除了 304/305 映射,KeyMonitor 无动作,方控彻底失效(真车实测:
                    // 屏蔽三键后酷狗不能方控,再关开关也无效,重启酷狗才恢复)。
                    // logcat 通道(InputManager 日志)不受系统媒体键路由影响,始终能收到三键。
                    // ★ 排除 300(静音键)——用户要求屏蔽三键不接管静音键,
                    // 300 保持"该干啥干啥"(系统原生静音/映射表行为),不再附加播放暂停。
                    if (isWheelKeysBlocked() && isThreeKeyCode(keyCode)) {
                        if (keyCode == KeyMonitor.KEY_MUTE) {
                            // 静音键不直通:落回 KeyMonitor 映射表(已删除映射则无动作,
                            // 仅系统原生静音生效)");不 return,继续走下方 handleExternalKeyDown。
                            Log.i(TAG, "blocked-3keys: mute key 300 excluded from takeover (system mute only)");
                        } else {
                            handleBlockedThreeKeyDown(keyCode);
                            return; // 不进入 KeyMonitor,避免与保留映射双触发
                        }
                    }
                    // 所有方向盘键（含 OK 键 306）经 logcat 通道进入 KeyMonitor 的单击/双击/长按判定
                    if (keyMonitor != null) {
                        keyMonitor.handleExternalKeyDown(keyCode);
                    }
                }
                @Override
                public void onKeyUp(int keyCode) {
                    // 媒体键 keyUp 由 MediaSession 通道喂 KeyMonitor(与 onKeyDown 对称),
                    // logcat 通道跳过,避免同一物理键的 keyUp 被 KeyMonitor 重复处理。
                    // ★ 与 onKeyDown 同步:改用 isSystemMediaKeyCode,只跳标准媒体键85-91,
                    // 放行私有码300/304/305的 keyUp(否则静音键300长按/单击判定缺 keyUp)。
                    if (mediaButtonViaSession && isSystemMediaKeyCode(keyCode)) {
                        return;
                    }
                    // 排除 300(静音键),其 keyUp 正常上报给 KeyMonitor
                    // (供单击/双击/长按判定)");304/305 等仍在屏蔽期间直接忽略。
                    if (isWheelKeysBlocked() && isThreeKeyCode(keyCode) && keyCode != KeyMonitor.KEY_MUTE) {
                        return; // 屏蔽期间按键抬起无需处理(动作已在按下时执行)
                    }
                    // 按键抬起：取消长按计时器、执行延迟单击（新增长按检测）
                    if (keyMonitor != null) {
                        keyMonitor.handleExternalKeyUp(keyCode);
                    }
                }
                @Override
                public void onMediaButtonRouted(int keyCode, String pkg) {
                    // Bug#53:系统把媒体键实际路由到 pkg(系统注入 A 直达的直接证据)。
                    // 记录供 MDC 转发判断"系统注入是否已直达控制器";只有当路由到当前控制器时
                    // 才打 logcat mark,让随后到达的 MDC 转发被 isEasFromRecentLogcat() 去重。
                    // 路由到别的 app(非当前控制器)不打 mark——避免误去重导致不切歌。
                    lastMediaSessionRouteKeyCode = keyCode;
                    lastMediaSessionRoutePkg = pkg;
                    lastMediaSessionRouteAt = System.currentTimeMillis();
                    if (pkg != null && pkg.equals(controllerPkg)) {
                        // 按方向打 mark(路由记录供 MDC 的 isEasFromRecentLogcat 短窗口吸收,
                        // 以及 isSystemMediaKeyRoutedToController 长窗口兜底)。
                        markLogcatKeyHandle(dirOfKeyCode(keyCode), SRC_MEDIASESSION);
                        Log.i(TAG, "Bug#53: system routed media key " + keyCode
                                + " to controller " + pkg + ", MDC forward will be deduped");
                    }
                    // 手机互联场景增强:系统把媒体键路由到 mediacenter,且当前无真实控制器
                    // (mediaController 为 null 或是被阻塞的 XCMedia2) → 自动选中 mediacenter 作为代理控制器。
                    // 这是手机互联场景的强信号(CarPlay/HiCar/CarLife 等),比被动检测更及时。
                    // USB 音源激活时不自动选 mediacenter 代理——防止手机互联
                    // 媒体键路由事件把 USB 播放切到投影代理。
                    if (!usbRouteArmed && "ecarx.xsf.mediacenter".equals(pkg)
                            && (mediaController == null || isControlBlocked(mediaController.getPackageName()))) {
                        MediaController mcProxy = findActiveMediacenterController();
                        if (mcProxy != null) {
                            Log.i(TAG, "onMediaButtonRouted: auto-select mediacenter as proxy controller (phone projection scenario)");
                            selectController(mcProxy);
                        }
                    }
                }
            });
            logcatKeyMonitor.start();
        } catch (Throwable t) {
            Log.w(TAG, "startLogcatKeyMonitor error: " + t.getMessage());
        }
    }
 /** 是否为"三键"(上一曲/下一曲/播放暂停)物理键码:真车 CAR.INPUT 码 304/305/300 + 标准媒体键码。 */
    private boolean isThreeKeyCode(int keyCode) {
        return keyCode == KeyMonitor.KEY_PREV       // 304 上一曲
                || keyCode == KeyMonitor.KEY_NEXT   // 305 下一曲
                || keyCode == KeyMonitor.KEY_MUTE   // 300(默认映射为播放/暂停)
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS    // 88
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT        // 87
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE  // 85
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY        // 126
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;      // 127
    }
 /** 屏蔽三键时,logcat 通道直接接管三键(按下即动作,不经 KeyMonitor 映射表)。
 * 与 KeyMonitorCallback 的 prev/next/playpause 分支走同一条 sendXxx → dispatchMediaAction
 * 链路(酷狗 startService 等特殊处理在 handleKnownPlayerSpecial 中),保证屏蔽期间方控有效。 */
    private void handleBlockedThreeKeyDown(int keyCode) {
        // logcat 通道与 MediaSession.Callback/MEDIA_BUTTON 广播转发是同一物理按键的
        // 不同通道。若系统媒体键通道(mediaButtonBlocker proxy / onMediaButtonEvent)先处理并打了 mark,
        // 这里必须忽略,否则按一次切歌 = 双通道各切一次 → USB 连切2首。
        // 本通道是 logcat 真实按键通道,保持 500ms 短窗口(长窗口会吞用户快速连按两次)。
        // 屏蔽三键下 MDC/广播副本(豁免接收器)仍可能先执行,logcat 解析延迟 >500ms 时
        // 短窗口失效 → 同样连切2首;+wasCopyHandledNearThisPhysicalPress(物理按压时刻基准)兜底。
        int dir = dirOfKeyCode(keyCode);
        // 只保留物理按压时刻窗口——仅吸收"本次按压的副本"(MDC/广播副本先执行)。
        // 去掉 500ms 短窗口(isMdcHandledRecently/isEasFromRecentLogcat):短窗口会把
        // 用户真实快速连按(间隔<500ms)的第二次按压误吸收(双击映射被吞)。
        if (wasCopyHandledNearThisPhysicalPress(dir)) {
            Log.d(TAG, "blocked-3keys dedup: 另一通道刚处理同按键,logcat 通道忽略 keyCode=" + keyCode);
            return;
        }
        switch (keyCode) {
            case KeyMonitor.KEY_PREV:
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                Log.i(TAG, "blocked-3keys: direct PREV via logcat (keyCode=" + keyCode + ", controller=" + controllerPkg + ")");
                markLogcatKeyHandle(dir, SRC_LOGCAT);
                physicalSend("prev");
                break;
            case KeyMonitor.KEY_NEXT:
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                Log.i(TAG, "blocked-3keys: direct NEXT via logcat (keyCode=" + keyCode + ", controller=" + controllerPkg + ")");
                markLogcatKeyHandle(dir, SRC_LOGCAT);
                physicalSend("next");
                break;
            case KeyMonitor.KEY_MUTE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                Log.i(TAG, "blocked-3keys: direct PLAYPAUSE via logcat (keyCode=" + keyCode + ", controller=" + controllerPkg + ")");
                markLogcatKeyHandle(dir, SRC_LOGCAT);
                physicalSend("playpause");
                break;
            default:
                break;
        }
    }
    private void stopLogcatKeyMonitor() {
        if (logcatKeyMonitor != null) {
            try {
                logcatKeyMonitor.stop();
            } catch (Throwable t) {
                Log.w(TAG, "stopLogcatKeyMonitor error: " + t.getMessage());
            }
            logcatKeyMonitor = null;
        }
    }
    public boolean isWheelKeysBlocked() {
        // 按键监听完全靠 LogcatKeyMonitor（纯日志通道），直接返回 key_blocked 偏好设置。
        return isKeyBlockedSetting();
    }
 /**
 * 通用"系统注入是否会命中当前控制器"判定(非音源白名单)。
 * 真车日志确认:方向盘 305 一次按压,系统 KeyEventDispatcherUtil→ecarx.xsf.inputservice
 * 会把注入的媒体键(87/88)路由给"当前真实在播的媒体应用"(AudioTrack started +
 * USAGE_MEDIA,即 getRealPlayingMusicPkg)。因此:
 * - 真实在播的应用 == 当前控制器 → 系统注入那刀会切到控制器 → CarMedia 必须让位防双切;
 * - 真实在播的是别的应用 / 控制器未在播 → 系统注入切到别处/切空 → CarMedia 接管兜底。
 * 任何音源(酷狗/酷我/汽水/QQ 等)只要满足"系统注入会命中控制器",统一让位,兼容通用;
 * 不列举具体包名。系统注入被 CarMedia 自身 MediaSession 截走时,由既有
 * mediaButtonViaSession 通道处理(mediaButtonViaSession 判据在上方 PREV/NEXT 分支)。
 */
    private boolean systemMediaKeyHitsController() {
        try {
            if (isWheelKeysBlocked()) return false; // 屏蔽三键:系统注入已禁用,CarMedia 接管
            if (controllerPkg == null) return false;
            String realPkg = getRealPlayingMusicPkg();
            if (realPkg == null) return false;
            // 让位给系统注入的前提是"系统注入能真正切到该 App"——即它自带**可用**
            // 的 MEDIA_BUTTON 接收器。车机版酷狗(kugou.auto)接收器被"始终禁用"、只靠 CarMedia
            // startService 直连切歌:系统注入到它接收器无效,若 CarMedia 让位 → 没人切 = 没反应。
            if (!hasOwnMediaButtonReceiver(controllerPkg)) return false;
            return controllerPkg.equals(realPkg);
        } catch (Throwable t) {
            Log.w(TAG, "systemMediaKeyHitsController error: " + t.getMessage());
            return false;
        }
    }
 /** 读取"屏蔽三键"设置意图(pref key_blocked),供 UI 勾选状态显示。 */
    public boolean isKeyBlockedSetting() {
        try {
            return getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("key_blocked", false);
        } catch (Throwable t) {
            return false;
        }
    }
 /** 该包的 MEDIA_BUTTON 接收器是否必须"始终禁用"、由 CarMedia 自己发送命令切歌。
 * 车机版酷狗(com.kugou.android.auto)——走 startService 直连,接收器防抢播。
 * (注:网易云 IOT 接收器 inert 的问题改由通用兜底解决,不在此白名单——见 scheduleReceiverCutFallback。) */
    private boolean isReceiverAlwaysDisabled(String pkg) {
        return "com.kugou.android.auto".equals(pkg);
    }
 /**
 * 判断 keyCode 是否为标准 Android KeyEvent 媒体键码(会被系统媒体键路由到 MediaSession.Callback)。
 * CAR.INPUT 层私有键码(300/304/305/348/306/301/302 等)不进 Android input 系统,
 * 系统永远不会把它们路由到 MediaSession.Callback,因此 logcat 通道不得因
 * mediaButtonViaSession=true 而跳过这些键的动作(否则静音键 300 的 playpause 彻底丢失)。
 */
    private boolean isSystemMediaKeyCode(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE   // 85
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP      // 86
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT      // 87
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS  // 88
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_REWIND    // 89
                || keyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD // 90
                || keyCode == android.view.KeyEvent.KEYCODE_MUTE;            // 91
    }
 /**
 * 把系统注入的标准媒体键码归一化到方向盘私有码,供 KeyMonitor 统一判定/查映射。
 * 87(MEDIA_NEXT)->305(KEY_NEXT 下一曲) / 88(MEDIA_PREVIOUS)->304(KEY_PREV 上一曲)
 * / 85·126·127(PLAY_PAUSE·PLAY·PAUSE)->300(KEY_MUTE,默认映射 playpause)
 * / 89(MEDIA_REWIND 上一曲长按/快退)->304 / 90(MEDIA_FAST_FORWARD 下一曲长按/快进)->305。
 * 复用现有 304/305/300 映射与单击/双击/长按配置,无需重配 UI。返回 -1 表示非可归一化媒体键。
 */
    private int normalizeMediaKeyCode(int code) {
        switch (code) {
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
            case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return KeyMonitor.KEY_NEXT;      // 87 / 90 -> 305
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                return KeyMonitor.KEY_PREV;      // 88 / 89 -> 304
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                return KeyMonitor.KEY_MUTE;      // 85/126/127 -> 300
            default:
                return -1;
        }
    }
 /** 清空某方向的 logcat mark(仅 logcat 执行来源标记),用于 MediaSession 喂 KeyMonitor
 * 前清除 onMediaButtonRouted 打的 SRC_MEDIASESSION 路由 mark——它只服务于 MDC 转发去重
 * (Bug#53,用 lastMediaSessionRoute*),清掉它不影响 MDC 去重,但避免 MediaSession 自己喂入的
 * 媒体键在 onKeyAction 里被 wasCopyHandledNearThisPhysicalPress 当成"已处理副本"而吸收(丢键)。 */
    private void clearDirCopyMark(int dir) {
        if (dir < 0 || dir >= DIR_COUNT) return;
        lastLogcatKeyHandleMs[dir] = 0L;
        lastLogcatKeyHandleSrc[dir] = -1;
    }
 /**
 * 仅在目标状态与当前实际状态不一致时才调用 setComponentEnabledSetting。
 * 冗余调用(尤其酷狗接收器"始终禁用"场景下每次开关/重启都重复禁用)
 * 会扰动系统媒体键路由注册——真车实测"关闭屏蔽三键后酷狗仍不能方控,必须重启酷狗"。
 * 状态一致时跳过调用,避免该扰动,也避免无意义的包组件状态写入。
 */
    private void setComponentEnabledIfNeeded(PackageManager pm, ComponentName comp, int targetState) {
        try {
            int cur = pm.getComponentEnabledSetting(comp);
            if (cur == targetState) {
                return;
            }
            pm.setComponentEnabledSetting(comp, targetState, PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Log.w(TAG, "setComponentEnabledIfNeeded failed: " + comp + " " + t.getMessage());
        }
    }
 /** 切源后重设第三方 MEDIA_BUTTON 接收器策略:无论屏蔽三键开关,只保留当前控制器(含 ghost)
 * 的接收器启用(让它经车广播自行切歌,CarMedia transportControls 对概念版等是空操作),
 * 其余第三方一律禁用(防 auto+lite 同装时 lite 抢播切源)。用 MATCH_DISABLED_COMPONENTS
 * 找出当前被禁用的控制器接收器并恢复启用,避免"切到概念版时它接收器仍是禁用 → 按键没反应"。
 * setComponentEnabledIfNeeded 状态去重,冗余调用安全。 */
    private void applyControllerReceiverPolicyOnSwitch() {
        String target = (ghostControllerPkg != null) ? ghostControllerPkg : controllerPkg;
        if (target == null) return;
        try {
            PackageManager pm = getPackageManager();
            Intent qi = new Intent(Intent.ACTION_MEDIA_BUTTON);
            java.util.List<ResolveInfo> q = pm.queryBroadcastReceivers(qi,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            if (q != null) {
                for (ResolveInfo ri : q) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai == null || ai.name == null) continue;
                    String pkg = ai.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    if ("ecarx.xsf.mediacenter".equals(pkg)) continue;
                    if ("com.android.bluetooth".equals(pkg)) continue;
                    if ("com.android.systemui".equals(pkg)) continue;
                    boolean isTargetOwn = pkg.equals(target);
                    boolean kugouAlwaysDisabled = isReceiverAlwaysDisabled(pkg);
                    // 无论屏蔽三键开关,非当前控制器的第三方接收器一律禁用——
                    // auto+lite 同装时,lite 接收器若启用会收到 MEDIA_BUTTON 广播抢播(切歌切到概念版)。
                    // 只保留当前控制器接收器(概念版等靠接收器切歌);kugou.auto 始终禁用优先(走 startService)。
                    boolean disable = kugouAlwaysDisabled || !isTargetOwn;
                    ComponentName comp = new ComponentName(pkg, ai.name);
                    setComponentEnabledIfNeeded(pm, comp,
                            disable ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                    Log.i(TAG, "switch receiver policy: " + (disable ? "disabled" : "enabled")
                            + " " + pkg + "/" + ai.name + (isTargetOwn ? " (controller, keep for cut)" : ""));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyControllerReceiverPolicyOnSwitch error: " + t.getMessage());
        }
    }
 /** 开机自启/服务重建时禁用 com.ecarx.multimedia 启动页(原 MainActivity 逻辑收编到后台)。 */
    public void setWheelKeysBlocked(boolean blocked) {
        // 1) 禁用/启用系统 MediaKeyReceiver(真正的屏蔽:断开 MODE/上一首/下一首链路)
        // 模拟器上没有 ecarx.xsf.mediacenter 包,独立 try-catch 不影响按键监听
        try {
            PackageManager pm = getPackageManager();
            ComponentName comp = new ComponentName(
                    "ecarx.xsf.mediacenter",
                    "ecarx.xsf.mediacenter.MediaKeyReceiver");
            // 状态去重——仅在目标状态与实际状态不一致时才调用
            // setComponentEnabledSetting。冗余的重复禁用/启用会扰动系统媒体键路由,
            // 是"关闭屏蔽三键后仍不能方控、必须重启酷狗才恢复"的可疑根因之一。
            setComponentEnabledIfNeeded(pm, comp,
                    blocked ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        } catch (Exception e) {
            Log.w(TAG, "MediaKeyReceiver toggle failed (not present on this device): "
                    + e.getMessage());
        }
        // 2) 方案B :批量禁用/启用第三方音乐应用的 MEDIA_BUTTON Receiver。
        // 车机输入服务直接发 ACTION_MEDIA_BUTTON 普通广播(非有序),abortBroadcast 无效,
        // 酷狗等静态 Receiver 收到后自动起播抢焦点。屏蔽三键时禁用这些 Receiver,
        // 由 CarMedia 广播拦截器统一转发给当前控制器。
        try {
            PackageManager pm2 = getPackageManager();
            Intent queryIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            java.util.List<ResolveInfo> receivers = pm2.queryBroadcastReceivers(queryIntent, 0);
            for (ResolveInfo ri : receivers) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null) continue;
                String pkg = ai.packageName;
                String cls = ai.name;
                if (pkg.equals(getPackageName())) continue;
                if ("ecarx.xsf.mediacenter".equals(pkg)) continue;
                if ("com.android.bluetooth".equals(pkg)) continue;
                if ("com.android.systemui".equals(pkg)) continue;
                // 酷狗始终禁用!不管屏蔽三键是否打开,都禁用酷狗的 MediaButtonIntentReceiver。
                // 原因:
                // 1. 现在已验证通过 startService 启动 KugouPlaybackService 可以直接控制酷狗
                // (command="togglepause"/"next"/"previous" + keycode),不需要 MediaButtonIntentReceiver。
                // 2. XSFInputService 发送的是普通广播(ordered=false),abortBroadcast() 无效,
                // 酷狗的 MediaButtonIntentReceiver(priority=Integer.MAX_VALUE)会收到广播并抢播,
                // 导致播放汽水/酷我时按方向盘切歌,酷狗也会开始播放。
                // 3. 用户要求"不管打不打开屏蔽三键,都不应该乱跳音源"。
                // 所以酷狗的 MediaButtonIntentReceiver 必须始终禁用,不依赖屏蔽三键的状态。
                boolean kugouAlwaysDisabled = isReceiverAlwaysDisabled(pkg);
                // 当前控制器(含 ghost)自带 MEDIA_BUTTON 接收器时不禁用——概念版等
                // 只靠接收器切歌,transportControls 是空操作;屏蔽三键时若连它一起禁用,
                // 物理键就彻底没人处理(按键没反应)。保留它,物理键广播直达其接收器自行切歌
                // (physicalSend/EAS 会让位防双切)。
                // 改为"无论屏蔽三键开关,非当前控制器的第三方接收器一律禁用"。
                // auto+lite 同装时,lite 接收器若在屏蔽关时被启用,会收到 MEDIA_BUTTON 广播抢播
                // → "切歌切到概念版"。只保留当前控制器接收器,杜绝任何源乱跳(用户硬性要求)。
                // 尚未选控制器(controllerPkg 与 ghostControllerPkg 皆空)时全部启用,避免首次启动无源可切。
                boolean deferTargetOwn = pkg.equals(controllerPkg) || pkg.equals(ghostControllerPkg);
                boolean noTarget = (controllerPkg == null) && (ghostControllerPkg == null);
                // kugou.auto 始终禁用优先:它走 startService 不用接收器,即使恰好是控制器也禁用(防双切)。
                boolean shouldDisable = kugouAlwaysDisabled || (!noTarget && !deferTargetOwn);
                try {
                    ComponentName comp = new ComponentName(pkg, cls);
                    // 状态去重(见 setComponentEnabledIfNeeded 注释)。
                    // 酷狗接收器"始终禁用",旧逻辑每次开关/服务重启都会重复 setComponentEnabledSetting
                    // (DISABLED),冗余调用会扰动系统媒体键路由注册,导致关闭屏蔽后酷狗仍不能方控。
                    setComponentEnabledIfNeeded(pm2, comp,
                            shouldDisable ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                    Log.i(TAG, "third-party MEDIA_BUTTON receiver " + (shouldDisable ? "disabled" : "enabled")
                            + ": " + pkg + "/" + cls
                            + (kugouAlwaysDisabled ? " (kugou always disabled)" : ""));
                } catch (Exception e) {
                    Log.w(TAG, "toggle receiver failed: " + pkg + "/" + cls + " " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query MEDIA_BUTTON receivers failed: " + e.getMessage());
        }
        // ★ 硬编码禁用酷狗 MEDIA_BUTTON 接收器(不依赖 queryBroadcastReceivers)。
        // 根因:queryBroadcastReceivers 在部分车机上返回空列表或找不到酷狗接收器,
        // 导致酷狗的 MediaButtonIntentReceiver(priority=MAX)始终启用,收到普通广播后抢播。
        // 从反编译 Manifest 确认酷狗接收器完整组件名:
        // com.kugou.android.auto / com.kugou.framework.service.headset.MediaButtonIntentReceiver
        // 直接 setComponentEnabledSetting 禁用,绕过 query 阶段。
        try {
            PackageManager pmHard = getPackageManager();
            ComponentName kugouComp = new ComponentName(
                    "com.kugou.android.auto",
                    "com.kugou.framework.service.headset.MediaButtonIntentReceiver");
            int kugouCur = pmHard.getComponentEnabledSetting(kugouComp);
            if (kugouCur != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pmHard.setComponentEnabledSetting(kugouComp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "kugou MediaButtonIntentReceiver hard-disabled (was state=" + kugouCur + ")");
            } else {
                Log.d(TAG, "kugou MediaButtonIntentReceiver already disabled, skip");
            }
        } catch (Throwable t) {
            Log.w(TAG, "hard-disable kugou MediaButtonIntentReceiver failed: " + t.getMessage());
        }
        // ★ 延迟重试禁用酷狗接收器。
        // 根因1:开机时 PackageManager 还没完全扫描完酷狗 APK,首次 setComponentEnabledSetting 可能失败。
        // 根因2(反编译确认):酷狗在 MediaSession 初始化时会通过 setComponentEnabledSetting
        // 动态启用自己的 MediaButtonIntentReceiver(见 smali/com/kugou/framework/service/mediasession/e.smali),
        // 覆盖 CarMedia 的禁用。必须在酷狗开机自启动 + MediaSession 初始化完成后再禁用。
        // 分五档延迟重试:5s(包扫描)/15s(BOOT_COMPLETED)/30s(Splash+App初始化)/60s(MediaSession初始化)/120s(兜底)。
        final Handler h = new Handler(android.os.Looper.getMainLooper());
        final int[] retryDelays = {5000, 15000, 30000, 60000, 120000};
        for (final long delay : retryDelays) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        PackageManager pmR = getPackageManager();
                        ComponentName kComp = new ComponentName(
                                "com.kugou.android.auto",
                                "com.kugou.framework.service.headset.MediaButtonIntentReceiver");
                        int cur = pmR.getComponentEnabledSetting(kComp);
                        if (cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                            Log.d(TAG, "kugou receiver retry @ " + delay + "ms: already disabled");
                            return;
                        }
                        pmR.setComponentEnabledSetting(kComp,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP);
                        Log.i(TAG, "kugou receiver retry @ " + delay + "ms: disabled (was " + cur + ")");
                    } catch (Throwable t) {
                        Log.w(TAG, "kugou receiver retry @ " + delay + "ms failed: " + t.getMessage());
                    }
                }
            }, delay);
        }
        // 3) 不再调用 keyMonitor.register()。
        // 按键监听完全靠 LogcatKeyMonitor（logcat 通道），requestKeysInterception
        // 已废弃（会导致输入服务死循环→音量乱跳）。LogcatKeyMonitor 在 initKeyMonitor
        // 中已启动且始终运行，不需要在此处确保注册。
        // 3) 持久化勾选状态
        getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                .edit().putBoolean("key_blocked", blocked).apply();
        // ★ 解除屏蔽时重新断言自建 MediaSession 活跃状态,促使 MediaSessionService
        // 重新评估媒体按钮会话归属——若"关闭开关后仍不能方控"是系统媒体键路由未即时恢复所致,
        // 此 nudge 让系统把媒体键路由回 CarMedia 的媒体键代理 Callback,立即恢复方控。
        if (!blocked && mediaButtonViaSession && mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.setActive(true);
                Log.i(TAG, "setWheelKeysBlocked: re-asserted MediaSession active (media button proxy)");
            } catch (Throwable t) {
                Log.w(TAG, "setWheelKeysBlocked: re-assert MediaSession failed: " + t.getMessage());
            }
        }
    }
    private class KeyMonitorCallback implements KeyMonitor.Callback {
        @Override
        public void onKeyAction(String action, int keyCode) {
            // MediaKeyReceiver 会把媒体键路由到"默认/活跃会话"(常常是正在抢焦点的 QQ 音乐),
            // 用户听蓝牙时切歌/播放暂停按了没反应(真车反馈)。蓝牙控制器下 CarMedia 接管,
            // keyTargetController=当前控制器,QQ 在播切 QQ、蓝牙在播切蓝牙;
            // 手机互联场景(CarPlay/HiCar/CarLife 等无自己的 MediaSession)fallback 到 mediacenter 代理。
            Log.i(TAG, "Key action=" + action + " keyCode=" + keyCode);
            // ★ v11:未屏蔽三键时,仅上一首/下一首由系统通道处理,
            // CarMedia 的 logcat 通道不插手,避免与系统 MediaKeyReceiver 双发 → 连切2首。
            // 播放暂停/静音/MODE/SOURCE 均由 CarMedia 接管(用户要求)。
            // Bug#53 修正：撤销"屏蔽三键时让位"——真车实测屏蔽三键后系统侧全关
            // (MediaKeyReceiver 禁用,系统注入/MDC 转发都不再发生),CarMedia logcat 通道是唯一
            // 切歌路径,必须接管;让位会导致按了没反应。未屏蔽三键时仍让位,交给系统注入。
            // ★ 移除"未屏蔽三键时跳过 prev/next"逻辑：
            // 原设计是让位给系统 MediaKeyReceiver 处理切歌，防 CarMedia+系统双切。
            // 但 registerMediaButtonBlocker() 已用最高优先级拦截并 abortBroadcast() 截断了
            // ACTION_MEDIA_BUTTON 有序广播，系统 MediaKeyReceiver 和酷狗均收不到广播，
            // 系统通道不再处理切歌。CarMedia logcat 通道是唯一切歌路径，必须接管。
            // 双切防护由下方 isMdcHandledRecently() 负责(MDC 转发去重)。
            // ★ 双切修复：不勾选"屏蔽三键"时，方向盘三键(304/305/300)由系统
            // MediaKeyReceiver/MDC 处理（injectInputEvent → 播放器切歌），CarMedia 的
            // logcat 通道不得再重复处理，否则按一次切两首（真车实测：删掉 304/305 映射后
            // 系统单切，证明双切 = CarMedia logcat 通道 + 系统通道）。
            // 勾选"屏蔽三键"时系统被禁用，CarMedia 接管三键 → 单切。
            // v6:蓝牙控制器时 CarMedia 始终接管三键——未勾选屏蔽三键时系统
            // MediaKeyReceiver 会把媒体键路由到"默认/活跃会话"(常常是正在抢焦点的 QQ 音乐),
            // 用户听蓝牙时切歌/播放暂停按了没反应(真车反馈)。蓝牙控制器下 CarMedia 接管,
            // 命令直达蓝牙(mediaController=com.android.bluetooth);若系统同时路由到蓝牙
            // 造成双切,可勾选"屏蔽三键"(禁用系统 MediaKeyReceiver)配合。
            // ★ v12:根据"屏蔽三键"状态决定系统 MediaKeyReceiver 启用/禁用,
            // 不再无条件禁用。initKeyMonitor 按 key_blocked 持久化值恢复(见 setWheelKeysBlocked):
            // - 未屏蔽三键(key_blocked=false):系统 MediaKeyReceiver 启用,上/下一首由系统注入
            // 处理,CarMedia logcat 通道在 onKeyAction 开头 skip 上/下一首防双切。
            // - 屏蔽三键(key_blocked=true):系统 MediaKeyReceiver 禁用,CarMedia 完全接管三键。
            // keyTargetController=当前控制器,QQ 在播切 QQ、蓝牙在播切蓝牙;
            // 手机互联场景(CarPlay/HiCar/CarLife 等无自己的 MediaSession)fallback 到 mediacenter 代理。
            // 模式键(348) 系统不处理,始终由 CarMedia 接管,不受此判断影响。
            // 单击/双击已在 KeyMonitor 层判定完毕,action 为纯动作常量(如 mode/source/prev/next/playpause)
            if (KeyMonitor.ACTION_MODE.equals(action)) {
                openMainUi();
            } else if (KeyMonitor.ACTION_HOME.equals(action)) {
                openMainUi();
            } else if (KeyMonitor.ACTION_PREV.equals(action)) {
                // 方案A :MediaSession 媒体键代理就绪时,prev/next/playpause
                // 由 MediaSession.Callback.onMediaButtonEvent 唯一处理,logcat 通道跳过防双触发。
                // 修复:仅标准 Android 媒体键码(85-91)才跳过,CAR.INPUT 私有码(300/304/305等)
                // 不会被系统路由到 MediaSession,跳过会导致动作彻底丢失(静音键 300 playpause 没反应根因)。
                if (mediaButtonViaSession && isSystemMediaKeyCode(keyCode)) {
                    Log.i(TAG, "PREV key handled by MediaSession.Callback, skip logcat channel to avoid double-trigger");
                    return;
                }
                // 蓝牙防双切兼容补丁(同 NEXT):判定依据用"XCMedia2 是否持有 MDC 焦点"
                // (isMultimediaHoldingFocus = queryCurrentFocusClient==com.ecarx.multimedia),不用
                // "是否被禁用"(isMultimediaDisabled,包启用状态)。持焦点 → 系统注入的媒体键会路由给
                // XCMedia2 切歌 → CarMedia 让位防双切;不持焦点(含禁用/进程死/焦点被其他客户端抢走)
                // → 系统注入那刀无人接 → CarMedia 接管兜底,保证禁用/不禁用都单切。
                // onKeyAction 位于内部类 KeyMonitorCallback,补丁原先直接引用 bridge
                // 无该局部变量 → 编译报"找不到符号"。改为内联 MediaCenterBridge.get() + 判空。
                MediaCenterBridge bridge = MediaCenterBridge.get();
                if (bridge != null && !isKeyBlockedSetting() && isBluetoothController()
                        && bridge.isMultimediaHoldingFocus()) {
                    Log.i(TAG, "PREV key: BT+unblocked+XCMedia2 holds MDC focus, yield to system injection (avoid double skip)");
                    return;
                }
                // 通用防双切:未屏蔽三键时,若系统注入的媒体键会命中当前控制器
                // (当前真实在播的音乐应用 == 控制器),则 CarMedia 让位给系统通道,避免
                // CarMedia startService 与系统注入 keycode 87 双发连切2首(真车日志确认)。
                // 非音源白名单:任何源只要系统注入会切到它,统一让位;控制器不在播/注入切
                // 不到控制器时,CarMedia 接管兜底,不丢键。见 systemMediaKeyHitsController()。
                if (systemMediaKeyHitsController()) {
                    Log.i(TAG, "PREV key: system media key injection will hit controller, yield to system channel (avoid double skip)");
                    // 让位后若系统注入/接收器窗口内没真切歌(接收器 inert 的 App),
                    // CarMedia 兜底切歌,避免"让位=按键丢失"。
                    scheduleReceiverCutFallback("prev");
                    return;
                }
                // 删除旧"让位系统通道"分支( 引入,恢复 Bug#47 时误信
                // "系统通道一直活着"——真车确认系统媒体键被 CarMedia 媒体键代理截走,
                // 播放器收不到,让位=按键丢失)。shouldCarMediaOwnKey 已改为通用接管,
                // 本通道收到物理键即处理,防双切由下方 wasCopyHandledNearThisPhysicalPress 负责。
                // Bug#52: MDC 转发可能先到达并已切歌,logcat 通道后到达时需去重
                // 同时检查 isEasFromRecentLogcat —— MediaSession.Callback 先处理媒体键时
                // 也打 logcat mark,后到达的 logcat 通道必须忽略,否则按一次切歌=双通道各切一次(USB 连切2首确认)
                // 本通道是 logcat 真实按键,保持 500ms 短窗口(长窗口会吞用户快速连按两次)。
                // 短窗口失效,靠"副本处理时刻落在[上一次按压解析,本次按压解析)"判定吸收(真车 07:20:21 序列 422ms 实证)。
                // 只保留物理时刻窗口(吸收本次按压的副本)");500ms 短窗口会吞快速连按第二次
                    Log.i(TAG, "PREV key dedup: MDC 转发/MediaSession 刚处理同按键,logcat 通道忽略防双击)");
                if (wasCopyHandledNearThisPhysicalPress(DIR_PREV)) {
                    Log.i(TAG, "PREV key dedup: MDC 转发/MediaSession 刚处理同按键,logcat 通道忽略防双切");
                    return;
                }
                Log.i(TAG, "PREV key pressed - handled by CarMedia");
                markLogcatKeyHandle(DIR_PREV, SRC_LOGCAT);
                physicalSend("prev");
            } else if (KeyMonitor.ACTION_NEXT.equals(action)) {
                if (mediaButtonViaSession && isSystemMediaKeyCode(keyCode)) {
                    Log.i(TAG, "NEXT key handled by MediaSession.Callback, skip logcat channel to avoid double-trigger");
                    return;
                }
                // 蓝牙防双切兼容补丁:未屏蔽三键 + 蓝牙 + XCMedia2 持有 MDC 焦点时,
                // 系统注入的 KEYCODE_MEDIA_NEXT 已路由给 XCMedia2 切歌 → CarMedia 让位防双切
                // (实测:不禁用 XCMedia2 会双切,禁用则单切);
                // XCMedia2 未持有焦点(含禁用/进程死/焦点被抢)→ 系统注入那刀消失 → CarMedia 必须接管。
                // 判定用"是否持有 MDC 焦点"(isMultimediaHoldingFocus)而非"是否禁用"
                // (isMultimediaDisabled,包启用状态)——禁用时焦点必不在 XCMedia2,前者严格覆盖后者。
                MediaCenterBridge bridge = MediaCenterBridge.get();
                if (bridge != null && !isKeyBlockedSetting() && isBluetoothController()
                        && bridge.isMultimediaHoldingFocus()) {
                    Log.i(TAG, "NEXT key: BT+unblocked+XCMedia2 holds MDC focus, yield to system injection (avoid double skip)");
                    return;
                }
                // 通用防双切:与 PREV 同款,未屏蔽三键时若系统注入会命中当前控制器
                // 则让位,防 CarMedia startService + 系统注入 keycode 87 双发连切2首。
                // 通用非白名单,见 systemMediaKeyHitsController()。
                if (systemMediaKeyHitsController()) {
                    Log.i(TAG, "NEXT key: system media key injection will hit controller, yield to system channel (avoid double skip)");
                    // 让位后若系统注入/接收器窗口内没真切歌(接收器 inert 的 App),
                    // CarMedia 兜底切歌,避免"让位=按键丢失"。
                    scheduleReceiverCutFallback("next");
                    return;
                }
                // 删除旧"让位系统通道"分支,见 PREV 同款说明。
                    Log.i(TAG, "NEXT key dedup: MDC 转发/MediaSession 刚处理同按键,logcat 通道忽略防双击)");
                if (wasCopyHandledNearThisPhysicalPress(DIR_NEXT)) {
                    Log.i(TAG, "NEXT key dedup: MDC 转发/MediaSession 刚处理同按键,logcat 通道忽略防双切");
                    return;
                }
                Log.i(TAG, "NEXT key pressed - handled by CarMedia");
                markLogcatKeyHandle(DIR_NEXT, SRC_LOGCAT);
                physicalSend("next");
            } else if (KeyMonitor.ACTION_MUTE.equals(action)) {
                // 与 prev/next 同款双向去重,防双静音
                int muteDir = dirOfKeyCode(keyCode);
                // 删除旧"让位系统通道"分支,静音键由 CarMedia 统一接管。
                if (wasCopyHandledNearThisPhysicalPress(muteDir)) {
                    Log.i(TAG, "MUTE key dedup: 另一通道刚处理同按键,logcat 通道忽略");
                    return;
                }
                markLogcatKeyHandle(muteDir, SRC_LOGCAT);
                toggleMute();
            } else if (KeyMonitor.ACTION_PLAYPAUSE.equals(action)) {
                if (mediaButtonViaSession && isSystemMediaKeyCode(keyCode)) {
                    Log.i(TAG, "PLAYPAUSE key handled by MediaSession.Callback, skip logcat channel to avoid double-trigger");
                    return;
                }
                // 删除旧"让位系统通道"分支,见 PREV 同款说明。
                // 与 prev/next 同款双向去重,防 MediaSession/MDC 先处理后 logcat 双触发
                    Log.i(TAG, "PLAYPAUSE key dedup: 另一通道刚处理同按键,logcat 通道忽略防双击)");
                if (wasCopyHandledNearThisPhysicalPress(DIR_PLAYPAUSE)) {
                    Log.i(TAG, "PLAYPAUSE key dedup: 另一通道刚处理同按键,logcat 通道忽略防双切");
                    return;
                }
                markLogcatKeyHandle(DIR_PLAYPAUSE, SRC_LOGCAT);
                physicalSend("playpause");
            } else if (KeyMonitor.ACTION_SOURCE.equals(action)) {
                openCurrentSourceApp();
            } else if (action != null && action.startsWith("openapp:")) {
                // 新增：自定义打开 App（含系统 App），action 格式 openapp:<包名>
                String pkg = action.substring(8);
                Log.i(TAG, "Key action=openapp pkg=" + pkg);
                launchAppByPackage(pkg);
            }
        }
        @Override
        public void onMonitorStatus(String status) {
            Log.i(TAG, status);
        }
    }
 /** 静音切换(保留兼容:用户手动录了 mute 动作时生效;默认映射已用 playpause) */
    private void toggleMute() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            am.setStreamMute(AudioManager.STREAM_MUSIC, !muted);
            Log.i(TAG, "toggleMute -> " + (!muted ? "muted" : "unmuted"));
        } catch (Throwable t) {
            Log.w(TAG, "toggleMute error: " + t.getMessage());
        }
    }
    private void openMainUi() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "openMainUi failed: " + e.getMessage());
        }
    }
    private void openCurrentSourceApp() {
        try {
            String pkg = controllerPkg;
            if (pkg == null || pkg.isEmpty()) {
                Log.i(TAG, "openCurrentSourceApp: no controller pkg, fallback to main ui");
                openMainUi();
                return;
            }
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                Log.w(TAG, "openCurrentSourceApp: no launch intent for " + pkg
                        + ", fallback to main ui");
                openMainUi();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            Log.i(TAG, "openCurrentSourceApp: launching " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "openCurrentSourceApp failed: " + t.getMessage());
            openMainUi();
        }
    }
// ================= P2: UI 访问器 =================
    public List<MediaController> getAvailableControllers() {
        return availableControllers;
    }
 /**
 * 解析 dumpsys media_session 文本,提取所有在 Sessions Stack 里但 active=false 的包名。
 * API 28 的 getSessions/getActiveSessions 都只返回 active=true 的会话,
 * 酷我车机版暂停时 active=false 被过滤,但 dumpsys 能看到。
 * 返回的包名列表用于 UI 控制器列表展示(ghost 条目),用户点击时用 am start 拉起。
 */
    // 卡顿修复:getGhostControllerPkgs 结果缓存。
    // buildMetaSig() 每秒被 pushAll() 调用一次,而该方法每次启动 dumpsys media_session
    // 子进程(fork+IPC+全量文本解析,车机低端 SoC 上 100ms~1s),主线程被阻塞导致
    // 进度条/歌词推送延迟到好几秒一次。ghost 列表只随媒体会话注册/销毁变化,
    // 10s 缓存足够实时,且 updateAvailableControllers() 会随会话回调刷新,列表长期准确。
    private volatile List<String> cachedGhostPkgs;
    private volatile long ghostCacheTime = 0L;
    private static final long GHOST_CACHE_MS = 10000L;
    private final Object ghostCacheLock = new Object();

 /** 盒子会话出现/消失时作废旧 ghost 缓存,让 CarPlay 列表项及时增减。 */
    private void invalidateGhostCache() {
        synchronized (ghostCacheLock) {
            cachedGhostPkgs = null;
            ghostCacheTime = 0L;
        }
    }

    public List<String> getGhostControllerPkgs() {
        long now = SystemClock.elapsedRealtime();
        if (cachedGhostPkgs != null && now - ghostCacheTime < GHOST_CACHE_MS) {
            return cachedGhostPkgs;
        }
        List<String> result = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            String currentPkg = null;
            boolean currentActive = false;
            boolean inSession = false;
            while ((line = br.readLine()) != null) {
                // Session 行格式(多种 ROM 兼容):
                // 真车: "Session <pkg>/<component> (userId=0)"
                // 模拟器: " KwMediaSessionService cn.kuwo.kwmusiccar/KwMediaSessionService (userId=0)"
                // 共同点: 含 "(userId=" 且含 "/"(pkg/component),且不是 "Media button session is ..." 噪声行。
                // 旧正则要求行首是 "Session" 或非缩进,匹配不上模拟器 4 空格缩进+类名 tag 的格式,
                // 导致暂停(active=false)的酷我在模拟器上识别不到、从切换列表消失(真车格式能匹配所以真车正常)。
                if (line.contains("(userId=") && line.contains("/") && !line.contains("session is")) {
                    // 处理前一个 session
                    if (inSession && currentPkg != null && !currentActive) {
                        addGhostIfMissing(result, currentPkg);
                    }
                    inSession = true;
                    currentPkg = null;
                    currentActive = false;
                }
                if (inSession) {
                    if (line.contains("package=")) {
                        int idx = line.indexOf("package=");
                        if (idx >= 0) {
                            currentPkg = line.substring(idx + 8).trim();
                        }
                    }
                    if (line.contains("active=")) {
                        int idx = line.indexOf("active=");
                        if (idx >= 0) {
                            currentActive = line.substring(idx + 7, idx + 11).startsWith("true");
                        }
                    }
                }
            }
            // 最后一个 session
            if (inSession && currentPkg != null && !currentActive) {
                addGhostIfMissing(result, currentPkg);
            }
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "getGhostControllerPkgs error: " + t.getMessage());
        }
        // CarPlay 改为"白名单盒子出现才显示"——不再无条件常驻列表。
        // E-Box 无 MediaSession,不进 availableControllers(活跃会话),dumpsys 的 Sessions Stack
        // 里也没有它(无媒体会话),因此 UI ghost 条目机制只负责渲染,是否出现由这里决定:
        // 仅当白名单盒子(com.carletter.ceb / com.aufn.carplay,见 CARPLAY_BOX_REAL_PACKAGES)
        // 真实 MediaSession 出现(getKnownControllers 全量会话命中 isCarplayBoxRealPkg)时,
        // 才追加 CarPlay 条目;盒子未连接/未注册会话则隐藏。点击选中后仍走注入(dispatchMediaAction)。
        if (findCarplayBoxController() != null) {
            if (!result.contains(PKG_CARPLAY_BOX)) {
                result.add(PKG_CARPLAY_BOX);
                Log.d(TAG, "getGhostControllerPkgs: + CarPlay box (白名单盒子会话出现)");
            }
        } else {
            Log.d(TAG, "getGhostControllerPkgs: CarPlay hidden (无白名单盒子会话)");
        }
        // 卡顿修复:结果写入缓存(10s)。解析失败时保留旧缓存,不空转 dumpsys。
        synchronized (ghostCacheLock) {
            cachedGhostPkgs = result;
            ghostCacheTime = SystemClock.elapsedRealtime();
        }
        return result;
    }
    private void addGhostIfMissing(List<String> list, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(PKG_SELF) || isHiddenController(pkg)) {
            return;
        }
        // 酷狗特殊处理:酷狗的 MediaSession active=false 且 PlaybackState 永远 PAUSED,
        // 但实际可能在播放(AudioTrack started)。如果酷狗实际在播放,不标记为 ghost 控制器,
        // 避免 CarMedia 自动切换到其他活跃控制器(如酷我)。
        if (PKG_KUGOU.equals(pkg) && isAudioReallyPlaying(pkg)) {
            return;
        }
        // 排除已在 availableControllers 里的(active=true 的)
        for (MediaController mc : availableControllers) {
            if (mc != null && pkg.equals(mc.getPackageName())) {
                return;
            }
        }
        // 排重
        if (!list.contains(pkg)) {
            list.add(pkg);
            Log.d(TAG, "getGhostControllerPkgs: found paused session " + pkg);
        }
    }
    public String getCurrentTitle() {
        return currentTitle;
    }
    public String getCurrentArtist() {
        return currentArtist;
    }
 /** 当前控制器是否为蓝牙(A2DP sink)。 */
    private boolean isBluetoothController() {
        return "com.android.bluetooth".equals(controllerPkg);
    }
 /**
 * 蓝牙是否真实在播:A2DP sink 的 AudioTrack 常驻 started 不可靠,
 * 一律以蓝牙 MediaSession 实时状态为准(state==3 播放才算真在播)。
 * 与 Bug#25/方案B 的"蓝牙用 controller 实时会话状态"口径一致。
 */
    private boolean isBluetoothReallyPlaying() {
        try {
            MediaController bt = findControllerByPackage(PKG_BLUETOOTH);
            if (bt == null || bt.getPlaybackState() == null) {
                return false;
            }
            return bt.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable t) {
            return false;
        }
    }

 /**
 * (A2,对齐原厂 isBtReady):AVRCP profile 是否已连接。
 * 反射 getProfileConnectionState(6)(BluetoothProfile.AVRCP=6 / STATE_CONNECTED=2)。
 * 异常回退 true——不收紧现有行为,只做收紧方向上的增强。
 */
    private boolean isAvrcpConnected() {
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba != null) {
                return ba.getProfileConnectionState(6) == 2;
            }
        } catch (Throwable t) {
            Log.w(TAG, "isAvrcpConnected failed: " + t.getMessage());
        }
        return true;
    }

 /** 蓝牙是否"完全就绪"(A2DP && AVRCP 双连接)——幽灵蓝牙(MediaSession 残留但未真连)被排除。 */
    private boolean isBluetoothReady() {
        return a2dpSinkConnected && isAvrcpConnected();
    }

 /**
 * 蓝牙是否"真实在播(有声音)":物理就绪(A2DP+AVRCP 双连接)且 MediaSession 实时状态==PLAYING。
 * 播放 QQ/落雪音乐时中途连上蓝牙(手机没放歌没声音),控制器被切到蓝牙。
 * 蓝牙"连上了"≠"在放音乐"——仅 isBluetoothReady(连接就绪)不够:连接但手机没放歌也会被选走;
 * 仅 isBluetoothReallyPlaying(会话 PLAYING)也不够:幽灵蓝牙(未连手机但 MediaSession 残留
 * PLAYING, 确认)会误放行。两个条件必须同时成立才算"蓝牙真有声音"。
 */
    private boolean isBluetoothActuallyPlaying() {
        return isBluetoothReady() && isBluetoothReallyPlaying();
    }

 /**
 * 蓝牙是否允许被"自动选中为控制器"（对齐 1.3.4 开机选蓝牙行为）。
 * 1.3.4 的 updateAvailableControllers 第二遍(state∈{2,3})对蓝牙**不查物理连接**，
 * 只要蓝牙 MediaSession 存在且 state==PAUSED/PLAYING 就选中，所以 1.3.4 开机会
 * 自动把控制器选到蓝牙。现版曾用 isBluetoothActuallyPlaying(双连接+会话PLAYING)
 * 或 isBluetoothReady(A2DP+AVRCP 双连接)当门槛——这些依赖 a2dpSinkConnected /
 * isAvrcpConnected 反射标志，开机时若蓝牙已在 CarMedia 启动前连好、初始化查询
 * 太早或广播未收到，标志恒为 false，蓝牙永远选不上控制器 → 系统不给蓝牙 A2DP
 * sink audio focus(audioFocus=0) → 手机一播即被 DELAYED_PAUSE/sendAvrcpPause 暂停。
 * 修复:改用 isBluetoothSessionActive(蓝牙 MediaSession 真实存在,不依赖物理连接
 * 反射标志,日志 getKnownControllers 已返回 com.android.bluetooth)且无其他真实
 * 非蓝牙音源在播,即允许选中;既恢复"开机自动选蓝牙"，又不回归 旧
 * Bug(QQ 在播时中途连蓝牙被误切)。
 */
    private boolean isBluetoothSelectable() {
        // (USB/蓝牙自动播放开关):开关关闭时 App 不自动接管蓝牙控制器——
        // 开机/重连的自动选源都不选蓝牙(updateAvailableControllers 第2/3遍与 A2DP 重连
        // 都经本函数判断)。用户手动点蓝牙 tab(selectController)不受影响;手机若自行续播,
        // 声音照常由系统 A2DP 播放,只是 App 不接管显示/控制。开关开启(默认)保持原行为。
        if (!isUsbAutoResumeEnabled()) {
            return false;
        }
        if (!isBluetoothSessionActive()) {
            return false;                 // 蓝牙 MediaSession 不存在(无蓝牙会话)不选
        }
        if (isRealNonBluetoothSourcePlaying()) {
            return false;                 // 有其他真实音源在播不抢(防误切 QQ/酷我等)
        }
        return true;                      // 蓝牙会话存在且无其他源在播 → 允许选中(含暂停态)
    }

 /** 是否有其他真实音源正在播放(USB 自动恢复防抢播用)。
 * 蓝牙以会话实时状态为准(A2DP AudioTrack 常驻 started 不可靠)");
 * USB 自身 AudioTrack 在 getRealPlayingMusicPkg 中被 PKG_SELF 过滤,不算"其他源"。 */
    private boolean isOtherSourceReallyPlaying() {
        try {
            if (isBluetoothReallyPlaying()) {
                return true;
            }
            String realPkg = getRealPlayingMusicPkg();
            return realPkg != null && !UsbSourceController.SOURCE_PKG_USB.equals(realPkg);
        } catch (Throwable t) {
            return false;
        }
    }

 /** (Bug#72E):是否有真实外部"非蓝牙"音源在播(AudioTrack started,
 * 排除系统媒体中心/自身/USB 虚拟包)。
 * 刻意排除蓝牙:蓝牙 A2DP 常驻 started + session 残留 PLAYING 会被
 * isBluetoothReallyPlaying 误判为"在播"(09-12 USB 切歌误切蓝牙的根因),
 * 若把蓝牙算进"外部在播",会挡住 USB 切歌时该有的 re-arm,回归旧 Bug。 */
    private boolean isRealNonBluetoothSourcePlaying() {
        try {
            String realPkg = getRealPlayingMusicPkg();
            return realPkg != null
                    && !UsbSourceController.SOURCE_PKG_USB.equals(realPkg)
                    && !PKG_BLUETOOTH.equals(realPkg);
        } catch (Throwable t) {
            return false;
        }
    }

 /**
 * MediaSession 层面是否真在播放(state==PLAYING)。
 * 配合 isAudioReallyPlaying(AudioTrack started)双重判断,
 * 排除 CarPlay/手机互联等暂停后 AudioTrack 仍常驻 started 的僵尸状态。
 * 蓝牙例外:蓝牙 A2DP sink 的 MediaSession state 可能不准确,已在 isAudioReallyPlaying
 * 中用 isBluetoothReallyPlaying 单独处理,此处直接放行。
 */
    private boolean isSessionReallyPlaying(MediaController c) {
        if (c == null) return false;
        if (PKG_BLUETOOTH.equals(c.getPackageName())) return true;
        // 酷狗车载版 MediaSession 状态不准确(active=false 且 PlaybackState 永远 PAUSED),
        // 但实际在播放(AudioTrack started)。对酷狗改用 isAudioReallyPlaying 判断,否则自动切源
        // 时被双重判断过滤掉,永远切不到酷狗(模拟器+真车日志确认)。
        if (PKG_KUGOU.equals(c.getPackageName())) {
            return isAudioReallyPlaying(c.getPackageName());
        }
        try {
            PlaybackState ps = c.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }
 /** 当前控制器是否"真实在播":蓝牙用 MediaSession 实时状态,其余用 AudioTrack 状态。 */
    private boolean isControllerReallyPlaying() {
        if (controllerPkg == null) {
            return false;
        }
        if (isBluetoothController()) {
            return isBluetoothReallyPlaying();
        }
        return isAudioReallyPlaying(controllerPkg);
    }
    public int getPlaybackState() {
        // USB 音源激活时返回 USB 播放器状态
        if (usbRouteArmed && usbSourceController != null) {
            int s = usbSourceController.getPlayState();
            if (s == UsbMusicPlayer.STATE_PLAYING) return STATE_PLAYING;
            if (s == UsbMusicPlayer.STATE_PAUSED) return STATE_PAUSED;
            return 0;
        }
        // 真实音频状态优先:MediaSession state 可能陈旧/错误(如汽水暂停时仍报 3),
        // AudioManager 的 AudioTrack 状态才是真实播放状态。
        // 优先级:真实音频状态 > 通知判定 > controller 回调缓存。
        // 例外:蓝牙(com.android.bluetooth)的 A2DP sink AudioTrack 常驻,
        // audioReal 对蓝牙不可靠(暂停时仍可能报 started)--蓝牙一律以
        // controller 实时会话状态为准(桌面原版:state==3 → 播放,其余 → 停止)。
        int audioState = getAudioRealPlayState();
        if (!isBluetoothController() && audioState > 0) {
            return audioState;
        }
        if (mediaController == null) {
            // 无控制器 = 无音源在播:已停止。
            // 之前会命中通知快照/缓存继续报"播放中",音源被停止后主界面
            // 一直残留播放态(用户实测),这里必须返回停止态。
            return 0;
        }
        // 蓝牙:暂停/停止一律显示停止态(桌面原版:非播放状态直接反映到 UI)
        if (isBluetoothController()) {
            try {
                PlaybackState live = mediaController.getPlaybackState();
                if (live != null) {
                    int s = live.getState();
                    return (s == PlaybackState.STATE_PLAYING) ? s : 0;
                }
            } catch (Exception ignored) {
            }
        }
        // 控制器实时状态明确报停止/无状态(STATE_STOPPED=1 / STATE_NONE=0)时,
        // 通知快照/回调缓存不得再报播放--音源停止但会话仍存活时必须立刻显示
        // 停止态(与桌面原版一致:非播放状态直接反映到 UI)。
        try {
            PlaybackState live = mediaController.getPlaybackState();
            if (live != null) {
                int s = live.getState();
                if (s == PlaybackState.STATE_STOPPED || s == PlaybackState.STATE_NONE) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        // 通知优先:有通知权威快照(notifActive)时用通知判定的状态;
        // 否则实时读 controller(lastPlaybackState 是回调缓存,可能陈旧)。
        // 注意:走到这里 audioState==0(真实音频无该源任何 started/paused 的
        // AudioTrack)。播放中几乎必然有 started 的 AudioTrack,audioState==0 =
        // 源未在播(暂停/停止)。此时 lastPlaybackState 可能是 MediaSession 残留
        // 的 PLAYING(汽水暂停不更新 state)--不可信,一律返回暂停。
        if (notifActive() && lastPlaybackState != null) {
            return lastPlaybackState.getState() == STATE_PLAYING ? STATE_PAUSED : lastPlaybackState.getState();
        }
        if (mediaController.getPlaybackState() != null) {
            int s = mediaController.getPlaybackState().getState();
            return s == STATE_PLAYING ? STATE_PAUSED : s;
        }
        return lastPlaybackState != null ? lastPlaybackState.getState() : 0;
    }
 /**
 * 反射 AudioManager.getActivePlaybackConfigurations() 获取真实音频播放状态。
 * API 28 该方法是隐藏 API(android.jar 中已存在),反射调用。
 * 返回:当前控制器对应包名有 started 的 AudioTrack → STATE_PLAYING(3);
 * 有 paused 的 AudioTrack → STATE_PAUSED(2);无 → 0。
 */
    private int getAudioRealPlayState() {
        // 蓝牙 A2DP sink 的 AudioTrack 常驻 started(暂停/停止时仍报 started),
        // 直接扫描会误报 state=3。蓝牙一律以 MediaSession 实时状态为准,与
        // isBluetoothReallyPlaying()/getPlaybackState() 的蓝牙例外口径一致。
        if (isBluetoothController()) {
            boolean playing = isBluetoothReallyPlaying();
            return playing ? STATE_PLAYING : 0;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return 0;
            }
            Method m = AudioManager.class.getMethod("getActivePlaybackConfigurations");
            Object result = m.invoke(am);
            if (!(result instanceof List<?>)) {
                    return 0;
            }
            List<?> configs = (List<?>) result;
            int targetUid = getPackageUid(controllerPkg);
            boolean isBt = "com.android.bluetooth".equals(controllerPkg);
            boolean anyStarted = false;
            boolean anyPaused = false;
            // 反射获取 PLAYER_STATE_STARTED 真实值(车机ROM=2,标准AOSP=1),
            // 避免硬编码 pstate==2 把 AOSP 的 PAUSED(=2) 误判为播放(酷狗暂停时显示播放中)。
            int playerStartedState = 1;
            try {
                java.lang.reflect.Field f = Class.forName("android.media.AudioPlaybackConfiguration").getField("PLAYER_STATE_STARTED");
                playerStartedState = f.getInt(null);
            } catch (Exception e) { }
            // 第一遍:按当前控制器包名 uid 精确匹配(现有逻辑)
            for (Object conf : configs) {
                // 取 AudioPlaybackConfiguration 的 uid(隐藏方法 getClientUid)
                try {
                    Method getUid = conf.getClass().getMethod("getClientUid");
                    Method getState = conf.getClass().getMethod("getPlayerState");
                    int uid = (Integer) getUid.invoke(conf);
                    int pstate = (Integer) getState.invoke(conf);
                    if (uid == targetUid) {
                        // 调试日志已移除
                        // 只认 PLAYER_STATE_STARTED(ROM=2,AOSP=1 自动适配),
                        // 之前 pstate==2 无 pstate==1 时误判为 ROM started,导致 AOSP 上 PAUSED 被当成播放。
                        if (pstate == playerStartedState) { anyStarted = true; }
                        else if (pstate == 2 || pstate == 3) { anyPaused = true; }
                    }
                    // 检查 XSFMediaCenter 的伪造播放:即使 uid 不在 mConnectedDevices 中,
                    // 也可能被 EAS 卡片触发短时 started。若整个扫描结果只有它,视为无真实播放。
                    // (下方第二遍扫描也会过滤它)
                } catch (Exception e) {
                }
            }
            // anyStarted 已在第一遍扫描中设置(pstate == playerStartedState)
            // 第二遍(蓝牙兜底):按包名 uid 匹配不到(蓝牙 A2DP sink 的 AudioTrack
            // 可能归属 com.android.bluetooth / system(1000) / audioserver,uid 不固定),
            // 且当前控制器是蓝牙时,全局扫描 started/paused 的媒体 AudioTrack。
            // 蓝牙是车机唯一音源时全局判定有效;多音源并发时优先走第一遍精确匹配。
            if (!anyStarted && !anyPaused && isBt) {
                for (Object conf : configs) {
                    try {
                        Method getState = conf.getClass().getMethod("getPlayerState");
                        int pstate = (Integer) getState.invoke(conf);
                        // 跳过非媒体:尽量拿 AudioAttributes 过滤(拿不到放行)
                        try {
                            Object attrs = conf.getClass().getMethod("getAudioAttributes").invoke(conf);
                            Method getUsage = attrs.getClass().getMethod("getUsage");
                            int usage = (Integer) getUsage.invoke(attrs);
                            if (usage != 1) { // USAGE_MEDIA=1
                                continue;
                            }
                        } catch (Exception e) {
                            // 拿不到属性时放行
                        }
                        if (pstate == playerStartedState) { anyStarted = true; }
                        else if (pstate == 2 || pstate == 3) { anyPaused = true; }
                    } catch (Exception e) {
                    }
                }
            }
            // 判定:AOSP started=1 信号存在 → 播放;
            // ROM started=2(无 AOSP 信号时)→ 播放;state=3 → 暂停(两套定义一致)");
            // 有 state=2 且无 state=1(ROM started)→ 播放;有 state=2 且有 state=1(AOSP paused)→ 暂停。
            if (anyStarted) { return STATE_PLAYING; }
            if (anyPaused) { return STATE_PAUSED; }
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "getAudioRealPlayState failed: " + e.getMessage());
            return 0;
        }
    }
    private int getPackageUid(String pkg) {
        if (pkg == null) { return -1; }
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            return -1;
        }
    }
    public long getPlaybackPosition() {
        // USB 音源激活时返回 USB 播放位置
        if (usbRouteArmed && usbSourceController != null) {
            return usbSourceController.getPosition();
        }
        // 真实音频状态优先:汽水等 App 暂停时 MediaSession position 仍会继续走(与 state 残留同类 bug)。
        // 播放中 → 实时读 position;暂停/停止 → 冻结在最后已知播放位置。
        // 蓝牙例外:蓝牙 A2DP sink 的 AudioTrack 常驻,audioReal 对蓝牙不可靠(暂停时仍可能报
        // started)——蓝牙一律以 controller 实时会话状态为准(state==3 → 播放,其余 → 冻结),
        // 与 getPlaybackState() 的蓝牙例外保持一致。
        // 蓝牙进度累加:蓝牙 AVRCP 的 position 通常不实时更新(只在播放/暂停/seek 时发事件),
        // 导致进度条卡住。播放中用本地时间差累加,让进度条平滑走动并同步手机实际位置。
        boolean isBt = isBluetoothController();
        // 统一读一次 live state,避免多次调用 getPlaybackState() 期间状态变化导致不一致
        PlaybackState live = null;
        if (mediaController != null) {
            try {
                live = mediaController.getPlaybackState();
            } catch (Exception ignored) {
            }
        }
        // 判定是否在播放
        boolean playing;
        if (isBt) {
            // 蓝牙:用 controller 实时状态判定(audioReal 不可靠)
            playing = (live != null && live.getState() == PlaybackState.STATE_PLAYING);
        } else {
            int audioState = getAudioRealPlayState();
            playing = (audioState == STATE_PLAYING);
            if (!playing && audioState == STATE_PAUSED) {
                // 非蓝牙且明确暂停:重置非蓝牙累加起点(重新播放时重新初始化),返回冻结值
                lastNonBtPositionTime = 0;
                lastNonBtPositionMs = 0;
                return frozenPlaybackPosition;
            }
        }
        if (playing) {
            if (isBt) {
                // 蓝牙播放:本地时间差累加 + PlaybackState 实时位置校准。
                // 关键:PlaybackState.getPosition() 返回的是"最近一次更新时的位置",不是实时位置。
                // 播放中的实时位置 = getPosition() + (now - getLastPositionUpdateTime())。
                // 车机蓝牙 AVRCP 可能不频繁更新 position,但 seek 后通常会更新一次
                // PlaybackState(position + lastPositionUpdateTime),用上述公式可算出 seek 后的
                // 新位置,从而校准本地累加起点。
                long now = android.os.SystemClock.elapsedRealtime();
                // 计算基于 PlaybackState 的实时位置
                long avrcpPos = 0;
                boolean avrcpValid = false;
                if (live != null) {
                    long basePos = live.getPosition();
                    long updateTime = live.getLastPositionUpdateTime();
                    if (updateTime > 0 && basePos >= 0) {
                        avrcpPos = basePos + (now - updateTime);
                        avrcpValid = true;
                    } else if (basePos > 0) {
                        avrcpPos = basePos;
                        avrcpValid = true;
                    }
                }
                // 累加起点未初始化 → 立即初始化(优先用 avrcpPos,其次 frozen,最后 0)
                if (lastBtPositionTime == 0) {
                    long initStart = avrcpValid ? avrcpPos :
                            ((frozenPlaybackPosition > 0) ? frozenPlaybackPosition : 0);
                    // 切歌过渡保护(与 non-bt 同款)——AVRCP 残留旧歌
                    // position 且已换新歌时长时从 0 起步,防新歌进度条满格。
                    if (initStart > playbackDuration && playbackDuration > 0) {
                        initStart = 0;
                    }
                    lastBtPositionMs = initStart;
                    lastBtPositionTime = now;
                    Log.d(TAG, "bt accumulator init: start=" + lastBtPositionMs
                            + " avrcp=" + (avrcpValid ? avrcpPos : "invalid")
                            + " frozen=" + frozenPlaybackPosition);
                }
                // 用 PlaybackState 实时位置校准累加起点(差距>500ms 才校准,
                // 避免 AVRCP 返回的陈旧值把进度条拉回去)
                if (avrcpValid) {
                    long estimated = lastBtPositionMs + (now - lastBtPositionTime);
                    if (Math.abs(avrcpPos - estimated) > 500) {
                        lastBtPositionMs = avrcpPos;
                        lastBtPositionTime = now;
                        Log.d(TAG, "bt calibrate from PlaybackState: avrcp=" + avrcpPos
                                + " estimated=" + estimated
                                + " diff=" + Math.abs(avrcpPos - estimated));
                    }
                }
                // 本地累加
                long result = lastBtPositionMs + (now - lastBtPositionTime);
                if (playbackDuration > 0 && result > playbackDuration) {
                    result = playbackDuration;
                }
                frozenPlaybackPosition = result;
                return result;
            }
            // 非蓝牙播放:本地时间差累加 + PlaybackState 实时位置校准(与蓝牙同款)。
            // QQ/酷我等车机版音源的 MediaSession position 播放中不实时更新
            // (只在切歌/seek/暂停时更新),裸 getPosition() 会卡住 → 歌词永远取第一行、
            // 进度条不动。实时位置 = getPosition() + (now - getLastPositionUpdateTime()),
            // 再以本地累加器平滑推进(音源 position 实时更新时每帧校准,误差自动收敛)。
            // 酷我特例:MediaSession position 恒 0(重开 App 后)或播放中不更新,PlaybackState
            // 推算不可靠 —— 优先用酷我 AIDL getCurrentPos(1) 拿与 UI 一致的实时位置
            // (KuwoAidlClient 后台轮询缓存,反编译确认含本地补偿)。
            boolean kuwoAidlOk = isKuwoPkg(controllerPkg)
                    && kuwoAidlClient != null && kuwoAidlClient.isAvailable();
            if (live != null || kuwoAidlOk) {
                long now = android.os.SystemClock.elapsedRealtime();
                long realPos = -1;
                if (kuwoAidlOk) {
                    long aidlPos = kuwoAidlClient.getEstimatedPosMs();
                    if (aidlPos >= 0) {
                        realPos = aidlPos;
                    }
                }
                if (realPos < 0 && live != null) {
                    // 基于 PlaybackState 的实时位置(带 lastPositionUpdateTime 补偿)
                    long basePos = live.getPosition();
                    long updateTime = live.getLastPositionUpdateTime();
                    if (updateTime > 0 && basePos >= 0) {
                        realPos = basePos + (now - updateTime);
                    } else if (basePos >= 0) {
                        realPos = basePos;
                    }
                }
                // 累加起点未初始化 → 立即初始化(优先实时推算值,其次 frozen)
                if (lastNonBtPositionTime == 0) {
                    long initStart = (realPos >= 0) ? realPos
                            : ((frozenPlaybackPosition > 0) ? frozenPlaybackPosition : 0);
                    // 切歌过渡保护——metadata 已换新歌(duration 变小)而
                    // PlaybackState 仍残留旧歌 position 时,init 起点会超过新歌时长;
                    // 视为未就绪从 0 起步,避免"旧 position ÷ 新 duration"被下方
                    // clamp 成 100% 的满格进度条(与 handleMetadata 切歌重置配套)。
                    if (initStart > playbackDuration && playbackDuration > 0) {
                        initStart = 0;
                    }
                    lastNonBtPositionMs = initStart;
                    lastNonBtPositionTime = now;
                    Log.d(TAG, "non-bt accumulator init: start=" + lastNonBtPositionMs
                            + " realPos=" + (realPos >= 0 ? realPos : "invalid")
                            + " frozen=" + frozenPlaybackPosition);
                }
                // 用实时位置校准累加起点(差距>500ms 才校准,避免陈旧值把进度拉回去)
                if (realPos >= 0) {
                    long estimated = lastNonBtPositionMs + (now - lastNonBtPositionTime);
                    if (Math.abs(realPos - estimated) > 500) {
                        lastNonBtPositionMs = realPos;
                        lastNonBtPositionTime = now;
                        Log.d(TAG, "non-bt calibrate: real=" + realPos
                                + " estimated=" + estimated
                                + " diff=" + Math.abs(realPos - estimated));
                    }
                }
                long result = lastNonBtPositionMs + (now - lastNonBtPositionTime);
                if (playbackDuration > 0 && result > playbackDuration) {
                    result = playbackDuration;
                }
                frozenPlaybackPosition = result;
                return result;
            }
            return frozenPlaybackPosition;
        }
        // 非播放(蓝牙暂停/停止,或非蓝牙 audioState==0 无法判定):
        if (isBt) {
            // 蓝牙非播放:重置累加起点,返回冻结值
            lastBtPositionTime = 0;
            lastBtPositionMs = 0;
            return frozenPlaybackPosition;
        }
        // 非蓝牙 audioState==0(无法判定)时降级回实时读(保持原行为)
        if (live != null) {
            return live.getPosition();
        }
        return frozenPlaybackPosition;
    }
    public long getPlaybackDuration() {
        // USB 音源激活时返回 USB 当前歌曲时长
        if (usbRouteArmed && usbSourceController != null) {
            long d = usbSourceController.getDuration();
            if (d > 0) return d;
        }
        return playbackDuration;
    }
    public String getControllerPkg() {
        return controllerPkg;
    }
 /** ghost 控制器:用户选中的暂停态会话包名(如暂停的酷我)。 */
    private String ghostControllerPkg;
 /** ghost 保护截止时间(ms)。用户选暂停态会话(ghost)后,保护窗口内
 * (ghostProtectUntil 之前)自动切源/刷新全部让路,防止 9/7 竞态复现;
 * 窗口过期后若出现"真实在播的新源",允许自动切走(否则 ghost 永久挡住
 * 自动切源,打开新音源 App 播放时控制器/歌词停在旧源,界面"卡住")。 */
    private long ghostProtectUntil = 0L;
 /** 用户刚主动选择控制器(通过 setGhostControllerPkg 或 UI 选择)。
 * true 时 dispatchMediaAction 不自动切换到其他在播源,第一次操作后重置为 false。
 * 场景:用户选酷狗后点播放,应控制酷狗(即使酷狗暂未播放),而不是自动切到正在播的酷我。 */
    private boolean controllerJustSelected = false;
 /** 专门用于重置 controllerJustSelected 的 Runnable,避免 removeCallbacksAndMessages(null) 误伤歌词轮询 */
    private final Runnable resetControllerJustSelectedRunnable = new Runnable() {
        @Override
        public void run() {
            controllerJustSelected = false;
            Log.i(TAG, "controllerJustSelected reset after 5s protection window");
        }
    };
    public void setGhostControllerPkg(String pkg) {
        // 用户主动选择控制器:设置标志,dispatchMediaAction 不自动切换(5秒保护窗口)
        // 取消之前可能存在的延迟重置任务,确保新的选择立即生效
        if (lyricPollHandler != null) {
            lyricPollHandler.removeCallbacks(resetControllerJustSelectedRunnable);
        }
        controllerJustSelected = true;
        // 修复:补上 5 秒自动重置调度(与 selectControllerInner/dispatchMediaAction 一致)。
        // 原实现只 removeCallbacks 不 postDelayed,标志永久为 true,
        // 用户选完酷狗/酷我(ghost)后打开其他音源 App 播放,自动切源被永久跳过,
        // 控制器/歌词停在旧源,界面"卡住",只能重新选控制器恢复。
        if (lyricPollHandler != null) {
            lyricPollHandler.postDelayed(resetControllerJustSelectedRunnable, 5000);
        }
        // ghost 保护窗口同步为 5 秒。窗口内自动切源/刷新全部让路,
        // 窗口外出现真实在播新源时允许自动切换(否则 ghost 永久挡住切源)。
        if (pkg != null) {
            ghostProtectUntil = System.currentTimeMillis() + 5000L;
        } else {
            ghostProtectUntil = 0L;
        }
        Log.i(TAG, ">>> setGhostControllerPkg called: pkg=" + pkg
                + " oldGhostPkg=" + ghostControllerPkg
                + " oldControllerPkg=" + controllerPkg
                + " oldMediaController=" + (mediaController != null ? mediaController.getPackageName() : "null"));
        this.ghostControllerPkg = pkg;
        if (pkg != null) {
            this.controllerPkg = pkg;
            // 清掉旧控制器的 metadata,让酷我的通知 title/artist 能被 applyNotificationMeta 接受
            // (applyNotificationMeta 守卫:通知 title 必须与 currentTitle 一致或 currentTitle 为未知)
            currentTitle = null;
            currentArtist = null;
            qqArtistComplete = false;
            frozenPlaybackPosition = 0;
            playbackDuration = 0;   // ghost 选中同样重置时长,防显示上一个音乐的进度条
            lastBtPositionMs = 0;
            lastBtPositionTime = 0;
            lastNonBtPositionMs = 0;
            lastNonBtPositionTime = 0;
            // 清旧歌词
            if (cloudLyricFetcher != null) {
                cloudLyricFetcher.clearLoadedLrc();
            }
            // ghost控制器(酷狗/酷我active=false)不再直接置空mediaController,
            // 而是通过findControllerByPackage兜底(getKnownControllers)拿到active=false的会话,
            // 设置为当前控制器,这样控制按钮才能正常工作。
            MediaController ghostCtrl = findControllerByPackage(pkg);
            if (ghostCtrl != null) {
                Log.i(TAG, "setGhostControllerPkg: found controller for ghost " + pkg);
                // ★关键修复★先取消注册旧控制器的 Callback!
                // 竞态条件:旧 MediaController(酷我/汽水)的 Callback 未取消注册,
                // 当酷我/汽水的元数据变化时,onMetadataChanged() 仍会触发,
                // 此时 mediaController 已被设置为酷狗,handleMetadata() 的来源校验
                // (mediaController.getPackageName()==ghostControllerPkg) 会通过,
                // 导致 currentTitle 被覆盖成酷我/汽水的歌名!
                // 修复:设置新 mediaController 前,先 unregister 旧 Callback。
                unregisterControllerCallback();
                mediaController = ghostCtrl;
                controllerStale = false;
                try {
                    ghostCtrl.registerCallback(controllerCallback);
                } catch (Exception e) {
                    Log.w(TAG, "setGhostControllerPkg: register callback failed: " + e.getMessage());
                }
                // 注册 Callback 后主动读取当前元数据和播放状态!
                // 与 selectControllerInner() 保持一致。否则若播放器元数据没有变化,
                // onMetadataChanged() 不会回调,CarMedia 一直显示"等待音乐播放"。
                // 酷狗场景确认:setGhostControllerPkg 后 currentTitle=null,
                // 但 mediaController.getMetadata() 实际有值,主动读取即可显示歌名歌手。
                try {
                    MediaMetadata meta = ghostCtrl.getMetadata();
                    if (meta != null) {
                        handleMetadata(meta);
                        Log.i(TAG, "setGhostControllerPkg: proactive metadata loaded for " + pkg);
                    } else {
                        Log.w(TAG, "setGhostControllerPkg: metadata null for " + pkg);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setGhostControllerPkg: get metadata error: " + t.getMessage());
                }
                try {
                    PlaybackState ps = ghostCtrl.getPlaybackState();
                    if (ps != null) {
                        updatePlaybackStatus(ps);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setGhostControllerPkg: get playback state error: " + t.getMessage());
                }
            } else {
                Log.w(TAG, "setGhostControllerPkg: no controller found for ghost " + pkg);
                // ★关键修复★即使没找到新控制器,也要取消注册旧控制器的 Callback!
                // 否则旧 MediaController(酷我/汽水)的 Callback 仍会触发,
                // handleMetadata() 被旧源元数据调用,currentTitle 被覆盖成旧源歌名。
                unregisterControllerCallback();
                mediaController = null;
                controllerStale = true;
            }
            // ghost 选中酷我同样启动 AIDL 真实进度客户端
            if (kuwoAidlClient != null) {
                if (isKuwoPkg(pkg)) {
                    kuwoAidlClient.start();
                } else {
                    kuwoAidlClient.stop();
                }
            }
            // ghost 选中酷狗同样启动封面 AIDL 客户端(防"选酷狗但未接管"时无封面)
            if (kugouCoverProvider != null) {
                if (PKG_KUGOU.equals(pkg)) {
                    kugouCoverProvider.start();
                } else {
                    kugouCoverProvider.stop();
                }
            }
            Log.i(TAG, "setGhostControllerPkg: " + pkg + " (cleared old metadata)");
            // 酷我通知早已 posted,不会重新触发 onNotificationPosted 回调,
            // 主动 getActiveNotifications 拉取并解析。
            MediaNotificationListener.requestActiveNotifications(pkg);
        }
    }
    public boolean isDimAutoPush() {
        return dimAutoPush;
    }
 /** 推送开关:直接读本地持久化值(SharedPreferences),不依赖 UI 勾选框/内存态。
 * 所有推送判断(歌词/元数据/源切换)都走这里。 */
    private boolean isDimPushEnabled() {
        try {
            return getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("dim_auto_push", true);
        } catch (Throwable t) {
            return true;
        }
    }
 /** (Bug#72D) + :USB/蓝牙开机自动播放开关(设置项,默认开)。
 * 该开关同时门控蓝牙自动接管(isBluetoothSelectable 读取本值)。 */
    public boolean isUsbAutoResumeEnabled() {
        try {
            return getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .getBoolean("usb_auto_resume", true);
        } catch (Throwable t) {
            return true;
        }
    }
 /** (Bug#72D):USB 开机自动播放开关——写 prefs + 实时同步 USB 控制器
 * (关闭时清 pending,防本次开机剩余窗口继续触发自动播放)。 */
    public void setUsbAutoResumeEnabled(boolean enabled) {
        try {
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .edit().putBoolean("usb_auto_resume", enabled).apply();
        } catch (Throwable ignored) {
        }
        if (usbSourceController != null) {
            usbSourceController.setAutoResumeEnabled(enabled);
        }
        Log.i(TAG, "setUsbAutoResumeEnabled: " + enabled);
    }
    public void setDimAutoPush(boolean v) {
        // 先写内存+prefs，让 isDimPushEnabled() 立即反映新状态
        // （isDimPushEnabled 读 prefs，不读内存字段，所以必须先写）
        dimAutoPush = v;
        try {
            getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                    .edit().putBoolean("dim_auto_push", v).apply();
        } catch (Throwable ignored) {
        }

        if (!v) {
            // 关闭推送：只停推送链路 + 推空内容清空仪表盘/小卡片。
            // ⚠ 修复（Bug#64）：不再 destroy EAS 连接。
            // destroy + 重开 re-init 在真车上不可靠：MediaCenterAPI 单例中
            // 旧 binder 的死亡监听未解除，重开后旧 binder 迟到死亡通知会清掉
            // 新注册状态；且 performRegistration 看到 mRegistered=true 会跳过
            // 重新注册 → 推送"假成功"（桥接层返回 true、EAS 层未送达）→
            // 仪表盘/小卡片重开后永远不再更新。
            // 开关只做推送门控（isDimPushEnabled 已覆盖所有推送入口），
            // EAS 连接与注册保持存活，重开时首推立即送达、无重建风险。
            try {
                stopPositionTicker();
                stopLyricPoll();
                // 推"无内容"状态：sourceType=-1 + 空 UUID → 小卡片进入"无客户端"清空态
                // （Bug#65；ROM 确认见 MediaCenterBridge.pushClearedToCard 注释）。
                // 旧实现 pushMetadata("", "") 会把 sourceType=6 + UUID="|"（非空）推上去，
                // 小卡片走"歌曲信息"分支只显示空字段（外壳残留"空内容但 UI 还在"）。
                boolean delivered = MediaCenterBridge.get().pushClearedToCard();
                Log.i(TAG, "setDimAutoPush(false): push cleared content, delivered=" + delivered);
                // 重置推送快照，避免重新开启时用旧数据去重
                lastPushedTitle = null;
                lastPushedArtist = null;
                lastPushedArtworkUri = null;
                lastPushedPlayState = -1;
                lastPushedLyricForTitle = null;
                firstPushPending = false;
                Log.i(TAG, "setDimAutoPush(false): cleared pushed, EAS connection kept alive");
            } catch (Throwable t) {
                Log.w(TAG, "setDimAutoPush(false) error: " + t.getMessage());
            }
            // Bug#67 self-kill to trigger EAS no-client cleanup:
            // binder death -> XSF "no client playing" -> DIM/widget truly cleared
            // (user verified force-stop clears DIM). Self-kill != force-stop: START_STICKY
            // foreground service auto-restarts; gate isDimPushEnabled()=false keeps DIM clear.
            // EAS设置新增"关闭EAS后Kill自身"开关（eas_kill_self，默认 true）。
            // 开启 → 关闭EAS推送后按原逻辑自 kill；关闭 → 仅清卡片+停推送，保留进程与 EAS 连接。
            boolean easKillSelf = true;
            try {
                easKillSelf = getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                        .getBoolean("eas_kill_self", true);
            } catch (Throwable ignored) {
            }
            if (easKillSelf) {
                scheduleSelfKillForNoClient();
            } else {
                Log.i(TAG, "setDimAutoPush(false): eas_kill_self off, process kept alive");
            }
        } else {
            // 重新开启：EAS 连接从未销毁，直接武装首推重试（firstPushPending 强制
            // 全量推一次、绕过去重），推送链路即刻恢复，无需异步重建。
            // 仅当连接确实已失效（启动时绑定失败 / 服务被系统回收等）才按需重建。
            try {
                cancelSelfKill(); // Bug#67: reopening cancels pending self-kill
                reenableStartupComponents(); // Bug#67(A): re-enable BootReceiver + NL anchors
                firstPushPending = true;
                firstPushRetryCount = 0;
                if (!MediaCenterBridge.get().isAlive()) {
                    MediaCenterBridge.get().ensureAlive(this);
                    Log.i(TAG, "setDimAutoPush(true): EAS not alive, rebuilding connection");
                }
                startFirstPushRetry();
                Log.i(TAG, "setDimAutoPush(true): firstPush retry armed, EAS connection alive");
            } catch (Throwable t) {
                Log.w(TAG, "setDimAutoPush(true) error: " + t.getMessage());
            }
        }
    }
 /**
 * Bug#67 -> Bug#67(A) : toggle OFF -> delayed 1s "self-disable":
 * arm a UI-reopen alarm, stopSelf() (kills START_STICKY auto-restart), then kill
 * the process. The EAS client binder really dies -> XSF "no client playing" ->
 * DIM/widget truly cleared.
 * User verified on vehicle: only force-stop / pm disable-user clears DIM; plain
 * self-kill failed because START_STICKY restarted the service in ~1s and the client
 * re-registered (card came back).
 *
 * 修订（不开主界面就不能方控）：不再禁用 BootReceiver / MediaNotificationListener。
 * 原实现把它们一起禁用是为了"关闭 EAS 客户端可能复活的所有通道"，但代价是：
 * 关推送后开机不自启、保活锚点全断 → 服务不活 → 方控（全部依赖服务 logcat 通道，
 * 且屏蔽三键时系统 MediaKeyReceiver 也被禁用）彻底失效，直到手动打开主界面才恢复。
 * 现在 EAS 客户端复活已被两处门控兜住，禁用锚点不再必要：
 * - CarMediaService.onCreate：dim_auto_push=false 时跳过 MediaCenterBridge.init
 * （Bug#67(A)，进程重启不会把卡片带回来）
 * - MediaNotificationListener.onCreate： 新增同款门控（NL 是唯一绕过
 * 主服务门控直接 init EAS 的路径）
 * 因此自 kill 只保留 stopSelf + kill（立即清 EAS），锚点始终启用：
 * 下次开机 BootReceiver 拉起服务 → 方控可用、DIM 因门控保持清空。
 * Reopening the switch re-enables the anchors + ensureAlive() re-registers the client.
 */
    private void scheduleSelfKillForNoClient() {
        try {
            cancelSelfKill();
            // Bug#67: mark UI reopen (with timestamp) so only the imminent START_STICKY
            // restart reopens the UI; late relaunches (keep-alive etc.) must NOT pop it.
            try {
                getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                        .edit().putLong("ui_reopen_at", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {
            }
            mSelfKillThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return; // reopened within window, cancel self-kill
                    }
                    if (isDimPushEnabled()) {
                        return; // double check: prefs is the authority
                    }
                    // Bug#67(A) + 修订：不再禁用 BootReceiver/NL 锚点
                    //（EAS 客户端复活已由两处 dim_auto_push 门控兜住，见方法头注释）。
                    // 锚点保持启用 → 下次开机/通知到达都能拉起服务 → 方控不依赖打开主界面。
                    scheduleUiReopenAlarm(); // process dies, onCreate-based reopen cannot run
                    Log.i(TAG, "setDimAutoPush(false): anchors kept enabled, stopping service, self kill");
                    stopSelf();
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException e) {
                        return; // reopened during stopSelf window
                    }
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, "carmedia-selfkill");
            mSelfKillThread.start();
        } catch (Throwable t) {
            Log.w(TAG, "scheduleSelfKillForNoClient error: " + t.getMessage());
        }
    }

    private void cancelSelfKill() {
        try {
            Thread t = mSelfKillThread;
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
            mSelfKillThread = null;
            // Bug#67: reopening cancels the pending UI reopen too
            try {
                getSharedPreferences("carmedia_prefs", MODE_PRIVATE)
                        .edit().remove("ui_reopen_at").apply();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

 /** EAS 推送配置变更：图标/封面模式、自定义图标/封面、kill-self 等设置变化后
 * 由 MainActivity 调用——重置去重快照并立即全量重推一次，让小卡片/仪表盘马上反映新配置。 */
    public void onEasPushConfigChanged() {
        try {
            firstPushHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        lastPushedTitle = null;
                        lastPushedArtist = null;
                        lastPushedArtworkUri = null;
                        lastPushedPlayState = -1;
                        lastPushedLyricForTitle = null;
                        firstPushPending = true;
                        if (isDimPushEnabled()) {
                            pushMetadataToCard();
                        }
                        Log.i(TAG, "onEasPushConfigChanged: snapshots reset, re-push armed");
                    } catch (Throwable t) {
                        Log.w(TAG, "onEasPushConfigChanged run error: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "onEasPushConfigChanged error: " + t.getMessage());
        }
    }


 /**
 * Bug#67(A) : disable/re-enable a startup anchor component.
 * Only BootReceiver (boot auto-start) and MediaNotificationListener (keep-alive)
 * are touched; MainActivity stays enabled so the user can always reopen from the
 * launcher. Self-owned component state changes need no CHANGE_COMPONENT_ENABLED_STATE
 * permission (uid matches package); DONT_KILL_APP keeps the running process untouched
 * (we self-kill anyway right after).
 */
    private void setComponentEnabled(Class<?> cls, boolean enabled) {
        try {
            ComponentName cn = new ComponentName(this, cls);
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            Log.i(TAG, "Bug#67(A): " + (enabled ? "enabled " : "disabled ") + cls.getSimpleName());
        } catch (Throwable t) {
            Log.w(TAG, "Bug#67(A): setComponentEnabled(" + cls.getSimpleName() + ") error: " + t.getMessage());
        }
    }

 /** Bug#67(A): reopening the toggle re-enables both startup/keep-alive anchors. */
    private void reenableStartupComponents() {
        setComponentEnabled(BootReceiver.class, true);
        setComponentEnabled(MediaNotificationListener.class, true);
    }

 /**
 * 修复"不开主界面就不能方控"：
 * 每次主界面绑定服务时调用，无条件恢复开机自启/保活锚点（BootReceiver + NL）。
 *
 * 背景：Bug#67 自 kill（关 EAS 推送 + eas_kill_self=true）会把这两个锚点禁用，
 * 且原实现只在"重新打开 EAS 推送"（setDimAutoPush(true)）时恢复；若用户打开
 * 主界面但没重开推送，锚点保持禁用 → 下次开机 CarMediaService 不自启。
 * 而方控（LogcatKeyMonitor→KeyMonitor→sendXxx）全部依赖服务存活，且
 * 屏蔽三键（key_blocked=true）时系统 MediaKeyReceiver 也被禁用——服务一死，
 * 方控三路全断，直到手动打开主界面 bind 拉起服务（用户实测现象）。
 * 本方法与"推送开关"解耦：UI 打开过即恢复锚点，下次开机自动恢复方控。
 * NL 的 EAS 初始化已由 dim_auto_push 门控（MediaNotificationListener.onCreate，
 * 修复），恢复 NL 不会在关闭推送时把仪表盘卡片带回来。
 */
    public void ensureStartupAnchorsEnabled() {
        try {
            reenableStartupComponents();
            Log.i(TAG, "ensureStartupAnchorsEnabled: BootReceiver + MediaNotificationListener re-enabled (UI opened)");
        } catch (Throwable t) {
            Log.w(TAG, "ensureStartupAnchorsEnabled error: " + t.getMessage());
        }
    }

 /**
 * Bug#67(A): the process self-kills, so the onCreate-based UI reopen (which relies on
 * the START_STICKY restart) never runs. Arm an AlarmManager to reopen the main UI ~3s
 * later from outside the process. The reopened MainActivity binds the service
 * (BIND_AUTO_CREATE); the service onCreate sees dim_auto_push=false and skips EAS
 * registration, so DIM stays clear until the user flips the toggle back on.
 */
    private void scheduleUiReopenAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0xE5A5, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000L, pi);
            Log.i(TAG, "Bug#67(A): UI reopen alarm armed (+3s)");
        } catch (Throwable t) {
            Log.w(TAG, "Bug#67(A): scheduleUiReopenAlarm error: " + t.getMessage());
        }
    }

    // ================= QQ 音乐歌词授权状态(P4) =================
 /** QQ 音乐是否已安装。 */
    public boolean isQqMusicInstalled() {
        return qqMusicFetcher != null && qqMusicFetcher.isQqMusicInstalled();
    }
 /** QQ 音乐授权状态码:0=未安装 1=已授权 2=未授权(root可写) 3=未授权(无root) 4=未授权(已绑定但接口失败)。 */
    public int getQqAuthStatus() {
        if (qqMusicFetcher == null) {
            return 0;
        }
        try {
            return qqMusicFetcher.getAuthStatus();
        } catch (Throwable t) {
            Log.w(TAG, "getQqAuthStatus error: " + t.getMessage());
            return 3;
        }
    }
 /** 拉起 QQ 音乐授权(仅用户手动点"一键授权"按钮触发):
 * 先探测 root(此时允许拉起 su 弹窗)--
 * 有 root → 直写授权文件 + 重启 QQ 进程重建缓存 + 绑定取歌词
 * 无 root → 回调 UI 提示"无 root 环境,登录授权暂不支持"
 * 探测在后台线程执行,避免 su 卡 UI 线程。 */
    public void authorizeQqMusic() {
        if (qqMusicFetcher == null) {
            return;
        }
        qqMusicFetcher.probeRoot(new QQMusicLyricFetcher.RootProbeCallback() {
            @Override
            public void onRootProbeResult(final boolean hasRoot) {
                Log.i(TAG, "authorizeQqMusic: hasRoot=" + hasRoot);
                if (!hasRoot) {
                    // 无 root:当前不支持免 root 登录授权,提示用户(不静默失败)
                        mAuthListener.onAuthMessage("无 root 环境,登录授权暂不支持");
                    if (mAuthListener != null) {
                        mAuthListener.onAuthMessage("无 root 环境,登录授权暂不支持");
                    }
                    return;
                }
                // 有 root:后台线程写授权 + 重启 QQ + 绑定取歌词
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            qqMusicFetcher.forceAuthWrite();
                            qqMusicFetcher.refreshAuthState();
                            // 关键:授权文件写入发生在 QQ 音乐服务启动之后时,进程内授权缓存
                            // (OpenIDPermissionCache)不会重新加载 → 必须先重启 QQ 音乐进程
                            // 重建缓存,否则 getCurrentSong 永远 code=5(No permission)。
                            qqMusicFetcher.restartQqProcess();
                            qqMusicFetcher.bind();
                            qqMusicFetcher.fetchCurrentSong();
                            Log.i(TAG, "authorizeQqMusic: root direct write done");
                        } catch (Throwable t2) {
                            Log.w(TAG, "authorizeQqMusic error: " + t2.getMessage());
                        }
                    }
                }, "qq-auth");
                t.start();
            }
        });
    }
}
