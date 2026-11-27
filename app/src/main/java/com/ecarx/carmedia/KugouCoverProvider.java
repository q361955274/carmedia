package com.ecarx.carmedia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * 酷狗车机版(com.kugou.android.auto)封面 AIDL 客户端。
 *
 * 背景 :酷狗 MediaSession metadata 在 albumImg 为空时
 * 不写任何 ART 字段(dumpsys 实测无 ART、通知 largeIcon=null),系统层面拿不到封面;
 * 但酷狗车机版对外暴露了 exported=true、无权限要求的 AutoSdkAIDLRemoteService
 * (action=android.intent.action.AutoSdkAIDLRemoteService),AIDL 接口
 * IKgAutoInterface.executeAction("PLAYER_GET_PLAYING_MUSIC", bundle) 返回 Bundle,
 * 其 EXECUTE_ACTION_RESULT key = KgMusicResult(Parcelable 包装类),
 * KgMusicResult.getData() = KgMusic,字段 albumImagePath = 封面 URL
 * (correctImgUrl 已把 {size} 占位替换为 240,反编译确认)。
 *
 * 本类以裸 Parcel transact 调用该接口(与 KuwoAidlClient 同款,避开编译期 AIDL 依赖):
 * DESCRIPTOR = com.kugou.auto.proxy.IKgAutoInterface
 * TRANSACTION_executeAction = 1
 * data: writeInterfaceToken → writeString(action) → writeInt(0)(params=null)
 * reply: readException → readInt(1=有结果) → Bundle
 *
 * KG_MUSIC 是酷狗进程的 Parcelable,CarMedia 进程没有该类,直接 getParcelable 会因
 * ClassNotFoundException 返回 null → 用 DexClassLoader 加载酷狗 base.apk 后
 * result.setClassLoader(dexLoader) 再读,再反射 getData()/albumImagePath。
 *
 * 防抖语义与 CoverFallback 一致:同曲成功缓存 / 失败 60s 冷却 / 单飞防重入。
 * 酷狗未安装/未运行/bind 失败/返回空 → 优雅降级(request 返回 false),
 * 由调用方(CarMediaService)回退 CoverFallback 在线搜图。
 */
public class KugouCoverProvider {

    private static final String TAG = "KugouCover";

    private static final String PKG = "com.kugou.android.auto";
    private static final String SVC = "com.kugou.auto.proxy.AutoSdkAIDLRemoteService";
    private static final String DESCRIPTOR = "com.kugou.auto.proxy.IKgAutoInterface";
 /** IKgAutoInterface$Stub 反编译确认:TRANSACTION_executeAction = 1 */
    private static final int TRANSACTION_executeAction = 1;
    private static final String ACTION_GET_PLAYING_MUSIC = "PLAYER_GET_PLAYING_MUSIC";
 /**
 * 实机验证:酷狗 exported=true 无权限,但 play 类 action 有调用方白名单
 * 校验(checkCallingPid,errorCode=100 "check appId/appKey fail")。白名单
 * mCheckedCallingPkgNameSet 初始为空,唯一填充点:handleCheckAction 校验 appId/appKey
 * 通过后把调用方包名 add 进去("add checked calling package success")。
 * 因此必须先调 APP_CHECK(带合法 APP_ID/APP_KEY)让酷狗把我们加入白名单,
 * 之后 PLAYER_GET_PLAYING_MUSIC 才能通过 checkCallingPid。
 * 合法凭据:appId=md5("kgtest"),appKey=SecretKeyUtil.buildAppKey(appId)(反射酷狗
 * 自己的 SecretKeyUtil 生成,与 buildAppKey 同源,无需自己实现 AES/MD5)。
 */
    private static final String ACTION_APP_CHECK = "APP_CHECK";
    private static final String KEY_APP_ID = "APP_ID";
    private static final String KEY_APP_KEY = "APP_KEY";
    private static final String KEY_SDK_VERSION = "android.intent.action.KgAutoSdk.VersionCode";
    private static final int SDK_VERSION = 27; // <28 服务端只走 check(V1):appKey==buildAppKey(appId),避开 checkV2 的还原歧义
 /**
 * executeAction 返回 Bundle 的 key = "EXECUTE_ACTION_RESULT"(反编译 ResultUtil
 * .successKgMusicResult 确认),值是 KgMusicResult(Parcelable 包装类);
 * KgMusicResult.getData() 返回 KgMusic。注意 Key.KG_MUSIC="KG_MUSIC" 是
 * addEventListener 事件回调用的 key,executeAction 返回包里没有。
 */
    private static final String KEY_EXECUTE_ACTION_RESULT = "EXECUTE_ACTION_RESULT";
 /** KgMusicResult.getData() 方法名(public,反编译确认) */
    private static final String METHOD_GET_DATA = "getData";
 /** KgMusic.albumImagePath 字段(public final String,反编译确认) */
    private static final String FIELD_ALBUM_IMAGE_PATH = "albumImagePath";

