package com.ecarx.carmedia;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 汽水音乐歌词抓取器（移植自 MusicLyrics 项目的汽水音乐歌词获取思路）。
 * <p>
 * 链路（均为免签名公开接口，实测可用）：
 * 1. 搜索 : GET api.qishui.com/luna/search/track?q=<歌名 歌手>&aid=386088&device_platform=android...
 * 模拟 Android 客户端 UA，返回歌曲列表（含 track_id / name / artists），无签名。
 * 2. 歌词 : GET beta-luna.douyin.com/luna/h5/seo_track?track_id=<id>&device_platform=web
 * 汽水音乐 PC 端 SEO 接口，浏览器 UA，返回 KRC 逐字歌词（lyric.content）。
 * 3. 转换 : KRC([起始ms,持续ms]<字级标签>文字) -> 标准 LRC[mm:ss.xx]文字 -> LrcUtil.parse
 * <p>
 * 归属校验由上层 CarMediaService.lyricTitleMatches 完成（TextUtil.normalize 归一化比较）。
 */
public class SodaMusicLyricFetcher {

    private static final String TAG = "CarMedia";
    private static final String SEARCH_URL = "https://api.qishui.com/luna/search/track";
    private static final String SEO_URL = "https://beta-luna.douyin.com/luna/h5/seo_track";
 /** 系统 curl 二进制路径（搜索接口按 TLS 指纹对 Conscrypt 降级，curl/BoringSSL 指纹可拿到完整数据）。 */
    private static final String[] CURL_BINS = {"/system/bin/curl", "/system/xbin/curl", "curl"};

