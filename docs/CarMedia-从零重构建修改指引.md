# CarMedia 从零重构建修改指引

车机系统级媒体桥接 App（包名 `com.ecarx.carmedia`，桌面名称「蓝牙音乐」）。本文记录工程结构、构建方式、核心链路与关键约束，用于从零重建项目。

## 1. 架构

CarMedia 监听系统 MediaSession 与通知栏，把当前播放的歌名、歌手、歌词、进度、状态归一化后推送到 EAS，同时提供 WebView 界面、方向盘按键映射、多音源歌词抓取、USB 本地播放与在线更新。

- **推送只走 EAS 一条链路**：EAS 内部把播放信息同时转给 launcher 小卡片（MediaCenterUi）和仪表盘（DIM），不需要直连 adaptapi DimInteraction。
- **参照实现**：MediaBridge（`com.geely.auto.music`），纯 EAS 推送。
- **UI 与业务分离**：`CarMediaService` 是唯一状态源，`MainActivity` 只做转发与 JsBridge，不读 pref、不碰系统服务。

## 2. 构建

### 2.1 构建环境

| 项 | 值 |
|---|---|
| JDK | 17 |
| Gradle / AGP | 8.7（wrapper）/ 8.5.2 |
| compileSdk / minSdk / targetSdk | 34 / 28 / 28 |
| versionName / versionCode | 1.6.8 / 33 |
| 签名 | 本地 debug keystore；车机需 platform 私签（见 §13） |

### 2.2 本地配置

首次构建前复制根目录 `local.properties.example` 为 `local.properties` 填入本机值；该文件已被 `.gitignore` 忽略，不入库。配置键共 7 个：

- `sdk.dir`：Android SDK 路径（本机路径，无占位）。
- `ecarx.appId` → `manifestPlaceholders.ecarxAppId` → Manifest meta-data，缺省 `YOUR_ECARX_APP_ID`。
- `ecarx.appKey` → `manifestPlaceholders.ecarxAppKey` → Manifest meta-data，缺省 `YOUR_ECARX_APP_KEY`。
- `carmedia.updateUrl` → `BuildConfig.UPDATE_URL`，缺省 `https://example.com/CarMedia.txt`。
- `gh.owner` → `BuildConfig.GITHUB_OWNER`，缺省 `YOUR_GITHUB_OWNER`。
- `gh.repo` → `BuildConfig.GITHUB_REPO`，缺省 `YOUR_GITHUB_REPO`。
- `gh.token` → `BuildConfig.GITHUB_TOKEN`，缺省 `YOUR_GITHUB_TOKEN`。

未配置时全部走缺省占位符，项目仍可编译；EAS 鉴权、热更新、在线更新相应安全降级（见 §12）。

### 2.3 构建与安装

```powershell
.\gradlew.bat assembleDebug --offline
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.ecarx.carmedia/.MainActivity
```

`gradle.properties`：JVM 堆 `2048m`，daemon 与 parallel 开启，编码 UTF-8。退出码为 1 而输出 `BUILD SUCCESSFUL` 时是 deprecation 告警，不是构建失败。EAS 推送需在界面打开「自动仪表推送」开关，且只有真实车机有 EAS 框架。

## 3. 工程结构

```
app/src/main/
├── AndroidManifest.xml
├── java/  业务代码 com.ecarx.carmedia（40 个类）+ EAS SDK/AIDL 接口（com.ecarx.eas、com.ecarx.sdk、ecarx.xsf）
├── assets/ui.html  界面（WebView 全部 UI）
└── res/  widget 布局、图标、配色
```

### 3.1 业务代码（`com.ecarx.carmedia`）

