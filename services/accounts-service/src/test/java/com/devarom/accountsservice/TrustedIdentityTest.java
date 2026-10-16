package com.devarom.accountsservice;

import com.devarom.accountsservice.identity.TrustedIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TrustedIdentityTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsMissingOrBlankSubject(String subject) {
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity(subject, null, null, List.of()));
    }

    @Test
    void rejectsUnsupportedSubjectAndClaimFormats() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("x".repeat(256), null, null, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("bad\0subject", null, null, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("subject", "x".repeat(321), null, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("subject", null, "x".repeat(256), List.of()));
    }

    @Test
    void preservesOpaqueSubjectAndNormalizesAbsentAttributes() {
        var identity = new TrustedIdentity(" opaque:subject ", null, null, List.of());
        assertThat(identity.subject()).isEqualTo(" opaque:subject ");
        assertThat(identity.email()).isEmpty();
        assertThat(identity.fullName()).isEmpty();
        assertThat(identity.roles()).isEmpty();
        assertThat(identity.toString()).doesNotContain("opaque:subject");
    }

    @Test
    void copiesRolesAndRejectsInvalidRoleListsWithoutGrantingRoles() {
        var roles = new ArrayList<>(List.of("CUSTOMER"));
        var identity = new TrustedIdentity("subject", null, null, roles);
        roles.add("ADMIN");
        assertThat(identity.roles()).containsExactly("CUSTOMER");
        assertThatThrownBy(() -> identity.roles().add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("subject", null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedIdentity("subject", null, null, List.of(" ")));
    }
}
