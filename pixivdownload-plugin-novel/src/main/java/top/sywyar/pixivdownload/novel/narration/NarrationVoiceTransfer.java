package top.sywyar.pixivdownload.novel.narration;

import top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution;
import java.io.*;

/** 参考音随所属花名册数据库迁移，音频正文保持流式复制。 */
public final class NarrationVoiceTransfer implements EnvironmentFileContribution {
    @Override public Definition definition() {
        return new Definition("reference-voices", Root.DATA, "narration-voice", "[1-9][0-9]*/[0-9]+\\.(wav|mp3|pcm)", 2,
                Kind.RESOURCE, 1, 32L * 1024 * 1024, true);
    }
    @Override public void copy(String name, InputStream source, InputStream existing, OutputStream output,
                               boolean includeCredentials) throws IOException {
        source.transferTo(output);
    }
}
