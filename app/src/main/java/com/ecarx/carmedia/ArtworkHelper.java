package com.ecarx.carmedia;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 封面图本地缓存（移植自 MediaBridge / carMedia_fixed_icon_artwork）。
 *
 * 作用：把音源 metadata 提供的封面 bitmap/URI 落盘到 filesDir/artwork，
 * 再通过 ArtworkContentProvider（content://com.ecarx.carmedia.artwork/xxx）
 * 共享给 EAS 小卡片/仪表盘。系统 UI 通过 content URI 读取封面，
 * 比裸 http URL（小卡片可能不支持联网加载）可靠。
 *
 * 磁盘策略：
 * - 512px 上限缩放（超过才缩放，OOM 回退原图）
 * - 文件名 = 9 采样像素 + 宽高 的 MD5 前 12 位（同图不重复写盘）
 * - 缓存目录最多保留 10 个文件（app_icon.png 除外）
 */
public class ArtworkHelper {

    private static final String TAG = "ArtworkHelper";
    private static final String ARTWORK_DIR_NAME = "artwork";
    private static final int MAX_WIDTH = 512;
    private static final int MAX_HEIGHT = 512;
    private static final int MAX_CACHED_FILES = 10;
    static final String APP_ICON_FILENAME = "app_icon.png";

    private ArtworkHelper() {
    }

 /** filesDir/artwork（不存在则创建）。 */
    public static File getArtworkDir(Context context) {
        File dir = new File(context.getFilesDir(), ARTWORK_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

 /**
 * 保存封面：缩放 → 哈希命名 → 写盘（已存在跳过）→ 清理旧文件 → 返回 content URI。
 * 返回 null 表示失败（入参 null 或 IO 异常）。
 */
    public static Uri saveArtwork(Context context, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        FileOutputStream fos = null;
        try {
            Bitmap scaled = scaleDownBitmap(bitmap);
            File dir = getArtworkDir(context);
            String hash = generateBitmapHash(scaled);
            String fileName = "artwork_" + hash + ".png";
            File file = new File(dir, fileName);
            if (!file.exists()) {
                fos = new FileOutputStream(file);
                scaled.compress(Bitmap.CompressFormat.PNG, 90, fos);
            }
            cleanupOldArtworks(dir, fileName);
            if (scaled != bitmap) {
                scaled.recycle();
            }
            return ArtworkContentProvider.buildUri(fileName);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save artwork", e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

 /**
 * 保存用户自定义的 EAS 图标/封面：
 * 写到固定文件名（eas_custom_icon.png / eas_custom_cover.png），
 * 与哈希命名的封面缓存不同，不受 cleanupOldArtworks 数量清理影响，
     * 保证用户设置持久有效。返回 content:// URI，失败返回 null。
 */
    public static Uri saveCustomImage(Context context, Bitmap bitmap, String stableName) {
        if (bitmap == null || stableName == null) {
            return null;
        }
        FileOutputStream fos = null;
        try {
            Bitmap scaled = scaleDownBitmap(bitmap);
            File dir = getArtworkDir(context);
            File file = new File(dir, stableName);
            fos = new FileOutputStream(file);
            scaled.compress(Bitmap.CompressFormat.PNG, 90, fos);
            if (scaled != bitmap) {
                scaled.recycle();
            }
            return ArtworkContentProvider.buildUri(stableName);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save custom image " + stableName, t);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

 /**
     * 从 content:// 或 file:// URI 读 bitmap。
 * 显式拒绝 http/https（网络图不在这里下载——避免阻塞主线程，
 * 且 EAS 小卡片对 http URL 的支持依赖系统实现，见 extractArtworkAndPush 的直传分支）。
 */
    public static Bitmap loadBitmapFromUri(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            Log.w(TAG, "Rejecting HTTP URL in loadBitmapFromUri — pass URL to widget directly");
            return null;
        }
        InputStream is = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            is = resolver.openInputStream(uri);
            if (is != null) {
                return BitmapFactory.decodeStream(is);
            }
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load bitmap from URI: " + uri, e);
            return null;
        } catch (SecurityException e) {
            Log.e(TAG, "No permission to access URI: " + uri, e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

 /**
 * 哈希命名：9 个采样像素（四角/边中点/中心等）+ 宽高 → MD5 前 12 位。
 * 内容相同的图得到相同文件名，避免重复写盘。
 */
    private static String generateBitmapHash(Bitmap bitmap) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            StringBuilder sb = new StringBuilder();
            sb.append(w).append("x").append(h);
            // 9 个采样点：x 取 {0, w/2, w-1}，y 取 {0, h/2, h-1} 的组合
            int[] xs = {0, w / 2, w - 1, 0, w / 2, w - 1, 0, w / 2, w - 1};
            int[] ys = {0, 0, 0, h / 2, h / 2, h / 2, h - 1, h - 1, h - 1};
            int xMax = Math.max(0, w - 1);
            int yMax = Math.max(0, h - 1);
            for (int i = 0; i < 9; i++) {
                int px = Math.min(xs[i], xMax);
                int py = Math.min(ys[i], yMax);
                sb.append("_").append(Integer.toHexString(bitmap.getPixel(px, py)));
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                String s = Integer.toHexString(b & 0xff);
                if (s.length() == 1) {
                    hex.append('0');
                }
                hex.append(s);
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

 /** 超过 512x512 才缩放（保持宽高比），OOM 时回退原图。 */
    private static Bitmap scaleDownBitmap(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= MAX_WIDTH && h <= MAX_HEIGHT) {
            return bitmap;
        }
        float scale = Math.min(MAX_WIDTH / (float) w, MAX_HEIGHT / (float) h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);
        try {
            return Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OOM while scaling bitmap, using original", e);
            return bitmap;
        }
    }

 /** 文件数 > 10 时按最后修改时间删除最旧的多余文件（保留当前与 app_icon.png）。 */
    private static void cleanupOldArtworks(File dir, String currentFileName) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_CACHED_FILES) {
                return;
            }
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            int toDelete = files.length - MAX_CACHED_FILES;
            for (int i = 0; i < toDelete; i++) {
                String name = files[i].getName();
                // 用户自定义 EAS 图标/封面（eas_custom_*）永不被清理
                if (name.equals(currentFileName) || name.equals(APP_ICON_FILENAME)
                        || name.startsWith("eas_custom_")) {
                    continue;
                }
                files[i].delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to cleanup old artworks", e);
        }
    }
}
