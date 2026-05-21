package top.sywyar.pixivdownload.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsVoice;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉取并缓存 Edge TTS 的可用语音列表。
 *
 * <p>列表很大且基本不变，进程内缓存一次即可。在线获取失败（断网 / 代理不可用）时，退回到内置的常用语音兜底，
 * 保证前端语音下拉始终有可选项（覆盖中 / 日 / 英）。
 */
@Service
@Slf4j
public class EdgeTtsVoiceService {

    private static final String VOICES_URL =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list"
                    + "?trustedclienttoken=" + EdgeTtsClient.TRUSTED_TOKEN;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EdgeTtsVersionService versionService;

    private volatile List<EdgeTtsVoice> cached;

    public EdgeTtsVoiceService(RestTemplate restTemplate, ObjectMapper objectMapper,
                               EdgeTtsVersionService versionService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.versionService = versionService;
    }

    public List<EdgeTtsVoice> listVoices() {
        List<EdgeTtsVoice> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            List<EdgeTtsVoice> fetched = fetchRemote();
            cached = fetched.isEmpty() ? fallbackVoices() : fetched;
            return cached;
        }
    }

    private List<EdgeTtsVoice> fetchRemote() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", versionService.userAgent());
            headers.set("Sec-MS-GEC", EdgeTtsClient.generateSecMsGec());
            headers.set("Sec-MS-GEC-Version", versionService.secMsGecVersion());
            // 按仓库约束：UTF-8 JSON 用 byte[] 取回再解析，避免 ISO-8859-1 误解码。
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    VOICES_URL, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                return List.of();
            }
            List<EdgeTtsVoice> out = new ArrayList<>();
            for (JsonNode v : root) {
                String shortName = v.path("ShortName").asText("");
                if (shortName.isBlank()) continue;
                out.add(new EdgeTtsVoice(
                        shortName,
                        v.path("Locale").asText(""),
                        v.path("Gender").asText(""),
                        firstNonBlank(v.path("FriendlyName").asText(""), shortName)
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("获取 Edge TTS 语音列表失败，使用内置兜底语音: {}", e.getMessage());
            return List.of();
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static List<EdgeTtsVoice> fallbackVoices() {
        List<EdgeTtsVoice> list = new ArrayList<>();
        list.add(new EdgeTtsVoice("zh-CN-XiaoxiaoNeural", "zh-CN", "Female", "Xiaoxiao"));
        list.add(new EdgeTtsVoice("zh-CN-YunxiNeural", "zh-CN", "Male", "Yunxi"));
        list.add(new EdgeTtsVoice("zh-CN-YunyangNeural", "zh-CN", "Male", "Yunyang"));
        list.add(new EdgeTtsVoice("zh-CN-XiaoyiNeural", "zh-CN", "Female", "Xiaoyi"));
        list.add(new EdgeTtsVoice("zh-TW-HsiaoChenNeural", "zh-TW", "Female", "HsiaoChen"));
        list.add(new EdgeTtsVoice("ja-JP-NanamiNeural", "ja-JP", "Female", "Nanami"));
        list.add(new EdgeTtsVoice("ja-JP-KeitaNeural", "ja-JP", "Male", "Keita"));
        list.add(new EdgeTtsVoice("en-US-AriaNeural", "en-US", "Female", "Aria"));
        list.add(new EdgeTtsVoice("en-US-GuyNeural", "en-US", "Male", "Guy"));
        list.add(new EdgeTtsVoice("ko-KR-SunHiNeural", "ko-KR", "Female", "SunHi"));
        return list;
    }
}
