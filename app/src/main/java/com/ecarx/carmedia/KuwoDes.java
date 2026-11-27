package com.ecarx.carmedia;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * KuwoDes - 酷我自研 DES 变体（非标准 DES），从改版 APK smali 逐表 1:1 移植。
 * <p>
 * 与标准 DES 的差异（均已在 用 mobi.kuwo.cn 实测验证，接口返回 200）：
 * 1. 8 个 S-box 全部为自定义 64 项表（与标准 DES 4x16 不同），按 6 位值直接索引；
 * 2. key / 块数据均为小端字节序组装；
 * 3. 总是多加密一块（余数补零），即最少 2 块；
 * 4. Feistel 末轮不交换 L/R，合块为 (L<<32)|R 后直接做 IP1。
 * javax.crypto 无法等价，必须使用本实现。
 */
public final class KuwoDes {

    private KuwoDes() {
    }

    private static final int[] IP = {57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7,56,48,40,32,24,16,8,0,58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6};
    private static final int[] EEXP = {31,0,1,2,3,4,-1,-1,3,4,5,6,7,8,-1,-1,7,8,9,10,11,12,-1,-1,11,12,13,14,15,16,-1,-1,15,16,17,18,19,20,-1,-1,19,20,21,22,23,24,-1,-1,23,24,25,26,27,28,-1,-1,27,28,29,30,31,30,-1,-1};
    private static final char[] S1 = {14,4,3,15,2,13,5,3,13,14,6,9,11,2,0,5,4,1,10,12,15,6,9,10,1,8,12,7,8,11,7,0,0,15,10,5,14,4,9,10,7,8,12,3,13,1,3,6,15,12,6,11,2,9,5,0,4,2,11,14,1,7,8,13};
    private static final char[] S2 = {15,0,9,5,6,10,12,9,8,7,2,12,3,13,5,2,1,14,7,8,11,4,0,3,14,11,13,6,4,1,10,15,3,13,12,11,15,3,6,0,4,10,1,7,8,4,11,14,13,8,0,6,2,15,9,5,7,1,10,12,14,2,5,9};
    private static final char[] S3 = {10,13,1,11,6,8,11,5,9,4,12,2,15,3,2,14,0,6,13,1,3,15,4,10,14,9,7,12,5,0,8,7,13,1,2,4,3,6,12,11,0,13,5,14,6,8,15,2,7,10,8,15,4,9,11,5,9,0,14,3,10,7,1,12};
    private static final char[] S4 = {7,10,1,15,0,12,11,5,14,9,8,3,9,7,4,8,13,6,2,1,6,11,12,2,3,0,5,14,10,13,15,4,13,3,4,9,6,10,1,12,11,0,2,5,0,13,14,2,8,15,7,4,15,1,10,7,5,6,12,11,3,8,9,14};
    private static final char[] S5 = {2,4,8,15,7,10,13,6,4,1,3,12,11,7,14,0,12,2,5,9,10,13,0,3,1,11,15,5,6,8,9,14,14,11,5,6,4,1,3,10,2,12,15,0,13,2,8,5,11,8,0,15,7,14,9,4,12,7,10,9,1,13,6,3};
    private static final char[] S6 = {12,9,0,7,9,2,14,1,10,15,3,4,6,12,5,11,1,14,13,0,2,8,7,13,15,5,4,10,8,3,11,6,10,4,6,11,7,9,0,6,4,2,13,1,9,15,3,8,15,3,1,14,12,5,11,0,2,12,14,7,5,10,8,13};
    private static final char[] S7 = {4,1,3,10,15,12,5,0,2,11,9,6,8,7,6,9,11,4,12,15,0,3,10,5,14,13,7,8,13,14,1,2,13,6,14,9,4,1,2,14,11,13,5,0,1,10,8,3,0,11,3,5,9,4,15,2,7,8,12,15,10,7,6,12};
    private static final char[] S8 = {13,7,10,0,6,9,5,15,8,4,3,10,11,14,12,5,2,11,9,6,15,12,0,3,4,1,14,13,1,2,7,8,1,2,12,15,10,4,0,3,13,14,6,9,7,8,9,6,15,1,5,12,3,10,14,5,8,7,11,0,4,13,2,11};
    private static final int[] PERM = {15,6,19,20,28,11,27,16,0,14,22,25,4,17,30,9,1,7,23,13,31,26,2,8,18,12,29,5,21,10,3,24};
    private static final int[] IP1 = {39,7,47,15,55,23,63,31,38,6,46,14,54,22,62,30,37,5,45,13,53,21,61,29,36,4,44,12,52,20,60,28,35,3,43,11,51,19,59,27,34,2,42,10,50,18,58,26,33,1,41,9,49,17,57,25,32,0,40,8,48,16,56,24};
    private static final int[] PC1 = {56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3};
    private static final int[] PC2 = {13,16,10,23,0,4,-1,-1,2,27,14,5,20,9,-1,-1,22,18,11,3,25,7,-1,-1,15,6,26,19,12,1,-1,-1,40,51,30,36,46,54,-1,-1,29,39,50,44,32,47,-1,-1,43,48,38,55,33,52,-1,-1,45,41,49,35,28,31,-1,-1};
    private static final char[] SHIFTS = {1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1};
    private static final long[] RMASK = {0L, 1048577L, 3145731L};