| 文件 | 职责 |
|---|---|
| CarMediaService.java | 核心服务，全部业务逻辑，单一事实源 |
| MainActivity.java | UI 容器与 JsBridge |
| MediaCenterBridge.java / CarMediaMusicClient.java | EAS 推送桥接与客户端回调 |
| MediaNotificationListener.java / NotificationPermissionHelper.java | 通知监听（权威数据源 + 保活锚点）与授权 |
| KeyMonitor.java / LogcatKeyMonitor.java / CarPropertyKeyMonitor.java | 手势判定、CarService 属性监听与 logcat 兜底 |
| QQMusicLyricFetcher.java | QQ 音乐歌词（AIDL 直连） |
| CloudLyricFetcher.java | 网易云 / 云端歌词（HTTP） |
| KuwoLyricFetcher.java + KuwoDes.java / KuwoAidlClient.java | 酷我歌词（AIDL + DES）与真实进度 AIDL |
| KugouLyricFetcher.java / KugouCoverProvider.java | 酷狗歌词与封面 AIDL |
| SodaMusicLyricFetcher.java | 汽水音乐歌词 |
| EmbeddedLyricReader.java | 音频文件内嵌歌词（FLAC/MP3/M4A/OGG/WMA/APE） |
| LrcUtil.java / TextUtil.java | LRC 解析、文本归一化 |
| ArtworkHelper.java / ArtworkContentProvider.java / AppIconResolver.java / CoverFallback.java | 封面存取、共享与应用图标 |
| UsbMusicScanner.java / UsbMusicPlayer.java / UsbSourceController.java / UsbMusicItem.java | USB 本地扫描、播放与音源门面 |
| CarMediaWidget.java / CarMediaSkyWidget.java / WallpaperMask.java / CardActionReceiver.java | 桌面小组件 |
| UiHotUpdate.java | ui.html 热更新 |
| ApkProvider.java / SilentInstaller.java / UpdateResultReceiver.java | 在线更新、安装与会话结果回调 |
| BootReceiver.java / SettingsRecovery.java / SourceSwitchReceiver.java / LogCaptureManager.java | 开机自启、设置恢复、广播切音源、日志抓取 |

### 3.2 SDK 与 AIDL 接口（反编译移植）

- `com.ecarx.eas.framework.sdk`：IEASFrameworkService、IEASFrameworkSuppportService。
- `com.ecarx.eas.sdk`：ECarXApiClient。
- `com.ecarx.eas.sdk.mediacenter`：MediaCenterAPI、MusicClient、MusicClientWrapper、MusicPlaybackInfo、ExCallbackWrapper。
- `com.ecarx.eas.xsf.mediacenter`：IExCallback、IExContent。
- `com.ecarx.sdk.openapi.msg`：EASFrameworkMessage、EASFrameworkRetMessage、SupportServiceRetMessage。
- `ecarx.xsf.mediacenter`：IMedia、IMediaCenterSvc、IMediaCenterClientToken、IMediaLists、IMusicClient、IMusicPlaybackInfo、IRecommend、ISearchMusicCallback。

## 4. AndroidManifest

- 声明 `android:sharedUserId="android.uid.system"`，系统级权限需 platform 私签后才能生效；`usesCleartextTraffic="true"`，在线更新允许 HTTP 下载。

权限全集：`BLUETOOTH`、`BLUETOOTH_CONNECT`、`INTERNET`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`、`GET_TASKS`、`READ_LOGS`、`MEDIA_CONTENT_CONTROL`、`MODIFY_AUDIO_ROUTING`、`WRITE_SECURE_SETTINGS`、`CHANGE_COMPONENT_ENABLED_STATE`、`INJECT_EVENTS`、`INSTALL_PACKAGES`、`DELETE_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、`READ_MEDIA_AUDIO`、`READ_WALLPAPER_INTERNAL`、`FORCE_STOP_PACKAGES`、`ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION`。

`READ_LOGS` 用于方控日志监听，`MODIFY_AUDIO_ROUTING` 影响播放状态读取（见 §5.3），`OPENAPI_MEDIACENTER_PERMISSION` 为 EAS 必需。

组件：

- **MainActivity**：landscape、singleTask，LAUNCHER + MEDIA_PLAY_FROM_SEARCH + ECARX_VR_APP_OPEN；**CarMediaService**：前台服务，exported=false。
- **MediaNotificationListener**：BIND_NOTIFICATION_LISTENER_SERVICE；**BootReceiver**：directBootAware，priority=1000，BOOT_COMPLETED + LOCKED_BOOT_COMPLETED。
- **ArtworkContentProvider**（`com.ecarx.carmedia.artwork`）与 **ApkProvider**（`com.ecarx.carmedia.apk`）：exported、grantUriPermissions。
- **UpdateResultReceiver** 接收静默安装结果广播；**SettingsRecovery$DelayedReceiver** 更新后延迟恢复车机设置；**SourceSwitchReceiver** 广播切音源，exported=false。
- **CarMediaWidget / CarMediaSkyWidget** 桌面小组件（AppWidgetProvider）；**CardActionReceiver** 小组件卡片动作回调。

凭据注入：Manifest 中 `<meta-data>` 的 `eCarX_OpenAPI_AppId` / `eCarX_OpenAPI_AppKey` 使用 `${ecarxAppId}` / `${ecarxAppKey}` 占位，由 `manifestPlaceholders` 从 `local.properties` 的 `ecarx.appId` / `ecarx.appKey` 注入；未配置时为缺省占位符，公开仓库不含真实值。

## 5. CarMediaService

