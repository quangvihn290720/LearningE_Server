package com.learninge.server.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.FieldErrors;

import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountRulesTest {
    private static boolean valid(BiConsumer<FieldErrors, String> rule, String value) {
        FieldErrors errors = new FieldErrors();
        rule.accept(errors, value);
        try {
            errors.throwIfAny();
            return true;
        } catch (AppException e) {
            return false;
        }
    }

    @Test
    void normalizesEmailAndAnswer() {
        assertThat(AccountRules.normalizeEmail(" Minh@Gmail.COM ")).isEqualTo("minh@gmail.com");
        assertThat(AccountRules.normalizeAnswer(" Hà Nội ")).isEqualTo("ha noi");
        assertThat(AccountRules.normalizeAnswer("ĐÀ NẴNG")).isEqualTo("da nang");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "minh.a+1@sub.gmail.com"})
    void acceptsEmails(String email) {
        assertThat(valid(AccountRules::checkEmail, email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "a@b", "minh@gmail", "a@@b.co", "a b@c.de", "@gmail.com", "a@.com", "a@com."})
    void rejectsEmails(String email) {
        assertThat(valid(AccountRules::checkEmail, email)).isFalse();
    }

    @Test
    void emailLengthBoundary() {
        String domain = "@" + "d".repeat(60) + ".com";
        String ok = "a".repeat(254 - domain.length()) + domain;
        assertThat(valid(AccountRules::checkEmail, ok)).isTrue();
        assertThat(valid(AccountRules::checkEmail, "a" + ok)).isFalse();
    }

    @Test
    void passwordBoundaries() {
        BiConsumer<FieldErrors, String> rule = (e, v) -> AccountRules.checkPassword(e, "password", v);
        assertThat(valid(rule, "abc1234")).isFalse();
        assertThat(valid(rule, "abc12345")).isTrue();
        assertThat(valid(rule, "a1".repeat(32))).isTrue();
        assertThat(valid(rule, "a1".repeat(32) + "x")).isFalse();
        assertThat(valid(rule, "abcdefgh")).isFalse();
        assertThat(valid(rule, "12345678")).isFalse();
    }

    @Test
    void nicknameBoundaries() {
        assertThat(valid(AccountRules::checkNickname, "Mi")).isFalse();
        assertThat(valid(AccountRules::checkNickname, "lan.anh")).isTrue();
        assertThat(valid(AccountRules::checkNickname, "a".repeat(20))).isTrue();
        assertThat(valid(AccountRules::checkNickname, "a".repeat(21))).isFalse();
        assertThat(valid(AccountRules::checkNickname, "Minh A1")).isFalse();
        assertThat(valid(AccountRules::checkNickname, "a@b")).isFalse();
    }

    @Test
    void countsCharactersNotUtf16Units() {
        assertThat(valid(AccountRules::checkDisplayName, "😀".repeat(30))).isTrue();
        assertThatCode(() -> AccountRules.checkDisplayName(new FieldErrors(), ""))
                .doesNotThrowAnyException();
        assertThat(valid(AccountRules::checkDisplayName, "a\nb")).isFalse();
    }
}