    private static final long FAIL_COOLDOWN_MS = 60000L;
    private static final long REBIND_RETRY_MS = 3000L;
 /** bind 未完成时等待的最长时间(ms):酷狗进程刚被拉起时 bind 有延迟。 */
    private static final long BIND_WAIT_MS = 2000L;

 /** 封面 URL 解析成功回调(可能主线程,也可能 daemon 线程;调用方自行保证线程安全)。 */
    public interface CoverCallback {
        void onResolved(String url);
    }

    // 静态防抖状态(与 CoverFallback 一致:同曲缓存 / 失败冷却 / 单飞)
    private static volatile String sCached;
    private static volatile long sFailUntil;
    private static volatile boolean sInFlight;
    private static volatile String sKey;
 /** 是否已通过 APP_CHECK 把调用方包名加入酷狗白名单(进程内一次即可)。 */
    private static volatile boolean sChecked;

    private final Context mContext;
    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: " + name);
            mRemote = service;
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            mRemote = null;
            mBound = false;
            // 酷狗进程被杀/重启后自动重绑
            scheduleRebind();
        }
    };

    private volatile IBinder mRemote;
    private volatile boolean mBound;
    private volatile boolean mStopped;
    private volatile ClassLoader mDexLoader;

    public KugouCoverProvider(Context context) {
        this.mContext = context.getApplicationContext();
    }

 /** 启动:绑定酷狗 AIDL 服务(酷狗未安装/未运行则跳过,不影响其他功能)。 */
    public synchronized void start() {
        mStopped = false;
        if (mBound || mRemote != null) {
            return;
        }
        if (!isInstalled()) {
            Log.d(TAG, "start: kugou not installed, skip");
            return;
        }
        bindTo();
    }

 /** 停止:解绑并清空状态(可再次 start)。 */
    public synchronized void stop() {
        mStopped = true;
        if (mBound) {
            try {
                mContext.unbindService(mConn);
            } catch (Exception ignored) {
            }
        }
        mBound = false;
        mRemote = null;
    }

    private boolean isInstalled() {
        try {
            mContext.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void bindTo() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PKG, SVC));
            boolean ok = mContext.bindService(intent, mConn, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService(" + PKG + "/" + SVC + ")=" + ok);
            mBound = ok;
            if (!ok) {
                scheduleRebind();
            }
        } catch (Exception e) {
            Log.w(TAG, "bind error: " + e.getMessage());
            scheduleRebind();
        }
    }

    private void scheduleRebind() {
        if (mStopped) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(REBIND_RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (mStopped || mBound || mRemote != null || !isInstalled()) {
                    return;
                }
                bindTo();
            }
        });
        t.setName("kugou-cover-rebind");
        t.setDaemon(true);
        t.start();
    }

 /** 缓存命中查询(key=「歌名|歌手」小写;同曲且已解析成功时直接复用)。 */
    public static String getCachedUrl(String title, String artist) {
        String key = makeKey(title, artist);
        if (key != null && key.equals(sKey) && sCached != null) {
            return sCached;
        }
        return null;
    }

 /**
 * 发起酷狗封面请求(异步 daemon 线程,不阻塞主线程)。
 * 返回 true=请求被接受(结果经回调送达);false=被防抖拒绝(冷却中/单飞中/参数非法),
 * 此时调用方可回退 CoverFallback 在线搜图。
 */
    public boolean request(final String title, final String artist, final CoverCallback cb) {
        final String key = makeKey(title, artist);
        if (key == null) {
            return false;
        }
        if (key.equals(sKey)) {
            if (sCached != null) {
                if (cb != null) {
                    cb.onResolved(sCached);
                }
                return true;
            }
            if (sInFlight) {
                return false;
            }
        }
        if (SystemClock.elapsedRealtime() < sFailUntil) {
            return false;
        }
        sKey = key;
        sInFlight = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = fetchCoverUrlSync();
                    if (url != null && !url.isEmpty()) {
                        sCached = url;
                        Log.i(TAG, "cover resolved -> " + url);
                        if (cb != null) {
                            cb.onResolved(url);
                        }
                    } else {
                        Log.d(TAG, "no cover url for '" + key + "'");
                        sFailUntil = SystemClock.elapsedRealtime() + FAIL_COOLDOWN_MS;
                    }
                } catch (Throwable t1) {
                    Log.w(TAG, "request error: " + t1.getMessage());
                } finally {
                    sInFlight = false;
                }
            }
        });
        t.setName("kugou-cover");
        t.setDaemon(true);
        t.start();
        return true;
    }

 /** 同步执行 AIDL 调用,返回封面 URL;任何失败返回 null(优雅降级)。 */
    private String fetchCoverUrlSync() throws Exception {
        IBinder remote = mRemote;
        if (remote == null) {
            // 酷狗进程刚被拉起/刚 start() 时 bind 尚未完成,短等;
            // 等不到就放弃本次(下次 metadata 变化/1.5s 重试会再走)。
            long deadline = SystemClock.elapsedRealtime() + BIND_WAIT_MS;
            while (mRemote == null && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            remote = mRemote;
        }
        if (remote == null) {
            Log.d(TAG, "fetchCoverUrlSync: not bound");
            return null;
        }
        // 首次调用前先 APP_CHECK 把调用方包名加入酷狗白名单,
        // 否则 PLAYER_GET_PLAYING_MUSIC 被 checkCallingPid 拒绝(errorCode=100)。
        if (!sChecked) {
            boolean ok = doAppCheck(remote);
            if (!ok) {
                Log.d(TAG, "fetchCoverUrlSync: APP_CHECK failed, skip this round");
                return null;
            }
            sChecked = true;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(ACTION_GET_PLAYING_MUSIC);
            data.writeInt(0); // params Bundle = null
            remote.transact(TRANSACTION_executeAction, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 1) {
                return null;
            }
            Bundle result = reply.readBundle(null);
            if (result == null) {
                return null;
            }
            result.setClassLoader(getDexLoader());
            // EXECUTE_ACTION_RESULT -> KgMusicResult -> getData() -> KgMusic
            Object resultObj = result.getParcelable(KEY_EXECUTE_ACTION_RESULT);
            if (resultObj == null) {
                Log.d(TAG, "fetchCoverUrlSync: EXECUTE_ACTION_RESULT null");
                return null;
            }
            // 诊断:打印实际返回的 Result 类型与 toString(含 errorCode/errorMsg),
            // 酷狗失败时返回 VoidResult(无 getData),需要知道 errorCode 定位原因。
            Log.d(TAG, "fetchCoverUrlSync: result class=" + resultObj.getClass().getName()
                    + " toString=" + resultObj);
            // KgMusicResult.getData() 优先;失败(如返回 VoidResult)回退反射读私有 data 字段
            Object kg = null;
            try {
                java.lang.reflect.Method getData = resultObj.getClass().getMethod(METHOD_GET_DATA);
                kg = getData.invoke(resultObj);
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "getData() not on " + resultObj.getClass().getName()
                        + ", fallback field data (errorCode/errorMsg=" + dumpError(resultObj) + ")");
                try {
                    Field dataField = resultObj.getClass().getDeclaredField("data");
                    dataField.setAccessible(true);
                    kg = dataField.get(resultObj);
                } catch (Throwable t2) {
                    Log.w(TAG, "fallback data field failed: " + t2.getMessage());
                }
            }
            if (kg == null) {
                Log.d(TAG, "fetchCoverUrlSync: KgMusicResult.getData() null");
                return null;
            }
            Field f = kg.getClass().getField(FIELD_ALBUM_IMAGE_PATH);
            Object v = f.get(kg);
            return (v instanceof String) ? ((String) v).trim() : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

 /**
 * APP_CHECK:生成合法 appId(appId=md5("kgtest"))与 appKey(buildAppKey 算法),
 * 调 executeAction 让酷狗把调用方包名加入 mCheckedCallingPkgNameSet 白名单
 * (handleCheckAction 校验通过后 add,"add checked calling package success")。
 * 成功后 play 类 action 才不被拒。
 *
 * 演进:反射整类 SecretKeyUtil 会触发其依赖链(KGCommonApp/devkit.a)
 * 抛 NoClassDefFoundError。改为按 buildAppKey 算法等价实现:
 * appId = md5("kgtest")
 * SDK_KEY = j._y(0x6b737561, "null") ← 反射酷狗 native(arm64 转译进程可加载 libj.so)
 * appKey = md5(encrypt(appId, SDK_KEY, false)) ← AES/CBC/PKCS5Padding,纯 Java
 */
    private boolean doAppCheck(IBinder remote) {
        try {
            // 服务端 check/checkV2 都拒绝 appId==md5("kgtest")(返回 false),必须用非 kgtest 的正式 appId。
            // 任意 appId 只要满足 appKey==buildAppKey(appId) 即通过(version<28 走 check 精确比较)。
            String appId = md5hex("doubao-car"); // f1625b7603ce20e75da015fd9c7d6ccb,非 kgtest
            // SDK_KEY 两个候选:kgautosdkkey(native 期望值) / null(j.y 异常回退)。先试 kgautosdkkey。
            String sdkKey = "kgautosdkkey";
            String base = md5hex(encryptIso8859_1(appId, sdkKey)); // V1 = buildAppKey(appId)
            // version<28 走 check(V1):appKey == buildAppKey(appId) 精确相等,直接传 V1 base。
            String appKey = base;
            Log.d(TAG, "APP_CHECK appKeyV2=" + appKey + " (base=" + base + ")");
            if (appId == null || appKey == null || appKey.isEmpty()) {
                Log.w(TAG, "APP_CHECK: gen appId/appKey failed");
                return false;
            }
            Bundle params = new Bundle();
            params.putString(KEY_APP_ID, appId);
            params.putString(KEY_APP_KEY, appKey);
            params.putInt(KEY_SDK_VERSION, SDK_VERSION);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(ACTION_APP_CHECK);
                data.writeInt(1); // params Bundle 非空
                params.writeToParcel(data, 0);
                remote.transact(TRANSACTION_executeAction, data, reply, 0);
                reply.readException();
                // 读服务端返回 Bundle 的 EXECUTE_SUCCESS 判定白名单是否真加入
                Bundle rb = reply.readInt() > 0 ? reply.readBundle(getClass().getClassLoader()) : null;
                if (rb != null) {
                    boolean ok = rb.getBoolean("EXECUTE_SUCCESS", false);
                    int ec = rb.getInt("EXECUTE_ERROR_CODE", -1);
                    String em = rb.getString("EXECUTE_ERROR_MSG");
                    Log.i(TAG, "APP_CHECK reply: EXECUTE_SUCCESS=" + ok + " code=" + ec + " msg=" + em);
                    return ok;
                }
                Log.w(TAG, "APP_CHECK reply bundle null");
                return true;
            } finally {
                reply.recycle();
                data.recycle();
            }
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            Log.w(TAG, "APP_CHECK error: " + cause + " / msg=" + cause.getMessage());
            return false;
        }
    }

 /** 酷狗同款 buildAppKeyV2:base 中按 yyMMdd 的 6 位在位置 1,3,5,7,9,11 插入日期字符,末尾倒数第 3 位插校验位。 */
    private static String buildAppKeyV2(String base, String date) {
        StringBuilder sb = new StringBuilder(base);
        int sum = 13;
        for (int i = 0; i < 6; i++) {
            char ch = date.charAt(i);
            sum += ch;
            sb.insert(i * 2 + 1, ch);
        }
        sum *= 7;
        sb.insert(sb.length() - 3, sum % 10);
        return sb.toString();
    }

 /** 酷狗同款 md5:BigInteger(1,digest).toString(16)(可能丢前导 0,与服务端算法一致)。 */
    private static String md5hex(String src) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(src.getBytes("UTF-8"));
        String s = new java.math.BigInteger(1, d).toString(16);
        while (s.length() < 32) s = "0" + s;  // 服务端 md5 补零到 32 位
        return s;
    }

 /** 酷狗同款 encrypt:key/iv = md5(key) 前/后 16 字节 hex;AES/CBC/PKCS5;输入输出 ISO8859-1。 */
    private static String encryptIso8859_1(String src, String key) throws Exception {
        String md5key = md5hex(key);
        javax.crypto.spec.SecretKeySpec sk = new javax.crypto.spec.SecretKeySpec(
                md5key.substring(0, 16).getBytes("ISO8859-1"), "AES");
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, sk,
                new javax.crypto.spec.IvParameterSpec(md5key.substring(16, 32).getBytes("ISO8859-1")));
        byte[] enc = c.doFinal(src.getBytes("ISO8859-1"));
        return new String(enc, "ISO8859-1");
    }

 /**
 * 懒加载酷狗 APK 的 ClassLoader:KG_MUSIC 的 Parcelable 需要酷狗类才能反序列化。
 *
 * 实测演进:
 * 1) 磁盘型 DexClassLoader(48MB APK 提取+oat 优化)→ 模拟器卡死 30s+(无后续日志);
 * 2) InMemoryDexClassLoader(4 dex 内存加载,秒级成功)→ 但 SecretKeyUtil 依赖链
 * 抛 NoClassDefFoundError: com.kugou.common.devkit.a(多 dex 交叉引用+验证问题);
 * 3) PathClassLoader:与酷狗进程同款加载方式(APK 全部 dex + oat 缓存),正确处理
 * 多 dex 依赖 → 优先使用;失败回退 InMemory。
 */
    private ClassLoader getDexLoader() {
        ClassLoader l = mDexLoader;
        if (l != null) {
            return l;
        }
        synchronized (this) {
            l = mDexLoader;
            if (l == null) {
                try {
                    ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(PKG, 0);
                    l = new dalvik.system.PathClassLoader(ai.sourceDir, mContext.getClassLoader());
                    Log.d(TAG, "path dex loader ready: " + ai.sourceDir);
                } catch (Throwable t1) {
                    Log.w(TAG, "path dex loader error: " + t1.getMessage() + ", fallback in-memory");
                    try {
                        l = buildInMemoryDexLoader();
                    } catch (Throwable t2) {
                        Log.w(TAG, "in-memory dex loader error: " + t2.getMessage());
                        l = mContext.getClassLoader();
                    }
                }
                mDexLoader = l;
            }
            return l;
        }
    }

 /** 读 APK 内全部 classes*.dex 到内存,InMemoryDexClassLoader(API 27+,反射构造)。 */
    private ClassLoader buildInMemoryDexLoader() throws Exception {
        ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(PKG, 0);
        java.util.List<java.nio.ByteBuffer> bufs = new java.util.ArrayList<>();
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(ai.sourceDir);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().matches("classes\\d*\\.dex")) {
                    continue;
                }
                java.io.InputStream is = zf.getInputStream(e);
                try {
                    byte[] b = new byte[(int) e.getSize()];
                    int off = 0;
                    while (off < b.length) {
                        int n = is.read(b, off, b.length - off);
                        if (n < 0) {
                            break;
                        }
                        off += n;
                    }
                    bufs.add(java.nio.ByteBuffer.wrap(b));
                } finally {
                    is.close();
                }
            }
        } finally {
            zf.close();
        }
        if (bufs.isEmpty()) {
            throw new IllegalStateException("no dex in " + ai.sourceDir);
        }
        Class<?> imdc = Class.forName("dalvik.system.InMemoryDexClassLoader");
        java.lang.reflect.Constructor<?> ctor =
                imdc.getConstructor(java.nio.ByteBuffer[].class, ClassLoader.class);
        return (ClassLoader) ctor.newInstance(
                bufs.toArray(new java.nio.ByteBuffer[0]), mContext.getClassLoader());
    }

    private static String makeKey(String title, String artist) {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        return (title.trim() + "|" + (artist == null ? "" : artist.trim())).toLowerCase();
    }

 /** 诊断:沿类层级反射读 errorCode/errorMsg(酷狗 Result 基类字段,失败结果定位用)。 */
    private static String dumpError(Object obj) {
        try {
            StringBuilder sb = new StringBuilder();
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("errorCode");
                    f.setAccessible(true);
                    sb.append("errorCode=").append(f.get(obj)).append(' ');
                } catch (NoSuchFieldException ignored) {
                }
                try {
                    Field f = c.getDeclaredField("errorMsg");
                    f.setAccessible(true);
                    sb.append("errorMsg=").append(f.get(obj)).append(' ');
                } catch (NoSuchFieldException ignored) {
                }
                c = c.getSuperclass();
            }
            return sb.length() == 0 ? "no error fields" : sb.toString().trim();
        } catch (Throwable t) {
            return "dumpError failed: " + t.getMessage();
        }
    }
}
