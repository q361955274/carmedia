package com.ecarx.carmedia;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * 静默安装器（需系统签名 + INSTALL_PACKAGES 权限）。
 * 利用 PackageInstaller Session API，系统签名应用调用时不弹确认框。
 *
 * 使用前提：
 * 1. AndroidManifest 声明 android:sharedUserId="android.uid.system"
 * 2. platform key 签名
 * 3. AndroidManifest 添加 <uses-permission android:name="android.permission.INSTALL_PACKAGES" />
 *
 * 安装结果通过广播接收，action = "com.ecarx.carmedia.UPDATE_RESULT"
 * 对应 Receiver: UpdateResultReceiver
 *
 * 安全设计：
 * - install() 默认不修改任何系统属性，适用于已刷入修改版 services.jar 的车机
 * （ApkAuth.auth(File) 已被改为 return true，不需要绕过验证）
 * - installWithVerifyBypass() 仅在安装前临时设置 sys.jsbd.apk_verify=1，
 * 并在 UpdateResultReceiver 收到安装结果后立即恢复原值，
 * 风险窗口只有几秒，避免全局开关长期开启导致误装其他app搞崩系统设置
 */
public class SilentInstaller {
    private static final String TAG = "SilentInstaller";
    public static final String ACTION_UPDATE_RESULT = "com.ecarx.carmedia.UPDATE_RESULT";
    private static final String PROP_APK_VERIFY = "sys.jsbd.apk_verify";

 /** 安装状态持久化（供 Receiver / MainActivity 跨进程交互，防 force-stop 丢状态） */
    public static final String PREFS = "carmedia_prefs";
    public static final String PREF_UPDATE_ACTIVE = "update_active";   // 是否有进行中的静默安装会话
    public static final String PREF_UPDATE_RESULT = "update_result";   // 最近一次安装结果（成功后清除）
 /** 本次安装包的版本元信息（供安装失败时组装详细原因；成功后由 Receiver 清理） */
    public static final String PREF_UPDATE_VERCODE_NEW = "update_vercode_new";  // 新包 versionCode（解析失败为 -1）
    public static final String PREF_UPDATE_VERNAME_NEW = "update_vername_new";  // 新包 versionName
    public static final String PREF_UPDATE_VERCODE_OLD = "update_vercode_old";  // 已装版本 versionCode
    public static final String PREF_UPDATE_VERNAME_OLD = "update_vername_old";  // 已装版本 versionName
 /** 安装前签名预检结果（1=一致 / 0=不一致 / -1=未知）。由 MainActivity 在调静默安装前写入 */
    public static final String PREF_UPDATE_SIG_MATCH = "update_sig_match";
 /** ApkAuth 旁路（sys.jsbd.apk_verify=1）是否生效（1=生效或不需要旁路 / 0=未生效）。由 installInternal 写入 */
    public static final String PREF_UPDATE_BYPASS_OK = "update_bypass_ok";

 /** 安装前保存的原始属性值，安装完成后恢复（避免全局开关长期开启） */
    private static String sSavedPropValue = null;
 /** 标记本次安装是否临时绕过了验证，用于Receiver判断是否需要恢复属性 */
    private static volatile boolean sBypassActive = false;
 /** 包验证者（车机应用商店 com.ecarx.appstore 的 VerifyService）旁路原值 */
    private static android.content.Context sAppContext = null;
    private static Integer sSavedVerifierEnable = null;
    private static Integer sSavedVerifierAdb = null;
    private static volatile boolean sVerifierBypassActive = false;
 /** 系统安装器路径的旁路原值：null=未激活；""=原值为空。由 ensure/releaseBypassForSystemInstaller 管理 */
    private static volatile String sSysInstallerSaved = null;
 /** 最近一次静默安装提交失败的原因（供 MainActivity 回退系统安装器时提示用户，成功时为 null） */
    public static volatile String sLastFailReason = null;

 /**
 * 静默安装APK（不修改系统属性）。
 * 适用于已刷入修改版 services.jar 的车机——ApkAuth.auth 已被改为 return true。
 *
 * @param context 上下文
 * @param apkFile APK文件
 * @return true=已提交安装会话（异步，实际结果通过 UpdateResultReceiver 接收）
 */
    public static boolean install(Context context, File apkFile) {
        return installInternal(context, apkFile, false);
    }

