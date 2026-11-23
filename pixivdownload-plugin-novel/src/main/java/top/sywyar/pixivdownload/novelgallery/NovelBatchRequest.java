package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.core.web.HttpParameterException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.node.BooleanNode;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import java.util.List;

public record NovelBatchRequest(
        @HttpApiNullable @Schema(defaultValue = "ids", examples = {"ids", "filter"},
                pattern = "^([iI][dD][sS]|[fF][iI][lL][tT][eE][rR]|\\s*)$") String mode,
        @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> ids,
        @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> excludeIds,
        @Valid Filter filter,
        @JsonDeserialize(using = ExactInteger.class) @Positive Long collectionId,
        String groupBy,
        String format,
        @HttpApiNullable @JsonSetter(nulls = Nulls.SKIP) @Schema(implementation = Boolean.class) BooleanNode deleteAfter,
        String variant,
        @HttpApiNullable @JsonSetter(nulls = Nulls.SKIP) @Schema(implementation = Boolean.class) BooleanNode confirmAll
) {
    // 破坏性操作只接受 JSON 布尔值。直接绑定 Boolean 会将非零数值和字符串也转换为 true。
    public boolean confirmsAll() {
        return BooleanNode.TRUE.equals(confirmAll);
    }

    public boolean deletesAfterExport() {
        return BooleanNode.TRUE.equals(deleteAfter);
    }

    public boolean filterMode() {
        if (mode == null || mode.isBlank() || "ids".equalsIgnoreCase(mode)) return false;
        if (!"filter".equalsIgnoreCase(mode)) throw new HttpParameterException("body", "/mode", "invalid_value");
        if (filter == null) throw new HttpParameterException("body", "/filter", "required");
        return true;
    }

    /** 作品选择的 JSON 整数必须保留原值；数值截断可能选中另一条记录。 */
    public static final class ExactInteger extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class, "Expected a JSON integer");
            return parser.getLongValue();
        }
    }

    public record Filter(Integer page,
            Integer size,
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> collectionIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> tagIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> notTagIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> orTagIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> authorIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> notAuthorIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> orAuthorIds,
            @JsonDeserialize(using = ExactInteger.class) @Positive Long authorId,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> seriesIds,
            @JsonDeserialize(contentUsing = ExactInteger.class) List<@NotNull @Positive Long> notSeriesIds,
            @JsonDeserialize(using = ExactInteger.class) @Positive Long seriesId,
            @JsonDeserialize(using = ExactInteger.class) Long uploadedFrom,
            @JsonDeserialize(using = ExactInteger.class) Long uploadedBefore,
            @JsonDeserialize(using = ExactInteger.class) Long downloadedFrom,
            @JsonDeserialize(using = ExactInteger.class) Long downloadedBefore, String sourceTimeField,
            @JsonDeserialize(using = ExactInteger.class) Long createdFrom,
            @JsonDeserialize(using = ExactInteger.class) Long createdBefore,
            @JsonDeserialize(using = ExactInteger.class) Long updatedFrom,
            @JsonDeserialize(using = ExactInteger.class) Long updatedBefore,
            @JsonDeserialize(using = ExactInteger.class) Long reuploadedFrom,
            @JsonDeserialize(using = ExactInteger.class) Long reuploadedBefore) {
        public Filter(Integer page,
            Integer size,
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            List<Long> collectionIds,
            List<Long> tagIds,
            List<Long> notTagIds,
            List<Long> orTagIds,
            List<Long> authorIds,
            List<Long> notAuthorIds,
            List<Long> orAuthorIds,
            Long authorId,
            List<Long> seriesIds,
            List<Long> notSeriesIds,
            Long seriesId,
            Long uploadedFrom,
            Long uploadedBefore,
            Long downloadedFrom,
            Long downloadedBefore) {
            this(page, size, sort, order, search, searchType, r18, ai, collectionIds, tagIds, notTagIds, orTagIds, authorIds, notAuthorIds, orAuthorIds, authorId, seriesIds, notSeriesIds, seriesId, uploadedFrom, uploadedBefore, downloadedFrom, downloadedBefore, null, null, null, null, null, null, null);
        }

        public Filter(Integer page,
            Integer size,
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            List<Long> collectionIds,
            List<Long> tagIds,
            List<Long> notTagIds,
            List<Long> orTagIds,
            List<Long> authorIds,
            List<Long> notAuthorIds,
            List<Long> orAuthorIds,
            Long authorId,
            List<Long> seriesIds,
            List<Long> notSeriesIds,
            Long seriesId) {
            this(page, size, sort, order, search, searchType, r18, ai, collectionIds, tagIds, notTagIds, orTagIds, authorIds, notAuthorIds, orAuthorIds, authorId, seriesIds, notSeriesIds, seriesId, null, null, null, null);
        }
    }
}
