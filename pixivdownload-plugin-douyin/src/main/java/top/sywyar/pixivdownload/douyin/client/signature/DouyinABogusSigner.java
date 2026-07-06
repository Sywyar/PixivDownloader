package top.sywyar.pixivdownload.douyin.client.signature;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class DouyinABogusSigner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
    private static final int[] SORT_INDEX = {
            18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35, 57, 39, 41, 43, 22, 28,
            32, 60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71
    };
    private static final int[] SORT_INDEX_2 = {
            18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28, 32, 36, 23, 29, 33, 37,
            44, 45, 46, 47, 48, 49, 50, 24, 25, 52, 53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71
    };
    private static final byte[] UA_KEY = {0x00, 0x01, 0x0e};
    private static final String[] ALPHABETS = {
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe",
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
    };
    private static final int[] BIG_ARRAY = {
            121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78, 101, 250, 207, 198, 50,
            139, 227, 220, 105, 97, 143, 34, 28, 194, 215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120,
            247, 45, 90, 11, 27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
            178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147, 122, 129, 65, 40, 88, 150,
            110, 219, 199, 255, 181, 254, 48, 4, 195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104,
            96, 83, 80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1, 188, 164, 210, 237,
            222, 98, 212, 77, 253, 42, 170, 202, 26, 22, 29, 182, 251, 10, 173, 152, 58, 138, 54, 141,
            185, 33, 157, 31, 252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82, 128, 109,
            57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214, 230, 174, 232, 63, 52, 205, 86, 140,
            66, 175, 111, 171, 246, 133, 238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
            213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168, 53, 21, 183, 41, 67, 85,
            224, 155, 226, 242, 87, 177, 146, 70, 190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59,
            9, 94, 179, 107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156
    };

    private final String userAgent;

    public DouyinABogusSigner(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
    }

    public String signQuery(String query) {
        String fingerprint = generateChromeFingerprint();
        String abogus = new Generator(userAgent, fingerprint).generate(query == null ? "" : query, "");
        return query + "&a_bogus=" + abogus;
    }

    private static String generateChromeFingerprint() {
        int innerWidth = randomBetween(1024, 1920);
        int innerHeight = randomBetween(768, 1080);
        int outerWidth = innerWidth + randomBetween(24, 32);
        int outerHeight = innerHeight + randomBetween(75, 90);
        int screenY = RANDOM.nextBoolean() ? 0 : 30;
        int sizeWidth = randomBetween(1024, 1920);
        int sizeHeight = randomBetween(768, 1080);
        int availWidth = randomBetween(1280, 1920);
        int availHeight = randomBetween(800, 1080);
        return innerWidth + "|" + innerHeight + "|" + outerWidth + "|" + outerHeight + "|"
                + "0|" + screenY + "|0|0|" + sizeWidth + "|" + sizeHeight + "|"
                + availWidth + "|" + availHeight + "|" + innerWidth + "|" + innerHeight + "|24|24|Win32";
    }

    private static int randomBetween(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }

    private static final class Generator {

        private final String userAgent;
        private final String fingerprint;
        private final int[] bigArray;

        private Generator(String userAgent, String fingerprint) {
            this.userAgent = userAgent;
            this.fingerprint = fingerprint;
            this.bigArray = BIG_ARRAY.clone();
        }

        private String generate(String params, String body) {
            int[] abDir = new int[72];
            abDir[8] = 3;
            abDir[18] = 44;
            abDir[19] = 1;
            abDir[21] = 0;
            abDir[22] = 1;
            abDir[24] = 1;
            abDir[56] = 6383;
            abDir[66] = 0;
            abDir[69] = 0;
            abDir[70] = 0;
            abDir[71] = 0;

            long start = System.currentTimeMillis();
            int[] array1 = paramsToArray(paramsToArray(params));
            int[] array2 = paramsToArray(paramsToArray(body));
            int[] array3 = paramsToArray(
                    base64Encode(latin1(rc4Encrypt(UA_KEY, userAgent)), 1),
                    false);
            long end = System.currentTimeMillis();

            putTime(abDir, 20, start);
            abDir[26] = 0;
            abDir[27] = 0;
            abDir[28] = 0;
            abDir[29] = 0;
            abDir[30] = 0;
            abDir[31] = 1;
            abDir[32] = 0;
            abDir[33] = 0;
            abDir[34] = 0;
            abDir[35] = 0;
            abDir[36] = 0;
            abDir[37] = 14;
            abDir[38] = array1[21];
            abDir[39] = array1[22];
            abDir[40] = array2[21];
            abDir[41] = array2[22];
            abDir[42] = array3[23];
            abDir[43] = array3[24];
            putTime(abDir, 44, end);
            abDir[48] = abDir[8];
            abDir[49] = (int) (end / 4_294_967_296L);
            abDir[50] = (int) (end / 1_099_511_627_776L);
            abDir[51] = 0;
            abDir[52] = 0;
            abDir[53] = 0;
            abDir[54] = 0;
            abDir[55] = 0;
            abDir[57] = 6383 & 255;
            abDir[58] = (6383 >> 8) & 255;
            abDir[59] = (6383 >> 16) & 255;
            abDir[60] = (6383 >> 24) & 255;
            abDir[64] = fingerprint.length();
            abDir[65] = fingerprint.length();

            List<Integer> values = new ArrayList<>();
            for (int index : SORT_INDEX) {
                values.add(abDir[index]);
            }
            for (int i = 0; i < fingerprint.length(); i++) {
                values.add((int) fingerprint.charAt(i));
            }
            int xor = 0;
            for (int i = 0; i < SORT_INDEX_2.length - 1; i++) {
                if (i == 0) {
                    xor = abDir[SORT_INDEX_2[i]];
                }
                xor ^= abDir[SORT_INDEX_2[i + 1]];
            }
            values.add(xor);

            String bytes = randomBytes(3) + transformBytes(values);
            return abogusEncode(bytes, 0);
        }

        private static void putTime(int[] abDir, int offset, long value) {
            abDir[offset] = (int) ((value >> 24) & 255);
            abDir[offset + 1] = (int) ((value >> 16) & 255);
            abDir[offset + 2] = (int) ((value >> 8) & 255);
            abDir[offset + 3] = (int) (value & 255);
            abDir[offset + 4] = (int) (value / 4_294_967_296L);
            abDir[offset + 5] = (int) (value / 1_099_511_627_776L);
        }

        private static int[] paramsToArray(String value) {
            return DouyinSm3.hashToArray(value + "cus");
        }

        private static int[] paramsToArray(int[] values) {
            return DouyinSm3.hashToArray(values);
        }

        private static int[] paramsToArray(String value, boolean addSalt) {
            return DouyinSm3.hashToArray(addSalt ? value + "cus" : value);
        }

        private String transformBytes(List<Integer> values) {
            StringBuilder bytes = new StringBuilder(values.size());
            values.forEach(value -> bytes.append((char) value.intValue()));
            StringBuilder result = new StringBuilder(bytes.length());
            int indexB = bigArray[1];
            int initial = 0;
            int valueE = 0;
            for (int index = 0; index < bytes.length(); index++) {
                int sumInitial;
                if (index == 0) {
                    initial = bigArray[indexB];
                    sumInitial = indexB + initial;
                    bigArray[1] = initial;
                    bigArray[indexB] = indexB;
                } else {
                    sumInitial = initial + valueE;
                }
                int charValue = bytes.charAt(index);
                sumInitial %= bigArray.length;
                int valueF = bigArray[sumInitial];
                result.append((char) (charValue ^ valueF));

                valueE = bigArray[(index + 2) % bigArray.length];
                sumInitial = (indexB + valueE) % bigArray.length;
                initial = bigArray[sumInitial];
                bigArray[sumInitial] = bigArray[(index + 2) % bigArray.length];
                bigArray[(index + 2) % bigArray.length] = initial;
                indexB = sumInitial;
            }
            return result.toString();
        }

        private static String randomBytes(int length) {
            StringBuilder result = new StringBuilder(length * 4);
            for (int i = 0; i < length; i++) {
                int rd = RANDOM.nextInt(10_000);
                result.append((char) (((rd & 255) & 170) | 1));
                result.append((char) (((rd & 255) & 85) | 2));
                result.append((char) (((rd >>> 8) & 170) | 5));
                result.append((char) (((rd >>> 8) & 85) | 40));
            }
            return result.toString();
        }

        private static byte[] rc4Encrypt(byte[] key, String plaintext) {
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
            byte[] data = plaintext.getBytes(StandardCharsets.ISO_8859_1);
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

        private static String latin1(byte[] bytes) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }

        private static String base64Encode(String input, int alphabetIndex) {
            StringBuilder binary = new StringBuilder(input.length() * 8);
            for (int i = 0; i < input.length(); i++) {
                String bits = Integer.toBinaryString(input.charAt(i) & 0xff);
                binary.append("0".repeat(8 - bits.length())).append(bits);
            }
            int paddingLength = (6 - binary.length() % 6) % 6;
            binary.append("0".repeat(paddingLength));
            String alphabet = ALPHABETS[alphabetIndex];
            StringBuilder output = new StringBuilder(binary.length() / 6 + 2);
            for (int i = 0; i < binary.length(); i += 6) {
                output.append(alphabet.charAt(Integer.parseInt(binary.substring(i, i + 6), 2)));
            }
            output.append("=".repeat(paddingLength / 2));
            return output.toString();
        }

        private static String abogusEncode(String input, int alphabetIndex) {
            String alphabet = ALPHABETS[alphabetIndex];
            StringBuilder abogus = new StringBuilder();
            for (int i = 0; i < input.length(); i += 3) {
                int n;
                if (i + 2 < input.length()) {
                    n = (input.charAt(i) << 16) | (input.charAt(i + 1) << 8) | input.charAt(i + 2);
                } else if (i + 1 < input.length()) {
                    n = (input.charAt(i) << 16) | (input.charAt(i + 1) << 8);
                } else {
                    n = input.charAt(i) << 16;
                }
                int[] shifts = {18, 12, 6, 0};
                int[] masks = {0xfc0000, 0x03f000, 0x0fc0, 0x3f};
                for (int j = 0; j < shifts.length; j++) {
                    if (shifts[j] == 6 && i + 1 >= input.length()) {
                        break;
                    }
                    if (shifts[j] == 0 && i + 2 >= input.length()) {
                        break;
                    }
                    abogus.append(alphabet.charAt((n & masks[j]) >> shifts[j]));
                }
            }
            abogus.append("=".repeat((4 - abogus.length() % 4) % 4));
            return abogus.toString();
        }
    }
}
