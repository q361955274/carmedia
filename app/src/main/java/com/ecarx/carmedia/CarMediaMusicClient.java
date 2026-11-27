package com.ecarx.carmedia;

import android.util.Log;

import com.ecarx.eas.sdk.mediacenter.MusicClient;
import com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo;

/**
 * EAS 音乐客户端（注册到 MediaCenterAPI）。
 *
 * 原版 carmedia 只实现了 getter（getCurrentSourceType/getMediaSourceTypeList/
 * getMusicPlaybackInfo/setPlaybackInfo），所有控制回调（onPlay/onPause/onNext/...）
 * 继承 MusicClient 基类默认实现（返回 false）——这就是原版"小卡片控制按钮没反应"
 * 的根因。此处仿照 MediaBridge 的 MusicClientImpl，补全全部控制回调，转发到
 * CarMediaService 的真实媒体控制。
 */
public class CarMediaMusicClient extends MusicClient {
    private static final String TAG = "CarMediaMusicClient";

 /** 控制回调：由 CarMediaService 注入，转发真实媒体控制。 */
    public interface Controller {
        void onPlayCommand();
        void onPauseCommand();
        void onNextCommand();
        void onPreviousCommand();
        void onSeekCommand(long pos);
        void onStopCommand();
        void onRewindCommand();
        void onForwardCommand();
 /** 收藏命令（EAS 小卡片收藏按钮 → 真实音源；like=true 收藏 / false 取消）。移植 MediaBridge handleCollect 语义。 */
        void onCollectCommand(boolean like);
    }

    private MusicPlaybackInfo mPlaybackInfo;
    private volatile Controller mController;

    public CarMediaMusicClient() {
    }

    public void setController(Controller controller) {
        this.mController = controller;
    }

    private boolean forward(String action) {
        Controller c = mController;
        if (c == null) {
            Log.w(TAG, "No controller attached, cannot handle: " + action);
            return false;
        }
        try {
            switch (action) {
                case "PLAY":
                    c.onPlayCommand();
                    break;
                case "PAUSE":
                    c.onPauseCommand();
                    break;
                case "NEXT":
                    c.onNextCommand();
                    break;
                case "PREVIOUS":
                    c.onPreviousCommand();
                    break;
                case "STOP":
                    c.onStopCommand();
                    break;
                case "REWIND":
                    c.onRewindCommand();
                    break;
                case "FAST_FORWARD":
                    c.onForwardCommand();
                    break;
                default:
                    return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "forward " + action + " failed", e);
            return false;
        }
    }

    // ---------- 控制回调（EAS 小卡片/仪表盘按钮 → 真实媒体控制） ----------

    @Override
    public boolean onPlay() {
        return forward("PLAY");
    }

    @Override
    public boolean onPause() {
        return forward("PAUSE");
    }

    @Override
    public boolean onNext() {
        return forward("NEXT");
    }

    @Override
    public boolean onPrevious() {
        return forward("PREVIOUS");
    }

    @Override
    public boolean onExit() {
        return forward("STOP");
    }

    @Override
    public boolean onReplay() {
        return forward("PLAY");
    }

    @Override
    public boolean onRewind() {
        return forward("REWIND");
    }

    @Override
    public boolean onForward() {
        return forward("FAST_FORWARD");
    }

    @Override
    public boolean onMediaForward(boolean z) {
        return z && forward("FAST_FORWARD");
    }

    @Override
    public boolean onMediaRewind(boolean z) {
        return z && forward("REWIND");
    }

    @Override
    public void onSeek(long j) {
        Controller c = mController;
        if (c != null) {
            try {
                c.onSeekCommand(j);
            } catch (Exception e) {
                Log.e(TAG, "onSeek failed", e);
            }
        }
    }

    @Override
    public boolean onSourceSelected(int i) {
        if (i != 6) {
            return true;
        }
        return forward("PLAY");
    }

    @Override
    public boolean ctrlPlayMediaList(int i) {
        return forward("PLAY");
    }

    @Override
    public boolean ctrlPauseMediaList(int i) {
        return forward("PAUSE");
    }

    // ---------- 收藏回调（移植 MediaBridge MusicClientImpl.handleCollect 语义）----------
    @Override
    public boolean onCollect(int i, boolean z) {
        handleCollect(z);
        return true;
    }

    @Override
    public int ctrlCollect(int i, boolean z) {
        handleCollect(z);
        return 0;
    }

    @Override
    public void ctrlCollectByUUID(int i, String str, boolean z) {
        // UUID 版：与普通版同语义（当前歌曲收藏切换），转发到真实音源
        handleCollect(z);
    }

 /** 收藏命令统一入口：立即更新本地收藏状态并转发到 CarMediaService 的真实音源控制。 */
    private void handleCollect(boolean like) {
        try {
            // 立即反馈到卡片（乐观更新，MediaBridge 语义：点击后马上反映状态）
            com.ecarx.carmedia.MediaCenterBridge bridge = com.ecarx.carmedia.MediaCenterBridge.get();
            if (bridge != null) {
                bridge.setCollected(like);
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleCollect local state error: " + t.getMessage());
        }
        Controller c = mController;
        if (c != null) {
            try {
                c.onCollectCommand(like);
            } catch (Throwable t) {
                Log.e(TAG, "handleCollect forward failed", t);
            }
        } else {
            Log.w(TAG, "handleCollect: no controller attached, collect toggled locally only");
        }
    }

    @Override
    public boolean onLoopModeChange(int i) {
        return true;
    }

    @Override
    public boolean onMediaQualityChange(int i) {
        return true;
    }

    @Override
    public boolean onSourceChanged(int i, String str) {
        return true;
    }

    @Override
    public void operationType(int i) {
    }

    @Override
    public void onMediaCenterFocusChanged(String str) {
        // EAS 播放焦点变化回调。★不再反抢 :抢焦点会触发
        // XCMedia2 的"无焦点就暂停蓝牙"霸权逻辑(BTMusicStateManagerP 收到 A2DP
        // 播放事件时 queryIfHasFocus() 判定焦点不在自己 → AVRCP PAUSE 蓝牙)。
        // CarMedia 不持有焦点 → XCMedia2 判定空=自己有焦点 → 不暂停蓝牙。
        // 仅保留日志观察焦点归属。
        Log.i(TAG, "onMediaCenterFocusChanged: focus -> " + str + " (不反抢,保持空让 XCMedia2 判定自己有焦点)");
        CarMediaService svc = CarMediaService.sInstance;
        if (svc != null) {
            svc.onEasFocusLost();
        }
    }

    // ---------- getter（EAS 查询当前播放信息） ----------

    @Override
    public int getCurrentSourceType() {
        return 6;
    }

    @Override
    public int[] getMediaSourceTypeList() {
        // 与 performRegistration 的 updateMediaSourceTypeList({6}) 保持一致
        return new int[]{6};
    }

    @Override
    public MusicPlaybackInfo getMusicPlaybackInfo() {
        return this.mPlaybackInfo;
    }

    public void setPlaybackInfo(MusicPlaybackInfo musicPlaybackInfo) {
        this.mPlaybackInfo = musicPlaybackInfo;
    }
}
