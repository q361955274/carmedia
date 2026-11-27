package com.ecarx.carmedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LRC 歌词解析工具（统一版）。
 * <p>
 * 供所有歌词源共用：QQMusicLyricFetcher / CloudLyricFetcher / 未来的酷狗/酷我/汽水等。
 * <p>
 * 功能：
 * - parse()：解析 LRC 文本 → 按时间戳排序的歌词行列表
 * - getCurrentLine()：按播放进度取当前歌词行（空白行回退到最近非空行）
 * - getNextLine()：按播放进度取下一句非空歌词行
 * <p>
 * 铁律（用户多次强调）：parse 只过滤空行和空白时间戳行；禁止过滤任何有文本的行
 * （包括作词/作曲/歌名等元数据标签行），有文本就保留。
 */
public final class LrcUtil {

    private LrcUtil() {}

 /** 歌词行：时间戳（毫秒）+ 文本。 */
    public static class Line implements Comparable<Line> {
        public long timeMs;
        public String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public int compareTo(Line o) {
            return Long.compare(this.timeMs, o.timeMs);
        }
    }

 /**
 * 解析 LRC 文本为按时间戳排序的歌词行列表。
 * 支持多时间戳标签（同一行多个 [mm:ss.xxx]），空输入返回空列表。
 * <p>
 * 铁律：只跳过真正空白行（纯时间戳无文本），有文本的行全部保留
 * （包括 [00:00.00]歌名 - 歌手、作词: xxx 等元数据行）。
 */
    public static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return lines;
        }
        String[] rows = lrc.split("\n");
        for (String row : rows) {
            String s = row.trim();
            if (s.isEmpty()) {
                continue;
            }
            int idx = 0;
            while (idx < s.length() && s.charAt(idx) == '[') {
                int end = s.indexOf(']', idx);
                if (end < 0) {
                    break;
                }
                String tag = s.substring(idx + 1, end);
                try {
                    long t = parseTime(tag);
                    String text = s.substring(end + 1).trim();
                    // 跳过空行时间戳（如网易云 LRC 两句之间的空行）：
                    // 若保留，播放进度落在这类时间点 getCurrentLine 会返回空串，
                    // 导致 UI 在两句歌词之间短暂变"无歌词"。
                    // 仅跳过真正空白行，不作词/作曲等有内容行不受影响。
                    if (text.trim().isEmpty()) {
                        idx = end + 1; // 必须推进 idx，否则同一空时间戳死循环（线程卡死→歌词永不绑定）
                        continue;
                    }
                    lines.add(new Line(t, text));
                } catch (Exception ignored) {
                }
                idx = end + 1;
            }
        }
        Collections.sort(lines);
        return lines;
    }

 /**
 * 按播放进度取当前歌词行。
 * 即使 LRC 里残留空行时间戳，也不会在播放到该时间点返回空串——
 * 向前回退到最近一条非空行（双保险，解析层已过滤空行）。
 */
    public static String getCurrentLine(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String line = "";
        for (Line l : lines) {
            if (l.timeMs <= positionMs) {
                if (l.text != null && !l.text.trim().isEmpty()) {
                    line = l.text;
                }
            } else {
                break;
            }
        }
        return line;
    }

 /** 下一句歌词行：当前位置之后第一个非空行；无则空串。 */
    public static String getNextLine(List<Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        for (Line l : lines) {
            if (l.timeMs > positionMs && l.text != null && !l.text.trim().isEmpty()) {
                return l.text;
            }
        }
        return "";
    }

 /** 解析 [mm:ss.xxx] 时间戳为毫秒，非法格式抛 IllegalArgumentException。 */
    private static long parseTime(String s) {
        int colon = s.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("not a time tag");
        }
        String mm = s.substring(0, colon);
        String rest = s.substring(colon + 1);
        int dot = rest.indexOf('.');
        int sec;
        int ms = 0;
        if (dot >= 0) {
            sec = Integer.parseInt(rest.substring(0, dot));
            String msStr = rest.substring(dot + 1);
            while (msStr.length() < 3) {
                msStr = msStr + "0";
            }
            msStr = msStr.substring(0, Math.min(3, msStr.length()));
            ms = Integer.parseInt(msStr);
        } else {
            sec = Integer.parseInt(rest);
        }
        return Long.parseLong(mm) * 60000L + (long) sec * 1000L + ms;
    }
}
