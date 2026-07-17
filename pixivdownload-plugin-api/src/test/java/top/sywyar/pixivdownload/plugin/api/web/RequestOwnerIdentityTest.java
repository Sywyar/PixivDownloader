package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("请求 owner 作用域稳定契约")
class RequestOwnerIdentityTest {

    @Test
    @DisplayName("管理员作用域固定为全 owner 且不携带 UUID")
    void adminScopeNeverCarriesOwnerUuid() {
        RequestOwnerIdentity identity = new RequestOwnerIdentity("untrusted", true);

        assertThat(identity.admin()).isTrue();
        assertThat(identity.ownerUuid()).isNull();
        assertThat(RequestOwnerIdentity.adminScope()).isEqualTo(identity);
    }

    @Test
    @DisplayName("非管理员作用域规范化且必须携带 owner UUID")
    void ownerScopeRequiresNonBlankUuid() {
        assertThat(RequestOwnerIdentity.owner("  owner-a  ").ownerUuid()).isEqualTo("owner-a");
        assertThatThrownBy(() -> RequestOwnerIdentity.owner(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOwnerIdentity.owner("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
