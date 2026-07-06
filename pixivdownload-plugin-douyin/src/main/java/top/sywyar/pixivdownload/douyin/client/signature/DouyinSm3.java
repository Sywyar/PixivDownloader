package top.sywyar.pixivdownload.douyin.client.signature;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class DouyinSm3 {

    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };
    private static final int TJ_0_15 = 0x79cc4519;
    private static final int TJ_16_63 = 0x7a879d8a;

    private DouyinSm3() {
    }

    static int[] hashToArray(String value) {
        return hashToArray(value.getBytes(StandardCharsets.UTF_8));
    }

    static int[] hashToArray(int[] values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return hashToArray(bytes);
    }

    private static int[] hashToArray(byte[] input) {
        byte[] digest = hash(input);
        int[] result = new int[digest.length];
        for (int i = 0; i < digest.length; i++) {
            result[i] = digest[i] & 0xff;
        }
        return result;
    }

    private static byte[] hash(byte[] input) {
        byte[] padded = pad(input);
        int[] vector = Arrays.copyOf(IV, IV.length);
        for (int offset = 0; offset < padded.length; offset += 64) {
            compress(vector, padded, offset);
        }
        ByteBuffer out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        for (int value : vector) {
            out.putInt(value);
        }
        return out.array();
    }

    private static byte[] pad(byte[] input) {
        long bitLength = (long) input.length * 8L;
        int paddedLength = input.length + 1 + 8;
        int remainder = paddedLength % 64;
        if (remainder != 0) {
            paddedLength += 64 - remainder;
        }
        byte[] padded = Arrays.copyOf(input, paddedLength);
        padded[input.length] = (byte) 0x80;
        ByteBuffer.wrap(padded, padded.length - 8, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(bitLength);
        return padded;
    }

    private static void compress(int[] vector, byte[] block, int offset) {
        int[] w = new int[68];
        int[] w1 = new int[64];
        ByteBuffer buffer = ByteBuffer.wrap(block, offset, 64).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 16; i++) {
            w[i] = buffer.getInt();
        }
        for (int i = 16; i < 68; i++) {
            w[i] = p1(w[i - 16] ^ w[i - 9] ^ rotateLeft(w[i - 3], 15))
                    ^ rotateLeft(w[i - 13], 7)
                    ^ w[i - 6];
        }
        for (int i = 0; i < 64; i++) {
            w1[i] = w[i] ^ w[i + 4];
        }

        int a = vector[0];
        int b = vector[1];
        int c = vector[2];
        int d = vector[3];
        int e = vector[4];
        int f = vector[5];
        int g = vector[6];
        int h = vector[7];
        for (int i = 0; i < 64; i++) {
            int ss1 = rotateLeft(rotateLeft(a, 12) + e + rotateLeft(i < 16 ? TJ_0_15 : TJ_16_63, i), 7);
            int ss2 = ss1 ^ rotateLeft(a, 12);
            int tt1 = ff(i, a, b, c) + d + ss2 + w1[i];
            int tt2 = gg(i, e, f, g) + h + ss1 + w[i];
            d = c;
            c = rotateLeft(b, 9);
            b = a;
            a = tt1;
            h = g;
            g = rotateLeft(f, 19);
            f = e;
            e = p0(tt2);
        }
        vector[0] ^= a;
        vector[1] ^= b;
        vector[2] ^= c;
        vector[3] ^= d;
        vector[4] ^= e;
        vector[5] ^= f;
        vector[6] ^= g;
        vector[7] ^= h;
    }

    private static int ff(int round, int x, int y, int z) {
        return round < 16 ? x ^ y ^ z : (x & y) | (x & z) | (y & z);
    }

    private static int gg(int round, int x, int y, int z) {
        return round < 16 ? x ^ y ^ z : (x & y) | (~x & z);
    }

    private static int p0(int value) {
        return value ^ rotateLeft(value, 9) ^ rotateLeft(value, 17);
    }

    private static int p1(int value) {
        return value ^ rotateLeft(value, 15) ^ rotateLeft(value, 23);
    }

    private static int rotateLeft(int value, int bits) {
        return Integer.rotateLeft(value, bits);
    }
}
