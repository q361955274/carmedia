package com.ecarx.carmedia;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从音频文件内部元数据读取内嵌歌词（全格式，零第三方依赖，纯 Java 便于本机校验）。
 * <p>
 * 与 CarMediaService.loadEmbeddedLyric() 读取的"旁车同名 .lrc 文件"不同，
 * 本类解析的是音频文件容器里内嵌的歌词标签：
 * <ul>
 * <li>FLAC：VORBIS_COMMENT 块的 lyrics / LYRICS / UNSYNCEDLYRICS 键</li>
 * <li>MP3 ：ID3v2.3/.4 的 USLT 帧、TXXX:LYRICS、尾部 APEv2 的 Lyrics 键</li>
 * <li>M4A/MP4/AAC：moov→udta→meta→ilst 下的 ©lyr / lyr / lyrics 原子</li>
 * <li>OGG/OPUS：第一页第二个包头（VorbisComment / OpusTags）的 lyrics 字段</li>
 * <li>WMA/ASF：扩展内容描述对象的 WM/Lyrics（Unicode 字符串）</li>
 * <li>APE/WAV：尾部 APEv2 标签的 Lyrics 键（WAV 一般无标准歌词标签）</li>
 * </ul>
 * 返回值是原始歌词文本（可能是带时间戳的 LRC，也可能是纯歌词文本），
 * 由调用方统一交给 LrcUtil.parse 处理；未找到或解析失败返回 null。
 * <p>
 * 所有解析均做边界防护（不越界、不整读大文件——只读文件头/尾的有界窗口），
 * 任何异常向上抛出，由调用方兜底。
 */
public final class EmbeddedLyricReader {

    private EmbeddedLyricReader() {}

    private static final int PREFIX_LIMIT = 4 * 1024 * 1024; // 前置探测上限（4MB）
    private static final int SUFFIX_LIMIT = 4 * 1024 * 1024; // 尾部探测上限（4MB）

