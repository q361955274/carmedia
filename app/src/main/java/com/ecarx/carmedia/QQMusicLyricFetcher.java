package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

import java.util.Arrays;
import java.util.List;

/**
 * QQ 音乐歌词抓取器（P4-QQ，原版逻辑 1:1 移植，AIDL 协议手写实现）。
 *
 * 通过 AIDL 绑定 QQ 音乐车机版服务 com.tencent.qqmusiccar.third.api.QQMusicApiService：
 * - execute("hi", {sdkVersionCode=10000}) 握手
 * - registerEventListener(["playSong","playState"], listener) 监听切歌事件
 * - execute("getCurrentSong", null) 取当前歌曲 JSON
 * - executeAsync("getLyric", {songId}, callback) 异步取歌词
 * - writeAuthViaSu()：root 写入 QQ 音乐第三方授权文件（appid=161）
 *
 * AIDL 协议（手写，等价原版编译产物）：
 * IQQMusicApi DESCRIPTOR = com.tencent.qqmusic.third.api.contract.IQQMusicApi
 * execute TRANSACTION 1 (String, Bundle) -> Bundle
 * executeAsync TRANSACTION 2 (String, Bundle, IQQMusicApiCallback)
 * registerEventListener TRANSACTION 3 (List<String>, IQQMusicApiEventListener) -> Bundle
 * IQQMusicApiCallback DESCRIPTOR = com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback
 * onReturn TRANSACTION 1 (Bundle)
 * IQQMusicApiEventListener DESCRIPTOR = com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener
 * onEvent TRANSACTION 1 (String, Bundle)
 */
public class QQMusicLyricFetcher {

    private static final String TAG = "QQMusicLyric";
    private static final String PKG = "com.tencent.qqmusiccar";
    private static final String SVC = "com.tencent.qqmusiccar.third.api.QQMusicApiService";

    // ============ QQ 音乐车机版真实 AIDL 接口名（原版 smali 确认） ============
    // 注意：必须用 QQ 音乐自己的接口 descriptor，不能用本应用的类名，
    // 否则服务端 Binder 接口校验失败（Binder invocation to an incorrect interface）闪退。
    private static final String DESCRIPTOR_API = "com.tencent.qqmusic.third.api.contract.IQQMusicApi";
    private static final String DESCRIPTOR_CB = "com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback";
    private static final String DESCRIPTOR_LISTENER = "com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener";

    public interface Callback {
        void onLyricReady(String lyric, String title, String artist);
        void onError(String message);
    }

    // ============ API 状态跟踪（供 UI 显示） ============
 /** API 状态：0=空闲 1=调用中 2=错误 3=成功。 */
    public static final int API_IDLE = 0;
    public static final int API_ACTIVE = 1;
    public static final int API_ERROR = 2;
    public static final int API_SUCCESS = 3;

    private volatile int mApiStatus = API_IDLE;
    private volatile String mApiMsg = "";
    private volatile long mApiChangedAt = 0L;
    private volatile long mApiActiveAt = 0L;

 /** ACTIVE 状态保持时长（ms）：调用太快的话 UI 每秒推送看不到绿闪，以最近 ACTIVE 时间为准保持一段时间。 */
    private static final long ACTIVE_HOLD_MS = 3000L;

    private void setApiStatus(int status, String msg) {
        mApiStatus = status;
        mApiMsg = msg != null ? msg : "";
        mApiChangedAt = System.currentTimeMillis();
        if (status == API_ACTIVE) {
            mApiActiveAt = System.currentTimeMillis();
        }
        if (status == API_IDLE || status == API_SUCCESS) {
            mApiMsg = "";
        }
        Log.d(TAG, "apiStatus -> " + status + " " + msg);
    }

 /** 切歌时调用：重置为 IDLE（黄灯）。 */
    public void resetApiStatus() {
        mApiStatus = API_IDLE;
        mApiMsg = "";
        mApiActiveAt = 0L;
        mApiChangedAt = 0L;
        // 切歌：重置歌词标志（上一首歌的歌词不适用于新歌）
        mHasLyric = false;
        mRestartedForAuth = false;
        mRestarting = false;
    }

