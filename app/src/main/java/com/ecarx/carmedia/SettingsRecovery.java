package com.ecarx.carmedia;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 在线更新后的整车设置（ecarx.settings）恢复器。
 *
 * 背景（详见指引 §35 与 ROM 分析）：
 * - ecarx.settings（整车设置，Kanzi 3D UI，APK 约 423MB）声明 sharedUserId=android.uid.system（uid 1000）；
 * 旧版 carmedia（去 sharedUserId 之前）同样声明 android.uid.system，二者同属 uid 1000 共享组。
 * - 在线更新 = 覆盖安装 carmedia。PMS 替换包时会 killUid(1000) 连带杀死该 uid 组的所有进程
 * （含 ecarx.settings）；而 carmedia 带着"系统应用更新版、/system 原版已删"的残留身份，
 * 每次开机 PMS 都走 "Updated system package + com.ecarx.carmedia no longer exists;
 * revoking system privileges" 流程（清 FLAG_SYSTEM，见 PackageManagerService.smali ~4276）。
 * - 被反复 kill / 重启的 Kanzi 3D 设置界面可能以"半初始化"状态回弹 → 整车设置只剩"关于车机"、
 * 车门 / 屏幕等车控项空白。对 ecarx.settings 做一次 force-stop（干净冷启动）可恢复。
 *
 * 用法：在线更新成功后调用 {@link #recover(Context)}（立即 force-stop + 延迟兜底第二发）。
 *
 * 注意：
 * - 需要签名权限 android.permission.FORCE_STOP_PACKAGES（signature|privileged），
 * 必须在 manifest 声明；CarMedia 为 platform 私签且吉利 PMS 对 /data 下 platform 签名包标 PRIVILEGED，可拿到。
 * - 不能从应用内执行 "adb shell am force-stop ..."（无 adb 连接、无 shell uid）；
 * 等价实现是反射 ActivityManager.forceStopPackage（或 am force-stop 命令），以本应用 uid 调用，权限足够即生效。
 * - ecarx.settings 属 uid 1000 共享组，force-stop 会连带杀死该 uid 组其他系统进程（多为 START_STICKY，
 * 会自动重启），属可接受影响；v1.5.8+ 的 carmedia 已非 uid 1000，不会误杀自身。
 */
public class SettingsRecovery {
    private static final String TAG = "SettingsRecovery";
    public static final String PKG_SETTINGS = "ecarx.settings";
    public static final String ACTION_RECOVER_SETTINGS = "com.ecarx.carmedia.RECOVER_SETTINGS";
 /** 延迟兜底的时间：更新事务后等整车设置回弹出问题，再 force-stop 一次强制干净重启 */
    private static final long DELAY_MS = 5000L;

 /** 立即 force-stop 整车设置（ecarx.settings）。返回是否成功。 */
    public static boolean forceStopSettings(Context context) {
        try {
            Object am = context.getSystemService("activity");
            if (am == null) {
                am = context.getSystemService(Context.ACTIVITY_SERVICE);
            }
            if (am == null) {
                Log.w(TAG, "ActivityManager service not available");
                return fallbackAmExec();
            }
            Method m = am.getClass().getMethod("forceStopPackage", String.class);
            m.setAccessible(true);
            m.invoke(am, PKG_SETTINGS);
            Log.i(TAG, "force-stopped " + PKG_SETTINGS);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "forceStopSettings reflection failed: " + t.getMessage());
            return fallbackAmExec();
        }
    }

 /**
 * 兜底：以 am force-stop 命令形式执行（以本应用 uid 调用，仍需 FORCE_STOP_PACKAGES 权限）。
 * 与用户提出的 "adb shell am force-stop ecarx.settings" 等价，但去掉了 adb shell 前缀。
 */
    private static boolean fallbackAmExec() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"am", "force-stop", PKG_SETTINGS});
            int rc = p.waitFor();
            Log.i(TAG, "am force-stop " + PKG_SETTINGS + " rc=" + rc);
            return rc == 0;
        } catch (Throwable t) {
            Log.w(TAG, "am force-stop failed: " + t.getMessage());
            return false;
        }
    }

 /**
 * 在线更新成功后调用：立即 force-stop + 延迟兜底第二发。
 * 立即一发尽量在更新事务窗口内清掉半初始化状态；延迟一发由 AlarmManager 持有，
 * 即使本应用进程被系统 force-stop 也能触发，覆盖"设置先回弹、后出问题"的场景。
 */
    public static void recover(Context context) {
        Context c = (context != null) ? context.getApplicationContext() : null;
        if (c == null) return;
        forceStopSettings(c);
        scheduleDelayedRecover(c);
    }

    private static void scheduleDelayedRecover(Context context) {
        try {
            Intent i = new Intent(ACTION_RECOVER_SETTINGS);
            i.setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, 0x5E7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + DELAY_MS, pi);
                Log.i(TAG, "scheduled delayed force-stop in " + DELAY_MS + "ms");
            } else {
                Log.w(TAG, "AlarmManager not available");
            }
        } catch (Throwable t) {
            Log.w(TAG, "scheduleDelayedRecover failed: " + t.getMessage());
        }
    }

 /** 延迟恢复 Receiver：到点再对整车设置 force-stop 一次（第二发兜底）。需在 manifest 注册。 */
    public static class DelayedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!ACTION_RECOVER_SETTINGS.equals(intent.getAction())) return;
            forceStopSettings(context);
        }
    }
}
