package com.learninge.server.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.server.common.error.AppException;
import com.learninge.server.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    /**
     * RFC 7914 §11.
     */
    @Test
    void matchesPublishedVectors() {
        assertThat(hex("passwd", "salt", 1))
                .startsWith("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc");
        assertThat(hex("Password", "NaCl", 80000))
                .startsWith("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56");
    }

    private static String hex(String secret, String salt, int iterations) {
        return HexFormat.of()
                .formatHex(
                        PasswordHasher.pbkdf2(
                                secret.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, 512));
    }

    @Test
    void verifiesOnlyTheRightSecretAndSaltsEveryHash() {
        PasswordHasher hasher = new PasswordHasher(4);
        PasswordHasher.Hash hash = hasher.hash("abc12345");
        assertThat(hasher.verify("abc12345", hash)).isTrue();
        assertThat(hasher.verify("abc12346", hash)).isFalse();
        assertThat(hasher.hash("abc12345").hash()).isNotEqualTo(hash.hash());
    }

    @Test
    void reportsBusyInsteadOfQueueingForever() {
        PasswordHasher hasher = new PasswordHasher(1);
        try (var pool = Executors.newFixedThreadPool(100)) {
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                all.add(CompletableFuture.runAsync(() -> hasher.hash("abc12345"), pool));
            }
            int busy = 0;
            for (CompletableFuture<?> f : all) {
                try {
                    f.join();
                } catch (CompletionException e) {
                    assertThat(((AppException) e.getCause()).code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    busy++;
                }
            }
            assertThat(busy).isPositive();
        }
        assertThat(hasher.hash("abc12345")).isNotNull();
    }
}
