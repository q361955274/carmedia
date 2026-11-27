package com.ecarx.carmedia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ★移植 MediaBridge（com.geely.auto.music.CoverFallback）：
 * 封面兜底——音源未带封面（ART_URI 为空）时，按「歌名 歌手」在网易云/QQ 搜索专辑封面，
 * 下载后经 ArtworkHelper.saveArtwork 落盘为 content:// URI，回调通知 CarMediaService 重推全量。
 *
 * 与 MediaBridge 原版差异：
 * - 去掉反射 startService 触发 UPDATE_ACTION（CarMedia 同包，改为 ResolveCallback 直接回调）；
 * - ArtworkHelper.saveArtwork 同包直接调用；
 * - 防抖语义保留：同曲缓存 / 失败 60s 冷却 / 单飞（防重入）。
 */
public final class CoverFallback {
    private static final String TAG = "CoverFallback";
    private static volatile Uri sCached;
    private static volatile long sFailUntil;
    private static volatile boolean sInFlight;
    private static volatile String sKey;

 /** 解析成功回调（CarMediaService.onCoverFallbackResolved 在主线程处理）。 */
    public interface ResolveCallback {
        void onResolved(Uri uri);
    }

    private CoverFallback() {
    }

 /** 缓存命中查询（key=「歌名|歌手」小写；同曲且已解析成功时直接复用）。 */
    public static Uri getCachedUri(String title, String artist) {
        String makeKey = makeKey(title, artist);
        if (makeKey != null && makeKey.equals(sKey)) {
            return sCached;
        }
        return null;
    }

 /**
 * 发起封面兜底请求（异步线程，daemon，不阻塞主线程）。
 * 条件：title 非空 && 不在失败冷却期 && 非同一曲的重复飞行请求。
     * 成功 → onResolved(content:// URI)；失败 → 冷却 60s 后允许重试。
 */
    public static void request(final Context context, final String title, final String artist,
                               final ResolveCallback callback) {
        final String makeKey;
        if (context == null || (makeKey = makeKey(title, artist)) == null
                || SystemClock.elapsedRealtime() < sFailUntil) {
            return;
        }
        if (!makeKey.equals(sKey) || (!sInFlight && sCached == null)) {
            sKey = makeKey;
            sInFlight = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Uri resolved = resolve(context, title, artist);
                        if (resolved != null) {
                            sCached = resolved;
                            Log.i(TAG, "cover resolved -> " + resolved);
                            if (callback != null) {
                                callback.onResolved(resolved);
                            }
                        } else {
                            Log.d(TAG, "no cover found for '" + makeKey + "'");
                            sFailUntil = SystemClock.elapsedRealtime() + 60000L;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "cover fallback error: " + t.getMessage());
                    } finally {
                        sInFlight = false;
                    }
                }
            });
            thread.setName("cover-fallback");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static String makeKey(String title, String artist) {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        return (title.trim() + "|" + (artist == null ? "" : artist.trim())).toLowerCase();
    }

    private static Uri resolve(Context context, String title, String artist) {
        try {
            String pic = searchNetease(title, artist);
            if (pic == null) {
                pic = searchQQ(title, artist);
            }
            if (pic == null || pic.isEmpty()) {
                return null;
            }
            Bitmap bmp = downloadBitmap(pic);
            if (bmp == null) {
                return null;
            }
            return ArtworkHelper.saveArtwork(context, bmp);
        } catch (Throwable t) {
            Log.w(TAG, "resolve failed: " + t.getMessage());
            return null;
        }
    }

 /** 网易云搜索：/api/search/get 命中首曲 → /api/song/detail 取 album.picUrl（退 blurPicUrl）。 */
    private static String searchNetease(String title, String artist) throws Exception {
        String body = httpGet("https://music.163.com/api/search/get?s="
                + URLEncoder.encode(join(title, artist), "UTF-8")
                + "&type=1&offset=0&limit=1", "https://music.163.com/", 8000);
        if (body == null) {
            return null;
        }
        JSONObject result = new JSONObject(body).optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        if (songs == null || songs.length() == 0 || songs.optJSONObject(0) == null) {
            return null;
        }
        long id = songs.optJSONObject(0).optLong("id", 0L);
        if (id == 0L) {
            return null;
        }
        String detail = httpGet("https://music.163.com/api/song/detail/?ids="
                + URLEncoder.encode("[" + id + "]", "UTF-8"), "https://music.163.com/", 8000);
        if (detail == null) {
            return null;
        }
        JSONArray list = new JSONObject(detail).optJSONArray("songs");
        if (list == null || list.length() == 0 || list.optJSONObject(0) == null
                || list.optJSONObject(0).optJSONObject("album") == null) {
            return null;
        }
        JSONObject album = list.optJSONObject(0).optJSONObject("album");
        String pic = album.optString("picUrl", null);
        if (pic == null || pic.isEmpty()) {
            pic = album.optString("blurPicUrl", null);
        }
        return (pic == null || pic.isEmpty()) ? null : pic;
    }

 /** QQ 搜索：client_search_cp 取首曲 albummid → y.gtimg.cn 300x300 封面。 */
    private static String searchQQ(String title, String artist) throws Exception {
        String body = httpGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=1&format=json&w="
                + URLEncoder.encode(join(title, artist), "UTF-8"), "https://y.qq.com/", 8000);
        if (body == null) {
            return null;
        }
        JSONObject data = new JSONObject(body).optJSONObject("data");
        JSONObject song = data == null ? null : data.optJSONObject("song");
        JSONArray list = song == null ? null : song.optJSONArray("list");
        if (list == null || list.length() == 0 || list.optJSONObject(0) == null) {
            return null;
        }
        String albumMid = list.optJSONObject(0).optString("albummid", null);
        if (albumMid == null || albumMid.isEmpty()) {
            return null;
        }
        return "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
    }

    private static String join(String title, String artist) {
        String t = title == null ? "" : title.trim();
        String a = artist != null ? artist.trim() : "";
        if (a.isEmpty()) {
            return t;
        }
        return t + " " + a;
    }

    private static String httpGet(String url, String referer, int timeout) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", referer);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.d(TAG, "http get failed: " + url + " -> " + t);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Bitmap downloadBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", "https://music.163.com/");
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            byte[] data = out.toByteArray();
            if (data.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            Log.d(TAG, "bitmap download failed: " + url + " -> " + t);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
