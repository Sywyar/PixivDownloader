package top.sywyar.pixivdownload.tts.narration.controller;

/**
 * 多角色朗读 TTS 试听端点（{@code POST /api/narration/tts/preview}）的请求体。
 *
 * @param text               要朗读的文本
 * @param controlInstruction 音色描述（VoxCPM 内联 voice-design 用；可为空）
 */
public record NarrationTtsPreviewRequest(String text, String controlInstruction) {
}