    private static final String ANDROID_UA =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; Build/V417IR;tt-ok/3.12.13.19)";
    private static final String PC_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

 /** 常见"非原版"标记，打分时降权（防止 DJ 版/翻唱顶掉原版）。 */
    private static final String[] VERSION_BADGE = {
            "dj", "翻唱", "cover", "伴奏", "remix", "女声", "男声", "live", "铃声", "现场"
    };

    public interface Callback {
        void onLyricReady(String lyric, String title, String artist);

        void onError(String message);
    }

    private final Callback mCallback;
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

    public SodaMusicLyricFetcher(Callback callback) {
        this.mCallback = callback;
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
        } catch (Throwable ignored) {
        }
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

 /** 重置 API 状态灯为空闲（切歌/切换音源/清空歌词时调用，避免成功状态常亮不回落）。 */
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
    public java.util.List<String> getAllLyricLines() {
        List<LrcUtil.Line> lrc = mParsedLrc;
        java.util.List<String> out = new java.util.ArrayList<>();
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
 * 按歌名+歌手搜索并加载汽水音乐歌词（异步，回调 onLyricReady/onError）。
 * 同歌名搜索进行中时复用旧 seq，防并发重复请求。
 */
    public void search(final String title, final String artist) {
        synchronized (mLock) {
            if (mActiveSearches > 0 && title != null && title.equals(mSearchingTitle)
                    && (artist == null ? mSearchingArtist == null : artist.equals(mSearchingArtist))) {
                Log.d(TAG, "SodaLyric: same-title search in progress, reuse #" + mSearchSeq);
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
        Log.d(TAG, "SodaLyric: search start #" + seq + " " + title);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String krc = null;
                    // 汽水 CDN 对 app 进程的搜索请求存在"降级候选"(返回无歌词的隐藏 track),
                    // 且候选集合不稳定。这里对"歌名+歌手"与"仅歌名"各做多轮重试,
                    // 只要某一轮返回的候选里含"有歌词版本"即命中(每轮均为独立连接)。
                    for (int attempt = 0; attempt < 2 && krc == null; attempt++) {
                        java.util.List<String> trackIds = searchTrack(title, artist);
                        if (trackIds != null) {
                            krc = tryFetchLyric(trackIds);
                        }
                        if (krc == null) {
                            Log.i(TAG, "SodaLyric: retry #" + (attempt + 1) + " full-keyword search");
                        }
                    }
                    if (krc == null && artist != null && !artist.isEmpty()) {
                        Log.i(TAG, "SodaLyric: retry with title-only search");
                        for (int attempt = 0; attempt < 2 && krc == null; attempt++) {
                            java.util.List<String> trackIds = searchTrack(title, null);
                            if (trackIds != null) {
                                krc = tryFetchLyric(trackIds);
                            }
                            if (krc == null) {
                                Log.i(TAG, "SodaLyric: retry #" + (attempt + 1) + " title-only search");
                            }
                        }
                    }
                    if (krc == null || krc.trim().isEmpty()) {
                        fail(seq, "no lyric content");
                        return;
                    }
                    applyKrc(seq, krc, title, artist);
                } catch (Throwable t) {
                    Log.w(TAG, "SodaLyric: error: " + t.getMessage());
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
 * 按指定 track_id 直接抓取歌词(调试/验证入口,跳过搜索),复用完整解析与绑定链路。
 * 模拟器端到端验证用:adb shell am startservice -n com.ecarx.carmedia/.CarMediaService
 * -a com.ecarx.carmedia.DEBUG_SODA_LYRIC --es track_id <id>
 */
    public void fetchByTrackId(final String trackId, final String title, final String artist) {
        if (trackId == null || trackId.isEmpty()) {
            return;
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
        Log.i(TAG, "SodaLyric: debugFetch track_id=" + trackId);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String krc = fetchSeoLyric(trackId);
                    if (krc == null || krc.trim().isEmpty()) {
                        fail(seq, "no lyric content (track " + trackId + ")");
                        return;
                    }
                    applyKrc(seq, krc, title, artist);
                } catch (Throwable t) {
                    Log.w(TAG, "SodaLyric: error: " + t.getMessage());
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

 /** KRC 转 LRC → 解析 → 若 seq 仍最新则绑定当前歌词 → 回调。 */
    private void applyKrc(int seq, String krc, String title, String artist) {
        String lrc = krcToLrc(krc);
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
            Log.i(TAG, "SodaLyric: loaded " + parsed.size() + " lines for " + title);
            if (mCallback != null) {
                mCallback.onLyricReady(lrc,
                        title != null ? title : "",
                        artist != null ? artist : "");
            }
        }
    }

 /** 逐个尝试候选 track 的 SEO 歌词,返回第一个非空 KRC。 */
    private String tryFetchLyric(java.util.List<String> trackIds) {
        for (String tid : trackIds) {
            String c;
            try {
                c = fetchSeoLyric(tid);
            } catch (Throwable t) {
                continue;
            }
            if (c != null && !c.trim().isEmpty()) {
                Log.i(TAG, "SodaLyric: lyric found via track_id=" + tid);
                return c;
            }
        }
        return null;
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

 /** 响应前 N 字符（诊断用，去控制字符）。 */
    private static String headOf(String s) {
        if (s == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 160; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c >= 32) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

 /**
 * 用系统 curl 二进制发起 GET（避免 Android HttpURLConnection/Conscrypt 的 TLS 指纹
 * 被汽水 CDN 识别并降级返回"无歌词版本"候选；curl/BoringSSL 指纹返回完整数据）。
 * 返回 null 表示 curl 不可用/执行失败（调用方回退 HttpURLConnection）。
 */
    private static String httpGetCurl(String urlStr, String ua) throws Exception {
        String bin = null;
        for (String c : CURL_BINS) {
            java.io.File f = new java.io.File(c);
            boolean can = false;
            try {
                can = f.canExecute();
            } catch (Throwable t) {
                can = false;
            }
            if (f.exists() && can) {
                bin = c;
                break;
            }
        }
        if (bin == null) {
            Log.w(TAG, "SodaLyric: curl unavailable, fallback HttpURLConnection");
            return null;
        }

        // 汽水 CDN 按请求特征/DNS 轮询可能路由到"无歌词版本"节点；优先强制解析到完整数据节点。
        // 硬编码 IP 失效/不通时,自动去掉 --resolve 走系统 DNS 重试一次(不无限等待)。
        String body = curlExec(bin, urlStr, ua, true);
        if (body == null) {
            Log.w(TAG, "SodaLyric: pinned-IP curl failed, retry via DNS");
            body = curlExec(bin, urlStr, ua, false);
        }
        return body;
    }

 /** 执行一次 curl。pinned=true 带 --resolve 数据节点, false 走系统 DNS。失败/超时返回 null。 */
    private static String curlExec(String bin, String urlStr, String ua, boolean pinned) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(bin);
            cmd.add("-s");
            cmd.add("--max-time");
            cmd.add("5");            // 12s -> 5s,真车弱网不再等十几秒
            cmd.add("-A");
            cmd.add(ua);
            if (pinned) {
                cmd.add("--resolve");
                cmd.add("api.qishui.com:443:112.95.9.93");
            }
            cmd.add("-w");
            cmd.add("\n__remote_ip=%{remote_ip}");
            cmd.add(urlStr);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try {
                java.io.InputStream is = p.getInputStream();
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Throwable ignored) {
            }
            boolean done = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                Log.w(TAG, "SodaLyric: curl timeout (pinned=" + pinned + ")");
                p.destroy();
                return null;
            }
            int ec = p.exitValue();
            byte[] raw = out.toByteArray();
            Log.i(TAG, "SodaLyric: curl exit=" + ec + " pinned=" + pinned + " bytes=" + raw.length);
            if (ec != 0) {
                return null;
            }
            String s = new String(raw, "UTF-8");
            int ri = s.lastIndexOf("\n__remote_ip=");
            if (ri >= 0) {
                Log.i(TAG, "SodaLyric: curl remote_ip=" + s.substring(ri + 13).trim());
                s = s.substring(0, ri);
            }
            Log.i(TAG, "SodaLyric: curl body=" + headOf(s));
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "SodaLyric: curl exec fail: " + t.getMessage());
            return null;
        }
    }

 /**
 * 标准 HTTP GET（HttpURLConnection 自动处理 chunked/gzip/重定向）。
 * 构造函数已设 java.net.preferIPv4Stack=true 强制 IPv4（与 httpGetV4 同目的）。
 */
    private static String httpGet(String urlStr, String ua) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "close");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bout.write(buf, 0, n);
                }
                is.close();
            }
            return new String(bout.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

 /**
 * 汽水音乐搜索接口：按"歌名 歌手"搜索，对候选打分选最匹配的 track_id。
 * 返回 null 表示未搜到。
 */
    private java.util.List<String> searchTrack(String title, String artist) throws Exception {
        String kw = ((title == null ? "" : title) + " " + (artist == null ? "" : artist)).trim();
        String url = SEARCH_URL
                + "?q=" + URLEncoder.encode(kw, "UTF-8")
                + "&cursor=0&count=10&aid=386088&device_platform=android"
                + "&app_name=luna&version_code=100198030";
        String body = null;
        JSONObject json = null;
        try {
            body = httpGetCurl(url, ANDROID_UA);
            if (body == null) {
                Log.w(TAG, "SodaLyric: curl unavailable, fallback HttpURLConnection");
                body = httpGet(url, ANDROID_UA);
            }
            Log.i(TAG, "SodaLyric: url=" + url);
            Log.i(TAG, "SodaLyric: resp=" + headOf(body));
            json = new JSONObject(body);
        } catch (Throwable t) {
            Log.w(TAG, "SodaLyric: searchTrack parse fail: " + t.getMessage()
                    + " head=" + headOf(body));
            return null;
        }
        JSONArray groups = json.optJSONArray("result_groups");
        if (groups == null) {
            return null;
        }
        // 收集所有 (score, id),按分数降序取前 3 个候选——同一首歌可能有多个 track 记录,
        // 其中部分(如 6160)在 SEO 接口无歌词,需回退到其他版本(如 6206)。
        java.util.List<long[]> scored = new java.util.ArrayList<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            JSONArray data = g != null ? g.optJSONArray("data") : null;
            if (data == null) {
                continue;
            }
            for (int j = 0; j < data.length(); j++) {
                JSONObject entity = data.optJSONObject(j).optJSONObject("entity");
                JSONObject t = entity != null ? entity.optJSONObject("track") : null;
                if (t == null || !t.has("id")) {
                    continue;
                }
                String id = String.valueOf(t.optLong("id"));
                String name = t.optString("name");
                String[] arts = new String[0];
                JSONArray ar = t.optJSONArray("artists");
                if (ar != null) {
                    arts = new String[ar.length()];
                    for (int k = 0; k < ar.length(); k++) {
                        arts[k] = ar.optJSONObject(k).optString("name");
                    }
                }
                int sc = scoreTrack(name, arts, title, artist);
                scored.add(new long[]{sc, t.optLong("id")});
            }
        }
        scored.sort((a, b) -> Long.compare(b[0], a[0])); // 分数降序
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int n = 0; n < scored.size() && n < 3; n++) {
            ids.add(String.valueOf(scored.get(n)[1]));
        }
        Log.d(TAG, "SodaLyric: search '" + kw + "' top=" + ids);
        return ids.isEmpty() ? null : ids;
    }

 /** 启发式打分：歌名精确 > 包含 > 字符重叠；歌手匹配加权；非原版降权。 */
    private static int scoreTrack(String name, String[] artists, String title, String artist) {
        String n = TextUtil.normalize(name);
        String nt = TextUtil.normalize(title);
        int s = 0;
        if (n.equals(nt)) {
            s += 100;
        } else if (n.contains(nt) || nt.contains(n)) {
            s += 60;
        } else {
            s += commonChars(n, nt) * 2;
        }
        if (artist != null && !artist.isEmpty()) {
            String na = TextUtil.normalize(artist);
            boolean hit = false, sub = false;
            for (String a : artists) {
                String nn = TextUtil.normalize(a);
                if (nn.equals(na)) {
                    hit = true;
                } else if (nn.contains(na) || na.contains(nn)) {
                    sub = true;
                }
            }
            if (hit) {
                s += 100;
            } else if (sub) {
                s += 50;
            } else {
                s -= 40;
            }
        }
        String low = name.toLowerCase();
        for (String b : VERSION_BADGE) {
            if (low.contains(b)) {
                s -= 25;
            }
        }
        return s;
    }

    private static int commonChars(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        boolean[] seen = new boolean[65536];
        for (int i = 0; i < a.length(); i++) {
            seen[a.charAt(i)] = true;
        }
        int c = 0;
        for (int i = 0; i < b.length(); i++) {
            if (seen[b.charAt(i)]) {
                c++;
            }
        }
        return c;
    }

 /**
 * 汽水音乐 SEO 接口：按 track_id 拿 KRC 逐字歌词。
 * 返回 lyric.content（KRC 格式），空表示无歌词。
 */
    private String fetchSeoLyric(String trackId) throws Exception {
        String url = SEO_URL + "?track_id=" + trackId + "&device_platform=web";
        String body = null;
        JSONObject json = null;
        try {
            body = httpGet(url, PC_UA);
            json = new JSONObject(body);
        } catch (Throwable t) {
            Log.w(TAG, "SodaLyric: fetchSeoLyric parse fail: " + t.getMessage()
                    + " head=" + headOf(body));
            return null;
        }
        JSONObject st = json.optJSONObject("seo_track");
        JSONObject lyric = st != null ? st.optJSONObject("lyric") : null;
        String content = lyric != null ? lyric.optString("content") : "";
        if (content.isEmpty()) {
            JSONObject l2 = json.optJSONObject("lyric");
            if (l2 != null) {
                content = l2.optString("content");
            }
        }
        return content;
    }

 /**
 * KRC -> 标准 LRC：
 * [起始ms,持续ms]<字内偏移,持续,0>文字 -> [mm:ss.xx]文字（去掉字级标签）
 * 铁律遵守：只解析时间戳行，有文本就保留，不做任何元数据过滤。
 */
    static String krcToLrc(String krc) {
        if (krc == null) {
            return "";
        }
        // 铁律(见指引最高优先级):只过滤空行/空文本行,任何有文本的行都必须保留——
        // 包括非时间戳的元数据标签行(如"作词:xxx""纯音乐，请欣赏"),转成 [00:00.00] 显示在开头。
        Pattern lineRe = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
        Pattern wordRe = Pattern.compile("<[^>]+>");
        Pattern tagRe = Pattern.compile("\\[[^\\]]*\\]");
        StringBuilder sb = new StringBuilder();
        for (String line : krc.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue; // 空行过滤(铁律允许)
            }
            Matcher m = lineRe.matcher(s);
            if (m.matches()) {
                long startMs = Long.parseLong(m.group(1));
                String text = wordRe.matcher(m.group(3)).replaceAll("").trim();
                if (text.isEmpty()) {
                    continue; // 空白时间戳行过滤(铁律允许)
                }
                appendLrc(sb, startMs, text);
            } else {
                // 非时间戳行:去掉 [..] 标签和 <..> 后仍有文本 → 保留(时间戳 0,显示在歌曲开头)
                String text = tagRe.matcher(wordRe.matcher(s).replaceAll("")).replaceAll("").trim();
                if (text.isEmpty()) {
                    continue; // 纯标签/纯空白 → 过滤
                }
                appendLrc(sb, 0L, text);
            }
        }
        return sb.toString();
    }

    private static void appendLrc(StringBuilder sb, long startMs, String text) {
        long mm = startMs / 60000;
        long ss = (startMs % 60000) / 1000;
        long cs = (startMs % 1000) / 10;
        sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s%n", mm, ss, cs, text));
    }
}
