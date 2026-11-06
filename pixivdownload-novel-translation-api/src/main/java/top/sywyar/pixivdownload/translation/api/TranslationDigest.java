package top.sywyar.pixivdownload.translation.api;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 原文、译文和发布请求共用的带长度摘要；区分 null、空文本及字段边界。 */
public final class TranslationDigest {
    private TranslationDigest() {}

    /** @return 按 UTF-16 码元计算的固定摘要，不分配整份编码副本 */
    public static String of(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String text : fields) {
                digest.update(ByteBuffer.allocate(4).putInt(text == null ? -1 : text.length()).array());
                if (text != null) for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    digest.update((byte) (c >>> 8));
                    digest.update((byte) c);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
