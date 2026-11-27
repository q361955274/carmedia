package com.ecarx.carmedia;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.util.TypedValue;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.media.session.PlaybackState;

public class CarMediaWidget extends AppWidgetProvider {

    public static final String TAG = "CarMediaWidget";
    public static final String ACTION_WIDGET_UPDATE = "com.ecarx.carmedia.ACTION_WIDGET_UPDATE";

    private static volatile String sLastTitle = "";
    private static int sLastLyricSizeMode = -1;
    private static volatile String sLastArtist = "";
    private static volatile String sLastLyricM10 = "";
    private static volatile String sLastLyricM9 = "";
    private static volatile String sLastLyricM8 = "";
    private static volatile String sLastLyricM7 = "";
    private static volatile String sLastLyricM6 = "";
    private static volatile String sLastLyricM5 = "";
    private static volatile String sLastLyricM4 = "";
    private static volatile String sLastLyricM3 = "";
    private static volatile String sLastLyricM2 = "";
    private static volatile String sLastLyricM1 = "";
    private static volatile String sLastLyricCur = "";
    private static volatile String sLastLyricN1 = "";
    private static volatile String sLastLyricN2 = "";
    private static volatile String sLastLyricN3 = "";
    private static volatile String sLastLyricN4 = "";
    private static volatile String sLastLyricN5 = "";
    private static volatile String sLastLyricN6 = "";
    private static volatile String sLastLyricN7 = "";
    private static volatile String sLastLyricN8 = "";
    private static volatile String sLastLyricN9 = "";
    private static volatile String sLastLyricN10 = "";
    private static volatile int sLastPlayState = -1;
    private static volatile boolean sLastPlaying = false;
    private static volatile int sLastUiMode = -1;
    private static volatile String sLastArtworkUri = "";
    private static volatile String sLastSource = "";
    private static volatile String sLastSourcePkg = "";

