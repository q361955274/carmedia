package com.ecarx.carmedia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启接收器：BOOT_COMPLETED / LOCKED_BOOT_COMPLETED 后拉起前台服务。
 * directBootAware=true，锁屏前阶段也能启动（与原实现一致）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "CarMedia";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "BootReceiver action=" + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            try {
                Intent service = new Intent(context, CarMediaService.class);
                context.startForegroundService(service);
            } catch (Throwable t) {
                Log.w(TAG, "BootReceiver startForegroundService failed: " + t.getMessage());
            }
            // 开机即推一次桌面小卡片。壁纸层不依赖服务——服务未就绪时
            // updateAll 只补壁纸（磁盘缓存兜底 + 自愈重试），不画初始化文案；
            // 服务就绪后 onCreate / 控制器选中也会再刷，卡片背景无需等"重放小部件"。
            try {
                CarMediaWidget.updateAll(context);
            } catch (Throwable t) {
                Log.w(TAG, "BootReceiver widget updateAll failed: " + t.getMessage());
            }
        }
    }
}
