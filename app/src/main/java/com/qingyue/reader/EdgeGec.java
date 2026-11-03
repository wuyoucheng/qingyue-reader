package com.qingyue.reader;

/**
 * 微软 Edge TTS 的 Sec-MS-GEC 令牌（v2 算法，2025 年后生效）。
 *
 * 与旧版（v1，仅 SHA256）不同，v2 令牌格式为：
 *   "&lt;CS64 16 位十六进制&gt;-&lt;自定义 SHA256 64 位十六进制&gt;"
 * 其中 SHA256 是微软改过轮常量的变体，不能用标准 MessageDigest 实现；
 * CS64 是一个 32 位混合函数。两者都必须与参考实现逐位一致，否则服务端返回 403。
 *
 * 纯 Java、无 Android 依赖，便于在 PC 上用 javac 单测与参考实现对拍。
 */
public final class EdgeGec {

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final long WIN_EPOCH = 11644473600L;
    private static final long M = 0xFFFFFFFFL;

    /** 服务端 Date 头与本地时间的偏差（秒），403 时据此校正后重试 */
    private static volatile double clockSkewSeconds = 0.0;

    public static void adjustClockSkew(double seconds) {
        clockSkewSeconds += seconds;
    }

    public static double getClockSkew() {
        return clockSkewSeconds;
    }

    private EdgeGec() {}

    // ---------------------------------------------------------------- SHA-256

