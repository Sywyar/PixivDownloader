package top.sywyar.pixivdownload.novel.db;

/**
 * 某花名册角色的<b>参考音 / 标准音</b>元数据（{@code novel_narration_voices} 的参考音相关列）。音频字节本身存盘于
 * {@code data/novel/narration-voice/{castId}/{characterId}.{ext}}，本记录只承载库内元信息。
 *
 * @param castId      花名册 ID
 * @param characterId 角色 ID（0=旁白）
 * @param ext         参考音文件扩展名（{@code wav}/{@code mp3}）；为 {@code null} 表示未配参考音
 * @param text        参考音转录文本（{@code ref_text}，作克隆 in-context 提示），可为 {@code null}
 * @param source      来源：{@code auto}=自动生成的标准音、{@code upload}=用户上传，可为 {@code null}
 * @param time        参考音生成 / 上传时间（Unix epoch 毫秒），可为 {@code null}
 */
public record NovelNarrationVoiceRef(
        long castId,
        int characterId,
        String ext,
        String text,
        String source,
        Long time
) {

    /** 是否已配置参考音（有扩展名即视为已配）。 */
    public boolean present() {
        return ext != null && !ext.isBlank();
    }
}
