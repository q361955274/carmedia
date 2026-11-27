package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷狗音乐歌词抓取器（车机版 com.kugou.android.auto 专属，纯 AIDL 链路）。
 * <p>
 * 链路（与改版 APK 酷我方案同构；旧 web 搜索版已删除）：
 * <pre>
 * 绑定 AutoSdkAIDLRemoteService（IKgAutoInterface，TRANSACTION=1）
 * → APP_CHECK 白名单 → executeAction("LYRIC_KRC_BY_WORD", null)
 * → EXECUTE_ACTION_RESULT=KgLyricResult → getSongLyric() → SongLyric{lyric, offset}
 * lyric 已在酷狗进程内完成 KRC 解码（Base64→去4字节头→XOR magic→zlib），
 * 返回的仍是 KRC 格式文本（行标签 [mm,ss.xx] + 字级 &lt;...&gt;），CarMedia 侧 krcToLrc 转标准 LRC。
 * </pre>
 * 失败语义：binder 未就绪 / APP_CHECK 失败 / 无播放歌曲 / 无歌词 → onError，
 * 由上层（CarMediaService.fallbackKugouLyricToXmf2）回退 XMF2。
 * <p>
 * 铁律：LRC 解析只滤空行（LrcUtil.parse）；krcToLrc 只剥 &lt;...&gt; 字级标签，
 * 以 [ 开头的元数据行（[ar:][ti:] 作词/作曲等）一律原样保留。
 */
public class KugouLyricFetcher {

    private static final String TAG = "CarMedia";

    // ================= 酷狗 AIDL（与 KugouCoverProvider 同款链路） =================
    private static final String PKG_KUGOU = "com.kugou.android.auto";
    private static final String SVC = "com.kugou.auto.proxy.AutoSdkAIDLRemoteService";
    private static final String DESCRIPTOR = "com.kugou.auto.proxy.IKgAutoInterface";
    private static final int TRANSACTION_executeAction = 1;
 /** 酷狗 AIDL 歌词 action（反编译 AutoSdkAIDLRemoteService$1: action.startsWith("LYRIC_") → handleGetLyricAction）。 */
    private static final String ACTION_LYRIC_KRC_BY_WORD = "LYRIC_KRC_BY_WORD";
    private static final String ACTION_APP_CHECK = "APP_CHECK";
    private static final String KEY_APP_ID = "APP_ID";
    private static final String KEY_APP_KEY = "APP_KEY";
    private static final String KEY_SDK_VERSION = "android.intent.action.KgAutoSdk.VersionCode";
    private static final int SDK_VERSION = 27; // <28 服务端只走 check(V1):appKey==buildAppKey(appId)
    private static final String KEY_EXECUTE_ACTION_RESULT = "EXECUTE_ACTION_RESULT";
    private static final String METHOD_GET_SONG_LYRIC = "getSongLyric";
    private static final String FIELD_LYRIC = "lyric";
    private static final long BIND_WAIT_MS = 2000L;
 /** 酷狗 KRC 行时间标签：[mm,ss.xx]（逗号分隔，酷狗 KRC 专属）或 [mm:ss.xx]。 */
    private static final Pattern KRC_TIME = Pattern.compile("^\\[(\\d+)[,:](\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

 /** 酷狗 AIDL 状态（静态，进程内全局一份）。 */
    private static volatile IBinder sRemote;
    private static volatile boolean sBound;
    private static volatile boolean sStopped;
    private static volatile boolean sChecked;
    private static volatile ClassLoader sDexLoader;
    private static volatile KugouLyricFetcher sInstance;
    private static final int MAX_AUTO_RETRY = 5;

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "KugouLyric: aidl connected " + name);
            sRemote = service;
            sBound = true;
            // 冷启动场景：binder 连接成功后自动重试歌词获取，无需手动重选控制器
            if (sInstance != null) {
                sInstance.onAidlConnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "KugouLyric: aidl disconnected");
            sRemote = null;
            sBound = false;
            scheduleRebind();
        }
    };

    public interface Callback {
        void onLyricReady(String lyric, String title, String artist);

        void onError(String message);
    }

    private final Callback mCallback;
    private final Context mAppContext;
    private final Object mLock = new Object();

    private volatile List<LrcUtil.Line> mParsedLrc;
    private volatile String mCurrentLrc;
    private volatile String mCurrentSongTitle;
    private volatile String mCurrentSongArtist;
    private int mSearchSeq;
    private int mActiveSearches;
    private volatile int mApiState;      // 0=空闲 1=调用中 2=错误 3=成功（供 UI 状态灯）
    private volatile String mApiMsg;
    private String mSearchingTitle;
    private String mSearchingArtist;
    private volatile String mLastSearchTitle;
    private volatile String mLastSearchArtist;
    private int mAutoRetryCount;
    private volatile boolean mRetryThreadRunning;

    public KugouLyricFetcher(Context context, Callback callback) {
        this.mCallback = callback;
        this.mAppContext = context != null ? context.getApplicationContext() : null;
        sInstance = this;
    }

    public boolean isReady() {
        return mParsedLrc != null;
    }

 /** 供 UI 状态灯：0=空闲 1=调用中 2=错误 3=成功。 */
    public int getApiState() {
        return mApiState;
    }

    public String getApiMsg() {
        return mApiMsg;
    }

 /** 重置 API 状态灯为空闲（切歌/切换音源/清空歌词时调用）。 */
    public void resetApiStatus() {
        mApiState = 0;
        mApiMsg = null;
    }

 /** 当前加载歌词对应的歌名（归属校验用）。 */
    public String getSongTitle() {
        return mCurrentSongTitle;
    }

 /** 当前加载歌词对应的歌手。 */
    public String getSongArtist() {
        return mCurrentSongArtist;
    }

 /** 按播放进度取当前歌词行（纯向前回退，不过滤任何行）。 */
    public String getCurrentLyricLine(long positionMs) {
        List<LrcUtil.Line> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) {
            return "";
        }
        String line = "";
        for (LrcUtil.Line l : lrc) {
            if (l.timeMs <= positionMs) {
                if (l.text != null && !l.text.trim().isEmpty()) {
                    line = l.text;
                }
            } else {
                break;
            }
        }
        return line;
    }

 /** 下一句歌词行（position 后第一个非空行）。 */
    public String getNextLyricLine(long positionMs) {
        List<LrcUtil.Line> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) {
            return "";
        }
        for (LrcUtil.Line l : lrc) {
            if (l.timeMs > positionMs && l.text != null && !l.text.trim().isEmpty()) {
                return l.text;
            }
        }
        return "";
    }

 /** 全部非空歌词行（按时间顺序，供晴空主题右侧完整歌词列表）。 */
    public List<String> getAllLyricLines() {
        List<LrcUtil.Line> lrc = mParsedLrc;
        List<String> out = new java.util.ArrayList<>();
        if (lrc == null) return out;
        for (LrcUtil.Line l : lrc) {
            if (l.text != null && !l.text.trim().isEmpty()) out.add(l.text.trim());
        }
        return out;
    }

 /** 当前歌词行在「全部非空行」中的索引。 */
    public int getCurrentLyricIndex(long positionMs) {
        List<LrcUtil.Line> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) return -1;
        int idx = -1;
        for (LrcUtil.Line l : lrc) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            if (l.timeMs > positionMs) break;
            idx++;
        }
        return idx;
    }

 /**
 * 按歌名+歌手加载酷狗歌词（异步，回调 onLyricReady/onError）。
 * 唯一链路 = AIDL（当前播放歌曲 songId 精确取词）；失败由上层回退 XMF2。
 */
    public void search(final String title, final String artist) {
        synchronized (mLock) {
            if (mActiveSearches > 0 && title != null && title.equals(mSearchingTitle)
                    && (artist == null ? mSearchingArtist == null : artist.equals(mSearchingArtist))) {
                Log.d(TAG, "KugouLyric: same-title search in progress, reuse #" + mSearchSeq);
                return;
            }
        }
        mCurrentSongTitle = title;
        mCurrentSongArtist = artist;
        mLastSearchTitle = title;
        mLastSearchArtist = artist;
        mAutoRetryCount = 0;
        mCurrentLrc = null;
        mParsedLrc = null;
        mApiState = 1;
        mApiMsg = null;
        final int seq;
        synchronized (mLock) {
            seq = ++mSearchSeq;
            mActiveSearches++;
            mSearchingTitle = title;
            mSearchingArtist = artist;
        }
        Log.d(TAG, "KugouLyric: search start #" + seq + " " + title);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String krc = fetchLyricViaAidl();
                    if (krc == null || krc.trim().isEmpty()) {
                        fail(seq, "no aidl lyric");
                        return;
                    }
                    // 调试：原始 KRC 前几行（验证行标签格式 [开始毫秒,结束毫秒]）
                    String head = krc.trim();
                    int nl = head.indexOf('\n');
                    if (nl < 0) nl = head.length();
                    Log.d(TAG, "KugouLyric: krc raw head: " + head.substring(0, Math.min(240, nl + 1)).replace('\n', '|'));
                    String lrc = krcToLrc(krc);
                    if (lrc == null || lrc.trim().isEmpty()) {
                        fail(seq, "empty krc to lrc");
                        return;
                    }
                    Log.i(TAG, "KugouLyric: aidl lyric ok len=" + lrc.length());
                    applyLrc(seq, lrc, title, artist);
                } catch (Throwable t) {
                    Log.w(TAG, "KugouLyric: error: " + t.getMessage());
                    fail(seq, t.getMessage());
                } finally {
                    synchronized (mLock) {
                        mActiveSearches--;
                        if (mActiveSearches <= 0) {
                            mSearchingTitle = null;
                            mSearchingArtist = null;
                        }
                    }
                }
            }
        }).start();
    }

    // ================= 酷狗 AIDL 歌词（反编译 1:1） =================

 /** AIDL 连接成功后持续重试歌词获取（冷启动场景：酷狗 App 播放器核心初始化需要 5-15 秒，
 * binder 绑定成功但 isAppRunning()/getCurPlaySong() 可能仍为 false）。
 * 每 3 秒重试一次，最多 5 次（覆盖 15 秒冷启动窗口）。 */
    private void onAidlConnected() {
        if (mParsedLrc != null) {
            return; // 已有歌词，无需重试
        }
        if (mLastSearchTitle == null || mLastSearchTitle.isEmpty()) {
            return; // 没有搜索记录
        }
        // 已有重试线程在跑就不重复启动
        if (mRetryThreadRunning) {
            Log.d(TAG, "KugouLyric: retry thread already running, skip");
            return;
        }
        mRetryThreadRunning = true;
        mAutoRetryCount = 0;
        final String retryTitle = mLastSearchTitle;
        final String retryArtist = mLastSearchArtist;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "KugouLyric: start continuous retry loop for: " + retryTitle);
                for (int i = 1; i <= MAX_AUTO_RETRY; i++) {
                    try {
                        // 先等 500ms 让 APP_CHECK 完成
                        if (i == 1) {
                            Thread.sleep(500L);
                        } else {
                            Thread.sleep(3000L); // 后续每次间隔 3 秒
                        }
                        // 已有歌词就停止
                        if (mParsedLrc != null) {
                            Log.d(TAG, "KugouLyric: retry loop, lyric already loaded, stop");
                            break;
                        }
                        // 歌名变了（切歌了）就停止，避免旧歌歌词覆盖新歌
                        if (!retryTitle.equals(mLastSearchTitle)) {
                            Log.d(TAG, "KugouLyric: retry loop, title changed to " + mLastSearchTitle + ", stop");
                            break;
                        }
                        mAutoRetryCount = i;
                        Log.i(TAG, "KugouLyric: auto-retry #" + i + "/" + MAX_AUTO_RETRY + ": " + retryTitle);
                        String krc = fetchLyricViaAidl();
                        if (krc == null || krc.trim().isEmpty()) {
                            Log.d(TAG, "KugouLyric: auto-retry #" + i + " got null krc (app not ready yet)");
                            continue;
                        }
                        String lrc = krcToLrc(krc);
                        if (lrc == null || lrc.trim().isEmpty()) {
                            Log.d(TAG, "KugouLyric: auto-retry #" + i + " empty lrc");
                            continue;
                        }
                        Log.i(TAG, "KugouLyric: auto-retry #" + i + " SUCCESS len=" + lrc.length());
                        applyLrc(mSearchSeq, lrc, retryTitle, retryArtist);
                        break; // 成功就停止
                    } catch (Throwable t) {
                        Log.w(TAG, "KugouLyric: auto-retry #" + i + " error: " + t.getMessage());
                    }
                }
                mRetryThreadRunning = false;
                Log.i(TAG, "KugouLyric: retry loop ended after " + MAX_AUTO_RETRY + " attempts");
            }
        });
        t.setName("kugou-lyric-auto-retry");
        t.setDaemon(true);
        t.start();
    }

 /** 启动/重绑酷狗 AIDL 服务（酷狗未安装则跳过）。 */
    private synchronized void startAidl() {
        sStopped = false;
        if (sAppCtx == null && mAppContext != null) {
            sAppCtx = mAppContext;
        }
        if (sBound || sRemote != null) {
            return;
        }
        if (mAppContext == null || !isInstalled()) {
            return;
        }
        bindTo();
    }

    private static void scheduleRebind() {
        if (sStopped) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (sStopped || sBound || sRemote != null) {
                    return;
                }
                bindTo();
            }
        });
        t.setName("kugou-lyric-rebind");
        t.setDaemon(true);
        t.start();
    }

    private static void bindTo() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PKG_KUGOU, SVC));
            Context ctx = sAppCtx;
            if (ctx == null) {
                return;
            }
            boolean ok = ctx.bindService(intent, CONN, Context.BIND_AUTO_CREATE);
            sBound = ok;
            if (!ok) {
                scheduleRebind();
            }
        } catch (Throwable t) {
            Log.w(TAG, "KugouLyric: bind error: " + t.getMessage());
            scheduleRebind();
        }
    }

    private static Context sAppCtx;

    private boolean isInstalled() {
        try {
            mAppContext.getPackageManager().getPackageInfo(PKG_KUGOU, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

 /**
 * AIDL 取当前播放歌曲 KRC 歌词（酷狗进程内已解码为 KRC 文本）。
 * 步骤：绑定（短等）→ APP_CHECK 白名单 → executeAction("LYRIC_KRC_BY_WORD")
 * → EXECUTE_ACTION_RESULT=KgLyricResult → getSongLyric().lyric。
 * 返回 null 表示不可用/无词（调用方 fail → 上层 XMF2 兜底）。
 */
    private String fetchLyricViaAidl() {
        startAidl();
        IBinder remote = sRemote;
        if (remote == null) {
            long deadline = SystemClock.elapsedRealtime() + BIND_WAIT_MS;
            while (sRemote == null && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            remote = sRemote;
        }
        if (remote == null) {
            Log.d(TAG, "KugouLyric: aidl not bound");
            return null;
        }
        if (!sChecked) {
            boolean ok = doAppCheck(remote);
            if (!ok) {
                Log.d(TAG, "KugouLyric: APP_CHECK failed");
                return null;
            }
            sChecked = true;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(ACTION_LYRIC_KRC_BY_WORD);
            data.writeInt(0); // params Bundle = null
            remote.transact(TRANSACTION_executeAction, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 1) {
                Log.d(TAG, "KugouLyric: executeAction no result");
                return null;
            }
            Bundle result = reply.readBundle(null);
            if (result == null) {
                return null;
            }
            result.setClassLoader(getDexLoader());
            Object resultObj = result.getParcelable(KEY_EXECUTE_ACTION_RESULT);
            if (resultObj == null) {
                Log.d(TAG, "KugouLyric: EXECUTE_ACTION_RESULT null");
                return null;
            }
            Log.d(TAG, "KugouLyric: result class=" + resultObj.getClass().getName()
                    + " toString=" + resultObj);
            // KgLyricResult.getSongLyric() → SongLyric.lyric（public final String）
            Method get = resultObj.getClass().getMethod(METHOD_GET_SONG_LYRIC);
            Object songLyric = get.invoke(resultObj);
            if (songLyric == null) {
                return null;
            }
            Field f = songLyric.getClass().getField(FIELD_LYRIC);
            Object v = f.get(songLyric);
            return (v instanceof String) ? ((String) v).trim() : null;
        } catch (Throwable t) {
            Log.w(TAG, "KugouLyric: aidl lyric error: " + t.getMessage());
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

 /** 酷狗同款 APP_CHECK（KugouCoverProvider 同源算法）。 */
    private boolean doAppCheck(IBinder remote) {
        try {
            String appId = md5hex("doubao-car"); // 非 kgtest，避开服务端对 kgtest 的拒绝
            String sdkKey = "kgautosdkkey";
            String base = md5hex(encryptIso8859_1(appId, sdkKey)); // V1 = buildAppKey(appId)
            String appKey = base; // version<28 走 check(V1) 精确比较
            Bundle params = new Bundle();
            params.putString(KEY_APP_ID, appId);
            params.putString(KEY_APP_KEY, appKey);
            params.putInt(KEY_SDK_VERSION, SDK_VERSION);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(ACTION_APP_CHECK);
                data.writeInt(1);
                params.writeToParcel(data, 0);
                remote.transact(TRANSACTION_executeAction, data, reply, 0);
                reply.readException();
                Bundle rb = reply.readInt() > 0 ? reply.readBundle(getClass().getClassLoader()) : null;
                if (rb != null) {
                    boolean ok = rb.getBoolean("EXECUTE_SUCCESS", false);
                    Log.i(TAG, "KugouLyric: APP_CHECK EXECUTE_SUCCESS=" + ok
                            + " code=" + rb.getInt("EXECUTE_ERROR_CODE", -1)
                            + " msg=" + rb.getString("EXECUTE_ERROR_MSG"));
                    return ok;
                }
                return true;
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "KugouLyric: APP_CHECK error: " + t.getMessage());
            return false;
        }
    }

    private static String md5hex(String src) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(src.getBytes("UTF-8"));
        String s = new java.math.BigInteger(1, d).toString(16);
        while (s.length() < 32) s = "0" + s;
        return s;
    }

    private static String encryptIso8859_1(String src, String key) throws Exception {
        String md5key = md5hex(key);
        javax.crypto.spec.SecretKeySpec sk = new javax.crypto.spec.SecretKeySpec(
                md5key.substring(0, 16).getBytes("ISO8859-1"), "AES");
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, sk,
                new javax.crypto.spec.IvParameterSpec(md5key.substring(16, 32).getBytes("ISO8859-1")));
        byte[] enc = c.doFinal(src.getBytes("ISO8859-1"));
        return new String(enc, "ISO8859-1");
    }

 /** 懒加载酷狗 APK 的 ClassLoader（与 KugouCoverProvider 同款：Path 优先，内存回退）。 */
    private ClassLoader getDexLoader() {
        ClassLoader l = sDexLoader;
        if (l != null) {
            return l;
        }
        synchronized (KugouLyricFetcher.class) {
            l = sDexLoader;
            if (l == null) {
                try {
                    ApplicationInfo ai = mAppContext.getPackageManager().getApplicationInfo(PKG_KUGOU, 0);
                    l = new dalvik.system.PathClassLoader(ai.sourceDir, mAppContext.getClassLoader());
                } catch (Throwable t1) {
                    try {
                        l = buildInMemoryDexLoader();
                    } catch (Throwable t2) {
                        Log.w(TAG, "KugouLyric: dex loader error: " + t2.getMessage());
                        l = mAppContext.getClassLoader();
                    }
                }
                sDexLoader = l;
            }
            return l;
        }
    }

    private ClassLoader buildInMemoryDexLoader() throws Exception {
        ApplicationInfo ai = mAppContext.getPackageManager().getApplicationInfo(PKG_KUGOU, 0);
        java.util.List<java.nio.ByteBuffer> bufs = new java.util.ArrayList<>();
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(ai.sourceDir);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().matches("classes\\d*\\.dex")) {
                    continue;
                }
                java.io.InputStream is = zf.getInputStream(e);
                try {
                    byte[] b = new byte[(int) e.getSize()];
                    int off = 0;
                    while (off < b.length) {
                        int n = is.read(b, off, b.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                    bufs.add(java.nio.ByteBuffer.wrap(b));
                } finally {
                    is.close();
                }
            }
        } finally {
            zf.close();
        }
        if (bufs.isEmpty()) {
            throw new IllegalStateException("no dex in " + ai.sourceDir);
        }
        Class<?> imdc = Class.forName("dalvik.system.InMemoryDexClassLoader");
        java.lang.reflect.Constructor<?> ctor =
                imdc.getConstructor(java.nio.ByteBuffer[].class, ClassLoader.class);
        return (ClassLoader) ctor.newInstance(
                bufs.toArray(new java.nio.ByteBuffer[0]), mAppContext.getClassLoader());
    }

    // ================= KRC → LRC =================

 /**
 * 酷狗 KRC → 标准 LRC。
 * <p>
 * 酷狗 KRC 行格式（q0 解码后）：行标签是 [开始毫秒,结束毫秒]（两个整数，逗号分隔），
 * 如 [12345,8900]；随后是字级标签 &lt;字开始,字时长,&gt;文本 交错排列，酷狗把标签边界
 * 转义为全角（[&lt; → ［&lt;，&gt;] → &gt;］）。
 * <p>
 * 转换：行标签按「开始毫秒」输出 [mm:ss.xx]；剥掉 &lt;...&gt; 字级标签与全角转义残留；
 * 以 [ 开头但不含毫秒对的元数据行（[ar:][ti:] 等）原样保留（铁律）。
 */
    static String krcToLrc(String krc) {
        StringBuilder sb = new StringBuilder(krc.length());
        String[] rows = krc.split("\n");
        for (String row : rows) {
            String s = row.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = KRC_TIME.matcher(s);
            if (!m.find()) {
                if (s.startsWith("[")) {
                    sb.append(s).append('\n'); // 元数据行原样保留（铁律）
                }
                continue;
            }
            long ms;
            String g2 = m.group(2);
            if (g2.indexOf('.') >= 0) {
                // 标准 [mm:ss.xx]（兼容酷狗个别标准行）
                try {
                    ms = Long.parseLong(m.group(1)) * 60000L + (long) (Double.parseDouble(g2) * 1000.0);
                } catch (NumberFormatException e) {
                    continue;
                }
            } else {
                // 酷狗 KRC 行标签 [开始毫秒,结束毫秒]：用「开始毫秒」作为 LRC 时间
                try {
                    ms = Long.parseLong(m.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            // 剥字级标签（<...>）与酷狗全角转义残留（［ ］）
            String rest = TAGS.matcher(s.substring(m.end())).replaceAll("");
            rest = rest.replace("［", "").replace("］", "");
            long mm2 = ms / 60000L;
            long ss2 = (ms / 1000L) % 60L;
            long cs2 = (ms / 10L) % 100L;
            sb.append('[')
              .append(mm2 < 10 ? "0" : "").append(mm2).append(':')
              .append(ss2 < 10 ? "0" : "").append(ss2).append('.')
              .append(cs2 < 10 ? "0" : "").append(cs2).append(']')
              .append(rest).append('\n');
        }
        return sb.toString();
    }

 /** 解析 LRC 文本 → 绑定 → 若 seq 仍最新则回调。 */
    private void applyLrc(int seq, String lrc, String title, String artist) {
        List<LrcUtil.Line> parsed = LrcUtil.parse(lrc);
        if (parsed == null || parsed.isEmpty()) {
            fail(seq, "empty parsed lrc");
            return;
        }
        if (seq == mSearchSeq) {
            mCurrentLrc = lrc;
            mParsedLrc = parsed;
            mApiState = 3;
            mApiMsg = null;
            Log.i(TAG, "KugouLyric: loaded " + parsed.size() + " lines for " + title);
            if (mCallback != null) {
                mCallback.onLyricReady(lrc,
                        title != null ? title : "",
                        artist != null ? artist : "");
            }
        }
    }

    private void fail(int seq, String msg) {
        if (seq == mSearchSeq) {
            mCurrentLrc = null;
            mParsedLrc = null;
            mApiState = 2;
            mApiMsg = msg;
        }
        if (mCallback != null && seq == mSearchSeq) {
            mCallback.onError(msg);
        }
    }
}