### 5.1 生命周期与保活

onCreate 按序初始化：前台通知、MediaSession 监听、歌词抓取器与轮询 Handler、按键监听、通知授权、推送与歌词黑名单恢复、音频播放监控、A2DP 与网易云广播注册、EAS 桥接、控制器刷新与首次推送重试。`onStartCommand` 返回 `START_STICKY`，`onTaskRemoved` 重新拉起自己，`onDestroy` 逆序释放；不注册自建 MediaSession，避免抢占媒体键和蓝牙 AVRCP。保活三层：MediaNotificationListener 作为系统绑定锚点，进程被杀后自动重启并拉起主服务；`onTaskRemoved` 重新拉起；`START_STICKY` 兜底。强制停止后能否自启取决于 ROM，部分 ROM 会彻底冻结。

### 5.2 控制器管理

- `refreshController()` 完整重选：置 stale → unregister → 清空 → `updateAvailableControllers()`；`refreshControllerList()` 只刷列表不切换。
- 选源优先级：过滤自身、隐藏控制器与系统媒体包 → 活动会话 → 真实音频在播的源 → 播放/暂停态会话 → 全空置 stale。
- `selectController()` 带重入保护，系统媒体包直接拒绝；当前控制器仍存活时只刷列表不切换，避免切换风暴。

### 5.3 播放状态判定

优先级：真实音频（AudioTrack 反射）> 通知判定 > 控制器实时状态 > 回调缓存。

- 反射 `AudioManager.getActivePlaybackConfigurations()`，按包名 uid 匹配 AudioTrack，`started` 视为播放；需 `MODIFY_AUDIO_ROUTING`，否则 uid 被脱敏为 -1，表现为「播放却显示暂停」。
- 兼容定制 ROM 与 AOSP 的 state 常量偏移（started 可能是 1 或 2），排除 SoundPool idle 干扰；切歌瞬间 AudioTrack 重建，判定不到按暂停处理，不沿用旧快照。
- **蓝牙例外**：A2DP sink 的 AudioTrack 常驻 started，一律以 MediaSession 会话状态判定；状态未知时不推送。

### 5.4 通知权威与歌词轮询

- 只接受当前控制器自己的通知；歌名与当前歌名一致才采纳，避免后台文案污染标题；非当前控制器的在播通知先触发重新选源，再合并元数据；通知移除时清空快照。
- 500ms 轮询歌词：位置未变跳过，行变化或仍在播放时推送；2 秒 position ticker 只在播放时运行。推送去重维度为歌名、歌手、状态、歌词行；主界面与 EAS 共用同一取词结果与拼接格式。
- 关闭「自动仪表推送」只停轮询与推送并清空卡片，不销毁 EAS 连接，重开直接恢复。
- 媒体控制：`sendMediaAction(playpause|next|prev)` 为统一入口，UI 按钮与方向盘按键共用；播放/暂停判定用真实音频状态（蓝牙用会话状态），EAS 仪表盘按钮回调直接调用 play/pause。

## 6. UI 层

WebView 加载 `assets/ui.html`（或热更新版本），原生只提供数据和动作转发。

- `pushAll()` 每秒推送 JSON：歌名、歌手、歌词、下一句、状态、包名、控制器列表；`runJs()` 在 WebView 未就绪时缓存，`onPageFinished` 后补执行。
- JsBridge 提供：媒体控制、控制器切换、按键录制、歌词黑名单、壁纸选择、日夜模式、在线更新、USB 播放接口、日志监听。主题由 ui.html 的 CSS 变量控制，原生主题只作为 WebView 背景。

## 7. 歌词系统

- **QQ 音乐**（绑定 `com.tencent.qqmusiccar` AIDL，未授权提示授权）、**网易云**（HTTP 搜索 + 备用接口）、**酷我**（AIDL playlog + DES 加密）、**酷狗**（AIDL 取词转 LRC）、**汽水音乐**（MEDIA_ID 取词）、**USB**（内嵌歌词、网易云、汽水、备用四种来源）。

- `LrcUtil.parse()` 支持一行多时间标签，只过滤空行和空白时间戳行；`getCurrentLine()` 取时间戳不大于播放位置的最后一行，`getNextLine()` 取其后第一个非空行。
- **元数据行必须显示**：作词、作曲、编曲、OP、SP 等标签行不得过滤。
- 取词位置统一用 `getPlaybackPosition()`：多数音源 MediaSession 的 position 播放中不更新，直接用会卡在第一句。`TextUtil.normalize()` 用于歌名归属校验。

## 8. EAS 推送

### 8.1 状态语义与链路

