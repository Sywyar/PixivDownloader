package acceptance;

import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaStore;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.download.media.ImageOutputService;
import top.sywyar.pixivdownload.download.media.MediaOutputSettings;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs both workbench media paths against the HTTP acceptance instance's actual filesystem and stores. */
public final class ArtworkRecoveryMediaFixture {
    public static void processImages(ArtworkMediaStore media, FileTransactionService transactions, DownloadPathGuard paths, Path recovered,
                                     Path normal) throws Exception {
        var encodings = new AtomicInteger();
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            if (tool != FfmpegRunner.Tool.FFMPEG) throw new AssertionError("Unexpected encoder: " + tool);
            Path input = Path.of(args.get(args.indexOf("-i") + 1));
            if (!ImageIO.write(ImageIO.read(input.toFile()), "png", output.toFile())) throw new AssertionError("PNG encoder unavailable");
            encodings.incrementAndGet();
            return "";
        };
        var processor = new ImageOutputService(runner, JsonMapper.shared(), media, transactions);
        long recoveredId = Long.parseLong(recovered.getFileName().toString());
        processor.addMissingFormats(recoveredId, 0, recovered.resolve(recoveredId + "_p0.jpg"), "png", () -> false, paths);
        processor.addMissingFormats(recoveredId, 1, recovered.resolve(recoveredId + "_p1.png"), "png", () -> false, paths);
        if (encodings.get() != 1) throw new AssertionError("Expected only the recovered JPEG to require encoding");
        long normalId = Long.parseLong(normal.getFileName().toString());
        var settings = new MediaOutputSettings();
        settings.setImageFormats("original");
        for (int page = 0; page < 2; page++) {
            processor.process(normalId, page, normal.resolve(normalId + "_p" + page), page == 0 ? "jpg" : "png", settings, () -> false);
        }
    }
}