    private static final long[] SHA256_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    };

    private static final long[] SHA256_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    };

    private static final long[] SHA256_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L,
    };

    private static final long[] K = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    };

    private static final long SHA256_ADJUSTMENT = 0xC9DC06A7L;
    private static final long SIGMA0_ADJUSTMENT = 0x059C0B80L;
    private static final long SIGMA1_ADJUSTMENT = 0x000B92C0L;

    private static long rol16(long v) {
        v &= M;
        return ((v << 16) | (v >> 16)) & M;
    }

    private static long rotr(long v, int n) {
        v &= M;
        return ((v >> n) | (v << (32 - n))) & M;
    }

    private static long sigma0(long v) {
        return ((v >> 7) ^ (v << 25) ^ (v >> 18) ^ (v << 14) ^ (v >> 3)) & M;
    }

    private static long sigma1(long v) {
        return ((v >> 17) ^ (v << 15) ^ (v >> 19) ^ (v << 13) ^ (v >> 10)) & M;
    }

    private static long[] sha256Rounds(long[] state, long[] words, int firstRound) {
        long a = state[0], b = state[1], c = state[2], d = state[3];
        long e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = firstRound; i < 64; i++) {
            long kk = (firstRound == 14 && i == 14) ? SHA256_ADJUSTMENT : K[i];
            long s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            long ch = g ^ (e & (f ^ g));
            long t1 = (h + s1 + ch + kk + words[i]) & M;
            long s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            long maj = (a & b) | (c & (a | b));
            long t2 = (s0 + maj) & M;
            h = g; g = f; f = e; e = (d + t1) & M;
            d = c; c = b; b = a; a = (t1 + t2) & M;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static void expandSchedule(long[] words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = words[i - 2], y = words[i - 15];
            long s1 = ((x >> 17) ^ (x << 15) ^ (x >> 19) ^ (x << 13) ^ (x >> 10));
            long s0 = ((y >> 7) ^ (y << 25) ^ (y >> 18) ^ (y << 14) ^ (y >> 3));
            words[i] = (s1 + words[i - 7] + s0 + words[i - 16]) & M;
        }
    }

    /** 对应参考实现 _sha256_init：前 7 字节折叠进预计算状态 */
    private static long[] sha256Init(byte[] data) {
        long[] words = new long[64];
        words[14] = ((long) (data[0] & 0xff) << 16) | ((long) (data[1] & 0xff) << 8) | (data[2] & 0xff);
        words[15] = ((long) (data[3] & 0xff) << 24) | ((long) (data[4] & 0xff) << 16)
                | ((long) (data[5] & 0xff) << 8) | (data[6] & 0xff);
        for (int index = 16; index < 30; index++) {
            long value = SHA256_SCHEDULE[index - 16];
            if (index - 2 >= 14) {
                long s1 = sigma1(words[index - 2]);
                if (index == 16) s1 ^= SIGMA1_ADJUSTMENT;
                value += s1;
            }
            if (index - 7 >= 14) value += words[index - 7];
            if (index - 15 >= 14) {
                long s0 = sigma0(words[index - 15]);
                if (index == 29) s0 ^= SIGMA0_ADJUSTMENT;
                value += s0;
            }
            words[index] = value & M;
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - K[14]) & M;
        expandSchedule(words, 31);
        long[] working = sha256Rounds(SHA256_STATE, words, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = (SHA256_INITIAL[i] + working[i]) & M;
        return out;
    }

    /** 微软变体 SHA-256（与标准 SHA-256 结果不同，勿替换成 MessageDigest） */
    public static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("need >= 7 bytes");
        long length = 57L + data.length;
        int zeroPad = (int) (((55 - length) % 64 + 64) % 64);
        byte[] padded = new byte[data.length - 7 + 1 + zeroPad + 8];
        int p = 0;
        System.arraycopy(data, 7, padded, p, data.length - 7);
        p += data.length - 7;
        padded[p++] = (byte) 0x80;
        p += zeroPad;
        long bits = length * 8;
        for (int i = 7; i >= 0; i--) {
            padded[p + i] = (byte) (bits & 0xff);
            bits >>>= 8;
        }
        long[] state = sha256Init(java.util.Arrays.copyOf(data, 7));
        for (int off = 0; off < padded.length; off += 64) {
            long[] words = new long[64];
            for (int i = 0; i < 16; i++) {
                words[i] = ((long) (padded[off + i * 4] & 0xff) << 24)
                        | ((long) (padded[off + i * 4 + 1] & 0xff) << 16)
                        | ((long) (padded[off + i * 4 + 2] & 0xff) << 8)
                        | (padded[off + i * 4 + 3] & 0xff);
            }
            expandSchedule(words, 16);
            long[] working = sha256Rounds(state, words, 0);
            for (int i = 0; i < 8; i++) state[i] = (state[i] + working[i]) & M;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    // ------------------------------------------------------------------- CS64

    private static final long[][] CS64_COEFFICIENTS = {
            {0xE82E91B1L, 0xB32700D0L}, {0x44DE24C6L, 0x88DFF51DL},
            {0x3BEB65BCL, 0x29514038L}, {0xC89C5933L, 0xB328CBDFL},
            {0x8D1EC211L, 0xD405B4D5L}, {0x314EF5D5L, 0x26E55F3CL},
            {0xE92491B1L, 0x607801C6L}, {0xF0A5B9D5L, 0x82B2C1FBL},
            {0x06064CF3L, 0xFA51A3DAL}, {0xDA79FA60L, 0x207CC464L},
            {0xBED20560L, 0x3C3C74F3L}, {0x89F9C9F8L, 0xD2D14B61L},
            {0x35FA38C6L, 0xBB04A2C1L}, {0xCB49E3C6L, 0xC5626167L},
    };
    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJUSTMENT0 = 0xF2F90B1AL;
    private static final long CS64_ADJUSTMENT1 = 0x0B1A0000L;

    private static long[] wordsLe4Even(byte[] data) {
        int pad = (4 - data.length % 4) % 4;
        byte[] d = new byte[data.length + pad];
        System.arraycopy(data, 0, d, 0, data.length);
        int n = d.length / 4;
        if (n % 2 == 1) n += 1;
        long[] out = new long[n];
        for (int i = 0; i < d.length / 4; i++) {
            out[i] = (d[i * 4] & 0xff) | ((long) (d[i * 4 + 1] & 0xff) << 8)
                    | ((long) (d[i * 4 + 2] & 0xff) << 16) | ((long) (d[i * 4 + 3] & 0xff) << 24);
        }
        return out;
    }

    private static long[] cs64Digest(byte[] data, byte[] digest) {
        long keyA = (digest[0] & 0xff) | ((long) (digest[1] & 0xff) << 8)
                | ((long) (digest[2] & 0xff) << 16) | ((long) (digest[3] & 0xff) << 24);
        long keyB = (digest[4] & 0xff) | ((long) (digest[5] & 0xff) << 8)
                | ((long) (digest[6] & 0xff) << 16) | ((long) (digest[7] & 0xff) << 24);
        keyA |= 1;
        keyB |= 1;
        long f0 = (keyA * CS64_FACTOR) & M;
        long f1 = (keyB * CS64_FACTOR) & M;

        java.util.List<Long> terms = new java.util.ArrayList<>();
        java.util.List<Long> offsets = new java.util.ArrayList<>();
        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms.add((CS64_COEFFICIENTS[i][0] * ((i % 2 == 0) ? f0 : f1)) & M);
            offsets.add(CS64_COEFFICIENTS[i][1]);
        }

        byte[] prefixed = new byte[data.length + 1];
        prefixed[0] = 0;
        System.arraycopy(data, 0, prefixed, 1, data.length);
        long[] ws = wordsLe4Even(prefixed);
        for (int index = 0; index < ws.length; index++) {
            long word = ws[index];
            boolean even = (index % 2 == 0);
            long term = word * (even ? keyA : keyB);
            long offset = rol16(word) * (even ? 0xEF0569FBL : 0xC31713DBL);
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * f0;
                offset += CS64_ADJUSTMENT1;
            }
            terms.add(term & M);
            offsets.add(offset & M);
        }

        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index + 1 < terms.size(); index += 2) {
            long x = terms.get(index), y = terms.get(index + 1);
            long t = rol16((offsets.get(index) + x) & M);
            long q = (t * 0x76F50F34L) & M;
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) & M)) & M;
            t = rol16((offsets.get(index + 1) + y) & M);
            q = (t * 0x1881CE3CL) & M;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & M;
            swapAcc = (swapAcc + swapLast) & M;

            state = (state * keyA + x) & M;
            state = (rol16(state) * 0xCF98B111L) & M;
            state = (rol16(state) * 0x87085B9FL) & M;
            state = (rol16(state) * 0x12CEB96DL) & M;
            state = (rol16(state) * 0x257E1D83L) & M;
            acc = (acc + state) & M;
            long t2 = (state * keyB + y) & M;
            t2 = (rol16(t2) * 0xA27416F5L) & M;
            t2 = (rol16(t2) * 0xD38396FFL) & M;
            state = (rol16((rol16(t2) * 0x7C932B89L) & M) * 0xBFA49F69L) & M;
            acc = (acc + state) & M;
        }
        return new long[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static String hexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02X", x));
        return sb.toString();
    }

    private static String cs64Hex(long[] pair) {
        StringBuilder sb = new StringBuilder(16);
        for (long w : pair) {
            sb.append(String.format(java.util.Locale.US, "%02X", w & 0xff));
            sb.append(String.format(java.util.Locale.US, "%02X", (w >> 8) & 0xff));
            sb.append(String.format(java.util.Locale.US, "%02X", (w >> 16) & 0xff));
            sb.append(String.format(java.util.Locale.US, "%02X", (w >> 24) & 0xff));
        }
        return sb.toString();
    }

    /** 生成 v2 格式的 Sec-MS-GEC 令牌 */
    public static String generateSecMsGec(long unixMillis) {
        double unix = unixMillis / 1000.0 + clockSkewSeconds;
        double ticks = unix + WIN_EPOCH;
        ticks -= ticks % 300.0;
        String strToHash = String.format(java.util.Locale.US, "%.0f", ticks * 1e7) + TRUSTED_CLIENT_TOKEN;
        byte[] data = strToHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        String cs64 = cs64Hex(cs64Digest(data, digest));
        return cs64 + "-" + hexUpper(digest);
    }
}
