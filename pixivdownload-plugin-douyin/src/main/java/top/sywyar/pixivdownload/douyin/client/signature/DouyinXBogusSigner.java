package top.sywyar.pixivdownload.douyin.client.signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * X-Bogus signer ported from the Apache-licensed Evil0ctal Douyin_TikTok_Download_API implementation
 * used by jiji262/douyin-downloader.
 */
public final class DouyinXBogusSigner {

    private static final int[] HEX = new int[128];
    private static final String CHARACTER = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=";
    private static final byte[] UA_KEY = {0x00, 0x01, 0x0c};
    private static final byte[] X_KEY = {(byte) 0xff};
    private static final int CT = 536_919_696;

    static {
        Arrays.fill(HEX, -1);
        for (int i = 0; i <= 9; i++) {
            HEX['0' + i] = i;
        }
        for (int i = 0; i < 6; i++) {
            HEX['a' + i] = 10 + i;
            HEX['A' + i] = 10 + i;
        }
    }

    private final String userAgent;

    public DouyinXBogusSigner(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                : userAgent;
    }

    public SignedUrl sign(String url) {
        String value = url == null ? "" : url;
        int[] uaMd5Array = md5StrToArray(md5(Base64.getEncoder()
                .encodeToString(rc4Encrypt(UA_KEY, userAgent.getBytes(StandardCharsets.ISO_8859_1)))));
        int[] emptyMd5Array = md5StrToArray(md5(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")));
        int[] urlMd5Array = md5Encrypt(value);
        long timer = Instant.now().getEpochSecond();
        int[] values = {
                64,
                0,
                1,
                12,
                urlMd5Array[14],
                urlMd5Array[15],
                emptyMd5Array[14],
                emptyMd5Array[15],
                uaMd5Array[14],
                uaMd5Array[15],
                (int) ((timer >> 24) & 255),
                (int) ((timer >> 16) & 255),
                (int) ((timer >> 8) & 255),
                (int) (timer & 255),
                (CT >> 24) & 255,
                (CT >> 16) & 255,
                (CT >> 8) & 255,
                CT & 255
        };
        int xor = values[0];
        for (int i = 1; i < values.length; i++) {
            xor ^= values[i];
        }

        List<Integer> array3 = new ArrayList<>();
        List<Integer> array4 = new ArrayList<>();
        for (int i = 0; i < values.length + 1; i += 2) {
            int first = i == values.length ? xor : values[i];
            array3.add(first);
            if (i + 1 < values.length) {
                array4.add(values[i + 1]);
            }
        }
        List<Integer> merged = new ArrayList<>(array3);
        merged.addAll(array4);

        byte[] payload = encodingConversion(merged);
        byte[] garbled = new byte[payload.length + 2];
        garbled[0] = 2;
        garbled[1] = (byte) 255;
        byte[] encrypted = rc4Encrypt(X_KEY, payload);
        System.arraycopy(encrypted, 0, garbled, 2, encrypted.length);

        StringBuilder xb = new StringBuilder();
        for (int i = 0; i < garbled.length; i += 3) {
            xb.append(calculation(garbled[i] & 0xff, garbled[i + 1] & 0xff, garbled[i + 2] & 0xff));
        }
        String separator = value.contains("?") ? "&" : "?";
        String signedUrl = value + separator + "X-Bogus=" + xb;
        return new SignedUrl(signedUrl, xb.toString(), userAgent);
    }

    private static int[] md5StrToArray(String value) {
        String text = value == null ? "" : value;
        if (text.length() > 32) {
            int[] chars = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                chars[i] = text.charAt(i) & 0xff;
            }
            return chars;
        }
        int[] out = new int[text.length() / 2];
        for (int i = 0, j = 0; i + 1 < text.length(); i += 2, j++) {
            int hi = text.charAt(i) < HEX.length ? HEX[text.charAt(i)] : -1;
            int lo = text.charAt(i + 1) < HEX.length ? HEX[text.charAt(i + 1)] : -1;
            out[j] = ((hi < 0 ? 0 : hi) << 4) | (lo < 0 ? 0 : lo);
        }
        return out;
    }

    private static String md5(String input) {
        return md5(md5StrToArray(input));
    }

    private static String md5(int[] input) {
        byte[] bytes = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            bytes[i] = (byte) input[i];
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashed = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest is unavailable", e);
        }
    }

    private static int[] md5Encrypt(String urlPath) {
        return md5StrToArray(md5(md5StrToArray(md5(urlPath))));
    }

    private static byte[] encodingConversion(List<Integer> m) {
        if (m.size() != 19) {
            throw new IllegalArgumentException("X-Bogus payload requires 19 values");
        }
        int[] order = {0, 10, 1, 11, 2, 12, 3, 13, 4, 14, 5, 15, 6, 16, 7, 17, 8, 18, 9};
        byte[] payload = new byte[order.length];
        for (int i = 0; i < order.length; i++) {
            payload[i] = (byte) (m.get(order[i]) & 0xff);
        }
        return payload;
    }

    private static byte[] rc4Encrypt(byte[] key, byte[] data) {
        int[] state = new int[256];
        for (int i = 0; i < 256; i++) {
            state[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + state[i] + (key[i % key.length] & 0xff)) % 256;
            int tmp = state[i];
            state[i] = state[j];
            state[j] = tmp;
        }
        byte[] encrypted = new byte[data.length];
        int i = 0;
        j = 0;
        for (int index = 0; index < data.length; index++) {
            i = (i + 1) % 256;
            j = (j + state[i]) % 256;
            int tmp = state[i];
            state[i] = state[j];
            state[j] = tmp;
            int k = state[(state[i] + state[j]) % 256];
            encrypted[index] = (byte) ((data[index] & 0xff) ^ k);
        }
        return encrypted;
    }

    private static String calculation(int a1, int a2, int a3) {
        int x3 = ((a1 & 255) << 16) | ((a2 & 255) << 8) | (a3 & 255);
        return "" + CHARACTER.charAt((x3 & 16_515_072) >> 18)
                + CHARACTER.charAt((x3 & 258_048) >> 12)
                + CHARACTER.charAt((x3 & 4_032) >> 6)
                + CHARACTER.charAt(x3 & 63);
    }

    public record SignedUrl(String url, String xBogus, String userAgent) {
    }
}
