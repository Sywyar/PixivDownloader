package top.sywyar.pixivdownload.tts.narration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.ai.narration.CastAnalysisRequest;
import top.sywyar.pixivdownload.ai.narration.CastAnalysisResponse;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.SpeakerAttributionRequest;
import top.sywyar.pixivdownload.ai.narration.SpeakerAttributionResponse;
import top.sywyar.pixivdownload.ai.narration.SpeakerAttributionResponse.SentenceVoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「AI 听小说」朗读脚本编排服务：两段式地把一段文本变成多角色 {@link NarrationScript}——先选角（一次 AI 调用
 * 产出稳定名册），再逐句归属说话人（分批 AI 调用），最后合成每句的 Control Instruction。
 *
 * <p>本服务是分析层入口，刻意与具体 TTS 引擎<b>解耦</b>：只依赖 {@link AiService} 与
 * {@code ai.narration} 实体，<b>不</b>引用任何 Edge / VoxCPM 引擎类、<b>不</b>读小说库、<b>不</b>负责断句
 * （句子由调用方切好传入）。
 *
 * <p><b>音色一致性</b>由「同一角色复用同一份名册音色画像」保证；逐句 {@code delivery} 仅追加到该角色基底画像
 * 之后、不改动它。更强的一致性（如 VoxCPM 按角色复用同一参考音 / 随机种子）属引擎适配器职责，不在本层。
 *
 * <p><b>兜底</b>：选角失败 / AI 关闭 → 全篇单一旁白；某批归属失败 → 该批整批归旁白。结果始终与输入句子
 * 一一对应、永不缺句，听书流程不会因 AI 异常中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrationScriptService {

    /** 单次归属请求最多处理的句子数，控制响应体量与下标规模。 */
    static final int MAX_SENTENCES_PER_REQUEST = 40;

    private static final double CAST_TEMPERATURE = 0.4;
    private static final double ATTRIBUTION_TEMPERATURE = 0.2;

    private final AiService aiService;

    /**
     * 一段文本的完整多角色朗读脚本：选角 + 逐句归属 + 合成 Control Instruction。
     *
     * @param castText  用于选角的正文（建议整章或其头部样本）
     * @param sentences 按朗读顺序切好的句子（调用方负责断句）
     */
    public NarrationScript analyze(String castText, List<String> sentences) {
        return buildScript(buildCast(castText), sentences);
    }

    /**
     * 用<b>给定名册</b>（如持久化花名册）产出朗读脚本：逐句归属 + 合成每句 Control Instruction。与
     * {@link #analyze} 的区别是跳过选角、直接复用传入名册，便于上层用按系列 / 单本持久化的稳定名册驱动朗读。
     */
    public NarrationScript buildScript(List<NarrationCharacter> cast, List<String> sentences) {
        List<NarrationCharacter> roster = (cast == null || cast.isEmpty())
                ? List.of(NarrationCharacter.defaultNarrator()) : cast;
        if (sentences == null || sentences.isEmpty()) {
            return new NarrationScript(roster, List.of(), roster.size() > 1);
        }
        List<SentenceVoice> voices = attribute(sentences, roster);
        Map<Integer, NarrationCharacter> byId = indexById(roster);
        NarrationCharacter narrator = byId.getOrDefault(NarrationCharacter.NARRATOR_ID,
                NarrationCharacter.defaultNarrator());

        List<NarrationScript.Line> lines = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            SentenceVoice v = voices.get(i);
            NarrationCharacter c = byId.getOrDefault(v.speakerId(), narrator);
            String instruction = combine(c.controlInstruction(), v.delivery());
            lines.add(new NarrationScript.Line(i, sentences.get(i), c.id(), c.name(), instruction));
        }
        return new NarrationScript(roster, lines, roster.size() > 1);
    }

    /**
     * 选角：产出稳定名册（旁白居首）。AI 关闭 / 调用失败 / 回复不可解析 / 正文为空时回退到单一默认旁白。
     */
    public List<NarrationCharacter> buildCast(String castText) {
        return buildCast(castText, List.of());
    }

    /**
     * 选角（带「已有角色」上下文）：把 {@code knownNames} 发给 AI 复用，仅新增未在册的角色——用于按系列 / 单本
     * 共享花名册时让已选角角色跨章保持稳定。其余语义同 {@link #buildCast(String)}。
     */
    public List<NarrationCharacter> buildCast(String castText, List<String> knownNames) {
        if (castText == null || castText.isBlank()) {
            return List.of(NarrationCharacter.defaultNarrator());
        }
        try {
            AiChatResult chat = aiService.chat(
                    CastAnalysisRequest.CALL_TYPE,
                    new CastAnalysisRequest(castText, knownNames).toMessages(),
                    AiChatOptions.json().withTemperature(CAST_TEMPERATURE));
            List<NarrationCharacter> roster = CastAnalysisResponse.parse(chat.content()).roster();
            return roster.isEmpty() ? List.of(NarrationCharacter.defaultNarrator()) : roster;
        } catch (AiService.AiException | IllegalArgumentException e) {
            log.warn("narration cast analysis failed, falling back to single narrator: {}", e.getMessage());
            return List.of(NarrationCharacter.defaultNarrator());
        }
    }

    /**
     * 逐句归属：把句子映射到名册 id。分批请求；某批失败时整批归旁白。返回与 {@code sentences} 等长、下标连续。
     */
    public List<SentenceVoice> attribute(List<String> sentences, List<NarrationCharacter> cast) {
        if (sentences == null || sentences.isEmpty()) {
            return List.of();
        }
        java.util.Set<Integer> validIds = indexById(cast).keySet();
        int total = sentences.size();
        List<SentenceVoice> out = new ArrayList<>(total);
        for (int start = 0; start < total; start += MAX_SENTENCES_PER_REQUEST) {
            int end = Math.min(start + MAX_SENTENCES_PER_REQUEST, total);
            List<String> chunk = sentences.subList(start, end);
            try {
                AiChatResult chat = aiService.chat(
                        SpeakerAttributionRequest.CALL_TYPE,
                        new SpeakerAttributionRequest(cast, chunk).toMessages(),
                        AiChatOptions.json().withTemperature(ATTRIBUTION_TEMPERATURE));
                List<SentenceVoice> normalized = SpeakerAttributionResponse.parse(chat.content())
                        .normalizedTo(chunk.size(), validIds);
                appendWithOffset(out, normalized, start);
            } catch (AiService.AiException | IllegalArgumentException e) {
                log.warn("narration attribution batch failed, falling back to narrator: range=[{},{}), err={}",
                        start, end, e.getMessage());
                for (int i = start; i < end; i++) {
                    out.add(SentenceVoice.narratorAt(i));
                }
            }
        }
        return out;
    }

    /** 合成最终 Control Instruction：角色基底音色画像 +（非空时）逐句情绪微调。 */
    static String combine(String baseInstruction, String delivery) {
        String base = baseInstruction == null ? "" : baseInstruction.trim();
        if (delivery == null || delivery.isBlank()) {
            return base;
        }
        if (base.isEmpty()) {
            return delivery.trim();
        }
        String trimmed = base.endsWith(".") ? base.substring(0, base.length() - 1) : base;
        return trimmed + ", " + delivery.trim();
    }

    private static void appendWithOffset(List<SentenceVoice> out, List<SentenceVoice> batch, int offset) {
        for (SentenceVoice v : batch) {
            out.add(new SentenceVoice(offset + v.index(), v.speakerId(), v.delivery()));
        }
    }

    private static Map<Integer, NarrationCharacter> indexById(List<NarrationCharacter> cast) {
        Map<Integer, NarrationCharacter> byId = new LinkedHashMap<>();
        if (cast != null) {
            for (NarrationCharacter c : cast) {
                byId.putIfAbsent(c.id(), c);
            }
        }
        byId.putIfAbsent(NarrationCharacter.NARRATOR_ID, NarrationCharacter.defaultNarrator());
        return byId;
    }
}
