package com.ecarx.carmedia;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * USB 本地音乐扫描器。
 *
 * 优先级：
 * 1. MediaStore 查询（EXTERNAL_CONTENT_URI），过滤 _data 位于已知 USB 挂载路径下；
 * 2. MediaStore 查不到时，File 递归遍历 USB 挂载目录，MediaMetadataRetriever 读元数据。
 *
 * 扫描在子线程执行，所有 ScanCallback 回调切到主线程 Handler。
 */
public class UsbMusicScanner {

    private static final String TAG = "CarMedia";
    private static final String TAG_SUB = "UsbMusicScanner";

 /** 已知 USB 挂载根目录（车机厂商各异，覆盖主流路径 + 模拟器调试路径）。 */
    private static final String[] KNOWN_USB_ROOTS = new String[] {
            "/storage/usbotg",
            "/storage/usb-otg",
            "/mnt/usb",
            "/storage/UDiskA",
            "/storage/UDiskB",
            // 模拟器调试路径（真实车机上不存在，自动跳过）
            "/sdcard/USB",
            "/storage/emulated/0/USB",
            "/data/local/tmp/USB",
            "/mnt/shared"
    };

 /** File 兜底扫描支持的扩展名。 */
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            "mp3", "flac", "wav", "aac", "ogg", "m4a", "amr"
    ));

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean scanning = false;
 /** 扫描开始时间戳（自愈判断用）。 */
    private volatile long scanStartedAt = 0L;
 /** 最近一次成功保存缓存的 key（挂载路径解析波动时兜底命中用）。 */
    private volatile String lastScanKey = null;
    private volatile boolean stopRequested = false;
    private int scannedCount = 0;
    private int totalCount = 0;
 /** 当前扫描任务的回调引用（进度上报用）。 */
    private volatile ScanCallback currentCallback;

    // 卡死修复：挂载路径/容量缓存。
    // USB 播放时 pushAll 每秒调用 isUsbMounted()->getUsbMountPaths()，每次做 5 次
    // AdaptAPI 反射（Policy.create/getStoragePolicy/getVolumeFullPath x2/getUsbHostCount，
    // 可能触发 binder 往返）+ StatFs，车机主线程被拖垮。缓存后每秒推送不再重复反射。
    private volatile List<String> cachedMountPaths;
    private volatile long mountPathCacheTime = 0L;
    private static final long MOUNT_PATH_CACHE_MS = 3000L;
 /** 卡死加固:缓存过期但有旧值时,主线程不重复做 5 次 AdaptAPI 反射,
 * 返回旧值 + 后台线程刷新(反射是 binder 往返,车机 IPC 拥堵时可达数百 ms-3s)。 */
    private final Object mountPathRefreshLock = new Object();
    private boolean mountPathRefreshing = false;

    private volatile String cachedCapacity;
    private volatile long capacityCacheTime = 0L;
    private static final long CAPACITY_CACHE_MS = 5000L;

 /** USB 挂载/拔出/扫描完成回调引用（由 UsbSourceController 设置）。 */
    private volatile UsbMountCallback usbMountCallback;
 /** USB 插拔广播接收器实例。 */
    private BroadcastReceiver usbBroadcastReceiver;
 /** 广播是否已注册（幂等标记，防止重复注册/反注册崩溃）。 */
    private volatile boolean usbReceiverRegistered = false;

    // ================= USB 读取提速（A/B/C） =================
    // A: MediaStore 查空后不立即 File 兜底——等系统 MediaScanner 完成(MEDIA_SCANNER_FINISHED)
    // 或超时重查,最多 MEDIASTORE_RETRY_TIMES 次。多媒体(XCMedia2)同法:等扫描完成再查库。
 /** 最近一次收到 MEDIA_SCANNER_FINISHED 的时间（elapsedRealtime，主线程写、扫描线程读）。 */
    private volatile long lastScannerFinishedTime = 0L;
    private static final int MEDIASTORE_RETRY_TIMES = 8;
    // 扫描提速:重试次数 2->8(等系统 MediaScanner 最多约 12s,收到
    // MEDIA_SCANNER_FINISHED 提前返回)。之前只等 3s 就放弃,系统扫描稍慢就误走
    // File 递归兜底(逐文件解码元数据,20-90s)——比多媒体慢很多的直接原因之一。
    private static final long SCANNER_WAIT_MS = 1500L;
    // 防卡死：目录遍历阶段单挂载点最长 20s（U 盘 IO 挂起/超大目录不阻塞整体）
    private static final long WALK_TIMEOUT_MS = 20000L;
    // 防卡死：文件元数据阶段总超时 90s——个别损坏文件 MediaMetadataRetriever
    // 阻塞会占满线程池导致后续任务排队级联卡死,到点放弃剩余,返回已收集部分,绝不永久卡。
    private static final long FILE_SCAN_PHASE_TIMEOUT_MS = 90000L;
    // 防超大目录爆炸：文件扫描上限与遍历深度
    private static final int MAX_SCAN_FILES = 8000;
    private static final int MAX_SCAN_DEPTH = 10;
    // scanning 标志可能因扫描线程异常/被系统回收而卡 true,
    // 导致后续所有 forceRescan 被"already scanning"挡掉——插入/切源从此不再扫描。
    // 自愈:scanning 卡住超过该阈值后强制复位重扫。真车大 U 盘扫描约 20s,取 90s 兜底。
    private static final long SCAN_STUCK_RESET_MS = 90000L;

    // C: SQLite 缓存（首次扫描结果落库,切源命中免重扫;拔盘清缓存;rescan/插入/系统扫描完成强制刷新）
    private static final String CACHE_DB_NAME = "carmedia_usb_cache.db";
    private static final String CACHE_TABLE = "usb_audio_cache";
    private SQLiteDatabase usbCacheDb;            // 懒加载,进程级复用,绝不 close
    private volatile boolean bypassCache = false; // forceRescan() 置位,本次扫描绕过缓存

    public UsbMusicScanner(Context context) {
        this.appContext = context.getApplicationContext();
    }

 /**
 * 返回 USB 挂载路径列表，仅包含真实存在的路径。
 *
 * 优先通过 AdaptAPI（IStoragePolicy 反射）获取车机真实挂载点；
 * 反射失败或返回空时，回退到硬编码 KNOWN_USB_ROOTS。
 */
    public List<String> getUsbMountPaths() {
        // 卡死修复：3s 缓存，避免每秒推送反复做 5 次 AdaptAPI 反射。
        long now = SystemClock.elapsedRealtime();
        if (cachedMountPaths != null && now - mountPathCacheTime < MOUNT_PATH_CACHE_MS) {
            return cachedMountPaths;
        }
        if (cachedMountPaths != null) {
            // 卡死加固:缓存过期但有旧值 → 主线程不反射,返回旧值+
            // 后台刷新。挂载状态 3s 内基本不变;插拔/切源会走 forceRescan 重新解析,
            // 后台延迟刷新无实际影响,但主线程绝不会再被 AdaptAPI binder 拖住。
            refreshMountPathsAsync();
            return cachedMountPaths;
        }
        List<String> fresh = resolveMountPathsNow();
        cachedMountPaths = fresh;
        mountPathCacheTime = now;
        return fresh;
    }

 /** 缓存过期时后台刷新挂载路径(防抖,主线程只触发一次)。 */
    private void refreshMountPathsAsync() {
        synchronized (mountPathRefreshLock) {
            if (mountPathRefreshing) {
                return;
            }
            mountPathRefreshing = true;
        }
        final Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> fresh = resolveMountPathsNow();
                    synchronized (mountPathRefreshLock) {
                        cachedMountPaths = fresh;
                        mountPathCacheTime = SystemClock.elapsedRealtime();
                        mountPathRefreshing = false;
                    }
                } catch (Throwable e) {
                    synchronized (mountPathRefreshLock) {
                        mountPathRefreshing = false;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

 /** 实际解析挂载路径（原 getUsbMountPaths 逻辑，被缓存包装）。 */
    private List<String> resolveMountPathsNow() {
        // 1) 优先 AdaptAPI 反射获取
        List<String> adaptPaths = getUsbMountPathsViaAdaptApi();
        if (!adaptPaths.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (String p : adaptPaths) {
                if (p != null && !p.isEmpty()) {
                    File f = new File(p);
                    if (f.exists() && f.isDirectory()) {
                        result.add(p);
                    }
                }
            }
            if (!result.isEmpty()) {
                return dedupByCanonicalPath(result);
            }
        }
        // 2) 回退：硬编码已知路径
        List<String> result = new ArrayList<>();
        for (String root : KNOWN_USB_ROOTS) {
            File f = new File(root);
            if (f.exists() && f.isDirectory()) {
                result.add(root);
            }
        }
        // fix: /sdcard and /storage/emulated/0 point to same dir,
        // without dedup same files scanned twice (521 -> 1043). Use canonical path dedup.
        return dedupByCanonicalPath(result);
    }

 /**
 * Deduplicate by canonical path to eliminate duplicate mount points caused by symlinks
 * (e.g. /sdcard/USB vs /storage/emulated/0/USB). Keeps first occurrence of original path.
 */
    private static List<String> dedupByCanonicalPath(List<String> paths) {
        if (paths == null || paths.size() <= 1) {
            return paths;
        }
        List<String> out = new ArrayList<>(paths.size());
        Set<String> seen = new HashSet<>();
        for (String p : paths) {
            try {
                String canonical = new File(p).getCanonicalPath();
                if (seen.add(canonical)) {
                    out.add(p);
                } else {
                    Log.i(TAG, TAG_SUB + ": dedup mount path (same canonical): " + p);
                }
            } catch (Throwable t) {
                if (seen.add(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

 /** 任一 USB 挂载点存在即认为 USB 已挂载。 */
    public boolean isUsbMounted() {
        return !getUsbMountPaths().isEmpty();
    }

    public boolean isScanning() {
        return scanning;
    }

    public int[] getScanProgress() {
        return new int[] { scannedCount, totalCount };
    }

 /**
 * 读取第一个 USB 挂载点的容量信息，返回 "已用 X.X GB / 总 Y.Y GB"，失败返回空串。
 *
 * 总容量 = blockCount * blockSize，可用 = availableBlocks * blockSize，已用 = 总 - 可用。
 * 使用 Long 变体避免大磁盘（>2TB）int 溢出。
 */
    public String getDeviceCapacity() {
        try {
            // 卡死修复：容量 5s 缓存（StatFs 磁盘 IO 不进每秒推送）。
            long capNow = SystemClock.elapsedRealtime();
            if (cachedCapacity != null && capNow - capacityCacheTime < CAPACITY_CACHE_MS) {
                return cachedCapacity;
            }
            List<String> paths = getUsbMountPaths();
            if (paths == null || paths.isEmpty()) {
                return "";
            }
            String path = paths.get(0);
            if (path == null || path.isEmpty()) {
                return "";
            }
            StatFs stat = new StatFs(path);
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availBlocks = stat.getAvailableBlocksLong();
            long totalBytes = totalBlocks * blockSize;
            long usedBytes = (totalBlocks - availBlocks) * blockSize;
            if (totalBytes <= 0) {
                return "";
            }
            double usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0);
            double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
            String cap = String.format("已用 %.1f GB / 总 %.1f GB", usedGb, totalGb);
            cachedCapacity = cap;
            capacityCacheTime = capNow;
            return cap;
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": getDeviceCapacity 失败: " + t.getMessage());
            return "";
        }
    }

    public void stopScan() {
        stopRequested = true;
    }

    // ================= AdaptAPI IStoragePolicy 反射获取挂载路径 =================
    // CarMedia 无 AdaptAPI 编译依赖，全部通过 Class.forName + Method.invoke 反射调用。
    // 调用链（参考 XCMedia2 USBAdapterApiManager）：
    // Policy.create(Context) → Policy 对象
    // policy.getStoragePolicy() → IStoragePolicy 对象
    // iStoragePolicy.getVolumeFullPath(1) → USB1 挂载路径
    // iStoragePolicy.getVolumeFullPath(2) → USB2 挂载路径
    // iStoragePolicy.getUsbHostCount() → USB Host 数量（==2 表示支持双 USB）
    // 任何环节失败均静默降级，返回空列表由上层回退硬编码路径。

 /**
 * 通过 AdaptAPI 反射获取 USB1/USB2 挂载路径列表。
 * 获取失败时返回空列表，由调用方回退硬编码路径。
 */
    private List<String> getUsbMountPathsViaAdaptApi() {
        List<String> result = new ArrayList<>();
        Object storagePolicy = null;
        try {
            // 1) Policy.create(context)
            Object policy = invokeStaticReflect(
                    "com.ecarx.xui.adaptapi.policy.Policy", "create",
                    new Class[]{Context.class}, appContext);
            if (policy == null) {
                Log.w(TAG, TAG_SUB + ": AdaptAPI Policy.create 返回 null");
                return result;
            }
            // 2) policy.getStoragePolicy()
            storagePolicy = invokeReflect(policy, "getStoragePolicy", null);
            if (storagePolicy == null) {
                Log.w(TAG, TAG_SUB + ": AdaptAPI getStoragePolicy 返回 null");
                return result;
            }
            // 3) USB1: getVolumeFullPath(1)
            String path1 = tryGetVolumeFullPath(storagePolicy, 1);
            if (path1 != null) {
                result.add(path1);
            }
            // 4) 判断是否支持 USB2：反射 getUsbHostCount()
            boolean hasUsb2 = false;
            try {
                Object hostCount = invokeReflect(storagePolicy, "getUsbHostCount", null);
                if (hostCount instanceof Integer) {
                    hasUsb2 = ((Integer) hostCount).intValue() == 2;
                }
            } catch (Throwable t) {
                Log.w(TAG, TAG_SUB + ": getUsbHostCount 反射失败: " + t.getMessage());
            }
            // 5) USB2: getVolumeFullPath(2)
            if (hasUsb2) {
                String path2 = tryGetVolumeFullPath(storagePolicy, 2);
                if (path2 != null) {
                    result.add(path2);
                }
            }
            Log.i(TAG, TAG_SUB + ": AdaptAPI 挂载路径: " + result);
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": getUsbMountPathsViaAdaptApi 异常: " + t.getMessage());
            result.clear();
        }
        return result;
    }

 /**
 * 反射调用 IStoragePolicy.getVolumeFullPath(int)，成功返回路径字符串，失败返回 null。
 */
    private String tryGetVolumeFullPath(Object storagePolicy, int volumeId) {
        try {
            Object v = invokeReflect(storagePolicy, "getVolumeFullPath",
                    new Class[]{int.class}, volumeId);
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": getVolumeFullPath(" + volumeId + ") 失败: " + t.getMessage());
        }
        return null;
    }

 /**
 * 反射调用静态方法（参考 MainActivity.invokeStatic）。
 */
    private static Object invokeStaticReflect(String cls, String method,
                                              Class<?>[] types, Object... args) throws Exception {
        return Class.forName(cls).getMethod(method, types).invoke(null, args);
    }

 /**
 * 反射调用实例方法（参考 MainActivity.invoke）。
 * types 为 null 时按方法名遍历所有 public 方法匹配。
 */
    private static Object invokeReflect(Object obj, String method,
                                        Class<?>[] types, Object... args) throws Exception {
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

 /**
 * 启动扫描。usbPath 为空/null 时自动在所有已知 USB 挂载点下扫描。
 */
    public void startScan(final String usbPath, final ScanCallback callback) {
        if (scanning) {
            // 自愈:scanning 卡住超过阈值(扫描线程异常/被回收)则强制复位重扫
            if (scanStartedAt > 0L
                    && SystemClock.elapsedRealtime() - scanStartedAt > SCAN_STUCK_RESET_MS) {
                Log.w(TAG, TAG_SUB + ": scanning stuck > "
                        + (SCAN_STUCK_RESET_MS / 1000L) + "s, force reset and rescan");
                scanning = false;
            } else {
                Log.w(TAG, TAG_SUB + ": already scanning, ignore");
                if (callback != null) {
                    callback.onScanError("already scanning");
                }
                return;
            }
        }
        // forceRescan() 置位后在此取走：本次扫描绕过 SQLite 缓存强制刷新
        final boolean bypass = bypassCache;
        bypassCache = false;
        scanning = true;
        scanStartedAt = SystemClock.elapsedRealtime();
        stopRequested = false;
        scannedCount = 0;
        totalCount = 0;
        currentCallback = callback;
        // 卡死修复：开始新扫描时失效挂载路径/容量缓存，确保用最新路径。
        cachedMountPaths = null;
        cachedCapacity = null;

        notifyStarted(callback);

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<UsbMusicItem> result = new ArrayList<>();
                boolean cacheHit = false;
                try {
                    // 1) 优先 MediaStore（B：volume 级查询 + DATA LIKE 兜底合并）
                    List<UsbMusicItem> fromMediaStore = queryFromMediaStore();
                    if (fromMediaStore != null && !fromMediaStore.isEmpty()) {
                        result.addAll(fromMediaStore);
                        Log.i(TAG, TAG_SUB + ": MediaStore found " + result.size() + " usb tracks");
                    } else {
                        // C: SQLite 缓存命中——快路径：先给缓存(秒出列表,不卡 UI)。
                        // 改为无条件命中(forceRescan 也先给缓存),插入/切源瞬间列表立即可用;
                        // 命中且为强制重扫时,下方启动后台异步重扫更新缓存(新盘数据后台到位)。
                        // (真车反馈):key 归一化 + lastScanKey 兜底 + 空缓存也生效。
                        List<UsbMusicItem> cached = loadCacheWithFallback();
                        if (cached != null) {
                            result.addAll(cached);
                            cacheHit = true;
                            Log.i(TAG, TAG_SUB + ": cache hit (fast path), songs=" + result.size());
                            // 缓存来自旧 key(挂载路径解析波动):同步复制到当前 key,下次直接命中
                            String curKey = buildScanKey();
                            if (lastScanKey != null && !curKey.equals(lastScanKey)) {
                                saveCache(curKey, result);
                                lastScanKey = curKey;
                            }
                        }
                        // A: MediaStore 查空不立即 File 兜底——等系统扫描完成(MEDIA_SCANNER_FINISHED)
                        // 或超时重查最多 2 次。系统 MediaScanner(native) 扫完 USB 后 MediaStore 才有数据,
                        // 切源瞬间常为空;多媒体(XCMedia2)就是靠"等扫描完成再查库"做到几秒出列表。
                        if (result.isEmpty() && !stopRequested) {
                            for (int retry = 0; retry < MEDIASTORE_RETRY_TIMES && !stopRequested; retry++) {
                                waitForScannerFinished(SCANNER_WAIT_MS);
                                if (stopRequested) break;
                                fromMediaStore = queryFromMediaStore();
                                if (fromMediaStore != null && !fromMediaStore.isEmpty()) {
                                    result.addAll(fromMediaStore);
                                    Log.i(TAG, TAG_SUB + ": MediaStore hit after retry " + (retry + 1)
                                            + ", songs=" + result.size());
                                    break;
                                }
                                Log.w(TAG, TAG_SUB + ": MediaStore still empty after retry " + (retry + 1));
                            }
                        }
                        // 2) File 递归兜底（仍空才走：无 MediaStore 且无缓存且重试无效）
                        // 改为后台执行——首屏立即返回空列表,不再同步遍历大 U 盘
                        // (真车 File 遍历 + MediaMetadataRetriever 逐文件读元数据可达 20-90s,
                        // 主扫描线程同步等待期间扫描标志不释放,界面一直"扫描中"卡住)。
                        // 后台扫完会写缓存并二次 notifyFinished 刷新列表(回调幂等,播放不中断)。
                        if (result.isEmpty() && !stopRequested) {
                            List<String> roots = resolveScanRoots(usbPath);
                            if (roots.isEmpty() && !stopRequested) {
                                // 修复：第一次切到 USB 时不扫描（空列表），切蓝牙再切回才扫到。
                                // 根因：首次 activate() 时 AdaptAPI 反射（Policy.create）可能未就绪返回空，
                                // getUsbMountPaths() 回退硬编码 KNOWN_USB_ROOTS 也找不到厂商自定义挂载点，
                                // resolveScanRoots 得到空列表 → scanByFile totalCount=0 → 返回空列表。
                                // 延迟 1.5s 重新解析挂载点再扫一次（第二次反射通常已就绪）；仍拿不到路径
                                // 则按空列表返回（不阻塞 UI，扫描完成后 songCount 变化会触发 UI 拉列表）。
                                Log.w(TAG, TAG_SUB + ": no mount roots on first pass, retry after 1.5s (AdaptAPI init)");
                                try {
                                    Thread.sleep(1500L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                if (!stopRequested) {
                                    roots = resolveScanRoots(usbPath);
                                }
                            }
                            Log.i(TAG, TAG_SUB + ": MediaStore empty, fallback file scan in background, roots=" + roots);
                            if (!roots.isEmpty() && !stopRequested) {
                                startFileFallbackScan(roots, callback);
                            }
                        }
                        // 缓存命中且为强制重扫(forceRescan)→后台异步重扫更新缓存。
                        // 不阻塞首屏(先给缓存秒出),换盘/新数据后台到位后二次通知刷新列表。
                        if (cacheHit && bypass && !stopRequested) {
                            startBackgroundRefresh(usbPath, callback);
                        }
                    }
                    // C: 写入缓存（新扫描结果落库；缓存命中且 key 未变时无需重写）
                    if (!stopRequested) {
                        String curKey = buildScanKey();
                        if (!cacheHit || lastScanKey == null || !curKey.equals(lastScanKey)) {
                            saveCache(curKey, result);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, TAG_SUB + ": scan error: " + t.getMessage());
                    notifyError(callback, t.getMessage() == null ? "unknown" : t.getMessage());
                    scanning = false;
                    return;
                }
                final List<UsbMusicItem> finalList = result;
                scanning = false;
                notifyFinished(callback, finalList);
            }
        }, "UsbMusicScanner").start();
    }

    // ================= MediaStore 路径 =================

 /**
 * 缓存命中且为强制重扫时的后台刷新。新线程执行 File 重扫(带阶段超时
 * 防卡死),完成后写缓存并二次通知刷新列表。不阻塞主扫描线程的首屏返回。
 */
    private void startBackgroundRefresh(final String usbPath, final ScanCallback callback) {
        final List<UsbMusicItem> refresh = new ArrayList<>();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> roots = resolveScanRoots(usbPath);
                    if (roots.isEmpty() && !stopRequested) {
                        try {
                            Thread.sleep(1500L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (!stopRequested) {
                            roots = resolveScanRoots(usbPath);
                        }
                    }
                    if (roots.isEmpty() || stopRequested) {
                        return;
                    }
                    scanByFile(roots, refresh, callback);
                    if (!refresh.isEmpty() && !stopRequested) {
                        saveCache(buildScanKey(), refresh);
                        Log.i(TAG, TAG_SUB + ": background refresh done, songs=" + refresh.size());
                        // 二次通知：列表刷新为最新数据(onScanFinished 幂等,播放不中断)
                        notifyFinished(callback, refresh);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, TAG_SUB + ": background refresh error: " + t.getMessage());
                }
            }
        }, "UsbMusicScanner-Refresh").start();
    }

 /**
 * File 兜底扫描后台执行——首屏不阻塞。扫描完成后写缓存并二次通知
 * 刷新列表(与 startBackgroundRefresh 同模式,UsbSourceController.onScanFinished 幂等)。
 */
    private void startFileFallbackScan(final List<String> roots, final ScanCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<UsbMusicItem> found = new ArrayList<>();
                try {
                    scanByFile(roots, found, callback);
                } catch (Throwable t) {
                    Log.w(TAG, TAG_SUB + ": background file scan error: " + t.getMessage());
                }
                if (stopRequested || found.isEmpty()) {
                    return;
                }
                saveCache(buildScanKey(), found);
                Log.i(TAG, TAG_SUB + ": background file fallback done, songs=" + found.size());
                notifyFinished(callback, found);
            }
        }, "UsbMusicScanner-FileFallback").start();
    }

    private List<UsbMusicItem> queryFromMediaStore() {
        // B: 与多媒体(XCMedia2)同法——先按 volume 级 Uri 查询(MediaStore.Audio.Media.getContentUri(volume)),
        // 合并 DATA LIKE 兜底查询,按 path 去重。
        Map<String, UsbMusicItem> merged = new LinkedHashMap<>();
        try {
            List<UsbMusicItem> byVolume = queryByVolume();
            for (UsbMusicItem it : byVolume) {
                merged.put(it.path, it);
            }
            if (!merged.isEmpty()) {
                Log.i(TAG, TAG_SUB + ": volume-level MediaStore query found " + merged.size() + " usb tracks");
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": volume-level query failed: " + t.getMessage());
        }
        // 原 DATA LIKE 兜底（挂载根前缀过滤,兼容旧系统/主存储子目录等 volume 级查不到的场景）
        List<UsbMusicItem> byDataLike = queryByDataLike();
        for (UsbMusicItem it : byDataLike) {
            merged.put(it.path, it);
        }
        return new ArrayList<>(merged.values());
    }

 /**
 * B: volume 级查询。参考 XCMedia2 UsbDataManager.startQuery():
 * MediaStore.Audio.Media.getContentUri(volumeName) 按卷查询,一次拉全量元数据。
 * 卷名来源: StorageVolume 枚举(API 24+) + 反射 MediaStore.getExternalVolumeNames(API 29+)
 * + USB 挂载根路径最后一段(车机 ROM 实测 volume 名=挂载点最后一段,如 usbotg-otg1)。
 */
    private List<UsbMusicItem> queryByVolume() {
        List<UsbMusicItem> out = new ArrayList<>();
        Set<String> volumeNames = enumerateUsbVolumeNames();
        if (volumeNames.isEmpty()) {
            return out;
        }
        String[] proj = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM
        };
        ContentResolver cr = appContext.getContentResolver();
        for (String vol : volumeNames) {
            if (stopRequested) break;
            Cursor c = null;
            try {
                Uri uri = MediaStore.Audio.Media.getContentUri(vol);
                c = cr.query(uri, proj, null, null, MediaStore.Audio.Media.TITLE + " ASC");
                if (c == null) {
                    continue;
                }
                int kept = 0;
                while (c.moveToNext()) {
                    if (stopRequested) break;
                    UsbMusicItem item = new UsbMusicItem();
                    item.id = c.getLong(0);
                    item.title = c.getString(1);
                    item.artist = c.getString(2);
                    item.duration = c.getLong(3);
                    item.path = c.getString(4);
                    item.albumId = c.getLong(5);
                    item.album = c.getString(6);
                    // 逐条按 USB 挂载根过滤（volume 名枚举可能含主存储等非 USB 卷）
                    if (!isPathUnderUsbRoots(item.path)) {
                        continue;
                    }
                    item.folderName = extractFolderName(item.path);
                    out.add(item);
                    kept++;
                }
                if (kept > 0) {
                    Log.i(TAG, TAG_SUB + ": volume=" + vol + " kept=" + kept);
                }
            } catch (Throwable t) {
                Log.w(TAG, TAG_SUB + ": volume query failed vol=" + vol + " : " + t.getMessage());
            } finally {
                if (c != null) {
                    try { c.close(); } catch (Throwable ignored) {}
                }
            }
        }
        return out;
    }

 /** B: 枚举候选 USB MediaStore 卷名（API 28 兼容：新 API 走反射，旧 API 走路径推导）。 */
    private Set<String> enumerateUsbVolumeNames() {
        Set<String> names = new LinkedHashSet<>();
        // 0) 多媒体(XCMedia2)同法:AdaptAPI IStoragePolicy.getVolumeName(1/2) 直接拿准确卷名。
        // 之前只反射了 getVolumeFullPath(挂载路径),卷名靠枚举/路径最后一段推导——真车卷名
        // 与挂载点最后一段不一致时 MediaStore.getContentUri(错卷名) 查空 → 走 File 兜底慢/卡。
        // 补上这环后 MediaStore 命中 → 秒出列表,与多媒体一致。
        addUsbVolumeNamesViaAdaptApi(names);
        // 1) StorageManager.getStorageVolumes()（API 24+ 可安全调用；API 28 有）
        try {
            StorageManager sm = (StorageManager) appContext.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                List<StorageVolume> vols = sm.getStorageVolumes();
                for (StorageVolume sv : vols) {
                    if (sv == null) continue;
                    try {
                        if (sv.isPrimary()) continue; // 主外部存储交给 DATA LIKE 路径
                    } catch (Throwable ignored) {}
                    // StorageVolume.getPath() 是 @hide @SystemApi(编译期不可见),运行时存在,用反射拿。
                    // API 30+ 的 getDirectory() 是 public 的,同样反射兜底。
                    String vp = null;
                    try {
                        Method mPath = sv.getClass().getMethod("getPath");
                        Object r = mPath.invoke(sv);
                        if (r instanceof String) vp = (String) r;
                    } catch (Throwable ignored) {}
                    if (vp == null || vp.isEmpty()) {
                        try {
                            Method mDir = sv.getClass().getMethod("getDirectory");
                            Object r = mDir.invoke(sv);
                            if (r instanceof java.io.File) vp = ((java.io.File) r).getAbsolutePath();
                        } catch (Throwable ignored) {}
                    }
                    if (vp == null || vp.isEmpty() || !isUsbVolumePath(vp)) continue;
                    // 候选1: uuid（MediaStore volume 名标准规则, public API 16+）
                    String uuid = null;
                    try { uuid = sv.getUuid(); } catch (Throwable ignored) {}
                    if (uuid != null && !uuid.isEmpty()) names.add(uuid);
                    // 候选2: 挂载点最后一段（车机 ROM 实测 volume 名=路径最后一段）
                    String seg = lastPathSegment(vp);
                    if (seg != null && !seg.isEmpty()) names.add(seg);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": StorageManager.getStorageVolumes failed: " + t.getMessage());
        }
        // 2) 反射 MediaStore.getExternalVolumeNames（API 29+；API 28 上方法不存在,抛 NoSuchMethodException 正常）
        try {
            Method m = MediaStore.class.getMethod("getExternalVolumeNames", Context.class);
            Object res = m.invoke(null, appContext);
            if (res instanceof Set) {
                for (Object o : (Set<?>) res) {
                    if (o != null) names.add(o.toString());
                }
            }
        } catch (Throwable t) {
            // API 28 及以下无此方法,忽略
        }
        // 3) USB 挂载根路径最后一段推导（AdaptAPI 拿到的真实路径,如 /storage/usbotg/usbotg-otg1）
        for (String root : getUsbMountPaths()) {
            String seg = lastPathSegment(root);
            if (seg != null && !seg.isEmpty()) names.add(seg);
        }
        // 主外部存储卷不是 USB,移除(其数据由 DATA LIKE 按挂载根过滤处理)
        names.remove("external");
        names.remove("external_primary");
        return names;
    }

 /**
 * B: 多媒体(XCMedia2)同法——AdaptAPI IStoragePolicy.getVolumeName(1/2) 拿准确卷名。
 * 车机 MediaStore 卷名由 USB 卷注册决定,可能与挂载点最后一段不同;AdaptAPI 是权威来源。
 * 模拟器/无车机框架时反射失败,静默走原枚举逻辑。
 */
    private void addUsbVolumeNamesViaAdaptApi(Set<String> names) {
        try {
            Class<?> clazz = Class.forName("com.ecarx.xui.adaptapi.policy.Policy");
            Method mCreate = clazz.getMethod("create", Context.class);
            Object policy = mCreate.invoke(null, appContext);
            if (policy == null) {
                return;
            }
            Method mGetSp = policy.getClass().getMethod("getStoragePolicy");
            Object sp = mGetSp.invoke(policy);
            if (sp == null) {
                return;
            }
            Method mGetVol = sp.getClass().getMethod("getVolumeName", int.class);
            for (int idx = 1; idx <= 2; idx++) {
                Object v = mGetVol.invoke(sp, idx);
                if (v instanceof String && !((String) v).isEmpty()) {
                    names.add((String) v);
                    Log.i(TAG, TAG_SUB + ": AdaptAPI volumeName(" + idx + ")=" + v);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": AdaptAPI getVolumeName 反射不可用: " + t.getMessage());
        }
    }

 /** 判断卷路径是否属于 USB（命中已知挂载根 / 含 usb|otg|udisk 关键字）。 */
    private boolean isUsbVolumePath(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String root : getUsbMountPaths()) {
            if (isPathUnder(root, path)) return true;
        }
        for (String root : KNOWN_USB_ROOTS) {
            if (isPathUnder(root, path)) return true;
        }
        String lp = path.toLowerCase(Locale.US);
        return lp.contains("usb") || lp.contains("otg") || lp.contains("udisk");
    }

 /** path 是否命中任一 USB 挂载根（动态解析优先，回退硬编码）。 */
    private boolean isPathUnderUsbRoots(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String root : getUsbMountPaths()) {
            if (path.startsWith(root)) return true;
        }
        for (String root : KNOWN_USB_ROOTS) {
            if (path.startsWith(root)) return true;
        }
        return false;
    }

 /** 双向前缀判断：root 是 path 的子路径或父路径。 */
    private static boolean isPathUnder(String root, String path) {
        if (root == null || path == null) return false;
        return path.equals(root) || path.startsWith(root + "/") || root.startsWith(path + "/");
    }

    private static String lastPathSegment(String path) {
        if (path == null || path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash >= path.length() - 1) return path;
        return path.substring(slash + 1);
    }

 /** 原 DATA LIKE 查询（挂载根前缀过滤），作为 volume 级查询的兜底。 */
    private List<UsbMusicItem> queryByDataLike() {
        List<UsbMusicItem> list = new ArrayList<>();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM
        };
        // _data 包含 USB 挂载前缀。SQLite LIKE 不能 OR 多模式，用拼接 selection。
        // 使用动态解析的挂载路径（AdaptAPI 优先，回退硬编码）。
        List<String> usbRoots = getUsbMountPaths();
        if (usbRoots.isEmpty()) {
            usbRoots = Arrays.asList(KNOWN_USB_ROOTS); // 兜底：无挂载点时仍按已知路径查 DB
        }
        StringBuilder sel = new StringBuilder();
        for (int i = 0; i < usbRoots.size(); i++) {
            if (i > 0) sel.append(" OR ");
            sel.append(MediaStore.Audio.Media.DATA + " LIKE ?");
        }
        String[] args = new String[usbRoots.size()];
        for (int i = 0; i < usbRoots.size(); i++) {
            args[i] = usbRoots.get(i) + "%";
        }

        ContentResolver cr = appContext.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri, proj, sel.toString(), args,
                    MediaStore.Audio.Media.TITLE + " ASC");
            if (c == null) return list;
            int count = c.getCount();
            totalCount = Math.max(totalCount, count);
            while (c.moveToNext()) {
                if (stopRequested) break;
                UsbMusicItem item = new UsbMusicItem();
                item.id = c.getLong(0);
                item.title = c.getString(1);
                item.artist = c.getString(2);
                item.duration = c.getLong(3);
                item.path = c.getString(4);
                item.albumId = c.getLong(5);
                item.album = c.getString(6);
                item.folderName = extractFolderName(item.path);
                list.add(item);
                scannedCount = list.size();
                // 修复：MediaStore 命中后每首歌都 notifyProgress → mainHandler
                // 堆积数百个 onScanProgress → 每个触发全量 onStateChanged/pushAllPublic →
                // 主线程卡死几秒(ANR,主界面消失)。与 File 路径同策略：每 100 首推一次进度。
                if (scannedCount % 100 == 0) {
                    notifyProgress(scannedCount, Math.max(totalCount, scannedCount));
                }
            }
            // 循环结束补一次最终进度(每 100 首节流会漏掉非整百的尾部)
            if (scannedCount > 0) {
                notifyProgress(scannedCount, Math.max(totalCount, scannedCount));
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": mediaStore query failed: " + t.getMessage());
            return new ArrayList<>(); // 让上层走 File 兜底
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return list;
    }

    // ================= A/C: 查空重试 + SQLite 缓存 =================

 /**
 * 强制重扫：绕过 SQLite 缓存,重新扫描并更新缓存。
 * 用于 rescan() / USB 插入 / 系统媒体库扫描完成等需要最新数据的场景。
 */
    public void forceRescan(ScanCallback callback) {
        if (scanning) {
            Log.w(TAG, TAG_SUB + ": forceRescan ignored, scan already in progress");
            return;
        }
        bypassCache = true;
        startScan(null, callback);
    }

 /** A: 等待系统 MediaScanner 完成（收到 MEDIA_SCANNER_FINISHED 提前返回）。 */
    private void waitForScannerFinished(long timeoutMs) {
        long start = SystemClock.elapsedRealtime();
        long mark = lastScannerFinishedTime;
        while (!stopRequested && SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (lastScannerFinishedTime > mark) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

 /** C: 挂载路径归一化到已知 USB 根——AdaptAPI 真实挂载点(/storage/usbotg/usbotg-otg1)
 * 与硬编码根(/storage/usbotg)归一后一致,保证缓存 key 稳定(真车第一次切回退硬编码、
 * 第二次 AdaptAPI 就绪取真实路径,若不归一 key 变化 → 缓存 miss → 全量重扫卡死)。 */
    private static String normalizeRoot(String root) {
        if (root == null) return null;
        for (String known : KNOWN_USB_ROOTS) {
            if (root.equals(known) || root.startsWith(known + "/")) {
                return known;
            }
        }
        return root;
    }

 /** C: 缓存 key = 归一化后的 USB 挂载根列表(排序) join。无挂载时为 "none"。 */
    private String buildScanKey() {
        List<String> roots = new ArrayList<>();
        for (String r : getUsbMountPaths()) {
            String n = normalizeRoot(r);
            if (n != null && !roots.contains(n)) {
                roots.add(n);
            }
        }
        Collections.sort(roots);
        StringBuilder sb = new StringBuilder();
        for (String r : roots) {
            if (sb.length() > 0) sb.append('|');
            sb.append(r);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

 /** C: 缓存读取兜底——当前 key 未命中时,再试上次成功 key(路径解析波动时命中旧缓存)。 */
    private List<UsbMusicItem> loadCacheWithFallback() {
        String key = buildScanKey();
        List<UsbMusicItem> cached = loadCache(key);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        if (lastScanKey != null && !lastScanKey.equals(key)) {
            List<UsbMusicItem> viaLast = loadCache(lastScanKey);
            if (viaLast != null && !viaLast.isEmpty()) {
                Log.i(TAG, TAG_SUB + ": cache hit via lastScanKey " + lastScanKey);
                return viaLast;
            }
        }
        return cached; // 当前 key 的空缓存(有值)或 null(无缓存)
    }

 /**
 * C: 读取缓存。命中(含空列表缓存)返回非 null;无缓存/出错返回 null。
 * 空列表也缓存:U 盘无音乐时同样免重扫。
 */
    private List<UsbMusicItem> loadCache(String scanKey) {
        SQLiteDatabase db = getCacheDb();
        if (db == null) return null;
        List<UsbMusicItem> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT path,title,artist,album,duration,folder_name,album_id,cover_path"
                    + " FROM " + CACHE_TABLE + " WHERE scan_key=?", new String[]{scanKey});
            if (c == null) return null;
            while (c.moveToNext()) {
                UsbMusicItem item = new UsbMusicItem();
                item.path = c.getString(0);
                item.title = c.getString(1);
                item.artist = c.getString(2);
                item.album = c.getString(3);
                item.duration = c.getLong(4);
                item.folderName = c.getString(5);
                item.albumId = c.getLong(6);
                item.coverPath = c.getString(7);
                item.id = item.path == null ? 0L : item.path.hashCode();
                list.add(item);
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": loadCache failed: " + t.getMessage());
            return null;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return list; // 空列表也是有效缓存
    }

 /** C: 全量写缓存（同 scan_key 先删后插,事务批量）。 */
    private void saveCache(String scanKey, List<UsbMusicItem> list) {
        SQLiteDatabase db = getCacheDb();
        if (db == null) return;
        try {
            db.beginTransaction();
            try {
                db.delete(CACHE_TABLE, "scan_key=?", new String[]{scanKey});
                if (list != null) {
                    for (UsbMusicItem it : list) {
                        if (it == null || it.path == null || it.path.isEmpty()) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("path", it.path);
                        cv.put("title", it.title);
                        cv.put("artist", it.artist);
                        cv.put("album", it.album);
                        cv.put("duration", it.duration);
                        cv.put("folder_name", it.folderName);
                        cv.put("album_id", it.albumId);
                        cv.put("cover_path", it.coverPath);
                        cv.put("scan_key", scanKey);
                        db.insertWithOnConflict(CACHE_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            lastScanKey = scanKey;
            Log.i(TAG, TAG_SUB + ": cache saved, songs=" + (list == null ? 0 : list.size()) + " key=" + scanKey);
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": saveCache failed: " + t.getMessage());
        }
    }

 /** C: 清空全部缓存（USB 拔出时调用,保证换盘后缓存失效）。 */
    public void clearCache() {
        try {
            SQLiteDatabase db = getCacheDb();
            if (db != null) {
                int n = db.delete(CACHE_TABLE, null, null);
                Log.i(TAG, TAG_SUB + ": usb audio cache cleared, rows=" + n);
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": clearCache failed: " + t.getMessage());
        }
    }

 /** C: 懒加载缓存数据库（进程级单例,绝不 close,避免跨线程 close 崩溃）。 */
    private SQLiteDatabase getCacheDb() {
        if (usbCacheDb == null) {
            synchronized (this) {
                if (usbCacheDb == null) {
                    try {
                        usbCacheDb = appContext.openOrCreateDatabase(CACHE_DB_NAME, Context.MODE_PRIVATE, null);
                        usbCacheDb.execSQL("CREATE TABLE IF NOT EXISTS " + CACHE_TABLE
                                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL,"
                                + " title TEXT, artist TEXT, album TEXT, duration INTEGER,"
                                + " folder_name TEXT, album_id INTEGER, cover_path TEXT, scan_key TEXT)");
                        usbCacheDb.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_scan_key ON "
                                + CACHE_TABLE + " (scan_key)");
                    } catch (Throwable t) {
                        Log.w(TAG, TAG_SUB + ": open cache db failed: " + t.getMessage());
                        usbCacheDb = null;
                    }
                }
            }
        }
        return usbCacheDb;
    }

    // ================= File 递归兜底 =================

    private List<String> resolveScanRoots(String usbPath) {
        List<String> roots = new ArrayList<>();
        if (usbPath != null && !usbPath.isEmpty()) {
            File f = new File(usbPath);
            if (f.exists() && f.isDirectory()) {
                roots.add(usbPath);
            }
        }
        if (roots.isEmpty()) {
            roots.addAll(getUsbMountPaths());
        }
        return roots;
    }

    private void scanByFile(List<String> roots, List<UsbMusicItem> out, ScanCallback callback) {
        // 多线程加速：瓶颈在 MediaMetadataRetriever 逐文件读元数据+封面(每首几十~上百ms)。
        // 阶段1 多挂载点并行遍历收集文件;阶段2 固定线程池并行读元数据,结果按提交顺序汇总(顺序与单线程一致)。
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(8, cores));
        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "UsbScan-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        List<File> files = new ArrayList<>();
        try {
            // 阶段1: 并行遍历所有挂载点收集音频文件(同步列表,多线程 add 安全)
            final List<File> collected = Collections.synchronizedList(new ArrayList<File>());
            List<Future<?>> walkFutures = new ArrayList<>();
            for (String root : roots) {
                walkFutures.add(pool.submit(new Runnable() {
                    @Override public void run() {
                        collectAudioFiles(new File(root), collected, 0);
                    }
                }));
            }
            for (Future<?> f : walkFutures) {
                // 遍历加超时——U 盘 IO 挂起/超大目录时该挂载点 20s 后放弃,
                // 不阻塞整体扫描(线程池 shutdownNow 会清理残留遍历线程)
                try {
                    f.get(WALK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Throwable te) {
                    Log.w(TAG, TAG_SUB + ": walk timeout, stop waiting remaining roots");
                    break;
                }
            }
            files.addAll(collected);
            totalCount = files.size();
            scannedCount = 0;
            if (totalCount == 0) {
                Log.w(TAG, TAG_SUB + ": no audio files under " + roots);
                return;
            }
            Log.i(TAG, TAG_SUB + ": file scan total=" + totalCount + " threads=" + threads);

            // 阶段2: 并行读取元数据,按提交顺序汇总(保持与单线程一致的列表顺序)
            List<Future<UsbMusicItem>> futures = new ArrayList<>(files.size());
            for (final File f : files) {
                if (stopRequested) break;
                futures.add(pool.submit(new Callable<UsbMusicItem>() {
                    @Override
                    public UsbMusicItem call() {
                        if (stopRequested) return null;
                        return fromFile(f);
                    }
                }));
            }
            int done = 0;
            // 防卡死重构:分批等待(每批=线程数),单任务最多 15s;
            // 一旦批内出现超时说明线程池被卡死任务占满,放弃剩余批(否则每个排队
            // 任务都等满 15s,几百首级联 = 永久卡死);整阶段还有总超时兜底。
            long phaseStart = SystemClock.elapsedRealtime();
            int batchSize = Math.max(1, threads);
            boolean stuck = false;
            for (int start = 0; start < futures.size() && !stopRequested && !stuck; start += batchSize) {
                int end = Math.min(start + batchSize, futures.size());
                for (int i = start; i < end && !stopRequested; i++) {
                    // 阶段总超时:到点放弃剩余,返回已收集部分,绝不永久卡
                    if (SystemClock.elapsedRealtime() - phaseStart > FILE_SCAN_PHASE_TIMEOUT_MS) {
                        stuck = true;
                        Log.w(TAG, TAG_SUB + ": file scan phase timeout, stop waiting, kept=" + out.size());
                        break;
                    }
                    try {
                        UsbMusicItem item = futures.get(i).get(15, TimeUnit.SECONDS);
                        if (item != null) {
                            out.add(item);
                        }
                    } catch (java.util.concurrent.TimeoutException te) {
                        // 单文件卡死:线程被占,后续任务将排队——放弃剩余,避免级联卡死
                        stuck = true;
                        Log.w(TAG, TAG_SUB + ": file metadata stuck at " + files.get(i).getName()
                                + ", stop waiting remaining, kept=" + out.size());
                        break;
                    } catch (Throwable te) {
                        // 单文件异常:跳过该文件继续,不阻塞整个扫描
                        Log.d(TAG, TAG_SUB + ": file metadata error/skip: " + files.get(i).getName());
                    }
                    done++;
                    scannedCount = out.size();
                    // fix: reduce progress notify from every 5 to every 50 files.
                    // Every-5 caused ~209 main-thread posts for 1043 songs, each triggering
                    // full pushAllPublic() -> 640 frames skipped (~10.6s UI freeze).
                    // 50->100。扫描中 JS 已不再加载列表，这里再降一半推送频率：
                    // 289 首时扫描进度推送从 6 次降到 3 次（100/200/完成）。
                    if (done % 100 == 0 || done >= futures.size()) {
                        notifyProgress(scannedCount, totalCount);
                    }
                }
            }
            Log.i(TAG, TAG_SUB + ": file metadata phase done=" + done + "/" + futures.size()
                    + " kept=" + out.size() + " stuck=" + stuck);
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": parallel scan failed: " + t.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private void collectAudioFiles(File dir, List<File> out, int depth) {
        if (dir == null || !dir.exists() || stopRequested) return;
        // 防超大目录爆炸:深度与文件数量上限
        if (depth > MAX_SCAN_DEPTH) return;
        if (out.size() >= MAX_SCAN_FILES) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (stopRequested || out.size() >= MAX_SCAN_FILES) return;
            if (c.isDirectory()) {
                collectAudioFiles(c, out, depth + 1);
            } else if (isAudioFile(c)) {
                out.add(c);
            }
        }
    }

    private boolean isAudioFile(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot >= n.length() - 1) return false;
        String ext = n.substring(dot + 1).toLowerCase(Locale.US);
        return AUDIO_EXTS.contains(ext);
    }

    private UsbMusicItem fromFile(File f) {
        UsbMusicItem item = new UsbMusicItem();
        item.path = f.getAbsolutePath();
        item.folderName = extractFolderName(item.path);
        // 文件名去扩展名作为兜底标题
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        item.title = dot > 0 ? name.substring(0, dot) : name;
        item.artist = "";
        item.album = "";
        item.duration = 0;
        item.id = System.identityHashCode(f);
        item.albumId = 0;

        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(item.path);
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String ar = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String al = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null && !t.isEmpty()) item.title = t;
            if (ar != null && !ar.isEmpty()) item.artist = ar;
            if (al != null && !al.isEmpty()) item.album = al;
            if (dur != null) {
                try { item.duration = Long.parseLong(dur); } catch (Throwable ignored) {}
            }
            // 扫描提速:File 兜底不再解码嵌入封面。
            // 之前每首歌 getEmbeddedPicture(整图解码 1-5MB)+ 写文件,289 首累计 15-60s,
            // 而 UI 歌曲列表根本不显示封面,只播放页用。改为播放时按需异步补读(单首,快)。
            // item.coverPath 留空;缓存表 cover_path 列保留,兼容旧缓存数据。
        } catch (Throwable t) {
            // 个别损坏文件读不到元数据：保留文件名兜底，继续下一首
            Log.d(TAG, TAG_SUB + ": mmr failed " + f.getName() + " : " + t.getMessage());
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
        return item;
    }

    private static String extractFolderName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "";
        int prev = path.lastIndexOf('/', slash - 1);
        String folder = prev < 0 ? path.substring(0, slash) : path.substring(prev + 1, slash);
        return folder == null ? "" : folder;
    }

    // ================= 主线程回调分发 =================

    private void notifyStarted(final ScanCallback cb) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { cb.onScanStarted(); }
        });
    }

    private void notifyProgress(final int scanned, final int total) {
        final ScanCallback cbRef = currentCallback;
        if (cbRef == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { cbRef.onScanProgress(scanned, total); }
        });
    }

    private void notifyFinished(final ScanCallback cb, final List<UsbMusicItem> list) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { cb.onScanFinished(list); }
        });
    }

    private void notifyError(final ScanCallback cb, final String msg) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { cb.onScanError(msg); }
        });
    }

    // ================= USB 插拔广播监听 =================
    // 参考 XCMedia2 UsbScanHelper：监听所有 MEDIA_* 系统广播 + ecarx 自定义广播。
    // 必须 filter.addDataScheme("file")，否则收不到 MEDIA_MOUNTED/UNMOUNTED 等广播。
    // 通过 intent.getData().getPath() 判断是哪个 USB 路径。

 /**
 * 设置 USB 挂载/拔出/扫描完成回调（由 UsbSourceController 在 activate 时调用）。
 */
    public void setUsbMountCallback(UsbMountCallback callback) {
        this.usbMountCallback = callback;
    }

 /**
 * 注册 USB 插拔广播接收器。
 * 监听：MEDIA_MOUNTED / MEDIA_UNMOUNTED / MEDIA_EJECT / MEDIA_REMOVED /
 * MEDIA_SCANNER_STARTED / MEDIA_SCANNER_FINISHED / MEDIA_CHECKING /
 * MEDIA_NOFS / ecarx.intent.action.media.UPDATED
 * 必须设置 addDataScheme("file")，否则 MEDIA_* 广播收不到。
 */
    public void registerUsbBroadcastReceiver(Context context) {
        if (usbReceiverRegistered) {
            Log.w(TAG, TAG_SUB + ": USB 广播接收器已注册，跳过");
            return;
        }
        if (context == null) {
            Log.w(TAG, TAG_SUB + ": registerUsbBroadcastReceiver context 为 null");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
            filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addAction(Intent.ACTION_MEDIA_REMOVED);
            filter.addAction(Intent.ACTION_MEDIA_CHECKING);
            filter.addAction(Intent.ACTION_MEDIA_NOFS);
            // ecarx 自定义：媒体库更新完成
            filter.addAction("ecarx.intent.action.media.UPDATED");
            // 关键：不加 file scheme 收不到 MEDIA_* 广播
            filter.addDataScheme("file");
            filter.addDataScheme("content");

            usbBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handleUsbBroadcast(intent);
                }
            };
            context.getApplicationContext().registerReceiver(usbBroadcastReceiver, filter);
            usbReceiverRegistered = true;
            Log.i(TAG, TAG_SUB + ": USB 广播接收器已注册");
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": 注册 USB 广播接收器失败: " + t.getMessage());
            usbBroadcastReceiver = null;
            usbReceiverRegistered = false;
        }
    }

 /**
 * 反注册 USB 插拔广播接收器。处理未注册/已反注册的空指针情况。
 */
    public void unregisterUsbBroadcastReceiver() {
        if (!usbReceiverRegistered || usbBroadcastReceiver == null) {
            return;
        }
        try {
            appContext.unregisterReceiver(usbBroadcastReceiver);
            Log.i(TAG, TAG_SUB + ": USB 广播接收器已反注册");
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": 反注册 USB 广播接收器失败: " + t.getMessage());
        } finally {
            usbBroadcastReceiver = null;
            usbReceiverRegistered = false;
        }
    }

 /**
 * 内部：处理 USB 插拔广播，解析路径并通过 UsbMountCallback 回调。
 */
    private void handleUsbBroadcast(Intent intent) {
        try {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (intent.getData() == null) {
                return;
            }
            String path = intent.getData().getPath();
            if (path == null || path.isEmpty()) {
                return;
            }
            Log.i(TAG, TAG_SUB + ": USB 广播 action=" + action + " path=" + path);

            final UsbMountCallback cb = usbMountCallback;
            if (cb == null) {
                return;
            }
            // 切主线程回调
            final String cbPath = path;
            switch (action) {
                case Intent.ACTION_MEDIA_MOUNTED:
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            cb.onUsbMounted(cbPath);
                        }
                    });
                    break;
                case Intent.ACTION_MEDIA_UNMOUNTED:
                case Intent.ACTION_MEDIA_EJECT:
                case Intent.ACTION_MEDIA_REMOVED:
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            cb.onUsbUnmounted(cbPath);
                        }
                    });
                    break;
                case Intent.ACTION_MEDIA_SCANNER_FINISHED:
                    // A: 置扫描完成信号,让等待中的 MediaStore 重试提前返回
                    lastScannerFinishedTime = SystemClock.elapsedRealtime();
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            cb.onUsbScanFinished(cbPath);
                        }
                    });
                    break;
                default:
                    // MEDIA_SCANNER_STARTED / MEDIA_CHECKING / MEDIA_NOFS / UPDATED：暂不回调
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": handleUsbBroadcast 异常: " + t.getMessage());
        }
    }

 /**
 * 扫描进度回调接口。所有方法均在主线程回调。
 */
    public interface ScanCallback {
        void onScanStarted();
        void onScanProgress(int scanned, int total);
        void onScanFinished(List<UsbMusicItem> list);
        void onScanError(String msg);
    }

 /**
 * USB 挂载/拔出/扫描完成回调接口。所有方法均在主线程回调。
 * 由 UsbSourceController 实现，处理 USB 热插拔逻辑。
 */
    public interface UsbMountCallback {
 /** USB 已挂载插入。 */
        void onUsbMounted(String path);
 /** USB 已拔出/卸载。 */
        void onUsbUnmounted(String path);
 /** USB 媒体扫描完成。 */
        void onUsbScanFinished(String path);
    }

 /**
 * 将嵌入封面图片保存到应用缓存目录，返回缓存文件路径。
 * 文件名用歌曲路径的 MD5，避免重复保存同一首歌的封面。
 */
 /** 改为 public static,供 CarMediaService 播放时按需补封面。 */
    public static String saveCoverToCache(Context ctx, byte[] data, String songPath) {
        try {
            // ★ (EAS/DIM USB 封面 EACCES 根因):原实现存 ctx.getCacheDir()
            // (/data/user/0/com.ecarx.carmedia/cache/ 私有目录),系统小卡片/MDW 进程
            // 不同 uid 无权读取 → Glide 加载失败(EACCES Permission denied),封面推到
            // 了但界面不显示(日志确认)。CarMedia 是 sharedUserId=android.uid.system
            // (uid 1000 系统应用)且已声明 WRITE_EXTERNAL_STORAGE,可写公共外部存储。
            // 改存 /sdcard/Pictures/carmedia_usb_covers/ —— 系统所有进程都可读。
            java.io.File cacheDir = getUsbCoverPublicDir(ctx);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String fileName = Integer.toHexString(songPath.hashCode()) + ".jpg";
            java.io.File outFile = new java.io.File(cacheDir, fileName);
            // 已存在则直接返回（同一首歌的封面不需要重复保存）
            if (outFile.exists() && outFile.length() > 0) {
                return outFile.getAbsolutePath();
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            fos.write(data);
            fos.flush();
            fos.close();
            return outFile.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": saveCoverToCache failed: " + t.getMessage());
            return null;
        }
    }

 /** ★ USB 封面公共可读目录。优先 /sdcard/Pictures/carmedia_usb_covers/
 * (系统所有进程可读),外部存储不可用时回退到 filesDir(本应用与同 uid 系统进程可读)。 */
    public static java.io.File getUsbCoverPublicDir(Context ctx) {
        try {
            java.io.File picDir = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES);
            if (picDir != null) {
                java.io.File d = new java.io.File(picDir, "carmedia_usb_covers");
                if (d.exists() || d.mkdirs()) {
                    return d;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": getUsbCoverPublicDir external fail: " + t.getMessage());
        }
        // 回退:filesDir(共享 uid 的 system 进程可读,但第三方 DIM 未必)
        java.io.File filesDir = ctx.getFilesDir();
        java.io.File d = new java.io.File(filesDir, "usb_covers_public");
        if (!d.exists()) d.mkdirs();
        return d;
    }
}
