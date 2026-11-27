package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * 酷我车机版(kwmusiccar)真实播放进度 AIDL 客户端。
 *
 * 背景 :酷我 MediaSession 的 PlaybackState.position 由
 * PlayControlImpl.B1() 提供(裸 MainService.f(),无本地累计补偿),播放中不实时更新、
 * 重开 App 后恒 0 —— CarMedia 只能从 MediaSession 拿到 0,进度条从 0 累加。
 * 但酷我暴露了 exported=true 的 RemoteService(action=cn.kuwo.service.RemoteService),
 * 其 AIDL AIDLPlayContentInterface.getCurrentPos(playerId) 返回与酷我 UI 一致的实时
 * 位置(PlayControlImpl.getCurrentPos(),含 l0() 本地补偿)。playerId=1 为音乐播放器
 * (RemoteService.java:79 addPlayManager(1, ...) 反编译确认)。
 *
 * 本类绑定该服务,后台 HandlerThread 周期轮询 getCurrentPos(1),缓存最近一次结果及
 * 时间戳;CarMediaService.getPlaybackPosition() 对酷我以此作为累加器校准源。
 * 酷我未安装/未运行/bind 失败时优雅降级(getEstimatedPosMs() 返回 -1)。
 */
public class KuwoAidlClient {

    private static final String TAG = "KuwoAidl";

    // 酷我包名有两套:标准版 kwmusiccar(夜森模拟器) 与 GKUI 一卡通定制版 kwmusiccas(真车
    // 吉利车机,版本 GKUI_yikatong2.6.8.3)。服务类路径、AIDL 接口相同,仅包名不同,需都支持。
    private static final String PKG_CAR = "cn.kuwo.kwmusiccar";
    private static final String PKG_CAS = "cn.kuwo.kwmusiccas";
    private static final String SVC = "cn.kuwo.service.remote.RemoteService";
    private static final String DESCRIPTOR = "cn.kuwo.service.remote.AIDLPlayContentInterface";
    private static final int PLAYER_ID_MUSIC = 1;

    // AIDLPlayContentInterface.Stub 的 transact code(反编译确认):
    // TRANSACTION_getStatus=11, getDuration=12, getCurrentPos=13
    private static final int TRANSACTION_getCurrentPos = 13;
    private static final int TRANSACTION_getDuration = 12;
    private static final int TRANSACTION_getStatus = 11;
    private static final int TRANSACTION_seek = 10; // 0xa: seek(II)V, arg0=playerId, arg1=posMs

    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long REBIND_RETRY_MS = 3000L;

