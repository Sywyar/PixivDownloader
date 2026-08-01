package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("开放推送通道标识契约")
class PushChannelIdTest {

    @Test
    @DisplayName("相同稳定 token 的独立实例按值相等")
    void independentInstancesWithSameTokenAreEqual() {
        assertThat(new PushChannelId("custom-channel"))
                .isEqualTo(new PushChannelId("custom-channel"))
                .hasSameHashCodeAs(new PushChannelId("custom-channel"));
    }

    @Test
    @DisplayName("接受 canonical 小写 token")
    void acceptsCanonicalLowercaseToken() {
        assertThat(new PushChannelId("channel-2").id()).isEqualTo("channel-2");
    }

    @Test
    @DisplayName("拒绝空白、大写、空格与超长 token")
    void rejectsNonCanonicalToken() {
        for (String invalid : new String[]{
                null, "", "Channel", "channel name", "-channel", "a".repeat(65)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PushChannelId(invalid));
        }
    }
}
