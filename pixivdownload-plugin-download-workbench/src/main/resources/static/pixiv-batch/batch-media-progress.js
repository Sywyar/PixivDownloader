'use strict';

// 两版队列在原有进度区域呈现相同的媒体阶段；模型只保存机器码。
function mediaProgressLabel(progress) {
    const keys = {
        'ffmpeg-waiting': 'queue.media.waiting',
        ffmpeg: 'queue.media.converting',
        verifying: 'queue.media.verifying',
        thumbnail: 'queue.media.thumbnail',
        finalizing: 'queue.media.finalizing'
    };
    const key = keys[progress && progress.phase];
    if (!key) return '';
    const parts = [bt(key, null)];
    if (progress.outputFormat) parts.push(String(progress.outputFormat).toUpperCase());
    if (Number.isInteger(progress.outputIndex) && Number.isInteger(progress.outputCount)
            && progress.outputIndex > 0 && progress.outputIndex <= progress.outputCount) {
        parts.push(progress.outputIndex + '/' + progress.outputCount);
    }
    return parts.join(' · ');
}
