package top.sywyar.pixivdownload.push;

/**
 * 推送「发送格式」的稳定枚举——整个格式化系统的一等类型。两类用途：
 * <ul>
 *   <li>{@link PushMessage#sourceFormat() 源格式}：业务侧撰写消息时使用的格式（只能是文本类的
 *       {@link #PLAIN_TEXT} / {@link #MARKDOWN} / {@link #HTML}）；</li>
 *   <li>{@link PushChannel#supportedFormats() 通道支持格式}：每个通道按优先级声明自己能渲染的格式，由
 *       {@link PushFormatConverter#negotiate} 据此协商出目标格式。</li>
 * </ul>
 *
 * <p>{@link #CARD} 是<b>结构化</b>目标格式：它没有通道无关的源表示，<b>只能作为目标</b>，由声明支持它的通道
 * （如飞书 interactive 卡片）用 title / 正文 / level 自行构建；其卡片正文以 {@link #MARKDOWN} 内联文本承载。
 * 因此 {@code CARD} 可达 ⟺ {@code MARKDOWN} 可达（见 {@link PushFormatConverter}）。
 */
public enum PushFormat {

    /** 纯文本。任意源恒可转换到此格式，是所有通道的<b>兜底</b>格式。 */
    PLAIN_TEXT,

    /** 轻量 Markdown（业务撰写的默认源格式）。 */
    MARKDOWN,

    /** HTML 富文本（如 Telegram 的 {@code parse_mode=HTML}）。 */
    HTML,

    /** 结构化卡片（通道自行构建，正文以 Markdown 内联承载）。仅作目标格式，不可作源。 */
    CARD
}