 /**
 * 静默安装APK（临时绕过 ECARX 白名单验证）。
 * 仅在安装前设置 sys.jsbd.apk_verify=1，安装结果回调后立即恢复原值。
 * 适用于原版 services.jar 的车机。风险窗口仅几秒。
 *
 * @param context 上下文
 * @param apkFile APK文件
 * @return true=已提交安装会话
 */
    public static boolean installWithVerifyBypass(Context context, File apkFile) {
        return installInternal(context, apkFile, true);
    }

 /** 本次静默安装是否成功开启 ECARX 验证旁路（false=旁路未生效，安装可能被 ApkAuth 拒绝） */
    public static boolean isBypassEffective() {
        return sBypassActive;
    }

 /**
 * 系统安装器路径的验证旁路：拉起系统安装器前调用，确保 sys.jsbd.apk_verify=1
 * （车机未刷修改版 services.jar 时 ApkAuth 会拦所有安装，静默路径的旁路在失败回调后
 * 已被恢复，系统安装器再装会被拦，必须重新开启）。
 * 安装结束（onActivityResult）后由 releaseBypassForSystemInstaller() 恢复原值。
 * @return true=旁路已生效
 */
    public static boolean ensureBypassForSystemInstaller() {
        try {
            String cur = getProp(PROP_APK_VERIFY);
            String curSafe = (cur == null) ? "" : cur;
            if (setProp(PROP_APK_VERIFY, "1") && "1".equals(getProp(PROP_APK_VERIFY))) {
                sSysInstallerSaved = curSafe;
                Log.i(TAG, "sys-installer bypass ON: sys.jsbd.apk_verify=1 (original=\"" + curSafe + "\")");
                if (sAppContext != null) {
                    disablePackageVerifier(sAppContext);
                }
                return true;
            }
            Log.w(TAG, "sys-installer bypass NOT effective (SELinux may deny setting sys.jsbd.*)");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "ensureBypassForSystemInstaller failed: " + t.getMessage());
            return false;
        }
    }

 /** 系统安装器路径结束（用户从安装器返回/取消选择）后恢复旁路原值；未激活时无操作。 */
    public static void releaseBypassForSystemInstaller() {
        if (sSysInstallerSaved == null) return;
        String restoreTo = sSysInstallerSaved;
        sSysInstallerSaved = null;
        setProp(PROP_APK_VERIFY, restoreTo);
        Log.i(TAG, "sys-installer bypass OFF: sys.jsbd.apk_verify=\"" + restoreTo + "\"");
        if (sAppContext != null) {
            restorePackageVerifier(sAppContext);
        }
    }

 /**
 * 关闭车机应用商店的"包验证者"（PackageVerifier）。
 * 日志证据：静默安装 commit 后，PMS 把 APK 交给 com.ecarx.appstore.verifier.VerifyService，
 * 它联网 appstore-api.xchanger.cn 查白名单，服务器对 com.ecarx.carmedia 返回 Reject，
 * PMS 立即中止、PackageInstaller 回调缺 EXTRA_STATUS（=我们收到的 -999）。
 * 这层校验与 sys.jsbd.apk_verify（ApkAuth）无关，是 AOSP 标准的 PackageVerifier 机制。
 * 我们是 system uid（priv-app），有 WRITE_SECURE_SETTINGS，可直接临时关闭，装完恢复。
 */
    public static boolean disablePackageVerifier(Context context) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            int curEn = Settings.Global.getInt(cr, "package_verifier_enable", 1);
            int curAdb = Settings.Global.getInt(cr, "verifier_verify_adb_installs", 1);
            sSavedVerifierEnable = curEn;
            sSavedVerifierAdb = curAdb;
            Settings.Global.putInt(cr, "package_verifier_enable", 0);
            Settings.Global.putInt(cr, "verifier_verify_adb_installs", 0);
            int nowEn = Settings.Global.getInt(cr, "package_verifier_enable", 1);
            sVerifierBypassActive = (nowEn == 0);
            Log.i(TAG, "package verifier bypass " + (sVerifierBypassActive ? "ON" : "NOT effective")
                    + " (enable " + curEn + "->" + nowEn + ", adb " + curAdb + "->0)");
            return sVerifierBypassActive;
        } catch (Throwable t) {
            Log.w(TAG, "disablePackageVerifier failed: " + t.getMessage());
            return false;
        }
    }

 /** 恢复包验证者原值（收到安装结果后调用，与 ApkAuth 旁路一起关） */
    public static void restorePackageVerifier(Context context) {
        if (!sVerifierBypassActive) return;
        sVerifierBypassActive = false;
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            if (sSavedVerifierEnable != null) {
                Settings.Global.putInt(cr, "package_verifier_enable", sSavedVerifierEnable);
            }
            if (sSavedVerifierAdb != null) {
                Settings.Global.putInt(cr, "verifier_verify_adb_installs", sSavedVerifierAdb);
            }
            Log.i(TAG, "package verifier restored: enable=" + sSavedVerifierEnable + ", adb=" + sSavedVerifierAdb);
        } catch (Throwable t) {
            Log.w(TAG, "restorePackageVerifier failed: " + t.getMessage());
        }
        sSavedVerifierEnable = null;
        sSavedVerifierAdb = null;
    }


 /**
 * 安装未知应用（REQUEST_INSTALL_PACKAGES）AppOps 自动放行。
 * 系统安装器弹"未知来源"提示时，检查的是"发起安装的应用"的 OP_REQUEST_INSTALL_PACKAGES 开关：
 * - 正常情况发起方是本应用（CarMedia），给本应用放行即可；
 * - 个别吉利/联发科车机 ROM 把发起方 uid 反查成了系统预装的 LPPe Service
 * （com.mediatek.location.lppe.main——联发科定位服务，与安装毫无关系），
 * 于是提示"打开 LPPe Service 的安装未知应用权限"，让用户去设置里手动开。
 * 本方法利用 CarMedia 的系统签名权限（system uid + platform 签名）把本应用与
 * LPPe Service 的该开关都直接写成 ALLOWED，系统安装器的检查直接通过，不再弹窗引导
 * 用户去设置页开权限。LPPe Service 未声明 REQUEST_INSTALL_PACKAGES，放行它零风险。
 * @return true=本应用已放行
 */
    public static boolean ensureInstallUnknownSourceAllowed(Context context) {
        boolean selfOk = false;
        try {
            Object appOps = context.getSystemService("appops");
            if (appOps == null) {
                Log.w(TAG, "appops service not available");
                return false;
            }
            // AppOpsManager.permissionToOpCode 与 setMode 均为隐藏 API，反射调用
            Method permissionToOpCode = Class.forName("android.app.AppOpsManager")
                    .getMethod("permissionToOpCode", String.class);
            int opCode = (Integer) permissionToOpCode.invoke(null, "android.permission.REQUEST_INSTALL_PACKAGES");
            if (opCode < 0) opCode = 66; // OP_REQUEST_INSTALL_PACKAGES（Android 8~12 一致）
            Method setMode = appOps.getClass().getMethod("setMode", int.class, int.class, String.class, int.class);
            final int MODE_ALLOWED = 0;
            // 1) 本应用（正常发起方）
            String self = context.getPackageName();
            int selfUid = context.getPackageManager().getPackageUid(self, 0);
            setMode.invoke(appOps, opCode, selfUid, self, MODE_ALLOWED);
            selfOk = true;
            Log.i(TAG, "install-unknown-source ALLOWED for " + self);
            // 2) 关键兜底：system uid(1000) 名下有几十个系统应用，ROM 的 ExternalSourcesBlockedDialog
            // 用 getPackageNameForUid(ORIGINATING_UID) 反查发起方，可能返回 uid=1000 下任意一个包名
            // （历史上出现过 LPPe Service、现在又是别的）。与其逐个猜，不如把 selfUid 名下所有包
            // 全部放行 OP_REQUEST_INSTALL_PACKAGES——这些包都是系统组件，放行未知应用安装零风险，
            // 且我们自身就是 system uid，本就具备该权限。
            try {
                String[] pkgs = context.getPackageManager().getPackagesForUid(selfUid);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        if (pkg == null || pkg.equals(self)) continue;
                        try {
                            setMode.invoke(appOps, opCode, selfUid, pkg, MODE_ALLOWED);
                        } catch (Throwable ignored) {}
                    }
                    Log.i(TAG, "install-unknown-source ALLOWED for all " + pkgs.length + " packages under uid " + selfUid);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "ensureInstallUnknownSourceAllowed failed: " + t.getMessage());
        }
        return selfOk;
    }

    private static boolean installInternal(Context context, File apkFile, boolean bypassVerify) {
        if (apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "apk file not found: " + apkFile);
            sLastFailReason = "更新包文件不存在";
            return false;
        }
        sAppContext = context.getApplicationContext();
        // 每次尝试前重置上次失败原因
        sLastFailReason = null;

        // 安装未知应用 AppOps 自动放行（本应用 + LPPe Service 兜底）：
        // 即便静默安装失败回退系统安装器，也不会再弹"打开 XX 安装未知应用权限"的引导。
        try {
            ensureInstallUnknownSourceAllowed(context);
        } catch (Throwable ignored) {}

        // 关闭车机应用商店的包验证者（com.ecarx.appstore.verifier.VerifyService 会联网查白名单并 Reject）
        try {
            disablePackageVerifier(context);
        } catch (Throwable ignored) {}

        if (bypassVerify) {
            // 保存原值并临时打开开关
            sSavedPropValue = getProp(PROP_APK_VERIFY);
            // 注意：SystemProperties.set 是异步的，且 SELinux 拒绝时 Java 层不抛异常，
            // 必须读回验证，否则旁路可能根本没生效却误以为已打开
            if (setProp(PROP_APK_VERIFY, "1") && "1".equals(getProp(PROP_APK_VERIFY))) {
                sBypassActive = true;
                Log.i(TAG, "temp bypass OK: sys.jsbd.apk_verify=1 (original=\""
                        + sSavedPropValue + "\", will restore after install)");
            } else {
                sBypassActive = false;
                Log.w(TAG, "bypass NOT effective: readback != 1 after setProp"
                        + " (SELinux may deny system_app writing sys.jsbd.*), install will likely be rejected by ApkAuth");
            }
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        // 旁路是否生效写入元信息：-999 时区分"签名一致但 ApkAuth 白名单拦截"
        // bypassVerify=false（已刷修改版 services.jar）视为不需要旁路，记 1
        final int bypassOk = (!bypassVerify || sBypassActive) ? 1 : 0;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(PREF_UPDATE_BYPASS_OK, bypassOk).apply();
        // 提交前记录本次安装包的版本元信息：失败时 Receiver 才能给出"降级/同版本"等详细原因，
        // 而不是只看到笼统的未知错误码（如 -999）
        recordInstallMeta(context, apkFile);
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        params.setSize(apkFile.length());

        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            // 写入APK数据到session
            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("carmedia_update", 0, apkFile.length())) {
                byte[] buffer = new byte[65536];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                session.fsync(out);
            }

            // 构建结果回调 PendingIntent
            Intent intent = new Intent(ACTION_UPDATE_RESULT);
            intent.setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // 提交安装 —— 系统签名应用调用时不弹确认框，直接后台安装
            session.commit(pi.getIntentSender());
            Log.i(TAG, "install committed, sessionId=" + sessionId
                    + ", size=" + apkFile.length()
                    + ", bypass=" + bypassVerify
                    + ", path=" + apkFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "install failed: " + e.getMessage(), e);
            sLastFailReason = "安装会话写入失败：" + e.getMessage();
            if (session != null) {
                try { session.abandon(); } catch (Exception ignored) {}
            }
            // 提交失败，立刻恢复属性
            restorePropIfNeeded();
            return false;
        } catch (SecurityException e) {
            Log.e(TAG, "install denied (missing INSTALL_PACKAGES or not system signed): "
                    + e.getMessage(), e);
            sLastFailReason = "缺少安装权限或非系统签名，静默安装被拒绝";
            if (session != null) {
                try { session.abandon(); } catch (Exception ignored) {}
            }
            restorePropIfNeeded();
            return false;
        } finally {
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
        }
    }

 /**
 * 安装完成后恢复 sys.jsbd.apk_verify 原值。
 * 由 UpdateResultReceiver 在收到安装结果时调用。
 */
    public static void onInstallResultReceived() {
        restorePropIfNeeded();
        // 包验证者恢复需要 ContentResolver，由 Receiver 传 context 进来（重载）
    }

 /**
 * 进程启动自检：若上次安装因 force-stop 中断导致 sys.jsbd.apk_verify 残留为 1，
 * 这里统一恢复为空，避免旁路长期开启（全局 APK 验证被绕过）。
 * 在 MainActivity.onCreate 调用。
 */
    public static void restorePropOnStart(Context context) {
        try {
            String cur = getProp(PROP_APK_VERIFY);
            if ("1".equals(cur)) {
                boolean active = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(PREF_UPDATE_ACTIVE, false);
                // 进程已经重启，说明旧进程已被 force-stop（安装必然已结束），残留旁路直接关闭
                if (!active) {
                    setProp(PROP_APK_VERIFY, "");
                    Log.i(TAG, "startup cleanup: sys.jsbd.apk_verify left as 1, restored to empty");
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_UPDATE_ACTIVE, false).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "restorePropOnStart failed: " + t.getMessage());
        }
    }

 /** Receiver 收到结果后调：同时恢复 ApkAuth 旁路与包验证者旁路 */
    public static void onInstallResultReceived(Context context) {
        restorePropIfNeeded();
        restorePackageVerifier(context);
    }

    private static void restorePropIfNeeded() {
        if (!sBypassActive) return;
        sBypassActive = false;
        String restoreTo = (sSavedPropValue != null) ? sSavedPropValue : "";
        setProp(PROP_APK_VERIFY, restoreTo);
        Log.i(TAG, "restored sys.jsbd.apk_verify=\"" + restoreTo + "\" (bypass window closed)");
        sSavedPropValue = null;
    }

 /**
 * 安装前解析新包与已装版本的 versionCode/versionName 并持久化。
 * Receiver 收到失败回调时据此给出"服务器包版本过低（降级）/与当前相同"等详细原因；
 * 安装成功后由 UpdateResultReceiver 调用 {@link #clearInstallMeta} 清理。
 * 解析失败不阻塞安装（放行交给系统兜底）。
 */
    public static void recordInstallMeta(Context context, File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo newPi = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            PackageInfo oldPi = pm.getPackageInfo(context.getPackageName(), 0);
            int newCode = (newPi != null) ? newPi.versionCode : -1;
            String newName = (newPi != null && newPi.versionName != null) ? newPi.versionName : "";
            int oldCode = (oldPi != null) ? oldPi.versionCode : -1;
            String oldName = (oldPi != null && oldPi.versionName != null) ? oldPi.versionName : "";
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_UPDATE_VERCODE_NEW, newCode)
                    .putString(PREF_UPDATE_VERNAME_NEW, newName)
                    .putInt(PREF_UPDATE_VERCODE_OLD, oldCode)
                    .putString(PREF_UPDATE_VERNAME_OLD, oldName)
                    .apply();
            Log.i(TAG, "install meta: new=v" + newName + "(" + newCode + ") installed=v" + oldName + "(" + oldCode + ")");
        } catch (Throwable t) {
            Log.w(TAG, "recordInstallMeta failed: " + t.getMessage());
        }
    }

 /** 安装成功后清理本次版本元信息（避免下次失败时误用上次的版本号） */
    public static void clearInstallMeta(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(PREF_UPDATE_VERCODE_NEW)
                    .remove(PREF_UPDATE_VERNAME_NEW)
                    .remove(PREF_UPDATE_VERCODE_OLD)
                    .remove(PREF_UPDATE_VERNAME_OLD)
                    .remove(PREF_UPDATE_SIG_MATCH)
                    .remove(PREF_UPDATE_BYPASS_OK)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "clearInstallMeta failed: " + t.getMessage());
        }
    }

    // ==================== SystemProperties 反射封装 ====================

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            Log.w(TAG, "getProp failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setProp failed (need system uid): " + t.getMessage());
            return false;
        }
    }
}