```
EAS playbackStatus: PAUSED=0, PLAYING=1, INTERRUPT=2, PREPARE=3
MediaCenterAPI.updateMusicPlaybackState
  → ecarx.xsf.mediacenter.MediaCenterService
      ├→ launcher 小卡片（MediaCenterUi.WidgetService）
      └→ 仪表盘 DIM（DimInteractionHelper.updateDimPlaybackInfo）
```

EAS 状态与 MediaSession 的 3/2 不同，推送前必须转换成 0/1；DIM 只在有客户端在播时转发，推 EAS 即覆盖小卡片与仪表盘。

### 8.2 封面与应用图标

- `ArtworkHelper` 缩放 Bitmap（超 512px 才缩）、按内容哈希存到 `filesDir/artwork/`（保留最近 10 个）；`ArtworkContentProvider` 以 `content://com.ecarx.carmedia.artwork/<文件名>` 跨进程读取并拒绝路径穿越；`AppIconResolver` 反射取图标，取不到回退自身图标；http 封面链接直接透传 URI，`content`/`file` 才落盘；音源无封面数据时卡片无封面属数据源限制。

## 9. 按键系统

两条通道：`CarPropertyKeyMonitor` 优先通过 Android Automotive `CarPropertyManager` 订阅 `HW_KEY_INPUT`；设备无 CarService 或连接失败时回退 `LogcatKeyMonitor`（后台读 `logcat -b all -s InputManager:V CAR.INPUT:V`，解析按下/抬起时间戳注入 `handleExternalKeyDown/Up`，避免同键双发）。两路事件汇入 `KeyMonitor` 做手势判定与动作映射。

键码：模式/SRC 348、上一曲 304、下一曲 305、静音 300、音量加/减 301/302、返回 307、OK 306；其中 OK 只由 CAR.INPUT 日志解析，其余可由 CarService 属性或 InputManager 日志获得。

手势：单击为抬起后 400ms 内无第二次按下；双击为两次按下间隔小于 400ms；长按为按下 600ms 无抬起。映射表格式 `"code:gesture" → action`，gesture 为 1 单击 / 2 双击 / 3 长按；动作支持 `mode`、`source`、`prev`、`next`、`playpause`、`mute`、`voice`、`call`、`openapp:<包名>`、`none`。默认映射：`348:1` 打开主界面、`348:2` 打开当前音源、`305:1` 下一曲、`304:1` 上一曲、`300:1` 播放暂停、`306:1` 打开主界面；首次注入后以 pref 为准。

静音键保留系统原生静音，CarMedia 只附加播放/暂停，不用 `mute` 动作，避免双触发；屏蔽三键状态持久化，进程重启后按 pref 恢复。按键录制模式下按下直接上报键码与手势，由 UI 选择动作保存；无 InputManager / CAR.INPUT 日志的设备无法验证方控。

## 10. 通知授权

`NotificationPermissionHelper` 依次尝试：反射 `NotificationManager.setNotificationListenerAccessGranted()`；`cmd notification allow_listener <组件>`；直写 `Settings.Secure.enabled_notification_listeners`（需系统签名与系统权限）。

## 11. 高级特性

- **蓝牙**：监听 AudioPlaybackCallback 的语音通信用途，通话结束后延迟 2 秒恢复播放；A2DP 重连后主动刷新控制器；当前控制器不是蓝牙时，蓝牙不作为自动切源候选。
- **隐藏与幽灵控制器**：隐藏系统媒体中心等无效控制器；已暂停但仍在播放的音源以「幽灵条目」保留（`getGhostControllerPkgs()` 汇总，含 CarPlay 盒子白名单会话），可手动选中，选中期间不参与自动切源。
- **多媒体禁用**：切到蓝牙时释放系统多媒体 App（`com.ecarx.multimedia`）音频焦点，避免双播；禁用状态持久化。
- **歌词黑名单**：按音源包名禁用歌词显示，持久化保存。
- **歌名闪烁检测**：蓝牙音源把歌词塞进 TITLE 时，30 秒内歌名频繁变化且歌手不变，判定为音源自带歌词，跳过备用歌词与封面兜底。
- **USB 本地播放**：USB 为自有音源，虚拟包名 `com.ecarx.carmedia.usb`，复用现有推送、进度条与按键链路；`UsbMusicScanner` 优先 MediaStore、失败回退文件递归，`UsbMusicPlayer` 封装 MediaPlayer 与音频焦点，`UsbSourceController` 为单例门面（收藏持久化，拔出自动暂停清空）；USB 激活时跳过外部扫描与自动切源，内嵌歌词按时间戳匹配。
- **桌面小组件**：`CarMediaWidget`（竖卡）与 `CarMediaSkyWidget`（晴空横版）；圆角由 `WallpaperMask` 烘进位图（RemoteViews 不支持裁剪）；内容未变跳过更新，部分更新不重建点击 PendingIntent。

