package com.ecarx.carmedia;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.media.session.PlaybackState;

/**
 * 晴空主题 · 桌面小组件（第二种布局，横版）。
 * 布局 widget_carmedia_sky.xml 照抄 ui.html skin-sky：
 * 左侧 = 音源图标/名称 + 大封面 + 歌名歌手 + 蓝色渐变进度条+时间 + 控制按钮；
 * 右侧 = 渐变封面背景 + 多行歌词(±4，当前句发光蓝 #3BA0FF) + 歌词字号调节按钮。
 * 数据源与主卡片一致（CarMediaService），控制按钮走 CardActionReceiver。
 * 歌词字号与旧卡片共享 widget_cfg 的 lyric_size_mode（可独立调节，互不干扰）。
 */
public class CarMediaSkyWidget extends AppWidgetProvider {

    public static final String TAG = "CarMediaSkyWidget";

    private static volatile String sLastTitle = "";
    private static volatile String sLastArtist = "";
    private static volatile String sLastLyricM4 = "";
    private static volatile String sLastLyricM3 = "";
    private static volatile String sLastLyricM2 = "";
    private static volatile String sLastLyricM1 = "";
    private static volatile String sLastLyricCur = "";
    private static volatile String sLastLyricN1 = "";
    private static volatile String sLastLyricN2 = "";
    private static volatile String sLastLyricN3 = "";
    private static volatile String sLastLyricN4 = "";
    private static volatile int sLastLyricSizeMode = -1;
    private static volatile boolean sLastPlaying = false;
    private static volatile String sLastSource = "";
    private static volatile String sLastArtworkUri = "";
    private static volatile long sLastPos = -1;
    private static volatile long sLastDur = -1;
    private static volatile int sLastUiMode = -1;
    private static volatile String sLastSourcePkg = "";
    private static volatile int sLastHalf = -1;
    // ★ 晴空小组件文字颜色自适配：按封面分区明暗判定（默认深底→浅字，直到封面分区亮度判定覆盖）。
    // sInfoDark=左侧信息区亮度、sLyricDark=右侧歌词区亮度，分区判定避免"主体浅、歌词区深"误判。
    private static volatile boolean sInfoDark = true;
    private static volatile boolean sLyricDark = true;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        Log.i(TAG, "[DBG] onUpdate called, ids=" + java.util.Arrays.toString(appWidgetIds));
        updateAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, android.os.Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (CarMediaWidget.ACTION_WIDGET_UPDATE.equals(action)) {
            updateAll(context);
        } else if ("com.ecarx.carmedia.WIDGET_LYRIC_DEC".equals(action)
                || "com.ecarx.carmedia.WIDGET_LYRIC_INC".equals(action)) {
            android.content.SharedPreferences sp = context.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE);
            int mode = sp.getInt("lyric_size_mode", 1);
            if ("com.ecarx.carmedia.WIDGET_LYRIC_INC".equals(action)) {
                if (mode < 3) mode++;
            } else {
                if (mode > 0) mode--;
            }
            sp.edit().putInt("lyric_size_mode", mode).apply();
            sLastLyricSizeMode = -1;
            updateAll(context);
        }
    }

    public static int[] getWidgetIds(Context context) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(context);
            return m.getAppWidgetIds(new ComponentName(context, CarMediaSkyWidget.class));
        } catch (Throwable t) {
            return new int[0];
        }
    }

 /** 外部（CarMediaService 轮询/切歌）可调用，刷新所有晴空卡片。 */
    public static void updateAll(final Context context) {
        try {
            final int[] ids = getWidgetIds(context);
            if (ids == null || ids.length == 0) return;
            CarMediaService svc = CarMediaService.sInstance;
            if (svc == null || svc.getControllerPkg() == null) {
                try {
                    RemoteViews rv0 = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
                    bindClicks(context, rv0);
                    rv0.setTextViewText(R.id.sk_title_text, context.getString(R.string.widget_default_title));
                    rv0.setTextViewText(R.id.sk_artist, context.getString(R.string.widget_default_artist));
                    rv0.setTextViewText(R.id.sk_lyric_cur, context.getString(R.string.widget_default_lyric));
                    rv0.setTextViewText(R.id.sk_time_cur, "00:00");
                    rv0.setTextViewText(R.id.sk_time_dur, "00:00");
                    AppWidgetManager.getInstance(context).updateAppWidget(ids, rv0);
                } catch (Throwable ignored) {}
                return;
            }
            String title = svc.getMetaTitle();
            if (title == null || title.isEmpty()) title = svc.getDisplayTitle();
            String artist = svc.getMetaArtist();
            if (artist == null || artist.isEmpty()) artist = svc.getDisplayArtist();
            String artwork = svc.getCurrentArtworkUri();
            if (artwork == null) artwork = "";
            int state = svc.getPlaybackState();
            String sourcePkg = svc.getControllerPkg();
            String sourceName = sourceNameOf(context, sourcePkg);
            long pos = svc.getPlaybackPosition();
            long dur = svc.getPlaybackDuration();

            // ---- 歌词行数自适应：按卡片实际高度 + 当前字号估算能放几行（最多 ±4）----
            android.content.SharedPreferences lspc = context.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE);
            int lmodec = lspc.getInt("lyric_size_mode", 1);
            float[] szc = {32f, 48f, 64f, 78f};
            int half = 4;
            try {
                int minH = 0;
                AppWidgetManager awm0 = AppWidgetManager.getInstance(context);
                for (int id : ids) {
                    android.os.Bundle opt = awm0.getAppWidgetOptions(id);
                    if (opt == null) continue;
                    int h = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                    if (h > 0 && (minH == 0 || h < minH)) minH = h;
                }
                if (minH > 0) {
                    // 右侧歌词区可用高度 ≈ 卡片高 - 上下padding(28) - 顶部分调节行(40+8) - 底部余量(8)
                    float area = minH - 28f - 48f - 8f;
                    float lineH = szc[lmodec] * 1.05f + 4f;
                    int fit = (int) (area / lineH);
                    if (fit < 1) fit = 1;
                    half = (fit - 1) / 2;
                    if (half > 4) half = 4;
                }
            } catch (Throwable ignored) { half = 4; }
            // ★ (Bug: 卡片 resize 后歌词行数/布局不重排): 原实现歌词可见性只看"文本是否变化"，
            // 卡片尺寸变了但歌词没变时 changed=false → 提前 return → half 变了却不推送 → 歌词行数不更新。
            // 修复:记录 sLastHalf，尺寸变化(或字号变化)导致 half 变化时强制按新 half 重设歌词可见性。
            boolean halfChanged = (half != sLastHalf);
            if (halfChanged) sLastHalf = half;

            // ---- 多行歌词：当前行 ±half（最多 ±4 共 9 行），按卡片高度自适应 ----
            String lyricM4 = "", lyricM3 = "", lyricM2 = "", lyricM1 = "", lyric = "", lyricN1 = "", lyricN2 = "", lyricN3 = "", lyricN4 = "";
            try {
                java.util.List<String> lines = svc.getFullLyricLines();
                int idx = svc.getCurrentLyricIndex();
                if (lines != null && !lines.isEmpty() && idx >= 0) {
                    lyricM4 = (half >= 4 && idx - 4 >= 0) ? lines.get(idx - 4) : "";
                    lyricM3 = (half >= 3 && idx - 3 >= 0) ? lines.get(idx - 3) : "";
                    lyricM2 = (half >= 2 && idx - 2 >= 0) ? lines.get(idx - 2) : "";
                    lyricM1 = (half >= 1 && idx - 1 >= 0) ? lines.get(idx - 1) : "";
                    lyric = (idx < lines.size()) ? lines.get(idx) : "";
                    lyricN1 = (half >= 1 && idx + 1 < lines.size()) ? lines.get(idx + 1) : "";
                    lyricN2 = (half >= 2 && idx + 2 < lines.size()) ? lines.get(idx + 2) : "";
                    lyricN3 = (half >= 3 && idx + 3 < lines.size()) ? lines.get(idx + 3) : "";
                    lyricN4 = (half >= 4 && idx + 4 < lines.size()) ? lines.get(idx + 4) : "";
                }
            } catch (Throwable ignored) {}
            if (lyricM4 == null) lyricM4 = "";
            if (lyricM3 == null) lyricM3 = "";
            if (lyricM2 == null) lyricM2 = "";
            if (lyricM1 == null) lyricM1 = "";
            if (lyric == null || lyric.isEmpty()) lyric = context.getString(R.string.widget_default_lyric);
            if (lyricN1 == null) lyricN1 = "";
            if (lyricN2 == null) lyricN2 = "";
            if (lyricN3 == null) lyricN3 = "";
            if (lyricN4 == null) lyricN4 = "";
            if (title == null || title.isEmpty()) title = context.getString(R.string.widget_default_title);
            if (artist == null || artist.isEmpty()) artist = context.getString(R.string.widget_default_artist);
            if (pos < 0) pos = 0;
            if (dur < 0) dur = 0;

            boolean uiModeChanged = false;
            try {
                int night = context.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (night != sLastUiMode) {
                    sLastUiMode = night;
                    uiModeChanged = true;
                }
            } catch (Throwable ignored) {}

            final RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
            boolean changed = false;

            if (uiModeChanged || !title.equals(sLastTitle)) {
                rv.setTextViewText(R.id.sk_title_text, title);
                changed = true;
            }
            if (uiModeChanged || !artist.equals(sLastArtist)) {
                rv.setTextViewText(R.id.sk_artist, artist);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricM4.equals(sLastLyricM4)) {
                rv.setTextViewText(R.id.sk_lyric_m4, lyricM4);
                rv.setViewVisibility(R.id.sk_lyric_m4, (half < 4 || lyricM4.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricM3.equals(sLastLyricM3)) {
                rv.setTextViewText(R.id.sk_lyric_m3, lyricM3);
                rv.setViewVisibility(R.id.sk_lyric_m3, (half < 3 || lyricM3.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricM2.equals(sLastLyricM2)) {
                rv.setTextViewText(R.id.sk_lyric_m2, lyricM2);
                rv.setViewVisibility(R.id.sk_lyric_m2, (half < 2 || lyricM2.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricM1.equals(sLastLyricM1)) {
                rv.setTextViewText(R.id.sk_lyric_m1, lyricM1);
                rv.setViewVisibility(R.id.sk_lyric_m1, (half < 1 || lyricM1.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || !lyric.equals(sLastLyricCur)) {
                rv.setTextViewText(R.id.sk_lyric_cur, lyric);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricN1.equals(sLastLyricN1)) {
                rv.setTextViewText(R.id.sk_lyric_n1, lyricN1);
                rv.setViewVisibility(R.id.sk_lyric_n1, (half < 1 || lyricN1.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricN2.equals(sLastLyricN2)) {
                rv.setTextViewText(R.id.sk_lyric_n2, lyricN2);
                rv.setViewVisibility(R.id.sk_lyric_n2, (half < 2 || lyricN2.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricN3.equals(sLastLyricN3)) {
                rv.setTextViewText(R.id.sk_lyric_n3, lyricN3);
                rv.setViewVisibility(R.id.sk_lyric_n3, (half < 3 || lyricN3.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            if (uiModeChanged || halfChanged || !lyricN4.equals(sLastLyricN4)) {
                rv.setTextViewText(R.id.sk_lyric_n4, lyricN4);
                rv.setViewVisibility(R.id.sk_lyric_n4, (half < 4 || lyricN4.isEmpty()) ? View.GONE : View.VISIBLE);
                changed = true;
            }
            // ---- 歌词字号：随 mode 变化才设置（避免每秒轮询重置 RemoteViews 导致滚动抖动）。
            // ★ 加 uiModeChanged——切日夜全量 updateAppWidget 会把布局重置为 XML
            // 默认字号与标签"32"，仅 lmode 防抖会跳过重设导致档位失效。----
            android.content.SharedPreferences lsp = context.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE);
            int lmode = lsp.getInt("lyric_size_mode", 1);
            if (uiModeChanged || lmode != sLastLyricSizeMode) {
                // 档位与旧卡片完全一致：32 / 48 / 64 / 78
                float[] sizes = {32f, 48f, 64f, 78f};
                float sz = sizes[lmode];
                rv.setTextViewTextSize(R.id.sk_lyric_m4, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_m3, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_m2, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_m1, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_cur, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_n1, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_n2, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_n3, TypedValue.COMPLEX_UNIT_SP, sz);
                rv.setTextViewTextSize(R.id.sk_lyric_n4, TypedValue.COMPLEX_UNIT_SP, sz);
                String[] sizeLabels = {"32", "48", "64", "78"};
                rv.setTextViewText(R.id.sk_lyric_size_label, sizeLabels[lmode]);
                changed = true;
            }
            boolean sourceBound = false;
            if (uiModeChanged || !sourceName.equals(sLastSource)
                    || (sourcePkg != null && !sourcePkg.equals(sLastSourcePkg))) {
                rv.setTextViewText(R.id.sk_source_name, sourceName);
                changed = true;
                sourceBound = bindSourceClick(context, rv, sourcePkg);
            }
            // 音源图标：随 pkg 变化加载
            if ((uiModeChanged || sourcePkg == null || !sourcePkg.equals(sLastSourcePkg))) {
                loadSourceIconAsync(context, sourcePkg, ids);
            }
            boolean playing = (state == PlaybackState.STATE_PLAYING);
            if (uiModeChanged || playing != sLastPlaying) {
                rv.setImageViewResource(R.id.sk_play_icon,
                        playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
                changed = true;
            }
            // 进度条 + 时间：独立 RemoteViews 单独提交，不进入主 rv，也不置 changed。
            // ★ 之前为保歌名跑马灯加了 ≥5000ms 节流(宿主对任何部分更新都整体重绘打断跑马灯)。
            // 用户已去掉跑马灯(歌名改 ellipsize=end)，节流不再需要 → 移除，进度条恢复随 500ms 轮询流畅刷新。
            String tc = fmtTime(pos), td = fmtTime(dur);
            if (uiModeChanged || pos != sLastPos || dur != sLastDur) {
                try {
                    RemoteViews rvP = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
                    rvP.setTextViewText(R.id.sk_time_cur, tc);
                    rvP.setTextViewText(R.id.sk_time_dur, td);
                    float ratio = dur > 0 ? (float) pos / (float) dur : 0f;
                    if (ratio < 0f) ratio = 0f;
                    if (ratio > 1f) ratio = 1f;
                    rvP.setProgressBar(R.id.sk_seek, 1000, (int) (1000 * ratio), false);
                    AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rvP);
                } catch (Throwable ignored) {}
            }
            // ★ 文字颜色按封面分区亮度自适配（亮区→深字、暗区→浅字），不再依赖固定日夜色
            applySkyTextColors(context, rv, sInfoDark, sLyricDark);
            if (!changed) return;
            // 音源点击绑定到对应 App 时必须走全量 update：partiallyUpdateAppWidget 不会应用 setOnClickPendingIntent，
            // 且 bindClicks 会把音源点击重置为进主界面，需在全量后重新 bindSourceClick 覆盖回对应 App。
            if (uiModeChanged || sourceBound) {
                bindClicks(context, rv);
                bindSourceClick(context, rv, sourcePkg);
                AppWidgetManager.getInstance(context).updateAppWidget(ids, rv);
            } else {
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rv);
            }
            sLastTitle = title;
            sLastArtist = artist;
            sLastLyricM4 = lyricM4;
            sLastLyricM3 = lyricM3;
            sLastLyricM2 = lyricM2;
            sLastLyricM1 = lyricM1;
            sLastLyricCur = lyric;
            sLastLyricN1 = lyricN1;
            sLastLyricN2 = lyricN2;
            sLastLyricN3 = lyricN3;
            sLastLyricN4 = lyricN4;
            sLastLyricSizeMode = lmode;
            sLastPlaying = playing;
            sLastSource = sourceName;
            sLastSourcePkg = sourcePkg;
            sLastPos = pos;
            sLastDur = dur;
            sLastArtworkUri = artwork;
            if (uiModeChanged || !artwork.isEmpty()) {
                final String artworkFinal = artwork;
                loadCoverAsync(context, artworkFinal, new CoverCallback() {
                    @Override
                    public void onCover(Bitmap bmp) {
                        try {
                            if (bmp == null) return;
                            String now = (CarMediaService.sInstance != null
                                    ? CarMediaService.sInstance.getCurrentArtworkUri() : null);
                            if (now == null || !now.equals(artworkFinal)) return;
                            // ★ 分区判定明暗：整卡背景封面烤成横版圆角位图后，左侧信息区与右侧歌词区
                            // 分别取亮度——避免"封面主体浅、歌词区恰为深色"时整卡均值判浅导致歌词看不清。
                            // ★ 圆角：与主卡完全同机制——WallpaperMask.round 烤 1296×720(1.8:1) 圆角封面位图，
                            // set 到最底层 sk_wallpaper(fitXY，四角透明露出根 sk_card_bg 圆角底)。布局已删除
                            // sk_lyric_bg_img / sk_lyric_scrim 两个方形盖层，整卡只有这一层背景，圆角必然成立。
                            Bitmap bgRounded = WallpaperMask.round(context, bmp);
                            Bitmap coverRounded = roundBitmap(bmp, 22f);
                            Bitmap ref = (bgRounded != null && bgRounded != bmp) ? bgRounded : bmp;
                            sInfoDark = regionDark(ref, 0f, 0.45f);    // 左侧信息区（源名/歌名/歌手/进度）
                            sLyricDark = regionDark(ref, 0.45f, 1f);   // 右侧歌词区
                            RemoteViews rv2 = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
                            applySkyTextColors(context, rv2, sInfoDark, sLyricDark);
                            rv2.setImageViewBitmap(R.id.sk_cover, coverRounded != null ? coverRounded : bmp);
                            rv2.setViewVisibility(R.id.sk_cover, View.VISIBLE);
                            rv2.setViewVisibility(R.id.sk_cover_ph_icon, View.INVISIBLE);
                            // 整卡背景：与主卡 rv_wallpaper 同款——圆角封面位图铺到最底层，四角透明 → 圆角
                            rv2.setImageViewBitmap(R.id.sk_wallpaper, (bgRounded != null && bgRounded != bmp) ? bgRounded : bmp);
                            rv2.setViewVisibility(R.id.sk_wallpaper, View.VISIBLE);
                            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rv2);
                        } catch (Throwable t) {
                            Log.w(TAG, "cover update error: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateAll error: " + t.getMessage());
        }
    }

    private static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long m = s / 60;
        s = s % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private static String sourceNameOf(Context context, String pkg) {
        if (pkg == null) return "";
        String p = pkg.toLowerCase();
        if (p.contains("bluetooth")) return "蓝牙";
        if (p.contains("usb") || p.contains("remount") || p.contains("usbsource")) return "USB";
        if (p.contains("carplay") || p.contains("carlife")) return "手机互联";
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception e) {}
        return "音乐";
    }

    private static void bindClicks(Context context, RemoteViews rv) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(context, 200, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.sk_lyrics, openPi);
        rv.setOnClickPendingIntent(R.id.sk_cover_frame, openPi);
        // 音源图标/名称：点击进主界面（服务就绪后由 bindSourceClick 覆盖为进对应音源 App）
        rv.setOnClickPendingIntent(R.id.sk_source_icon, openPi);
        rv.setOnClickPendingIntent(R.id.sk_source_name, openPi);
        bindAction(context, rv, R.id.sk_btn_prev, CardActionReceiver.ACTION_PREV);
        bindAction(context, rv, R.id.sk_btn_play, CardActionReceiver.ACTION_PLAY_PAUSE);
        bindAction(context, rv, R.id.sk_btn_next, CardActionReceiver.ACTION_NEXT);
        // 歌词字号调节
        Intent dec = new Intent(context, CarMediaSkyWidget.class);
        dec.setAction("com.ecarx.carmedia.WIDGET_LYRIC_DEC");
        PendingIntent decPi = PendingIntent.getBroadcast(context, 299, dec,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.sk_btn_lyric_dec, decPi);
        Intent inc = new Intent(context, CarMediaSkyWidget.class);
        inc.setAction("com.ecarx.carmedia.WIDGET_LYRIC_INC");
        PendingIntent incPi = PendingIntent.getBroadcast(context, 298, inc,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.sk_btn_lyric_inc, incPi);
    }

 /** 把音源图标/名称的点击绑定为打开对应音源 App；成功绑定返回 true（调用方据此走全量更新，因为 partiallyUpdate 不应用 onClickPendingIntent）。 */
    public static boolean bindSourceClick(Context context, RemoteViews rv, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            PendingIntent pi = PendingIntent.getActivity(context, 201, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.sk_source_icon, pi);
            rv.setOnClickPendingIntent(R.id.sk_source_name, pi);
            return true;
        }
        return false;
    }

    private static void bindAction(Context context, RemoteViews rv, int viewId, String action) {
        Intent i = new Intent(context, CardActionReceiver.class);
        i.setAction(action);
        i.setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, viewId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(viewId, pi);
    }

    private static void loadSourceIconAsync(final Context context, final String pkg, final int[] ids) {
        if (pkg == null || pkg.isEmpty()) return;
        if (UsbSourceController.SOURCE_PKG_USB.equals(pkg)) {
            try {
                int iconRes = R.drawable.ic_widget_music;
                final Bitmap noteBmp = decodeResourceToBitmap(context, iconRes);
                if (noteBmp != null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
                                rv.setImageViewBitmap(R.id.sk_source_icon, noteBmp);
                                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rv);
                            } catch (Throwable t) {
                                Log.w(TAG, "USB source icon update error: " + t.getMessage());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "USB source icon fallback error: " + t.getMessage());
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.graphics.drawable.Drawable d = context.getPackageManager().getApplicationIcon(pkg);
                    if (d == null) return;
                    int size = (int) (44 * context.getResources().getDisplayMetrics().density);
                    final Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                    d.setBounds(0, 0, size, size);
                    d.draw(c);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia_sky);
                                rv.setImageViewBitmap(R.id.sk_source_icon, bmp);
                                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rv);
                            } catch (Throwable t) {
                                Log.w(TAG, "source icon update error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable t) {}
            }
        }, "sky-widget-src-icon").start();
    }

    private static Bitmap decodeResourceToBitmap(Context context, int resId) {
        try {
            int size = (int) (34 * context.getResources().getDisplayMetrics().density);
            android.graphics.drawable.Drawable d = context.getResources().getDrawable(resId);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    public interface CoverCallback {
        void onCover(Bitmap bmp);
    }

    private static void loadCoverAsync(final Context context, final String uriStr, final CoverCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = null;
                    if (uriStr.startsWith("content://")) {
                        bmp = decodeUri(context, Uri.parse(uriStr));
                    } else if (uriStr.startsWith("file://")) {
                        try {
                            java.io.File f = new java.io.File(Uri.parse(uriStr).getPath());
                            bmp = decodeFile(f);
                        } catch (Throwable ignored) {}
                    } else if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                        try {
                            bmp = decodeHttp(uriStr);
                        } catch (Throwable ignored) {}
                    }
                    final Bitmap finalBmp = bmp;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onCover(finalBmp);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "loadCover error: " + t.getMessage());
                }
            }
        }, "sky-widget-cover").start();
    }

    private static Bitmap decodeHttp(String urlStr) {
        java.io.InputStream is = null;
        try {
            java.net.URL u = new java.net.URL(urlStr);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            if (data == null || data.length == 0) return null;
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            int scale = sampleSize(opt.outWidth, opt.outHeight);
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = scale;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        } catch (Throwable t) {
            Log.w(TAG, "decodeHttp error: " + t.getMessage());
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap decodeUri(Context context, Uri u) {
        java.io.InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(u);
            if (is == null) return null;
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(is, null, opt);
            try { is.close(); } catch (Throwable ignored) {}
            int scale = sampleSize(opt.outWidth, opt.outHeight);
            is = context.getContentResolver().openInputStream(u);
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = scale;
            return android.graphics.BitmapFactory.decodeStream(is, null, opt);
        } catch (Throwable t) {
            Log.w(TAG, "decodeUri error: " + t.getMessage());
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap decodeFile(java.io.File f) {
        java.io.InputStream is = null;
        try {
            if (!f.exists() || f.length() <= 0) return null;
            is = new java.io.FileInputStream(f);
            android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(is, null, opt);
            try { is.close(); } catch (Throwable ignored) {}
            int scale = sampleSize(opt.outWidth, opt.outHeight);
            is = new java.io.FileInputStream(f);
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = scale;
            return android.graphics.BitmapFactory.decodeStream(is, null, opt);
        } catch (Throwable t) {
            Log.w(TAG, "decodeFile error: " + t.getMessage());
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static int sampleSize(int w, int h) {
        if (w <= 0 || h <= 0) return 1;
        // 封面清晰度。封面同一张图既显示在左侧 120dp，又作右侧整卡背景铺满
        // (最大 570×316dp，屏密度 2x 约需 1100px 宽)。原 512px 对整卡背景仍偏小，提到 1024px
        // 更接近旧件"大分辨率壁纸"级清晰度。1024×1024×4≈4MB，车机 widget 可承受。
        int scale = 1;
        while (w / scale > 1024 || h / scale > 1024) scale *= 2;
        return scale;
    }

 /**
 * 取位图横向 [x0,x1] 区间的平均亮度判明暗（Rec.601 加权，平均 <128 判暗）。
 * 分区判定：左右信息区/歌词区分别取封面对应区间亮度，避免"封面主体浅、歌词区恰为深色"时整图均值误判。
 */
    private static boolean regionDark(Bitmap bmp, float x0, float x1) {
        if (bmp == null) return true;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int sx0 = (int) (w * x0);
            int sx1 = (int) (w * x1);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sx0 < 0) sx0 = 0;
            if (sx1 > w) sx1 = w;
            int sw = Math.max(1, sx1 - sx0);
            int sh = Math.max(1, h / 8);
            // 截取横向区间再缩小，统计该区间亮度
            Bitmap crop = Bitmap.createBitmap(bmp, sx0, 0, sw, h);
            Bitmap small = Bitmap.createScaledBitmap(crop, sw, sh, true);
            if (crop != small) crop.recycle();
            int px = sw * sh;
            int[] pix = new int[px];
            small.getPixels(pix, 0, sw, 0, 0, sw, sh);
            if (small != crop) small.recycle();
            long sum = 0;
            int count = 0;
            int step = Math.max(1, px / 256);   // 最多抽样 ~256 像素
            for (int i = 0; i < px; i += step) {
                int c = pix[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                count++;
            }
            double avg = count > 0 ? (double) sum / count : 0;
            return avg < 128.0;
        } catch (Throwable t) {
            return true;
        }
    }

 /**
 * 按封面分区亮度给晴空小组件文字设色：
 * infoDark 左侧信息区（源名/歌名/歌手/时间/字号标签）——暗→浅字，亮→深字；
 * lyricDark 右侧歌词区（多行歌词/当前句）——暗→浅字，亮→深字。
 * 根因：封面是动态图、可深可浅，固定日夜两套色必然与某些封面撞色导致看不清；
 * 且整卡均值判定不可靠（主体浅但歌词区深会误判），故分区判定。
 * 远端行(±2~4)用更低对比的中性色做层次，当前句用高亮蓝。
 */
    private static void applySkyTextColors(Context context, RemoteViews rv, boolean infoDark, boolean lyricDark) {
        final int title  = infoDark ? 0xFFFFFFFF : 0xFF141A26;   // 歌名 / 字号标签
        final int artist = infoDark ? 0xFFC9D8F2 : 0xFF3A4557;   // 歌手 / 音源名
        final int minor  = infoDark ? 0xFFA9BFE0 : 0xFF5D6E86;   // 时间（次要）
        final int cur    = lyricDark ? 0xFF3BA0FF : 0xFF2563EB;  // 当前句高亮蓝
        // 顺序：m4 m3 m2 m1 cur n1 n2 n3 n4（远→近→远），按歌词区亮度选色
        final int[] ly = lyricDark
                ? new int[]{0xFF5F84B2, 0xFF7399C6, 0xFF88AEDA, 0xFF9DC2EE, cur, 0xFF9DC2EE, 0xFF88AEDA, 0xFF7399C6, 0xFF5F84B2}
                : new int[]{0xFF8295B0, 0xFF6F8199, 0xFF5D6E86, 0xFF4C5B70, cur, 0xFF4C5B70, 0xFF5D6E86, 0xFF6F8199, 0xFF8295B0};
        rv.setTextColor(R.id.sk_title_text, title);
        rv.setTextColor(R.id.sk_artist, artist);
        rv.setTextColor(R.id.sk_source_name, artist);
        rv.setTextColor(R.id.sk_time_cur, minor);
        rv.setTextColor(R.id.sk_time_dur, minor);
        rv.setTextColor(R.id.sk_lyric_size_label, title);
        rv.setTextColor(R.id.sk_lyric_m4, ly[0]);
        rv.setTextColor(R.id.sk_lyric_m3, ly[1]);
        rv.setTextColor(R.id.sk_lyric_m2, ly[2]);
        rv.setTextColor(R.id.sk_lyric_m1, ly[3]);
        rv.setTextColor(R.id.sk_lyric_cur, ly[4]);
        rv.setTextColor(R.id.sk_lyric_n1, ly[5]);
        rv.setTextColor(R.id.sk_lyric_n2, ly[6]);
        rv.setTextColor(R.id.sk_lyric_n3, ly[7]);
        rv.setTextColor(R.id.sk_lyric_n4, ly[8]);
    }

 /** 烤圆角位图（保持原比例，四角透明）。用于左侧大封面（22dp 圆角，对齐 sk_cover_ph）。
 * 先缩到足够显示尺寸（左侧 120dp，density=1 时 240px 富余）再烤，降低车机内存峰值。 */
    private static Bitmap roundBitmap(Bitmap src, float r) {
        if (src == null) return null;
        try {
            Bitmap scaled = src;
            int dst = 240;
            int maxSide = Math.max(src.getWidth(), src.getHeight());
            if (maxSide > dst) {
                float sc = dst / (float) maxSide;
                scaled = Bitmap.createScaledBitmap(src,
                        Math.max(1, (int) (src.getWidth() * sc)), Math.max(1, (int) (src.getHeight() * sc)), true);
            }
            int w = scaled.getWidth(), h = scaled.getHeight();
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.BitmapShader shader =
                    new android.graphics.BitmapShader(scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            p.setShader(shader);
            c.drawRoundRect(new android.graphics.RectF(0f, 0f, (float) w, (float) h), r, r, p);
            if (scaled != src) scaled.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }
}