    private static final char[][] SBOX = {S1, S2, S3, S4, S5, S6, S7, S8};

    private static long perm(int[] p, int len, long input) {
        long out = 0;
        for (int i = 0; i < len; i++) {
            int v = p[i];
            if (v >= 0 && ((input >>> v) & 1L) != 0) {
                out |= (1L << i);
            }
        }
        return out;
    }

    private static long[] keySchedule(long key, int mode) {
        long c = perm(PC1, 0x38, key);
        long[] ks = new long[16];
        for (int i = 0; i < 16; i++) {
            int sh = SHIFTS[i];
            long mask = RMASK[sh];
            c = ((c & mask) << (0x1c - sh)) | ((c & ~mask) >>> sh);
            ks[i] = perm(PC2, 0x40, c);
        }
        if (mode == 1) {
            for (int i = 0; i < 8; i++) {
                long t = ks[i];
                ks[i] = ks[15 - i];
                ks[15 - i] = t;
            }
        }
        return ks;
    }

    private static long blockEnc(long[] ks, long block) {
        long ip = perm(IP, 0x40, block);
        long l = ip & 0xffffffffL;
        long r = (ip >>> 0x20) & 0xffffffffL;
        for (int i = 0; i < 16; i++) {
            long er = perm(EEXP, 0x40, r) ^ ks[i];
            long s = 0;
            for (int b = 7; b >= 0; b--) {
                long v = (er >>> (b * 8)) & 0xffL;
                int idx = (int) v;
                if (idx > 0x3f) idx = 0x3f;
                s = (s << 4) | (SBOX[b][idx] & 0xfL);
            }
            long f = perm(PERM, 0x20, s);
            long nl = r;
            r = l ^ f;
            l = nl;
        }
        // smali: shl-long v4, v6(l), 0x20; or-long v2, v4 → (L<<32)|R
        return perm(IP1, 0x40, (l << 0x20) | r);
    }

 /** 酷我 DES 加密（ECB、无填充、小端、总是多加密一块）。 */
    public static byte[] encrypt(byte[] data, byte[] key) {
        long k = 0;
        for (int i = 0; i < 8; i++) k |= (key[i] & 0xffL) << (i * 8);
        long[] ks = keySchedule(k, 0);
        int nBlocks = data.length / 8;
        ByteBuffer bb = ByteBuffer.allocate((nBlocks + 1) * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nBlocks; i++) {
            long blk = 0;
            for (int j = 0; j < 8; j++) blk |= (data[i * 8 + j] & 0xffL) << (j * 8);
            bb.putLong(blockEnc(ks, blk));
        }
        long tail = 0;
        int rem = data.length - nBlocks * 8;
        for (int i = 0; i < rem; i++) tail |= (data[nBlocks * 8 + i] & 0xffL) << (i * 8);
        bb.putLong(blockEnc(ks, tail));
        return bb.array();
    }
}
