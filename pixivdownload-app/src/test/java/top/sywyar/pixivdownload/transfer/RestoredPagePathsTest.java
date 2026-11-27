package top.sywyar.pixivdownload.transfer;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.core.db.schema.contribution.CoreSchemaContribution;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RestoredPagePathsTest {
    @TempDir Path temp;

    @org.junit.jupiter.api.Test
    void keepsAnOfflinePageUnderItsSelectedRootAndRejectsAnEscapingSuffix() throws Exception {
        var table = CoreSchemaContribution.CONTRIBUTION.tables().stream()
                .filter(t -> t.name().equals("external_work_files")).findFirst().orElseThrow();
        var source = Map.of(0L, "/source");
        var target = Map.of(0L, temp.resolve("missing-link/../art").toString());
        List<Object> inside = List.of("a", "ARTWORK", 42L, 0, "{0}", "{0}/page.jpg", 1);
        List<Object> outside = List.of("b", "ARTWORK", 42L, 1, "{0}", "{0}/../other/page.jpg", 1);
        try (var check = new RestoredPagePaths(temp.resolve("pages.mv"), source, target)) {
            check.check(table, inside, inside, "");
            assertThatThrownBy(() -> check.check(table, outside, outside, ""))
                    .isInstanceOf(RestoredPagePaths.Conflict.class).hasMessageContaining("ARTWORK 42 page 1")
                    .hasMessageContaining("root_path {0}, file_path {0}");
        }
    }

    @ParameterizedTest
    @CsvSource({"Cover.jpg,cover.jpg", "Straße.jpg,STRASSE.jpg", "É.jpg,É.jpg"})
    void checksMissingFilesUsingTheDirectoryRule(String first, String second) throws Exception {
        Path directory = Files.createDirectory(temp.resolve("pictures"));
        Path exemplar = Files.createFile(directory.resolve(first));
        boolean aliases = Files.exists(directory.resolve(second));
        Files.delete(exemplar);
        Path index = temp.resolve("pages.mv");
        var table = CoreSchemaContribution.CONTRIBUTION.tables().stream()
                .filter(t -> t.name().equals("external_work_files")).findFirst().orElseThrow();
        var source = Map.of(0L, "/original", 7L, "/original/sub");
        var target = Map.of(0L, temp.toString(), 7L, directory.toString());
        List<Object> a = List.of("a", "ARTWORK", 42L, 0, "{7}", "{7}/" + first, 1);
        List<Object> same = List.of("b", "ARTWORK", 43L, 0, "{0}", "{0}/sub/" + first, 1);
        List<Object> b = List.of("c", "ARTWORK", 44L, 1, "{7}", "{7}/" + second, 1);
        try (var check = new RestoredPagePaths(index, source, target)) {
            check.check(table, a, a, "");
            check.check(table, same, a, "");
            if (aliases) assertThatThrownBy(() -> check.check(table, b, b, ""))
                    .isInstanceOf(RestoredPagePaths.Conflict.class)
                    .hasMessageContaining("ARTWORK 42 page 0").hasMessageContaining("ARTWORK 44 page 1");
            else check.check(table, b, b, "");
        }
        assertThat(index).doesNotExist();
        assertThat(directory).isEmptyDirectory();
    }
}
