package com.ecarx.carmedia;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 方向盘按键 CarPropertyManager 监听器（方案H）。
 *
 * <p><b>原理</b>：通过 Android Automotive 标准 API CarPropertyManager 订阅
 * VehicleProperty.HW_KEY_INPUT (0x287000fb) 属性。方向盘按键按下/抬起时，
 * Vehicle HAL 直接推送属性变化事件，包含完整的 action(0=DOWN/1=UP)、
 * keyCode、display 三个 int 值。</p>
 *
 * <p><b>事件格式</b>（从 ROM 反编译 InputHalService.smali 确认）：
 * <pre>
 * int32Values[0] = action (0=ACTION_DOWN, 1=ACTION_UP)
 * int32Values[1] = keyCode (300=静音, 304=上一曲, 305=下一曲, 348=模式, 306=OK)
 * int32Values[2] = display (0=主屏, 1=仪表)
 * </pre></p>
 *
 * <p><b>降级机制</b>：若 CarService 不存在、连接超时、订阅失败，自动回调
 * {@link StatusListener#onFallbackToLogcat()}，由调用方启动日志通道兜底。</p>
 */
public class CarPropertyKeyMonitor {

    private static final String TAG = "CarMedia";

 /** VehicleProperty.HW_KEY_INPUT 属性 ID（从 ROM 反编译确认） */
    private static final int HW_KEY_INPUT = 0x287000fb;

 /** 连接超时时间（毫秒），超时后触发降级到日志通道 */
    private static final long CONNECT_TIMEOUT_MS = 5000L;

    public interface Listener {
 /** 按键按下（action=0） */
        void onKeyDown(int keyCode);
 /** 按键抬起（action=1） */
        void onKeyUp(int keyCode);
    }

 /** 状态监听：连接成功/失败降级 */
    public interface StatusListener {
 /** CarPropertyManager 连接成功并订阅完成 */
        void onConnected();
 /** 连接失败/超时/订阅失败，调用方应启动日志通道兜底 */
        void onFallbackToLogcat(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final StatusListener statusListener;
    private final Handler handler = new Handler(Looper.getMainLooper());

 /** android.car.Car 实例 */
    private Object car;
 /** android.car.CarPropertyManager 实例 */
    private Object carPropertyManager;
 /** CarPropertyEventCallback 动态代理 */
    private Object propertyCallback;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean fallbackTriggered;

 /** 连接超时计时器 */
    private final Runnable connectTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected && running && !fallbackTriggered) {
                Log.w(TAG, "CarPropertyKeyMonitor: connect timeout (" + CONNECT_TIMEOUT_MS + "ms), fallback to logcat");
                triggerFallback("connect timeout");
            }
        }
    };

    public CarPropertyKeyMonitor(Context context, Listener listener, StatusListener statusListener) {
        this.context = context;
        this.listener = listener;
        this.statusListener = statusListener;
    }

 /** 启动监听（幂等）。异步连接 CarService，连接完成后自动注册属性回调。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        fallbackTriggered = false;
        connected = false;
        try {
            // 启动连接超时计时器
            handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);

            // 尝试连接 CarService
            boolean connected = connectCar();
            if (!connected) {
                Log.w(TAG, "CarPropertyKeyMonitor: connectCar returned false, fallback to logcat");
                triggerFallback("connectCar returned false");
                return;
            }
            Log.i(TAG, "CarPropertyKeyMonitor: CarService connect initiated, waiting for onServiceConnected...");
        } catch (Throwable t) {
            Log.e(TAG, "CarPropertyKeyMonitor start failed: " + t.getMessage(), t);
            triggerFallback("start exception: " + t.getMessage());
        }
    }

 /**
 * 尝试连接 CarService。尝试多种 API 签名以兼容不同 Android 版本。
 * @return true 表示连接已发起（异步等待 onServiceConnected），false 表示 Car API 不存在
 */
    private boolean connectCar() {
        try {
            Class<?> carClass = Class.forName("android.car.Car");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "CarPropertyKeyMonitor: android.car.Car not found on this device");
            return false;
        }

        // 动态代理 CarServiceConnectionListener
        Object connectionListener = createConnectionListener();
        if (connectionListener == null) {
            return false;
        }

        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Handler h = new Handler(Looper.getMainLooper());

            // 尝试签名1: createCar(Context, CarServiceConnectionListener, Handler)
            try {
                Class<?> listenerClass = Class.forName("android.car.Car$CarServiceConnectionListener");
                Method m = carClass.getMethod("createCar", Context.class, listenerClass, Handler.class);
                car = m.invoke(null, context, connectionListener, h);
                Log.i(TAG, "CarPropertyKeyMonitor: createCar (sig1: CarServiceConnectionListener)");
                return true;
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "CarPropertyKeyMonitor: sig1 not found, try sig2");
            }

            // 尝试签名2: createCar(Context, ServiceConnectionListener, Handler)
            try {
                Class<?> listenerClass = Class.forName("android.car.Car$ServiceConnectionListener");
                Method m = carClass.getMethod("createCar", Context.class, listenerClass, Handler.class);
                car = m.invoke(null, context, connectionListener, h);
                Log.i(TAG, "CarPropertyKeyMonitor: createCar (sig2: ServiceConnectionListener)");
                return true;
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "CarPropertyKeyMonitor: sig2 not found, try sig3");
            }

            // 尝试签名3: createCar(Context) + connect()
            try {
                Method m = carClass.getMethod("createCar", Context.class);
                car = m.invoke(null, context);
                // 尝试调用 connect()
                try {
                    carClass.getMethod("connect").invoke(car);
                    Log.i(TAG, "CarPropertyKeyMonitor: createCar(Context) + connect()");
                    // 签名3没有连接回调，延迟检查连接状态
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (running && !connected) {
                                // 尝试直接注册回调，如果成功则认为已连接
                                if (registerPropertyCallback()) {
                                    connected = true;
                                    notifyConnected();
                                } else {
                                    triggerFallback("sig3 connect failed");
                                }
                            }
                        }
                    }, 2000);
                    return true;
                } catch (Exception e2) {
                    Log.w(TAG, "CarPropertyKeyMonitor: connect() failed: " + e2.getMessage());
                }
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "CarPropertyKeyMonitor: sig3 not found");
            }

            Log.w(TAG, "CarPropertyKeyMonitor: no compatible createCar signature found");
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "CarPropertyKeyMonitor: connectCar exception: " + t.getMessage(), t);
            return false;
        }
    }

 /** 创建 CarServiceConnectionListener 动态代理（兼容多种接口名） */
    private Object createConnectionListener() {
        String[] possibleInterfaces = {
            "android.car.Car$CarServiceConnectionListener",
            "android.car.Car$ServiceConnectionListener"
        };
        for (String ifaceName : possibleInterfaces) {
            try {
                final Class<?> iface = Class.forName(ifaceName);
                Object proxy = Proxy.newProxyInstance(
                        iface.getClassLoader(),
                        new Class[]{iface},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                String name = method.getName();
                                if ("onServiceConnected".equals(name)) {
                                    Log.i(TAG, "CarPropertyKeyMonitor: CarService connected (iface=" + iface.getSimpleName() + ")");
                                    connected = true;
                                    handler.removeCallbacks(connectTimeoutRunnable);
                                    if (registerPropertyCallback()) {
                                        notifyConnected();
                                    } else {
                                        triggerFallback("registerCallback failed");
                                    }
                                } else if ("onServiceDisconnected".equals(name)) {
                                    Log.w(TAG, "CarPropertyKeyMonitor: CarService disconnected");
                                    connected = false;
                                }
                                return null;
                            }
                        });
                return proxy;
            } catch (ClassNotFoundException e) {
                Log.d(TAG, "CarPropertyKeyMonitor: iface not found: " + ifaceName);
            }
        }
        Log.w(TAG, "CarPropertyKeyMonitor: no compatible CarServiceConnectionListener interface found");
        return null;
    }

 /** 注册 HW_KEY_INPUT 属性回调 */
    private boolean registerPropertyCallback() {
        try {
            if (car == null) {
                Log.e(TAG, "registerPropertyCallback: car is null");
                return false;
            }
            Class<?> carClass = Class.forName("android.car.Car");
            String propertyServiceName = (String) carClass.getField("PROPERTY_SERVICE").get(null);

            carPropertyManager = carClass.getMethod("getCarManager", String.class)
                    .invoke(car, propertyServiceName);

            if (carPropertyManager == null) {
                Log.e(TAG, "registerPropertyCallback: CarPropertyManager is null");
                return false;
            }

            // 创建 CarPropertyEventCallback 动态代理
            Class<?> callbackClass = Class.forName("android.car.CarPropertyManager$CarPropertyEventCallback");
            propertyCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class[]{callbackClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onChangeEvent".equals(name) && args != null && args.length > 0) {
                                handlePropertyChange(args[0]);
                            } else if ("onErrorEvent".equals(name)) {
                                int propId = (args != null && args.length > 0) ? (Integer) args[0] : -1;
                                Log.w(TAG, "CarPropertyKeyMonitor: onErrorEvent, propertyId=" + propId);
                            }
                            return null;
                        }
                    });

            // 尝试多种 registerCallback 签名
            try {
                // 签名1: registerCallback(CarPropertyEventCallback, int, float)
                carPropertyManager.getClass()
                        .getMethod("registerCallback", callbackClass, int.class, float.class)
                        .invoke(carPropertyManager, propertyCallback, HW_KEY_INPUT, 0f);
                Log.i(TAG, "CarPropertyKeyMonitor: registered HW_KEY_INPUT (sig1: rate=0)");
                return true;
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "CarPropertyKeyMonitor: registerCallback sig1 not found, try sig2");
            }

            try {
                // 签名2: registerCallback(CarPropertyEventCallback, int)
                carPropertyManager.getClass()
                        .getMethod("registerCallback", callbackClass, int.class)
                        .invoke(carPropertyManager, propertyCallback, HW_KEY_INPUT);
                Log.i(TAG, "CarPropertyKeyMonitor: registered HW_KEY_INPUT (sig2: no rate)");
                return true;
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "CarPropertyKeyMonitor: no compatible registerCallback signature found");
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerPropertyCallback failed: " + t.getMessage(), t);
            return false;
        }
    }

 /** 解析 CarPropertyValue，提取 action/keyCode 并回调 */
    private void handlePropertyChange(Object carPropertyValue) {
        try {
            if (carPropertyValue == null) {
                return;
            }
            Object value = carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue);
            if (!(value instanceof int[])) {
                Log.w(TAG, "CarPropertyKeyMonitor: unexpected value type: "
                        + (value != null ? value.getClass().getName() : "null"));
                return;
            }
            int[] arr = (int[]) value;
            if (arr.length < 2) {
                Log.w(TAG, "CarPropertyKeyMonitor: int[] length < 2, length=" + arr.length);
                return;
            }
            int action = arr[0];
            int keyCode = arr[1];
            int display = arr.length >= 3 ? arr[2] : 0;

            if (action == 0) {
                Log.i(TAG, "CarProperty key DOWN: code=" + keyCode + " display=" + display);
                if (listener != null) {
                    try {
                        listener.onKeyDown(keyCode);
                    } catch (Throwable t) {
                        Log.w(TAG, "onKeyDown callback error: " + t.getMessage());
                    }
                }
            } else {
                Log.i(TAG, "CarProperty key UP: code=" + keyCode + " display=" + display);
                if (listener != null) {
                    try {
                        listener.onKeyUp(keyCode);
                    } catch (Throwable t) {
                        Log.w(TAG, "onKeyUp callback error: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "handlePropertyChange error: " + t.getMessage());
        }
    }

    private void notifyConnected() {
        if (statusListener != null) {
            try {
                statusListener.onConnected();
            } catch (Throwable t) {
                Log.w(TAG, "onConnected callback error: " + t.getMessage());
            }
        }
    }

    private void triggerFallback(String reason) {
        if (fallbackTriggered) {
            return;
        }
        fallbackTriggered = true;
        handler.removeCallbacks(connectTimeoutRunnable);
        Log.w(TAG, "CarPropertyKeyMonitor: fallback to logcat, reason=" + reason);
        if (statusListener != null) {
            try {
                statusListener.onFallbackToLogcat(reason);
            } catch (Throwable t) {
                Log.w(TAG, "onFallbackToLogcat callback error: " + t.getMessage());
            }
        }
    }

 /** 停止监听并断开 CarService（幂等） */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        connected = false;
        handler.removeCallbacks(connectTimeoutRunnable);
        try {
            if (carPropertyManager != null && propertyCallback != null) {
                Class<?> callbackClass = Class.forName("android.car.CarPropertyManager$CarPropertyEventCallback");
                try {
                    carPropertyManager.getClass()
                            .getMethod("unregisterCallback", callbackClass)
                            .invoke(carPropertyManager, propertyCallback);
                } catch (Throwable t) {
                    Log.w(TAG, "unregisterCallback error: " + t.getMessage());
                }
            }
            if (car != null) {
                try {
                    car.getClass().getMethod("disconnect").invoke(car);
                } catch (Throwable t) {
                    Log.w(TAG, "Car.disconnect error: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "CarPropertyKeyMonitor stop error: " + t.getMessage());
        } finally {
            car = null;
            carPropertyManager = null;
            propertyCallback = null;
        }
        Log.i(TAG, "CarPropertyKeyMonitor stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }
}
