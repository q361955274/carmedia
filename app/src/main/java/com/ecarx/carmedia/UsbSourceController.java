package com.ecarx.carmedia;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * USB 音源门面控制器。
 *
 * 单例，收敛 USB 侧所有逻辑：扫描、播放、状态聚合。
 * 任何状态变化（挂载/扫描/播放/切歌/进度）都会触发 UsbStateCallback.onStateChanged()，
 * CarMediaService 收到后更新 MediaSession 并通知 MainActivity 刷新 UI。
 */
public class UsbSourceController implements UsbMusicScanner.UsbMountCallback {

    private static final String TAG = "CarMedia";
    private static final String TAG_SUB = "UsbSourceController";
    private static final String PREFS_NAME = "carmedia_usb_prefs";
    private static final String KEY_PLAY_MODE = "usb_play_mode";
 /** 开机自动恢复：上次播放歌曲索引。 */
    private static final String KEY_LAST_INDEX = "usb_last_index";
 /** 歌曲收藏 SharedPreferences 文件名（key=歌曲路径，值 "1"=收藏）。 */
    private static final String PREFS_FAVS = "carmedia_usb_favs";

 /** USB 音源虚拟包名（用于日志/黑名单隔离，不参与真实 MediaSession）。 */
    public static final String SOURCE_PKG_USB = "com.ecarx.carmedia.usb";

    private static volatile UsbSourceController instance;

    private Context appContext;
    private UsbMusicScanner scanner;
    private UsbMusicPlayer player;
    private List<UsbMusicItem> musicList = new ArrayList<>();
    private volatile boolean activated = false;
    // 真实拔出事件标志——onUsbUnmounted 置位,onUsbMounted/activate 清除。
    // CarMediaService 用它复位 usbRouteArmed(替代 AdaptAPI 反射判断,防反射失败误复位导致切歌切到蓝牙)。
    private volatile boolean unmountedEventPending = false;
    private UsbStateCallback stateCallback;
    // 开机自动恢复：init 时启用；扫描完成且记忆了上次歌曲 → 置 pending，
    // CarMediaService 在 onStateChanged 里消费并自动播放；用户切走音源后禁用。
    private volatile boolean autoResumeEnabled = false;
    private volatile boolean autoResumePending = false;
    // 卡死防御：notifyStateChanged 100ms 合并（高频触发源存在时
    // onStateChanged 最多 10 次/s），尾部补发保证最后状态不丢。
    private final Handler notifyMergeHandler = new Handler(Looper.getMainLooper());
    private boolean notifyScheduled = false;
    private boolean notifyMergePending = false;

    private UsbSourceController() {
    }

    public static UsbSourceController getInstance() {
        if (instance == null) {
            synchronized (UsbSourceController.class) {
                if (instance == null) {
                    instance = new UsbSourceController();
                }
            }
        }
        return instance;
    }

