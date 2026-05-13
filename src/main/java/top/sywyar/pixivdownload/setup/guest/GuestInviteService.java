package top.sywyar.pixivdownload.setup.guest;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.config.SslConfig;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.setup.guest.dto.HourlyBucket;
import top.sywyar.pixivdownload.setup.guest.dto.InviteCreateRequest;
import top.sywyar.pixivdownload.setup.guest.dto.InviteDetail;
import top.sywyar.pixivdownload.setup.guest.dto.InviteSummary;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 访客邀请的事务边界、惰性删除与异步统计。
 *
 * <p>状态语义：
 * <ul>
 *   <li>{@code expire_time != null && now > expire_time} 或 {@code revoked = 1}：立即物理删除，{@link #resolveByCode} 返回空。</li>
 *   <li>{@code paused = 1}：暂时不可用，{@link #resolveByCode} 返回空但记录保留以便恢复。</li>
 * </ul>
 *
 * <p>白名单分漫画/插画侧 (tag/author) 与小说侧 (novelTag/novelAuthor) 两套独立配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestInviteService {

    /** 24 字符的 base32 字母表，去掉视觉易混的 0/O/1/I。 */
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 24;
    private static final long HOUR_MILLIS = 3_600_000L;

    private final GuestInviteMapper mapper;
    private final SslConfig sslConfig;

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.port:6999}")
    private int serverPort;

    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    public void init() {
        mapper.createInvitesTable();
        mapper.createInvitesCodeIndex();
        mapper.createInviteTagsTable();
        mapper.createInviteAuthorsTable();
        mapper.createInviteNovelTagsTable();
        mapper.createInviteNovelAuthorsTable();
        mapper.createAccessStatsTable();
        mapper.createAccessStatsBucketIndex();
        // 旧库迁移：为缺少 novel_* 列的库追加列；ALTER TABLE 不支持 IF NOT EXISTS，依赖异常吞并。
        addColumnIfMissing(mapper::addNovelTagUnrestrictedColumn, "novel_tag_unrestricted");
        addColumnIfMissing(mapper::addNovelAuthorUnrestrictedColumn, "novel_author_unrestricted");
        // 一次性迁移：把漫画侧的白名单内容/开关复制到小说侧；只对 novel_* 仍为 NULL 的行生效。
        try {
            int copiedTags = mapper.copyTagsToNovelSide();
            int copiedAuthors = mapper.copyAuthorsToNovelSide();
            int updatedTagFlag = mapper.setNovelTagUnrestrictedFromLegacy();
            int updatedAuthorFlag = mapper.setNovelAuthorUnrestrictedFromLegacy();
            if (copiedTags + copiedAuthors + updatedTagFlag + updatedAuthorFlag > 0) {
                log.info("Guest invite novel-side migrated: tags={}, authors={}, tagFlag={}, authorFlag={}",
                        copiedTags, copiedAuthors, updatedTagFlag, updatedAuthorFlag);
            }
        } catch (Exception e) {
            log.warn("Guest invite novel-side migration failed: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Runnable addColumn, String columnName) {
        try {
            addColumn.run();
        } catch (Exception e) {
            // SQLite 在列已存在时会抛 "duplicate column name"，视为幂等成功。
            String msg = String.valueOf(e.getMessage());
            if (!msg.toLowerCase().contains("duplicate column")) {
                log.debug("ALTER TABLE add column {} ignored: {}", columnName, msg);
            }
        }
    }

    // ── 创建 / 编辑 / 暂停 / 删除 ─────────────────────────────────────────────

    @Transactional
    public long createInvite(InviteCreateRequest req) {
        validateAgeRating(req.isAllowSfw(), req.isAllowR18(), req.isAllowR18g());
        String name = sanitizeName(req.getName());
        Long expireTime = computeExpireTime(req.getExpireDays());

        boolean tagUnrestricted = req.isTagUnrestricted();
        Set<Long> tagIds = sanitizeIds(req.getTagIds());
        if (tagUnrestricted) tagIds.clear();

        boolean authorUnrestricted = req.isAuthorUnrestricted();
        Set<Long> authorIds = sanitizeIds(req.getAuthorIds());
        if (authorUnrestricted) authorIds.clear();

        boolean novelTagUnrestricted = req.isNovelTagUnrestricted();
        Set<Long> novelTagIds = sanitizeIds(req.getNovelTagIds());
        if (novelTagUnrestricted) novelTagIds.clear();

        boolean novelAuthorUnrestricted = req.isNovelAuthorUnrestricted();
        Set<Long> novelAuthorIds = sanitizeIds(req.getNovelAuthorIds());
        if (novelAuthorUnrestricted) novelAuthorIds.clear();

        validateWhitelistNonEmpty(
                tagUnrestricted, tagIds, authorUnrestricted, authorIds,
                novelTagUnrestricted, novelTagIds, novelAuthorUnrestricted, novelAuthorIds);

        GuestInviteRow row = new GuestInviteRow();
        row.setCode(generateUniqueCode());
        row.setName(name);
        row.setExpireTime(expireTime);
        row.setAllowSfw(req.isAllowSfw());
        row.setAllowR18(req.isAllowR18());
        row.setAllowR18g(req.isAllowR18g());
        row.setTagUnrestricted(tagUnrestricted);
        row.setAuthorUnrestricted(authorUnrestricted);
        row.setNovelTagUnrestricted(novelTagUnrestricted);
        row.setNovelAuthorUnrestricted(novelAuthorUnrestricted);
        row.setCreatedTime(System.currentTimeMillis());
        mapper.insertInvite(row);

        for (Long tagId : tagIds) mapper.insertInviteTag(row.getId(), tagId);
        for (Long authorId : authorIds) mapper.insertInviteAuthor(row.getId(), authorId);
        for (Long tagId : novelTagIds) mapper.insertInviteNovelTag(row.getId(), tagId);
        for (Long authorId : novelAuthorIds) mapper.insertInviteNovelAuthor(row.getId(), authorId);

        log.info("Guest invite created: id={}, name={}, expireTime={}", row.getId(), name, expireTime);
        return row.getId();
    }

    @Transactional
    public void updateInvite(long id, InviteCreateRequest req) {
        GuestInviteRow row = requireExisting(id);
        validateAgeRating(req.isAllowSfw(), req.isAllowR18(), req.isAllowR18g());

        boolean tagUnrestricted = req.isTagUnrestricted();
        Set<Long> tagIds = sanitizeIds(req.getTagIds());
        if (tagUnrestricted) tagIds.clear();
        boolean authorUnrestricted = req.isAuthorUnrestricted();
        Set<Long> authorIds = sanitizeIds(req.getAuthorIds());
        if (authorUnrestricted) authorIds.clear();
        boolean novelTagUnrestricted = req.isNovelTagUnrestricted();
        Set<Long> novelTagIds = sanitizeIds(req.getNovelTagIds());
        if (novelTagUnrestricted) novelTagIds.clear();
        boolean novelAuthorUnrestricted = req.isNovelAuthorUnrestricted();
        Set<Long> novelAuthorIds = sanitizeIds(req.getNovelAuthorIds());
        if (novelAuthorUnrestricted) novelAuthorIds.clear();

        validateWhitelistNonEmpty(
                tagUnrestricted, tagIds, authorUnrestricted, authorIds,
                novelTagUnrestricted, novelTagIds, novelAuthorUnrestricted, novelAuthorIds);

        row.setName(sanitizeName(req.getName()));
        row.setExpireTime(computeExpireTime(req.getExpireDays()));
        row.setAllowSfw(req.isAllowSfw());
        row.setAllowR18(req.isAllowR18());
        row.setAllowR18g(req.isAllowR18g());
        row.setTagUnrestricted(tagUnrestricted);
        row.setAuthorUnrestricted(authorUnrestricted);
        row.setNovelTagUnrestricted(novelTagUnrestricted);
        row.setNovelAuthorUnrestricted(novelAuthorUnrestricted);
        mapper.updateInviteCore(row);

        mapper.deleteInviteTags(id);
        mapper.deleteInviteAuthors(id);
        mapper.deleteInviteNovelTags(id);
        mapper.deleteInviteNovelAuthors(id);
        for (Long tagId : tagIds) mapper.insertInviteTag(id, tagId);
        for (Long authorId : authorIds) mapper.insertInviteAuthor(id, authorId);
        for (Long tagId : novelTagIds) mapper.insertInviteNovelTag(id, tagId);
        for (Long authorId : novelAuthorIds) mapper.insertInviteNovelAuthor(id, authorId);
    }

    @Transactional
    public void pause(long id) {
        requireExisting(id);
        mapper.updatePaused(id, true);
    }

    @Transactional
    public void resume(long id) {
        requireExisting(id);
        mapper.updatePaused(id, false);
    }

    @Transactional
    public void delete(long id) {
        requireExisting(id);
        mapper.purgeInviteTags(id);
        mapper.purgeInviteAuthors(id);
        mapper.purgeInviteNovelTags(id);
        mapper.purgeInviteNovelAuthors(id);
        mapper.purgeInviteAccessStats(id);
        mapper.deleteInvite(id);
    }

    @Transactional
    public int deleteExpired(long now) {
        List<Long> ids = mapper.findExpiredIds(now);
        for (Long id : ids) {
            mapper.purgeInviteTags(id);
            mapper.purgeInviteAuthors(id);
            mapper.purgeInviteNovelTags(id);
            mapper.purgeInviteNovelAuthors(id);
            mapper.purgeInviteAccessStats(id);
            mapper.deleteInvite(id);
        }
        return ids.size();
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    public List<InviteSummary> list() {
        List<GuestInviteRow> rows = mapper.findAll();
        List<InviteSummary> out = new ArrayList<>(rows.size());
        for (GuestInviteRow row : rows) {
            out.add(toSummary(row));
        }
        return out;
    }

    public InviteDetail detail(long id) {
        GuestInviteRow row = requireExisting(id);
        List<InviteDetail.TagBrief> tags = mapper.findInviteTags(id).stream()
                .map(t -> new InviteDetail.TagBrief(t.tagId(), t.name(), t.translatedName()))
                .toList();
        List<InviteDetail.AuthorBrief> authors = mapper.findInviteAuthors(id).stream()
                .map(a -> new InviteDetail.AuthorBrief(a.authorId(), a.name()))
                .toList();
        List<InviteDetail.TagBrief> novelTags = mapper.findInviteNovelTags(id).stream()
                .map(t -> new InviteDetail.TagBrief(t.tagId(), t.name(), t.translatedName()))
                .toList();
        List<InviteDetail.AuthorBrief> novelAuthors = mapper.findInviteNovelAuthors(id).stream()
                .map(a -> new InviteDetail.AuthorBrief(a.authorId(), a.name()))
                .toList();
        return InviteDetail.builder()
                .id(row.getId())
                .code(row.getCode())
                .url(buildInviteUrl(row.getCode()))
                .name(row.getName())
                .expireTime(row.getExpireTime())
                .allowSfw(row.isAllowSfw())
                .allowR18(row.isAllowR18())
                .allowR18g(row.isAllowR18g())
                .tagUnrestricted(row.isTagUnrestricted())
                .authorUnrestricted(row.isAuthorUnrestricted())
                .novelTagUnrestricted(resolveNovelTagUnrestricted(row))
                .novelAuthorUnrestricted(resolveNovelAuthorUnrestricted(row))
                .paused(row.isPaused())
                .used(row.getFirstUsedTime() != null)
                .totalRequestCount(row.getTotalRequestCount())
                .firstUsedTime(row.getFirstUsedTime())
                .lastUsedTime(row.getLastUsedTime())
                .createdTime(row.getCreatedTime())
                .tags(tags)
                .authors(authors)
                .novelTags(novelTags)
                .novelAuthors(novelAuthors)
                .build();
    }

    /**
     * 邀请码兑换。命中过期/已吊销则物理删除并返回空；命中暂停则返回空但保留记录。
     */
    @Transactional
    public Optional<GuestInviteSession> resolveByCode(String code) {
        if (code == null) return Optional.empty();
        String normalized = code.trim().toUpperCase();
        if (normalized.isEmpty()) return Optional.empty();

        GuestInviteRow row = mapper.findByCode(normalized);
        if (row == null) return Optional.empty();

        long now = System.currentTimeMillis();
        if (row.isRevoked() || (row.getExpireTime() != null && now > row.getExpireTime())) {
            mapper.purgeInviteTags(row.getId());
            mapper.purgeInviteAuthors(row.getId());
            mapper.purgeInviteNovelTags(row.getId());
            mapper.purgeInviteNovelAuthors(row.getId());
            mapper.purgeInviteAccessStats(row.getId());
            mapper.deleteInvite(row.getId());
            return Optional.empty();
        }
        if (row.isPaused()) return Optional.empty();

        boolean novelTagUnrestricted = resolveNovelTagUnrestricted(row);
        boolean novelAuthorUnrestricted = resolveNovelAuthorUnrestricted(row);

        Set<Long> tagIds = row.isTagUnrestricted()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(mapper.findInviteTagIds(row.getId())));
        Set<Long> authorIds = row.isAuthorUnrestricted()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(mapper.findInviteAuthorIds(row.getId())));
        Set<Long> novelTagIds = novelTagUnrestricted
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(mapper.findInviteNovelTagIds(row.getId())));
        Set<Long> novelAuthorIds = novelAuthorUnrestricted
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(mapper.findInviteNovelAuthorIds(row.getId())));

        return Optional.of(new GuestInviteSession(
                row.getId(),
                row.getCode(),
                row.isAllowSfw(),
                row.isAllowR18(),
                row.isAllowR18g(),
                row.isTagUnrestricted(),
                tagIds,
                row.isAuthorUnrestricted(),
                authorIds,
                novelTagUnrestricted,
                novelTagIds,
                novelAuthorUnrestricted,
                novelAuthorIds));
    }

    /**
     * 异步累加访问统计：upsert 当前小时桶 + 累计计数。失败仅记日志，不影响主请求。
     */
    @Async
    public void recordHit(long inviteId) {
        try {
            long now = System.currentTimeMillis();
            mapper.upsertAccessStat(inviteId, now / HOUR_MILLIS);
            mapper.incrementUsage(inviteId, now);
        } catch (Exception e) {
            log.warn("Failed to record guest invite hit for id={}: {}", inviteId, e.getMessage());
        }
    }

    /** 返回最近 days 天的小时桶序列，缺失桶补 0。days 允许 1 / 7 / 30。 */
    public List<HourlyBucket> getAccessStats(long id, int days) {
        requireExisting(id);
        int hours = (switch (days) {
            case 1 -> 1;
            case 30 -> 30;
            default -> 7;
        }) * 24;
        long currentBucket = System.currentTimeMillis() / HOUR_MILLIS;
        long fromBucket = currentBucket - hours + 1;
        var rows = mapper.findAccessStats(id, fromBucket);
        long[] counts = new long[hours];
        for (var row : rows) {
            int idx = (int) (row.bucketHour() - fromBucket);
            if (idx >= 0 && idx < hours) counts[idx] = row.requestCount();
        }
        List<HourlyBucket> buckets = new ArrayList<>(hours);
        for (int i = 0; i < hours; i++) {
            buckets.add(new HourlyBucket((fromBucket + i) * HOUR_MILLIS, counts[i]));
        }
        return buckets;
    }

    // ── 维护任务回调 ─────────────────────────────────────────────────────────

    /**
     * 物理删除已过期或已吊销的邀请，及其关联表与小时桶。返回清理的邀请数量。
     */
    @Transactional
    public int purgeExpiredAndRevoked(long now) {
        List<Long> ids = mapper.findExpiredOrRevokedIds(now);
        for (Long id : ids) {
            mapper.purgeInviteTags(id);
            mapper.purgeInviteAuthors(id);
            mapper.purgeInviteNovelTags(id);
            mapper.purgeInviteNovelAuthors(id);
            mapper.purgeInviteAccessStats(id);
        }
        if (!ids.isEmpty()) {
            mapper.deleteExpiredOrRevoked(now);
        }
        return ids.size();
    }

    /** 删除超过 30 天（720 小时）的访问统计桶。 */
    @Transactional
    public int trimOldAccessStats() {
        long minBucket = System.currentTimeMillis() / HOUR_MILLIS - 720;
        return mapper.deleteAccessStatsOlderThan(minBucket);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private GuestInviteRow requireExisting(long id) {
        GuestInviteRow row = mapper.findById(id);
        if (row == null) {
            throw new LocalizedException(HttpStatus.NOT_FOUND,
                    "guest.invite.not-found",
                    "邀请不存在");
        }
        return row;
    }

    /** 兼容旧库：迁移失败 / 列读出 NULL 时，回退到漫画侧的 unrestricted 值。 */
    private boolean resolveNovelTagUnrestricted(GuestInviteRow row) {
        return row.getNovelTagUnrestricted() == null
                ? row.isTagUnrestricted()
                : row.getNovelTagUnrestricted();
    }

    private boolean resolveNovelAuthorUnrestricted(GuestInviteRow row) {
        return row.getNovelAuthorUnrestricted() == null
                ? row.isAuthorUnrestricted()
                : row.getNovelAuthorUnrestricted();
    }

    private InviteSummary toSummary(GuestInviteRow row) {
        return InviteSummary.builder()
                .id(row.getId())
                .code(row.getCode())
                .name(row.getName())
                .expireTime(row.getExpireTime())
                .allowSfw(row.isAllowSfw())
                .allowR18(row.isAllowR18())
                .allowR18g(row.isAllowR18g())
                .tagUnrestricted(row.isTagUnrestricted())
                .authorUnrestricted(row.isAuthorUnrestricted())
                .novelTagUnrestricted(resolveNovelTagUnrestricted(row))
                .novelAuthorUnrestricted(resolveNovelAuthorUnrestricted(row))
                .paused(row.isPaused())
                .used(row.getFirstUsedTime() != null)
                .totalRequestCount(row.getTotalRequestCount())
                .firstUsedTime(row.getFirstUsedTime())
                .lastUsedTime(row.getLastUsedTime())
                .createdTime(row.getCreatedTime())
                .build();
    }

    private void validateAgeRating(boolean sfw, boolean r18, boolean r18g) {
        if (!sfw && !r18 && !r18g) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.age-rating.empty",
                    "请至少选择一个可见的年龄分级");
        }
    }

    /**
     * 至少存在一项白名单允许可见（漫画或小说任一维度），否则拒绝创建/更新。
     */
    private void validateWhitelistNonEmpty(
            boolean tagUnrestricted, Set<Long> tagIds,
            boolean authorUnrestricted, Set<Long> authorIds,
            boolean novelTagUnrestricted, Set<Long> novelTagIds,
            boolean novelAuthorUnrestricted, Set<Long> novelAuthorIds) {
        boolean anyAllowed = tagUnrestricted || !tagIds.isEmpty()
                || authorUnrestricted || !authorIds.isEmpty()
                || novelTagUnrestricted || !novelTagIds.isEmpty()
                || novelAuthorUnrestricted || !novelAuthorIds.isEmpty();
        if (!anyAllowed) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.whitelist.empty",
                    "未选择任何可见标签或可见作者");
        }
    }

    private String sanitizeName(String name) {
        if (name == null) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.name.required", "请填写访客名称");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.name.required", "请填写访客名称");
        }
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private Long computeExpireTime(Integer expireDays) {
        if (expireDays == null) return null; // 永久
        if (expireDays <= 0) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "guest.invite.expire.invalid", "有效期天数必须为正整数");
        }
        return System.currentTimeMillis() + expireDays * 86_400_000L;
    }

    private Set<Long> sanitizeIds(List<Long> ids) {
        Set<Long> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (Long id : ids) {
            if (id != null && id > 0) out.add(id);
        }
        return out;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = randomCode();
            if (mapper.findByCode(code) == null) return code;
        }
        throw new IllegalStateException("Failed to generate unique invite code after 8 attempts");
    }

    private String randomCode() {
        char[] buf = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            buf[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(buf);
    }

    /**
     * 严格按 CLAUDE.md 约束动态构造对外 URL，不硬编码 scheme 或 host。
     */
    private String buildInviteUrl(String code) {
        String scheme = sslEnabled ? "https" : "http";
        String domain = sslConfig.getDomain();
        if (domain == null || domain.isBlank()) domain = "localhost";
        StringBuilder sb = new StringBuilder().append(scheme).append("://").append(domain);
        boolean defaultPort = (sslEnabled && serverPort == 443) || (!sslEnabled && serverPort == 80);
        if (!defaultPort) sb.append(':').append(serverPort);
        sb.append("/invite?code=").append(code);
        return sb.toString();
    }
}
