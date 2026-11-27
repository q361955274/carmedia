package com.ecarx.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 广播切音源接收器（新增； exported 回退为 false + 显式投递）。
 *
 * 通过广播切换音源：
 * action = com.ecarx.carmedia.SWITCH_SOURCE
 * extra = "pkg" → 目标音源包名，如 com.android.bluetooth / cn.kuwo.kwmusiccar
 * / com.kugou.android.auto（与 CarMediaService 内 PKG_* 常量一致）
 *
 * 投递要求（真车日志确认）：
 * - Manifest 中该 receiver 为 android:exported="false"：只有同 uid / 同签名 /
 * 系统身份（系统桌面 ecarx.launcher3 为 uid 1000）能投递，普通第三方 app 发不进。
 * - 调用方必须做【显式投递】——否则 Android 9 会报
 * "Background execution not allowed: receiving ... to SourceSwitchReceiver" 拦截
 * （manifest 静态 receiver 收不到隐式广播）。二选一：
 * a) setPackage 定向（桌面端当前采用，用户指定）：
 * Intent i = new Intent("com.ecarx.carmedia.SWITCH_SOURCE");
 * i.putExtra("pkg", "com.android.bluetooth");
 *           i.setPackage("com.ecarx.carmedia");   // ★显式定向，绕过后台执行限制
 * sendBroadcast(i);
 * b) 显式组件（若 a 在个别 ROM 不生效时更保险）：
 * i.setComponent(new ComponentName("com.ecarx.carmedia",
 * "com.ecarx.carmedia.SourceSwitchReceiver"));
 * - adb 验证：adb shell am broadcast -a com.ecarx.carmedia.SWITCH_SOURCE \
 * --es pkg com.android.bluetooth -n com.ecarx.carmedia/.SourceSwitchReceiver
 *
 * 服务未运行时先拉起服务，稍后延迟重试一次（与 CardActionReceiver 同款兜底）。
 */
public class SourceSwitchReceiver extends BroadcastReceiver {

    public static final String ACTION_SWITCH_SOURCE = "com.ecarx.carmedia.SWITCH_SOURCE";
    public static final String EXTRA_PKG = "pkg";

    private static final String TAG = "SourceSwitchReceiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || !ACTION_SWITCH_SOURCE.equals(intent.getAction())) {
            return;
        }
        final String pkg = intent.getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.isEmpty()) {
            Log.w(TAG, "switchSource: missing pkg extra");
            return;
        }
        CarMediaService svc = CarMediaService.sInstance;
        if (svc != null) {
            dispatch(svc, pkg);
            return;
        }
        // 服务未运行：拉起服务（前台服务常驻，onCreate 即赋值 sInstance），稍后重试
        try {
            context.startService(new Intent(context, CarMediaService.class));
        } catch (Throwable t) {
            Log.w(TAG, "switchSource: startService err " + t.getMessage());
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                CarMediaService s2 = CarMediaService.sInstance;
                if (s2 != null) {
                    dispatch(s2, pkg);
                } else {
                    Log.w(TAG, "switchSource: service not ready, pkg=" + pkg + " dropped");
                }
            }
        }, 300L);
    }

    private void dispatch(CarMediaService svc, String pkg) {
        try {
            boolean ok = svc.switchToSource(pkg);
            Log.i(TAG, "switchSource: pkg=" + pkg + " handled=" + ok);
        } catch (Throwable t) {
            Log.w(TAG, "switchToSource(" + pkg + ") error: " + t.getMessage());
        }
    }
}
