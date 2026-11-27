package com.ecarx.carmedia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * CarMedia 主界面（横屏，WebView 加载 assets/ui.html）。
 * 界面全部由 HTML/CSS/JS 渲染，Java 仅通过 CarMediaBridge 桥接后台服务，
 * 每秒推送一次状态（标题/歌词/进度/设置/按键/背景）。
 * 展示规则（与前台服务推送同一来源）：
 * 有歌词：主标题=歌词，副标题=歌名-歌手；无歌词：主标题=歌名，副标题=歌手。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarMedia";
    private static final String PREFS = "carmedia_prefs";
    private static final String PREF_BG_MODE = "bg_mode";
    private static final String PREF_BG_SRC = "bg_src";
    private static final String PREF_BG_CLEAN = "bg_clean";
    private static final String BG_DARK = "dark";
    // ---- EAS 推送外观设置----
    private static final String PREF_EAS_ICON_MODE = "eas_icon_mode";
    private static final String PREF_EAS_COVER_MODE = "eas_cover_mode";
    private static final String PREF_EAS_CUSTOM_ICON = "eas_custom_icon";
    private static final String PREF_EAS_CUSTOM_COVER = "eas_custom_cover";
    private static final String PREF_EAS_KILL_SELF = "eas_kill_self";
 /** (Bug#72D):USB 开机自动播放上次歌曲(设置项,默认开)。 */
    private static final String PREF_USB_AUTO_RESUME = "usb_auto_resume";
    private static final String PREF_EAS_COVER_FALLBACK = "eas_cover_fallback";
    private static final String PREF_EAS_FALLBACK_COVER = "eas_fallback_cover";
    private static final int REQ_PICK_EAS_ICON = 0xE510;
    private static final int REQ_PICK_EAS_COVER = 0xE511;
    private static final int REQ_PICK_EAS_FALLBACK = 0xE512;
 /** 在线更新失败兜底：拉起系统安装器（ACTION_VIEW），返回后弹文件管理器选择器定位更新包 */
    private static final int REQ_INSTALL_VIEW = 0xE520;
 /** 运行时申请 READ_EXTERNAL_STORAGE（去掉 sharedUserId 后 USB 扫描依赖它） */
    private static final int REQ_CODE_STORAGE = 0xE530;
    private static final String BG_CUSTOM = "custom";
    private static final String BG_LIGHT = "light";

    // ---- 版本 & 更新检测 ----
    private static final String APP_VERSION = "1.6.8";                       // 当前版本
 /** 在线更新清单地址（纯文本 JSON，无需网盘）；真实地址由构建注入 BuildConfig.UPDATE_URL */
    private static final String UPDATE_URL = BuildConfig.UPDATE_URL;
    private static boolean sCheckedInProcess = false; // 进程内只检测一次（冷启动才检测；Activity 重建不重复）
    // 最近一次检测到的远程版本/更新内容缓存（点击版本号直接弹窗用，不重新联网）
    private static volatile String sCachedRemoteVer = null;
    private static volatile String sCachedNotes = null;
    // 最近一次清单里的 APK 直链 / MD5（点“立即更新”用）
    private static volatile String sCachedRemoteUrl = null;
    private static volatile String sCachedApkMd5 = null;
    private String pendingUpdateDialog; // WebView 未就绪时缓存的弹窗 JS，onPageFinished 后补执行

    // ---- Service 绑定 ----
    private CarMediaService mService;
    private boolean mBound;
    private ServiceConnection mConnection;
 /** 静态实例引用（供 CarMediaService 子线程回调切主线程调 pushAllPublic）。 */
    public static volatile MainActivity sInstance;

 /** 当前 UI 主题皮肤 id（JS setSkin/boot 通过 onSetSkin 同步；""=默认）。
        CarMediaService 用它做"仅氛围歌词主题提前"的门控。 */
    public static volatile String uiSkin = "";

    // ---- WebView ----
    private WebView webView;
    private boolean webViewReady;

    // ---- 日志抓取（引擎在后台拉 logcat，实时推给 ui.html 面板；不新建 Activity 窗口） ----
    private final LogCaptureManager logManager = new LogCaptureManager();
    private final LogCaptureManager.Listener logListener = new LogCaptureManager.Listener() {
        @Override
        public void onLineBatch(String batch) {
            pushJsLog(batch);
        }

        @Override
        public void onStatus(String status) {
            pushJsLogStatus(status);
        }
    };

    // ---- 背景 ----
    private String bgMode = BG_DARK;

    // ---- 按键录制 ----
    private boolean recording;

    // ---- 轮询 ----
    private final Handler uiHandler = new Handler();
    private Runnable uiPollRunnable;

    // ---- 车机日夜（ECarXPowerManager，反射适配） ----
    private Object mDayNightMode;
    private Object mNightCbProxy;
    private boolean mNightRegistered;
    private boolean mNightState; // true=夜间 false=日间/未知

    private boolean isBgCleanEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_BG_CLEAN, false);
    }

 /** 初始化/重建 WebView（onCreate 与后台销毁后重建共用）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        webView.setBackgroundColor(0xFF080B13);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.addJavascriptInterface(new JsBridge(), "CarMediaBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && "content".equals(uri.getScheme())
                        && "com.ecarx.carmedia.artwork".equals(uri.getAuthority())) {
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        if (is != null) {
                            return new WebResourceResponse("image/png", "UTF-8", is);
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("CarMedia", "intercept artwork failed: " + uri + " - " + t.getMessage());
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                webViewReady = true;
                // ★ 页面(重新)加载完成必须强制一次全量推送：热更新 reload / 后台重建后，
                // metaSig 与上次相同会被轻量通道吞掉（只推歌词/进度），新页面的设置开关、
                // 背景、壁纸、EAS 等停留在 boot 默认状态（表现为"要重新开关一次才更新"）。
                forceFullPush = true;
                pushAll();
                // 补执行更新弹窗（若检测时 WebView 尚未加载完成）
                if (pendingUpdateDialog != null) {
                    String js = pendingUpdateDialog;
                    pendingUpdateDialog = null;
                    Log.i(TAG, "runJs: flush pending update dialog after page finished");
                    runJs(js);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                String msg = cm.message();
                if (msg != null && !msg.isEmpty()) {
                    if (msg.startsWith("VP:")) {
                        android.util.Log.w("CarMedia_VP", msg);
                    } else {
                        android.util.Log.w("CarMedia_JS", cm.sourceId() + ":" + cm.lineNumber() + " " + msg);
                    }
                }
                return true;
            }
        });
        setContentView(webView);
        // 加载页面前同步读取车机日夜状态，避免 onPageFinished 首次 pushAll 时 mNightState 仍为默认 false
        // （onRegisterNightCallback 是 runOnUiThread 异步执行，会晚于首次 pushAll，导致 auto 模式闪成日间）
        mNightState = readNightState();
        webView.loadUrl(UiHotUpdate.resolveUiUrl(MainActivity.this));
        hideSystemBars();
    }

 /** 销毁 WebView 释放内存（后台释放内存开关开启时，onStop 调用）。 */
    private void destroyWebView() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                android.view.ViewParent p = webView.getParent();
                if (p instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) p).removeView(webView);
                }
                webView.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "destroyWebView error: " + t.getMessage());
            }
            webView = null;
        }
        webViewReady = false;
    }

 /** 存储权限授予回调——授权后重扫一次 USB（去掉 system uid 后依赖此权限）。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE_STORAGE) {
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "READ_EXTERNAL_STORAGE granted=" + granted + " (USB 扫描依赖)");
            if (granted && mBound && mService != null) {
                mService.rescanUsb();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 后台释放内存开关开启时，onStop 销毁了 WebView，回到前台需重建
        if (webView == null) {
            initWebView();
            if (uiPollRunnable != null) {
                uiHandler.removeCallbacks(uiPollRunnable);
                uiHandler.postDelayed(uiPollRunnable, 1000);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台释放内存开关开启时，销毁 WebView 释放约50MB（前台服务继续运行）
        if (isBgCleanEnabled()) {
            uiHandler.removeCallbacks(uiPollRunnable);
            destroyWebView();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        requestWindowFeature(1);
        hideSystemBars();

        // 修复：去掉 sharedUserId=android.uid.system 后，READ_EXTERNAL_STORAGE 不再
        // 自动授予。USB 扫描（MediaStore 查询 + /storage FUSE 子目录递归）依赖它；不授予 →
        // MediaStore 被 Permission Denial、File 递归只扫到顶层 → 列表没歌 → 车机 UI 一直"等待音乐播放"。
        // 这里在启动时运行时申请，授权后由 onRequestPermissionsResult 触发一次 USB 重扫。
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{ android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_CODE_STORAGE);
        }

        initWebView();

        bgMode = readBgMode();

        // App 更新检查移到 UiHotUpdate.onResult 之后（见下方），避免热更新 reload 把刚弹出的更新窗冲掉

        // 展示上次更新安装结果（安装中进程被杀导致 toast 丢失时，这里兜底）
        showPendingUpdateResult();
        // 启动自检：恢复残留的 sys.jsbd.apk_verify 旁路开关（防全局验证长期被绕过）
        SilentInstaller.restorePropOnStart(this);

        // 后台检查 ui.html 热更新（GitHub 私有仓库，未配置时跳过，详见 UiHotUpdate）
        UiHotUpdate.checkAndUpdate(MainActivity.this, new UiHotUpdate.Callback() {
            @Override
            public void onResult(boolean updated, String msg) {
                if (updated) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView == null) return;
                            // 热更新 reload 前，如果公告弹窗正开着（首次安装场景），
                            // 先清除 localStorage 版本标记，确保 reload 后公告重新弹出，不会被热更新刷掉
                            webView.evaluateJavascript(
                                "if(document.getElementById('noticeMask').classList.contains('open')){" +
                                "localStorage.removeItem('carmedia_notice_ver');}",
                                new android.webkit.ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String value) {
                                        webView.loadUrl(UiHotUpdate.resolveUiUrl(MainActivity.this));
                                        webViewReady = false;
                                        Log.i(TAG, "hot update: ui.html refreshed");
                                    }
                                });
                        }
                    });
                }
                // 热更新结果出来后再检查 App 更新：若本次要 reload，reload 已发起、WebView 未就绪，
                // showUpdate 会进 pendingUpdateDialog 缓存，新页面 onPageFinished 补推；
                // 若不 reload，直接弹。避免热更新 reload 把刚弹出的 App 更新窗冲掉。
                checkForUpdateOnce();
            }
        });

        // 绑定后台服务
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, ">>> MainActivity: CarMediaService connected");
                mService = ((CarMediaService.LocalBinder) service).getService();
                mBound = true;
                // 授权流程消息（无 root 等）→ JS toast 提示
                mService.setAuthMessageListener(new CarMediaService.AuthMessageListener() {
                    @Override
                    public void onAuthMessage(final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showToast(msg);
                            }
                        });
                    }
                });
                // 每次打开主界面都恢复开机自启/保活锚点（BootReceiver + NL）。
                // 否则此前"关 EAS 推送自 kill"（Bug#67）禁用的锚点不会恢复 → 下次开机
                // CarMediaService 不自启 → 方控（全依赖服务 logcat 通道）不打开主界面就失效。
                mService.ensureStartupAnchorsEnabled();
                mService.refreshControllerList();
                pushAll();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mService = null;
                mBound = false;
            }
        };
        bindService(new Intent(this, CarMediaService.class), mConnection, Context.BIND_AUTO_CREATE);

        // 1s UI 状态轮询
        uiPollRunnable = new Runnable() {
            @Override
            public void run() {
                // 卡死修复：USB 激活且正在播放/扫描时，播放器进度轮询与扫描进度
                // 推送已驱动 UI 更新，这里跳过，避免双通道每秒各全量推送一次（车机 CPU 被吃满）。
                // USB 未播放且未扫描（暂停/停止/空闲）时仍需本轮询保持 UI 更新。
                if (mService != null && mService.isUsbRouteArmed()
                        && (mService.isUsbPlaying() || mService.isUsbScanning())) {
                    scheduleUiPoll(this);
                    return;
                }
                pushAll();
                scheduleUiPoll(this);
            }
        };
        uiHandler.postDelayed(uiPollRunnable, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sInstance = null;
        uiHandler.removeCallbacksAndMessages(null);
        unregisterNightCallback();
        destroyWebView();
        if (mBound && mConnection != null) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemBars();
        // 接管 uiMode：车机切日夜时不再销毁重建 Activity / WebView，仅把 night 位同步推给 ui.html 平滑换肤。
        // （ecarx 私有回调 onDayNightChanged 仍会独立触发，这里是双保险 + 消除首帧白闪）
        boolean night = (newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night != mNightState) {
            mNightState = night;
            runJs("CarMediaUI.onNightChanged(" + night + ");");
        }
    }

    @Override
    public void onBackPressed() {
        if (recording) {
            cancelRecording();
            return;
        }
        moveTaskToBack(true);
    }

    private void hideSystemBars() {
        // 只隐藏顶部状态栏（消除黑边），保留底部虚拟按键导航栏（需求）。
        // FLAG_FULLSCREEN + SYSTEM_UI_FLAG_FULLSCREEN 隐藏状态栏；
        // SYSTEM_UI_FLAG_IMMERSIVE_STICKY：用户下拉状态栏后自动缩回；
        // SYSTEM_UI_FLAG_LAYOUT_STABLE：布局稳定不重排；
        // 不含 HIDE_NAVIGATION → 底部导航栏保持显示。
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 重新获得焦点时再次隐藏状态栏（防止下拉通知栏/系统弹窗后黑边重新出现）
        if (hasFocus) {
            hideSystemBars();
        }
    }

    // ================= JS 桥 =================

    private void runJs(String js) {
        if (webView == null) {
            return;
        }
        // WebView 未就绪时，更新弹窗先缓存，onPageFinished 后补执行（否则弹窗丢失）
        if (!webViewReady && js.startsWith("CarMediaUI.showUpdate")) {
            pendingUpdateDialog = js;
            Log.i(TAG, "runJs: update dialog pending (webView not ready)");
            return;
        }
        // WebView.evaluateJavascript 必须在主线程调用；下载进度等后台线程回调
        // 若不切主线程会被 WebView 丢弃/抛异常，导致 updateDownloadProgress 等 JS 收不到
        final String code = js;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(code, null);
                } catch (Throwable t) {
                    Log.w(TAG, "runJs failed: " + t.getMessage());
                }
            }
        });
    }

    // 卡死防御：日志实证存在 30ms 级全量推送循环（getFullLyricLines 刷屏 + logd 90% CPU + 4 次 ANR 被杀）。
    // 每次 pushAll 都跑 Binder IPC/AdaptAPI 反射/evaluateJavascript，必须节流：
    // 同签名(歌名|歌手|状态|位置秒|歌词行|来源包名) 500ms 内重复调用直接跳过（循环只算轻量签名不推 JS）。
    private long lastPushAllMs;
    private String lastPushAllSig;
    private int rapidRepeatCount;
 /** pushAll 因 WebView 未就绪被跳过时的日志限流 */
    private long lastPushSkipLoggedMs;
    private long rapidRepeatLoggedMs;
    // 移除 metaSig 轻量通道后,相关字段(lastMetaSig/lastLyricLine/
    // lastLightPushMs/cachedCtrlSig 等)已删除。forceFullPush 保留——语义变为
    // "绕过 500ms 防抖"(异步歌词抓取完成/设置变更时立即全量推送)。
    private boolean forceFullPush;
    private final Runnable fullPushRunnable = new Runnable() {
        @Override
        public void run() {
            forceFullPush = true;
            pushAll();
        }
    };

 /** 请求一次强制全量推送(异步歌词抓取完成/歌词源切换后调用,200ms 内合并)。 */
    public void requestFullPush() {
        if (webView == null || !webViewReady) return;
        webView.removeCallbacks(fullPushRunnable);
        webView.postDelayed(fullPushRunnable, 200);
    }

 /** 轻量签名：只读内存 getter，用于高频重复调用判定。 */
    private String buildPushSig() {
        if (mService == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(safe(mService.getDisplayTitle())).append('|');
        sb.append(safe(mService.getDisplayArtist())).append('|');
        sb.append(mService.getPlaybackState()).append('|');
        sb.append(mService.getPlaybackPosition() / 1000L).append('|');
        sb.append(safe(mService.getUiCurrentLyricLine())).append('|');
        sb.append(safe(mService.getControllerPkg()));
        return sb.toString();
    }

    // buildMetaSig() 元数据签名判定已随轻量通道一并移除(详见 pushAll 注释)。
    // 移除原因:签名判定与轻量通道推送的数据源(getPlaybackPosition/歌词行)不同步,
    // 导致 Bug#71(控制器列表不刷新)/Bug#73(设置栏不刷新)/§29.8(旧源进度残留)/
    // §29.9(晴空主题冻结)/自动切歌进度条 100% 一整个 bug 家族。现 pushAll 恒全量
    // 推送(v1.4.5 行为),无签名短路,UI 每次拿到完整状态。

    private void pushAll() {
        if (!webViewReady || webView == null) {
            // 诊断：WebView 未就绪导致状态推送被吞（表现为 UI 只有 boot 初始渲染、
            // 版本号停留在占位）。限流打印，避免刷屏。
            long now = SystemClock.elapsedRealtime();
            if (now - lastPushSkipLoggedMs > 10000) {
                lastPushSkipLoggedMs = now;
                Log.w(TAG, "pushAll skipped: webViewReady=" + webViewReady
                        + " webView=" + (webView != null) + " (UI 只有 boot 渲染,版本号/状态不更新)");
            }
            return;
        }
        // 移除 metaSig 轻量通道——pushAll 恒走全量推送。
        // 背景:轻量通道(签名相同只推歌词行/进度)引入了一整个 bug 家族
        // (Bug#71 控制器列表不刷新 / Bug#73 设置栏不刷新 / §29.8 CarPlay 旧源进度残留 /
        // §29.9 晴空主题冻结 / 自动切歌新歌进度条满格 100%),根因都是"签名判定覆盖不全"
        // 与"轻量通道推送的数据源(getPlaybackPosition/歌词行)不同步"。
        // v1.4.5(用户确认"没问题"的版本)即无此机制、每次全量推送;§22 的性能修复
        // (三路推送合并 / 挂载路径与容量缓存 / JS 歌词按键 __key 增量 / 列表分块渲染)
        // 均在其之前完成,每秒全量推送开销已被吸收。
        // forceFullPush 保留:语义变为"绕过下方 500ms 防抖"(异步歌词就绪/设置变更立即推)。
        boolean forceFull = forceFullPush;
        forceFullPush = false;
        // 节流 + 高频诊断（内容未变且 500ms 内重复 → 跳过全量推送）
        try {
            String nowSig = buildPushSig();
            long nowMs = SystemClock.elapsedRealtime();
            if (!forceFull && nowMs - lastPushAllMs < 500 && nowSig != null && nowSig.equals(lastPushAllSig)) {
                // 诊断钩子：200ms 内出现 3 次以上相同签名 → 打一次调用堆栈定位循环源
                if (nowMs - lastPushAllMs < 200) {
                    rapidRepeatCount++;
                    if (rapidRepeatCount >= 3 && nowMs - rapidRepeatLoggedMs > 10000) {
                        rapidRepeatLoggedMs = nowMs;
                        rapidRepeatCount = 0;
                        Log.w(TAG, "pushAll rapid-repeat (30ms-loop suspect): " + nowSig + "\n"
                                + Log.getStackTraceString(new Throwable()));
                    }
                } else {
                    rapidRepeatCount = 0;
                }
                return;
            }
            lastPushAllMs = nowMs;
            lastPushAllSig = nowSig;
            rapidRepeatCount = 0;
        } catch (Throwable ignored) {
        }
        try {
            JSONObject o = new JSONObject();
            o.put("title", safe(mService == null ? null : mService.getDisplayTitle()));
            o.put("artist", safe(mService == null ? null : mService.getDisplayArtist()));
            o.put("lyric", safe(mService == null ? null : mService.getUiCurrentLyricLine()));
            o.put("nextLyric", safe(mService == null ? null : mService.getUiNextLyricLine()));
            o.put("state", mService == null ? 0 : mService.getPlaybackState());
            o.put("pos", mService == null ? 0 : mService.getPlaybackPosition());
            o.put("dur", mService == null ? 0 : mService.getPlaybackDuration());
            String pkg = mService == null ? "" : safe(mService.getControllerPkg());
            // CarPlay 覆盖生效条件——手动选中 CarPlay 且盒子会话尚未成为当前控制器。
            // 此状态下车机收不到盒子元数据,旧源(网易云/汽水)的一切痕迹
            // (歌名/状态/封面/歌词/接口状态)都要清掉,见下方各处 cpOverride 分支。
            // 合并模式(合并且盒子会话存在)不覆盖——盒子 MediaSession
            // 携带真实歌曲信息,getDisplayTitle/Artist 从盒子会话取,正常显示。
            boolean cpOverride = mService != null && mService.isCarplayBoxSelected()
                    && !mService.isCarplayBoxMergedActive()
                    && !CarMediaService.PKG_CARPLAY_BOX.equals(pkg);
            if (mService != null && mService.isCarplayBoxSelected()) {
                // 手动选中 CarPlay → 顶部控制器芯片显示 "CarPlay" 而非 "未连接"
                o.put("pkg", CarMediaService.PKG_CARPLAY_BOX);
                o.put("pkgName", "CarPlay");
                // CarPlay 选中时 controllerPkg 仍停在旧源(如网易云/汽水),
                // 若不覆盖,歌曲信息/播放状态会一直显示旧源(切到 CarPlay
                // 歌名和播放态仍是网易云/汽水)。CarPlay 是纯媒体键注入、无法监听
                // 音乐(盒子不提供会话/元数据),因此歌名区显示固定来源说明
                // "CarPlay 手机音乐"(不再显示"等待音乐播放..."——那暗示未来会有信息)。
                // 只有盒子会话已成为当前控制器(pkg==CarPlay,盒子真实元数据会正常
                // 推送)时才保留标题/状态。
                if (cpOverride) {
                    o.put("title", "CarPlay 手机音乐");
                    o.put("artist", "");
                    o.put("lyric", "");
                    o.put("nextLyric", "");
                    o.put("state", 0);
                    o.put("pos", 0);
                    o.put("dur", 0);
                    o.put("lyricBlocked", false);
                }
            } else {
                o.put("pkg", pkg);
                String disp = pkg.isEmpty() ? "" : getAppDisplayName(pkg);
                // 需求:USB 激活时默认主题 ctrl-chip 显示中文名 "USB音乐",
                // 不露虚拟包名 com.ecarx.carmedia.usb(getAppDisplayName 对无 app 的
                // 虚拟包名会回退返回包名本身)。
                if (UsbSourceController.SOURCE_PKG_USB.equals(pkg)) {
                    disp = "USB音乐";
                }
                o.put("pkgName", disp);
            }
            // 当前控制器是否被屏蔽歌词（供主界面 chip 徽章 + 设置卡开关显示）
            // CarPlay 选中且无盒子会话时,按 CarPlay 包名判定(不沿用旧源的徽章)
            String lyricBlockedPkg = (mService != null && mService.isCarplayBoxSelected())
                    ? CarMediaService.PKG_CARPLAY_BOX : pkg;
            o.put("lyricBlocked", mService != null && lyricBlockedPkg != null && !lyricBlockedPkg.isEmpty()
                    && mService.isLyricBlocked(lyricBlockedPkg));
            o.put("clock", clockNow());
            o.put("bg", bgMode);
            o.put("customBg", customBgPath());
            o.put("dim", mService != null && mService.isDimAutoPush());
            o.put("keyBlock", mService != null && mService.isKeyBlockedSetting());
            o.put("bgClean", isBgCleanEnabled());
            o.put("wheel", mService != null && mService.isWheelKeysBlocked());
            // EAS 推送外观设置：图标/封面模式 + kill-self 开关 + 自定义图 URI
            o.put("easIconMode", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_ICON_MODE, "source"));
            o.put("easCoverMode", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_COVER_MODE, "source"));
            o.put("easKillSelf", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_EAS_KILL_SELF, true));
            // (Bug#72D)+ :USB/蓝牙开机自动播放开关(默认开;蓝牙经 isBluetoothSelectable 门控)
            o.put("usbAutoResume", getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_USB_AUTO_RESUME, true));
            o.put("easCustomIcon", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_CUSTOM_ICON, ""));
            o.put("easCustomCover", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_CUSTOM_COVER, ""));
            // 封面兜底:音源无封面时 empty=推空封面 / fixed=推固定图片
            o.put("easCoverFallback", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_COVER_FALLBACK, "empty"));
            o.put("easFallbackCover", getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_EAS_FALLBACK_COVER, ""));
            JSONObject keys = new JSONObject();
            if (mService != null) {
                Map<String, String> actions = mService.getKeyActions();
                if (actions != null) {
                    for (Map.Entry<String, String> e : actions.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            keys.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            o.put("keys", keys);
            o.put("version", APP_VERSION);
            // 歌词 API 状态（供 UI 顶部状态灯显示：{api: {status, msg}}）
            try {
                // CarPlay 覆盖时推空状态——旧源的歌词接口状态(如"网易云歌词·成功")
                // 不再残留,晴空/默认主题状态灯全部回到空闲(黄)
                o.put("apiStatus", cpOverride ? new JSONObject()
                        : (mService == null ? new JSONObject() : mService.getApiStatus()));
            } catch (Throwable t) {
                Log.w(TAG, "pushAll apiStatus failed: " + t.getMessage());
            }
            // QQ 音乐歌词授权状态：installed + status(0未装/1已授权/2未授权root可写/3未授权无root/4绑定失败)
            try {
                JSONObject qqAuth = new JSONObject();
                boolean installed = mService != null && mService.isQqMusicInstalled();
                qqAuth.put("installed", installed);
                qqAuth.put("status", installed ? mService.getQqAuthStatus() : 0);
                o.put("qqAuth", qqAuth);
            } catch (Throwable t) {
                Log.w(TAG, "pushAll qqAuth failed: " + t.getMessage());
            }
            // 控制器列表（供 JS 弹层选择，替代原生 AlertDialog）
            JSONArray ctlArr = new JSONArray();
            // 需求:U 盘挂载时 "USB音乐" 常驻控制器列表顶部(所有主题可见),
            // 无需切晴空主题。显示中文名,不露虚拟包名 com.ecarx.carmedia.usb;
            // current 跟随 USB 音源是否已武装(选中态高亮)。
            try {
                if (mService != null && mService.isUsbMounted()) {
                    JSONObject usb = new JSONObject();
                    usb.put("pkg", UsbSourceController.SOURCE_PKG_USB);
                    usb.put("name", "USB音乐");
                    usb.put("sub", "本地音乐");
                    usb.put("current", mService.isUsbRouteArmed());
                    usb.put("ghost", false);
                    usb.put("lyricBlocked", false);
                    ctlArr.put(usb);
                }
            } catch (Throwable ignored) { }
            if (mService != null) {
                List<MediaController> ctlList = mService.getAvailableControllers();
                if (ctlList != null) {
                    String curPkg = mService.getControllerPkg();
                    for (MediaController mc : ctlList) {
                        if (mc == null || mc.getPackageName() == null) continue;
                        try {
                            JSONObject c = new JSONObject();
                            String cp = mc.getPackageName();
                            c.put("pkg", cp);
                            String cn = getAppDisplayName(cp);
                            // 盒子真实包名条目——显示友好名(不露真实包名字符串),
                            // merged 标记供 UI 显示"信息源"徽章(点击 = 与 CarPlay 合并/取消合并)
                            if (CarMediaService.isCarplayBoxRealPkg(cp)) {
                                boolean labelOk = cn != null && !cn.isEmpty() && !cn.equals(cp);
                                c.put("name", labelOk ? cn : "CarPlay 盒子");
                                c.put("sub", "信息源");
                                c.put("merged", mService.isCarplayBoxMerged(cp));
                            } else {
                                c.put("name", (cn != null && !cn.isEmpty() && !cn.equals(cp)) ? cn : cp);
                            }
                            // 手动选中 CarPlay 盒子时,其他音源不高亮
                            c.put("current", cp.equals(curPkg) && !mService.isCarplayBoxSelected());
                            c.put("lyricBlocked", mService.isLyricBlocked(cp));
                            c.put("ghost", false);
                            ctlArr.put(c);
                        } catch (Throwable ignored) { }
                    }
                    // 合并 ghost 控制器:dumpsys 里 active=false 的暂停会话(如暂停的酷我车机版),
                    // API 28 无法构造 MediaController,只能展示包名,用户点击时用 am start 拉起。
                    // 路径B:CarPlay 盒子(E-Box)也走这里常驻显示(Service 侧在
                    // getGhostControllerPkgs 中追加),显示名固定为 "CarPlay",选中态跟随盒子激活状态。
                    List<String> ghostPkgs = mService.getGhostControllerPkgs();
                    for (String gp : ghostPkgs) {
                        try {
                            JSONObject c = new JSONObject();
                            c.put("pkg", gp);
                            if (CarMediaService.PKG_CARPLAY_BOX.equals(gp)) {
                                c.put("name", "CarPlay");
                                // CarPlay 项高亮 = 用户手动选中(与盒子连接状态无关)
                                c.put("current", mService.isCarplayBoxSelected());
                                // 隐藏包名小字:显示 "CarPlay 盒子" 代替虚拟包名 com.ecarx.carmedia.carplay
                                c.put("sub", "CarPlay 盒子");
                            } else {
                                String cn = getAppDisplayName(gp);
                                // 盒子真实包名 ghost 条目(active=false 时走此通道)
                                // ——友好名 + "信息源"小字 + merged 标记,与活跃会话条目一致
                                if (CarMediaService.isCarplayBoxRealPkg(gp)) {
                                    boolean labelOk = cn != null && !cn.isEmpty() && !cn.equals(gp);
                                    c.put("name", labelOk ? cn : "CarPlay 盒子");
                                    c.put("sub", "信息源");
                                    c.put("merged", mService.isCarplayBoxMerged(gp));
                                } else {
                                    c.put("name", (cn != null && !cn.isEmpty() && !cn.equals(gp)) ? cn : gp);
                                }
                                c.put("current", gp.equals(curPkg));
                            }
                            c.put("lyricBlocked", mService.isLyricBlocked(gp));
                            c.put("ghost", true);
                            ctlArr.put(c);
                        } catch (Throwable ignored) { }
                    }
                }
            }
            o.put("controllers", ctlArr);
            // USB 音源状态
            try {
                if (mService != null) {
                    o.put("usbSourceActive", mService.isUsbRouteArmed());
                    org.json.JSONObject usbState = new org.json.JSONObject(mService.getUsbStateJson());
                    o.put("usbState", usbState);
                } else {
                    o.put("usbSourceActive", false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "pushAll usbState failed: " + t.getMessage());
            }
            // 车机日夜状态（ECarXPowerManager）：true=夜 false=日（未取到则 false，JS 回退 matchMedia）
            o.put("night", mNightState);
            // 可用壁纸列表（供 JS 弹层选择，替代原生 AlertDialog）
            JSONArray wpArr = new JSONArray();
            for (String w : scanWallpapers()) {
                wpArr.put(w);
            }
            o.put("wallpapers", wpArr);
            // 晴空主题：全量歌词 + 当前行索引 + 封面（右侧完整歌词列表与封面背景）
            try {
                // CarPlay 覆盖(cpOverride)时清空全量歌词/封面,
                // 否则晴空主题右侧歌词列表与封面会残留旧源(网易云/汽水)内容
                java.util.List<String> fl = cpOverride ? null
                        : (mService == null ? null : mService.getFullLyricLines());
                JSONArray fla = new JSONArray();
                if (fl != null) { for (String s : fl) fla.put(s); }
                o.put("lyrics", fla);
                o.put("lyricIndex", cpOverride ? -1
                        : (mService == null ? -1 : mService.getCurrentLyricIndex()));
                o.put("artwork", cpOverride ? null
                        : (mService == null ? null : mService.getCurrentArtworkUri()));
            } catch (Throwable t) {
                Log.w(TAG, "pushAll fullLyric/artwork failed: " + t.getMessage());
            }
            runJs("CarMediaUI.updateAll(" + o.toString() + ");");
            Log.i(TAG, "pushAll full push done (version=" + APP_VERSION + ")");
        } catch (Throwable t) {
            Log.w(TAG, "pushAll failed: " + t.getMessage());
        }
    }

 /** public 包装：供 CarMediaService 在 USB 状态变化时从子线程切主线程调用。 */
    public void pushAllPublic() {
        pushAll();
    }

 /**
 * 歌词行轻量即时推送：歌词行变化时立即更新主界面歌词，不等每秒
 * 全量 pushAll。背景：EAS 走 500ms pollLyricOnce、UI 全量走 1s pollUiState
 * （叠加 500ms 防抖），UI 歌词常比 EAS 慢 0.5~1s。本方法与全量推送同源
 * （getBoundLyricLine/getCurrentLyricIndex），只作歌词滚动加速，不替代每秒
 * 全量推送（全量继续负责歌名/进度/控制器/设置等全部字段刷新），故不会引入
 * 移除 metaSig 轻量通道时的那类签名覆盖不全 bug。
 */
    public void pushLyricLineLight() {
        if (!webViewReady || webView == null || mService == null) return;
        try {
            String title = safe(mService.getDisplayTitle());
            String artist = safe(mService.getDisplayArtist());
            String lyric = safe(mService.getUiCurrentLyricLine());
            String nextLyric = safe(mService.getUiNextLyricLine());
            int idx = mService.getCurrentLyricIndex();
            String js = "CarMediaUI.updateLyricLine("
                    + JSONObject.quote(title == null ? "" : title) + ","
                    + JSONObject.quote(artist == null ? "" : artist) + ","
                    + JSONObject.quote(lyric == null ? "" : lyric) + ","
                    + JSONObject.quote(nextLyric == null ? "" : nextLyric) + ","
                    + idx + ");";
            runJs(js);
        } catch (Throwable t) {
            Log.w(TAG, "pushLyricLineLight failed: " + t.getMessage());
        }
    }

    private String customBgPath() {
        try {
            File f = new File(getFilesDir(), "custom_bg.jpg");
            if (f.exists()) {
                String src = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_BG_SRC, "");
                String base = Uri.fromFile(f).toString();
                if (!src.isEmpty()) {
                    // 附加来源文件名供 JS 识别亮色壁纸：file:///...custom_bg.jpg?src=sunrise.png
                    return base + "?src=" + src;
                }
                return base;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void setBgSrc(String src) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_BG_SRC, src == null ? "" : src).apply();
        } catch (Throwable ignored) {
        }
    }
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private String clockNow() {
        try {
            return new java.text.SimpleDateFormat("HH:mm MM/dd", java.util.Locale.getDefault())
                    .format(new java.util.Date());
        } catch (Throwable t) {
            return "--:--";
        }
    }

    // ---- 日志抓取：把引擎输出推给 ui.html 面板（logListener 回调，已在主线程） ----
    private void pushJsLog(String batch) {
        try {
            if (webView != null && webViewReady) {
                webView.evaluateJavascript(
                        "window._onLogLine && _onLogLine(" + org.json.JSONObject.quote(batch) + ");", null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pushJsLog error: " + t.getMessage());
        }
    }

    private void pushJsLogStatus(String status) {
        try {
            if (webView != null && webViewReady) {
                webView.evaluateJavascript(
                        "window._onLogStatus && _onLogStatus(" + org.json.JSONObject.quote(status) + ");", null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pushJsLogStatus error: " + t.getMessage());
        }
    }

 /** JS 调用的桥（所有回调切回主线程）。 */
    private class JsBridge {        /** 供 UI boot() 主动拉取应用版本号：不依赖每秒 pushAll（热更新/后台重建后立即显示真实版本） */
        @JavascriptInterface
        public String getAppVersion() {
            return APP_VERSION;
        }

 /** 供 UI 同步当前主题皮肤（setSkin 切换 + boot 启动各调一次）。
            服务端用它门控"仅氛围歌词(skin-neon)主题歌词提前"；皮肤状态只在 WebView 侧，Java 默认不知道。 */
        @JavascriptInterface
        public void onSetSkin(String id) {
            MainActivity.uiSkin = (id == null) ? "" : id;
        }

 /** 供 UI boot() 主动请求一次全量状态（设置开关/背景/壁纸/EAS 等），页面加载后兜底刷新 */
        @JavascriptInterface
        public void requestFullState() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    forceFullPush = true;
                    pushAll();
                }
            });
        }

 /** 供 UI 开始抓日志：mode = "full" | "widget" | "keymap"。后台 logcat 实时推给页面。 */
        @JavascriptInterface
        public void startLogCapture(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    LogCaptureManager.Mode m = LogCaptureManager.Mode.WIDGET;
                    try {
                        if ("full".equalsIgnoreCase(mode)) {
                            m = LogCaptureManager.Mode.FULL;
                        } else if ("keymap".equalsIgnoreCase(mode)) {
                            m = LogCaptureManager.Mode.KEYMAP;
                        }
                    } catch (Throwable ignored) {
                    }
                    logManager.start(MainActivity.this, m, logListener);
                }
            });
        }

 /** 供 UI 停止抓日志。 */
        @JavascriptInterface
        public void stopLogCapture() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    logManager.stop();
                }
            });
        }

 /** 供 UI 清空日志缓冲与临时文件。 */
        @JavascriptInterface
        public void clearLogCapture() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    logManager.clear(MainActivity.this);
                }
            });
        }

 /** 供 UI 导出日志到 /sdcard/Download，返回导出文件绝对路径（空串=失败）。 */
        @JavascriptInterface
        public String exportLogCapture() {
            try {
                java.io.File f = logManager.export(MainActivity.this);
                return (f == null) ? "" : f.getAbsolutePath();
            } catch (Throwable t) {
                Log.w(TAG, "exportLogCapture failed: " + t.getMessage());
                return "";
            }
        }

 /** 供 UI 查询当前是否正在抓取。 */
        @JavascriptInterface
        public boolean isLogCapturing() {
            return logManager.isRunning();
        }

 /** 供 UI 自建文件管理器选择弹窗：返回候选列表 [{pkg,label},...] */
        @JavascriptInterface
        public String getFileManagers() {
            try {
                List<String[]> fms = collectFileManagers(MainActivity.this);
                JSONArray arr = new JSONArray();
                for (String[] fm : fms) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("pkg", fm[0]);
                        o.put("label", fm[1]);
                        arr.put(o);
                    } catch (Throwable ignored) {}
                }
                return arr.toString();
            } catch (Throwable t) {
                Log.w(TAG, "getFileManagers failed: " + t.getMessage());
                return "[]";
            }
        }

 /** 供 UI 自建文件管理器选择弹窗：启动所选文件管理器主界面 */
        @JavascriptInterface
        public void openFileManager(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (pkg != null && !pkg.isEmpty()) {
                        launchFileManagerByPkg(MainActivity.this, pkg);
                    }
                }
            });
        }

 /** 供 UI 打开系统桌面「添加/编辑小组件」界面（ecarx.launcher3 的 WidgetEditActivity，原厂桌面无添加入口时用） */
        @JavascriptInterface
        public void openDesktopWidgetEdit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    launchDesktopWidgetEdit(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void onToggleDim() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.setDimAutoPush(!mService.isDimAutoPush());
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    }
                }
            });
        }

        @JavascriptInterface
        public void onToggleKeyBlock() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.setWheelKeysBlocked(!mService.isKeyBlockedSetting());
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    }
                }
            });
        }

        @JavascriptInterface
        public void onToggleBgClean() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean cur = isBgCleanEnabled();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_BG_CLEAN, !cur).apply();
                    Log.i(TAG, "bgClean toggled: " + !cur);
                    // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                    requestFullPush();
                }
            });
        }

        @JavascriptInterface
        public void onSetEasIconMode(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String m = ("none".equals(mode) || "custom".equals(mode)) ? mode : "source";
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_EAS_ICON_MODE, m).apply();
                        if (mService != null) {
                            mService.onEasPushConfigChanged();
                        }
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    } catch (Throwable t) {
                        Log.w(TAG, "onSetEasIconMode error: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onSetEasCoverMode(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String m = ("none".equals(mode) || "custom".equals(mode)) ? mode : "source";
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_EAS_COVER_MODE, m).apply();
                        if (mService != null) {
                            mService.onEasPushConfigChanged();
                        }
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    } catch (Throwable t) {
                        Log.w(TAG, "onSetEasCoverMode error: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onToggleEasKillSelf() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean cur = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_EAS_KILL_SELF, true);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_EAS_KILL_SELF, !cur).apply();
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    } catch (Throwable t) {
                        Log.w(TAG, "onToggleEasKillSelf error: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onToggleUsbAutoResume() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean cur = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_USB_AUTO_RESUME, true);
                        boolean next = !cur;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_USB_AUTO_RESUME, next).apply();
                        // (Bug#72D):同步到服务(实时清 pending),服务也会写同一 prefs,幂等
                        if (mService != null) {
                            mService.setUsbAutoResumeEnabled(next);
                        }
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    } catch (Throwable t) {
                        Log.w(TAG, "onToggleUsbAutoResume error: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onPickEasCustom(final String kind) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pickEasCustomImage(kind);
                }
            });
        }

        @JavascriptInterface
        public void onSetEasCoverFallback(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String m = ("fixed".equals(mode)) ? "fixed" : "empty";
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_EAS_COVER_FALLBACK, m).apply();
                        if (mService != null) {
                            mService.onEasPushConfigChanged();
                        }
                        // 设置变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                        requestFullPush();
                    } catch (Throwable t) {
                        Log.w(TAG, "onSetEasCoverFallback error: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onPickEasFallback() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pickEasCustomImage("fallback");
                }
            });
        }

        @JavascriptInterface
        public void onMediaAction(final String action) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.sendMediaAction(action);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onBrowseModeChange(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (BG_CUSTOM.equals(mode)) {
                        pickCustomWallpaper();
                        return;
                    }
                    bgMode = mode;
                    saveBgMode(mode);
                    // 背景模式变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                    requestFullPush();
                }
            });
        }

        @JavascriptInterface
        public void onPickWallpaper(final String file) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (file != null && !file.isEmpty()) {
                        // 直接应用 assets/wallpapers/<file>（亮色壁纸），bg 用 light 以启用浅色适配
                        try {
                            copyAssetToFiles(file);
                            setBgSrc(file);
                            bgMode = BG_LIGHT;
                            saveBgMode(BG_LIGHT);
                            // 背景变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                            requestFullPush();
                        } catch (Throwable t) {
                            Log.w(TAG, "apply light wallpaper failed: " + t.getMessage());
                        }
                        return;
                    }
                    // 空 file → 推送壁纸列表并打开 CSS 弹层（替代原生 AlertDialog）
                    pushAll();
                    runJs("CarMediaUI.showWallpaperSheet();");
                }
            });
        }

        @JavascriptInterface
        public void onSetKeyAction(final String action, final int code) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        // 兼容旧调用：未指定 tap 视为单击
                        mService.setKeyAction(code, 1, action);
                    }
                    // 按键映射变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                    requestFullPush();
                }
            });
        }

        @JavascriptInterface
        public void onRemoveKeyAction(final int code, final int tap) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.removeKeyAction(code, tap);
                    }
                    // 按键映射变化强制全量推送(轻量通道下 pushAll 会被吞,见 settingsSig)
                    requestFullPush();
                }
            });
        }

        @JavascriptInterface
        public void onRecordKey(final String action, final int tap) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startRecording(action, tap);
                }
            });
        }

        @JavascriptInterface
        public void onCancelRecord() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelRecording();
                }
            });
        }

 /**
 * 请求可打开 App 列表（新增，用于按键映射"打开App"选择器）。
 * 返回 JSON 数组：[{"pkg":"com.android.settings","name":"设置"}, ...]
 * 包含所有有主界面（LAUNCHER）的 App，含系统 App。
 */
        @JavascriptInterface
        public String onRequestAppList() {
            if (mService == null) {
                return "[]";
            }
            try {
                java.util.List<CarMediaService.AppInfo> apps = mService.getLaunchableApps();
                org.json.JSONArray arr = new org.json.JSONArray();
                for (CarMediaService.AppInfo app : apps) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("pkg", app.packageName);
                    obj.put("name", app.appName);
                    if (app.iconUri != null) obj.put("icon", app.iconUri);
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Throwable t) {
                android.util.Log.e("CarMedia", "onRequestAppList failed: " + t.getMessage());
                return "[]";
            }
        }

 /** 测试打开指定 App（供 UI App 选择器"试开"按钮用，新增） */
        @JavascriptInterface
        public void onLaunchApp(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.launchAppByPackage(pkg);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onToggleLyricBlock(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null && pkg != null && !pkg.isEmpty()) {
                        mService.setLyricBlocked(pkg, !mService.isLyricBlocked(pkg));
                    }
                    pushAll();
                }
            });
        }

        @JavascriptInterface
        public void onSelectController() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // 先强制刷新控制器列表（原原生对话框先 refreshControllerList 再取列表），
                    // 再推送最新列表，最后打开 CSS 弹层（替代原生 AlertDialog）
                    if (mService != null) {
                        mService.refreshControllerList();
                    }
                    pushAll();
                    runJs("CarMediaUI.showControllerSheet();");
                }
            });
        }

        @JavascriptInterface
        public void onSelectControllerIndex(final int index) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    confirmControllerSelection(index);
                    // 用户交互一律强制全量推送,不依赖 metaSig 猜状态
                    // (轻量通道只服务后台高频变化:歌词滚动/进度)
                    forceFullPush = true;
                    pushAll();
                }
            });
        }

 /**
 * 按包名切换控制器(替代 index 方案)。
 * 原 index 方案存在错位:JS 渲染列表时用的 availableControllers/ghost 列表
 * (ghost 还有 10s 缓存)与点击时 Java 侧实时列表不一致,导致点 CarPlay 落空。
 * 改为直接传包名匹配,彻底消除顺序/数量依赖。
 */
        @JavascriptInterface
        public void onSelectControllerPkg(final String pkg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    confirmControllerSelectionByPkg(pkg);
                    forceFullPush = true;
                    pushAll();
                }
            });
        }

        @JavascriptInterface
        public void onExit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    moveTaskToBack(true);
                }
            });
        }

        @JavascriptInterface
        public void onRegisterNightCallback() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    registerNightCallback();
                }
            });
        }

 /* 立即更新（弹窗按钮）：有 APK 直链则后台下载到缓存目录，完成后发起安装；无直链回退打开更新页 */
        @JavascriptInterface
        public void onOpenUpdate() {
            final String apkUrl = sCachedRemoteUrl;
            if (apkUrl == null || apkUrl.isEmpty()) {
                // 无直链（老清单格式）→ 回退打开更新页
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(UPDATE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable t) {
                            Log.w(TAG, "open update url failed: " + t.getMessage());
                        }
                    }
                });
                return;
            }
            showToast("正在下载更新包...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] data = httpGetBytes(apkUrl, new ProgressCallback() {
                            @Override
                            public void onProgress(final int percent) {
                                // 进度推送到 JS（runJs 内部处理主线程）
                                runJs("CarMediaUI.updateDownloadProgress(" + percent + ");");
                            }
                        });
                        if (data == null || data.length < 10000) {
                            runJs("CarMediaUI.updateDownloadFailed();");
                            showToast("下载失败，请重试");
                            return;
                        }
                        // 校验 MD5（清单提供了才校验）
                        final String md5 = sCachedApkMd5;
                        if (md5 != null && !md5.isEmpty()) {
                            String got = md5Hex(data);
                            if (!md5.equalsIgnoreCase(got)) {
                                Log.w(TAG, "apk md5 mismatch: expect=" + md5 + " got=" + got);
                                runJs("CarMediaUI.updateDownloadFailed();");
                                showToast("更新包校验失败");
                                return;
                            }
                        }
                        Log.i(TAG, "apk downloaded bytes=" + data.length);
                        runJs("CarMediaUI.updateDownloadDone();");
                        installApkBytes(data);
                    } catch (Throwable t) {
                        Log.w(TAG, "download apk failed: " + t.getMessage());
                        runJs("CarMediaUI.updateDownloadFailed();");
                        showToast("下载失败，请重试");
                    }
                }
            }, "update-download").start();
        }

 /* 手动检测更新（按钮触发）：绕过 24h 间隔立即检查 */
        @JavascriptInterface
        public void onCheckUpdate() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    checkForUpdate(true);
                }
            }, "update-check-manual").start();
        }

 /* 点击版本号：直接弹窗展示最新版本+更新内容（不做是否最新的判断） */
        @JavascriptInterface
        public void onShowUpdateInfo() {
            final String ver = sCachedRemoteVer;
            final String notes = sCachedNotes;
            if (ver != null) {
                // 有缓存（冷启动检测时已抓到）→ 秒弹，不比较版本
                showUpdateViewOnly(ver, notes);
                return;
            }
            // 无缓存 → 后台抓一次服务器清单，拿到就弹
            showToast("正在获取更新信息...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String page = httpGet(UPDATE_URL, null);
                        if (page == null || page.isEmpty()) {
                            showToast("获取更新信息失败，请检查网络");
                            return;
                        }
                        String[] v = parseRemoteManifest(page);
                        if (v == null) {
                            showToast("获取更新信息失败，请重试");
                            return;
                        }
                        sCachedRemoteVer = v[0];
                        sCachedNotes = v[1];
                        sCachedRemoteUrl = v[2];
                        sCachedApkMd5 = v[3];
                        showUpdateViewOnly(v[0], v[1]);
                    } catch (Throwable t) {
                        Log.w(TAG, "show update info failed: " + t.getMessage());
                        showToast("获取更新信息失败");
                    }
                }
            }, "update-info").start();
        }

        @JavascriptInterface
        public void onQqAuthorize() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.authorizeQqMusic();
                    }
                    // 稍后推送状态（后台 su/URI 完成后状态可能变化）
                    uiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // QQ 授权状态不入 metaSig,普通 pushAll 会被轻量通道吞掉,
                            // 授权结果永远不刷新 → 强制全量推送
                            requestFullPush();
                        }
                    }, 8000L);
                }
            });
        }

        @JavascriptInterface
        public void onSelectUsbSource() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.activateUsbSource();
                    }
                    pushAll();
                }
            });
        }

        @JavascriptInterface
        public void onDeactivateUsbSource() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) {
                        mService.deactivateUsbSource();
                    }
                    pushAll();
                }
            });
        }

        @JavascriptInterface
        public String onGetUsbMusicList() {
            if (mService == null) return "[]";
            return mService.getUsbMusicListJson();
        }

        @JavascriptInterface
        public void onPlayUsbMusic(final int index) {
            // fix: 不切主线程，直接在 JS bridge 子线程调用 play()。
            // play() 中创建 MediaPlayer+prepareAsync 不阻塞主线程，onPrepared 回调自动回主线程执行 start。
            // currentIndex/playState 已声明 volatile，子线程修改对主线程可见。
            if (mService != null) {
                mService.playUsbMusic(index);
            } else {
                Log.w(TAG, "JsBridge onPlayUsbMusic: mService is null!");
            }
        }

 /** 收藏列表点歌——播放器进入收藏序列模式,next/prev 按收藏列表顺序切。 */
        @JavascriptInterface
        public void onPlayUsbMusicFav(final int index) {
            if (mService != null) {
                mService.playUsbMusic(index, true);
            } else {
                Log.w(TAG, "JsBridge onPlayUsbMusicFav: mService is null!");
            }
        }

        @JavascriptInterface
        public void onUsbPlayPause() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.usbPlayPause();
                }
            });
        }

        @JavascriptInterface
        public void onUsbNext() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.usbNext();
                }
            });
        }

        @JavascriptInterface
        public void onUsbPrev() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.usbPrev();
                }
            });
        }

        @JavascriptInterface
        public void onUsbSeekTo(final long pos) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.usbSeekTo(pos);
                }
            });
        }

        @JavascriptInterface
        public boolean onSeekTo(final long pos) {
            if (mService != null) return mService.seekTo(pos);
            return false;
        }

        @JavascriptInterface
        public void onRescanUsb() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.rescanUsb();
                }
            });
        }

        @JavascriptInterface
        public void onSetUsbPlayMode(final int mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.setUsbPlayMode(mode);
                    pushAll();
                }
            });
        }

        @JavascriptInterface
        public String onGetUsbState() {
            if (mService == null) return "{}";
            return mService.getUsbStateJson();
        }

        @JavascriptInterface
        public void onToggleUsbFav() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.toggleUsbFav();
                }
            });
        }

        @JavascriptInterface
        public void onSetUsbLyricSource(final String source) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mService != null) mService.setUsbLyricSource(source);
                }
            });
        }

        @JavascriptInterface
        public String onGetUsbSongInfo(final int index) {
            if (mService == null) return "{}";
            return mService.getUsbSongInfoJson(index);
        }
    }

    void confirmControllerSelection(int index) {
        if (mService == null) {
            return;
        }
        // fix: 用户从控制器列表选中非 USB 音源时,若 USB 音源激活先停用。
        // 之前由前端点蓝牙 tab 时立即 deactivate(打开列表就停音乐、清歌词);
        // 改为"打开列表不动音源,选中才切"后,停用逻辑落在这里(与 ByPkg 一致)。
        if (mService.isUsbRouteArmed()) {
            mService.deactivateUsbSource();
        }
        List<MediaController> list = mService.getAvailableControllers();
        if (list != null && index >= 0 && index < list.size()) {
            // 用户手动切到真实音源 → 取消 CarPlay 盒子选中
            mService.setCarplayBoxSelected(false);
            mService.selectController(list.get(index));
        } else {
            // index 超出 availableControllers 范围 = ghost 控制器(dumpsys 里 active=false 的暂停会话)
            // ghost 条目在 JSON 数组中排在 availableControllers 之后,index 减去列表大小得到 ghost 索引
            int ghostIdx = (list != null ? list.size() : 0);
            int offset = index - ghostIdx;
            List<String> ghosts = mService.getGhostControllerPkgs();
            if (offset >= 0 && offset < ghosts.size()) {
                String pkg = ghosts.get(offset);
                if (CarMediaService.PKG_CARPLAY_BOX.equals(pkg)) {
                    // 路径B:点击 CarPlay 条目 = 手动切换选中(列表常驻,可随时切换;
                    // 盒子连接后即生效,不依赖当前连接状态)
                    Log.i(TAG, "confirmControllerSelection: switch to CarPlay box (selected)");
                    mService.setCarplayBoxSelected(true);
                    return;
                }
                // 用户手动切到 ghost 音源 → 取消 CarPlay 盒子选中
                mService.setCarplayBoxSelected(false);
                Log.i(TAG, "confirmControllerSelection: selecting ghost controller " + pkg);
                // 只选中,不拉起界面,不发 media key
                mService.setGhostControllerPkg(pkg);
            }
        }
    }

 /**
 * 按包名切换控制器(JS 传包名,不依赖列表顺序/数量)。
 * CarPlay → 手动选中;真实音源(活跃会话)→ selectController;ghost(暂停会话)→ setGhostControllerPkg。
 */
    void confirmControllerSelectionByPkg(String pkg) {
        if (mService == null || pkg == null || pkg.isEmpty()) {
            return;
        }
        // 需求:控制器列表点 "USB音乐" → 激活 USB 音源(无需切晴空主题)。
        // 必须放在下方 deactivate 块之前:选中 USB 时若尚未武装才 activate,
        // 已武装(正在播 USB)则保持现状,不触发 deactivate→activate 的往返。
        if (UsbSourceController.SOURCE_PKG_USB.equals(pkg)) {
            if (!mService.isUsbRouteArmed()) {
                Log.i(TAG, "confirmControllerSelectionByPkg: switch to USB");
                mService.activateUsbSource();
            }
            mService.setCarplayBoxSelected(false);
            return;
        }
        // fix: 同上——选中非 USB 控制器时若 USB 音源激活先停用。
        // 前端点蓝牙/控制器 tab 现在只弹列表(见 ui.html skySelectSource),
        // USB 播放与歌词在列表打开期间保持;真正切走音源发生在这里。
        if (mService.isUsbRouteArmed()) {
            mService.deactivateUsbSource();
        }
        if (CarMediaService.PKG_CARPLAY_BOX.equals(pkg)) {
            // 路径B:点击 CarPlay 条目 = 手动切换选中(列表常驻,选中即注入)
            Log.i(TAG, "confirmControllerSelectionByPkg: switch to CarPlay (selected)");
            mService.setCarplayBoxSelected(true);
            // 选中 CarPlay 立即尝试自动合并白名单盒子(com.carletter.ceb/
            // com.aufn.carplay)——"都搞白名单了,肯定要自动合并":盒子会话已存在时
            // 选中即绑定信息源(显示盒子歌名),不用等外部会话变化事件。
            mService.autoMergeCarplayBoxIfPresent();
            return;
        }
        // 合并模式:点击盒子真实 MediaSession 条目 = 与 CarPlay 合并/取消合并。
        // 合并后:CarPlay 负责切歌(注入 87/88),盒子 MediaSession 负责显示歌曲信息。
        // 再点同一盒子 = 取消合并(回到纯 CarPlay 固定文案)。
        if (CarMediaService.isCarplayBoxRealPkg(pkg)) {
            if (mService.isCarplayBoxSelected() && mService.isCarplayBoxMerged(pkg)) {
                Log.i(TAG, "confirmControllerSelectionByPkg: unmerge box " + pkg);
                // 取消合并 + 抑制自动合并(直到重新选 CarPlay 或手动再合并)
                mService.dismissCarplayMerge();
                return;
            }
            Log.i(TAG, "confirmControllerSelectionByPkg: merge CarPlay + box info source " + pkg);
            mService.setCarplayBoxSelected(true); // 合并自动确保 CarPlay 控制选中
            mService.setCarplayBoxMerged(pkg);
            // 把盒子会话绑定为当前控制器:元数据管道(监听/标题/封面)走盒子 MediaSession,
            // 控制仍由 carplayBoxSelected 分支注入 87/88(dispatchMediaAction 最前优先)
            if (!mService.switchToController(pkg)) {
                Log.w(TAG, "confirmControllerSelectionByPkg: box session not found " + pkg);
            }
            return;
        }
        // 真实音源(活跃会话)
        if (mService.switchToController(pkg)) {
            Log.i(TAG, "confirmControllerSelectionByPkg: switch to real source " + pkg);
            mService.setCarplayBoxSelected(false);
            return;
        }
        // ghost(暂停会话,如暂停的酷我)
        List<String> ghosts = mService.getGhostControllerPkgs();
        if (ghosts.contains(pkg)) {
            Log.i(TAG, "confirmControllerSelectionByPkg: select ghost " + pkg);
            mService.setCarplayBoxSelected(false);
            mService.setGhostControllerPkg(pkg);
        } else {
            Log.w(TAG, "confirmControllerSelectionByPkg: unknown controller " + pkg);
        }
    }

    // ================= 按键录制 =================

    private void startRecording(final String action, final int tap) {
        if (mService == null) {
            return;
        }
        recording = true;
        mService.startKeyRecording(new KeyMonitor.KeyRecorder() {
            @Override
            public void onKeyRecorded(final int keyCode) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        recording = false;
                        if (mService != null) {
                            mService.setKeyAction(keyCode, tap, action);
                        }
                        runJs("CarMediaUI.endRecord(" + keyCode + ");");
                        pushAll();
                    }
                });
            }
        });
    }

    private void cancelRecording() {
        recording = false;
        if (mService != null) {
            mService.stopKeyRecording();
        }
        runJs("CarMediaUI.cancelRecord();");
    }

    // ================= 背景管理 =================

    private String readBgMode() {
        try {
            String m = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_BG_MODE, BG_DARK);
            if (m == null || m.isEmpty()) {
                return BG_DARK;
            }
            // 旧版 custom 带壁纸来源 → 升级为 light（亮色壁纸）
            if (BG_CUSTOM.equals(m)) {
                String src = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_BG_SRC, "");
                if (!src.isEmpty()) {
                    return BG_LIGHT;
                }
            }
            return m;
        } catch (Throwable t) {
            return BG_DARK;
        }
    }

    private void saveBgMode(String mode) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_BG_MODE, mode).apply();
        } catch (Throwable ignored) {
        }
    }

 /** 扫描 assets/wallpapers 下所有图片，弹出选择框（不依赖系统图片选择器，避免模拟器闪退）。 */
    private void pickCustomWallpaper() {        try {
            final List<String> names = scanWallpapers();
            if (names.isEmpty()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择壁纸图片")
                    .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String file = names.get(which);
                            try {
                                copyAssetToFiles(file);
                                setBgSrc(file);
                                bgMode = BG_LIGHT;
                                saveBgMode(BG_LIGHT);
                                pushAll();
                            } catch (Throwable t) {
                                Log.w(TAG, "apply wallpaper failed: " + t.getMessage());
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "pickCustomWallpaper failed: " + t.getMessage());
        }
    }

 /** 列出 assets/wallpapers 下的图片文件名（jpg/png/webp），按名排序。 */
    private List<String> scanWallpapers() {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        try {
            String[] files = getAssets().list("wallpapers");
            if (files != null) {
                for (String f : files) {
                    String lower = f.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png") || lower.endsWith(".webp")) {
                        list.add(f);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan wallpapers failed: " + t.getMessage());
        }
        java.util.Collections.sort(list);
        return list;
    }

 /** 把 assets/wallpapers/<file> 复制到 filesDir，作为自定义壁纸。 */
    private void copyAssetToFiles(String file) throws Exception {
        InputStream in = getAssets().open("wallpapers/" + file);
        try {
            File out = new File(getFilesDir(), "custom_bg.jpg");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            } finally {
                fos.close();
            }
        } finally {
            in.close();
        }
    }

    // ================= EAS 推送外观：自定义图标/封面 =================

 /** 弹出选择来源：系统文件选择器 / 内置图片（与壁纸同一批 assets 图片，模拟器安全）。
 * kind: icon=推送图标 / cover=自定义封面 / fallback=音源无封面时的固定图片 */
    private void pickEasCustomImage(final String kind) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(easPickTitle(kind))
                    .setItems(new String[]{"从文件选择", "从内置图片选择"}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                openSystemImagePicker(kind);
                            } else {
                                showAssetsImagePicker(kind);
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "pickEasCustomImage failed: " + t.getMessage());
        }
    }

    private String easPickTitle(String kind) {
        if ("cover".equals(kind)) return "选择EAS推送封面";
        if ("fallback".equals(kind)) return "选择无封面固定图片";
        return "选择EAS推送图标";
    }

    private String easStableName(String kind) {
        if ("cover".equals(kind)) return "eas_custom_cover.png";
        if ("fallback".equals(kind)) return "eas_fallback_cover.png";
        return "eas_custom_icon.png";
    }

    private String easPrefKey(String kind) {
        if ("cover".equals(kind)) return PREF_EAS_CUSTOM_COVER;
        if ("fallback".equals(kind)) return PREF_EAS_FALLBACK_COVER;
        return PREF_EAS_CUSTOM_ICON;
    }

    private String easToastLabel(String kind) {
        if ("cover".equals(kind)) return "封面";
        if ("fallback".equals(kind)) return "固定图片";
        return "图标";
    }

    private int easReqCode(String kind) {
        if ("cover".equals(kind)) return REQ_PICK_EAS_COVER;
        if ("fallback".equals(kind)) return REQ_PICK_EAS_FALLBACK;
        return REQ_PICK_EAS_ICON;
    }

 /** 系统文件选择器（真车可用；模拟器不可用时 catch 后回退内置图片）。 */
    private void openSystemImagePicker(String kind) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, easReqCode(kind));
        } catch (Throwable t) {
            Log.w(TAG, "system image picker unavailable, fallback to assets", t);
            showAssetsImagePicker(kind);
        }
    }

 /** 内置图片选择（assets/wallpapers，与自定义壁纸同一来源，模拟器/真车都可用）。 */
    private void showAssetsImagePicker(final String kind) {
        try {
            final List<String> names = scanWallpapers();
            if (names.isEmpty()) {
                showToast("没有可用的内置图片");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(easPickTitle(kind) + "（内置图片）")
                    .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            String file = names.get(which);
                            try {
                                InputStream is = getAssets().open("wallpapers/" + file);
                                byte[] bytes;
                                try {
                                    bytes = readAll(is);
                                } finally {
                                    is.close();
                                }
                                Bitmap bmp = decodeSampled(bytes, 1024);
                                if (bmp == null) {
                                    showToast("图片解码失败");
                                    return;
                                }
                                Uri saved = ArtworkHelper.saveCustomImage(MainActivity.this, bmp, easStableName(kind));
                                if (saved == null) {
                                    showToast("图片保存失败");
                                    return;
                                }
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                        .putString(easPrefKey(kind), saved.toString())
                                        .apply();
                                if (mService != null) {
                                    mService.onEasPushConfigChanged();
                                }
                                pushAll();
                                // 轻量回写自定义图到 JS(否则弹窗"已设置"要等下次全量推送才刷新)
                                runJs("CarMediaUI.easCustomUpdated('" + kind + "',"
                                        + JSONObject.quote(saved.toString()) + ");");
                                showToast(easToastLabel(kind) + "已更新");
                            } catch (Throwable t) {
                                Log.w(TAG, "apply eas custom image failed: " + t.getMessage());
                                showToast("图片处理失败");
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "showAssetsImagePicker failed: " + t.getMessage());
        }
    }

 /** 读取输入流全部字节。 */
    private byte[] readAll(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

 /** 限制边长解码（防大图 OOM），最大边 maxDim px。 */
    private Bitmap decodeSampled(byte[] data, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w / sample > maxDim || h / sample > maxDim) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable t) {
            Log.w(TAG, "decodeSampled failed: " + t.getMessage());
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 安装器/MT 返回：关闭系统安装器路径的 ApkAuth 旁路（不再自动打开文件管理器，更新包路径已在提示中给出）
        if (requestCode == REQ_INSTALL_VIEW) {
            Log.i(TAG, "installer returned (resultCode=" + resultCode + "), release verify bypass");
            SilentInstaller.releaseBypassForSystemInstaller();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String kind;
        if (requestCode == REQ_PICK_EAS_ICON) {
            kind = "icon";
        } else if (requestCode == REQ_PICK_EAS_COVER) {
            kind = "cover";
        } else if (requestCode == REQ_PICK_EAS_FALLBACK) {
            kind = "fallback";
        } else {
            return;
        }
        try {
            InputStream is = getContentResolver().openInputStream(data.getData());
            if (is == null) {
                showToast("无法读取所选图片");
                return;
            }
            byte[] bytes;
            try {
                bytes = readAll(is);
            } finally {
                is.close();
            }
            Bitmap bmp = decodeSampled(bytes, 1024);
            if (bmp == null) {
                showToast("图片解码失败");
                return;
            }
            Uri saved = ArtworkHelper.saveCustomImage(this, bmp, easStableName(kind));
            if (saved == null) {
                showToast("图片保存失败");
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(easPrefKey(kind), saved.toString())
                    .apply();
            if (mService != null) {
                mService.onEasPushConfigChanged();
            }
            pushAll();
            // 轻量回写自定义图到 JS(否则弹窗"已设置"要等下次全量推送才刷新)
            runJs("CarMediaUI.easCustomUpdated('" + kind + "',"
                    + JSONObject.quote(saved.toString()) + ");");
            showToast(easToastLabel(kind) + "已更新");
        } catch (Throwable t) {
            Log.w(TAG, "onActivityResult eas custom image failed: " + t.getMessage());
            showToast("图片处理失败");
        }
    }

 /** 包名 -> 应用显示名。
 * 系统媒体中心 ecarx.xsf.mediacenter 在 CarMedia 场景下作为手机互联 app
 * (CarPlay/HiCar/CarLife 等)的系统代理控制器,系统原名不直观,覆盖为中文名"通用媒体中心"。 */
 /** 卡死加固:包名→显示名缓存。pushAll 每秒调 getAppDisplayName,
 * 每次都是 PackageManager Binder IPC(车机 PackageManager 忙时可阻塞主线程)。 */
    private final java.util.Map<String, String> pkgNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private String getAppDisplayName(String pkg) {
        if (pkg == null) {
            return "";
        }
        String cached = pkgNameCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        // 包名 -> 中文名覆盖（系统 app 名称不直观时优先用映射）
        if ("ecarx.xsf.mediacenter".equals(pkg)) {
            pkgNameCache.put(pkg, "通用媒体中心");
            return "通用媒体中心";
        }
        String name = pkg;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                name = label.toString();
            }
        } catch (Exception ignored) {
        }
        pkgNameCache.put(pkg, name);
        return name;
    }

    private void scheduleUiPoll(Runnable r) {
        if (uiHandler != null) {
            uiHandler.postDelayed(r, 1000);
        }
    }

    // ================= 车机日夜（ECarXPowerManager 反射适配） =================
    // ROM 分析：车机日夜真实来源是 ecarx_power_manager 服务，
    // WebView 的 prefers-color-scheme（ui_night_mode）不是权威值。
    // 调用链：com.ecarx.sdk.device.DeviceAPI.get(ctx) → getDayNightMode() → IDayNightMode
    // （旧路径：com.ecarx.xui.adaptapi.device.Device.create(ctx) 同构，Deprecated）
    // 方法：getDayNight() 1=日 2=夜；registerDayNightModeCallBack(回调) 实时监听。
    // 全部反射调用：车机无 SDK 时静默降级（模拟器/预览保持 matchMedia 行为）。

    private Object reflectDayNightMode() {
        try {
            // 1) 新路径 com.ecarx.sdk.device.DeviceAPI
            Object api = invokeStatic("com.ecarx.sdk.device.DeviceAPI", "get", new Class[]{Context.class}, this);
            if (api != null) {
                Object dm = invoke(api, "getDayNightMode", null, new Object[0]);
                if (dm != null) return dm;
            }
        } catch (Throwable ignored) { }
        try {
            // 2) 旧路径 com.ecarx.xui.adaptapi.device.Device（Deprecated）
            Object device = invokeStatic("com.ecarx.xui.adaptapi.device.Device", "create", new Class[]{Context.class}, this);
            if (device != null) {
                Object dm = invoke(device, "getDayNightMode", null, new Object[0]);
                if (dm != null) return dm;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object invokeStatic(String cls, String method, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(cls).getMethod(method, types).invoke(null, args);
    }

    private static Object invoke(Object obj, String method, Class<?>[] types, Object... args) throws Exception {
        if (obj == null) return null;
        if (types == null) {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getName().equals(method)) {
                    return m.invoke(obj, args);
                }
            }
            return null;
        }
        return obj.getClass().getMethod(method, types).invoke(obj, args);
    }

 /** 读取当前日夜：true=夜间。失败返回 false（JS 会回退 matchMedia）。 */
    private boolean readNightState() {
        try {
            Object dm = mDayNightMode != null ? mDayNightMode : reflectDayNightMode();
            if (dm == null) return false;
            if (mDayNightMode == null) mDayNightMode = dm;
            Object v = invoke(dm, "getDayNight", null, new Object[0]);
            return (v instanceof Integer) && ((Integer) v).intValue() == 2;
        } catch (Throwable t) {
            Log.w(TAG, "readNightState failed: " + t.getMessage());
            return false;
        }
    }

    private void registerNightCallback() {
        if (mNightRegistered) return;
        try {
            Object dm = mDayNightMode != null ? mDayNightMode : reflectDayNightMode();
            if (dm == null) return;
            if (mDayNightMode == null) mDayNightMode = dm;
            mNightState = readNightState();
            // 回调接口：IDayNightMode$IDayNightChangeCallBack，方法 onDayNightChanged(int)
            final Class<?> cbCls = Class.forName("com.ecarx.xui.adaptapi.device.daynigntmode.IDayNightMode$IDayNightChangeCallBack");
            mNightCbProxy = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onDayNightChanged".equals(method.getName()) && args != null && args.length > 0) {
                        final int v = ((Number) args[0]).intValue();
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mNightState = (v == 2);
                                runJs("CarMediaUI.onNightChanged(" + mNightState + ");");
                            }
                        });
                    }
                    return null;
                }
            });
            Boolean ok = (Boolean) invoke(dm, "registerDayNightModeCallBack", new Class[]{cbCls}, mNightCbProxy);
            mNightRegistered = (ok != null && ok.booleanValue());
            Log.i(TAG, "registerNightCallback ok=" + mNightRegistered + " night=" + mNightState);
            if (!mNightRegistered) {
                mNightCbProxy = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerNightCallback failed: " + t.getMessage());
        }
    }

    private void unregisterNightCallback() {
        if (!mNightRegistered || mDayNightMode == null || mNightCbProxy == null) {
            mNightRegistered = false;
            return;
        }
        try {
            invoke(mDayNightMode, "unregisterDayNigntModeCallBack", new Class[]{mNightCbProxy.getClass().getInterfaces()[0]}, mNightCbProxy);
        } catch (Throwable ignored) { }
        mNightRegistered = false;
        mNightCbProxy = null;
    }

    // ================= 版本更新检测（自建服务器在线更新） =================
    // 更新清单地址由构建注入（BuildConfig.UPDATE_URL，占位示例 https://example.com/CarMedia.txt），JSON 结构见 checkForUpdate 注释。
    // 服务器直链下载 APK，无需再经过网盘；蓝奏云方案（acw_sc__v2 反爬）已废弃。

 /** 每次冷启动（进程重启后首次打开主界面）检查一次更新。 */
    private void checkForUpdateOnce() {
        // 进程内已检查过（Activity 重建/热启动）不再重复；进程被杀后静态变量重置 → 重新检查
        if (sCheckedInProcess) {
            return;
        }
        sCheckedInProcess = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                checkForUpdate(false);
            }
        }, "update-check").start();
    }

 /** 抓取服务器更新清单（CarMedia.txt，JSON），解析版本/更新内容/下载地址，版本大于当前则弹窗。
 * force=true（手动按钮）时：无新版本也给 toast 反馈；自动检查则静默。
 * CarMedia.txt 格式（JSON 对象）：
 * {
     *    "version": "1.2",            // 远端最新版本号
     *    "notes": "1.新增xxx\n2.修复xxx",  // 更新内容（\n 换行）
     *    "url": "https://example.com/CarMedia-1.2.apk", // APK 直接下载地址
     *    "md5": "..."                 // 可选，APK MD5 校验值
 * } */
    private void checkForUpdate(final boolean force) {
        try {
            String page = httpGet(UPDATE_URL, null);
            if (page == null || page.isEmpty()) {
                if (force) {
                    showToast("检测失败，请检查网络");
                }
                Log.i(TAG, "update check: page empty");
                return;
            }
            final String[] ver = parseRemoteManifest(page);
            if (ver == null) {
                if (force) {
                    showToast("检测失败，请重试");
                }
                Log.i(TAG, "update check: version info not found");
                return;
            }
            final String remoteVer = ver[0];
            final String notes = ver[1];
            // 缓存最近检测到的版本信息（点击版本号直接弹窗用）
            sCachedRemoteVer = remoteVer;
            sCachedNotes = notes;
            sCachedRemoteUrl = ver[2];
            sCachedApkMd5 = ver[3];
            Log.i(TAG, "update check: remote=[" + remoteVer + "] notes=[" + notes + "]");
            if (compareVersion(remoteVer, APP_VERSION) <= 0) {
                Log.i(TAG, "update check: no newer version (remote=" + remoteVer + ", local=" + APP_VERSION + ")");
                if (force) {
                    showToast("当前已是最新版本");
                }
                return;
            }
            Log.i(TAG, "update check: newer version found " + remoteVer);
            // 弹提示框：有新版本，是否更新；更新内容（\n 换行）
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showUpdateDialog(remoteVer, notes);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "update check failed: " + t.getMessage());
            if (force) {
                showToast("检测失败，请重试");
            }
        }
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    runJs("showToast(" + JSONObject.quote(msg) + ");");
                } catch (Throwable t) {
                    Log.w(TAG, "showToast failed: " + t.getMessage());
                }
            }
        });
    }

 /** 展示上次更新安装结果（系统 Toast，不依赖 WebView；展示后清除，避免反复弹） */
    private void showPendingUpdateResult() {
        try {
            String last = getSharedPreferences(SilentInstaller.PREFS, MODE_PRIVATE)
                    .getString(SilentInstaller.PREF_UPDATE_RESULT, null);
            if (last != null) {
                getSharedPreferences(SilentInstaller.PREFS, MODE_PRIVATE)
                        .edit().remove(SilentInstaller.PREF_UPDATE_RESULT).apply();
                Toast.makeText(this, last, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "showPendingUpdateResult failed: " + t.getMessage());
        }
    }

 /** 解析服务器更新清单 JSON，返回 [版本号, 更新内容, 下载地址, MD5]；找不到/解析失败返回 null。
     *  容错：允许注释行（以 # 或 // 开头）与 BOM；JSON 解析失败时回退正则提取 version/notes/url。 */
    private String[] parseRemoteManifest(String page) {
        try {
            // 去 BOM、去注释行
            String s = page.trim();
            if (s.startsWith("\uFEFF")) {
                s = s.substring(1).trim();
            }
            StringBuilder sb = new StringBuilder();
            for (String line : s.split("\n")) {
                String t = line.trim();
                if (t.startsWith("#") || t.startsWith("//")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            String json = sb.toString().trim();
            JSONObject o = new JSONObject(json);
            String remoteVer = o.optString("version", "").trim();
            String notes = o.optString("notes", "").trim();
            String url = o.optString("url", "").trim();
            String md5 = o.optString("md5", "").trim();
            if (remoteVer.isEmpty()) {
                return null;
            }
            return new String[]{remoteVer, notes, url, md5};
        } catch (Throwable t) {
            // JSON 解析失败，回退正则提取
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile(
                    "\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(page);
            if (!vm.find()) {
                return null;
            }
            String remoteVer = vm.group(1).trim();
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile(
                    "\"notes\"\\s*:\\s*\"([^\"]*)\"").matcher(page);
            String notes = nm.find() ? nm.group(1).trim() : "";
            java.util.regex.Matcher um = java.util.regex.Pattern.compile(
                    "\"url\"\\s*:\\s*\"([^\"]*)\"").matcher(page);
            String url = um.find() ? um.group(1).trim() : "";
            return new String[]{remoteVer, notes, url, ""};
        }
    }

 /** 简单 HTTP GET（带 UA），返回响应体字符串。 */
    private String httpGet(String url, String cookie) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", "acw_sc__v2=" + cookie);
            }
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "update check: http " + code);
                return null;
            }
            java.io.InputStream in = conn.getInputStream();
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "update check: httpGet " + url + " -> " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

 /** 版本号比较：a>b 返回正，相等返回 0。支持 "1.0" / "1.0.1" 等。 */
    private static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

 /** 下载进度回调（工作线程调用，percent 0-100） */
    private interface ProgressCallback {
        void onProgress(int percent);
    }

 /** HTTP GET 下载字节（APK 下载用），带 UA；失败返回 null。
 * 支持进度回调：根据 Content-Length 计算百分比（无长度则只回调 0/100）。 */
    private byte[] httpGetBytes(String url, final ProgressCallback progress) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "download apk: http " + code);
                return null;
            }
            long total = conn.getContentLengthLong();
            java.io.InputStream in = conn.getInputStream();
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long read = 0;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    read += n;
                    if (progress != null) {
                        if (total > 0) {
                            int pct = (int) (read * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                progress.onProgress(pct);
                            }
                        } else {
                            // 无 Content-Length：无法算进度，仅在首块回调一次 0 表示开始
                            if (lastPct == -1) {
                                lastPct = 0;
                                progress.onProgress(0);
                            }
                        }
                    }
                }
                if (progress != null) {
                    progress.onProgress(100);
                }
                return bos.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "download apk failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

 /** 计算字节数组 MD5（小写 hex）。 */
    private static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

 /** 把 APK 字节写入缓存目录并调起系统安装器（需用户确认）。
     *  ApkProvider 提供 content:// URI，避免 file:// 在 targetSdk>=24 触发 FileUriExposedException。
 * 若安装器不可用则回退打开下载页。 */
 /** 静默安装超时兜底：提交会话后若 60s 内未收到任何安装结果，复位 UI 并提示（防"永远卡在正在安装"） */
    private static final Handler sInstallTimeoutHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sInstallTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            MainActivity a = sInstance;
            if (a == null) return;
            boolean active = a.getSharedPreferences(SilentInstaller.PREFS, MODE_PRIVATE)
                    .getBoolean(SilentInstaller.PREF_UPDATE_ACTIVE, false);
            if (active) {
                a.getSharedPreferences(SilentInstaller.PREFS, MODE_PRIVATE)
                        .edit().putBoolean(SilentInstaller.PREF_UPDATE_ACTIVE, false).apply();
                Log.w(TAG, "install timeout: no result in 60s, reset UI and notify");
                a.runJs("CarMediaUI.updateInstallFailed(\"安装结果未确认（可能被系统拦截），请重试\");");
            }
        }
    };

 /** 提交静默安装后启动 60s 超时看护（收到任何安装结果即由 Receiver 取消） */
    public static void armInstallTimeout() {
        sInstallTimeoutHandler.removeCallbacks(sInstallTimeoutRunnable);
        sInstallTimeoutHandler.postDelayed(sInstallTimeoutRunnable, 60000L);
    }

 /** 收到安装结果后取消超时看护 */
    public static void cancelInstallTimeout() {
        sInstallTimeoutHandler.removeCallbacks(sInstallTimeoutRunnable);
    }

 /**
 * 供 UpdateResultReceiver 在静默安装结果回调时调用：
 * 把失败原因推进 UI（toast + 复位"正在安装..."按钮），避免安装失败后界面永久卡住且无提示。
 */
    public static void notifyUpdateResult(final String msg) {
        try {
            if (sInstance != null) {
                sInstance.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (sInstance == null) return;
                        sInstance.runJs("CarMediaUI.updateInstallFailed(" + JSONObject.quote(msg) + ");");
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "notifyUpdateResult failed: " + t.getMessage());
        }
    }

 /**
 * 静默安装失败后的统一兜底：拉起系统安装器让用户手动确认安装。
 * 适用于所有静默安装未成功的场景（提交失败 / 结果失败 / -999 降级拒绝 / 未知状态码）：
 * 降级包在静默路径会被 PMS 直接拒绝，但系统安装器（PackageInstaller UI）会提示
 * "安装较低版本"让用户确认，签名一致时即可装上——所以一律回退系统安装器，而不是止步于报错。
 * 同时把更新包复制到公共下载目录并在提示中给出路径；系统安装器不可用时自动打开文件管理器定位，
 * 便于用户手动安装。
 * 可从 MainActivity 或 UpdateResultReceiver 任意上下文调用。
 */
    public static void fallbackToSystemInstaller(final Context ctx, final String reason) {
        try {
            // 0) 先把更新包复制到公共下载目录（cache 是应用私有目录，文件管理器打不开；失败则退回私有路径仅作提示）
            String publicPath = exportApkToPublicDownload(ctx);
            final String apkPath = (publicPath != null)
                    ? publicPath
                    : new File(ctx.getCacheDir(), "carmedia_update.apk").getAbsolutePath();
            // 展示给用户的路径用 /sdcard/ 写法，车机用户看得懂
            final String showPath = apkPath.replace("/storage/emulated/0/", "/sdcard/");

            final String tip = (reason != null && !reason.isEmpty())
                    ? (reason + "，已改用系统安装器，请在弹出的安装界面确认\n更新包位置：" + showPath)
                    : ("静默安装未成功，已改用系统安装器，请在弹出的安装界面确认\n更新包位置：" + showPath);
            Log.w(TAG, "fallback to system installer: " + tip);

            // 1) 系统 Toast + 结果持久化（不依赖 MainActivity/WebView 存活）
            try {
                Toast.makeText(ctx, tip, Toast.LENGTH_LONG).show();
                ctx.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE)
                        .edit().putString(SilentInstaller.PREF_UPDATE_RESULT, tip).apply();
            } catch (Throwable ignored) {}

            // 2) MainActivity 存活时同步刷新 UI 按钮（复位"正在安装..." + 弹窗内展示详细原因与路径）
            if (sInstance != null) {
                sInstance.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (sInstance != null) {
                                sInstance.runJs("CarMediaUI.updateInstallFailed(" + JSONObject.quote(tip) + ");");
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "fallback ui refresh failed: " + t.getMessage());
                        }
                    }
                });
            }

            // 3) 拉起系统安装器（content:// URI，避免 file:// 触发 FileUriExposedException）
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(ApkProvider.buildApkUri(), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // ★ 解析校验：车机 ROM 若精简/禁用系统安装器，VIEW Intent 可能被电话/相册等应用接管，
            // 这里先确认目标确实是安装器，否则打开文件管理器定位更新包并给明确提示
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            String targetPkg = (ri != null && ri.activityInfo != null) ? ri.activityInfo.packageName : null;
            if (targetPkg == null) {
                Log.w(TAG, "no activity handles APK install intent, open apk location");
                openApkLocation(ctx);
                try {
                    Toast.makeText(ctx, "未找到可用的系统安装器，已打开更新包所在目录，可手动点击安装（路径：" + showPath + "）", Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
                return;
            }
            String tp = targetPkg.toLowerCase(java.util.Locale.ROOT);
            if (!tp.contains("installer") && !tp.contains("packageinstall")) {
                Log.w(TAG, "APK install intent resolves to non-installer: " + targetPkg + ", open apk location");
                openApkLocation(ctx);
                try {
                    Toast.makeText(ctx, "系统安装器异常，安装请求被 " + targetPkg + " 接管，已打开更新包所在目录（路径：" + apkPath + "）", Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
                return;
            }
            // 拉起安装器：弹选择【系统安装器 / 其他文件管理器（MT/ES 等）】；点选前先确保
            // sys.jsbd.apk_verify=1（车机未刷修改版 services.jar 时 ApkAuth 拦所有安装，静默路径的
            // 旁路已在失败回调后恢复，必须为系统安装器重新开启），用户从安装器返回后由 onActivityResult 恢复
            Activity act = (ctx instanceof Activity) ? (Activity) ctx : sInstance;
            if (act != null) {
                final Activity a = act;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showInstallerChooser(a, intent, targetPkg, ri);
                    }
                });
            } else {
                SilentInstaller.ensureBypassForSystemInstaller();
                // 安装未知应用 AppOps 放行：避免车机 ROM 弹"打开 LPPe Service 安装权限"的引导
                SilentInstaller.ensureInstallUnknownSourceAllowed(ctx);
                ctx.startActivity(intent);
                Log.i(TAG, "system installer launched: " + targetPkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fallbackToSystemInstaller failed: " + t.getMessage());
            openApkLocation(ctx);
            try {
                Toast.makeText(ctx, "无法打开系统安装器，已打开更新包所在目录，可手动安装", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

 /**
 * 选择用哪个应用安装更新包：系统安装器 / 其他能处理 APK 的应用（ES、MT 等文件管理器、应用商店）。
 * 动态枚举——装了什么就显示什么（应用名），每次都弹（不记住默认，避免系统 chooser 勾"始终"后不再弹）；
 * 除系统安装器外没有其他候选时只列系统安装器并直接启动。调用前需在 UI 线程。
 */
    private static void showInstallerChooser(final Activity act, final Intent baseIntent, final String sysPkg, final ResolveInfo sysRi) {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Intent> intents = new java.util.ArrayList<>();
        try {
            // 1) 系统安装器
            Intent si = new Intent(baseIntent);
            si.setComponent(new ComponentName(sysRi.activityInfo.packageName, sysRi.activityInfo.name));
            labels.add("系统安装器");
            intents.add(si);
            // 2) 其他能处理 APK 的应用（ES/MT 等文件管理器、应用商店）——动态枚举，装谁显示谁
            List<ResolveInfo> ris = act.getPackageManager().queryIntentActivities(baseIntent, 0);
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(sysRi.activityInfo.packageName)) continue; // 跳过系统安装器
                String pkgL = pkg.toLowerCase(java.util.Locale.ROOT);
                if (isUnrelatedSystemUi(pkgL)) continue; // 跳过桌面/设置/相册等无关应用
                boolean dup = false;
                for (Intent ex : intents) {
                    if (ex.getComponent() != null && pkg.equals(ex.getComponent().getPackageName())) { dup = true; break; }
                }
                if (dup) continue;
                Intent ni = new Intent(baseIntent);
                ni.setComponent(new ComponentName(pkg, ri.activityInfo.name));
                String label = "";
                try {
                    label = ri.loadLabel(act.getPackageManager()).toString();
                } catch (Throwable ignored) {}
                if (label == null || label.isEmpty()) label = pkg;
                labels.add(label);
                intents.add(ni);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showInstallerChooser collect failed: " + t.getMessage());
        }
        if (intents.isEmpty()) return;
        // 收集完成，开启 ApkAuth 旁路（onActivityResult/取消/异常路径都会恢复）
        SilentInstaller.ensureBypassForSystemInstaller();
        // 安装未知应用 AppOps 放行：避免车机 ROM 弹"打开 LPPe Service 安装权限"的引导
        //（正常放行本应用；个别吉利/联发科 ROM 把发起方解析成 LPPe Service 时兜底放行它）
        SilentInstaller.ensureInstallUnknownSourceAllowed(act);
        // 只有系统安装器 → 直接启动，无需选择（返回后 onActivityResult 释放旁路）
        if (intents.size() == 1) {
            try {
                act.startActivityForResult(intents.get(0), REQ_INSTALL_VIEW);
                Log.i(TAG, "installer launched (single): " + labels.get(0));
            } catch (Throwable t) {
                Log.w(TAG, "start installer failed: " + t.getMessage());
                SilentInstaller.releaseBypassForSystemInstaller();
                openApkLocation(act);
            }
            return;
        }
        // 多个候选 → 弹原生单选对话框（每次都弹）；取消时释放旁路，点选后由 onActivityResult 释放
        final String[] items = labels.toArray(new String[0]);
        final boolean[] launched = {false};
        try {
            new AlertDialog.Builder(act)
                    .setTitle("选择安装方式")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            launched[0] = true;
                            try {
                                act.startActivityForResult(intents.get(which), REQ_INSTALL_VIEW);
                                Log.i(TAG, "installer chooser picked: " + labels.get(which));
                            } catch (Throwable t) {
                                Log.w(TAG, "start picked installer failed: " + t.getMessage());
                                SilentInstaller.releaseBypassForSystemInstaller();
                                openApkLocation(act);
                            }
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            d.dismiss();
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface d) {
                            // 点选启动后 dismiss 会再触发一次，用 launched 区分：未启动=取消 → 释放旁路
                            if (!launched[0]) {
                                SilentInstaller.releaseBypassForSystemInstaller();
                                Log.i(TAG, "installer chooser cancelled, bypass released");
                            }
                        }
                    })
                    .show();
            Log.i(TAG, "installer chooser shown: " + labels.size() + " options");
        } catch (Throwable t) {
            Log.w(TAG, "installer chooser dialog failed, fallback direct: " + t.getMessage());
            try {
                act.startActivityForResult(intents.get(0), REQ_INSTALL_VIEW);
            } catch (Throwable t2) {
                SilentInstaller.releaseBypassForSystemInstaller();
                openApkLocation(act);
            }
        }
    }

 /**
 * 把 cache 里的更新包复制到公共下载目录（/sdcard/Download/carmedia_update.apk），
 * 返回公共路径；失败返回 null（调用方退回应用私有路径仅作提示）。
 * 重复复制时若目标存在且大小一致则跳过。
 */
    private static String exportApkToPublicDownload(Context ctx) {
        try {
            File src = new File(ctx.getCacheDir(), "carmedia_update.apk");
            if (!src.exists()) return null;
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dst = new File(dir, "carmedia_update.apk");
            if (dst.exists() && dst.length() == src.length()) {
                return dst.getAbsolutePath();
            }
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            Log.i(TAG, "apk exported to public download: " + dst.getAbsolutePath());
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "exportApkToPublicDownload failed: " + t.getMessage());
            return null;
        }
    }

 /**
 * 唤起文件管理器并打开更新包所在目录（下载目录）。
 * 原则：不要选择器、不要复杂界面——直接唤起能打开指定路径的文件管理器。
     *   1) 优先 SAF 目录 URI（content://com.android.externalstorage.documents/.../Download）：
 * 系统文件管理器（DocumentsUI）100% 支持，直接定位到 Download 目录；
 * 2) 系统文件 app 被精简/无应用处理目录 URI 时，枚举已安装文件管理器（MT 等）
 * 直接启动其主界面（MT 等第三方不注册目录 intent-filter，只能打开主界面，用户自行进入 Download）。
 */
    private static void openApkLocation(Context ctx) {
        // 候选目录 URI：下载目录 → 存储根目录（不同 ROM 支持粒度不同）
        String[] candidateUris = {
                "content://com.android.externalstorage.documents/document/primary%3ADownload",
                "content://com.android.externalstorage.documents/root/primary"
        };
        for (String u : candidateUris) {
            try {
                Intent openDir = new Intent(Intent.ACTION_VIEW);
                openDir.setData(Uri.parse(u));
                openDir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ResolveInfo ri = ctx.getPackageManager().resolveActivity(openDir, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName.toLowerCase(java.util.Locale.ROOT);
                if (isUnrelatedSystemUi(pkg)) {
                    Log.w(TAG, "skip unrelated handler for apk location: " + ri.activityInfo.packageName);
                    continue;
                }
                ctx.startActivity(openDir);
                Log.i(TAG, "open apk location via directory uri: " + ri.activityInfo.packageName + " (" + u + ")");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "openApkLocation candidate failed: " + u + " -> " + t.getMessage());
            }
        }
        // 兜底：没有任何应用能处理目录 URI（系统文件 app 被精简）时，直接启动文件管理器主界面（MT/厂商文件 app）
        List<String[]> fms = collectFileManagers(ctx);
        if (!fms.isEmpty()) {
            launchFileManagerByPkg(ctx, fms.get(0)[0]);
            return;
        }
        Log.w(TAG, "no usable file manager to open apk location");
    }

 /** 枚举已安装的文件管理器（包名/应用名特征匹配，黑名单过滤，按包名去重）。 */
    private static List<String[]> collectFileManagers(Context ctx) {
        List<String[]> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(main, 0);
            String[] hints = {
                    "filemanager", "file_manager", "filemanage", "filebrowser", "filebrowse",
                    "files", "documentsui", "document", "mtxx", "mt文件", "explorer", "文件"
            };
            for (ResolveInfo ri : apps) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                String pkgL = pkg.toLowerCase(java.util.Locale.ROOT);
                String label = "";
                try {
                    label = ri.loadLabel(ctx.getPackageManager()).toString();
                } catch (Throwable ignored) {}
                String labelL = label.toLowerCase(java.util.Locale.ROOT);
                for (String h : hints) {
                    if (pkgL.contains(h) || labelL.contains(h)) {
                        if (isUnrelatedSystemUi(pkgL) && !pkgL.contains("file") && !pkgL.contains("documents")) {
                            continue;
                        }
                        if (seen.add(pkg)) {
                            out.add(new String[]{pkg, label});
                        }
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "collectFileManagers failed: " + t.getMessage());
        }
        return out;
    }

 /** 按包名启动文件管理器主界面（launcher intent，必开）。 */
    private static void launchFileManagerByPkg(Context ctx, String pkg) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                Log.w(TAG, "no launch intent for " + pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            Log.i(TAG, "launched file manager: " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "launch file manager " + pkg + " failed: " + t.getMessage());
        }
    }

 /**
 * 打开系统桌面「添加/编辑小组件」界面。
 * 原厂桌面（ecarx.launcher3）没有添加小组件的入口，此方法显式拉起其 WidgetEditActivity（exported=true 可跨应用启动）。
 *
 * 兜底修正：系统 settings 的 APPWIDGET_PICK（com.android.settings.AppWidgetPickActivity）
 * 单独拉起会「启动即销毁」闪退（它需要宿主上下文，logcat: AppWidgetAdapter EXTRA_CUSTOM_INFO not present / AppWindowToken destroyed），
 * 且魔改 Launcher3 不暴露任何可外部拉起的 widget 列表组件。
 * 因此：①先探测 ecarx.launcher3 是否安装——存在则走 WidgetEditActivity（真车路径）；②不存在（模拟器/第三方桌面）
 * 不再盲目拉起 settings picker，改为 Toast 引导用户在桌面长按空白处进入「小组件」。
 */
    private static void launchDesktopWidgetEdit(Context ctx) {
        boolean hasEcarxLauncher = false;
        try {
            ctx.getPackageManager().getPackageInfo("ecarx.launcher3", 0);
            hasEcarxLauncher = true;
        } catch (Throwable ignored) {}

        if (hasEcarxLauncher) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("ecarx.launcher3", "ecarx.launcher3.widget.WidgetEditActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                Log.i(TAG, "launched ecarx widget edit activity");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "launch ecarx widget edit failed: " + t.getMessage());
            }
        }

        String hint = hasEcarxLauncher
                ? "打开桌面小组件失败，请在桌面长按空白处进入「小组件」"
                : "当前桌面不支持外部添加小组件，请在桌面长按空白处进入「小组件」";
        Log.i(TAG, "no external widget-add entry on this launcher; hint=" + hint);
        if (ctx instanceof Activity) {
            final String h = hint;
            ((Activity) ctx).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ctx, h, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

 /** 明显无关的系统 UI 组件（桌面/设置/权限/安装器等），把目录 Intent 丢给它们没有意义。 */
    private static boolean isUnrelatedSystemUi(String pkg) {
        return pkg.contains("launcher") || pkg.contains("systemui")
                || pkg.contains(".settings") || pkg.contains("permissioncontroller")
                || pkg.contains("packageinstall") || pkg.contains("installer")
                || pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("contacts")
                || pkg.contains("browser") || pkg.contains("gallery") || pkg.contains("music")
                || pkg.contains("video") || pkg.contains("camera") || pkg.contains("maps")
                || pkg.contains("email") || pkg.contains("mms") || pkg.contains("sms");
    }

 /**
 * 下载包与已安装版本签名是否一致。
 * 不一致时覆盖安装必失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE），
 * 提前拦截并给用户明确原因，避免静默失败后 UI 卡在"正在安装"。
 * 读不到签名/出错时放行（不阻塞安装，交给系统兜底）。
 */
    private boolean signaturesMatchInstalled(File apkFile) {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo newPi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            PackageInfo oldPi = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (newPi == null || oldPi == null || newPi.signatures == null || oldPi.signatures == null
                    || newPi.signatures.length == 0 || oldPi.signatures.length == 0) {
                Log.w(TAG, "signature precheck: cannot read signatures, skip");
                return true;
            }
            boolean same = newPi.signatures[0].equals(oldPi.signatures[0]);
            Log.i(TAG, "signature precheck: " + (same ? "MATCH" : "MISMATCH"));
            return same;
        } catch (Throwable t) {
            Log.w(TAG, "signature precheck failed: " + t.getMessage());
            return true;
        }
    }

 /** 读取下载 APK 的 versionCode；解析失败返回 -1（放行，交给系统兜底）。 */
    private int readApkVersionCode(File apkFile) {
        try {
            PackageInfo pi = getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (pi == null) return -1;
            return pi.versionCode;
        } catch (Throwable t) {
            Log.w(TAG, "readApkVersionCode failed: " + t.getMessage());
            return -1;
        }
    }

    private void installApkBytes(final byte[] data) {
        try {
            final File apkFile = new File(getCacheDir(), "carmedia_update.apk");
            FileOutputStream fos = new FileOutputStream(apkFile);
            fos.write(data);
            fos.flush();
            fos.close();
            Log.i(TAG, "apk saved: " + apkFile.getAbsolutePath() + " bytes=" + data.length);

            // ★ 签名预检：与已安装版本签名不一致时，静默安装必失败；不再直接拦截，
            // 提示原因后回退系统安装器（系统安装器同样会拒绝覆盖安装，用户可先卸载旧版再装）
            boolean sigOk = signaturesMatchInstalled(apkFile);
            // 记录签名预检结果：静默安装 -999 时 Receiver 据此区分"签名不一致"还是"ApkAuth 白名单拦截"
            try {
                getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(SilentInstaller.PREF_UPDATE_SIG_MATCH, sigOk ? 1 : 0).apply();
            } catch (Throwable ignored) {}
            if (!sigOk) {
                Log.w(TAG, "signature mismatch, fallback to system installer");
                final String warn = "新版本签名与当前版本不一致，静默安装被拒绝，已改用系统安装器（系统安装器同样可能拒绝覆盖安装，可先卸载旧版再安装）";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        runJs("CarMediaUI.updateInstallFailed(" + JSONObject.quote(warn) + ");");
                    }
                });
                fallbackToSystemInstaller(MainActivity.this, warn);
                return;
            }

            // ★ 版本预检：新包 versionCode 低于/等于当前已装版本时，静默安装必被 PMS 以"降级"拒绝
            // （回调表现为 status=-999）。不再直接拦截——降级包在系统安装器里可经用户确认安装，
            // 所以提示原因后直接回退系统安装器。
            int newVc = readApkVersionCode(apkFile);
            int curVc = -1;
            try {
                curVc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Throwable ignored) {}
            if (newVc > 0 && curVc > 0 && newVc <= curVc) {
                final String warn = (newVc < curVc)
                        ? ("服务器更新包版本较低：新版本号 " + newVc + " < 当前已装 " + curVc
                        + "，静默安装会被系统拒绝。已改用系统安装器，请在弹出界面确认降级安装（如系统不允许降级，请先卸载旧版）。")
                        : ("服务器更新包版本号与当前相同（" + newVc + "），可能是上传了旧包。已改用系统安装器，请在弹出界面确认。");
                Log.w(TAG, "version precheck: newVc=" + newVc + " curVc=" + curVc + " -> fallback to system installer");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        runJs("CarMediaUI.updateInstallFailed(" + JSONObject.quote(warn) + ");");
                    }
                });
                fallbackToSystemInstaller(MainActivity.this, warn);
                return;
            }

            // 静默安装：始终先开 ECARX 验证旁路（兼容原版/修改版 services.jar）
            // 修改版 jar：ApkAuth 本就放行；原版 jar：verifyApk 看到 sys.jsbd.apk_verify=1 跳过 auth
            // 注意：始终先开旁路再静默安装。若先试不带旁路的 install()，它提交会话即返回 true，
            // 但原版 jar 上实际安装会被 ApkAuth 拒绝，旁路永不触发（表现为“下载完没反应”）。
            boolean silentOk = SilentInstaller.installWithVerifyBypass(MainActivity.this, apkFile);
            if (silentOk) {
                // 标记安装进行中（供启动自检判断旁路残留）
                getSharedPreferences(SilentInstaller.PREFS, MODE_PRIVATE)
                        .edit().putBoolean(SilentInstaller.PREF_UPDATE_ACTIVE, true).apply();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        runJs("CarMediaUI.updateInstalling();");
                        // 旁路未生效时提醒（原版 services.jar 上安装会被 ApkAuth 拒绝，表现就是"下载完没反应"）
                        showToast(SilentInstaller.isBypassEffective()
                                ? "正在后台安装更新，完成后自动生效"
                                : "正在后台安装更新（系统验证旁路未生效，安装可能被拦截，请留意结果）");
                    }
                });
                // 超时兜底：60s 内未收到安装结果则复位 UI 并提示，避免永久卡在"正在安装"
                armInstallTimeout();
                return;
            }

            // 静默安装提交失败（如系统未授予安装权限/旁路未生效/会话写入失败）：提示原因后回退系统安装器
            Log.w(TAG, "silent install submit failed, fallback to ACTION_VIEW installer");
            final String failReason = SilentInstaller.sLastFailReason;
            final String fallbackTip = (failReason != null && !failReason.isEmpty())
                    ? ("静默安装不可用（" + failReason + "）")
                    : "静默安装不可用";
            fallbackToSystemInstaller(MainActivity.this, fallbackTip);
        } catch (Throwable t) {
            Log.w(TAG, "save apk failed: " + t.getMessage());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    runJs("CarMediaUI.updateInstallFailed(" + JSONObject.quote("保存更新包失败，请重试") + ");");
                }
            });
        }
    }

 /** 主线程弹更新提示框。notes 中句号「。」视为换行（\n）。 */
    private void showUpdateDialog(final String remoteVer, final String notes) {
        try {
            final String n = (notes == null) ? "" : notes.replace("。", "\n");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    runJs("CarMediaUI.showUpdate(" + JSONObject.quote(remoteVer) + "," + JSONObject.quote(n) + ");");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showUpdateDialog failed: " + t.getMessage());
        }
    }

 /** 点击版本号查看：直接弹「最新版本+更新内容」，不判断是否最新（viewOnly=true） */
    private void showUpdateViewOnly(final String remoteVer, final String notes) {
        try {
            final String n = (notes == null) ? "" : notes.replace("。", "\n");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    runJs("CarMediaUI.showUpdate(" + JSONObject.quote(remoteVer) + "," + JSONObject.quote(n) + ",true);");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showUpdateViewOnly failed: " + t.getMessage());
        }
    }
}
