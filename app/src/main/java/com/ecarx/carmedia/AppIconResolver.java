package com.ecarx.carmedia;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * App 图标解析（移植自 MediaBridge / carMedia_fixed_icon_artwork，重构）。
 *
 * 重构：
 * - 去掉反射（原注释误称 getApplicationIcon(String) 为 @hide，实际是公开 API）
 * - 新增 drawableToUri() / drawableToDataUri() 公共方法
 * - drawableToDataUri() 返回 base64 data URI，可直接用于 WebView <img src>，
 *    不受 file:// 页面 content:// 访问限制（WebView 从 file:// 加载 content:// 会被拦截）
 */
public final class AppIconResolver {

    private static final int ICON_SIZE = 128;

    private AppIconResolver() {
    }

 /**
 * 将任意 Drawable 画到 128x128 ARGB_8888 画布，返回 Bitmap。
 * 不直接用 BitmapDrawable.getBitmap()，避免系统回收/格式不一致/AdaptiveIconDrawable 渲染异常。
 */
    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        drawable.draw(canvas);
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0 || bitmap.isRecycled()) {
            Log.w("AppIconResolver", "drawableToBitmap: invalid bitmap");
            return null;
        }
        return bitmap;
    }

 /**
     * 将任意 Drawable 转成 content:// URI（落盘到 filesDir/artwork）。
     * 注意：WebView 从 file:// 页面加载 content:// 图片可能被安全策略拦截，
 * 如需在 WebView 中显示，优先用 drawableToDataUri()。
 *
 * @param context 上下文
 * @param drawable 图标 Drawable（为 null 时返回 null）
     * @return content:// URI 字符串，失败返回 null
 */
    public static String drawableToUri(Context context, Drawable drawable) {
        try {
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) {
                return null;
            }
            Uri uri = ArtworkHelper.saveArtwork(context, bitmap);
            if (uri != null) {
                return uri.toString();
            }
        } catch (Throwable t) {
            Log.w("AppIconResolver", "drawableToUri failed", t);
        }
        return null;
    }

 /**
 * 将任意 Drawable 转成 base64 data URI（data:image/png;base64,...）。
     * 可直接用于 WebView <img src="...">，不受 file:// 页面同源策略限制。
 * 128x128 PNG 约 5-25KB，base64 后约 7-33KB，App 列表场景可接受。
 *
 * @param drawable 图标 Drawable（为 null 时返回 null）
 * @return base64 data URI 字符串，失败返回 null
 */
    public static String drawableToDataUri(Drawable drawable) {
        try {
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos);
            byte[] bytes = baos.toByteArray();
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            return "data:image/png;base64," + base64;
        } catch (Throwable t) {
            Log.w("AppIconResolver", "drawableToDataUri failed", t);
        }
        return null;
    }

 /**
     * 按包名解析 App 图标，返回 content:// URI。
 * 内部用公开 API PackageManager.getApplicationIcon(String)（非 @hide）。
 * 如果调用方已有 ResolveInfo，建议直接用 ri.loadIcon(pm) + drawableToUri/DataUri()。
 *
 * @param context 上下文
 * @param pkg 包名（为空直接返回 fallback）
 * @param fallback 解析失败时返回的兜底 URI 字符串
     * @return 图标 content:// URI 字符串，失败返回 fallback
 */
    public static String resolve(Context context, String pkg, String fallback) {
        if (TextUtils.isEmpty(pkg)) {
            return fallback;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Drawable drawable = pm.getApplicationIcon(pkg);
            String uri = drawableToUri(context, drawable);
            if (uri != null) {
                return uri;
            }
        } catch (Throwable t) {
            Log.w("AppIconResolver", "resolve failed for " + pkg, t);
        }
        return fallback;
    }
}
