package com.ecarx.carmedia;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

/**
 * 接收 PackageInstaller 静默安装的结果回调。
 *
 * 在 AndroidManifest 中注册：
 * <receiver android:name=".UpdateResultReceiver" android:exported="false">
 * <intent-filter>
 * <action android:name="com.ecarx.carmedia.UPDATE_RESULT" />
 * </intent-filter>
 * </receiver>
 */
public class UpdateResultReceiver extends BroadcastReceiver {
    private static final String TAG = "UpdateResult";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        Log.i(TAG, "status=" + status + " pkg=" + pkg + " msg=" + message);
        // 已收到安装结果（无论成败），取消 MainActivity 的超时看护
        MainActivity.cancelInstallTimeout();

        // 无论成功失败，只要收到结果就立刻恢复 sys.jsbd.apk_verify 原值
        // 避免全局验证开关长期开启导致误装其他app搞崩系统设置
        SilentInstaller.onInstallResultReceived(context);

        // 安装会话已结束，清除"进行中"标记
        SharedPreferences sp = context.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE);
        sp.edit().putBoolean(SilentInstaller.PREF_UPDATE_ACTIVE, false).apply();

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // 非系统签名时会要求用户确认 —— 把确认界面拉起来
                Log.w(TAG, "pending user action (not fully silent)");
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirmIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "start confirm activity failed: " + e.getMessage());
                    }
                }
                // 兜底：确认界面未能弹出/用户忽略时按钮可能卡住，先复位并提示
                MainActivity.notifyUpdateResult("需要手动确认安装，请留意系统安装提示");
                break;

            case PackageInstaller.STATUS_SUCCESS:
                Log.i(TAG, "INSTALL SUCCESS — 新版本已生效，自动重新打开主界面");
                // 安装成功：清理本次版本元信息，避免下次失败时误用
                SilentInstaller.clearInstallMeta(context);
                // 装的是自身：系统装完会 force-stop 旧进程，界面消失回桌面——很多人以为"点更新就闪退"，
                // 实际已装好。这里持久化成功标记（commit 同步落盘，防 force-stop 丢 apply）+ AlarmManager
                // 延迟拉起新版本主界面（进程被杀也能拉起），新进程 showPendingUpdateResult 会弹成功确认。
                try {
                    context.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE)
                            .edit().putString(SilentInstaller.PREF_UPDATE_RESULT, "更新安装成功，正在重新打开应用").commit();
                } catch (Throwable ignored) {}
                scheduleRestart(context);
                // ★ 在线更新(覆盖安装 carmedia)会经 PMS killUid(1000) 连带杀死同 uid 组的整车设置
                // (ecarx.settings，sharedUserId=android.uid.system)；Kanzi 3D 设置界面可能以半初始化状态
                // 回弹 → 只剩"关于"、车控项空白。此处对设置 force-stop 一次(立即+延迟兜底)强制干净冷启动恢复。
                SettingsRecovery.recover(context);
                break;

            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Log.e(TAG, "INSTALL FAILED: status=" + status + " msg=" + message);
                // 静默安装结果失败 → 统一回退系统安装器（降级包等场景系统安装器可经用户确认安装），
                // fallback 内部负责：系统 Toast + 结果持久化 + UI 复位 + 拉起系统安装器
                MainActivity.fallbackToSystemInstaller(context,
                        "更新安装失败：" + friendlyReason(status, message) + versionDetail(context));
                break;

            case -999:
                // ★ 特殊处理：PackageInstaller 回调未携带 EXTRA_STATUS（PackageManager 在解析/校验
                // 阶段直接拒绝，如降级安装、签名不一致、系统校验拦截），extras 里拿不到失败原因。
                // 结合安装前记录的版本元信息给出尽可能明确的提示，并回退系统安装器让用户手动确认。
                String unknownMsg = "更新安装失败（系统拒绝安装，错误码 -999）" + versionDetail(context)
                        + possibleCauses(context)
                        + "\n可查看日志（tag: UpdateResult / PackageManager 的 Downgrade detected）进一步定位。";
                Log.w(TAG, "status -999: EXTRA_STATUS missing, install rejected by system, fallback to system installer");
                MainActivity.fallbackToSystemInstaller(context, unknownMsg);
                break;

            default:
                Log.w(TAG, "unknown status: " + status);
                MainActivity.fallbackToSystemInstaller(context,
                        "更新安装失败（未知错误码 " + status + "）" + versionDetail(context));
                break;
        }
    }

 /**
 * 根据安装前 SilentInstaller 记录的版本元信息，补充"新包/当前版本号"上下文。
 * 没有记录时返回空串。典型输出："（服务器版本 14，当前已装 24）"。
 */
    private String versionDetail(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE);
            int newCode = sp.getInt(SilentInstaller.PREF_UPDATE_VERCODE_NEW, -1);
            int oldCode = sp.getInt(SilentInstaller.PREF_UPDATE_VERCODE_OLD, -1);
            String newName = sp.getString(SilentInstaller.PREF_UPDATE_VERNAME_NEW, "");
            String oldName = sp.getString(SilentInstaller.PREF_UPDATE_VERNAME_OLD, "");
            if (newCode < 0 || oldCode < 0) return "";
            String nv = newName.isEmpty() ? String.valueOf(newCode) : (newName + "（" + newCode + "）");
            String ov = oldName.isEmpty() ? String.valueOf(oldCode) : (oldName + "（" + oldCode + "）");
            if (newCode < oldCode) {
                return "\n原因：服务器包版本 " + nv + " 低于当前已装 " + ov + "（系统禁止降级安装）。"
                        + "请等待服务器上传新版本，或先卸载旧版再安装。";
            } else if (newCode == oldCode) {
                return "\n原因：服务器包版本 " + nv + " 与当前已装 " + ov + " 相同，可能上传了旧包。";
            }
            return "\n（服务器包 " + nv + "，当前已装 " + ov + "）";
        } catch (Throwable t) {
            return "";
        }
    }

 /**
 * 根据安装前记录的版本元信息，给出 -999（系统拒绝安装）最可能的原因。
 * 关键：不能笼统罗列"版本过低"——版本号高于当前时绝无可能是降级。
 */
    private String possibleCauses(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE);
            int newCode = sp.getInt(SilentInstaller.PREF_UPDATE_VERCODE_NEW, -1);
            int oldCode = sp.getInt(SilentInstaller.PREF_UPDATE_VERCODE_OLD, -1);
            if (newCode < 0 || oldCode < 0) {
                return "\n可能原因：签名不一致，或车机系统校验（ApkAuth 白名单）拦截。";
            }
            if (newCode < oldCode) {
                return "\n原因：服务器包版本号低于当前已装（系统禁止降级安装）。";
            }
            if (newCode > oldCode) {
                // 版本号正常。结合安装前的签名预检与旁路结果，给出确切原因（不再笼统猜）
                int sigMatch = sp.getInt(SilentInstaller.PREF_UPDATE_SIG_MATCH, -1);
                int bypassOk = sp.getInt(SilentInstaller.PREF_UPDATE_BYPASS_OK, -1);
                String head = "\n版本号正常（" + newCode + " > " + oldCode + "，非降级）。";
                if (sigMatch == 0) {
                    return head + "确切原因：签名不一致（服务器包签名与当前已装不同，系统拒绝覆盖升级）。"
                            + "需用相同签名重打包，或先卸载旧版再装。";
                }
                if (sigMatch == 1 && bypassOk == 0) {
                    return head + "确切原因：签名一致，但 ApkAuth 旁路未生效（车机 SELinux 拦截了 setprop），"
                            + "白名单校验拦截了安装。";
                }
                if (sigMatch == 1) {
                    return head + "签名一致、旁路已开仍被拒，最可能是车机 ApkAuth 白名单未收录本包，"
                            + "或 ROM 其他系统校验拦截。";
                }
                return head + "可能原因：签名不一致，或车机系统校验（ApkAuth 白名单）拦截。";
            }
            return "\n原因：服务器包版本号与当前已装相同。";
        } catch (Throwable t) {
            return "\n可能原因：签名不一致，或车机系统校验（ApkAuth 白名单）拦截。";
        }
    }

 /** 结果持久化 + 系统 Toast（应用随后可能被 force-stop，系统 Toast 不受影响） */
    private void showResult(Context context, String msg) {
        try {
            context.getSharedPreferences(SilentInstaller.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(SilentInstaller.PREF_UPDATE_RESULT, msg).apply();
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "showResult failed: " + t.getMessage());
        }
    }

 /**
 * 静默安装成功（更新的是自身）后：系统会 force-stop 旧进程，用户看到界面消失会误以为"点更新就闪退"。
 * 用 AlarmManager 延迟拉起新版本主界面（Alarm 由系统持有，进程被杀后仍会触发）。
 */
    private static void scheduleRestart(Context context) {
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 0x55, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
                Log.i(TAG, "scheduled restart in 1.5s to launch new version");
            }
        } catch (Throwable t) {
            Log.w(TAG, "scheduleRestart failed: " + t.getMessage());
        }
    }

 /** 把 PackageInstaller 的状态码翻译成用户可读的原因 */
    private String friendlyReason(int status, String message) {
        String m = (message == null) ? "" : message;
        // 常见错误直接翻译，其余带原始码便于排查
        if (m.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) return "新版本签名与当前版本不一致";
        if (m.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) return "新版本号低于当前版本";
        if (m.contains("INSTALL_FAILED_UID_CHANGED")) return "应用 UID 变更，无法升级";
        if (m.contains("INSTALL_FAILED_INVALID_APK")) return "安装包无效（可能被白名单校验拒绝）";
        if (m.contains("INSTALL_FAILED_USER_RESTRICTED")) return "被系统限制安装未知应用";
        if (m.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "存储空间不足";
        if (m.contains("INSTALL_FAILED_ALREADY_EXISTS")) return "已安装相同版本";
        if (m.contains("INSTALL_FAILED_INVALID_URI")) return "安装包地址无效";
        if (m.contains("INSTALL_FAILED_CONFLICTING_PROVIDER")) return "与系统组件冲突";
        if (m.contains("INSTALL_FAILED_MISSING_SHARED_LIBRARY")) return "缺少系统共享库";
        // message 为空时按状态码给可读描述，避免只显示"错误码 N"
        if (m.isEmpty()) {
            switch (status) {
                case PackageInstaller.STATUS_FAILURE_ABORTED: return "安装被中止（可能用户取消或系统拦截）";
                case PackageInstaller.STATUS_FAILURE_BLOCKED: return "安装被系统阻止（如禁止安装未知来源应用）";
                case PackageInstaller.STATUS_FAILURE_CONFLICT: return "与现有应用冲突";
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "应用不兼容（签名或设备限制）";
                case PackageInstaller.STATUS_FAILURE_INVALID: return "安装包无效";
                case PackageInstaller.STATUS_FAILURE_STORAGE: return "存储空间不足";
                default: return "错误码 " + status;
            }
        }
        return m;
    }
}