    private final Context mContext;
 /** 实际绑定的酷我包名(标准版或 GKUI 定制版),未确定时为 null。 */
    private volatile String mActivePkg = null;
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: " + name);
            mRemote = service;
            mBound = true;
            // 连接成功立即轮询一次,尽快拿到真实位置
            if (mPollHandler != null && !mStopped) {
                mPollHandler.removeCallbacks(pollRunnable);
                mPollHandler.post(pollRunnable);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            mRemote = null;
            mBound = false;
            mAvailable = false;
            // 酷我进程被杀/重启后自动重绑
            scheduleRebind();
        }
    };

    private HandlerThread mPollThread;
    private Handler mPollHandler;
    private volatile IBinder mRemote;
    private volatile boolean mBound;
    private volatile boolean mAvailable;

 /** 最近一次 getCurrentPos(1) 结果(毫秒);-1=尚未拿到有效值。 */
    private volatile long lastPosMs = -1;
 /** lastPosMs 对应的时间戳(elapsedRealtime);0=未知。 */
    private volatile long lastPosTimeMs = 0;

    private volatile boolean mStopped = false;

 /** 首次/重新拿到有效位置时通知(供 CarMedia 重置非蓝牙累加器,立即用 AIDL 真实值初始化)。 */
    public interface OnPosListener {
        void onPosReady();
    }

    private volatile OnPosListener mOnPos;
    private volatile boolean mHasPosOnce = false;

    public void setOnPosListener(OnPosListener l) {
        mOnPos = l;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mStopped) {
                return;
            }
            pollOnce();
            if (mPollHandler != null && !mStopped) {
                mPollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public KuwoAidlClient(Context context) {
        this.mContext = context.getApplicationContext();
    }

 /** 启动:创建轮询线程并绑定酷我 RemoteService(标准版/GKUI 定制版任一未安装则跳过)。 */
    public synchronized void start() {
        mStopped = false; // 复位停止标志(支持 stop 后重新 start 继续轮询)
        if (mPollThread == null) {
            mPollThread = new HandlerThread("kuwo-aidl-poll");
            mPollThread.start();
            mPollHandler = new Handler(mPollThread.getLooper());
        }
        String pkg = findInstalledPkg();
        if (pkg == null) {
            Log.d(TAG, "start: kuwo not installed, skip");
            return;
        }
        mActivePkg = pkg;
        if (mBound || mRemote != null) {
            return;
        }
        bindTo(mActivePkg);
    }

 /** 绑定指定包名的酷我 RemoteService;失败则安排重试。 */
    private void bindTo(String pkg) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, SVC));
            boolean ok = mContext.bindService(intent, mConn, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "start: bindService(" + pkg + ")=" + ok);
            mBound = ok;
            if (!ok) {
                scheduleRebind();
            }
        } catch (Exception e) {
            Log.w(TAG, "start bind error: " + e.getMessage());
            scheduleRebind();
        }
    }

 /** 停止:解绑并停止轮询(可再次 start)。 */
    public synchronized void stop() {
        mStopped = true;
        if (mPollHandler != null) {
            mPollHandler.removeCallbacksAndMessages(null);
        }
        if (mBound) {
            try {
                mContext.unbindService(mConn);
            } catch (Exception ignored) {
            }
        }
        mBound = false;
        mRemote = null;
        mAvailable = false;
        lastPosMs = -1;
        lastPosTimeMs = 0;
        mHasPosOnce = false;
    }

    private void scheduleRebind() {
        if (mPollHandler == null || mStopped) {
            return;
        }
        mPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mStopped || mBound || mRemote != null) {
                    return;
                }
                if (findInstalledPkg() == null) {
                    return;
                }
                if (mActivePkg == null) {
                    return;
                }
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(mActivePkg, SVC));
                    boolean ok = mContext.bindService(intent, mConn, Context.BIND_AUTO_CREATE);
                    Log.d(TAG, "rebind attempt(" + mActivePkg + ")=" + ok);
                    mBound = ok;
                    if (!ok) {
                        scheduleRebind();
                    }
                } catch (Exception e) {
                    scheduleRebind();
                }
            }
        }, REBIND_RETRY_MS);
    }

    private void pollOnce() {
        IBinder remote = mRemote;
        if (remote == null) {
            mAvailable = false;
            return;
        }
        try {
            int pos = transactInt(remote, TRANSACTION_getCurrentPos, PLAYER_ID_MUSIC);
            if (pos >= 0) {
                boolean firstValid = !mHasPosOnce;
                lastPosMs = pos;
                lastPosTimeMs = SystemClock.elapsedRealtime();
                mAvailable = true;
                mHasPosOnce = true;
                Log.d(TAG, "pollOnce: pos=" + pos + "ms");
                if (firstValid) {
                    OnPosListener l = mOnPos;
                    if (l != null) {
                        l.onPosReady();
                    }
                }
            } else {
                mAvailable = false;
                Log.w(TAG, "pollOnce: pos<0 (" + pos + ")");
            }
        } catch (Exception e) {
            mAvailable = false;
            Log.w(TAG, "pollOnce error: " + e.getMessage());
        }
    }

    private static int transactInt(IBinder remote, int code, int arg) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(arg);
            remote.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

 /** 当前估算的实时位置(ms,已推进到调用时刻);-1=不可用。 */
 /**
 * Kuwo AIDL seek: transact(0xa, playerId=1, posMs). Verified on
 * emulator: 176683ms -> seek(30000) -> 32731ms 3s later.
 * On success also moves the poll cache to the target so the accumulator
 * resumes from the new position immediately (no stale value in between).
 */
    public boolean seek(long posMs) {
        IBinder remote = mRemote;
        if (remote == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(PLAYER_ID_MUSIC);
            data.writeInt((int) posMs);
            boolean ok = remote.transact(TRANSACTION_seek, data, reply, 0);
            reply.readException();
            if (ok) {
                lastPosMs = posMs;
                lastPosTimeMs = SystemClock.elapsedRealtime();
                Log.d(TAG, "seek ok: " + posMs + "ms");
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "seek error: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public long getEstimatedPosMs() {
        if (!mAvailable || lastPosMs < 0) {
            return -1;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastPosTimeMs <= 0) {
            return Math.max(0, lastPosMs);
        }
        long est = lastPosMs + (now - lastPosTimeMs);
        return Math.max(0, est);
    }

 /** 是否已绑定并成功取到过位置。 */
    public boolean isAvailable() {
        return mAvailable;
    }

 /** 返回已安装的酷我包名(标准版优先,其次 GKUI 定制版);均未安装返回 null。 */
    private String findInstalledPkg() {
        android.content.pm.PackageManager pm = mContext.getPackageManager();
        for (String p : new String[]{PKG_CAR, PKG_CAS}) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
