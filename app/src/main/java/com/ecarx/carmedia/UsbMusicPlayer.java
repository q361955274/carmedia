package com.ecarx.carmedia;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * USB 本地音乐播放器。
 *
 * 封装原生 MediaPlayer，支持列表播放/暂停/切歌/seek/进度上报/音频焦点。
 * 所有 PlayerCallback 回调均在主线程。
 */
public class UsbMusicPlayer {

    private static final String TAG = "CarMedia";
    private static final String TAG_SUB = "UsbMusicPlayer";
    private static final long PROGRESS_INTERVAL_MS = 1000L; // 从500ms降到1000ms，减少状态推送和DOM重建频率，避免主线程阻塞
    // ★ (Bug#72H):卡歌/循环看门狗——位置冻结多久判卡死并自动切歌;连续异常多少首后暂停。
    private static final long STALL_SKIP_MS = 4000L;
    private static final int MAX_CONSECUTIVE_STALL_SKIPS = 3;

    // 播放状态
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_COMPLETED = 4;
    public static final int STATE_ERROR = 5;

    // 播放模式
    public static final int MODE_SEQUENCE = 0;
    public static final int MODE_RANDOM = 1;
    public static final int MODE_SINGLE_LOOP = 2;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private MediaPlayer mediaPlayer;
 /** 当前播放目标令牌——listener 每次重绑但用该字段判断回调是否仍有效。 */
    private volatile int currentTargetSeq = 0;
 /** 每次 play() 递增，用于在异步回调中判断是否已被新的播放取代/释放。 */
    private int playSequence = 0;
 /** 当前歌曲连续重试次数，play() 时重置为0。 */
    private int retryCount = 0;
    // ★ (Bug#72H):卡歌/循环看门狗状态(上次位置/冻结起始/连续异常计数/本曲起点/上次seek)。
    private long stallWatchPos = -1;
    private long stallWatchStart = 0L;
    private int stallWatchSkips = 0;
    private long trackStartTime = 0L;
    private long lastSeekAt = 0L;
    private List<UsbMusicItem> playlist;
    // fix: currentIndex/playState 可能在子线程(JS bridge)中修改，
    // 主线程进度回调需要立即可见，声明为 volatile 保证内存可见性。
    private volatile int currentIndex = -1;
    private volatile int playState = STATE_IDLE;
    private int playMode = MODE_SEQUENCE;
    // 收藏序列播放模式——收藏 tab 点歌后 next/prev/自动下一首按收藏列表
    // 顺序切(而非全部列表顺序)。favIndexList 为收藏歌曲在 playlist 中的下标升序数组。
    private boolean favMode = false;
    private int[] favIndexList;
    private PlayerCallback callback;

 /** 音频焦点监听器（USB 播放期间持有焦点，被抢占时暂停/恢复）。 */
    private AudioManager.OnAudioFocusChangeListener usbFocusListener;
 /** 是否因焦点丢失而暂停（用于 AUDIOFOCUS_GAIN 时判断是否恢复播放）。 */
    private boolean pausedByFocusLoss = false;