 /** 由 CarMediaService.onCreate 调用一次。 */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.scanner = new UsbMusicScanner(appContext);
        this.player = new UsbMusicPlayer(appContext);
        this.player.setPlayMode(loadPlayMode());
        this.player.setCallback(new UsbMusicPlayer.PlayerCallback() {
            @Override
            public void onPlayStateChanged(int state) {
                // ★ (开机续播无记忆根因):原实现只 saveLastIndex 于 play(int)
                // (用户手动点歌),而 next()/prev()/autoAdvance()(按切歌键/自动下一首)内部
                // 调 UsbMusicPlayer.play 绕过了 UsbSourceController.play → prefs 记忆停留在
                // 上次"手动点"的索引。用户:播到第20首(自动/按键切过去的)开机却播第1首。
                // 修复:播放状态每次变化为 PLAYING(含自动切歌/next/prev/seek 重播)都同步
                // 保存当前索引,保证记忆总是最新播放位置。
                if (state == UsbMusicPlayer.STATE_PLAYING) {
                    saveLastIndex(player != null ? player.getCurrentIndex() : -1);
                }
                notifyStateChanged();
            }

            @Override
            public void onProgressChanged(long position, long duration) {
                // 进度也通知上层（用于 MediaSession 更新 position）
                notifyStateChanged();
            }

            @Override
            public void onCompletion() {
                notifyStateChanged();
            }

            @Override
            public void onError(String msg) {
                Log.w(TAG, TAG_SUB + ": player error: " + msg);
                notifyStateChanged();
            }
        });
        // 服务启动即注册 USB 插拔广播 + 后台预热扫描。
        // 插上 U 盘(即使未切到 USB tab)也会自动扫描,歌曲列表提前就绪,
        // 从机制上避免 首次切到 USB 时挂载点反射未就绪 -> 0 首歌 的问题。
        scanner.setUsbMountCallback(this);
        scanner.registerUsbBroadcastReceiver(appContext);
        // 开机自动恢复启用（仅本次服务启动有效，恢复后自动关闭）
        autoResumeEnabled = true;
        warmupScan();
        Log.i(TAG, TAG_SUB + ": init done, playMode=" + player.getPlayMode());
    }

 /** 激活 USB 音源：检查挂载 → 启动扫描 → 扫描完成自动设置播放列表。 */
    public void activate() {
        if (appContext == null) {
            Log.w(TAG, TAG_SUB + ": not init");
            return;
        }
        // 设置 USB 插拔回调 + 注册广播接收器（热插拔监听）
        scanner.setUsbMountCallback(this);
        scanner.registerUsbBroadcastReceiver(appContext);

        activated = true;
        unmountedEventPending = false; // 重新激活视为新挂载场景
        // 每次切换到USB都自动扫描一次（获取最新歌曲列表）
        // 不预先检查 isUsbMounted()：第一次调用时 AdaptAPI 反射可能超时返回 false，
        // 导致直接 return 不扫描；扫描器内部会处理路径不存在的情况（返回空列表）
        // 若预热扫描/上次扫描仍在进行,不重复启动,其 onScanFinished 会更新列表
        if (scanner.isScanning()) {
            Log.i(TAG, TAG_SUB + ": activate, scan already in progress, wait for it");
        } else {
            Log.i(TAG, TAG_SUB + ": activate, auto scanning...");
            scanner.startScan(null, buildScanCallback());
        }
        notifyStateChanged();
    }

 /** 后台预热扫描：无论 USB 音源是否激活，有外部存储就自动扫描，列表提前就绪。 */
    private void warmupScan() {
        if (scanner == null || scanner.isScanning()) {
            return;
        }
        Log.i(TAG, TAG_SUB + ": warmup scan (auto, usb not necessarily activated)");
        scanner.startScan(null, buildScanCallback());
    }

 /** 统一的扫描回调：结果更新 musicList + 播放列表 + 通知 UI。activate/rescan/warmup 共用。 */
    private UsbMusicScanner.ScanCallback buildScanCallback() {
        return new UsbMusicScanner.ScanCallback() {
            @Override
            public void onScanStarted() {
                notifyStateChanged();
            }

            @Override
            public void onScanProgress(int scanned, int total) {
                notifyStateChanged();
            }

            @Override
            public void onScanFinished(List<UsbMusicItem> list) {
                // File 兜底扫描已后台化——首屏可能先收到空结果,此时正在播放
                // 则保留当前歌单/索引,避免 setPlaylist(空) 把正在播放的歌曲索引清掉;
                // 后台 File 扫描完成后会二次 onScanFinished(非空) 正确更新列表。
                if ((list == null || list.isEmpty()) && player != null) {
                    int st = player.getPlayState();
                    if (st == UsbMusicPlayer.STATE_PLAYING || st == UsbMusicPlayer.STATE_PREPARING) {
                        Log.i(TAG, TAG_SUB + ": scan finished empty while playing, keep current playlist (bg fallback)");
                        notifyStateChanged();
                        return;
                    }
                }
                musicList = list != null ? list : new ArrayList<UsbMusicItem>();
                player.setPlaylist(musicList);
                Log.i(TAG, TAG_SUB + ": scan finished, songs=" + musicList.size());
                // 开机自动恢复——服务启动后的扫描完成且记忆了上次歌曲
                // 且列表非空 → 置 pending，CarMediaService 在 onStateChanged 里消费并播放。
                // (用户需求):记忆索引 -1(从未播放过 USB)也置 pending——
                // 开机自动播第一首。原条件 getSavedLastIndex() >= 0 硬性要求"有记忆才自动播",
                // 刚装/从没播过 USB 时开机不自动播放;消费端(CarMediaService)会把 -1 落回 0。
                if (autoResumeEnabled && !musicList.isEmpty()) {
                    autoResumePending = true;
                    Log.i(TAG, TAG_SUB + ": auto-resume pending, savedIndex=" + getSavedLastIndex());
                }
                // 修复:用户主动点 USB tab 激活(activated=true)且播放器空闲时
                // 自动播放——记忆歌曲优先,无记忆播第一首。之前激活只扫描不播放(state=0),
                // 界面切到 USB 面板后主歌词区被隐藏且无任何歌词数据(用户反复反馈"切 USB 没歌词")。
                // 开机预热扫描(activated=false)不受影响,不抢播。
                if (activated && player != null && !musicList.isEmpty()) {
                    int st = player.getPlayState();
                    if (st == UsbMusicPlayer.STATE_IDLE) {
                        int idx = getSavedLastIndex();
                        if (idx < 0 || idx >= musicList.size()) idx = 0;
                        Log.i(TAG, TAG_SUB + ": activated scan finished, auto play index=" + idx);
                        player.play(idx);
                    }
                }
                notifyStateChanged();
            }

            @Override
            public void onScanError(String msg) {
                Log.w(TAG, TAG_SUB + ": scan error: " + msg);
                notifyStateChanged();
            }
        };
    }

 /** 停用：暂停播放，释放资源（但不销毁单例，下次 activate 可复用）。 */
    public void deactivate() {
        activated = false;
        // 用户手动切走音源后不再自动恢复
        autoResumeEnabled = false;
        autoResumePending = false;
        // 不再反注册 USB 广播接收器,保持监听——未激活时插入 U 盘仍自动预热扫描,
        // 用户切到 USB 时列表已就绪,不再出现 0 首歌。
        // 加固:每步独立 try/catch——任何一步失败都不能中断后续步骤,
        // 否则 CarMediaService.deactivateUsbSource 抛异常 → MainActivity 的 pushAll/
        // JS 的 selectController 不执行 → 蓝牙 tab 点击"不弹窗、UI 不更新"。
        try {
            if (scanner != null && scanner.isScanning()) {
                scanner.stopScan();
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": deactivate stopScan err: " + t.getMessage());
        }
        try {
            if (player != null) {
                player.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": deactivate player.release err: " + t.getMessage());
        }
        Log.i(TAG, TAG_SUB + ": deactivated");
        try {
            notifyStateChanged();
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": deactivate notify err: " + t.getMessage());
        }
    }

    public boolean isActivated() {
        return activated;
    }

 /** 是否已收到真实的 USB 拔出事件(事件驱动,不依赖 AdaptAPI 反射)。 */
    public boolean isUnmountedEventPending() {
        return unmountedEventPending;
    }

    public boolean isUsbMounted() {
        return scanner != null && scanner.isUsbMounted();
    }

    public List<UsbMusicItem> getMusicList() {
        return musicList;
    }

    public int getMusicCount() {
        return musicList == null ? 0 : musicList.size();
    }

    // ================= 开机自动恢复 =================

 /** 读取上次播放歌曲索引（无记忆返回 -1）。 */
    public int getSavedLastIndex() {
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return sp.getInt(KEY_LAST_INDEX, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private void saveLastIndex(int index) {
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_LAST_INDEX, index).apply();
        } catch (Throwable ignored) {
        }
    }

 /** 消费自动恢复标记（一次性：返回当前值并清除，避免重复触发）。 */
    public boolean consumeAutoResumePending() {
        boolean p = autoResumePending;
        autoResumePending = false;
        // 已消费即本次开机恢复完成，此后不再自动恢复（防止 activate 触发的二次扫描再触发）
        if (p) {
            autoResumeEnabled = false;
        }
        return p;
    }

 /** (Bug#72D):USB 开机自动播放由设置项控制(默认开)。
 * 服务启动时按 prefs 下发;关闭时清 pending,防止本次开机剩余窗口继续触发。 */
    public void setAutoResumeEnabled(boolean enabled) {
        autoResumeEnabled = enabled;
        if (!enabled) {
            autoResumePending = false;
        }
        Log.i(TAG, TAG_SUB + ": auto-resume enabled=" + enabled);
    }

    public void play(int index) {
        if (player != null) {
            player.play(index);
            // 记忆上次播放歌曲（开机自动恢复用）
            saveLastIndex(index);
        }
    }

    // ================= 收藏序列播放模式 =================

 /** 设置收藏序列播放模式:收藏 tab 点歌后 next/prev/自动下一首按收藏列表顺序切。
 * 收藏序列=musicList 中 isFav 的下标升序集合。 */
    public void setFavMode(boolean fav) {
        if (player == null) return;
        java.util.List<Integer> idxs = new java.util.ArrayList<>();
        if (fav && musicList != null) {
            for (int i = 0; i < musicList.size(); i++) {
                UsbMusicItem it = musicList.get(i);
                if (it != null && it.path != null && isFav(it.path)) idxs.add(i);
            }
        }
        player.setFavMode(fav, idxs);
    }

    public boolean isFavMode() {
        return player != null && player.isFavMode();
    }

    public void playPause() {
        if (player != null) player.playPause();
    }

 /** (Bug#72C):暂停 USB 播放(仅实际播放时生效,幂等安全)。
 * 切离 USB 音源时调用,避免"目标源 + USB"双音源。 */
    public void pause() {
        if (player != null) player.pause();
    }

 /** (Bug#72E):切离 USB 专用暂停——暂停并禁止焦点恢复自动续播
 * (清 pausedByFocusLoss,防 focus GAIN 后 handleAudioFocusChange 自动 resume
 * 把 USB 重新拉起来 → re-arm → 按键被 USB 抢走)。 */
    public void pauseForSwitch() {
        if (player != null) player.pauseForSwitch();
    }

    public void next() {
        if (player != null) player.next();
    }

    public void prev() {
        if (player != null) player.prev();
    }

    public void seekTo(long pos) {
        if (player != null) player.seekTo(pos);
    }

    public UsbMusicItem getCurrentItem() {
        return player == null ? null : player.getCurrentItem();
    }

    public int getCurrentIndex() {
        return player == null ? -1 : player.getCurrentIndex();
    }

    public int getPlayState() {
        return player == null ? UsbMusicPlayer.STATE_IDLE : player.getPlayState();
    }

    public long getPosition() {
        return player == null ? 0 : player.getPosition();
    }

    public long getDuration() {
        return player == null ? 0 : player.getDuration();
    }

    public int getPlayMode() {
        return player == null ? UsbMusicPlayer.MODE_SEQUENCE : player.getPlayMode();
    }

    public void setPlayMode(int mode) {
        if (player != null) {
            player.setPlayMode(mode);
            savePlayMode(mode);
            notifyStateChanged();
        }
    }

 /** 重新扫描。 */
    public void rescan() {
        if (!isUsbMounted()) {
            Log.w(TAG, TAG_SUB + ": rescan aborted, usb not mounted");
            notifyStateChanged();
            return;
        }
        Log.i(TAG, TAG_SUB + ": rescan...");
        // 用户主动 rescan 强制绕过 SQLite 缓存,拿最新列表
        scanner.forceRescan(buildScanCallback());
    }

    public boolean isScanning() {
        return scanner != null && scanner.isScanning();
    }

    public int[] getScanProgress() {
        if (scanner == null) return new int[] { 0, 0 };
        return scanner.getScanProgress();
    }

    public void setUsbStateCallback(UsbStateCallback callback) {
        this.stateCallback = callback;
    }

 /**
 * 设备信息 JSON：{name, mounted, songCount, capacity}。
 * capacity 由 UsbMusicScanner.getDeviceCapacity() 读取第一个挂载点 StatFs 得到。
 */
    public JSONObject getDeviceInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", "USB");
            o.put("mounted", isUsbMounted());
            o.put("songCount", getMusicCount());
            o.put("capacity", scanner == null ? "" : scanner.getDeviceCapacity());
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ================= USB 歌曲收藏 =================

 /**
 * 判断指定路径歌曲是否已收藏。
 * 从 SharedPreferences(carmedia_usb_favs) 读取，key=path，存在且值为 "1" 返回 true。
 */
    public boolean isFav(String path) {
        if (path == null || path.isEmpty() || appContext == null) return false;
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_FAVS, Context.MODE_PRIVATE);
            return "1".equals(sp.getString(path, ""));
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": isFav error: " + t.getMessage());
            return false;
        }
    }

 /**
 * 切换指定路径歌曲的收藏状态，返回切换后的状态（true=已收藏）。
 */
    public boolean toggleFav(String path) {
        if (path == null || path.isEmpty() || appContext == null) return false;
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_FAVS, Context.MODE_PRIVATE);
            boolean now = !"1".equals(sp.getString(path, ""));
            sp.edit().putString(path, now ? "1" : "0").apply();
            Log.i(TAG, TAG_SUB + ": toggleFav path=" + path + " -> " + now);
            return now;
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": toggleFav error: " + t.getMessage());
            return false;
        }
    }

 /**
 * 批量读取全部收藏路径集合（一次 SharedPreferences 读全部）。
 * 供列表 JSON 构建使用，避免 289 首歌每首一次 getSharedPreferences+getString。
 */
    public java.util.Set<String> getFavPaths() {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (appContext == null) return out;
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_FAVS, Context.MODE_PRIVATE);
            java.util.Map<String, ?> all = sp.getAll();
            if (all != null) {
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    if ("1".equals(e.getValue())) out.add(e.getKey());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": getFavPaths error: " + t.getMessage());
        }
        return out;
    }

 /** 当前播放歌曲是否已收藏（无当前歌曲返回 false）。 */
    public boolean isCurrentFav() {
        UsbMusicItem item = getCurrentItem();
        if (item == null || item.path == null) return false;
        return isFav(item.path);
    }

 /** Service onDestroy 调用。 */
    public void release() {
        // 反注册 USB 插拔广播接收器
        if (scanner != null) {
            scanner.unregisterUsbBroadcastReceiver();
            scanner.setUsbMountCallback(null);
        }
        if (scanner != null && scanner.isScanning()) {
            scanner.stopScan();
        }
        if (player != null) {
            player.release();
        }
        musicList = new ArrayList<>();
        activated = false;
        stateCallback = null;
        Log.i(TAG, TAG_SUB + ": released");
    }

    // ================= USB 插拔回调（UsbMountCallback 实现） =================

 /**
 * USB 插入挂载：自动触发扫描。
 */
    @Override
    public void onUsbMounted(String path) {
        unmountedEventPending = false; // 新盘插入,清除拔出事件标志
        Log.i(TAG, TAG_SUB + ": onUsbMounted path=" + path + " activated=" + activated);
        // 去掉 activated 限制——未激活时插入 U 盘也自动扫描(预热),
        // 且不依赖 isUsbMounted() 反射判断(首次反射可能失败误判未挂载,把扫描挡掉),
        // 广播已到即已挂载,直接启动扫描,扫描器内部 resolveScanRoots + 1.5s 重试兜底
        // 插入强制重扫(绕过缓存)——拔出时缓存已清,插入重扫拿新盘数据
        if (scanner != null && !scanner.isScanning()) {
            scanner.forceRescan(buildScanCallback());
        }
        // 插入重新启用自动恢复——服务启动置 true 但首次消费后关闭,
        // 若不在插入时重新启用,拔插 U 盘后不再"识别有歌就自动播放上次记忆"。
        autoResumeEnabled = true;
        notifyStateChanged();
    }

 /**
 * USB 拔出卸载：自动暂停播放、清空播放列表、通知 UI 更新。
 */
    @Override
    public void onUsbUnmounted(String path) {
        unmountedEventPending = true; // 真实拔出事件(事件驱动,供 armed 复位)
        Log.i(TAG, TAG_SUB + ": onUsbUnmounted path=" + path + " activated=" + activated);
        // 加固:每步独立 try/catch,状态通知放最后兜底——任何一步失败都不能
        // 吞掉拔出事件,否则 CarMediaService.onStateChanged 不触发 → usbRouteArmed 残留
        // true → UI 锁 USB 态(蓝牙页面显示 USB 旧歌/播放走 USB 分支无反应)。
        try {
            // 停止正在进行的扫描
            if (scanner != null && scanner.isScanning()) {
                scanner.stopScan();
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": onUsbUnmounted stopScan err: " + t.getMessage());
        }
        try {
            // 释放播放器资源（release 内部会暂停、停止进度、释放焦点）
            if (player != null) {
                player.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": onUsbUnmounted player.release err: " + t.getMessage());
        }
        try {
            // 拔出即清 SQLite 缓存——换 U 盘/内容变化时缓存必须失效,下次插入重扫
            if (scanner != null) {
                scanner.clearCache();
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": onUsbUnmounted clearCache err: " + t.getMessage());
        }
        // 清空播放列表
        musicList = new ArrayList<>();
        try {
            notifyStateChanged();
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": onUsbUnmounted notify err: " + t.getMessage());
        }
    }

 /**
 * USB 媒体扫描完成：刷新列表。
 */
    @Override
    public void onUsbScanFinished(String path) {
        Log.i(TAG, TAG_SUB + ": onUsbScanFinished path=" + path + " activated=" + activated);
        // 去掉 activated 限制——系统媒体库扫描完成时无论是否激活都重新扫描(预热),
        // 直接启动扫描,不依赖 isUsbMounted 反射判断
        // 系统扫描完成说明 MediaStore 有新数据,强制重扫(绕过缓存)拿最新列表
        if (scanner != null && !scanner.isScanning()) {
            scanner.forceRescan(buildScanCallback());
        }
    }

    // ================= 内部 =================

    private void notifyStateChanged() {
        final UsbStateCallback cb = stateCallback;
        if (cb == null) return;
        // 100ms 窗口合并：窗口内多次通知只执行一次，窗口结束若有新变化补发一次。
        if (notifyScheduled) {
            notifyMergePending = true;
            return;
        }
        notifyScheduled = true;
        notifyMergeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                notifyScheduled = false;
                try {
                    cb.onStateChanged();
                } catch (Throwable t) {
                    Log.w(TAG, TAG_SUB + ": state callback error: " + t.getMessage());
                }
                if (notifyMergePending) {
                    notifyMergePending = false;
                    notifyStateChanged();
                }
            }
        }, 100L);
    }

    private int loadPlayMode() {
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return sp.getInt(KEY_PLAY_MODE, UsbMusicPlayer.MODE_SEQUENCE);
        } catch (Throwable t) {
            return UsbMusicPlayer.MODE_SEQUENCE;
        }
    }

    private void savePlayMode(int mode) {
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putInt(KEY_PLAY_MODE, mode).apply();
        } catch (Throwable ignored) {
        }
    }

 /**
 * USB 状态变化回调。任何状态变化（挂载/扫描/播放/切歌/进度）都会触发。
 */
    public interface UsbStateCallback {
        void onStateChanged();
    }
}