    private static volatile android.graphics.Bitmap sWallpaperBmp = null;
    private static volatile boolean sWallpaperDirty = true;
    private static volatile int sWallpaperTick = 0;
    private static volatile boolean sWallpaperLoading = false;   // 壁纸加载互斥，防止并发线程叠加
    private static volatile int sWallpaperFailCount = 0;        // 壁纸加载连续失败次数（自愈重试退避）
    private static volatile long sLastFullUpdateMs = 0;         // 上次全量 updateAppWidget 时刻（自愈周期全量）
    private static final long FULL_REFRESH_INTERVAL_MS = 30000L; // 周期强制全量：让宿主(launcher/cardesk 等 AppWidgetHostView)手里始终是可交互+正确字号的 RemoteViews，宿主重建视图时不会"点不动/字体回默认"
    // 一次性强制走全量：壁纸刚加载好时置 true，让 updateAll 即使无内容变化也做一次含壁纸的全量推送（一次后自动清掉）
    private static volatile boolean sForceFull = false;
    // 已做过全量(updateAppWidget)的 widget id：新放置/宿主重建重挂载的微件不在集合内 → 强制全量，
    // 避免新微件复用全局 sLast* 缓存导致内容去重跳过 setText 而停在默认文案。
    private static final java.util.Set<Integer> sFullUpdatedIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    // 异步加载的音源图标/封面位图缓存：加载完成 → 置 sForceFull → 下一次 updateAll 走全量携带，
    // 避免部分更新(partiallyUpdateAppWidget)在 Lawnchair 等宿主上不应用导致图标/封面不显示。
    private static volatile android.graphics.Bitmap sSourceIconBmp = null;
    private static volatile android.graphics.Bitmap sCoverBmp = null;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        Log.i(TAG, "[DBG] onUpdate called, ids=" + java.util.Arrays.toString(appWidgetIds));
        updateAll(context);
        requestServiceRefresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, android.os.Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        // 卡片尺寸变化（resize / 宿主重排）后按新高度重新计算歌词行数
        updateAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_WIDGET_UPDATE.equals(action)) {
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
        } else if (android.content.Intent.ACTION_WALLPAPER_CHANGED.equals(action)
                || "com.ecarx.action.wallpaper.style".equals(action)) {
            sWallpaperDirty = true;
            updateAll(context);
        }
    }

    public static void requestServiceRefresh(Context context) {
        try {
            // 服务通常常驻；只在确实没起来时才拉。
            // 微件上下文用 startForegroundService 在 Android 9 会报"Calling a method without a qualified user"，
            // 且每次 onUpdate 都重复 start 是多余的。服务内部自己会转前台通知。
            if (CarMediaService.sInstance == null) {
                context.startService(new Intent(context, CarMediaService.class));
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestServiceRefresh startService failed: " + t.getMessage());
        }
        updateAll(context);
    }

    public static int[] getWidgetIds(Context context) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(context);
            return m.getAppWidgetIds(new ComponentName(context, CarMediaWidget.class));
        } catch (Throwable t) {
            return new int[0];
        }
    }

    public static void updateAll(final Context context) {
        try {
            final int[] ids = getWidgetIds(context);
            if (ids == null || ids.length == 0) return;
            // 壁纸加载提到最前面，不依赖 svc 状态，首次 updateAll 立刻加载（修复开机等 5 秒才出壁纸）
            if (sWallpaperDirty || sWallpaperBmp == null) {
                loadWallpaperAsync(context, ids);
            }
            // ---- 歌词行数自适应：保证高亮行（当前句）始终居中可见，不依赖卡片多大 ----
            android.content.SharedPreferences lsp0 = context.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE);
            int lmode0 = lsp0.getInt("lyric_size_mode", 1);
            float[] sizes0 = {32f, 48f, 64f, 78f};
            float sz0 = sizes0[lmode0];
            // half = 当前行前后各显示几行；默认 10（21 行全显）。按 widget 实际高度估算能放几行：
            // 歌词区 ≈ 卡片高 - 顶栏区(104dp) - 底部间距(20dp)；行高 ≈ 字号×1.05 + 行距余量(2dp)
            int half = 10;
            try {
                int minH = 0;
                AppWidgetManager awm0 = AppWidgetManager.getInstance(context);
                for (int id : ids) {
                    android.os.Bundle opt = awm0.getAppWidgetOptions(id);
                    if (opt == null) continue;
                    int h = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                    // [DBG] 打印宿主上报的实际渲染尺寸（像素，需 /density 得 dp）
                    int optW = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                    int optH = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                    Log.i(TAG, "[DBG] widget id=" + id + " OPTION_MIN_W=" + optW + " OPTION_MIN_H=" + optH + " (px) density=1.0 => dp=" + optW + "x" + optH);
                    if (h > 0 && (minH == 0 || h < minH)) minH = h;
                }
                if (minH > 0) {
                    float area = minH - 104f - 20f;
                    float lineH = sz0 * 1.05f + 2f;
                    int fit = (int) (area / lineH);
                    if (fit < 1) fit = 1;
                    half = (fit - 1) / 2;
                    if (half > 10) half = 10;
                }
            } catch (Throwable ignored) { half = 10; }
            CarMediaService svc = CarMediaService.sInstance;
            Log.d(TAG, "[WT] branch svcNull=" + (svc == null) + " ctrl=" + (svc != null ? svc.getControllerPkg() : "null") + " -> " + (svc == null || svc.getControllerPkg() == null ? "DEFAULT" : "NORMAL"));
            if (svc == null || svc.getControllerPkg() == null) {
                // 服务未就绪 / 控制器未选中（覆盖安装、开机早期）：不画默认文案、不缓存默认值。
                // 但仍推送一版"可交互"卡片：绑定全部按钮点击 + 占位文本，保证开机即点（进 App、
                // 调歌词大小、切歌都可用），避免停在宿主默认布局导致按钮点了没反应。
                if (sWallpaperDirty || sWallpaperBmp == null) {
                    loadWallpaperAsync(context, ids);
                }
                try {
                    RemoteViews rv0 = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia);
                    bindClicks(context, rv0);
                    applyLyricSizes(rv0, lmode0); // 开机分支也带用户字号，避免全量重置回落默认 24sp
                    applyWallpaperIfAny(rv0);    // 开机分支也带壁纸，避免壁纸单独部分更新在宿主上炸
                    rv0.setTextViewText(R.id.rv_title_text, context.getString(R.string.widget_default_title));
                    rv0.setTextViewText(R.id.rv_artist, context.getString(R.string.widget_default_artist));
                    rv0.setTextViewText(R.id.rv_lyric_cur, context.getString(R.string.widget_default_lyric));
                    rv0.setTextViewText(R.id.rv_source_name, context.getString(R.string.widget_default_artist));
                    AppWidgetManager.getInstance(context).updateAppWidget(ids, rv0);
                } catch (Throwable ignored) {}
                return;
            }
            String title = svc != null ? svc.getMetaTitle() : null;
            String artist = svc != null ? svc.getMetaArtist() : null;
            if (title == null || title.isEmpty()) title = svc != null ? svc.getDisplayTitle() : null;
            if (artist == null || artist.isEmpty()) artist = svc != null ? svc.getDisplayArtist() : null;
            String lyricM10 = null, lyricM9 = null, lyricM8 = null, lyricM7 = null, lyricM6 = null, lyricM5 = null, lyricM4 = null, lyricM3 = null, lyricM2 = null, lyricM1 = null, lyric = null, lyricN1 = null, lyricN2 = null, lyricN3 = null, lyricN4 = null, lyricN5 = null, lyricN6 = null, lyricN7 = null, lyricN8 = null, lyricN9 = null, lyricN10 = null;
            if (svc != null) {
                try {
                    java.util.List<String> lines = svc.getFullLyricLines();
                    int idx = svc.getCurrentLyricIndex();
                    if (lines != null && !lines.isEmpty() && idx >= 0) {
                        lyricM10 = (half >= 10 && idx - 10 >= 0) ? lines.get(idx - 10) : "";
                        lyricM9 = (half >= 9 && idx - 9 >= 0) ? lines.get(idx - 9) : "";
                        lyricM8 = (half >= 8 && idx - 8 >= 0) ? lines.get(idx - 8) : "";
                        lyricM7 = (half >= 7 && idx - 7 >= 0) ? lines.get(idx - 7) : "";
                        lyricM6 = (half >= 6 && idx - 6 >= 0) ? lines.get(idx - 6) : "";
                        lyricM5 = (half >= 5 && idx - 5 >= 0) ? lines.get(idx - 5) : "";
                        lyricM4 = (half >= 4 && idx - 4 >= 0) ? lines.get(idx - 4) : "";
                        lyricM3 = (half >= 3 && idx - 3 >= 0) ? lines.get(idx - 3) : "";
                        lyricM2 = (half >= 2 && idx - 2 >= 0) ? lines.get(idx - 2) : "";
                        lyricM1 = (half >= 1 && idx - 1 >= 0) ? lines.get(idx - 1) : "";
                        lyric = idx < lines.size() ? lines.get(idx) : "";
                        lyricN1 = (half >= 1 && idx + 1 < lines.size()) ? lines.get(idx + 1) : "";
                        lyricN2 = (half >= 2 && idx + 2 < lines.size()) ? lines.get(idx + 2) : "";
                        lyricN3 = (half >= 3 && idx + 3 < lines.size()) ? lines.get(idx + 3) : "";
                        lyricN4 = (half >= 4 && idx + 4 < lines.size()) ? lines.get(idx + 4) : "";
                        lyricN5 = (half >= 5 && idx + 5 < lines.size()) ? lines.get(idx + 5) : "";
                        lyricN6 = (half >= 6 && idx + 6 < lines.size()) ? lines.get(idx + 6) : "";
                        lyricN7 = (half >= 7 && idx + 7 < lines.size()) ? lines.get(idx + 7) : "";
                        lyricN8 = (half >= 8 && idx + 8 < lines.size()) ? lines.get(idx + 8) : "";
                        lyricN9 = (half >= 9 && idx + 9 < lines.size()) ? lines.get(idx + 9) : "";
                        lyricN10 = (half >= 10 && idx + 10 < lines.size()) ? lines.get(idx + 10) : "";
                    }
                } catch (Throwable ignored) {}
                if (lyric == null || lyric.isEmpty()) {
                    String cur = svc.getCurrentLyricLine();
                    lyric = cur != null ? cur : "";
                }
                if (lyricN1 == null || lyricN1.isEmpty()) {
                    String nx = svc.getNextBoundLyricLine();
                    lyricN1 = nx != null ? nx : "";
                }
            }
            String artwork = svc != null ? svc.getCurrentArtworkUri() : null;
            int state = svc != null ? svc.getPlaybackState() : 0;
            String sourcePkg = svc != null ? svc.getControllerPkg() : null;
            String sourceName = sourceNameOf(context, sourcePkg);
            Log.d(TAG, "[WT] title=" + title + " artist=" + artist);
            if (title == null || title.isEmpty()) title = context.getString(R.string.widget_default_title);
            if (artist == null || artist.isEmpty()) artist = context.getString(R.string.widget_default_artist);
            if (lyric == null || lyric.isEmpty()) lyric = context.getString(R.string.widget_default_lyric);
            if (lyricN1 == null || lyricN1.isEmpty()) lyricN1 = "";
            if (lyricN2 == null || lyricN2.isEmpty()) lyricN2 = "";
            if (lyricN3 == null || lyricN3.isEmpty()) lyricN3 = "";
            if (lyricN4 == null || lyricN4.isEmpty()) lyricN4 = "";
            if (lyricN5 == null || lyricN5.isEmpty()) lyricN5 = "";
            if (lyricN6 == null || lyricN6.isEmpty()) lyricN6 = "";
            if (lyricN7 == null || lyricN7.isEmpty()) lyricN7 = "";
            if (lyricN8 == null || lyricN8.isEmpty()) lyricN8 = "";
            if (lyricN9 == null || lyricN9.isEmpty()) lyricN9 = "";
            if (lyricN10 == null || lyricN10.isEmpty()) lyricN10 = "";
            if (lyricM10 == null) lyricM10 = "";
            if (lyricM9 == null) lyricM9 = "";
            if (lyricM8 == null) lyricM8 = "";
            if (lyricM7 == null) lyricM7 = "";
            if (lyricM6 == null) lyricM6 = "";
            if (lyricM5 == null) lyricM5 = "";
            if (lyricM4 == null) lyricM4 = "";
            if (lyricM3 == null) lyricM3 = "";
            if (lyricM2 == null) lyricM2 = "";
            if (lyricM1 == null) lyricM1 = "";
            if (artwork == null) artwork = "";
            boolean uiModeChanged = false;
            try {
                int night = context.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (night != sLastUiMode) {
                    sLastUiMode = night;
                    uiModeChanged = true;
                }
            } catch (Throwable ignored) {}
            if (uiModeChanged) {
                sWallpaperDirty = true;
                // 日夜切换：系统异步替换壁纸文件，轮询重读几次确保切好后能读到新壁纸并推送
                reloadWallpaperForTheme(context, ids);
            }
            if (++sWallpaperTick >= 60) {
                sWallpaperTick = 0;
                sWallpaperDirty = true;
            }
            if (sWallpaperDirty || sWallpaperBmp == null) {
                loadWallpaperAsync(context, ids);
            }
            final RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_carmedia);
            boolean changed = false;
            boolean titleChanged = false;
            boolean artistChanged = false;

            if (uiModeChanged || !title.equals(sLastTitle)) {
                rv.setTextViewText(R.id.rv_title_text, title);
                changed = true;
                titleChanged = true;
            }
            if (uiModeChanged || !artist.equals(sLastArtist)) {
                rv.setTextViewText(R.id.rv_artist, artist);
                changed = true;
                artistChanged = true;
            }
            if (uiModeChanged || !lyricM10.equals(sLastLyricM10)) {
                rv.setTextViewText(R.id.rv_lyric_m10, lyricM10);
                changed = true;
            }
            if (uiModeChanged || !lyricM9.equals(sLastLyricM9)) {
                rv.setTextViewText(R.id.rv_lyric_m9, lyricM9);
                changed = true;
            }
            if (uiModeChanged || !lyricM8.equals(sLastLyricM8)) {
                rv.setTextViewText(R.id.rv_lyric_m8, lyricM8);
                changed = true;
            }
            if (uiModeChanged || !lyricM7.equals(sLastLyricM7)) {
                rv.setTextViewText(R.id.rv_lyric_m7, lyricM7);
                changed = true;
            }
            if (uiModeChanged || !lyricM6.equals(sLastLyricM6)) {
                rv.setTextViewText(R.id.rv_lyric_m6, lyricM6);
                changed = true;
            }
            if (uiModeChanged || !lyricM5.equals(sLastLyricM5)) {
                rv.setTextViewText(R.id.rv_lyric_m5, lyricM5);
                changed = true;
            }
            if (uiModeChanged || !lyricM4.equals(sLastLyricM4)) {
                rv.setTextViewText(R.id.rv_lyric_m4, lyricM4);
                changed = true;
            }
            if (uiModeChanged || !lyricM3.equals(sLastLyricM3)) {
                rv.setTextViewText(R.id.rv_lyric_m3, lyricM3);
                changed = true;
            }
            if (uiModeChanged || !lyricM2.equals(sLastLyricM2)) {
                rv.setTextViewText(R.id.rv_lyric_m2, lyricM2);
                changed = true;
            }
            if (uiModeChanged || !lyricM1.equals(sLastLyricM1)) {
                rv.setTextViewText(R.id.rv_lyric_m1, lyricM1);
                changed = true;
            }
            if (uiModeChanged || !lyric.equals(sLastLyricCur)) {
                rv.setTextViewText(R.id.rv_lyric_cur, lyric);
                changed = true;
            }
            if (uiModeChanged || !lyricN1.equals(sLastLyricN1)) {
                rv.setTextViewText(R.id.rv_lyric_n1, lyricN1);
                changed = true;
            }
            if (uiModeChanged || !lyricN2.equals(sLastLyricN2)) {
                rv.setTextViewText(R.id.rv_lyric_n2, lyricN2);
                changed = true;
            }
            if (uiModeChanged || !lyricN3.equals(sLastLyricN3)) {
                rv.setTextViewText(R.id.rv_lyric_n3, lyricN3);
                changed = true;
            }
            if (uiModeChanged || !lyricN4.equals(sLastLyricN4)) {
                rv.setTextViewText(R.id.rv_lyric_n4, lyricN4);
                changed = true;
            }
            if (uiModeChanged || !lyricN5.equals(sLastLyricN5)) {
                rv.setTextViewText(R.id.rv_lyric_n5, lyricN5);
                changed = true;
            }
            if (uiModeChanged || !lyricN6.equals(sLastLyricN6)) {
                rv.setTextViewText(R.id.rv_lyric_n6, lyricN6);
                changed = true;
            }
            if (uiModeChanged || !lyricN7.equals(sLastLyricN7)) {
                rv.setTextViewText(R.id.rv_lyric_n7, lyricN7);
                changed = true;
            }
            if (uiModeChanged || !lyricN8.equals(sLastLyricN8)) {
                rv.setTextViewText(R.id.rv_lyric_n8, lyricN8);
                changed = true;
            }
            if (uiModeChanged || !lyricN9.equals(sLastLyricN9)) {
                rv.setTextViewText(R.id.rv_lyric_n9, lyricN9);
                changed = true;
            }
            if (uiModeChanged || !lyricN10.equals(sLastLyricN10)) {
                rv.setTextViewText(R.id.rv_lyric_n10, lyricN10);
                changed = true;
            }
            android.content.SharedPreferences lsp = context.getSharedPreferences("widget_cfg", Context.MODE_PRIVATE);
            int lmode = lsp.getInt("lyric_size_mode", 1);
            // ★ (Bug: 跑马灯滚一点就跳回开头):原实现每次 updateAll 都无条件
            // setTextViewTextSize(21行)+ changed=true → lyricPollRunnable 每秒轮询都触发
            // partiallyUpdateAppWidget → 歌词 TextView 每次重建 → 长歌词跑马灯滚动状态被重置。
            // 修复:字号只在 lmode 真正变化时才设置并 changed=true(轮询时歌名/歌词/字号都不变
            // → changed=false → 不重写 RemoteViews → 跑马灯能持续滚动)。
            // ★ 字号块加 uiModeChanged——切日夜走全量 updateAppWidget 会把布局重置为
            // XML 默认字号(24sp)与标签"32"；仅 lmode 防抖会跳过重设，导致用户设的档位失效。加
            // uiModeChanged 后全量更新强制重设字号+标签。跑马灯防抖逻辑不受影响(轮询时 uiModeChanged=false)。
            boolean timeFull = (android.os.SystemClock.elapsedRealtime() - sLastFullUpdateMs) >= FULL_REFRESH_INTERVAL_MS;
            // 字号只在 档位变化 / 日夜切换 / 到达周期全量 时重设（普通轮询不重设，避免歌词 TextView 重建打断跑马灯）
            if (uiModeChanged || timeFull || lmode != sLastLyricSizeMode) {
                applyLyricSizes(rv, lmode);
                sLastLyricSizeMode = lmode;
                changed = true;
            }
            String[] sizeLabels = {"32", "48", "64", "78"};
            rv.setTextViewText(R.id.rv_lyric_size_label, sizeLabels[lmode]);
            // 歌词行可见性：只显示 |offset|<=half 且非空的行（放不下就隐藏，保证高亮行始终居中可见、不被挤出）
            rv.setViewVisibility(R.id.rv_lyric_m10, (half >= 10 && !lyricM10.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m9, (half >= 9 && !lyricM9.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m8, (half >= 8 && !lyricM8.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m7, (half >= 7 && !lyricM7.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m6, (half >= 6 && !lyricM6.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m5, (half >= 5 && !lyricM5.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m4, (half >= 4 && !lyricM4.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m3, (half >= 3 && !lyricM3.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m2, (half >= 2 && !lyricM2.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_m1, (half >= 1 && !lyricM1.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_cur, (!lyric.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n1, (half >= 1 && !lyricN1.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n2, (half >= 2 && !lyricN2.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n3, (half >= 3 && !lyricN3.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n4, (half >= 4 && !lyricN4.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n5, (half >= 5 && !lyricN5.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n6, (half >= 6 && !lyricN6.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n7, (half >= 7 && !lyricN7.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n8, (half >= 8 && !lyricN8.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n9, (half >= 9 && !lyricN9.isEmpty()) ? View.VISIBLE : View.GONE);
            rv.setViewVisibility(R.id.rv_lyric_n10, (half >= 10 && !lyricN10.isEmpty()) ? View.VISIBLE : View.GONE);

            Log.i(TAG, "[DBG] srcPkg=" + sourcePkg + " srcName=" + sourceName
                    + " uiModeChanged=" + uiModeChanged + " sLastSource=" + sLastSource
                    + " svcNull=" + (svc == null));
            boolean sourceBound = false;
            if (uiModeChanged || !sourceName.equals(sLastSource)
                    || (sourcePkg != null && !sourcePkg.equals(sLastSourcePkg))) {
                Log.i(TAG, "[DBG] SOURCE_UPDATE hit: set name=" + sourceName
                        + " loadIcon pkg=" + sourcePkg);
                rv.setTextViewText(R.id.rv_source_name, sourceName);
                changed = true;
                sourceBound = bindSourceClick(context, rv, sourcePkg);
            }
            // 音源图标：随 pkg 变化加载（与名称分开判定，保证名称更新即触发图标刷新）
            if ((uiModeChanged || sourcePkg == null || !sourcePkg.equals(sLastSourcePkg))) {
                loadSourceIconAsync(context, sourcePkg, ids);
            }
            final boolean playing = (state == PlaybackState.STATE_PLAYING);
            if (uiModeChanged || playing != sLastPlaying) {
                rv.setImageViewResource(R.id.rv_play_icon,
                        playing ? R.drawable.ic_widget_pause : R.drawable.ic_widget_play);
                changed = true;
            }
            boolean artworkChanged = uiModeChanged || !artwork.equals(sLastArtworkUri);
            boolean forcedFull = sForceFull;   // 壁纸刚加载：即使内容没变也强制做一次全量（把壁纸一起推上去）
            if (sForceFull) {
                changed = true;
                sForceFull = false;
            }
            // ★ 新放置的微件强制全量：其 id 之前没全量过，即使内容与全局 sLast* 缓存相同也要全量，
            // 否则复用缓存跳过 setText，全新 RemoteViews 停在默认文案。
            boolean hasNewId = false;
            for (int id : ids) {
                if (!sFullUpdatedIds.contains(id)) { hasNewId = true; break; }
            }
            if (hasNewId) { changed = true; }
            Log.d(TAG, "[WT] changed=" + changed + " hasNewId=" + hasNewId + " doFull=" + (uiModeChanged || sourceBound || titleChanged || artistChanged || (playing != sLastPlaying) || timeFull || forcedFull || hasNewId));
            if (!changed) return;
            // 音源点击绑定到对应 App 时必须走全量 update：partiallyUpdateAppWidget 不会应用 setOnClickPendingIntent，
            // 且 bindClicks 会把音源点击重置为进主界面，需在全量后重新 bindSourceClick 覆盖回对应 App。
            // 注：壁纸只在「全量」里携带（forcedFull / 切源 / 周期30s 等），常态轮询走部分更新不带大位图。
            boolean doFull = uiModeChanged || sourceBound || titleChanged || artistChanged || (playing != sLastPlaying) || timeFull || forcedFull || hasNewId;
            if (doFull) {
                // ★ 自愈修复：任何全量更新都无条件重设 字号+标签+点击绑定，根治两类偶发：
                // ① 切音源(sourceBound)触发全量时布局回落到 XML 默认字号(24sp)、字号块被防抖跳过 → 歌词/标签变小；
                // ② 周期全量(30s)保证宿主(launcher / cardesk 等 AppWidgetHostView)手里始终是完整可交互的 RemoteViews，
                // 宿主重建/重挂载 widget 视图时不会"点不动 / 字体回默认32"。
                // ★ 全量必须无条件写真实文字：新放置/重新挂载的微件复用全局 sLast* 缓存，上方按内容去重的
                // setText 会跳过 → rv 里 title/artist/source 仍是初始默认。这里补写保证全量卡片始终带真实内容。
                rv.setTextViewText(R.id.rv_title_text, title);
                rv.setTextViewText(R.id.rv_artist, artist);
                rv.setTextViewText(R.id.rv_source_name, sourceName);
                // ★ 全量同时携带异步加载好的音源图标/封面位图（缓存），宿主(Lawnchair)才能渲染出来
                if (sSourceIconBmp != null) rv.setImageViewBitmap(R.id.rv_source_icon, sSourceIconBmp);
                if (sCoverBmp != null) {
                    rv.setImageViewBitmap(R.id.rv_cover, sCoverBmp);
                    rv.setViewVisibility(R.id.rv_cover, View.VISIBLE);
                    rv.setViewVisibility(R.id.rv_cover_ph_icon, View.INVISIBLE);
                }
                applyLyricSizes(rv, lmode);
                bindClicks(context, rv);
                bindSourceClick(context, rv, sourcePkg);
                applyWallpaperIfAny(rv);   // 壁纸并入全量（随周期/切源/刚加载时的全量一起推），避免单独部分更新+整卡大位图
                AppWidgetManager.getInstance(context).updateAppWidget(ids, rv);
                sLastFullUpdateMs = android.os.SystemClock.elapsedRealtime();
                for (int id : ids) { sFullUpdatedIds.add(id); }   // 记录已全量 id：新放置的微件下次不再触发 newId 全量
            } else {
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, rv);
            }
            sLastTitle = title;
            sLastArtist = artist;
            sLastLyricM10 = lyricM10;
            sLastLyricM9 = lyricM9;
            sLastLyricM8 = lyricM8;
            sLastLyricM7 = lyricM7;
            sLastLyricM6 = lyricM6;
            sLastLyricM5 = lyricM5;
            sLastLyricM4 = lyricM4;
            sLastLyricM3 = lyricM3;
            sLastLyricM2 = lyricM2;
            sLastLyricM1 = lyricM1;
            sLastLyricCur = lyric;
            sLastLyricN1 = lyricN1;
            sLastLyricN2 = lyricN2;
            sLastLyricN3 = lyricN3;
            sLastLyricN4 = lyricN4;
            sLastLyricN5 = lyricN5;
            sLastLyricN6 = lyricN6;
            sLastLyricN7 = lyricN7;
            sLastLyricN8 = lyricN8;
            sLastLyricN9 = lyricN9;
            sLastLyricN10 = lyricN10;

            sLastPlaying = playing;
            sLastPlayState = state;
            sLastArtworkUri = artwork;
            sLastSource = sourceName;
            sLastSourcePkg = sourcePkg;
            if (artworkChanged && !artwork.isEmpty()) {
                final String artworkFinal = artwork;
                loadCoverAsync(context, artworkFinal, new CoverCallback() {
                    @Override
                    public void onCover(Bitmap bmp) {
                        try {
                            if (bmp == null) return;
                            String now = (CarMediaService.sInstance != null
                                    ? CarMediaService.sInstance.getCurrentArtworkUri() : null);
                            if (now == null || !now.equals(artworkFinal)) return;
                            // ★ 部分更新宿主不应用 → 缓存位图 + 强制全量携带，宿主才能渲染出封面
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    sCoverBmp = bmp;
                                    sForceFull = true;
                                    updateAll(context);
                                }
                            });
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
        PendingIntent openPi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.rv_lyrics, openPi);
        // 音源图标/名称：点击进主界面（服务就绪后会由 bindSourceClick 覆盖为进对应音源 App）
        rv.setOnClickPendingIntent(R.id.rv_source_icon, openPi);
        rv.setOnClickPendingIntent(R.id.rv_source_name, openPi);
        bindAction(context, rv, R.id.rv_btn_prev, CardActionReceiver.ACTION_PREV);
        bindAction(context, rv, R.id.rv_btn_play, CardActionReceiver.ACTION_PLAY_PAUSE);
        bindAction(context, rv, R.id.rv_btn_next, CardActionReceiver.ACTION_NEXT);
        Intent dec = new Intent(context, CarMediaWidget.class);
        dec.setAction("com.ecarx.carmedia.WIDGET_LYRIC_DEC");
        PendingIntent decPi = PendingIntent.getBroadcast(context, 97, dec,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.rv_btn_lyric_dec, decPi);
        Intent inc = new Intent(context, CarMediaWidget.class);
        inc.setAction("com.ecarx.carmedia.WIDGET_LYRIC_INC");
        PendingIntent incPi = PendingIntent.getBroadcast(context, 98, inc,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.rv_btn_lyric_inc, incPi);
    }

 /** 把 21 行歌词字号 + 字号标签设为当前档位。全量更新时强制调用，避免布局回落 XML 默认字号(24sp)/标签"32"。 */
    private static void applyLyricSizes(RemoteViews rv, int lmode) {
        float[] sizes = {32f, 48f, 64f, 78f};
        float sz = sizes[lmode];
        rv.setTextViewTextSize(R.id.rv_lyric_m10, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m9, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m8, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m7, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m6, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m5, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m4, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m3, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m2, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_m1, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_cur, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n1, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n2, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n3, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n4, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n5, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n6, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n7, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n8, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n9, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewTextSize(R.id.rv_lyric_n10, TypedValue.COMPLEX_UNIT_SP, sz);
        rv.setTextViewText(R.id.rv_lyric_size_label, new String[]{"32", "48", "64", "78"}[lmode]);
    }

 /** 把音源图标/名称的点击绑定为打开对应音源 App；成功绑定返回 true（调用方据此走全量更新，因为 partiallyUpdate 不应用 onClickPendingIntent）。 */
    public static boolean bindSourceClick(Context context, RemoteViews rv, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            PendingIntent pi = PendingIntent.getActivity(context, 100, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.rv_source_icon, pi);
            rv.setOnClickPendingIntent(R.id.rv_source_name, pi);
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
        Log.i(TAG, "[DBG] loadSourceIconAsync called pkg=" + pkg);
        if (pkg == null || pkg.isEmpty()) return;
        // ★ (USB 图标卡旧源):USB 是虚拟包(com.ecarx.carmedia.usb),系统无
        // 该应用图标,getApplicationIcon 必然抛 NameNotFoundException → 原实现 catch 后
        // 静默返回 → 小组件图标永远停在上一个音源(用户实测"USB 播放图标还是旧源")。
        // 修复:USB 虚拟包直接用内置音符图标(ic_widget_music)兜底并推送。
        if (UsbSourceController.SOURCE_PKG_USB.equals(pkg)) {
            try {
                int iconRes = R.drawable.ic_widget_music;
                final android.graphics.Bitmap noteBmp = decodeResourceToBitmap(context, iconRes);
                if (noteBmp != null) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                // ★ 部分更新宿主不应用 → 缓存位图 + 强制全量携带
                                sSourceIconBmp = noteBmp;
                                sForceFull = true;
                                updateAll(context);
                            } catch (Throwable t) {
                                Log.w(TAG, "USB source icon (note) update error: " + t.getMessage());
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
                    // 位图按 44dp 生成（与布局 rv_source_icon 44dp 1:1），避免位图放大显示发糊
                    int size = (int) (44 * context.getResources().getDisplayMetrics().density);
                    final Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                    d.setBounds(0, 0, size, size);
                    d.draw(c);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                // ★ 部分更新宿主不应用 → 缓存位图 + 强制全量携带
                                sSourceIconBmp = bmp;
                                sForceFull = true;
                                updateAll(context);
                            } catch (Throwable t) {
                                Log.w(TAG, "source icon update error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable t) {}
            }
        }, "widget-src-icon").start();
    }

 /** ★ 把 drawable 资源解码为 44dp 位图(USB 虚拟包音符兜底用)。 */
    private static Bitmap decodeResourceToBitmap(Context context, int resId) {
        try {
            int size = (int) (44 * context.getResources().getDisplayMetrics().density);
            android.graphics.drawable.Drawable d = context.getResources().getDrawable(resId);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "decodeResourceToBitmap error: " + t.getMessage());
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
        }, "widget-cover").start();
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
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
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
        int scale = 1;
        while (w / scale > 256 || h / scale > 256) {
            scale *= 2;
        }
        return scale;
    }

 /**
 * 读取宿主实际渲染的小部件尺寸（px）。真车日志实测竖版 2×4 卡 = 306×546（density=1.0，px=dp）。
 * 兜底默认 306×546。位图必须按此宽高比烤，rv_wallpaper 用 fitXY 才不会拉变形/拉扁圆角。
 */
    private static int[] getCardSizePx(Context context, int[] ids) {
        // v1.6.9：位图比例 = 实际卡片比例(等比 fitXY)圆角才正、不变形。
        // 之前硬编码 570×316(1.8:1)，若与实际卡片比例不符 → fitXY 非等比 → 圆角被拉非常大(用户实测)。
        // 优先读宿主 OPTION_APPWIDGET_SIZES(真实渲染尺寸列表)；否则兜底 540×360(1.5:1，对齐用户实测"高占满、宽占3/2"，20万像素内)。
        try {
            if (ids != null) {
                for (int id : ids) {
                    android.os.Bundle opt = AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
                    if (opt == null) continue;
                    try { for (String k : opt.keySet()) { Log.i(TAG, "[DBG] OPTION " + k + " = " + opt.get(k)); } } catch (Throwable ignored2) {}

                    int mnw = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                    int mnh = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                    int mxw = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
                    int mxh = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
                    Object sz = opt.get(AppWidgetManager.OPTION_APPWIDGET_SIZES);
                    if (sz instanceof java.util.ArrayList) {
                        java.util.ArrayList<?> list = (java.util.ArrayList<?>) sz;
                        if (!list.isEmpty()) {
                            Object last = list.get(list.size() - 1);
                            if (last instanceof android.util.SizeF) {
                                int sw = (int) ((android.util.SizeF) last).getWidth();
                                int sh = (int) ((android.util.SizeF) last).getHeight();
                                if (sw > 0 && sh > 0 && sw >= sh) {
                                    Log.i(TAG, "[DBG] getCardSizePx id=" + id + " SIZES=(" + list + ") 用=" + sw + "x" + sh);
                                    return new int[]{sw, sh};
                                }
                            }
                        }
                    }
                    Log.i(TAG, "[DBG] getCardSizePx id=" + id + " OPTION min=" + mnw + "x" + mnh + " max=" + mxw + "x" + mxh + " sizes=" + sz);
                }
            }
        } catch (Throwable ignored) {}
        Log.i(TAG, "[DBG] getCardSizePx -> 兜底 306x546(竖版对齐宿主OPTION)");
        return new int[]{306, 546};
    }

 /**
 * 取全分辨率壁纸源。优先反射隐藏 API WallpaperManager.getWallpaper()（@SystemApi，
 * 需 READ_WALLPAPER_INTERNAL，已在 Manifest 声明），拿系统未缩放原图（真车 1920x720）。
 * 公开 getDrawable() 只返回 480x180（4x 缩水），烤卡必糊。反射失败再回退 getDrawable()。
 */
    private static android.graphics.Bitmap decodeWallpaperFullRes(Context context) {
        android.graphics.Bitmap bmp = null;
        // 路径 0：直接读系统壁纸文件（原始 1920x720，完全绕开 WallpaperManager 缩放）
        String[] candidates = {
            "/data/system/users/0/wallpaper",
            "/data/system/users/0/wallpaper_orig",
            "/data/system/wallpaper"
        };
        for (String wp : candidates) {
            try {
                java.io.File f = new java.io.File(wp);
                if (f.exists() && f.length() > 1024) {
                    bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        Log.i(TAG, "[DBG] 读文件成功 " + wp + " size=" + bmp.getWidth() + "x" + bmp.getHeight());
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "读文件失败 " + wp + ": " + t.getMessage());
            }
        }
        // 路径 A：反射 getWallpaper() 拿全分辨率
        if (bmp == null) try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(context);
            java.lang.reflect.Method m = android.app.WallpaperManager.class.getMethod("getWallpaper");
            android.graphics.drawable.Drawable d = (android.graphics.drawable.Drawable) m.invoke(wm);
            if (d != null) {
                bmp = drawableToBitmap(d);
                Log.i(TAG, "[DBG] getWallpaper()反射成功 size=" + bmp.getWidth() + "x" + bmp.getHeight());
            }
        } catch (Throwable t) {
            Log.w(TAG, "getWallpaper()反射失败,回退getDrawable(): " + t.getMessage());
        }
        // 路径 B：兜底公开 getDrawable()
        if (bmp == null) {
            try {
                android.graphics.drawable.Drawable d = android.app.WallpaperManager.getInstance(context).getDrawable();
                if (d != null) {
                    bmp = drawableToBitmap(d);
                    Log.i(TAG, "[DBG] getDrawable()兜底 size=" + bmp.getWidth() + "x" + bmp.getHeight());
                }
            } catch (Throwable t) {
                Log.w(TAG, "getDrawable()也失败: " + t.getMessage());
            }
        }
        return bmp;
    }

 /** Drawable -> Bitmap：BitmapDrawable 直接取，否则按 intrinsic 尺寸画到新 Bitmap。 */
    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d) {
        if (d == null) return null;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null) return b;
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) { w = 1; h = 1; }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return out;
    }

    private static android.graphics.Bitmap cleanSource(android.graphics.Bitmap bmp) {
        if (bmp == null) return null;
        try {
            Log.i(TAG, "[DBG] wallpaper src config=" + bmp.getConfig() + " size=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " alloc=" + (bmp.isRecycled() ? "recycled" : "live"));
            if (!bmp.isRecycled() && bmp.getConfig() != android.graphics.Bitmap.Config.ARGB_8888) {
                android.graphics.Bitmap copy = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                if (copy != null) { try { bmp.recycle(); } catch (Throwable ignored) {} return copy; }
            }
            android.graphics.Bitmap clean = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            if (clean != null && clean != bmp) { try { bmp.recycle(); } catch (Throwable ignored) {} return clean; }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper cleanSource fail: " + t);
            return bmp;
        }
    }

 /** 超大源（>2048）轻度降采样防内存过大；真车 1920×720 不会触发。 */
    private static android.graphics.Bitmap capSource(android.graphics.Bitmap bmp) {
        try {
            int s = 1;
            while (bmp.getWidth() / s > 2048 || bmp.getHeight() / s > 2048) s *= 2;
            if (s > 1) return android.graphics.Bitmap.createScaledBitmap(
                    bmp, bmp.getWidth() / s, bmp.getHeight() / s, true);
        } catch (Throwable ignored) {}
        return bmp;
    }

    private static void loadWallpaperAsync(final Context context, final int[] ids) {
        Log.i(TAG, "[DBG] loadWallpaperAsync enter, loading=" + sWallpaperLoading);
        synchronized (CarMediaWidget.class) {
            if (sWallpaperLoading) return; // 已在加载中，防止并发线程叠加
            sWallpaperLoading = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 修复：取全分辨率壁纸源（反射 getWallpaper 返回 1920×720）。
                    // 旧实现 getDrawable() 只拿到 480×180（4× 缩水）烤进卡片必糊；且旧逻辑把源压到 ≤640 是按
                    // 横版 570×316 卡设计的，本车卡片实为竖版 306×546 —— fitXY 横版位图进竖版槽会拉扁圆角、放大发糊。
                    // 现在按卡片真实宽高（getCardSizePx）cover 裁剪，圆角正圆、分辨率 1:1。不用磁盘缓存
                    // （缓存会污染旧版本位图导致马赛克+圆角错）。
                    android.graphics.Bitmap bmp = decodeWallpaperFullRes(context);
                    if (bmp == null) {
                        Log.w(TAG, "loadWallpaper: system wallpaper not ready, retry");
                        scheduleWallpaperRetry(context, ids);
                        return;
                    }
                    final android.graphics.Bitmap rawBmp = bmp;
                    sWallpaperFailCount = 0;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.graphics.Bitmap pushed = pushWallpaperRaw(context, ids, rawBmp);
                                if (pushed != null) {
                                    sWallpaperBmp = pushed;
                                    sWallpaperDirty = false;
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "wallpaper update error: " + t.getMessage());
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "loadWallpaper error: " + t.getMessage());
                    scheduleWallpaperRetry(context, ids);
                } finally {
                    synchronized (CarMediaWidget.class) {
                        sWallpaperLoading = false;
                    }
                }
            }
        }, "widget-wallpaper").start();
    }

 /** 壁纸加载失败自愈重试：2s→4s→8s→16s→30s 封顶，持续到成功（独立于 updateAll 的 60-tick 窗口）。 */
    private static void scheduleWallpaperRetry(final Context context, final int[] ids) {
        try {
            final int attempt = sWallpaperFailCount++;
            long delay = 2000L * (1L << Math.min(attempt, 4));
            if (delay > 30000L) delay = 30000L;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        loadWallpaperAsync(context, ids);
                    } catch (Throwable t) {
                        Log.w(TAG, "wallpaper retry error: " + t.getMessage());
                    }
                }
            }, delay);
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper retry schedule error: " + t.getMessage());
        }
    }

 /** 日夜切换时系统异步替换壁纸文件，按固定间隔重读几次，确保切好后能读到新壁纸并推送。 */
    private static void reloadWallpaperForTheme(final Context context, final int[] ids) {
        try {
            final long[] delays = {500L, 1200L, 2500L, 5000L};
            for (long d : delays) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        loadWallpaperAsync(context, ids);
                    }
                }, d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "reloadWallpaperForTheme error: " + t.getMessage());
        }
    }

 /** 壁纸位图并入全量更新（sWallpaperBmp 就绪时给 rv 设置 rv_wallpaper）。 */
    private static void applyWallpaperIfAny(RemoteViews rv) {
        android.graphics.Bitmap b = sWallpaperBmp;
        if (b == null || b.isRecycled()) return;
        try {
            rv.setImageViewBitmap(R.id.rv_wallpaper, b);
        } catch (Throwable t) {
            Log.w(TAG, "applyWallpaperIfAny fail: " + t.getMessage());
        }
    }

 /** 缓存恢复：置位壁纸并触发一次含壁纸的全量刷新。 */
    private static void pushWallpaper(Context context, int[] ids, android.graphics.Bitmap bmp) {
        try {
            sWallpaperBmp = bmp;
            sWallpaperDirty = false;
            sForceFull = true;
            updateAll(context);
        } catch (Throwable t) {
            Log.w(TAG, "pushWallpaper cached bmp failed: " + t.getMessage());
        }
    }

 /**
 * 新壁纸推送：按宿主实际卡片尺寸烤圆角位图（竖版 306×546 / 横版 570×316 自适应），fitXY 1:1 铺满。
 * 输出 ≤20 万像素（AppWidget Binder 位图上限），超限会撞宿主 → 糊/马赛克。
 * 【修复】不再用 partiallyUpdateAppWidget 单独推整卡大位图（cardesk 宿主对"部分更新+大位图"
 * inflate 失败 → 报"无法添加微件"；原车 launcher3 容忍、cardesk 不严格），改为置位缓存后走一次全量一起推。
 * 返回成功置位的位图（用于落盘缓存），失败返回 null。
 */
    private static android.graphics.Bitmap pushWallpaperRaw(Context context, int[] ids, android.graphics.Bitmap rawBmp) {
        try {
            Log.i(TAG, "[DBG] pushWallpaperRaw enter, raw=" + rawBmp.getWidth() + "x" + rawBmp.getHeight());
            int[] sz = getCardSizePx(context, ids);
            Log.i(TAG, "[DBG] card size=" + sz[0] + "x" + sz[1]);
            android.graphics.Bitmap bmp = WallpaperMask.round(context, rawBmp, sz[0], sz[1]);
            Log.i(TAG, "[DBG] round returned: " + (bmp != null ? bmp.getWidth() + "x" + bmp.getHeight() : "null"));
            if (bmp == null) return null;
            sWallpaperBmp = bmp;
            sWallpaperDirty = false;
            sForceFull = true;
            updateAll(context);
            Log.i(TAG, "[DBG] wallpaper folded into full update");
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "pushWallpaper 200k failed: " + t.getMessage());
        }
        return null;
    }
}