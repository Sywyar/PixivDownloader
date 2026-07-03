package top.sywyar.pixivdownload.core.hash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.core.asset.artwork.ArtworkFileLocator;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 作品图片感知哈希的计算与落库——核心资产索引能力，不属任何功能插件。
 * <p>
 * 下载后即时算 Hash 是核心下载链路的一部分（{@code ArtworkDownloadExecutor} 直接注入本服务），新作品落盘后即写入
 * {@code artwork_image_hashes}（schema 由核心 {@code ImageHashSchemaContribution} 声明）。本服务由根包扫描装配
 * （{@code @Service}），<b>不随任何 {@code plugins.<id>.enabled} 开关缺席</b>；疑似重复检测插件被禁用时，下载仍照常写 Hash。
 * <p>
 * 疑似重复 UI 的分组缓存由查询侧按数据库 fingerprint（行数 + 最大 created_time）自失效；故本核心写入服务<b>不依赖</b>
 * duplicate 插件的任何 UI 服务、也不主动失效其缓存——
 * 写 Hash 改变了 fingerprint，下一次查询自然重建。重复检测的批量回填 / 手动重扫沿用本服务做计算（正向 plugin→core 依赖）。
 */
@Slf4j
@Service
public class ArtworkHashService {

    private final ImageHashMapper imageHashMapper;
    private final ArtworkFileLocator artworkFileLocator;
    private final AppMessages messages;
    private final TransactionTemplate transactionTemplate;

    public ArtworkHashService(ImageHashMapper imageHashMapper,
                              ArtworkFileLocator artworkFileLocator,
                              AppMessages messages,
                              PlatformTransactionManager transactionManager) {
        this.imageHashMapper = imageHashMapper;
        this.artworkFileLocator = artworkFileLocator;
        this.messages = messages;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 单页哈希的计算结果。{@code hashes == null} 表示该页不可哈希（文件缺失 / 解码失败），落库时写页级哨兵。 */
    private record PageHashResult(int page, String extension, ImageHasher.Hashes hashes) {}

    public int recordArtworkHashes(ArtworkRecord artwork) {
        if (artwork == null) {
            return 0;
        }
        int written = 0;
        try {
            // 解码 + 计算哈希在事务外完成（耗时部分），避免在 SQLite 单写者写锁内做图片解码、
            // 长时间阻塞并发下载落库。
            int pageCount = Math.max(artwork.count(), 0);
            List<PageHashResult> results = new ArrayList<>(pageCount);
            for (int page = 0; page < pageCount; page++) {
                results.add(computePageHash(artwork, page));
            }
            // 落库放进一个短事务里整作品原子完成：删旧 + 写新（含逐页“无哈希”哨兵）。进程被强杀只会
            // 整体回滚为旧状态，不会留下“删了旧的、新的只写一半”的中间态；因解码已在事务外完成，
            // 写锁仅在这几条 SQL 期间短暂持有。
            long now = System.currentTimeMillis();
            Integer count = transactionTemplate.execute(status ->
                    persistArtworkHashes(artwork.artworkId(), results, now));
            written = count == null ? 0 : count;
        } catch (Exception e) {
            log.warn(messages.getForLog("duplicate.log.hash.artwork-failed",
                    artwork.artworkId(), e.getMessage()), e);
        }
        return written;
    }

    private int persistArtworkHashes(long artworkId, List<PageHashResult> results, long now) {
        imageHashMapper.deleteByArtwork(artworkId);
        int written = 0;
        for (PageHashResult result : results) {
            if (result.hashes() != null) {
                imageHashMapper.upsert(artworkId, result.page(), result.extension(),
                        result.hashes().dHash(), result.hashes().aHash(), now);
                written++;
            } else {
                imageHashMapper.markPageNoHash(artworkId, result.page(), now);
            }
        }
        if (results.isEmpty() || written == 0) {
            // 没有任何可哈希的页（文件缺失 / 解码失败 / 不支持的格式）：写入「已尝试」哨兵行，
            // 避免该作品在每次维护回填/扫描时被反复重试。
            imageHashMapper.markNoHash(artworkId, now);
        }
        return written;
    }

    private PageHashResult computePageHash(ArtworkRecord artwork, int page) {
        try {
            ArtworkFileLocator.LocatedArtworkFile source = artworkFileLocator.resolveHashSourceFile(artwork, page);
            if (source == null) {
                log.warn(messages.getForLog("duplicate.log.hash.source-missing", artwork.artworkId(), page));
                return new PageHashResult(page, null, null);
            }
            Optional<ImageHasher.Hashes> hashes = ImageHasher.hash(source.file().toPath());
            if (hashes.isEmpty()) {
                log.warn(messages.getForLog("duplicate.log.hash.decode-failed",
                        artwork.artworkId(), page, source.file().getAbsolutePath()));
                return new PageHashResult(page, null, null);
            }
            return new PageHashResult(page, source.extension(), hashes.get());
        } catch (Exception e) {
            log.warn(messages.getForLog("duplicate.log.hash.page-failed",
                    artwork.artworkId(), page, e.getMessage()), e);
            return new PageHashResult(page, null, null);
        }
    }
}