 /** 供 UI 推送：返回 {status, msg}。ACTIVE 保持期内对外显示 ACTIVE；ERROR/SUCCESS 常亮不回落。 */
    public JSONObject getApiStatus() {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            int st = mApiStatus;
            if (st == API_ACTIVE) {
                // 调用中：无条件绿
            } else if (st == API_IDLE || st == API_SUCCESS) {
                if (mApiActiveAt > 0 && (now - mApiActiveAt) < ACTIVE_HOLD_MS) {
                    st = API_ACTIVE; // 刚完成：保持期内仍显示绿
                }
            }
            // ERROR/SUCCESS：常亮不回落
            o.put("status", st);
            o.put("msg", st == API_IDLE ? "" : mApiMsg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // ================= AIDL 客户端（手写协议） =================

 /** 客户端 API 封装：持有远端 IBinder，手工 Parcel 编解码。 */
    private static class ApiClient {
        private final IBinder mRemote;

        ApiClient(IBinder remote) {
            mRemote = remote;
        }

        Bundle execute(String method, Bundle args) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_API);
                data.writeString(method);
                writeBundle(data, args);
                mRemote.transact(1, data, reply, 0);
                reply.readException();
                return readBundle(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void executeAsync(String method, Bundle args, IBinder callbackBinder) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_API);
                data.writeString(method);
                writeBundle(data, args);
                data.writeStrongBinder(callbackBinder);
                mRemote.transact(2, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        Bundle registerEventListener(List<String> events, IBinder listenerBinder) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_API);
                data.writeStringList(events);
                data.writeStrongBinder(listenerBinder);
                mRemote.transact(3, data, reply, 0);
                reply.readException();
                return readBundle(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

 /** Bundle 参数编码：int 标记 + writeToParcel（与 AIDL 生成代码一致）。 */
        private static void writeBundle(Parcel p, Bundle b) {
            if (b != null) {
                p.writeInt(1);
                b.writeToParcel(p, 0);
            } else {
                p.writeInt(0);
            }
        }

        private static Bundle readBundle(Parcel p) {
            if (p.readInt() != 0) {
                return (Bundle) Bundle.CREATOR.createFromParcel(p);
            }
            return null;
        }
    }

 /** 回调 Stub（本地 Binder，远端 transact 回来）。 */
    private static class CallbackBinder extends android.os.Binder {
        private final Callback mCallback;
        private final java.util.function.Supplier<String> mTitleSupplier;
        private final java.util.function.Supplier<String> mArtistSupplier;
        private final java.util.function.Consumer<List<LrcUtil.Line>> mLrcConsumer;
        private final java.util.function.Consumer<String> mLrcRawConsumer;
        private final java.util.function.Consumer<Integer> mStatusConsumer;

        CallbackBinder(Callback cb,
                       java.util.function.Supplier<String> title,
                       java.util.function.Supplier<String> artist,
                       java.util.function.Consumer<List<LrcUtil.Line>> lrcConsumer,
                       java.util.function.Consumer<String> lrcRawConsumer,
                       java.util.function.Consumer<Integer> statusConsumer) {
            mCallback = cb;
            mTitleSupplier = title;
            mArtistSupplier = artist;
            mLrcConsumer = lrcConsumer;
            mLrcRawConsumer = lrcRawConsumer;
            mStatusConsumer = statusConsumer;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: { // onReturn(Bundle)
                    data.enforceInterface(DESCRIPTOR_CB);
                    Bundle b = null;
                    if (data.readInt() != 0) {
                        b = (Bundle) Bundle.CREATOR.createFromParcel(data);
                    }
                    int code2 = b != null ? b.getInt("code") : -1;
                    String lyric = b != null ? b.getString("data") : null;
                    Log.d(TAG, "getLyric code=" + code2 + " len=" + (lyric != null ? lyric.length() : 0));
                    if (code2 == 0 && lyric != null && !lyric.isEmpty()) {
                        mLrcRawConsumer.accept(lyric);
                        mLrcConsumer.accept(LrcUtil.parse(lyric));
                        if (mStatusConsumer != null) {
                            mStatusConsumer.accept(API_SUCCESS);
                        }
                        if (mCallback != null) {
                            mCallback.onLyricReady(lyric, mTitleSupplier.get(), mArtistSupplier.get());
                        }
                    } else {
                        Log.w(TAG, "getLyric failed code=" + code2);
                        mLrcConsumer.accept(null);
                        if (mStatusConsumer != null) {
                            mStatusConsumer.accept(API_ERROR);
                        }
                        if (mCallback != null) {
                            mCallback.onLyricReady("", mTitleSupplier.get(), mArtistSupplier.get());
                        }
                    }
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

 /** 事件监听 Stub（本地 Binder）。 */
    private static class EventListenerBinder extends android.os.Binder {
        private final Runnable mOnPlaySong;

        EventListenerBinder(Runnable onPlaySong) {
            mOnPlaySong = onPlaySong;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: { // onEvent(String, Bundle)
                    data.enforceInterface(DESCRIPTOR_LISTENER);
                    String event = data.readString();
                    Log.d(TAG, "EVENT " + event);
                    if ("playSong".equals(event) && mOnPlaySong != null) {
                        mOnPlaySong.run();
                    }
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // ================= 实例 =================

    private final Context mContext;
    private final Callback mCallback;
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: " + name);
            mApi = new ApiClient(service);
            doHandshake();
            fetchCurrentSong();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            mApi = null;
            mBound = false;
        }
    };

    private ApiClient mApi;
    private boolean mBound;
 /** su 写授权成功标志：成功一次后不再重复写（QQ 授权文件持久存在，无需每首歌重写）。 */
    private boolean mAuthWritten;
 /** 明确已授权标志：getCurrentSong 返回 code=0（能读到当前歌曲/歌词）置 true。
 * AIDL 实测是"授权是否真正生效"的唯一可靠判据。 */
    private volatile boolean mAuthOk;
 /** 明确未授权标志：仅当 getCurrentSong 返回 code=5（"No permission"）时置 true，
 * getCurrentSong 成功（code=0）时清 false。与通用 mApiStatus 完全解耦——
 * getLyric 失败/瞬态 AIDL 错误不再污染授权显示。
 * Bug#Q1 修订：授权状态完全由 AIDL 实测（mAuthOk/mAuthError）决定，
 * 取消"root 写入即已授权"与"默认已授权"兜底——避免把从未授权误报成已授权。 */
    private volatile boolean mAuthError;
 /** 本次进程内是否已探测过授权文件（保留字段：writeAuthViaSu 成功时置 false 示意重新探测，已无实际 su 读取）。 */
    private boolean mAuthChecked;
 /** code=5 自动重启已执行标志（防止死循环重试）。 */
    private boolean mRestartedForAuth;
 /** 是否已成功拿到过当前歌的歌词（避免瞬态失败误触发备用歌词）。 */
    private volatile boolean mHasLyric;
 /** code=5 自愈重启进行中（期间不对外报错，避免触发备用歌词 fallback）。 */
    private volatile boolean mRestarting;
    private boolean mAuthFileOk;
    private String mCurrentSongId;
    private String mCurrentSongTitle;
    private String mCurrentSongArtist;
    private String mCurrentLrc;
 /** 原始 LRC 文本（含时间戳），无则 null。供 EAS 完整歌词推送 setLyricContent（移植 MediaBridge）。 */
    public String getRawLrc() {
        return mCurrentLrc;
    }
    private List<LrcUtil.Line> mParsedLrc;
    private long mLastFetchTime;
    private CallbackBinder mCallbackBinder;
    private EventListenerBinder mEventListenerBinder;

    public QQMusicLyricFetcher(Context context, Callback callback) {
        this.mContext = context;
        this.mCallback = callback;
        this.mBound = false;
        this.mCurrentSongId = null;
        this.mCurrentSongTitle = null;
        this.mCurrentSongArtist = null;
        this.mCurrentLrc = null;
        this.mParsedLrc = null;
        this.mLastFetchTime = 0L;
    }

 /** 绑定 QQ 音乐服务（QQ 音乐未安装则直接跳过）。
 * 注意：不再自动 root 写授权——只有用户手动点"一键授权"（authorizeQqMusic → forceAuthWrite）
 * 才会拉起 su。未授权时绑定后 AIDL 会返回 code=5，由 UI 提示用户去授权。 */
    public void bind() {
        if (!isQqMusicInstalled()) {
            Log.d(TAG, "bind: QQ music not installed, skip su/bind");
            setApiStatus(API_IDLE, "QQ音乐未安装");
            return;
        }
        if (mBound) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PKG, SVC));
            setApiStatus(API_ACTIVE, "绑定服务中");
            boolean ok = mContext.bindService(intent, mConn, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService returned: " + ok);
            mBound = ok;
            if (!ok) {
                setApiStatus(API_ERROR, "bindService=false");
            }
        } catch (Exception e) {
            Log.e(TAG, "bind error: " + e.getMessage());
            setApiStatus(API_ERROR, e.getMessage());
            if (mCallback != null) {
                mCallback.onError("bind: " + e.getMessage());
            }
        }
    }

 /**
 * 强制重启 QQ 音乐进程（root 下 am force-stop），并等待进程完全退出。
 *
 * 背景（反编译已确认）：QQ 音乐服务进程的授权缓存
 * OpenIDPermissionCache（进程级 HashMap）只在进程启动时从
 * PackageFile/shared_prefs 加载一次。若授权文件写入发生在服务启动之后，
 * 内存缓存永远不含本包名 → execute() 权限校验失败 → getCurrentSong code=5
 * （"No permission!"）。只有重启 QQ 音乐进程重建缓存才会恢复。
 *
 * @return true=已尝试重启（进程被结束或不存在）；false=无 root 执行失败
 */
    public boolean restartQqProcess() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("am force-stop " + PKG + "\n");
            os.writeBytes("exit\n");
            os.flush();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                Log.w(TAG, ">>> restartQqProcess: su timeout, killing");
                try { p.destroy(); } catch (Throwable ignored) { }
                return false;
            }
            int exit = p.exitValue();
            Log.d(TAG, ">>> restartQqProcess: force-stop exit=" + exit);
            // 等待进程完全退出（最多 2.5s），再让调用方重新 bindService
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(500L); } catch (InterruptedException ignored) { }
                if (!isQqProcessAlive()) {
                    break;
                }
            }
            unbind(); // 清掉本侧旧连接状态
            return true;
        } catch (Exception e) {
            Log.d(TAG, ">>> restartQqProcess FAIL: " + e.getMessage());
            return false;
        }
    }

 /** QQ 音乐进程是否存活（root 下 ps 探测）。 */
    private boolean isQqProcessAlive() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("ps -A | grep " + PKG + "\n");
            os.writeBytes("exit\n");
            os.flush();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                try { p.destroy(); } catch (Throwable ignored) { }
                return true; // 探测失败按存活处理，避免死循环等待
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains(PKG)) {
                    try { p.destroy(); } catch (Throwable ignored) { }
                    return true;
                }
            }
            try { p.destroy(); } catch (Throwable ignored) { }
            return false;
        } catch (Exception e) {
            return true; // 失败按存活处理
        }
    }

 /** 解绑。 */
    public void unbind() {
        if (mBound && mConn != null) {
            try {
                mContext.unbindService(mConn);
            } catch (Exception ignored) {
            }
        }
        mBound = false;
        mApi = null;
    }

 /** 握手：hi + 注册切歌事件 + 拉当前歌曲。 */
    private void doHandshake() {
        if (mApi == null) {
            setApiStatus(API_ERROR, "服务未连接");
            if (mCallback != null) {
                mCallback.onError("服务未连接");
            }
            return;
        }
        try {
            setApiStatus(API_ACTIVE, "hi");
            Bundle b = new Bundle();
            b.putInt("sdkVersionCode", 10000);
            Bundle r = mApi.execute("hi", b);
            int code = r != null ? r.getInt("code") : -1;
            Log.d(TAG, "hi code=" + code);
            if (code != 0) {
                setApiStatus(API_ERROR, "hi code=" + code);
                if (mCallback != null) {
                    mCallback.onError("hi code=" + code);
                }
            } else {
                setApiStatus(API_SUCCESS, "已连接");
            }

            mEventListenerBinder = new EventListenerBinder(() -> {
                try {
                    fetchCurrentSong();
                } catch (Exception e) {
                    Log.e(TAG, "event fetchCurrentSong error: " + e.getMessage());
                }
            });
            mApi.registerEventListener(Arrays.asList("playSong", "playState"), mEventListenerBinder);

            fetchCurrentSong();
        } catch (Exception e) {
            // 防御：Binder 通信失败（RemoteException/SecurityException 等）绝不应导致闪退
            Log.e(TAG, "handshake error: " + e.getMessage());
            setApiStatus(API_ERROR, e.getMessage());
            if (mCallback != null) {
                mCallback.onError("handshake: " + e.getMessage());
            }
        }
    }

 /** 拉当前歌曲 JSON 并解析。 */
    public void fetchCurrentSong() {
        if (mApi == null) {
            // 绑定进行中/未完成：静默返回，不报错、不触发备用歌词。
            // 绑定完成后 onServiceConnected 会调 doHandshake()+fetchCurrentSong() 自动拉取。
            // （carmedia 刚启动时 bind 是异步的，此时若立即 onError 会 100% 误触发备用歌词）
            Log.d(TAG, "fetchCurrentSong: mApi null (binding), skip silently");
            return;
        }
        try {
            setApiStatus(API_ACTIVE, "getCurrentSong");
            Bundle r = mApi.execute("getCurrentSong", null);
            int code = r != null ? r.getInt("code") : -1;
            String data = r != null ? r.getString("data") : null;
            Log.d(TAG, "getCurrentSong code=" + code + " data=" + data);
            if (code == 0 && data != null) {
                // Bug#Q1：getCurrentSong 成功（能读到当前歌曲）→ AIDL 授权实测生效
                mAuthOk = true;
                mAuthError = false;
                setApiStatus(API_SUCCESS, "歌曲信息就绪");
                parseAndFetchLyric(data);
            } else if (code == 5 && !mRestartedForAuth) {
                // code=5 = "No permission!"：未授权。
                // 不再自动 su 重启 QQ 进程（那会拉起 root 授权弹窗）——
                // 改为回调错误，UI 显示"去授权"按钮，由用户手动点一键授权。
                mRestartedForAuth = true; // 仅标记：本进程内不重复尝试自动授权
                mAuthOk = false;
                mAuthError = true; // Bug#Q1：仅 code=5 才置"明确未授权"
                Log.w(TAG, "getCurrentSong code=5, no permission (need manual auth)");
                setApiStatus(API_ERROR, "未授权");
                if (mCallback != null && !mHasLyric) {
                    mCallback.onError("getCurrentSong code=5 未授权");
                }
            } else {
                // 瞬态失败（非授权问题或自愈后仍失败）：若当前已有歌词则不触发 fallback，
                // 仅真正拿不到歌词时报错（由 CarMediaService 判断 hasLyric/isRestarting）
                setApiStatus(API_ERROR, "getCurrentSong code=" + code);
                if (mCallback != null && !mHasLyric) {
                    mCallback.onError("getCurrentSong code=" + code);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getCurrentSong error: " + e.getMessage());
            setApiStatus(API_ERROR, e.getMessage());
        }
    }

 /** 从歌曲 JSON 手写解析 id/mid/title/artist（原版逻辑）。 */
    private void parseAndFetchLyric(String json) {
        String songNumId = null;
        String mid = null;
        String title = null;
        String artist = null;
        try {
            int idIdx = json.lastIndexOf("\"id\":\"");
            if (idIdx >= 0) {
                String s = json.substring(idIdx + 6);
                int pipe = s.indexOf('|');
                int quote = s.indexOf('"');
                int end;
                if (pipe > 0 && (quote < 0 || pipe < quote)) {
                    end = pipe;
                } else if (quote > 0) {
                    end = quote;
                } else {
                    end = -1;
                }
                songNumId = end > 0 ? s.substring(0, end) : s;
                songNumId = songNumId.replaceAll("[^0-9]", "");
            }
            int midIdx = json.lastIndexOf("\"mid\":\"");
            if (midIdx >= 0) {
                String s = json.substring(midIdx + 7);
                mid = s.split("\"")[0];
            }
            int titleIdx = json.lastIndexOf("\"title\":\"");
            if (titleIdx >= 0) {
                String s = json.substring(titleIdx + 9);
                title = s.split("\"")[0];
            }
            int singerIdx = json.indexOf("\"singer\":{");
            if (singerIdx >= 0) {
                int t = json.indexOf("\"title\":\"", singerIdx);
                if (t >= 0) {
                    String s = json.substring(t + 9);
                    artist = s.split("\"")[0];
                }
            }
            mCurrentSongTitle = title;
            mCurrentSongArtist = artist;
            Log.d(TAG, "parsed: id=" + songNumId + " mid=" + mid + " title=" + title + " artist=" + artist);
            if (songNumId != null && !songNumId.isEmpty() && !songNumId.equals(mCurrentSongId)) {
                mCurrentSongId = songNumId;
                Log.d(TAG, "calling fetchLyric for songId=" + songNumId);
                fetchLyric(Long.parseLong(songNumId));
            } else {
                Log.d(TAG, "skipping fetchLyric (songNumId null/empty/same)");
            }
        } catch (Exception e) {
            Log.e(TAG, "parse error: " + e.getMessage());
        }
    }

 /** 异步取歌词（回调结果在 Binder 线程）。 */
    private void fetchLyric(long songId) {
        if (mApi == null) {
            setApiStatus(API_ERROR, "服务未连接");
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putLong("songId", songId);
            Log.d(TAG, "calling executeAsync getLyric...");
            setApiStatus(API_ACTIVE, "getLyric");
            mCallbackBinder = new CallbackBinder(mCallback,
                    () -> mCurrentSongTitle,
                    () -> mCurrentSongArtist,
                    lines -> {
                        mParsedLrc = lines;
                        mLastFetchTime = System.currentTimeMillis();
                    },
                    lrc -> {
                        mCurrentLrc = lrc;
                        // 有歌词 = 拿到过非空原始文本（含 QQ 纯音乐占位文本，如
                        // “此歌曲为没有填词的纯音乐”，这是正常内容要原样显示）
                        mHasLyric = (lrc != null && !lrc.isEmpty());
                    },
                    status -> {
                        if (status == API_IDLE) {
                            setApiStatus(API_IDLE, "");
                        } else if (status == API_SUCCESS) {
                            setApiStatus(API_SUCCESS, "歌词就绪");
                        } else {
                            setApiStatus(API_ERROR, "getLyric 失败");
                        }
                    });
            mApi.executeAsync("getLyric", b, mCallbackBinder);
        } catch (Exception e) {
            Log.e(TAG, "fetchLyric error: " + e.getMessage());
        }
    }

 /** 当前歌词行（按播放进度）。 */
    public String getCurrentLyricLine(long positionMs) {
        if (mParsedLrc != null && !mParsedLrc.isEmpty()) {
            return LrcUtil.getCurrentLine(mParsedLrc, positionMs);
        }
        return "";
    }

 /** 下一句歌词行（当前进度之后的第一个非空行）。 */
    public String getNextLyricLine(long positionMs) {
        if (mParsedLrc != null && !mParsedLrc.isEmpty()) {
            return LrcUtil.getNextLine(mParsedLrc, positionMs);
        }
        return "";
    }

 /** 全部非空歌词行（按时间顺序，供晴空主题右侧完整歌词列表）。 */
    public java.util.List<String> getAllLyricLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (mParsedLrc == null) return out;
        for (LrcUtil.Line l : mParsedLrc) {
            if (l.text != null && !l.text.trim().isEmpty()) out.add(l.text.trim());
        }
        return out;
    }

 /** 当前歌词行在「全部非空行」中的索引。 */
    public int getCurrentLyricIndex(long positionMs) {
        if (mParsedLrc == null || mParsedLrc.isEmpty()) return -1;
        int idx = -1;
        for (LrcUtil.Line l : mParsedLrc) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            if (l.timeMs > positionMs) break;
            idx++;
        }
        return idx;
    }

    public String getSongTitle() {
        return mCurrentSongTitle;
    }

    public String getSongArtist() {
        return mCurrentSongArtist;
    }

    public boolean isReady() {
        return mApi != null && mParsedLrc != null;
    }

 /** 是否已拿到当前歌的歌词（非空原始 LRC；含 QQ 纯音乐占位文本——那也是正常内容）。 */
    public boolean hasLyric() {
        return mHasLyric;
    }

 /** code=5 自愈重启是否进行中（期间不应触发备用歌词 fallback）。 */
    public boolean isRestarting() {
        return mRestarting;
    }

 /** root 写入 QQ 音乐第三方授权文件（appid=161，原版逻辑）。
 * 仅在未成功过且 QQ 音乐已安装时执行；waitFor 加 3 秒超时防卡死。 */
    private void writeAuthViaSu() {
        if (mAuthWritten) {
            return;
        }
        Log.d(TAG, ">>> writeAuthViaSu: writing authorization");
        Process p = null;
        try {
            long now = System.currentTimeMillis();
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("grep -q com.ecarx.carmedia /data/data/com.tencent.qqmusiccar/app_aidl/PackageFile 2>/dev/null || echo 'com.ecarx.carmedia' >> /data/data/com.tencent.qqmusiccar/app_aidl/PackageFile\n");
            os.writeBytes("cat > /data/data/com.tencent.qqmusiccar/shared_prefs/com.ecarx.carmedia.xml << 'EOF'\n");
            os.writeBytes("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
            os.writeBytes("<string name='appid'>161</string>\n");
            os.writeBytes("<long name='authtime' value='" + now + "'/>\n");
            os.writeBytes("<long name='activetime' value='" + now + "'/>\n");
            os.writeBytes("<long name='userauthtime' value='" + now + "'/>\n");
            os.writeBytes("</map>\nEOF\n");
            os.writeBytes("chown $(stat -c '%u:%g' /data/data/com.tencent.qqmusiccar) /data/data/com.tencent.qqmusiccar/shared_prefs/com.ecarx.carmedia.xml\n");
            os.writeBytes("chmod 660 /data/data/com.tencent.qqmusiccar/shared_prefs/com.ecarx.carmedia.xml\nexit\n");
            os.flush();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                Log.w(TAG, ">>> writeAuthViaSu: su timeout, killing");
                try { p.destroy(); } catch (Throwable ignored) { }
            } else {
                int exit = p.exitValue();
                Log.d(TAG, ">>> writeAuthViaSu: exit=" + exit);
                if (exit == 0) {
                    mAuthWritten = true;
                    mAuthChecked = false; // 保留：示意授权文件已更新（无实际 su 探测）
                    // 注意：文件写入成功 ≠ AIDL 生效（QQ 进程缓存可能未重建）。
                    // 是否真授权以 AIDL 实测（getCurrentSong code=0）为准，见 getAuthStatus。
                }
            }
        } catch (Exception e) {
            Log.d(TAG, ">>> writeAuthViaSu FAIL: " + e.getMessage());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

 /** QQ 音乐是否已安装（未安装则完全跳过 su/绑定）。 */
    public boolean isQqMusicInstalled() {
        try {
            mContext.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

 /** 授权状态码：0=未安装 1=已授权 2=未授权(可一键授权) 3=未授权(需登录) 4=未授权(绑定失败)。
 * 完全不执行 su：授权状态【只由 AIDL 实测决定】，与歌词/API 调用状态【完全解耦】。
 * Bug#Q1（反编译 v1.3.4 对比确认）：v1.3.4 的 getAuthStatus 逻辑与当前
 * 一致，用户当时没遇到问题是因为当时 AIDL 授权真实生效；现在很可能本来就未授权
 * （放歌 code=5），旧"默认已授权"兜底把它掩盖成已授权。现改为：
 * - mAuthOk（getCurrentSong code=0，能读当前歌/歌词）→ 已授权
 * - mAuthError（getCurrentSong code=5，No permission）→ 未授权
 * - 未探测到 AIDL 结果（未绑定/未返回）→ 诚实显示未授权（2），不再默认已授权
 * - 不再用 mAuthWritten/mAuthFileOk（root 写入≠AIDL 生效）当已授权判据 */
    public int getAuthStatus() {
        if (!isQqMusicInstalled()) {
            return 0;
        }
        if (mAuthOk) {
            // AIDL 实测 getCurrentSong code=0（能读到当前歌曲/歌词）→ 授权确实生效
            return 1;
        }
        if (mAuthError) {
            // AIDL 实测 getCurrentSong code=5（No permission）→ 明确未授权
            return 2;
        }
        // 尚未探测到 AIDL 结果（未绑定/未返回）：不默认"已授权"，
        // 诚实显示未授权（可一键授权），避免把从未授权误报成已授权。
        return 2;
    }

 /** 强制 root 写授权（授权按钮 root 场景直写，立刻生效）。
 * 仅由用户手动点"一键授权"触发（authorizeQqMusic），不会在后台自动调用。 */
    public void forceAuthWrite() {
        mAuthWritten = false;
        writeAuthViaSu();
    }

 /** 一次性 root 探测回调（手动授权流程用）。 */
    public interface RootProbeCallback {
        void onRootProbeResult(boolean hasRoot);
    }

 /** 探测是否有 root（su 可执行，5s 超时）。
 * 仅在用户手动点"一键授权"时调用——这是唯一允许拉起 su 弹窗的场景。
 * 后台线程执行，结果经回调返回。 */
    public void probeRoot(final RootProbeCallback cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process p = null;
                boolean ok = false;
                try {
                    p = Runtime.getRuntime().exec("su -c id");
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        Log.d(TAG, ">>> probeRoot: su timeout, assume no root");
                    } else {
                        String line = br.readLine();
                        ok = line != null && line.contains("uid=0");
                        Log.d(TAG, ">>> probeRoot: hasRoot=" + ok);
                    }
                } catch (Exception e) {
                    Log.d(TAG, ">>> probeRoot FAIL: " + e.getMessage());
                } finally {
                    if (p != null) {
                        try { p.destroy(); } catch (Throwable ignored) { }
                    }
                }
                final boolean result = ok;
                if (cb != null) {
                    cb.onRootProbeResult(result);
                }
            }
        }, "qq-probe-root");
        t.start();
    }

 /** 重置授权状态缓存：下次 getAuthStatus() 重新评估（不执行 su）。
 * 状态变化点（授权按钮点击/授权写入/QQ 重启）后调用，强制重新判定。 */
    public void refreshAuthState() {
        mAuthChecked = false;
    }

}

