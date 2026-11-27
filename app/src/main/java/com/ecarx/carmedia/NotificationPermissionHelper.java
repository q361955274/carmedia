package com.ecarx.carmedia;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 通知监听权限自动授予助手。
 *
 * 授权策略（与原实现 1:1 对齐）：
 * 1. 反射 NotificationManager.setNotificationListenerAccessGranted(ComponentName, boolean)
 * —— 系统隐藏 API，sharedUserId=android.uid.system + 平台签名时可用；
 * 2. fallback：Runtime.exec("cmd notification allow_listener <flatten>")；
 * 3. fallback：直写 Settings.Secure.enabled_notification_listeners（WRITE_SECURE_SETTINGS + system uid）。
 */
public final class NotificationPermissionHelper {

    private static final String TAG = "CarMedia_NL";

    private NotificationPermissionHelper() {
    }

 /** 尝试授予通知监听权限，返回是否成功。 */
    public static boolean grantNotificationListenerPermission(Context context) {
        String flatten = new ComponentName(context, MediaNotificationListener.class)
                .flattenToString();
        Log.i(TAG, "Component: " + flatten);

        // ---- 第一层：隐藏 API 反射 ----
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.e(TAG, "❌ NotificationManager is null!");
                return fallbackGrant(context, flatten);
            }
            try {
                java.lang.reflect.Method m = NotificationManager.class.getMethod(
                        "setNotificationListenerAccessGranted", ComponentName.class, boolean.class);
                m.invoke(nm, new ComponentName(context, MediaNotificationListener.class),
                        Boolean.TRUE);
                Log.i(TAG, "✅ NotificationListener enabled via setNotificationListenerAccessGranted");
                if (isNotificationListenerEnabled(context)) {
                    return true;
                }
                Log.w(TAG, "reflection reported success but settings not updated, trying fallback");
                return fallbackGrant(context, flatten);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "Method not found! setNotificationListenerAccessGranted does not exist");
                return fallbackGrant(context, flatten);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException! App is NOT signed with platform key / Cannot call hidden system API");
                return fallbackGrant(context, flatten);
            } catch (Exception e) {
                Log.e(TAG, "Failed to grant permission: " + e.getClass().getSimpleName());
                return fallbackGrant(context, flatten);
            }
        } catch (Exception e) {
            Log.e(TAG, "grantNotificationListenerPermission outer error: " + e.getMessage());
            return fallbackGrant(context, flatten);
        }
    }

 /** 第一层失败后的 fallback 链。 */
    private static boolean fallbackGrant(Context context, String flatten) {
        // ---- 第二层：cmd notification allow_listener ----
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"cmd", "notification", "allow_listener", flatten});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, "cmd out: " + line);
            }
            int exit = p.waitFor();
            Log.i(TAG, "cmd allow_listener exit=" + exit);
            if (exit == 0 && isNotificationListenerEnabled(context)) {
                Log.i(TAG, "✅ NotificationListener enabled via cmd allow_listener");
                return true;
            }
            // cmd 层失败或未生效，继续尝试 Settings.Secure 直写
            Log.w(TAG, "cmd allow_listener exit=" + exit
                    + " or settings not updated, trying Settings.Secure");
        } catch (Exception e) {
            Log.w(TAG, "cmd allow_listener failed, trying Settings.Secure: " + e.getMessage());
        }

        // ---- 第三层：直写 Settings.Secure.enabled_notification_listeners ----
        try {
            String key = "enabled_notification_listeners";
            String v2 = Settings.Secure.getString(context.getContentResolver(), key);
            String merged;
            if (v2 == null || v2.isEmpty()) {
                merged = flatten;
            } else if (v2.contains(flatten)) {
                merged = v2;
            } else {
                merged = v2 + ":" + flatten;
            }
            boolean ok = Settings.Secure.putString(context.getContentResolver(), key, merged);
            if (ok) {
                Log.i(TAG, "✅ built via Settings.Secure fallback");
                return true;
            }
            Log.e(TAG, "❌ Settings.Secure fallback failed");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Settings.Secure fallback exception: " + e.getMessage());
            return false;
        }
    }

 /** 检查通知监听权限是否已授予。 */
    public static boolean isNotificationListenerEnabled(Context context) {
        String flatten = new ComponentName(context, MediaNotificationListener.class)
                .flattenToString();
        String v = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (v != null && v.contains(flatten)) {
            Log.i(TAG, "✓ NotificationListener permission is ENABLED");
            return true;
        }
        Log.w(TAG, "⚠ NotificationListener permission is NOT enabled");
        return false;
    }

 /** 撤销通知监听权限。 */
    public static boolean revokeNotificationListenerPermission(Context context) {
        Log.i(TAG, "Revoking NotificationListener permission...");
        String flatten = new ComponentName(context, MediaNotificationListener.class)
                .flattenToString();
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.e(TAG, "❌ NotificationManager is null!");
                return false;
            }
            try {
                java.lang.reflect.Method m = NotificationManager.class.getMethod(
                        "setNotificationListenerAccessGranted", ComponentName.class, boolean.class);
                m.invoke(nm, new ComponentName(context, MediaNotificationListener.class),
                        Boolean.FALSE);
                Log.i(TAG, "✅ Revoked via setNotificationListenerAccessGranted");
                return true;
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "Method not found!");
                return fallbackRevoke(context, flatten);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException!");
                return fallbackRevoke(context, flatten);
            } catch (Exception e) {
                Log.e(TAG, "Revoke failed: " + e.getMessage());
                return fallbackRevoke(context, flatten);
            }
        } catch (Exception e) {
            Log.e(TAG, "revoke outer error: " + e.getMessage());
            return fallbackRevoke(context, flatten);
        }
    }

 /** 撤销的 fallback：从 enabled_notification_listeners 移除本组件。 */
    private static boolean fallbackRevoke(Context context, String flatten) {
        try {
            String key = "enabled_notification_listeners";
            String v = Settings.Secure.getString(context.getContentResolver(), key);
            if (v == null || v.isEmpty()) {
                return true; // 本来就没有
            }
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String part : v.split(":")) {
                if (part.isEmpty() || part.equals(flatten)) {
                    continue;
                }
                if (!first) {
                    sb.append(':');
                }
                sb.append(part);
                first = false;
            }
            Settings.Secure.putString(context.getContentResolver(), key, sb.toString());
            Log.i(TAG, "✅ Revoked via Settings.Secure removal");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ Revoke fallback exception: " + e.getMessage());
            return false;
        }
    }
}
