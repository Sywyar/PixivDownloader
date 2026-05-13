package top.sywyar.pixivdownload.setup.guest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MyBatis 与 guest_invites 表对应的可变行对象。{@code Boolean} 字段允许 SQLite 0/1 自动映射。
 * 不直接对外暴露——供 {@link GuestInviteMapper} 与 {@link GuestInviteService} 内部使用。
 *
 * <p>{@code novelTagUnrestricted} / {@code novelAuthorUnrestricted} 使用包装类型，
 * 是因为旧库迁移期间这两列允许 NULL，{@code GuestInviteService.init} 会按漫画侧值补齐。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuestInviteRow {

    private long id;
    private String code;
    private String name;
    private Long expireTime;
    private boolean allowSfw;
    private boolean allowR18;
    private boolean allowR18g;
    private boolean tagUnrestricted;
    private boolean authorUnrestricted;
    private Boolean novelTagUnrestricted;
    private Boolean novelAuthorUnrestricted;
    private long createdTime;
    private boolean paused;
    private boolean revoked;
    private Long firstUsedTime;
    private Long lastUsedTime;
    private long totalRequestCount;
}
