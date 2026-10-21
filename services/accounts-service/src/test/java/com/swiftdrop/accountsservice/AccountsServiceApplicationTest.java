package com.swiftdrop.accountsservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class AccountsServiceApplicationTest {
    @Test
    void declaresIndependentBootApplication() {
        assertThat(AccountsServiceApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }
}
