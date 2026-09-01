package top.sywyar.pixivdownload.plugin.catalog.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 安全关键仓库文档共用的严格 JSON/UTF-8 解析配置。 */
public final class PluginCatalogStrictJson {

    private PluginCatalogStrictJson() {
    }

    public static ObjectMapper mapper(boolean rejectUnknownFields) {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(16)
                        .maxStringLength(16_384)
                        .maxNumberLength(64)
                        .build())
                .build();
        return new ObjectMapper(factory)
                .registerModule(new ParameterNamesModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, rejectUnknownFields)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    }

    public static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }
}
