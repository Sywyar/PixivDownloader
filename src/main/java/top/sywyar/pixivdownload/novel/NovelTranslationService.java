package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.ai.translation.GlossaryTerm;
import top.sywyar.pixivdownload.ai.translation.TranslationRequest;
import top.sywyar.pixivdownload.ai.translation.TranslationResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;
import top.sywyar.pixivdownload.novel.db.NovelRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小说 AI 翻译编排服务：把一本小说的原始 Pixiv markup（{@code novels.raw_content}）整章翻译成目标语言。
 *
 * <p>长文按字数分段（{@code segmentSize}）：{@code <=0} 整章一次性请求；{@code >0} 时按段落（行）累积到约
 * {@code segmentSize} 字切成多段，<b>每段单独发一次 AI 请求</b>，再按原顺序拼接成完整译文。译文保留原始
 * markup（见 {@link TranslationRequest}），写入 {@code novel_translations}，供详情页按语言渲染与系列变体合订复用，
 * 避免重复请求 AI。
 *
 * <p>所有 AI 调用统一走 {@link AiService#chat}（OpenAI 兼容协议、JSON 输出），请求 / 响应分别由
 * {@link TranslationRequest} / {@link TranslationResponse} 规范。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelTranslationService {

    /** 单次翻译请求最多注入给 AI 的映射表条目数（控制 token 体量）。 */
    private static final int MAX_GLOSSARY_TERMS = 1000;

    private final AiService aiService;
    private final NovelDatabase novelDatabase;
    private final NovelGlossaryService glossaryService;
    private final AppMessages messages;

    public enum Status { OK, SKIPPED, INVALID_LANGUAGE, EMPTY, NOT_FOUND, ERROR }

    /**
     * @param status    结果状态
     * @param langCode  实际翻译语言的通用代码（成功 / 跳过时有值）
     * @param message   面向用户的本地化提示
     * @param truncated 译文是否疑似被模型输出长度截断（建议减小分段字数）
     */
    public record Result(Status status, String langCode, String message, boolean truncated) {}

    /**
     * 翻译单本小说为目标语言。
     *
     * @param novelId        小说 ID
     * @param targetLanguage 用户填写的目标语言自由文本（如「简体中文」「english」）
     * @param segmentSize    分段字数阈值；{@code <=0} 表示整章一次性翻译
     * @param overwrite      已存在该语言译文时：{@code true} 覆盖重译，{@code false} 跳过
     * @param langHint       目标语言代码提示（系列批量时由首章解析得到）；命中且 {@code !overwrite} 可在调用 AI 前直接跳过
     * @param glossaryId     名词映射表 ID；{@code null} 表示不使用映射表（不注入术语、不回写新名词）
     */
    public Result translateChapter(long novelId, String targetLanguage, int segmentSize,
                                   boolean overwrite, String langHint, Long glossaryId) {
        NovelRecord record = novelDatabase.getNovel(novelId);
        if (record == null) {
            return new Result(Status.NOT_FOUND, null, messages.get("novel.translate.not-found"), false);
        }
        String raw = record.rawContent();
        if (raw == null || raw.isBlank()) {
            return new Result(Status.EMPTY, null, messages.get("novel.translate.empty"), false);
        }
        // 提示语言代码命中且选择跳过：无需调用 AI 直接跳过
        if (!overwrite && langHint != null && !langHint.isBlank()
                && novelDatabase.hasTranslation(novelId, langHint)) {
            return new Result(Status.SKIPPED, langHint.trim(),
                    messages.get("novel.translate.skipped"), false);
        }

        // 注入给 AI 的映射表条目（含表内全部目标语言，模型仅对语言匹配项强制套用）。
        // 用可变列表：本章翻译中模型新发现的名词会即时并入，供后续分段沿用，保证章内分段一致。
        List<GlossaryTerm> glossaryTerms = new ArrayList<>(loadGlossaryTerms(glossaryId));

        List<String> segments = splitIntoSegments(raw, segmentSize);
        List<String> translatedSegments = new ArrayList<>(segments.size());
        // 跨段累计模型回报的新名词（同一原文以首次出现为准）
        Map<String, TranslationResponse.NewTerm> newTerms = new LinkedHashMap<>();
        String langCode = null;
        boolean truncated = false;
        try {
            for (int i = 0; i < segments.size(); i++) {
                AiChatResult chat = aiService.chat(
                        new TranslationRequest(targetLanguage, segments.get(i), glossaryTerms).toMessages(),
                        AiChatOptions.json().withTemperature(0.3));
                TranslationResponse parsed = TranslationResponse.parse(chat.content());
                if (parsed.invalidLanguage()) {
                    return new Result(Status.INVALID_LANGUAGE, null,
                            messages.get("novel.translate.invalid-language"), false);
                }
                if (!parsed.ok()) {
                    return new Result(Status.ERROR, null,
                            messages.get("novel.translate.invalid-language"), false);
                }
                if (i == 0) {
                    langCode = normalizeLang(parsed.lang(), targetLanguage);
                    // 首段解析出语言代码后，若选择跳过且已存在该语言译文，则不再继续翻译其余分段
                    if (!overwrite && novelDatabase.hasTranslation(novelId, langCode)) {
                        return new Result(Status.SKIPPED, langCode,
                                messages.get("novel.translate.skipped"), false);
                    }
                }
                translatedSegments.add(parsed.text() == null ? "" : parsed.text());
                boolean usableLang = langCode != null && !langCode.isBlank();
                for (TranslationResponse.NewTerm t : parsed.newTerms()) {
                    // 首次出现的新名词即时并入后续分段要发送的术语表（章内实时一致）
                    if (newTerms.putIfAbsent(t.source(), t) == null && usableLang) {
                        glossaryTerms.add(new GlossaryTerm(t.source(), langCode, t.target()));
                    }
                }
                if ("length".equalsIgnoreCase(chat.finishReason())) {
                    truncated = true;
                }
            }
        } catch (AiService.AiException e) {
            return new Result(Status.ERROR, null, e.getMessage(), false);
        }

        String translated = String.join("\n", translatedSegments);
        novelDatabase.saveTranslation(novelId, langCode, translated);
        mergeNewTerms(glossaryId, langCode, newTerms.values());
        String message = truncated
                ? messages.get("novel.translate.truncated")
                : messages.get("novel.translate.success");
        return new Result(Status.OK, langCode, message, truncated);
    }

    /** 读取映射表条目并转换为发给 AI 的术语列表；{@code glossaryId} 为空或表不存在时返回空表。 */
    private List<GlossaryTerm> loadGlossaryTerms(Long glossaryId) {
        if (glossaryId == null) {
            return List.of();
        }
        List<NovelGlossaryEntry> entries = NovelGlossaryService.capped(
                glossaryService.entries(glossaryId), MAX_GLOSSARY_TERMS);
        List<GlossaryTerm> terms = new ArrayList<>(entries.size());
        for (NovelGlossaryEntry e : entries) {
            terms.add(new GlossaryTerm(e.source(), e.langCode(), e.target()));
        }
        return terms;
    }

    /** 把模型回报的新名词按解析出的语言代码自动并入映射表（已存在条目不覆盖）。 */
    private void mergeNewTerms(Long glossaryId, String langCode,
                              java.util.Collection<TranslationResponse.NewTerm> newTerms) {
        if (glossaryId == null || newTerms.isEmpty() || langCode == null || langCode.isBlank()) {
            return;
        }
        List<NovelGlossaryEntry> toMerge = new ArrayList<>(newTerms.size());
        for (TranslationResponse.NewTerm t : newTerms) {
            toMerge.add(new NovelGlossaryEntry(t.source(), langCode, t.target()));
        }
        try {
            glossaryService.mergeAiTerms(glossaryId, langCode, toMerge);
        } catch (Exception e) {
            // 名词回写是 best-effort：失败不影响已保存的译文
            log.warn("merge AI glossary terms failed: glossaryId={}, lang={}, err={}",
                    glossaryId, langCode, e.getMessage());
        }
    }

    /**
     * 按段落（行）边界把原文累积切分成每段约 {@code segmentSize} 字的分段；{@code <=0} 时整体作为一段。
     * 仅在换行处切分，确保每个 Pixiv 标记 token 不会被截断，且分段拼接后能精确还原原文结构。
     */
    static List<String> splitIntoSegments(String raw, int segmentSize) {
        List<String> segments = new ArrayList<>();
        if (segmentSize <= 0) {
            segments.add(raw);
            return segments;
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            if (current.length() >= segmentSize) {
                segments.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0 || segments.isEmpty()) {
            segments.add(current.toString());
        }
        return segments;
    }

    private static String normalizeLang(String lang, String fallback) {
        if (lang != null && !lang.isBlank()) {
            return lang.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