## 12. 更新机制

### 12.1 ui.html 热更新

仓库归属与访问令牌由构建注入 `BuildConfig.GITHUB_OWNER` / `GITHUB_REPO` / `GITHUB_TOKEN`（对应 `gh.owner` / `gh.repo` / `gh.token`）。

- 任一字段为缺省占位符时，`UiHotUpdate` 不发起网络请求，直接回退内置 `assets/ui.html`。
- 已配置：启动时拉取最新 commit sha，与本地记录（pref `ui_remote_sha`）比对，不同则下载 `ui.html` 到 `filesDir` 并 reload（加载顺序 `filesDir` → `assets`，只改 assets 不推仓库不生效）；优先 Contents API（base64），失败回退 raw，结构校验不通过则丢弃；发布前更新 ui.html 顶部 `UI_VERSION`，公告弹窗按版本号变化触发。

### 12.2 在线更新

清单为纯文本 JSON，地址由 `carmedia.updateUrl` 注入 `BuildConfig.UPDATE_URL`，缺省占位 `https://example.com/CarMedia.txt`；占位地址不可达时，冷启动自动检查静默失败，手动检查提示失败，不影响主功能。

```json
{
  "version": "1.3",
  "notes": "更新说明",
  "url": "https://example.com/CarMedia_v1.3.apk",
  "md5": "可选"
}
```

- 冷启动自动检查一次；下载可选 MD5 校验，写入 cacheDir 后经 `ApkProvider` content URI 调起系统安装器，系统签名且持有 `INSTALL_PACKAGES` 时由 `SilentInstaller` 走 PackageInstaller 会话静默安装，结果经 `UpdateResultReceiver` 广播回传。清单容错注释行与 BOM，JSON 解析失败回退正则提取；部分车机应用商店会校验安装包白名单。

## 13. 签名

车机需 platform 私签（AOSP platform 密钥对），才能覆盖安装并拿到全部签名权限。

```powershell
apksigner sign --key <你的 platform.pk8 私钥路径> --cert <你的 platform.x509.pem 证书路径> `
  --out CarMedia-signed.apk app-debug.apk
apksigner verify --print-certs CarMedia-signed.apk
```

签名不一致会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载；签名身份变更后不可覆盖安装。

## 14. 调试

```powershell
adb logcat -s CarMedia            # 主服务
adb logcat -s CarMedia_NL         # 通知监听
adb logcat -s audioReal           # 真实音频状态
adb logcat -s UiHotUpdate         # 热更新
adb shell dumpsys media_session   # 会话 state / metadata
adb shell dumpsys audio           # AudioTrack uid / state
```

`input keyevent 85` 控制全局媒体键会话，不一定是当前控制器；`uiautomator dump` 在轮询动画下常失败，改用截图；`exec-out` 截图会被 PowerShell 转成文本，先写设备内文件再 pull。

## 15. 关键约束

1. EAS 状态只推 0/1，不要直接推 MediaSession 的 2/3。
2. 播放状态以真实音频为准；蓝牙例外，用会话状态。
3. 通知只接受当前控制器的，歌名以元数据为准。
4. 歌词元数据行必须显示，任何过滤都是 bug。
5. 主界面与仪表盘共用同一取词与拼接来源。
6. 打开控制器对话框前只刷列表，不重选控制器；当前控制器存活时不无条件重选；关闭推送开关不销毁 EAS 连接，否则重开后推送永久失效。
7. 自动切源排除自身、系统媒体包、隐藏控制器与投屏代理；蓝牙不作为候选，除非真的在播；USB 激活时跳过外部扫描与自动切源。
8. 按键去重以物理按压时刻为基准；静音键不做 `mute` 动作，避免与系统静音双触发。
9. 小组件圆角只能烘进位图；部分更新不会应用点击事件，需全量更新重建。

## 16. 已知限制

- 强制停止后能否自启取决于 ROM；无 EAS 框架的设备（如普通模拟器）不产生推送，只能验证界面与逻辑。
- 无 CarService / InputManager / CAR.INPUT 日志的设备无法验证方控；按键码与车型相关，换车型需重新采集映射。
- 音源不提供封面数据时小卡片只能显示占位图；酷我定制版包名与标准版不同，需双包名兼容。
- 云端歌词与封面接口依赖第三方服务，可能限流、调整或失效。
