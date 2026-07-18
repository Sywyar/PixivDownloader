package top.sywyar.pixivdownload.novel.narration.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;

/**
 * 朗读引擎<b>无关</b>的「短静音」音频工具（纯静态）：为<b>纯标点 / 无可发音内容</b>的脚本行（如小说里独立成段的
 * 「……」「！？」）生成一段极短的静音 WAV，作为<b>可跳过的停顿</b>返回，避免这类行在合成阶段抛
 * {@link NarrationVoiceException} 中断整条朗读链路。
 *
 * <p>由 {@code NarrationAudioService.synthesizeLine} 在归一文本为空时调用：前端把这段静音当作普通音频播放，
 * 极短播放结束后自动续到下一句——既保留了原文里那处停顿的语义，又不打断连续朗读。预览 / 种子音等<b>非脚本行</b>
 * 路径仍按空文本报错，不走此路。
 */
final class NarrationSilence {

    /** 静音采样率（Hz）：16kHz 单声道足够承载一段无声停顿，字节量极小。 */
    private static final int SAMPLE_RATE = 16_000;
    /** 静音时长（毫秒）：对应原文里一处停顿，短到不拖慢连续朗读。 */
    private static final int PAUSE_MS = 300;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    /** PCM WAV 头长度（RIFF/fmt /data 定长头）。 */
    private static final int HEADER_BYTES = 44;

    private NarrationSilence() {
    }

    /** 一段约 {@value #PAUSE_MS}ms 的 16-bit 单声道静音 WAV（{@code audio/wav}），用作可跳过的停顿。 */
    public static NarrationAudio shortPause() {
        return new NarrationAudio(silentWav(), "audio/wav");
    }

    /** 构造一段全零样本的标准 PCM WAV（小端序）。 */
    private static byte[] silentWav() {
        int blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8);
        int dataBytes = (SAMPLE_RATE * PAUSE_MS / 1000) * blockAlign;
        int byteRate = SAMPLE_RATE * blockAlign;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(HEADER_BYTES - 8 + dataBytes); // RIFF chunk size = 文件长度 - 8
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16);                            // fmt 子块长度（PCM）
        buf.putShort((short) 1);                   // 音频格式 = PCM
        buf.putShort((short) CHANNELS);
        buf.putInt(SAMPLE_RATE);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) BITS_PER_SAMPLE);
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(dataBytes);
        // 余下数据段保持 ByteBuffer 初始的全 0（静音）。
        return buf.array();
    }
}
