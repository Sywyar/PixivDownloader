package top.sywyar.pixivdownload.douyin.download.validation;

import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinErrorClassifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 对新下载响应与旧历史文件执行同一组有界媒体载荷检查。 */
public final class DouyinMediaPayloadValidator {

    public static final int PREFIX_BYTES = 512;

    private DouyinMediaPayloadValidator() {
    }

    public static void requireMediaPayload(String contentType, byte[] prefix)
            throws DouyinClientException {
        if (prefix == null || prefix.length == 0) {
            throw new DouyinClientException(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH,
                    "Douyin media response was empty");
        }
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String prefixText = new String(prefix, StandardCharsets.UTF_8)
                .replaceFirst("^\\uFEFF", "")
                .stripLeading()
                .toLowerCase(Locale.ROOT);
        boolean explicitDocument = normalizedType.startsWith("text/")
                || normalizedType.contains("json")
                || normalizedType.contains("html")
                || normalizedType.contains("xml");
        boolean documentPrefix = prefixText.startsWith("{")
                || prefixText.startsWith("[")
                || prefixText.startsWith("<");
        boolean printableText = isMostlyPrintableUtf8(prefix);
        if (!explicitDocument && !documentPrefix && !printableText) {
            return;
        }
        if (DouyinErrorClassifier.looksLikeLoginOrRiskPage(prefix)) {
            throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                    "Douyin media endpoint returned a verification response");
        }
        throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                "Douyin media endpoint returned a non-media response");
    }

    private static boolean isMostlyPrintableUtf8(byte[] prefix) {
        CharBuffer decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prefix));
        } catch (CharacterCodingException ignored) {
            return false;
        }
        int total = 0;
        int printable = 0;
        int visible = 0;
        for (int offset = 0; offset < decoded.length(); ) {
            int codePoint = Character.codePointAt(decoded, offset);
            offset += Character.charCount(codePoint);
            total++;
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || !Character.isISOControl(codePoint)) {
                printable++;
            }
            if (!Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint)) {
                visible++;
            }
        }
        return visible >= 4 && printable * 10 >= total * 9;
    }

    public static boolean isReusableMediaFile(Path path, String contentType) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(path)) {
            requireMediaPayload(contentType, input.readNBytes(PREFIX_BYTES));
            return true;
        } catch (IOException | DouyinClientException ignored) {
            return false;
        }
    }
}
