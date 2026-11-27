package com.ecarx.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 桌面小部件控制按钮接收器。
 * 小卡片点击 上一曲/播放暂停/下一曲 → 本接收器 → CarMediaService.sendMediaAction()。
 * 服务未运行时先拉起，稍后重试一次（服务常驻，sInstance 正常可用）。
 */
public class CardActionReceiver extends BroadcastReceiver {

    public static final String ACTION_PREV = "com.ecarx.carmedia.CARD_PREV";
    public static final String ACTION_PLAY_PAUSE = "com.ecarx.carmedia.CARD_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.ecarx.carmedia.CARD_NEXT";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        final String mediaAction;
        if (ACTION_PREV.equals(action)) {
            mediaAction = "prev";
        } else if (ACTION_NEXT.equals(action)) {
            mediaAction = "next";
        } else if (ACTION_PLAY_PAUSE.equals(action)) {
            mediaAction = "playpause";
        } else {
            return;
        }

        CarMediaService svc = CarMediaService.sInstance;
        if (svc != null) {
            dispatch(svc, mediaAction);
            return;
        }
        // 服务未运行：拉起服务（前台服务常驻，onCreate 即赋值 sInstance），稍后重试
        try {
            context.startService(new Intent(context, CarMediaService.class));
        } catch (Throwable t) {
            Log.w("CardActionReceiver", "startService error: " + t.getMessage());
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                CarMediaService s2 = CarMediaService.sInstance;
                if (s2 != null) {
                    dispatch(s2, mediaAction);
                } else {
                    Log.w("CardActionReceiver", "service not ready, action=" + mediaAction + " dropped");
                }
            }
        }, 300L);
    }

    private void dispatch(CarMediaService svc, String mediaAction) {
        try {
            svc.sendMediaAction(mediaAction);
        } catch (Throwable t) {
            Log.w("CardActionReceiver", "sendMediaAction(" + mediaAction + ") error: " + t.getMessage());
        }
    }
}
