package top.sywyar.pixivdownload.setup.guest.dto;

import lombok.Data;

import java.util.List;

@Data
public class InviteCreateRequest {

    /** 访客名称（用于记忆，不对外展示）。 */
    private String name;

    /** 有效期天数；{@code null} 表示永久。 */
    private Integer expireDays;

    private boolean allowSfw = true;
    private boolean allowR18;
    private boolean allowR18g;

    /** 漫画/插画侧可见标签：{@code true} 表示所有标签可见；{@code false} 时仅 {@link #tagIds} 内可见。 */
    private boolean tagUnrestricted = true;
    private List<Long> tagIds;

    /** 漫画/插画侧可见作者：{@code true} 表示所有作者可见；{@code false} 时仅 {@link #authorIds} 内可见。 */
    private boolean authorUnrestricted = true;
    private List<Long> authorIds;

    /** 小说侧可见标签：{@code true} 表示所有标签可见；{@code false} 时仅 {@link #novelTagIds} 内可见。 */
    private boolean novelTagUnrestricted = true;
    private List<Long> novelTagIds;

    /** 小说侧可见作者：{@code true} 表示所有作者可见；{@code false} 时仅 {@link #novelAuthorIds} 内可见。 */
    private boolean novelAuthorUnrestricted = true;
    private List<Long> novelAuthorIds;
}