 /** 进度轮询 Runnable。 */
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (playState == STATE_PLAYING && mediaPlayer != null) {
                try {
                    long pos = mediaPlayer.getCurrentPosition();
                    long dur = mediaPlayer.getDuration();
                    boolean playing = mediaPlayer.isPlaying();
                    // ★ (Bug#72H):卡歌/循环看门狗——
                    // ① 位置回绕(pos 大幅倒退、非用户 seek):native 循环在生效
                    // (如 ANDROID_LOOP=true 未被 setLooping(false) 覆盖),强制切下一首;
                    // ② 位置冻结(引擎僵死):连续 STALL_SKIP_MS 不动,强制切下一首;
                    // ③ 连续 MAX_CONSECUTIVE_STALL_SKIPS 首都异常则暂停,避免无限快跳。
                    if (playing) {
                        long now = System.currentTimeMillis();
                        boolean userSeek = (now - lastSeekAt < 2000L);
                        if (stallWatchPos >= 0 && pos < stallWatchPos - 200L && !userSeek) {
                            Log.w(TAG, TAG_SUB + ": watchdog: position wrapped back "
                                    + stallWatchPos + "->" + pos + " (native loop), skip");
                            stallWatchPos = -1;
                            stallWatchStart = 0L;
                            if (++stallWatchSkips >= MAX_CONSECUTIVE_STALL_SKIPS) {
                                stallWatchSkips = 0;
                                Log.w(TAG, TAG_SUB + ": watchdog: " + MAX_CONSECUTIVE_STALL_SKIPS
                                        + " consecutive abnormal tracks, pause");
                                pause();
                                return;
                            }
                            autoAdvance();
                            return;
                        }
                        if (pos == stallWatchPos && stallWatchPos >= 0
                                && now - stallWatchStart >= STALL_SKIP_MS
                                && (dur <= 0 || pos < dur - 500L)) {
                            Log.w(TAG, TAG_SUB + ": watchdog: position frozen at " + pos
                                    + "ms for " + STALL_SKIP_MS + "ms, skip");
                            stallWatchPos = -1;
                            stallWatchStart = 0L;
                            if (++stallWatchSkips >= MAX_CONSECUTIVE_STALL_SKIPS) {
                                stallWatchSkips = 0;
                                Log.w(TAG, TAG_SUB + ": watchdog: " + MAX_CONSECUTIVE_STALL_SKIPS
                                        + " consecutive abnormal tracks, pause");
                                pause();
                                return;
                            }
                            autoAdvance();
                            return;
                        }
                        if (pos != stallWatchPos) {
                            stallWatchPos = pos;
                            stallWatchStart = now;
                            // 歌曲已健康播放超过一个看门狗窗口:清连续异常计数
                            if (now - trackStartTime > STALL_SKIP_MS) {
                                stallWatchSkips = 0;
                            }
                        }
                    }
                    notifyProgress(pos, dur > 0 ? dur : 0);
                } catch (Throwable t) {
                }
            } else {
                stallWatchPos = -1;
                stallWatchStart = 0L;
            }
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    public UsbMusicPlayer(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        // 初始化 USB 音频焦点监听器：被电话/导航等抢占时暂停，焦点恢复时继续播放
        this.usbFocusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                handleAudioFocusChange(focusChange);
            }
        };
    }

    public void setCallback(PlayerCallback cb) {
        this.callback = cb;
    }

    public void setPlaylist(List<UsbMusicItem> list) {
        this.playlist = list;
        // 列表重建后收藏序列失效,退出收藏模式(避免下标错位)
        this.favMode = false;
        this.favIndexList = null;
        if (list == null || list.isEmpty()) {
            currentIndex = -1;
        } else if (currentIndex < 0 || currentIndex >= list.size()) {
            currentIndex = 0;
        }
    }

    public void setPlayMode(int mode) {
        this.playMode = mode;
    }

    public int getPlayMode() {
        return playMode;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public UsbMusicItem getCurrentItem() {
        if (playlist == null || currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        return playlist.get(currentIndex);
    }

    public int getPlayState() {
        return playState;
    }

    public long getPosition() {
        if (mediaPlayer == null) return 0;
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Throwable t) {
            return 0;
        }
    }

    public long getDuration() {
        if (mediaPlayer == null) return 0;
        // MediaPlayer 在错误状态(IDLE/PREPARING/ERROR)调 getDuration() 会打
        // error(-38,0)+"Attempt to call getDuration in wrong state" 日志(每次切歌刷屏,
        // 真车日志实测约 3 行/次)并抛 IllegalStateException——只在已 prepare 完成的状态读。
        int st = playState;
        if (st != STATE_PLAYING && st != STATE_PAUSED && st != STATE_COMPLETED) {
            return 0;
        }
        try {
            long d = mediaPlayer.getDuration();
            return d > 0 ? d : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

 /** 播放指定索引（用户手动/自动切歌入口：重置重试计数）。 */
    public void play(int index) {
        startPlayImpl(index, true);
    }

 /** onError 重试当前歌曲：不重置重试计数（让"重试 1 次后放弃+toast"的计数真正生效）。 */
    private void retryPlay(int index) {
        startPlayImpl(index, false);
    }

    private void startPlayImpl(int index, boolean resetRetry) {
        Log.i(TAG, TAG_SUB + ": play(" + index + ") called, listSize=" + (playlist == null ? 0 : playlist.size()));
        if (playlist == null || playlist.isEmpty()) {
            notifyError("playlist empty");
            return;
        }
        if (index < 0 || index >= playlist.size()) {
            notifyError("index out of range: " + index);
            return;
        }
        currentIndex = index;
        // ★ (Bug#80):retryCount 只在"用户/自动切歌"入口重置。
        // 旧代码 play() 无条件 retryCount=0，导致 onError 重试也调 play() 时把计数清零，
        // "重试 1 次后放弃+toast"分支永远走不到——遇到持续 -38(INVALID_OPERATION) 起播失败的歌
        // 会无限重试/卡死，UI 表现为"起播失败被跳歌/卡在当前歌"。retryPlay 走 resetRetry=false 保留计数。
        if (resetRetry) {
            retryCount = 0;
        }
        // ★ (Bug#72H):新曲起点,重置看门狗状态
        stallWatchPos = -1;
        stallWatchStart = 0L;
        trackStartTime = System.currentTimeMillis();
        lastSeekAt = 0L;
        UsbMusicItem item = playlist.get(index);
        if (item == null || item.path == null) {
            notifyError("item path null");
            return;
        }
        // fix: 切歌不再 release+延迟重建——复用同一个 MediaPlayer 实例，
        // startPlayerInternal 内 stop+reset 后直接 setDataSource，native 层不释放，
        // 从根本上消除 -38(INVALID_OPERATION) 竞态和 120ms 切歌延迟。
        final int seq = ++playSequence;
        currentTargetSeq = seq;
        setState(STATE_PREPARING);
        startPlayerInternal(seq, item);
    }

 /** 准备 MediaPlayer（仅 playSequence 令牌校验通过后调用，运行于主线程）。
 * 复用实例——首次创建后不再 release，每次 stop+reset 后重新
 * setDataSource + prepareAsync，native 层不释放,消除 -38 竞态与 120ms 延迟。 */
    private void startPlayerInternal(final int seq, final UsbMusicItem item) {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
        }
        try {
            // 复用实例:先停掉当前播放并复位到 Idle 状态(listener 在 reset 后保留)
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
            } catch (Throwable ignored) {}
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            // ★ (Bug#72H):显式关闭循环——OGG 测试音/铃声文件常带
            // ANDROID_LOOP=true 元数据,MediaPlayer native 层(NuPlayer)会无条件循环
            // (onCompletion 永不回调 → 永不自动切歌)。setLooping(false) 让这类文件
            // 也只播一遍后正常触发 onCompletion → autoAdvance。
            try {
                mediaPlayer.setLooping(false);
            } catch (Throwable ignored) {}
            mediaPlayer.setDataSource(item.path);
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    // fix: onError 和 onPrepared 可能同时触发（如 what=-38 非致命错误）。
                    // 复用实例后 onError 不再 release,用 currentTargetSeq 校验回调有效性。
                    if (currentTargetSeq != playSequence || mediaPlayer == null) {
                        return;
                    }
                    // (Bug#72E):PREPARING 期间被 pause/pauseForSwitch 主动暂停时,
                    // prepare 完成后不得自动 start 把 USB 重新拉起来——否则 onStateChanged(PLAYING)
                    // 触发 CarMedia 的 re-arm,usbRouteArmed 重新武装,按键又喂给 USB
                    // ("汽水在播,按切歌却切了 USB")。保持 PAUSED,等用户手动 resume。
                    if (playState == STATE_PAUSED) {
                        Log.i(TAG, TAG_SUB + ": onPrepared but paused during prepare, stay paused");
                        return;
                    }
                    // (B1,对齐原厂 MediaPlayerHelper):请求焦点并确认拿到才 start。
                    // 拿不到焦点(其他音源持 FULL 焦点)则保持 PREPARING,等 focus GAIN 时
                    // handleAudioFocusChange 自动 resume——避免双音源同时出声。
                    if (requestFocus()) {
                        setState(STATE_PLAYING);
                        try {
                            mp.start();
                        } catch (Throwable t) {
                            Log.w(TAG, TAG_SUB + ": onPrepared start failed: " + t.getMessage());
                            setState(STATE_ERROR);
                            notifyError("start failed: " + t.getMessage());
                            releasePlayer();
                            return;
                        }
                        startProgressTicker();
                    } else {
                        Log.i(TAG, TAG_SUB + ": focus not granted, hold PREPARING until GAIN");
                        pausedByFocusLoss = true;
                        setState(STATE_PREPARING);
                    }
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (currentTargetSeq != playSequence) {
                        return;
                    }
                    setState(STATE_COMPLETED);
                    stopProgressTicker();
                    notifyCompletion();
                    // 复用实例后无需等待 native 释放,直接自动切歌。
                    autoAdvance();
                }
            });
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (currentTargetSeq != playSequence) {
                        return true;
                    }
                    final String errSong = (playlist != null && currentIndex >= 0 && currentIndex < playlist.size())
                            ? playlist.get(currentIndex).title : "未知歌曲";
                    final String errDetail = "what=" + what + " extra=" + extra;
                    final int failedIdx = currentIndex;
                    Log.e(TAG, TAG_SUB + ": mediaplayer error " + errDetail + " song=" + errSong + " retry=" + retryCount);
                    // 标记本次播放已失效，阻止 onPrepared 继续执行 start
                    playSequence++;
                    setState(STATE_ERROR);
                    stopProgressTicker();
                    notifyError("mediaplayer error " + errDetail);
                    // 出错后先重试当前歌曲一次（-38 多为间歇性状态错误，重试通常可恢复）；
                    // 重试仍失败才停住并 toast 提示，不再无脑跳下一首。
                    // ★ (Bug#80):改用 retryPlay(failedIdx) 保留 retryCount，配合
                    // startPlayImpl 仅在非重试路径清零，使"重试 1 次后放弃"分支真正可达；
                    // 并 postDelayed(200ms) 让 native 从 ERROR 态充分复位，降低复用实例时
                    // -38(INVALID_OPERATION) 竞态复发概率。
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // 复用实例,无需 releasePlayer——play() 内 stop+reset 复用。
                            // fix: 重试前检查用户是否已切歌。如果 currentIndex 已变，
                            // 说明用户在 onError 触发后点了其他歌，此时不应再重试失败歌曲把 UI 抢回去。
                            if (currentIndex != failedIdx) {
                                return;
                            }
                            if (retryCount < 1) {
                                retryCount++;
                                // fix: 首次重试不再弹 toast——-38 竞态导致的
                                // "报错但能播"场景不应打扰用户；重试仍失败才提示(下方分支)。
                                Log.i(TAG, TAG_SUB + ": retry " + errSong + " attempt " + retryCount + " (silent)");
                                retryPlay(failedIdx);
                            } else {
                                Toast.makeText(appContext, "播放失败：" + errSong + "（错误码 " + what + "），请手动切歌", Toast.LENGTH_LONG).show();
                                Log.i(TAG, TAG_SUB + ": gave up on " + errSong + " after " + retryCount + " retries");
                            }
                        }
                    }, 200);
                    return true; // 已处理，不再抛 onCompletion
                }
            });
            mediaPlayer.prepareAsync();
        } catch (IllegalArgumentException | SecurityException | IllegalStateException | IOException e) {
            Log.e(TAG, TAG_SUB + ": play exception: " + e.getClass().getSimpleName() + " " + e.getMessage());
            setState(STATE_ERROR);
            notifyError(e.getMessage() == null ? "play failed" : e.getMessage());
            releasePlayer();
        }
    }

 /** 播放/暂停切换。 */
    public void playPause() {
        if (mediaPlayer == null || playState == STATE_ERROR || playState == STATE_COMPLETED || playState == STATE_IDLE) {
            // 无当前播放或已出错/完成：重新从当前索引开始播放
            if (playlist != null && !playlist.isEmpty()) {
                int idx = (currentIndex >= 0 && currentIndex < playlist.size()) ? currentIndex : 0;
                play(idx);
            }
            return;
        }
        if (playState == STATE_PLAYING) {
            pause();
        } else if (playState == STATE_PAUSED || playState == STATE_PREPARING) {
            resume();
        }
    }

    public void pause() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            setState(STATE_PAUSED);
            stopProgressTicker();
        } catch (Throwable t) {
            notifyError(t.getMessage() == null ? "pause error" : t.getMessage());
        }
    }

 /** (Bug#72E):切离 USB 时专用暂停——除暂停外,清掉"焦点丢失待恢复"标记,
 * 防止之后 focus GAIN 触发 handleAudioFocusChange 自动 resume 把 USB 又拉起来播放
 * (USB 恢复播放 → CarMedia re-arm → 按键被 USB 抢走)。 */
    public void pauseForSwitch() {
        if (mediaPlayer == null) {
            // 无实例时也清标记并置暂停态,防后续 focus GAIN/onPrepared 误恢复
            pausedByFocusLoss = false;
            setState(STATE_PAUSED);
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            pausedByFocusLoss = false;
            setState(STATE_PAUSED);
            stopProgressTicker();
            Log.i(TAG, TAG_SUB + ": pauseForSwitch (no auto-resume on focus gain)");
        } catch (Throwable t) {
            notifyError(t.getMessage() == null ? "pauseForSwitch error" : t.getMessage());
        }
    }

    public void resume() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.start();
            setState(STATE_PLAYING);
            startProgressTicker();
            requestFocus();
        } catch (Throwable t) {
            notifyError(t.getMessage() == null ? "resume error" : t.getMessage());
        }
    }

 /** 设置收藏序列播放模式。favIndexes=收藏歌曲在 playlist 中的下标(升序)。
 * null/空则退出收藏模式。 */
    public void setFavMode(boolean fav, java.util.List<Integer> favIndexes) {
        this.favMode = fav && favIndexes != null && !favIndexes.isEmpty();
        this.favIndexList = null;
        if (this.favMode) {
            int n = favIndexes.size();
            this.favIndexList = new int[n];
            for (int i = 0; i < n; i++) this.favIndexList[i] = favIndexes.get(i);
        }
        Log.i(TAG, TAG_SUB + ": setFavMode=" + this.favMode
                + (this.favIndexList != null ? " seqSize=" + this.favIndexList.length : ""));
    }

    public boolean isFavMode() { return favMode; }

 /** 当前歌曲在收藏序列中的位置(不在序列返回 -1)。 */
    private int favSeqPos(int index) {
        if (favIndexList == null || index < 0) return -1;
        for (int i = 0; i < favIndexList.length; i++) {
            if (favIndexList[i] == index) return i;
        }
        return -1;
    }

 /** 收藏序列下一首下标(序列末尾返回 -1)。 */
    private int favNextIndex() {
        int pos = favSeqPos(currentIndex);
        if (pos < 0 || pos >= favIndexList.length - 1) return -1;
        return favIndexList[pos + 1];
    }

 /** 收藏序列上一首下标(序列首返回 -1=重播当前)。 */
    private int favPrevIndex() {
        int pos = favSeqPos(currentIndex);
        if (pos <= 0) return -1;
        return favIndexList[pos - 1];
    }

 /** 按播放模式切下一首。 */
    public void next() {
        if (playlist == null || playlist.isEmpty()) return;
        // 收藏序列模式——按收藏列表顺序切下一首
        if (favMode && favIndexList != null) {
            if (playMode == MODE_SINGLE_LOOP) { play(currentIndex); return; }
            if (playMode == MODE_RANDOM) {
                if (favIndexList.length <= 1) { play(currentIndex); return; }
                int ri;
                do { ri = random.nextInt(favIndexList.length); } while (favIndexList[ri] == currentIndex);
                play(favIndexList[ri]);
                return;
            }
            int ni = favNextIndex();
            // 收藏序列末尾循环回第一首
            play(ni < 0 ? favIndexList[0] : ni);
            return;
        }
        switch (playMode) {
            case MODE_SINGLE_LOOP:
                // 单曲循环：重播当前
                play(currentIndex);
                break;
            case MODE_RANDOM: {
                int nextIdx;
                if (playlist.size() <= 1) {
                    nextIdx = currentIndex;
                } else {
                    do {
                        nextIdx = random.nextInt(playlist.size());
                    } while (nextIdx == currentIndex);
                }
                play(nextIdx);
                break;
            }
            case MODE_SEQUENCE:
            default: {
                // 顺序播放末尾循环回第一首(用户:播到最后一首不要直接停)
                if (currentIndex >= playlist.size() - 1) {
                    play(0);
                } else {
                    play(currentIndex + 1);
                }
                break;
            }
        }
    }

 /** 上一首。顺序模式：0 索引时重播当前；其他模式：随机/顺序减一。 */
    public void prev() {
        if (playlist == null || playlist.isEmpty()) return;
        // 收藏序列模式——按收藏列表顺序切上一首(序列首重播当前)
        if (favMode && favIndexList != null) {
            if (playMode == MODE_RANDOM) {
                if (favIndexList.length <= 1) { play(currentIndex); return; }
                int ri;
                do { ri = random.nextInt(favIndexList.length); } while (favIndexList[ri] == currentIndex);
                play(favIndexList[ri]);
                return;
            }
            int pi = favPrevIndex();
            play(pi < 0 ? currentIndex : pi);
            return;
        }
        if (playMode == MODE_RANDOM) {
            int nextIdx;
            if (playlist.size() <= 1) {
                nextIdx = currentIndex;
            } else {
                do {
                    nextIdx = random.nextInt(playlist.size());
                } while (nextIdx == currentIndex);
            }
            play(nextIdx);
            return;
        }
        // 顺序/单曲循环：第 0 首重播，其余减一
        if (currentIndex <= 0) {
            play(0);
        } else {
            play(currentIndex - 1);
        }
    }

    public void seekTo(long pos) {
        if (mediaPlayer == null) return;
        // ★ (Bug#72H):记录用户 seek 时间,看门狗 2s 内不回绕误判
        lastSeekAt = System.currentTimeMillis();
        try {
            mediaPlayer.seekTo((int) pos);
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": seekTo error: " + t.getMessage());
        }
    }

 /** 释放 MediaPlayer 与焦点。 */
    public void release() {
        stopProgressTicker();
        releasePlayer();
        abandonFocus();
        pausedByFocusLoss = false;
        playState = STATE_IDLE;
        currentIndex = -1;
    }

    // ================= 内部 =================

 /** 播放完成后的自动推进（按播放模式）。 */
    private void autoAdvance() {
        if (playlist == null || playlist.isEmpty()) return;
        // 收藏序列模式——自动下一首按收藏列表顺序
        if (favMode && favIndexList != null) {
            if (playMode == MODE_SINGLE_LOOP) { play(currentIndex); return; }
            if (playMode == MODE_RANDOM) {
                if (favIndexList.length <= 1) { play(currentIndex); return; }
                int ri;
                do { ri = random.nextInt(favIndexList.length); } while (favIndexList[ri] == currentIndex);
                play(favIndexList[ri]);
                return;
            }
            int ni = favNextIndex();
            // 收藏序列末尾循环回第一首
            play(ni < 0 ? favIndexList[0] : ni);
            return;
        }
        switch (playMode) {
            case MODE_SINGLE_LOOP:
                play(currentIndex);
                break;
            case MODE_RANDOM: {
                int nextIdx;
                if (playlist.size() <= 1) {
                    nextIdx = currentIndex;
                } else {
                    do {
                        nextIdx = random.nextInt(playlist.size());
                    } while (nextIdx == currentIndex);
                }
                play(nextIdx);
                break;
            }
            case MODE_SEQUENCE:
            default:
                if (currentIndex < playlist.size() - 1) {
                    play(currentIndex + 1);
                } else {
                    // 末尾循环回第一首
                    play(0);
                }
                break;
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
    }

    private void setState(int state) {
        this.playState = state;
        Log.i(TAG, TAG_SUB + ": setState=" + state + " currentIndex=" + currentIndex + " seq=" + playSequence);
        notifyStateChanged(state);
    }

    private void startProgressTicker() {
        stopProgressTicker();
        mainHandler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS);
    }

    private void stopProgressTicker() {
        mainHandler.removeCallbacks(progressRunnable);
    }

 /** 请求音频焦点,返回是否成功拿到(AUDIOFOCUS_REQUEST_GRANTED)。 */
    private boolean requestFocus() {
        try {
            if (audioManager != null) {
                int r = audioManager.requestAudioFocus(usbFocusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Throwable t) {
            Log.w(TAG, TAG_SUB + ": requestAudioFocus failed: " + t.getMessage());
        }
        return false;
    }

    private void abandonFocus() {
        try {
            if (audioManager != null) {
                audioManager.abandonAudioFocus(usbFocusListener);
            }
        } catch (Throwable ignored) {}
    }

 /**
 * 处理音频焦点变化（参考 CarMediaService.btFocusListener 模式）。
 *
 * 车机场景：
 * - AUDIOFOCUS_LOSS（永久丢失，如其他 App 强占）→ 暂停播放
 * - AUDIOFOCUS_LOSS_TRANSIENT（临时丢失，如电话/导航播报）→ 暂停播放
 * - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK（可降低音量）→ 车机场景直接暂停
 * - AUDIOFOCUS_GAIN（恢复焦点）→ 若之前因焦点丢失而暂停，则自动恢复播放
 */
    private void handleAudioFocusChange(int focusChange) {
        Log.i(TAG, TAG_SUB + ": USB focus change: " + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 永久丢失焦点：暂停，不自动恢复
                Log.i(TAG, TAG_SUB + ": focus LOSS (永久丢失)，暂停播放");
                pausedByFocusLoss = true;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 临时丢失（电话/导航）：暂停，焦点恢复后自动继续
                Log.i(TAG, TAG_SUB + ": focus LOSS_TRANSIENT (临时丢失)，暂停播放");
                pausedByFocusLoss = true;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 可降低音量：车机场景直接暂停（比降低音量体验更好）
                Log.i(TAG, TAG_SUB + ": focus LOSS_TRANSIENT_CAN_DUCK，暂停播放");
                pausedByFocusLoss = true;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // 恢复焦点：如果之前因焦点丢失而暂停，则恢复播放
                if (pausedByFocusLoss && mediaPlayer != null) {
                    Log.i(TAG, TAG_SUB + ": focus GAIN (恢复)，继续播放");
                    pausedByFocusLoss = false;
                    resume();
                }
                break;
            default:
                break;
        }
    }

    // ================= 回调分发（主线程） =================

    private void notifyStateChanged(final int state) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onPlayStateChanged(state); }
        });
    }

    private void notifyProgress(final long position, final long duration) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onProgressChanged(position, duration); }
        });
    }

    private void notifyCompletion() {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onCompletion(); }
        });
    }

    private void notifyError(final String msg) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override public void run() { callback.onError(msg); }
        });
    }

 /**
 * 播放器状态回调。所有方法均在主线程回调。
 */
    public interface PlayerCallback {
        void onPlayStateChanged(int state);
        void onProgressChanged(long position, long duration);
        void onCompletion();
        void onError(String msg);
    }
}
