package com.ecarx.carmedia;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 与原版差异：无（行为一致）
 */
public class CloudLyricFetcher {

    private static final String TAG = "CarMedia";

    static {
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }
    }

    public interface Callback {
        void onLyricReady(String lyric, String title, String artist);
        void onError(String message);
    }

    public static final int API_IDLE = 0;
    public static final int API_ACTIVE = 1;
    public static final int API_ERROR = 2;
    public static final int API_SUCCESS = 3;

    public static final String API_NETEASE_SEARCH = "netease_search";
    public static final String API_NETEASE_LYRIC = "netease_lyric";
    public static final String API_XMF2 = "xmf2";

    private final java.util.Map<String, int[]> mApiStatus = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> mApiMsg = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> mApiChangedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> mApiActiveAt = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long ACTIVE_HOLD_MS = 3000L;

    private void setApiStatus(String api, int status, String msg) {
        mApiStatus.put(api, new int[]{status, (int) (System.currentTimeMillis() / 1000)});
        mApiChangedAt.put(api, System.currentTimeMillis());
        if (status == API_ACTIVE) {
            mApiActiveAt.put(api, System.currentTimeMillis());
        }
        if (msg != null) {
            mApiMsg.put(api, msg);
        }
        if (status == API_IDLE) {
            mApiMsg.remove(api);
        }
    }

    private void setApiStatusIfCurrent(int seq, String api, int status, String msg) {
        if (seq != mSearchSeq) {
            return;
        }
        setApiStatus(api, status, msg);
    }

    public void resetApiStatus() {
        mXmf2ActiveType = null;
        for (String api : new String[]{API_NETEASE_SEARCH, API_NETEASE_LYRIC, API_XMF2}) {
            mApiStatus.put(api, new int[]{API_IDLE, (int) (System.currentTimeMillis() / 1000)});
            mApiMsg.remove(api);
            mApiChangedAt.remove(api);
            mApiActiveAt.remove(api);
        }
    }

    public JSONObject getApiStatus() {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            for (String api : new String[]{API_NETEASE_SEARCH, API_NETEASE_LYRIC, API_XMF2}) {
                JSONObject s = new JSONObject();
                int[] v = mApiStatus.get(api);
                int st = v != null ? v[0] : API_IDLE;
                if (st == API_ACTIVE) {
                    // 调用中：无条件绿
                } else if (st == API_IDLE || st == API_SUCCESS) {
                    Long activeAt = mApiActiveAt.get(api);
                    if (activeAt != null && (now - activeAt) < ACTIVE_HOLD_MS) {
                        st = API_ACTIVE; // 刚完成：保持期内仍显示绿（闪一下）
                    }
                }
                // ERROR：常亮红，不回落
                s.put("status", st);
                s.put("msg", mApiMsg.get(api) != null ? mApiMsg.get(api) : "");
                o.put(api, s);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static class LrcLine {
        public long timeMs;
        public String text;

        public LrcLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private final Callback mCallback;
    private String mCurrentLrc;
 /** 原始 LRC 文本（含时间戳），无则 null。供 EAS 完整歌词推送 setLyricContent（移植 MediaBridge）。 */
    public String getRawLrc() {
        return mCurrentLrc;
    }
    private String mCurrentSongArtist;
    private String mCurrentSongTitle;
    private List<LrcLine> mParsedLrc;
    private boolean mFallbackRunning;
    private volatile String mXmf2ActiveType;
    private int mSearchSeq; // 搜索请求序号：旧线程完成时若已不是最新请求则丢弃结果
    private int mActiveSearches;
    private String mSearchingTitle;
    private String mSearchingArtist;
    private String mBroadcastLrc;
    private String mBroadcastArtist;
    private volatile boolean mBroadcastLrcActive;

    // ---- xmf2 备用歌词接口歌手归属校验 ----
    private static final String[] XMF2_TYPES = {"qq", "kugou", "kuwo", "wy"};

 /** 歌名是否匹配：归一化后精确比较或包含匹配（统一到 TextUtil.normalize）。 */
    private static boolean titleMatches(String want, String got) {
        if (want == null || want.isEmpty() || got == null || got.isEmpty()) {
            return false;
        }
        String w = TextUtil.normalize(want);
        String g = TextUtil.normalize(got);
        if (w.equals(g)) {
            return true;
        }
        // 容忍后缀：如歌名带(Live)等后缀变体
        return w.contains(g) || g.contains(w);
    }

 /** 歌手是否匹配：期望歌手（可能是多歌手 "A/B"）与 LRC 歌手做包含匹配。 */
    private static boolean artistMatches(String want, String got) {
        if (want == null || want.isEmpty()) {
            return true;
        }
        if (got == null || got.isEmpty()) {
            return false;
        }
        String w = TextUtil.normalize(want);
        String g = TextUtil.normalize(got);
        for (String part : w.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (g.contains(part) || part.contains(g)) {
                return true;
            }
        }
        return false;
    }

 /** 从 USB 文件名式标题清洗出干净的搜索 歌名/歌手。
 * 处理 "0661.蒋雪儿 - 燕无歇." / "0659.小乐哥 - 执迷不悟 (DJ版)." 这类文件名:
 * - 去掉前导序号 "0661." 与尾部多余标点
 * - 从 "歌手 - 歌名" 拆出真实歌手(文件名是歌手在前,优先于 item.artist 的水印值)
 * 非文件名式标题(普通蓝牙歌名)原样返回,避免误拆 "歌名 - 歌手" 顺序的普通标题。
 * @return String[]{title, artist} */
    private static String[] cleanSearchKey(String title, String artist) {
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        boolean filenameLike = false;
        // 去前导序号(USB 文件名式标题,如 "0661.蒋雪儿 - 燕无歇.")
        String t2 = t.replaceFirst("^\\d+[.．、]?\\s*", "");
        if (!t2.equals(t)) {
            filenameLike = true;
        }
        t = t2;
        // 去尾部多余标点(文件名常带尾点 "..mp3" -> 标题结尾 ".")
        String t3 = t.replaceFirst("[.．。·\\s]+$", "");
        if (!t3.equals(t) || t.endsWith(".")) {
            filenameLike = true;
        }
        t = t3;
        // 仅文件名式标题才从 "歌手 - 歌名" 拆歌手:拆出的真实歌手优先于 item.artist(如水印 "小高网分享")
        if (filenameLike) {
            int last = t.lastIndexOf(" - ");
            if (last > 0) {
                String artistPart = t.substring(0, last).trim();
                String titlePart = t.substring(last + 3).trim();
                if (!artistPart.isEmpty() && !titlePart.isEmpty()) {
                    a = artistPart;
                    t = titlePart;
                }
            }
        }
        return new String[]{t, a};
    }

 /** 网易云归属校验·歌名:期望歌名是结果歌名的前缀,或结果歌名是期望歌名的前缀。
 * (比 titleMatches 的包含匹配更严:"相遇" 不能通过 "还会再相遇" 的包含匹配,防串歌;
 * 版本后缀如 "相遇 (Live)" 仍能通过前缀匹配)。 */
    private static boolean neteaseOwnershipTitle(String wantTitle, String gotName) {
        if (wantTitle == null || wantTitle.isEmpty() || gotName == null || gotName.isEmpty()) {
            return false;
        }
        String w = TextUtil.normalize(wantTitle);
        String g = TextUtil.normalize(gotName);
        if (w.isEmpty() || g.isEmpty()) {
            return false;
        }
        return w.startsWith(g) || g.startsWith(w);
    }

 /** 网易云归属校验·歌手:期望歌手与结果歌手兼容。
 * 兼容定义:artistMatches(包含/被包含) 通过,或共享 ≥2 个字符前缀
 * (容错 小乐哥/小乐歌 同音、"蒋雪儿" vs "蒋雪儿Snow.J" 后缀;
 * 同时 "灵骨Linan" vs "蓝心羽" 无共同前缀会被正确拒绝)。 */
    private static boolean neteaseArtistCompat(String wantArtist, String gotArtist) {
        if (wantArtist == null || wantArtist.trim().isEmpty()) {
            return true; // 无期望歌手:只看歌名
        }
        if (gotArtist == null || gotArtist.trim().isEmpty()) {
            return false;
        }
        if (artistMatches(wantArtist, gotArtist)) {
            return true;
        }
        String w = TextUtil.normalize(wantArtist);
        String g = TextUtil.normalize(gotArtist);
        if (w.isEmpty() || g.isEmpty()) {
            return false;
        }
        int common = 0;
        int max = Math.min(w.length(), g.length());
        while (common < max && w.charAt(common) == g.charAt(common)) {
            common++;
        }
        return common >= 2;
    }

    private static boolean isRealLrc(String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return false;
        }
        String[] rows = lrc.split("\n");
        int stampRows = 0;
        int textRows = 0;
        for (String row : rows) {
            String s = row.trim();
            if (s.startsWith("[") && s.indexOf(']') > 0) {
                stampRows++;
                String v = s.substring(s.indexOf(']') + 1).trim();
                if (!v.isEmpty()) {
                    textRows++;
                }
            }
        }
        // 至少 2 行时间戳 + 至少 1 行有效文本：拒绝 kuwo 等返回的单行残缺 LRC
        return stampRows >= 2 && textRows >= 1;
    }

    private static String[] extractLrcInfo(String lrc) {
        if (lrc == null || lrc.isEmpty()) {
            return null;
        }
        String[] rows = lrc.split("\n");
        for (String row : rows) {
            String s = row.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("[") && s.indexOf(']') > 0) {
                String v = s.substring(s.indexOf(']') + 1).trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (v.startsWith("作词") || v.startsWith("作曲") || v.startsWith("编曲")
                        || v.startsWith("原唱") || v.startsWith("制作") || v.startsWith("录音")
                        || v.startsWith("混音") || v.startsWith("和声") || v.startsWith("出品")
                        || v.startsWith("OP") || v.startsWith("SP") || v.startsWith("统筹")
                        || v.startsWith("企划") || v.startsWith("监制") || v.startsWith("发行")) {
                    continue;
                }
                int sep1 = v.indexOf(" - ");
                int sep2 = v.indexOf(" -");
                if (sep1 > 0 || sep2 > 0) {
                    int sep = sep1 > 0 ? sep1 : sep2;
                    String a = v.substring(0, sep).trim();
                    String b = v.substring(sep + (sep1 > 0 ? 3 : 2)).trim();
                    if (a.isEmpty() || b.isEmpty()) {
                        return null;
                    }
                    return new String[]{a, b};
                }
                return null;
            }
        }
        return null;
    }

    private static boolean lrcMatches(String lrc, String wantTitle, String wantArtist) {
        if (!isRealLrc(lrc)) {
            return false;
        }
        String[] info = extractLrcInfo(lrc);
        if (info == null) {
            // no info line (wy style), accept: qq/kugou/kuwo already tried
            return true;
        }
        boolean t1 = titleMatches(wantTitle, info[0]);
        boolean t2 = titleMatches(wantTitle, info[1]);
        if (t1 || t2 || artistMatches(wantArtist, info[0]) || artistMatches(wantArtist, info[1])) {
            // （xmf2 接口只按歌名搜，同名不同歌手返回热门版，此处按用户要求放宽归属校验）
            return true;
        }
        return false;
    }

 /**
 */
    private static String buildSearchKey(String wantTitle, String wantArtist) {
        String t = wantTitle != null ? wantTitle.trim() : "";
        String a = wantArtist != null ? wantArtist.trim() : "";
        // 去掉歌名中的括号及括号内内容（如 "心会跟爱一起走(Single Version)" -> "心会跟爱一起走"），
        t = stripBrackets(t);
        if (!t.isEmpty() && !a.isEmpty()) {
            return t + "-" + a;
        }
        return t;
    }

    private static String stripBrackets(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String prev;
        String cur = s;
        do {
            prev = cur;
            cur = prev.replaceAll("\\([^()]*\\)", "").replaceAll("\\uFF08[^\\uFF08\\uFF09]*\\uFF09", "");
        } while (!cur.equals(prev));
        return cur.trim();
    }

    private String fetchXmf2WithVerify(String wantTitle, String wantArtist) {
        for (String type : XMF2_TYPES) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("http://api.xmf2.cn/api/music/lyric.php?type=")
                        .append(type)
                        .append("&lrc=")
                        .append(URLEncoder.encode(buildSearchKey(wantTitle, wantArtist), "UTF-8"));
                String body = httpGetV4(sb.toString(), "Mozilla/5.0", null, 10000);
                if (body == null || body.trim().isEmpty()) {
                    continue;
                }
                if (lrcMatches(body, wantTitle, wantArtist)) {
                    mXmf2ActiveType = type;
                    return body;
                }
                Log.d(TAG, "CloudLyric: xmf2 type=" + type + " mismatch (" + wantTitle
                        + "), try next");
            } catch (Throwable t) {
                Log.d(TAG, "CloudLyric: xmf2 type=" + type + " error: " + t.getMessage());
            }
        }
        return null;
    }

    private final Object mLock = new Object();

    public CloudLyricFetcher(Callback callback) {
        this.mCallback = callback;
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }
    }

    public boolean isReady() {
        return mParsedLrc != null;
    }

    public String getSongTitle() {
        return mCurrentSongTitle;
    }

    public String getSongArtist() {
        return mCurrentSongArtist;
    }


    private boolean fetchFromXmf2(final String title, final String artist, final int seq) {
        synchronized (mLock) {
            if (mFallbackRunning) {
                // 当前搜索线程自己收尾（finally 减计数）
                Log.d(TAG, "CloudLyric: xmf2 already running, skip duplicate");
                return false;
            }
            mFallbackRunning = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    setApiStatusIfCurrent(seq, API_XMF2, API_ACTIVE, null);
                    String body = fetchXmf2WithVerify(
                            title != null ? title : "",
                            artist != null ? artist : "");
                    if (body == null) {
                        mXmf2ActiveType = null;
                        setApiStatusIfCurrent(seq, API_XMF2, API_ERROR, "no matching lyric");
                        if (seq == mSearchSeq) {
                            mCurrentLrc = null;
                            mParsedLrc = null;
                        }
                        if (mCallback != null && seq == mSearchSeq) {
                            mCallback.onError("xmf2: no matching lyric");
                        }
                        return;
                    }
                    setApiStatusIfCurrent(seq, API_XMF2, API_SUCCESS, mXmf2ActiveType);
                    // ★ 修复:网易云兜底(XMF2)成功拿到歌词时,同步标记网易云歌词接口成功。
                    // 否则 USB 选网易云源、网易云搜索归属校验失败落 xmf2 时,备用有歌词但
                    // netease_lyric 状态保持空闲(0) → UI 歌词接口一直闪黄灯(尽管有歌词)。
                    setApiStatusIfCurrent(seq, API_NETEASE_LYRIC, API_SUCCESS, "xmf2:" + mXmf2ActiveType);
                    if (seq == mSearchSeq) {
                        mCurrentLrc = body;
                        mParsedLrc = parseLrc(body);
                    }
                    if (mCallback != null && seq == mSearchSeq) {
                        mCallback.onLyricReady(body,
                                title != null ? title : "",
                                artist != null ? artist : "");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "CloudLyric: xmf2 error: " + t.getMessage());
                    setApiStatusIfCurrent(seq, API_XMF2, API_ERROR, t.getMessage());
                    if (seq == mSearchSeq) {
                        mCurrentLrc = null;
                        mParsedLrc = null;
                    }
                    if (mCallback != null && seq == mSearchSeq) {
                        mCallback.onError("xmf2: " + t.getMessage());
                    }
                } finally {
                    synchronized (mLock) {
                        mFallbackRunning = false;
                        mActiveSearches--;
                        // 最后一个活跃线程结束时清掉去重标记
                        if (mActiveSearches <= 0) {
                            mSearchingTitle = null;
                            mSearchingArtist = null;
                        }
                    }
                }
            }
        }).start();
        return true;
    }

    public void searchXmf2Only(final String title, final String artist) {
        if (mBroadcastLrcActive && mBroadcastLrc != null) {
            Log.d(TAG, "CloudLyric: broadcast lrc active, skip xmf2 search (" + title + ")");
            return;
        }
        synchronized (mLock) {
            if (mActiveSearches > 0 && title != null && title.equals(mSearchingTitle)
                    && (artist == null ? mSearchingArtist == null : artist.equals(mSearchingArtist))) {
                Log.d(TAG, "CloudLyric: same-title search in progress, reuse #" + mSearchSeq);
                return;
            }
            mSearchingTitle = title;
            mSearchingArtist = artist;
        }
        mCurrentSongTitle = title;
        mCurrentSongArtist = artist;
        mCurrentLrc = null;
        mParsedLrc = null;
        final int seq;
        synchronized (mLock) {
            seq = ++mSearchSeq;
            mActiveSearches++;
        }
        Log.d(TAG, "CloudLyric: xmf2 search start #" + seq + " " + title);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    setApiStatusIfCurrent(seq, API_XMF2, API_ACTIVE, null);
                    String body = fetchXmf2WithVerify(
                            title != null ? title : "",
                            artist != null ? artist : "");
                    if (body == null) {
                        mXmf2ActiveType = null;
                        setApiStatusIfCurrent(seq, API_XMF2, API_ERROR, "no matching lyric");
                        if (seq == mSearchSeq) {
                            mCurrentLrc = null;
                            mParsedLrc = null;
                            if (mCallback != null) {
                                mCallback.onLyricReady("",
                                        title != null ? title : "",
                                        artist != null ? artist : "");
                            }
                        }
                        return;
                    }
                    setApiStatusIfCurrent(seq, API_XMF2, API_SUCCESS, mXmf2ActiveType);
                    if (seq == mSearchSeq) {
                        mCurrentLrc = body;
                        mParsedLrc = parseLrc(body);
                        mCallback.onLyricReady(body,
                                title != null ? title : "",
                                artist != null ? artist : "");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "CloudLyric: xmf2-only error: " + t.getMessage());
                    setApiStatusIfCurrent(seq, API_XMF2, API_ERROR, t.getMessage());
                    if (seq == mSearchSeq) {
                        mCurrentLrc = null;
                        mParsedLrc = null;
                    }
                    if (mCallback != null && seq == mSearchSeq) {
                        mCallback.onError("xmf2: " + t.getMessage());
                    }
                } finally {
                    synchronized (mLock) {
                        mActiveSearches--;
                        // 最后一个活跃线程结束时清掉去重标记
                        if (mActiveSearches <= 0) {
                            mSearchingTitle = null;
                            mSearchingArtist = null;
                        }
                    }
                }
            }
        }).start();
    }

    public void search(final String title, final String artist) {
        if (mBroadcastLrcActive && mBroadcastLrc != null) {
            Log.d(TAG, "CloudLyric: broadcast lrc active, skip network search (" + title + ")");
            return;
        }
        // 每次都起新线程会互相抢占（seq 递增导致上一个结果被丢弃，歌词永远绑定不上）
        synchronized (mLock) {
            if (mActiveSearches > 0 && title != null && title.equals(mSearchingTitle)
                    && (artist == null ? mSearchingArtist == null : artist.equals(mSearchingArtist))) {
                Log.d(TAG, "CloudLyric: same-title search in progress, reuse #" + mSearchSeq + " (" + title + ")");
                return;
            }
        }
        mCurrentSongTitle = title;
        mCurrentSongArtist = artist;
        // ★ 串歌修复:清洗 USB 文件名式标题,用真实歌名/歌手去搜网易云,
        // 避免 "0661.蒋雪儿 - 燕无歇." 带序号前缀+水印歌手("小高网分享")匹配到错歌/翻唱版。
        // cleanTitle/cleanArtist 仅用于 搜索查询+归属校验+xmf2 兜底;
        // mCurrentSongTitle 仍保留原始标题(供 CarMediaService 归属校验与 UI 一致性)。
        final String[] ck = cleanSearchKey(title, artist);
        final String cleanTitle = ck[0];
        final String cleanArtist = ck[1];
        mCurrentLrc = null;
        mParsedLrc = null;
        final int seq;
        synchronized (mLock) {
            seq = ++mSearchSeq;
            mActiveSearches++;
            mSearchingTitle = title;
            mSearchingArtist = artist;
        }
        Log.d(TAG, "CloudLyric: search start #" + seq + " " + title);
        final Thread searchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean takenOver = false; // XMF2 fallback 接管了收尾（不再减计数）
                try {
                    setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_ACTIVE, null);
                    StringBuilder sb = new StringBuilder();
                    sb.append("https://music.163.com/api/search/get?s=")
                            .append(URLEncoder.encode(cleanTitle != null ? cleanTitle : "", "UTF-8"));
                    if (cleanArtist != null && !cleanArtist.isEmpty()) {
                        sb.append("+").append(URLEncoder.encode(cleanArtist, "UTF-8"));
                    }
                    sb.append("&type=1&limit=3");
                    String searchBody;
                    try {
                        searchBody = httpGetV4(sb.toString(), "Mozilla/5.0", null, 6000);
                    } catch (Throwable t) {
                        Log.w(TAG, "CloudLyric: netease search fail (" + t.getMessage() + "), fallback to xmf2");
                        setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_ERROR, t.getMessage());
                        takenOver = fetchFromXmf2(cleanTitle, cleanArtist, seq);
                        return;
                    }
                    JSONObject json = new JSONObject(searchBody);
                    if (json.optInt("code") != 200) {
                        Log.w(TAG, "CloudLyric: netease code != 200, fallback to xmf2");
                        setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_ERROR, "code=" + json.optInt("code"));
                        takenOver = fetchFromXmf2(cleanTitle, cleanArtist, seq);
                        return;
                    }
                    setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_SUCCESS, null);
                    JSONObject result = json.optJSONObject("result");
                    if (result == null) {
                        if (mCallback != null && seq == mSearchSeq) {
                            mCallback.onError("search result null");
                        }
                        return;
                    }
                    int songCount = result.optInt("songCount");
                    if (songCount == 0) {
                        if (mCallback != null && seq == mSearchSeq) {
                            mCallback.onError("No results");
                        }
                        return;
                    }
                    JSONArray songs = result.optJSONArray("songs");
                    if (songs == null || songs.length() == 0) {
                        if (mCallback != null && seq == mSearchSeq) {
                            mCallback.onError("No results");
                        }
                        return;
                    }
                    // ★ 串歌修复:归属校验遍历结果,取第一个 歌名(前缀)+歌手(兼容)匹配的;
                    // 全不匹配则拒收(不把错歌歌词推给当前歌),换 xmf2 兜底。
                    // 背景:USB 用脏标题(带序号前缀+水印歌手)搜网易云曾返回错歌/翻唱版
                    // (如 0662 樱木町 -> "樱木町人声纯享mp3(翻自 蓝心羽)" by 灵骨Linan,其歌词是《执迷不悟》),
                    // 旧代码直接取 songs[0] 无校验 → 错歌歌词被采用 → 歌词提前走/乱跳。
                    JSONObject song = null;
                    String songId = null;
                    String songName = null;
                    String joinedArtist = null;
                    for (int i = 0; i < songs.length() && i < 3; i++) {
                        JSONObject s = songs.optJSONObject(i);
                        if (s == null) {
                            continue;
                        }
                        String nm = s.optString("name");
                        String ar = null;
                        JSONArray arts = s.optJSONArray("artists");
                        if (arts != null && arts.length() > 0) {
                            StringBuilder ab = new StringBuilder();
                            for (int k = 0; k < arts.length(); k++) {
                                if (k > 0) {
                                    ab.append("/");
                                }
                                ab.append(arts.optJSONObject(k).optString("name"));
                            }
                            ar = ab.toString();
                        }
                        if (neteaseOwnershipTitle(cleanTitle, nm)
                                && neteaseArtistCompat(cleanArtist, ar)) {
                            song = s;
                            songId = String.valueOf(s.optLong("id", -1L));
                            songName = nm;
                            joinedArtist = ar;
                            break;
                        }
                        Log.d(TAG, "CloudLyric: netease result #" + i + " ownership fail name='" + nm
                                + "' artist='" + ar + "' want='" + cleanTitle + "/" + cleanArtist + "'");
                    }
                    if (song == null) {
                        // 前 3 个结果都与期望歌名/歌手不匹配:拒收,换 xmf2 兜底(其自带归属校验)
                        Log.w(TAG, "CloudLyric: netease no ownership match for " + cleanTitle
                                + ", fallback to xmf2");
                        setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_ERROR, "ownership fail");
                        takenOver = fetchFromXmf2(cleanTitle, cleanArtist, seq);
                        return;
                    }
                    if (songId == null || "-1".equals(songId) || songId.isEmpty()) {
                        if (mCallback != null && seq == mSearchSeq) {
                            mCallback.onError("song id invalid");
                        }
                        return;
                    }
                    // 校验通过的歌手(正确版)写回,供 UI/后续归属用
                    if (seq == mSearchSeq && joinedArtist != null) {
                        mCurrentSongArtist = joinedArtist;
                    }

                    String lyricUrlStr = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=-1&kv=-1";
                    String lbody;
                    try {
                        setApiStatusIfCurrent(seq, API_NETEASE_LYRIC, API_ACTIVE, null);
                        lbody = httpGetV4(lyricUrlStr, "Mozilla/5.0", "https://music.163.com/", 6000);
                    } catch (Throwable t) {
                        Log.w(TAG, "CloudLyric: netease lyric fail (" + t.getMessage() + "), fallback to xmf2");
                        setApiStatusIfCurrent(seq, API_NETEASE_LYRIC, API_ERROR, t.getMessage());
                        takenOver = fetchFromXmf2(cleanTitle, cleanArtist, seq);
                        return;
                    }
                    JSONObject ljson = new JSONObject(lbody);
                    String lrc = null;
                    JSONObject lrcObj = ljson.optJSONObject("lrc");
                    if (lrcObj != null) {
                        lrc = lrcObj.optString("lyric");
                    }
                    if (lrc == null || lrc.trim().isEmpty()) {
                        Log.w(TAG, "CloudLyric: netease lyric empty, fallback to xmf2");
                        setApiStatusIfCurrent(seq, API_NETEASE_LYRIC, API_ERROR, "empty lyric");
                        takenOver = fetchFromXmf2(cleanTitle, cleanArtist, seq);
                        return;
                    }
                    setApiStatusIfCurrent(seq, API_NETEASE_LYRIC, API_SUCCESS, null);
                    if (seq == mSearchSeq) {
                        mCurrentLrc = lrc;
                        mParsedLrc = parseLrc(lrc);
                        mCallback.onLyricReady(lrc,
                                title != null ? title : "",
                                joinedArtist != null ? joinedArtist : (artist != null ? artist : ""));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "CloudLyric: search error: " + t.getMessage());
                    setApiStatusIfCurrent(seq, API_NETEASE_SEARCH, API_ERROR, t.getMessage());
                    if (mCallback != null && seq == mSearchSeq) {
                        mCallback.onError("search: " + t.getMessage());
                    }
                } finally {
                    if (!takenOver) {
                        synchronized (mLock) {
                            mActiveSearches--;
                            if (mActiveSearches <= 0) {
                                mSearchingTitle = null;
                                mSearchingArtist = null;
                            }
                        }
                    }
                }
            }
        });
        searchThread.start();
        final Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException ignored) {
                }
                synchronized (mLock) {
                    if (mActiveSearches > 0) {
                        Log.w(TAG, "CloudLyric: watchdog reset stale searches (active=" + mActiveSearches + ")");
                        mActiveSearches = 0;
                        mFallbackRunning = false;
                        mSearchingTitle = null;
                        mSearchingArtist = null;
                    }
                }
            }
        });
        watchdog.start();
    }

 /**
 */
    public void setBroadcastLrc(String lrc, String title, String artist) {
        if (lrc == null || lrc.isEmpty()) {
            return;
        }
        synchronized (mLock) {
            mBroadcastLrc = lrc;
            mBroadcastArtist = artist;
            mBroadcastLrcActive = true;
            mParsedLrc = parseLrc(lrc);
            mCurrentLrc = lrc;
            mCurrentSongTitle = title;
            mCurrentSongArtist = artist;
        }
        // ★ 修复:广播歌词已实际可用,同步写成功态,状态灯不再停在空闲黄。
        markBroadcastLyricSuccess();
    }

 /** 供 CarMediaService 在广播歌词(整段或单行)实际可用时同步接口状态。 */
    public void markBroadcastLyricSuccess() {
        setApiStatus(API_NETEASE_SEARCH, API_SUCCESS, "broadcast");
        setApiStatus(API_NETEASE_LYRIC, API_SUCCESS, "broadcast");
    }

    public void clearBroadcastLrc() {
        synchronized (mLock) {
            mBroadcastLrcActive = false;
            mBroadcastLrc = null;
            mBroadcastArtist = null;
        }
        // ★ 修复:清除广播歌词时复位歌词接口状态,避免残留成功态。
        setApiStatus(API_NETEASE_LYRIC, API_IDLE, null);
    }

    public void clearLoadedLrc() {
        synchronized (mLock) {
            mParsedLrc = null;
            mCurrentLrc = null;
            mCurrentSongTitle = null;
            mCurrentSongArtist = null;
            mBroadcastLrcActive = false;
            mBroadcastLrc = null;
            mBroadcastArtist = null;
            mSearchSeq++;
            mSearchingTitle = null;
            mSearchingArtist = null;
        }
        // ★ 修复:清空歌词时复位歌词接口状态,避免残留成功态。
        setApiStatus(API_NETEASE_LYRIC, API_IDLE, null);
    }

    public String getCurrentLyricLine(long positionMs) {
        List<LrcLine> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) {
            return "";
        }
        // 向前回退到最近一条非空行。解析层已过滤空行，这里是双保险
        String line = "";
        for (LrcLine l : lrc) {
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

 /** Next lyric line after current playback position (for the 'next lyric' label). Empty if none. */
    public String getNextLyricLine(long positionMs) {
        List<LrcLine> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) {
            return "";
        }
        for (LrcLine l : lrc) {
            if (l.timeMs > positionMs && l.text != null && !l.text.trim().isEmpty()) {
                return l.text;
            }
        }
        return "";
    }

 /** 全部非空歌词行（按时间顺序，供晴空主题右侧完整歌词列表）。 */
    public java.util.List<String> getAllLyricLines() {
        List<LrcLine> lrc = mParsedLrc;
        java.util.List<String> out = new java.util.ArrayList<>();
        if (lrc == null) return out;
        for (LrcLine l : lrc) {
            if (l.text != null && !l.text.trim().isEmpty()) out.add(l.text.trim());
        }
        return out;
    }

 /** 当前歌词行在「全部非空行」中的索引（position 之前最后一个非空行）。 */
    public int getCurrentLyricIndex(long positionMs) {
        List<LrcLine> lrc = mParsedLrc;
        if (lrc == null || lrc.isEmpty()) return -1;
        int idx = -1;
        for (LrcLine l : lrc) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            if (l.timeMs > positionMs) break;
            idx++;
        }
        return idx;
    }



    private static String readAll(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getInputStream();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
        }
    }

 /**
 */
    static String httpGetV4(String urlStr, String ua, String referer, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : ("https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80);
        String path = url.getFile();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        InetAddress[] addrs = InetAddress.getAllByName(host);
        InetAddress v4 = null;
        for (InetAddress a : addrs) {
            if (a instanceof java.net.Inet4Address) {
                v4 = a;
                break;
            }
        }
        if (v4 == null) {
            throw new java.net.UnknownHostException("no IPv4 for " + host);
        }
        Socket socket = null;
        try {
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                socket = SSLSocketFactory.getDefault().createSocket();
                socket.connect(new InetSocketAddress(v4, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                SSLSocket ssl = (SSLSocket) socket;
                try {
                    SSLParameters p = ssl.getSSLParameters();
                    p.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
                    ssl.setSSLParameters(p);
                } catch (Throwable snie) {
                    Log.w(TAG, "CloudLyric: SNI set fail: " + snie.getMessage());
                }
                ssl.startHandshake();
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(v4, port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
            }
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n"
                    + "User-Agent: " + ua + "\r\n"
                    + (referer != null ? "Referer: " + referer + "\r\n" : "")
                    + "Accept: */*\r\n\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(req.getBytes("UTF-8"));
            os.flush();
            InputStream is = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev = -1, cur;
            int headerEnd = 0;
            while ((cur = is.read()) != -1) {
                head.write(cur);
                if (prev == '\r' && cur == '\n') {
                    byte[] b = head.toByteArray();
                    int len = b.length;
                    if (len >= 4 && b[len - 4] == '\r' && b[len - 3] == '\n' && b[len - 2] == '\r' && b[len - 1] == '\n') {
                        headerEnd = len;
                        break;
                    }
                }
                prev = cur;
            }
            if (headerEnd == 0) {
                throw new java.io.IOException("malformed response");
            }
            String headerStr = new String(head.toByteArray(), 0, headerEnd, "ISO-8859-1");
            int sp1 = headerStr.indexOf(' ');
            int sp2 = headerStr.indexOf(' ', sp1 + 1);
            int code = -1;
            if (sp1 >= 0 && sp2 > sp1) {
                try {
                    code = Integer.parseInt(headerStr.substring(sp1 + 1, sp2).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (code < 0) {
                throw new java.io.IOException("bad status line: " + headerStr);
            }
            int contentLength = -1;
            String lowerHeader = headerStr.toLowerCase();
            int ci = lowerHeader.indexOf("content-length:");
            if (ci >= 0) {
                int eol = headerStr.indexOf('\n', ci);
                String v = headerStr.substring(ci + 15, eol > 0 ? eol : headerStr.length()).trim();
                try {
                    contentLength = Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                }
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            if (contentLength >= 0) {
                while (total < contentLength && (n = is.read(buf, 0, Math.min(buf.length, contentLength - total))) != -1) {
                    body.write(buf, 0, n);
                    total += n;
                }
            } else {
                while ((n = is.read(buf)) != -1) {
                    body.write(buf, 0, n);
                    total += n;
                }
            }
            // W→D 降噪——每次 HTTP 响应都打 W(含 45 字节错误体×4 源),
            // 真车云歌词不可用时每首歌刷 ~8 行 W → logcat 洪峰之一。成功/失败都属
            // 正常容错流程,降为 D 级。
            Log.d(TAG, "CloudLyric: body read " + total + " bytes");
            String bodyStr = new String(body.toByteArray(), "UTF-8");
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code + " (" + host + path + ")");
            }
            return bodyStr;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

 /**
 */
    public static List<LrcLine> parseLrc(String lrc) {
        List<LrcLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return lines;
        }
        String[] raw = lrc.split("\n");
        for (String line : raw) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            int idx = 0;
            while (idx < s.length() && s.charAt(idx) == '[') {
                int end = s.indexOf(']', idx);
                if (end == -1) {
                    break;
                }
                String tag = s.substring(idx + 1, end);
                long t = parseTime(tag);
                if (t >= 0) {
                    String text = s.substring(end + 1);
                    if (text.trim().isEmpty()) {
                        idx = end + 1;
                        continue;
                    }
                    lines.add(new LrcLine(t, text));
                }
                idx = end + 1;
            }
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return lines;
    }

    private static long parseTime(String s) {
        try {
            String[] mmss = s.split(":");
            if (mmss.length < 2) {
                return -1;
            }
            int mm = Integer.parseInt(mmss[0].trim());
            String ssPart = mmss[1].trim();
            double sec = Double.parseDouble(ssPart);
            return (long) (mm * 60000L + sec * 1000.0);
        } catch (Throwable t) {
            return -1;
        }
    }
}

