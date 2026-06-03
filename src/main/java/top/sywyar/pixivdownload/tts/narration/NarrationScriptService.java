package top.sywyar.pixivdownload.tts.narration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.ai.narration.NarrationAnalysisRequest;
import top.sywyar.pixivdownload.ai.narration.NarrationAnalysisResponse;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.NarrationLineVoice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「AI 听小说」朗读分析编排服务：以<b>合并单次按段分析</b>把一段句子连同当前花名册发给 LLM，一次返回该段每句的
 * 说话人 + 情绪 delivery、新角色、对已有角色的兼容性补充与冲突上报（见 {@link NarrationSegmentAnalysis}）。
 * 这取代了早期的两段式（选角 + 逐句归属两次调用）。
 *
 * <p>本服务是分析层入口，刻意与具体 TTS 引擎、持久化<b>解耦</b>：只依赖 {@link AiService} 与
 * {@code ai.narration} 实体，<b>不</b>引用任何 Edge / VoxCPM 引擎类、<b>不</b>读小说库、<b>不</b>负责断句
 * （句子由调用方切好传入），也<b>不</b>做花名册落库 / 冲突路由（那是编排层 {@code novel.NovelNarrationCastService}
 * 的职责）。
 *
 * <p><b>音色一致性</b>由「每段携带花名册、模型复用既有角色 id」保证；逐句 {@code delivery} 仅追加到角色基底画像
 * 之后、不改动它。<b>兜底</b>：AI 关闭 / 调用失败 / 回复不可解析时该段整段归旁白（{@link NarrationSegmentAnalysis#narratorFallback}），
 * 结果始终与输入句子等长、永不缺句，听书流程不会因 AI 异常中断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrationScriptService {

    private static final double ANALYSIS_TEMPERATURE = 0.3;

    private final AiService aiService;

    /**
     * 对<b>一段</b>句子做合并单次分析：把 {@code roster}（当前花名册）+ 句子 + {@code nextId} 发给 LLM，
     * 返回该段逐句归属 + 新角色 + 补充 + 冲突。结果的逐句 speaker 已严格对齐到输入句数，且 speaker 只可能是
     * 「{@code roster} 已有 id」或「本响应新角色的临时 id」或旁白 0——编排层据此重映射临时 id。
     *
     * @param roster    当前花名册（旁白居首，id 0）
     * @param sentences 本段待分析句子（调用方负责断句）
     * @param nextId    新角色起始 id（= 花名册最大 id + 1）
     */
    public NarrationSegmentAnalysis analyzeSegment(List<NarrationCharacter> roster,
                                                   List<String> sentences, int nextId) {
        int count = sentences == null ? 0 : sentences.size();
        if (count == 0) {
            return NarrationSegmentAnalysis.narratorFallback(0);
        }
        List<NarrationCharacter> safeRoster = (roster == null || roster.isEmpty())
                ? List.of(NarrationCharacter.defaultNarrator()) : roster;
        try {
            AiChatResult chat = aiService.chat(
                    NarrationAnalysisRequest.CALL_TYPE,
                    new NarrationAnalysisRequest(safeRoster, sentences, nextId).toMessages(),
                    AiChatOptions.json().withTemperature(ANALYSIS_TEMPERATURE));
            NarrationAnalysisResponse response = NarrationAnalysisResponse.parse(chat.content());

            List<NarrationCharacter> newCharacters = response.newCharacters();
            Map<Integer, NarrationCharacter> rosterById = indexById(safeRoster);
            if (hasInvalidNewCharacterId(newCharacters, rosterById.keySet(), nextId)) {
                log.warn("narration segment analysis returned invalid new character id, falling back to narrator: count={}, nextId={}",
                        count, nextId);
                return NarrationSegmentAnalysis.narratorFallback(count);
            }
            Set<Integer> validSpeakerIds = new LinkedHashSet<>(rosterById.keySet());
            for (NarrationCharacter c : newCharacters) {
                validSpeakerIds.add(c.id());
            }
            List<NarrationLineVoice> lines = response.normalizedTo(count, validSpeakerIds);
            return new NarrationSegmentAnalysis(lines, newCharacters,
                    response.updatedCharacters(), response.conflicts());
        } catch (AiService.AiException | IllegalArgumentException e) {
            log.warn("narration segment analysis failed, falling back to narrator: count={}, err={}",
                    count, e.getMessage());
            return NarrationSegmentAnalysis.narratorFallback(count);
        }
    }

    /**
     * 结果装配：用<b>最终名册</b> + 整章逐句归属（{@code lineVoices}，speaker 已是真实 id、下标为全局下标）
     * 合成完整朗读脚本——逐句把角色基底画像与 {@code delivery} {@link #combine} 成最终 Control Instruction。
     * {@code lineVoices} 缺失 / 越界的句子归旁白，结果始终与 {@code sentences} 等长。
     */
    public NarrationScript buildScript(List<NarrationCharacter> roster, List<String> sentences,
                                       List<NarrationLineVoice> lineVoices) {
        List<NarrationCharacter> safeRoster = (roster == null || roster.isEmpty())
                ? List.of(NarrationCharacter.defaultNarrator()) : roster;
        if (sentences == null || sentences.isEmpty()) {
            return new NarrationScript(safeRoster, List.of(), safeRoster.size() > 1);
        }
        int count = sentences.size();
        NarrationLineVoice[] byIndex = new NarrationLineVoice[count];
        if (lineVoices != null) {
            for (NarrationLineVoice lv : lineVoices) {
                if (lv == null) {
                    continue;
                }
                int i = lv.index();
                if (i >= 0 && i < count && byIndex[i] == null) {
                    byIndex[i] = lv;
                }
            }
        }
        Map<Integer, NarrationCharacter> byId = indexById(safeRoster);
        NarrationCharacter narrator = byId.getOrDefault(NarrationCharacter.NARRATOR_ID,
                NarrationCharacter.defaultNarrator());

        List<NarrationScript.Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            NarrationLineVoice lv = byIndex[i] != null ? byIndex[i] : NarrationLineVoice.narratorAt(i);
            NarrationCharacter c = byId.getOrDefault(lv.speakerId(), narrator);
            String delivery = lv.delivery() == null ? "" : lv.delivery();
            String instruction = combine(c.controlInstruction(), delivery);
            lines.add(new NarrationScript.Line(i, sentences.get(i), c.id(), c.name(), delivery, instruction));
        }
        return new NarrationScript(safeRoster, lines, safeRoster.size() > 1);
    }

    /** 合成最终 Control Instruction：角色基底音色画像 +（非空时）逐句情绪微调。 */
    public static String combine(String baseInstruction, String delivery) {
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

    private static boolean hasInvalidNewCharacterId(List<NarrationCharacter> newCharacters,
                                                    Set<Integer> existingIds,
                                                    int nextId) {
        if (newCharacters == null || newCharacters.isEmpty()) {
            return false;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        Set<Integer> safeExistingIds = existingIds == null ? Set.of() : existingIds;
        for (NarrationCharacter c : newCharacters) {
            if (c == null) {
                continue;
            }
            int id = c.id();
            if (id < nextId || safeExistingIds.contains(id) || !seen.add(id)) {
                return true;
            }
        }
        return false;
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
