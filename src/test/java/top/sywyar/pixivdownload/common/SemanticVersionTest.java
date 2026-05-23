package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SemanticVersion tests")
class SemanticVersionTest {

    @Test
    @DisplayName("numeric core comparison")
    void numericCore() {
        assertThat(SemanticVersion.compare("1.2.0", "1.1.9")).isPositive();
        assertThat(SemanticVersion.compare("1.2.0", "1.2.0")).isZero();
        assertThat(SemanticVersion.compare("1.2", "1.2.0")).isZero();
        assertThat(SemanticVersion.compare("v1.2.3", "1.2.3")).isZero();
    }

    @Test
    @DisplayName("release outranks any pre-release of the same core")
    void releaseBeatsPreRelease() {
        assertThat(SemanticVersion.compare("1.0.0", "1.0.0-rc.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0", "1.0.0-snapshot.9")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-rc.1", "1.0.0")).isNegative();
    }

    @Test
    @DisplayName("suffix precedence: snapshot < dev < beta < rc < release")
    void suffixPrecedence() {
        assertThat(SemanticVersion.compare("1.0.0-dev.1", "1.0.0-snapshot.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-beta.1", "1.0.0-dev.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-rc.1", "1.0.0-beta.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-snapshot.1", "1.0.0-nightly.1")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-alpha.1", "1.0.0-dev.1")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-preview.1", "1.0.0-beta.1")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-m.1", "1.0.0-beta.1")).isZero();
    }

    @Test
    @DisplayName("same suffix compares by trailing number")
    void sameSuffixNumber() {
        assertThat(SemanticVersion.compare("1.0.0-snapshot.2", "1.0.0-snapshot.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-rc.10", "1.0.0-rc.2")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-rc1", "1.0.0-rc.1")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-beta", "1.0.0-beta.0")).isZero();
    }

    @Test
    @DisplayName("nightly build identifiers compare by date, run number, and attempt")
    void nightlyBuildIdentifiers() {
        assertThat(SemanticVersion.compare(
                "1.0.0-nightly.20260523.101.1",
                "1.0.0-nightly.20260523.100.1")).isPositive();
        assertThat(SemanticVersion.compare(
                "1.0.0-nightly.20260523.100.2",
                "1.0.0-nightly.20260523.100.1")).isPositive();
    }

    @Test
    @DisplayName("case-insensitive suffix")
    void caseInsensitive() {
        assertThat(SemanticVersion.compare("1.0.0-RC.1", "1.0.0-rc.1")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-Beta.1", "1.0.0-beta.1")).isZero();
    }

    @Test
    @DisplayName("unrecognized suffix is the lowest priority")
    void unknownSuffixLowest() {
        assertThat(SemanticVersion.compare("1.0.0-snapshot.1", "1.0.0-weird.1")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-weird.1", "1.0.0-nightly.1")).isNegative();
        assertThat(SemanticVersion.compare("1.0.0", "1.0.0-weird.99")).isPositive();
        assertThat(SemanticVersion.compare("1.0.0-weird.2", "1.0.0-weird.1")).isPositive();
    }

    @Test
    @DisplayName("null / blank handling")
    void nullHandling() {
        assertThat(SemanticVersion.compare(null, "1.0.0")).isNegative();
        assertThat(SemanticVersion.compare("1.0.0", "")).isPositive();
        assertThat(SemanticVersion.compare(null, " ")).isZero();
    }

    @Test
    @DisplayName("ignores +build metadata")
    void ignoresBuildMetadata() {
        assertThat(SemanticVersion.compare("1.0.0+host-abc", "1.0.0")).isZero();
        assertThat(SemanticVersion.compare("1.0.0-rc.1+host-abc", "1.0.0-rc.1")).isZero();
    }
}