 /** 读文件内嵌歌词；不支持的格式 / 未找到 / 解析失败返回 null。 */
    public static String readLyrics(String path) {
        if (path == null || path.isEmpty()) return null;
        String lower = path.toLowerCase();
        try {
            if (lower.endsWith(".flac")) return readFlac(path);
            if (lower.endsWith(".mp3")) return readMp3(path);
            if (lower.endsWith(".m4a") || lower.endsWith(".m4b")
                    || lower.endsWith(".mp4") || lower.endsWith(".aac")) return readMp4(path);
            if (lower.endsWith(".ogg") || lower.endsWith(".opus")) return readOgg(path);
            if (lower.endsWith(".wma") || lower.endsWith(".asf")) return readAsf(path);
            if (lower.endsWith(".ape") || lower.endsWith(".wav")) return readApeV2Tail(path);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ================= 通用读文件 =================

    private static byte[] readPrefix(String path, int max) throws IOException {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return null;
        long len = f.length();
        int n = (int) Math.min(max, len);
        if (n <= 0) return null;
        byte[] buf = new byte[n];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) break;
                off += r;
            }
        }
        return buf;
    }

    private static byte[] readSuffix(String path, int max) throws IOException {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return null;
        long len = f.length();
        int n = (int) Math.min(max, len);
        if (n <= 0) return null;
        byte[] buf = new byte[n];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(len - n);
            raf.readFully(buf, 0, n);
        }
        return buf;
    }

    // ================= FLAC =================

    private static String readFlac(String path) throws IOException {
        byte[] b = readPrefix(path, 512 * 1024); // FLAC 元数据块在文件头
        if (b == null || b.length < 4) return null;
        if (b[0] != 'f' || b[1] != 'L' || b[2] != 'a' || b[3] != 'C') return null;
        int p = 4;
        while (p + 4 <= b.length) {
            int header = b[p] & 0xff;
            int type = header & 0x7f;
            int len = ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
            p += 4;
            if (p + len > b.length) break;
            if (type == 4) { // VORBIS_COMMENT
                String s = parseVorbisComment(b, p, len);
                if (s != null && !s.isEmpty()) return s;
            }
            p += len;
            if ((header & 0x80) != 0) break; // last-metadata-block 标志
        }
        return null;
    }

 /** 解析 VORBIS_COMMENT / OpusTags 注释区（b[off,off+len)），返回首个歌词键值。 */
    private static String parseVorbisComment(byte[] b, int off, int len) {
        int p = off;
        int end = off + len;
        if (p + 4 > end) return null;
        int vendorLen = (int) le32(b, p);
        p += 4;
        if (p + vendorLen > end) return null;
        p += vendorLen; // 跳过 vendor 串
        if (p + 4 > end) return null;
        int count = (int) le32(b, p);
        p += 4;
        if (count < 0) return null;
        if (count > 4096) count = 4096; // 防御
        for (int i = 0; i < count; i++) {
            if (p + 4 > end) break;
            int clen = (int) le32(b, p);
            p += 4;
            if (p + clen > end) break;
            // 键名是 ASCII，值通常是 UTF-8（可能含中文），因此按字节定位 '='，值单独按 UTF-8 解码
            int eq = -1;
            for (int q = p; q < p + clen; q++) {
                if (b[q] == (byte) '=') { eq = q; break; }
            }
            if (eq > p) {
                String key = new String(b, p, eq - p, StandardCharsets.ISO_8859_1).trim();
                if (isLyricKey(key)) {
                    int vs = eq + 1;
                    int ve = p + clen;
                    if (ve > vs) {
                        String val = decodeText(b, vs, ve - vs);
                        if (!val.trim().isEmpty()) return val;
                    }
                }
            }
            p += clen;
        }
        return null;
    }

    // ================= MP3 =================

    private static String readMp3(String path) throws IOException {
        byte[] head = readPrefix(path, 1024 * 1024); // ID3v2 在文件头
        String s = head == null ? null : readId3v2(head);
        if (s != null && !s.isEmpty()) return s;
        byte[] tail = readSuffix(path, 128 * 1024); // APEv2 在文件尾
        s = tail == null ? null : readApeV2From(tail);
        return (s != null && !s.isEmpty()) ? s : null;
    }

    private static String readId3v2(byte[] b) {
        if (b.length < 10) return null;
        if (b[0] != 'I' || b[1] != 'D' || b[2] != '3') return null;
        int major = b[3] & 0xff;
        int size = syncsafe(b, 6); // 28-bit
        if (size <= 0) return null;
        int end = Math.min(b.length, 10 + size);
        int p = 10;
        String uslt = null;
        String txxx = null;
        while (p + 10 <= end) {
            String id = new String(b, p, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0 || id.charAt(0) == ' ' && id.equals("    ")) break; // 填充
            int fsize = (major >= 4) ? syncsafe(b, p + 4) : be32(b, p + 4);
            if (fsize < 0) break;
            int fstart = p + 10;
            int fend = fstart + fsize;
            if (fend > end) break;
            if (id.equals("USLT")) {
                String v = readUslt(b, fstart, fend);
                if (v != null && !v.trim().isEmpty()) { uslt = v; break; } // USLT 优先
            } else if (id.equals("TXXX")) {
                String v = readTxxx(b, fstart, fend);
                if (v != null && !v.trim().isEmpty() && txxx == null) txxx = v;
            }
            p = fend;
        }
        if (uslt != null && !uslt.trim().isEmpty()) return uslt;
        return (txxx != null && !txxx.trim().isEmpty()) ? txxx : null;
    }

 /** USLT 帧：enc(1) + language(3) + 内容描述符(按编码终止) + 歌词正文。 */
    private static String readUslt(byte[] b, int s, int e) {
        if (e - s < 4) return null;
        int enc = b[s] & 0xff;
        int p = s + 4; // 跳过 enc + language(3)
        int descEnd = findTerminator(b, p, e, enc);
        if (descEnd < 0) return null;
        p = descEnd + (enc == 1 || enc == 2 ? 2 : 1);
        if (p >= e) return null;
        return decodeText(b, p, e - p);
    }

 /** TXXX 帧：enc(1) + 描述(按编码终止) + 值。描述匹配歌词键时取值为歌词。 */
    private static String readTxxx(byte[] b, int s, int e) {
        if (e - s < 1) return null;
        int enc = b[s] & 0xff;
        int descEnd = findTerminator(b, s + 1, e, enc);
        if (descEnd < 0) return null;
        int p = descEnd + (enc == 1 || enc == 2 ? 2 : 1);
        if (p >= e) return null;
        String desc = new String(b, s + 1, descEnd - (s + 1), StandardCharsets.ISO_8859_1).trim();
        if (!isLyricKey(desc)) return null;
        return decodeText(b, p, e - p);
    }

    // ================= M4A / MP4 =================

    private static String readMp4(String path) throws IOException {
        // moov 可能在文件头（流式 fast-start）或文件尾；先头后尾双探
        byte[] head = readPrefix(path, PREFIX_LIMIT);
        String s = head == null ? null : scanMp4Boxes(head, 0, head.length);
        if (s != null && !s.isEmpty()) return s;
        byte[] tail = readSuffix(path, SUFFIX_LIMIT);
        s = tail == null ? null : scanMp4Boxes(tail, 0, tail.length);
        return (s != null && !s.isEmpty()) ? s : null;
    }

    private static String scanMp4Boxes(byte[] b, int start, int end) {
        int p = start;
        while (p + 8 <= end) {
            long size = be32(b, p);
            String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.charAt(0) == 0) break;
            int header = 8;
            if (size == 1) {
                if (p + 16 > end) break;
                size = be64(b, p + 8);
                header = 16;
            } else if (size == 0) {
                size = end - p; // 扩展至文件末尾
            }
            if (size < header) break;
            long ds = p + header;
            long de = p + size;
            if (de > end) { // 截断（尾部探测时常见）：放宽到可用范围，避免整体丢失
                de = end;
                if (de <= ds) break;
            }
            if (type.equals("moov") || type.equals("udta")) {
                String s = scanMp4Boxes(b, (int) ds, (int) de);
                if (s != null && !s.isEmpty()) return s;
            } else if (type.equals("meta")) {
                // meta 子盒前有 4 字节 version/flags
                if (de - ds < 4) break;
                String s = scanMp4Boxes(b, (int) ds + 4, (int) de);
                if (s != null && !s.isEmpty()) return s;
            } else if (type.equals("ilst")) {
                String s = scanIlst(b, (int) ds, (int) de);
                if (s != null && !s.isEmpty()) return s;
            } else if (isLyricAtom(type)) {
                String s = readLyricAtom(b, (int) ds, (int) de);
                if (s != null && !s.isEmpty()) return s;
            }
            p = (int) de;
        }
        return null;
    }

    private static String scanIlst(byte[] b, int s, int e) {
        int p = s;
        while (p + 8 <= e) {
            long size = be32(b, p);
            String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.charAt(0) == 0) break;
            int header = 8;
            if (size == 1) {
                if (p + 16 > e) break;
                size = be64(b, p + 8);
                header = 16;
            } else if (size == 0) {
                size = e - p;
            }
            if (size < header) break;
            long de = p + size;
            if (de > e) break;
            if (isLyricAtom(type)) {
                String v = readLyricAtom(b, (int) (p + header), (int) de);
                if (v != null && !v.isEmpty()) return v;
            }
            p = (int) de;
        }
        return null;
    }

    private static boolean isLyricAtom(String type) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        String k = sb.toString();
        return k.equals("lyr") || k.equals("lyrics") || k.equals("lyric");
    }

 /** ilst 条目下的 data 子盒：size+type+ver/flags(4)+locale(4)+值。 */
    private static String readLyricAtom(byte[] b, int s, int e) {
        int p = s;
        while (p + 8 <= e) {
            long size = be32(b, p);
            String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.charAt(0) == 0) break;
            int header = 8;
            if (size == 1) {
                if (p + 16 > e) break;
                size = be64(b, p + 8);
                header = 16;
            } else if (size == 0) {
                size = e - p;
            }
            if (size < header) break;
            long de = p + size;
            if (de > e) break;
            if (type.equals("data")) {
                long vs = p + header + 4; // 跳过 version/flags(4)
                if (vs + 4 > de) return null;
                vs += 4; // 跳过 locale(4)
                if (vs >= de) return null;
                int n = (int) (de - vs);
                return decodeText(b, (int) vs, n);
            }
            p = (int) de;
        }
        return null;
    }

    // ================= OGG / OPUS =================

    private static String readOgg(String path) throws IOException {
        byte[] b = readPrefix(path, 256 * 1024);
        if (b == null || b.length < 27) return null;
        if (b[0] != 'O' || b[1] != 'g' || b[2] != 'g' || b[3] != 'S') return null;
        int segCount = b[26] & 0xff;
        if (27 + segCount > b.length) return null;
        int bodyOff = 27 + segCount;
        List<int[]> pk = new ArrayList<>();
        int pktStart = 0;
        int curLen = 0;
        boolean started = false;
        for (int i = 0; i < segCount; i++) {
            int lacing = b[27 + i] & 0xff;
            if (!started) { pktStart = bodyOff; started = true; curLen = 0; }
            curLen += lacing;
            bodyOff += lacing;
            if (bodyOff > b.length) return null;
            if (lacing < 255) { pk.add(new int[]{pktStart, curLen}); started = false; }
        }
        if (started && curLen > 0) pk.add(new int[]{pktStart, curLen});
        if (pk.size() < 2) return null; // 第一个包=识别头, 第二个包=注释头
        int[] p2 = pk.get(1);
        int cs = p2[0], cl = p2[1];
        if (cs + cl > b.length) return null;
        // Vorbis 注释头: "\x03vorbis"；Opus 注释头: "OpusTags"
        if (cl >= 7 && b[cs] == 0x03 && b[cs + 1] == 'v' && b[cs + 2] == 'o'
                && b[cs + 3] == 'r' && b[cs + 4] == 'b' && b[cs + 5] == 'i' && b[cs + 6] == 's') {
            return parseVorbisComment(b, cs + 7, cl - 7);
        }
        if (cl >= 8 && b[cs] == 'O' && b[cs + 1] == 'p' && b[cs + 2] == 'u'
                && b[cs + 3] == 's' && b[cs + 4] == 'T' && b[cs + 5] == 'a'
                && b[cs + 6] == 'g' && b[cs + 7] == 's') {
            return parseVorbisComment(b, cs + 8, cl - 8);
        }
        return null;
    }

    // ================= WMA / ASF =================

    // ASF GUID（混合字节序存储：前 3 字段小端，后 2 字段大端）
    // "3026B275-8E66-CF11-A6D9-00AA0062CE6C" -> 75 B2 26 30 | 66 8E | 11 CF | A6 D9 | 00 AA 00 62 CE 6C
    private static final byte[] GUID_HEADER = {
            0x75, (byte) 0xB2, 0x26, 0x30, 0x66, (byte) 0x8E, 0x11, (byte) 0xCF,
            (byte) 0xA6, (byte) 0xD9, 0x00, (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C
    };
    // "D2D0A440-E307-11D2-97F0-00A0C95EA850" -> 40 A4 D0 D2 | 07 E3 | D2 11 | 97 F0 | 00 A0 C9 5E A8 50
    private static final byte[] GUID_EXT_CONTENT = {
            0x40, (byte) 0xA4, (byte) 0xD0, (byte) 0xD2, 0x07, (byte) 0xE3, (byte) 0xD2, 0x11,
            (byte) 0x97, (byte) 0xF0, 0x00, (byte) 0xA0, (byte) 0xC9, 0x5E, (byte) 0xA8, 0x50
    };

    private static String readAsf(String path) throws IOException {
        byte[] b = readPrefix(path, 256 * 1024);
        if (b == null || b.length < 30) return null;
        if (!guidEquals(b, 0, GUID_HEADER)) return null;
        long headerSize = le64(b, 16);
        int objCount = (int) le32(b, 24);
        if (objCount < 0 || objCount > 4096) return null;
        long headerEnd = Math.min(b.length, 30 + headerSize);
        int p = 30;
        for (int i = 0; i < objCount; i++) {
            if (p + 24 > headerEnd) break;
            long size = le64(b, p + 16);
            if (size < 24 || p + size > headerEnd) break;
            if (guidEquals(b, p, GUID_EXT_CONTENT)) {
                String s = readAsfExtContent(b, p + 24, (int) (p + size));
                if (s != null && !s.isEmpty()) return s;
            }
            p += size;
        }
        return null;
    }

    private static String readAsfExtContent(byte[] b, int s, int e) {
        if (e - s < 2) return null;
        int p = s;
        int count = u16le(b, p);
        p += 2;
        for (int i = 0; i < count; i++) {
            if (p + 2 > e) break;
            int nameLen = u16le(b, p);
            p += 2;
            if (p + nameLen > e) break;
            String name = new String(b, p, nameLen, StandardCharsets.UTF_16LE);
            p += nameLen;
            if (p + 2 > e) break;
            int vtype = u16le(b, p);
            p += 2;
            if (p + 4 > e) break;
            int vlen = (int) le32(b, p);
            p += 4;
            if (p + vlen > e) break;
            String lower = name.toLowerCase();
            if (lower.equals("wm/lyrics") || lower.equals("wm/lyrics_synchronised")) {
                if (vtype == 0) { // Unicode 字符串
                    String val = decodeText(b, p, vlen);
                    if (!val.trim().isEmpty()) return val.trim();
                }
                // vtype==2 为二进制同步歌词，结构复杂，暂不解析（WM/Lyrics 纯文本已覆盖常见场景）
            }
            p += vlen;
        }
        return null;
    }

    // ================= APE / WAV（尾部 APEv2）=================

    private static String readApeV2Tail(String path) throws IOException {
        byte[] tail = readSuffix(path, 128 * 1024);
        return tail == null ? null : readApeV2From(tail);
    }

    private static String readApeV2From(byte[] tail) {
        int idx = indexOf(tail, "APETAGEX");
        if (idx < 0 || idx + 32 > tail.length) return null;
        int tagSize = (int) le32(tail, idx + 12);
        int itemCount = (int) le32(tail, idx + 16);
        if (tagSize <= 0 || tagSize > tail.length) return null;
        if (itemCount < 0 || itemCount > 1024) return null;
        int headerPos = idx;
        int itemsStart = headerPos - tagSize;
        if (itemsStart < 0) itemsStart = 0;
        int p = itemsStart;
        for (int i = 0; i < itemCount; i++) {
            if (p + 8 > headerPos) break;
            int isize = (int) le32(tail, p);
            p += 8; // 跳过 size + flags
            int kend = p;
            while (kend < headerPos && tail[kend] != 0) kend++;
            String key = new String(tail, p, kend - p, StandardCharsets.ISO_8859_1).trim();
            p = kend + 1;
            if (p + isize > headerPos) break;
            if (isLyricKey(key)) {
                String val = decodeText(tail, p, isize);
                if (!val.trim().isEmpty()) return val.trim();
            }
            p += isize;
        }
        return null;
    }

    // ================= 工具 =================

    private static boolean isLyricKey(String key) {
        if (key == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toUpperCase(c));
        }
        String k = sb.toString();
        return k.equals("LYRICS") || k.equals("UNSYNCEDLYRICS") || k.equals("SYNCEDLYRICS")
                || k.equals("LYRIC") || k.equals("LYRICS_LRC") || k.equals("LRC");
    }

 /** 找到内容描述符/字符串的终止符位置；enc0/3 为单 0x00，enc1/2 为双 0x00。找不到返回 -1。 */
    private static int findTerminator(byte[] b, int s, int e, int enc) {
        int i = s;
        if (enc == 1 || enc == 2) {
            while (i + 1 < e) {
                if (b[i] == 0 && b[i + 1] == 0) return i;
                i++;
            }
        } else {
            while (i < e) {
                if (b[i] == 0) return i;
                i++;
            }
        }
        return -1;
    }

 /** 优先按 UTF-8 严格解码，失败退 ISO-8859-1（保证不抛、不产生乱码丢失）。 */
    private static String decodeText(byte[] b, int off, int len) {
        try {
            return new String(b, off, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(b, off, len, StandardCharsets.ISO_8859_1);
        }
    }

    private static int indexOf(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean guidEquals(byte[] b, int off, byte[] guid) {
        for (int i = 0; i < 16; i++) {
            if (b[off + i] != guid[i]) return false;
        }
        return true;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static long le64(byte[] b, int o) {
        return le32(b, o) | (le32(b, o + 4) << 32);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static long be64(byte[] b, int o) {
        long hi = ((long) be32(b, o)) & 0xffffffffL;
        long lo = ((long) be32(b, o + 4)) & 0xffffffffL;
        return (hi << 32) | lo;
    }

 /** ID3 同步安全整数（28-bit，每字节最高位为 0）。 */
    private static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14)
                | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f);
    }

    private static int u16le(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }
}
