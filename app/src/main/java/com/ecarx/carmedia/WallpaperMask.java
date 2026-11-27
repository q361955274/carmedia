package com.ecarx.carmedia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * v1.6.31：位图固定横版 1296×720（1.8:1，匹配横版槽位）。
 * 宿主 OPTION 上报的是竖版 306×546（不可信，实测槽位为横版），
 * 故不使用 getCardSizePx 的竖版尺寸，避免 fitXY 拉成竖版。
 * 圆角用 BitmapShader + drawRoundRect（抗锯齿，不依赖 alpha 蒙版，
 * 避免 DST_IN 蒙版透明通道在宿主侧丢失导致"没圆角"），圆角 18px。
 */
public class WallpaperMask {

    public static Bitmap round(Context ctx, Bitmap src) {
        return roundImpl(ctx, src, 1296, 720);
    }

    public static Bitmap round(Context ctx, Bitmap src, int targetW, int targetH) {
        // 忽略传入的宿主竖版尺寸，固定横版 1.8:1
        return roundImpl(ctx, src, 1296, 720);
    }

    private static Bitmap roundImpl(Context ctx, Bitmap src, int w, int h) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);

            // center-crop：把源图等比缩放铺满目标，四周居中
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            float scaledW = src.getWidth() * scale;
            float scaledH = src.getHeight() * scale;
            float tx = (w - scaledW) / 2f;
            float ty = (h - scaledH) / 2f;

            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(tx, ty);

            BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(shader);

            // 直接画带圆角的矩形，边缘抗锯齿，不依赖 alpha 蒙版
            float r = 18.0f;
            RectF rect = new RectF(0f, 0f, (float) w, (float) h);
            c.drawRoundRect(rect, r, r, p);

            return out;
        } catch (Throwable t) {
            return src;
        }
    }
}
