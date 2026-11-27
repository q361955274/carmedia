package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷我车机版歌词抓取器 —— 改版 APK（CarMedia-v1.5.4-kuwo-lyric）同款机制，1:1 移植。
 * <p>
 * 链路（本机实测 mobi.kuwo.cn 接口 200 验证）：
 * 1. 绑定酷我车机 RemoteService（AIDL DESCRIPTOR=cn.kuwo.service.remote.AIDLPlayContentInterface，
 * action=cn.kuwo.service.remote.RemoteService，包 cn.kuwo.kwmusiccar / cn.kuwo.kwmusiccas）
 * 2. transact(0x15=21, 写 playerId=1) → 播放日志 JSON → content 字段（内嵌 JSON）→ id(rid) + name(歌名)
 * 3. 按 rid 精确取词（不依赖歌名搜索）：
 * 明文 = user=0&prod=kwplayer_ar_9.9.9.1_aplayer&corp=kuwo&randomcode=&lt;16位hex&gt;
 * &amp;type=lyric&amp;rid=&lt;rid&gt;&amp;req=2&amp;lrcx=1&amp;encode=utf8
 *     加密payload = "http://mobi.kuwo.cn/mobi.s?" + 明文（URL 前缀一并参与 DES 加密）
 * KuwoDes.encrypt(payload, "ylzsxkwm") → Base64.NO_WRAP → URLEncoder
 *     GET http://mobi.kuwo.cn/mobi.s?f=kuwo&amp;q=&lt;密文&gt;
 * 4. 响应解析：前 3 行头（第 1 行 TP=content，第 2 行 lrcx=N），余下字节 zlib inflate；
 * lrcx=1 → Base64 解码 → 逐字节 XOR key="yeelion" → 酷我 lrcx 文本 → lrcxToLrc 转标准 LRC；
 * lrcx=0 → 解压文本直接就是标准 LRC。
 * <p>
 * 失败语义与 APK 一致：binder 未就绪 → 立即回调 onError（上层回退 XMF2）+ 后台 12 秒重试窗口；
 * 重试拿到真词后回调 onLyricReady 覆盖 XMF2 歌词。归属校验：mCurrentSongTitle 用 playlog content.name
 * （缺省用传入 title）。
 * <p>
 * 铁律：LRC 解析只滤空行（LrcUtil.parse）；lrcxToLrc 只剥 &lt;...&gt; 字级标签，
 * 以 [ 开头的元数据行（[ar:][ti:] 作词/作曲等）一律原样保留。
 */
public class KuwoLyricFetcher {

    private static final String TAG = "KuwoLyric";

    private static final String PKG_KUWO = "cn.kuwo.kwmusiccar";
    private static final String PKG_KUWO_CAS = "cn.kuwo.kwmusiccas";
    private static final String KUWO_SERVICE = "cn.kuwo.service.remote.RemoteService";
    private static final String DESCRIPTOR = "cn.kuwo.service.remote.AIDLPlayContentInterface";
    private static final int TRANSACTION_GET_PLAY_LOG_INFO = 0x15;

    private static final String MOBI_BASE = "http://mobi.kuwo.cn/mobi.s";
    private static final byte[] DES_KEY = "ylzsxkwm".getBytes();
    private static final byte[] XOR_KEY = "yeelion".getBytes();
    private static final Random RNG = new Random();

    private static final Pattern HEAD = Pattern.compile("^\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\]");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

 /** AIDL 状态（静态，进程内全局一份，与 APK 一致）。 */
    private static volatile IBinder sKuwoBinder;
    private static volatile boolean sBinding;
    private static volatile int sApiStatus;
    private static volatile String sApiMsg = "";

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sKuwoBinder = service;
            Log.i(TAG, "kuwo aidl connected: " + name);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sKuwoBinder = null;
            Log.w(TAG, "kuwo aidl disconnected");
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
    private volatile int mApiState;      // 0=空闲 1=连接/查询中 2=错误 3=成功（供 UI 状态灯）
    private volatile String mApiMsg;
    private String mSearchingTitle;
    private String mSearchingArtist;

    public KuwoLyricFetcher(Context context, Callback callback) {
        this.mCallback = callback;
        this.mAppContext = context != null ? context.getApplicationContext() : null;
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }
    }

    public boolean isReady() {
        return mParsedLrc != null;
    }

 /** 供 UI 状态灯：0=空闲 1=连接/查询中 2=错误 3=成功。 */
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

 /** 当前加载歌词对应的歌名（归属校验用；酷我链路为 playlog content.name 或传入 title）。 */
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
 * 按当前播放歌曲抓取酷我歌词（异步，回调 onLyricReady/onError）。
 * 酷我链路不搜索：AIDL 拿真实 rid → mobi.s 按 rid 精确取词。
 * binder 未就绪时立即回调 onError（上层回退 XMF2），并启动 12 秒重试窗口。
 */
    public void search(final String title, final String artist) {
        synchronized (mLock) {
            if (mActiveSearches > 0 && title != null && title.equals(mSearchingTitle)
                    && (artist == null ? mSearchingArtist == null : artist.equals(mSearchingArtist))) {
                Log.d(TAG, "KuwoLyric: same-title search in progress, reuse #" + mSearchSeq);
                return;
            }
        }
        mCurrentSongTitle = title;
        mCurrentSongArtist = artist;
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
        Log.d(TAG, "KuwoLyric: search start #" + seq + " " + title);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runFetchPipeline(seq, title, artist);
                } catch (Throwable t) {
                    Log.w(TAG, "KuwoLyric: error: " + t.getMessage());
                    setApi(2, t.getMessage());
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

 /**
 * 取词主流程（与改版 APK tryFetch 状态机一致）：
 * 1. ensureBound；binder 未就绪 → setApi(1,"连接中") + 立即 fail（上层回退 XMF2）+ 12s 重试窗口；
 * 2. binder 就绪 → playlog → content → rid；rid<=0 → setApi(2,"无播放信息") + fail；
 * 3. rid>0 → fetchLrcByRid → 成功绑定 + setApi(3) + onLyricReady；失败 setApi(2,"获取失败") + fail。
 */
    private void runFetchPipeline(int seq, String title, String artist) {
        ensureBound(mAppContext);
        if (sKuwoBinder == null) {
            // 与 APK 一致：先让上层走 XMF2 兜底，后台 12 秒窗口重试，重试成功用真词覆盖。
            setApi(1, "连接中");
            fail(seq, "binder not ready; fallback now + async retry");
            retryWorker(seq, title, artist);
            return;
        }
        setApi(1, "查询中");
        JSONObject content = fetchCurrentContent(title, artist);
        if (content == null) {
            Log.w(TAG, "playlog stale or no content; fallback");
            setApi(2, "无播放信息");
            fail(seq, "no matching play info");
            return;
        }
        long rid = content.optLong("id", 0L);
        if (rid <= 0) {
            Log.w(TAG, "playlog rid<=0; fallback");
            setApi(2, "无播放信息");
            fail(seq, "no play info");
            return;
        }
        // title 必须用传入的当前歌名（CarMedia metadata 权威），不能用 playlog.name——
        // 快速切歌时 playlog 滞后(上一首),若用其 name 覆盖,归属校验会被污染(必然通过)。
        fetchAndApply(seq, rid, title, artist);
    }

 /**
 * 取"当前播放歌曲"的 playlog content（含归属校验，防快速切歌时 playlog 滞后）。
 * <p>
 * 真车实测（酷我 4.2.8.20 车机版）：快速切歌（每 3~4 秒）时 RemoteService 的
 * playlog 恒返回"上一首"（content.id=上一首 rid、content.name=上一首歌名）。
 * 这里用 playlog.name 与传入歌名归一化比对：匹配 → 当前歌；不匹配 → 判定滞后，
 * 400ms 后重取（最多 3 次）；仍不匹配 → null（上层走 XMF2 兜底，handleMetadata
 * 每秒重触发，playlog 追上后下一次搜索自然成功）。name 为空时无法校验，按 APK 原行为放行。
 */
    private static JSONObject fetchCurrentContent(String wantTitle, String wantArtist) {
        JSONObject content = null;
        for (int i = 0; i < 3; i++) {
            content = extractContent(getPlayLogInfo());
            if (content == null) {
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ignored) {
                    return null;
                }
                continue;
            }
            long rid = content.optLong("id", 0L);
            if (rid <= 0) {
                return null;
            }
            String name = content.optString("name", null);
            if (name == null || name.isEmpty()) {
                return content; // 无真实歌名可校验：按 rid 取词（APK 原行为）
            }
            if (nameMatches(name, wantTitle)) {
                return content; // playlog 已是当前歌
            }
            Log.d(TAG, "playlog stale (name=" + name + " want=" + wantTitle + "), retry "
                    + (2 - i));
            try {
                Thread.sleep(400L);
            } catch (InterruptedException ignored) {
                return null;
            }
        }
        return null;
    }

 /** 歌名归一化匹配（双方包含：兼容 "雨樱花 (feat. xx)" 与 "雨樱花"）。 */
    private static boolean nameMatches(String logName, String wantTitle) {
        if (logName == null || wantTitle == null) {
            return false;
        }
        String a = TextUtil.normalize(logName);
        String b = TextUtil.normalize(wantTitle);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

 /**
 * 重试窗口（APK spawnRetryWorker 1:1）：binder 未连上时每 300ms 重绑，窗口 12 秒；
 * binder 就绪后最多 6 次尝试拿 playlog（每次间隔 500ms）；拿到 rid 后取词，成功回调覆盖 XMF2。
 */
    private void retryWorker(final int seq, final String title, final String artist) {
        final long deadline = System.currentTimeMillis() + 12000L;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (sKuwoBinder == null && System.currentTimeMillis() < deadline) {
                        if (mAppContext != null) ensureBound(mAppContext);
                        Thread.sleep(300L);
                    }
                    if (sKuwoBinder == null) {
                        Log.w(TAG, "retry worker: binder never connected");
                        setApi(2, "连接失败");
                        return;
                    }
                    JSONObject content = fetchCurrentContent(title, artist);
                    if (content == null) {
                        Log.w(TAG, "retry worker: playlog stale/null after retries");
                        setApi(2, "无播放信息");
                        return;
                    }
                    long rid = content.optLong("id", 0L);
                    if (rid <= 0) {
                        Log.w(TAG, "retry worker: rid<=0");
                        setApi(2, "无播放信息");
                        return;
                    }
                    fetchAndApply(seq, rid, title, artist);
                } catch (InterruptedException e) {
                    Log.d(TAG, "retry worker interrupted");
                } catch (Throwable t) {
                    Log.w(TAG, "retry worker error: " + t.getMessage());
                    setApi(2, "获取失败");
                }
            }
        });
        t.setName("kuwo-lyric-retry");
        t.setDaemon(true);
        t.start();
    }

 /** fetchLrcByRid → 成功 applyLrc + setApi(3)；失败 setApi(2,"获取失败")。 */
    private void fetchAndApply(final int seq, final long rid, final String title, final String artist) {
        final int s = seq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String lrc = fetchLrcByRid(rid);
                    if (lrc != null && !lrc.isEmpty()) {
                        Log.i(TAG, "lyric ok rid=" + rid + " len=" + lrc.length());
                        setApi(3, "rid " + rid);
                        applyLrc(s, lrc, title, artist);
                    } else {
                        Log.w(TAG, "lyric fetch fail rid=" + rid);
                        setApi(2, "获取失败");
                        fail(s, "lyric fetch fail rid=" + rid);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "lyric fetch error rid=" + rid + ": " + t.getMessage());
                    setApi(2, "获取失败");
                    fail(s, t.getMessage());
                }
            }
        }).start();
    }

    // ================= mobi.s 取词（APK fetchLrcByRid 1:1） =================

 /**
 * 按 rid 取标准 LRC：
     * 构造明文 → 拼接 "http://mobi.kuwo.cn/mobi.s?" 前缀 → KuwoDes 加密 → Base64 → URLEncode → GET。
 * 响应头 TP=content / lrcx=N，余下 zlib inflate；lrcx=1 再 Base64+XOR("yeelion") 得 lrcx 文本。
 */
    static String fetchLrcByRid(long rid) throws Exception {
        String randomcode = Long.toHexString(RNG.nextLong()) + Long.toHexString(RNG.nextLong());
        String plain = "user=0&prod=kwplayer_ar_9.9.9.1_aplayer&corp=kuwo&randomcode=" + randomcode
                + "&type=lyric&rid=" + rid + "&req=2&lrcx=1&encode=utf8";
        // 加密 payload = URL 前缀 + 明文（URL 一并参与 DES，缺了接口不认）
        String payload = MOBI_BASE + "?" + plain;
        byte[] enc = KuwoDes.encrypt(payload.getBytes("UTF-8"), DES_KEY);
        String b64 = Base64.encodeToString(enc, Base64.NO_WRAP);
        String url = MOBI_BASE + "?f=kuwo&q=" + URLEncoder.encode(b64, "UTF-8");
        byte[] body = httpGet(url);
        if (body == null || body.length == 0) {
            return null;
        }
        // 前 3 行解析头部：第 0 行 TP=content，第 1 行 lrcx=N
        int off = 0;
        int line = 0;
        int lrcx = -1;
        String tp = null;
        for (int i = 0; i < body.length && line < 3; i++) {
            if (body[i] == '\n') {
                String s = new String(body, off, i - off, "UTF-8").trim();
                if (line == 0) {
                    tp = s;
                } else if (line == 1 && s.startsWith("lrcx=")) {
                    try {
                        lrcx = Integer.parseInt(s.substring(5).trim());
                    } catch (NumberFormatException ignored) {
                        lrcx = -1;
                    }
                }
                off = i + 1;
                line++;
            }
        }
        if (!"TP=content".equals(tp) || off >= body.length) {
            return null;
        }
        int dataLen = body.length - off;
        byte[] data = new byte[dataLen];
        System.arraycopy(body, off, data, 0, dataLen);
        byte[] inflated = zlibInflate(data);
        if (inflated == null) {
            return null;
        }
        String text = new String(inflated, "UTF-8");
        if (lrcx == 1) {
            byte[] dec = Base64.decode(text.trim(), Base64.NO_WRAP);
            for (int i = 0; i < dec.length; i++) {
                dec[i] ^= XOR_KEY[i % XOR_KEY.length];
            }
            return lrcxToLrc(new String(dec, "UTF-8"));
        }
        return text;
    }

 /**
 * 酷我 lrcx → 标准 LRC（APK lrcxToLrc 1:1）：
 * 时间戳行取分/秒/厘秒换算毫秒，[mm:ss.xx] 补零输出，剥离 &lt;...&gt; 字级标签；
 * 非时间戳但以 [ 开头的行（[ar:][ti:] 等元数据）原样保留；其余丢弃。
 */
    static String lrcxToLrc(String lrcx) {
        StringBuilder sb = new StringBuilder(lrcx.length());
        String[] rows = lrcx.split("\n");
        for (String row : rows) {
            String s = row.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = HEAD.matcher(s);
            if (!m.find()) {
                if (s.startsWith("[")) {
                    sb.append(s).append('\n'); // 元数据行原样保留（铁律）
                }
                continue;
            }
            int mm;
            int ss;
            int cs;
            try {
                mm = Integer.parseInt(m.group(1));
                ss = Integer.parseInt(m.group(2));
                String frac = m.group(3);
                if (frac != null) {
                    cs = Integer.parseInt(frac);
                    if (frac.length() == 1) {
                        cs *= 100;
                    } else if (frac.length() == 2) {
                        cs *= 10;
                    }
                } else {
                    cs = 0;
                }
            } catch (NumberFormatException e) {
                continue;
            }
            long ms = mm * 0xea60L + ss * 1000L + cs;
            String rest = TAGS.matcher(s.substring(m.end())).replaceAll("");
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

 /** zlib inflate（APK 1:1：Inflater + ByteArrayOutputStream，异常返回 null）。 */
    static byte[] zlibInflate(byte[] input) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(input);
            ByteArrayOutputStream bout = new ByteArrayOutputStream(input.length * 4);
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) {
                    break;
                }
                bout.write(buf, 0, n);
            }
            inf.end();
            return bout.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

 /** HTTP GET 返回原始字节（APK 1:1：仅 200 读取，否则 null）。 */
    static byte[] httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "okhttp/3.12.0");
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return null;
        }
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bout.write(buf, 0, n);
        }
        is.close();
        conn.disconnect();
        return bout.toByteArray();
    }

    // ================= AIDL（APK 1:1） =================

    private static boolean isKuwoPkg(String pkg) {
        return PKG_KUWO.equals(pkg) || PKG_KUWO_CAS.equals(pkg);
    }

 /** 绑定酷我 RemoteService：优先 kwmusiccar，其次 kwmusiccas。 */
    private static void ensureBound(Context context) {
        if (sKuwoBinder != null || sBinding || context == null) {
            return;
        }
        String pkg = null;
        try {
            context.getPackageManager().getPackageInfo(PKG_KUWO, 0);
            pkg = PKG_KUWO;
        } catch (Exception ignored) {
        }
        if (pkg == null) {
            try {
                context.getPackageManager().getPackageInfo(PKG_KUWO_CAS, 0);
                pkg = PKG_KUWO_CAS;
            } catch (Exception ignored) {
            }
        }
        if (pkg == null) {
            return; // 酷我未安装：不绑定，直接走失败路径
        }
        sBinding = true;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, KUWO_SERVICE));
            context.bindService(intent, CONN, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind fail: " + t.getMessage());
            sBinding = false;
        }
    }

 /** transact(0x15) 取播放日志 JSON（APK getPlayLogInfo 1:1）。 */
    private static JSONObject getPlayLogInfo() {
        IBinder binder = sKuwoBinder;
        if (binder == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1); // playerId = 1
            binder.transact(TRANSACTION_GET_PLAY_LOG_INFO, data, reply, 0);
            reply.readException();
            String log = reply.readInt() != 0 ? reply.readString() : null;
            if (log == null || log.isEmpty()) {
                return null;
            }
            return new JSONObject(log);
        } catch (Throwable t) {
            Log.w(TAG, "transact21 fail: " + t.getMessage());
            sKuwoBinder = null;
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

 /** playlog.content 是内嵌 JSON 字符串，解析为对象（APK extractContent 1:1）。 */
    private static JSONObject extractContent(JSONObject playlog) {
        if (playlog == null) {
            return null;
        }
        try {
            String content = playlog.optString("content", null);
            if (content == null || content.isEmpty()) {
                return null;
            }
            return new JSONObject(content);
        } catch (Throwable t) {
            Log.w(TAG, "extractContent fail: " + t.getMessage());
            return null;
        }
    }

    private static void setApi(int status, String msg) {
        sApiStatus = status;
        sApiMsg = msg != null ? msg : "";
    }

 /** 静态 API 状态（{status, msg}），供 CarMediaService getApiStatus 聚合。 */
    public static JSONObject getApiStatusStatic() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", sApiStatus);
            o.put("msg", sApiMsg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ================= 状态绑定与回调 =================

 /** 解析 LRC → 绑定 → 若 seq 仍最新则回调。 */
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
            Log.i(TAG, "KuwoLyric: loaded " + parsed.size() + " lines for " + title);
            if (mCallback != null) {
                mCallback.onLyricReady(lrc,
                        title != null ? title : "",
                        artist != null ? artist : "");
            }
        }
    }

    private void fail(int seq, String msg) {
        if (seq == mSearchSeq) {
            mApiState = 2;
            mApiMsg = msg;
        }
        if (mCallback != null && seq == mSearchSeq) {
            mCallback.onError(msg);
        }
    }
}
