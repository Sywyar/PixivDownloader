package top.sywyar.pixivdownload.novel.translation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.novel.translation.ai.GlossaryTerm;
import top.sywyar.pixivdownload.novel.translation.ai.LangProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.LangProbeResponse;
import top.sywyar.pixivdownload.novel.translation.ai.SourceLanguageProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.SourceLanguageProbeResponse;
import top.sywyar.pixivdownload.novel.translation.ai.TitleTranslationRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TitleTranslationResponse;
import top.sywyar.pixivdownload.novel.translation.ai.TranslationRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TranslationResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.core.metadata.novel.NovelSeries;

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
    private final MessageResolver messages;

    public enum Status { OK, SKIPPED, SAME_LANGUAGE, INVALID_LANGUAGE, EMPTY, NOT_FOUND, ERROR }

    /** 源语言探测样本最大字数（控制 token 体量；覆盖开头 / 中段 / 结尾后再判定）。 */
    private static final int SAMPLE_MAX_CHARS = 200;

    /**
     * @param status    结果状态
     * @param langCode  实际翻译语言的通用代码（成功 / 跳过时有值）
     * @param message   面向用户的本地化提示
     * @param truncated 译文是否疑似被模型输出长度截断（建议减小分段字数）
     */
    public record Result(Status status, String langCode, String message, boolean truncated) {}

    /**
     * 翻译单本小说为目标语言。{@code translateBody / translateTitle / translateDescription} 三者必须至少有一项为
     * {@code true}（由 controller 校验）。{@code translateBody = false} 时跳过正文 AI 调用，仅对标题 / 简介走
     * 一次轻量 {@link TitleTranslationRequest} 调用（glossary-only，不附章节标题参考；description-only 时
     * 仍把原标题作为上下文传入，但只保存被请求的字段）。
     *
     * @param novelId             小说 ID
     * @param targetLanguage      用户填写的目标语言自由文本（如「简体中文」「english」）
     * @param segmentSize         分段字数阈值；{@code <=0} 表示整章一次性翻译
     * @param overwrite           已存在该语言译文时：{@code true} 覆盖重译，{@code false} 跳过
     * @param langHint            目标语言代码提示（系列批量时由首章解析得到）；命中且 {@code !overwrite} 可在调用 AI 前直接跳过
     * @param glossaryId          名词映射表 ID；{@code null} 表示不使用映射表（不注入术语、不回写新名词）
     * @param translateBody       是否翻译正文
     * @param translateTitle      是否翻译章节标题
     * @param translateDescription 是否翻译章节简介
     */
    public Result translateChapter(long novelId, String targetLanguage, int segmentSize,
                                   boolean overwrite, String langHint, Long glossaryId,
                                   boolean translateBody, boolean translateTitle,
                                   boolean translateDescription) {
        if (!translateBody && !translateTitle && !translateDescription) {
            return new Result(Status.ERROR, null,
                    messages.get("novel.translate.no-scope"), false);
        }
        NovelRecord record = novelDatabase.getNovel(novelId);
        if (record == null) {
            return new Result(Status.NOT_FOUND, null, messages.get("novel.translate.not-found"), false);
        }
        if (!translateBody) {
            return translateMetadataOnly(record, targetLanguage, overwrite, langHint, glossaryId,
                    translateTitle, translateDescription);
        }
        String raw = record.rawContent();
        if (raw == null || raw.isBlank()) {
            return new Result(Status.EMPTY, null, messages.get("novel.translate.empty"), false);
        }
        // 目标语言代码：优先用调用方已解析的 langHint；缺省时（如详情页单作品翻译）现解析一次，
        // 与「先取目标语言代码 → 检测源语言 → 翻译」的顺序一致。
        String targetCode = (langHint != null && !langHint.isBlank())
                ? langHint.trim() : resolveLangCode(targetLanguage);

        // 语言代码命中且选择跳过：只在<b>用户勾选的所有字段</b>都已有非空译文时跳过（连源语言探测都不必做）；
        // 否则即便该语言已有部分行（例如已译正文但缺标题）仍需继续翻译以补齐。
        if (!overwrite && targetCode != null && !targetCode.isBlank()
                && isAllRequestedFieldsTranslated(novelId, targetCode,
                translateBody, translateTitle, translateDescription)) {
            return new Result(Status.SKIPPED, targetCode,
                    messages.get("novel.translate.skipped"), false);
        }

        // 源语言探测：把正文开头一小段连同目标语言代码发给模型，判断原文是否已是目标语言。
        // 是则跳过整章翻译、省下一次完整翻译请求（best-effort：无法取得目标代码 / 探测失败 / 模型无法判定时照常翻译）。
        if (targetCode != null && !targetCode.isBlank() && isSourceSameAsTarget(raw, targetCode)) {
            return new Result(Status.SAME_LANGUAGE, targetCode,
                    messages.get("novel.translate.same-language"), false);
        }

        // 注入给 AI 的映射表条目（含表内全部目标语言，模型仅对语言匹配项强制套用）。
        // 用可变列表：本章翻译中模型新发现的名词会即时并入，供后续分段沿用，保证章内分段一致。
        List<GlossaryTerm> glossaryTerms = new ArrayList<>(loadGlossaryTerms(glossaryId));

        List<String> segments = splitIntoSegments(raw, segmentSize);
        List<String> translatedSegments = new ArrayList<>(segments.size());
        // 跨段累计模型回报的新名词（同一原文以首次出现为准）
        Map<String, TranslationResponse.NewTerm> newTerms = new LinkedHashMap<>();
        String langCode = null;
        // 章节标题 / 简介随首段同请求翻译；首段成功后写入此变量，结尾连同正文一起入库（DB 端 null 表示不动旧值）。
        String translatedTitle = null;
        String translatedDescription = null;
        boolean truncated = false;
        try {
            for (int i = 0; i < segments.size(); i++) {
                // 首段附 sourceTitle / sourceDescription：让标题与简介与本段正文共享同一次 AI 调用，
                // 自动复用同一映射表与上下文，保证术语一致。仅在用户勾选对应字段时附带 + 保存。
                String segmentTitle = (i == 0 && translateTitle) ? record.title() : null;
                String segmentDescription = (i == 0 && translateDescription) ? record.description() : null;
                AiChatResult chat = aiService.chat(
                        TranslationRequest.CALL_TYPE,
                        new TranslationRequest(targetLanguage, segments.get(i),
                                segmentTitle, segmentDescription, glossaryTerms).toMessages(),
                        AiChatOptions.json().withTemperature(0.3));
                TranslationResponse parsed;
                try {
                    parsed = TranslationResponse.parse(chat.content());
                } catch (IllegalArgumentException ex) {
                    // 模型回了非约定 JSON：走受控 Result，由调用方按状态本地化提示；
                    // 否则会泄到全局 400 异常处理器、返回 parser 细节而非翻译失败语义。
                    log.warn("novel translation response unparseable: novelId={}, err={}",
                            novelId, ex.getMessage());
                    return new Result(Status.ERROR, null,
                            messages.get("novel.translate.unparseable"), false);
                }
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
                    // 首段解析出语言代码后，若所有勾选字段都已译完则跳过，避免重复 AI 调用；
                    // 仅部分字段已译时继续翻译以补齐缺失项。
                    if (!overwrite && isAllRequestedFieldsTranslated(novelId, langCode,
                            translateBody, translateTitle, translateDescription)) {
                        return new Result(Status.SKIPPED, langCode,
                                messages.get("novel.translate.skipped"), false);
                    }
                    // 仅在用户勾选对应字段时落库（未勾选 → null，saveTranslation 会保留旧值不动）。
                    translatedTitle = translateTitle ? parsed.translatedTitle() : null;
                    translatedDescription = translateDescription ? parsed.translatedDescription() : null;
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
        novelDatabase.saveTranslation(novelId, langCode, translated, translatedTitle, translatedDescription);
        mergeNewTerms(glossaryId, langCode, newTerms.values());
        String message = truncated
                ? messages.get("novel.translate.truncated")
                : messages.get("novel.translate.success");
        return new Result(Status.OK, langCode, message, truncated);
    }

    /**
     * 仅翻译章节标题 / 简介（不动正文）。走一次 {@link TitleTranslationRequest} 调用，
     * 不附章节标题参考（单本场景下无意义）；翻译时仍把原标题作为上下文传给 AI，但只持久化用户勾选的字段。
     * 适用于用户只想补译标题、补译简介、或两者一起补译的场景，节省 AI tokens。
     */
    private Result translateMetadataOnly(NovelRecord record, String targetLanguage,
                                         boolean overwrite, String langHint, Long glossaryId,
                                         boolean translateTitle, boolean translateDescription) {
        long novelId = record.novelId();
        String title = record.title();
        // 没有标题可作上下文且只想翻译简介时仍可继续（AI 不会强制要标题），但若同时连标题字段都没有则更明确。
        if ((title == null || title.isBlank())
                && (record.description() == null || record.description().isBlank())) {
            return new Result(Status.EMPTY, null, messages.get("novel.translate.empty"), false);
        }
        // hint 命中且跳过模式：仅当用户勾选的字段都已有非空译文时跳过；只译了正文 / 只译了标题等部分场景仍需补齐。
        if (!overwrite && langHint != null && !langHint.isBlank()
                && isAllRequestedFieldsTranslated(novelId, langHint,
                false, translateTitle, translateDescription)) {
            return new Result(Status.SKIPPED, langHint.trim(),
                    messages.get("novel.translate.skipped"), false);
        }
        List<GlossaryTerm> terms = loadGlossaryTerms(glossaryId);
        // 标题为上下文必备；description 只在用户勾选时附带，避免无谓 tokens。
        String description = translateDescription ? record.description() : null;
        TitleTranslationResponse parsed = callTitleTranslator(
                title == null ? "" : title, description, targetLanguage, terms, List.of());
        if (parsed == null) {
            return new Result(Status.ERROR, null, messages.get("novel.translate.invalid-language"), false);
        }
        if (parsed.invalidLanguage()) {
            return new Result(Status.INVALID_LANGUAGE, null,
                    messages.get("novel.translate.invalid-language"), false);
        }
        if (!parsed.ok()) {
            return new Result(Status.ERROR, null,
                    messages.get("novel.translate.invalid-language"), false);
        }
        String langCode = normalizeLang(parsed.lang(), targetLanguage);
        if (langCode == null || langCode.isBlank()) {
            return new Result(Status.ERROR, null,
                    messages.get("novel.translate.invalid-language"), false);
        }
        if (!overwrite && isAllRequestedFieldsTranslated(novelId, langCode,
                false, translateTitle, translateDescription)) {
            return new Result(Status.SKIPPED, langCode,
                    messages.get("novel.translate.skipped"), false);
        }
        String savedTitle = translateTitle ? parsed.title() : null;
        String savedDescription = translateDescription ? parsed.translatedDescription() : null;
        if ((savedTitle == null || savedTitle.isBlank())
                && (savedDescription == null || savedDescription.isBlank())) {
            return new Result(Status.ERROR, null,
                    messages.get("novel.translate.invalid-language"), false);
        }
        novelDatabase.saveTranslationMetadata(novelId, langCode, savedTitle, savedDescription);
        return new Result(Status.OK, langCode, messages.get("novel.translate.success"), false);
    }

    /**
     * 把某系列的系列名 + 系列简介翻译为目标语言并落库。AI 请求里会附带：
     * <ul>
     *   <li>指定 {@code glossaryId} 时该映射表的全部条目（与正文翻译共用同一张表，保证专有名词译法一致）；</li>
     *   <li>同系列内所有章节的「原标题 → 该语言已译标题」对（仅含已经翻译完成的章节），作为命名 / 风格参考样例
     *       —— 仅服务于系列名翻译；系列简介只依赖名词映射表保证术语一致，不参考章节标题。</li>
     * </ul>
     * 这样系列名既能复用已经在正文 / 章节标题中确立的术语，又能跟同系列其它章节的风格保持一致；系列简介则
     * 与系列名在同一次 AI 调用里完成，省下一次请求。
     *
     * <p>{@code langHint} 与 {@link #translateChapter} 同义：命中 DB 已有翻译时直接跳过、不再请求 AI。
     *
     * @param glossaryId 名词映射表 ID；{@code null} 表示不注入术语
     * @return 实际翻译用的语言代码；无可翻译标题 / AI 拒识 / AI 失败时返回空字符串
     */
    public String translateSeriesTitle(long seriesId, String targetLanguage, String langHint, Long glossaryId,
                                       boolean translateTitle, boolean translateDescription) {
        if (!translateTitle && !translateDescription) return "";
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series == null) return "";
        String title = series.title();
        if (title == null || title.isBlank()) return "";
        // hint 命中：DB 已有该语言翻译则直接返回 hint，无需 AI（按用户选定字段判断已存在性）
        if (langHint != null && !langHint.isBlank()) {
            String lang = langHint.trim();
            boolean titleSatisfied = !translateTitle
                    || hasNonBlank(novelDatabase.getSeriesTitleTranslation(seriesId, lang));
            boolean descSatisfied = !translateDescription
                    || hasNonBlank(novelDatabase.getSeriesDescriptionTranslation(seriesId, lang));
            if (titleSatisfied && descSatisfied) {
                return lang;
            }
        }
        // 同系列章节的「原标题 → 该语言已译标题」对，作为译名 / 风格的参考样例（仅取已译完成的章节）。
        // 受 MAX_TITLE_REFERENCES 上限保护，章节多时按系列顺序优先取前若干个。
        List<TitleTranslationRequest.TitleReference> references = buildSeriesTitleReferences(
                seriesId, langHint, targetLanguage);
        // 映射表条目（含全部目标语言，模型仅对语言匹配项强制套用）。
        List<GlossaryTerm> terms = loadGlossaryTerms(glossaryId);
        // description 仅在用户勾选时附带；title 字段 AI 始终翻译，作为上下文以服务 description（即便只保留 description）。
        String description = translateDescription ? series.description() : null;
        TitleTranslationResponse parsed = callTitleTranslator(
                title, description, targetLanguage, terms, references);
        if (parsed == null || !parsed.ok()) return "";
        String code = normalizeLang(parsed.lang(), targetLanguage);
        if (code == null || code.isBlank()) return "";
        String savedTitle = translateTitle ? parsed.title() : null;
        String savedDescription = translateDescription ? parsed.translatedDescription() : null;
        if ((savedTitle == null || savedTitle.isBlank())
                && (savedDescription == null || savedDescription.isBlank())) {
            return "";
        }
        if (savedTitle != null && !savedTitle.isBlank()) {
            novelDatabase.saveSeriesTitleTranslation(seriesId, code, savedTitle, savedDescription);
        } else {
            // 只想补译简介：复用既有系列名（如果有），否则回退原文系列名以保留行的 NOT NULL 约束
            String existingTitle = novelDatabase.getSeriesTitleTranslation(seriesId, code);
            String titleToWrite = (existingTitle != null && !existingTitle.isBlank())
                    ? existingTitle : title;
            novelDatabase.saveSeriesTitleTranslation(seriesId, code, titleToWrite, savedDescription);
        }
        return code;
    }

    private static boolean hasNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * skip 模式下判断该语言是否已"满足"用户勾选的全部翻译范围：每个被勾选字段都已有非空译文。
     * 仅在用户既要翻译正文、又勾选了标题 / 简介时，会出现「正文已译但标题缺失」这类需要继续 AI 调用的情形——
     * 旧逻辑只看行存在与否，会误把该情况判为「整行已译」直接跳过。
     */
    private boolean isAllRequestedFieldsTranslated(long novelId, String langCode,
                                                   boolean translateBody, boolean translateTitle,
                                                   boolean translateDescription) {
        if (langCode == null || langCode.isBlank()) return false;
        String lang = langCode.trim();
        if (translateBody && !hasNonBlank(novelDatabase.getTranslationContent(novelId, lang))) {
            return false;
        }
        if (translateTitle && !hasNonBlank(novelDatabase.getTranslationTitle(novelId, lang))) {
            return false;
        }
        if (translateDescription && !hasNonBlank(novelDatabase.getTranslationDescription(novelId, lang))) {
            return false;
        }
        return true;
    }

    /** 单次系列名翻译能附带的「参考章节标题对」上限（控制 token 体量）。 */
    private static final int MAX_TITLE_REFERENCES = 80;

    /**
     * 收集同系列下、目标语言已有译文的「原章节标题 → 已译章节标题」对。
     * {@code preferredLang} 命中则直接用；否则尝试用 {@code targetLanguage} 与 DB 已有语言模糊匹配（取第一个非空）。
     */
    private List<TitleTranslationRequest.TitleReference> buildSeriesTitleReferences(
            long seriesId, String preferredLang, String targetLanguage) {
        List<NovelRecord> chapters = novelDatabase.getNovelsBySeriesId(seriesId);
        if (chapters.isEmpty()) return List.of();
        String lang = (preferredLang != null && !preferredLang.isBlank())
                ? preferredLang.trim() : null;
        if (lang == null && targetLanguage != null && !targetLanguage.isBlank()) {
            // 没有 hint 时按目标语言原样取一次（首段刚翻完写库后立刻调用本方法时通常能命中）
            lang = targetLanguage.trim();
        }
        if (lang == null) return List.of();
        List<TitleTranslationRequest.TitleReference> out = new ArrayList<>();
        for (NovelRecord r : chapters) {
            if (out.size() >= MAX_TITLE_REFERENCES) break;
            String source = r.title();
            if (source == null || source.isBlank()) continue;
            String target = novelDatabase.getTranslationTitle(r.novelId(), lang);
            if (target == null || target.isBlank()) continue;
            out.add(new TitleTranslationRequest.TitleReference(source, target));
        }
        return out;
    }

    /**
     * 调一次标题（+ 可选系列简介）翻译 AI；任何异常 / 不可解析回复都返回 {@code null}
     * （仅记 debug 日志，best-effort）。
     */
    private TitleTranslationResponse callTitleTranslator(String sourceTitle, String sourceDescription,
                                                         String targetLanguage,
                                                         List<GlossaryTerm> terms,
                                                         List<TitleTranslationRequest.TitleReference> references) {
        try {
            AiChatResult chat = aiService.chat(
                    TitleTranslationRequest.CALL_TYPE,
                    new TitleTranslationRequest(targetLanguage, sourceTitle, sourceDescription,
                            terms == null ? List.of() : terms,
                            references == null ? List.of() : references).toMessages(),
                    AiChatOptions.json().withTemperature(0.2));
            return TitleTranslationResponse.parse(chat.content());
        } catch (AiService.AiException | IllegalArgumentException e) {
            log.debug("title translation failed, fallback to original: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把用户输入的自由文本目标语言（如「简体中文」/ {@code english} / {@code 日本語}）转换为与
     * {@link TranslationResponse#lang()} 同一规范的 BCP-47 代码（如 {@code zh-CN} / {@code en-US}）。
     * 用于系列批量翻译开始前预解析，让首章也能凭 langHint 直接走 DB 跳过、不必为识别语言再发一次完整翻译请求。
     *
     * @return 探测得到的 BCP-47 代码；输入非真实语言、AI 关闭、调用失败等情况返回空字符串
     */
    public String resolveLangCode(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return "";
        }
        try {
            AiChatResult chat = aiService.chat(
                    LangProbeRequest.CALL_TYPE,
                    new LangProbeRequest(targetLanguage).toMessages(),
                    AiChatOptions.json().withTemperature(0.0));
            LangProbeResponse parsed = LangProbeResponse.parse(chat.content());
            return parsed.ok() ? parsed.code().trim() : "";
        } catch (AiService.AiException e) {
            // AiService 已记过失败日志，这里仅静默回退
            return "";
        }
    }

    /**
     * 源语言探测：把正文分布样本与目标语言代码发给模型，判定原文是否已是目标语言。
     * best-effort —— 样本为空 / 探测失败 / 模型无法判定时一律返回 {@code false}（照常翻译，绝不漏译）。
     */
    private boolean isSourceSameAsTarget(String raw, String targetCode) {
        String sample = languageProbeSample(raw, SAMPLE_MAX_CHARS);
        if (sample.isBlank()) {
            return false;
        }
        try {
            AiChatResult chat = aiService.chat(
                    SourceLanguageProbeRequest.CALL_TYPE,
                    new SourceLanguageProbeRequest(targetCode, sample).toMessages(),
                    AiChatOptions.json().withTemperature(0.0));
            return SourceLanguageProbeResponse.parse(chat.content()).isSame();
        } catch (AiService.AiException e) {
            // AiService 已记失败日志；探测失败不阻断翻译
            return false;
        }
    }

    /**
     * 取正文开头的一段可读文本作为语言探测样本：逐行累计，跳过空行与纯 Pixiv 标记行（如 {@code [newpage]}、
     * {@code [pixivimage:...]}），去掉行内 {@code [[...]]} / {@code [...]} 标记后只保留自然语言文本，
     * 累计到约 {@code maxChars} 字为止。
     */
    static String firstTextSample(String raw, int maxChars) {
        String text = readableText(raw);
        if (text.isBlank() || maxChars <= 0) {
            return "";
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    /**
     * 语言探测使用的保守样本：短正文直接使用全部自然语言文本；长正文从开头 / 中段 / 结尾各取一段。
     * 这样可避免只看开头题记、作者说明或引用时，把后续主体语言不同的小说误判为已是目标语言。
     */
    static String languageProbeSample(String raw, int maxChars) {
        String text = readableText(raw);
        if (text.isBlank() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String sep = " ... ";
        int separatorChars = sep.length() * 2;
        if (maxChars <= separatorChars + 3) {
            return text.substring(0, maxChars);
        }
        int section = (maxChars - separatorChars) / 3;
        int firstLen = section;
        int middleLen = section;
        int lastLen = maxChars - separatorChars - firstLen - middleLen;
        int middleStart = Math.max(firstLen, (text.length() - middleLen) / 2);
        int latestMiddleStart = Math.max(firstLen, text.length() - lastLen - middleLen);
        middleStart = Math.min(middleStart, latestMiddleStart);
        return text.substring(0, firstLen)
                + sep
                + text.substring(middleStart, middleStart + middleLen)
                + sep
                + text.substring(text.length() - lastLen);
    }

    private static String readableText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String text = stripPixivMarkup(line).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    /** 去掉 Pixiv markup 标记（{@code [[...]]} 与 {@code [...]}），仅留下用于语言判定的自然语言文本。 */
    private static String stripPixivMarkup(String line) {
        if (line == null) {
            return "";
        }
        String s = line.replaceAll("\\[\\[[^\\]]*\\]\\]", " ");
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        return s;
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
     * 仅在换行处切分，确保每个 Pixiv 标记 token 不会被截断，且分段以 {@code "\n"} 拼接后能精确还原原文结构
     * （包括位于分段边界的空行 / 尾随换行）。
     */
    static List<String> splitIntoSegments(String raw, int segmentSize) {
        List<String> segments = new ArrayList<>();
        if (segmentSize <= 0) {
            segments.add(raw);
            return segments;
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder current = new StringBuilder();
        // 用独立 flag 区分"当前段还没收过任何行"与"已收过一行（哪怕是空行）"，否则空行落在段首时
        // current 长度仍为 0，下一行会以为还没起头而漏掉它本应代表的换行 —— 拼回后丢失空行。
        boolean hasLine = false;
        for (String line : lines) {
            if (hasLine) {
                current.append('\n');
            }
            current.append(line);
            hasLine = true;
            if (current.length() >= segmentSize) {
                segments.add(current.toString());
                current.setLength(0);
                hasLine = false;
            }
        }
        if (hasLine || segments.isEmpty()) {
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
