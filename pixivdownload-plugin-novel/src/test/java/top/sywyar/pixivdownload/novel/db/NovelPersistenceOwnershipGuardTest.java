package top.sywyar.pixivdownload.novel.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesCatalogRow;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesTagRow;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说持久化行归属守卫")
class NovelPersistenceOwnershipGuardTest {

    @Test
    @DisplayName("NovelMapper 返回的项目类型必须与 Mapper 位于同一插件构件")
    void mapperProjectResultTypesBelongToNovelArtifact() {
        URL mapperOwner = codeSourceOf(NovelMapper.class);
        Set<Class<?>> projectResultTypes = new LinkedHashSet<>();
        for (var method : NovelMapper.class.getDeclaredMethods()) {
            collectProjectTypes(method.getGenericReturnType(), projectResultTypes);
        }

        assertThat(projectResultTypes)
                .as("守卫必须实际覆盖小说 Mapper 的项目结果类型")
                .contains(
                        NovelRecord.class,
                        NovelSeries.class,
                        NovelTagRow.class,
                        NovelSeriesCatalogRow.class,
                        NovelSeriesTagRow.class,
                        NovelMapper.NovelWorkDetailsRow.class,
                        NovelMapper.NovelWorkDetailValueRow.class);
        assertThat(projectResultTypes)
                .allSatisfy(type -> assertThat(codeSourceOf(type))
                        .as(type.getName() + " 的构件 owner")
                        .isEqualTo(mapperOwner));
    }

    private static void collectProjectTypes(Type type, Set<Class<?>> sink) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                collectProjectTypes(clazz.getComponentType(), sink);
            } else if (clazz.getName().startsWith("top.sywyar.pixivdownload.")) {
                sink.add(clazz);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            collectProjectTypes(parameterizedType.getRawType(), sink);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                collectProjectTypes(argument, sink);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            collectProjectTypes(arrayType.getGenericComponentType(), sink);
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getUpperBounds()) {
                collectProjectTypes(bound, sink);
            }
            for (Type bound : wildcardType.getLowerBounds()) {
                collectProjectTypes(bound, sink);
            }
        }
    }

    private static URL codeSourceOf(Class<?> type) {
        assertThat(type.getProtectionDomain()).as(type.getName() + " protection domain").isNotNull();
        assertThat(type.getProtectionDomain().getCodeSource()).as(type.getName() + " code source").isNotNull();
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
