package com.learninge.server.auth.domain;

import com.learninge.server.common.error.FieldErrors;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa và kiểm trường tài khoản (GRPC-API §Ràng buộc trường). Hàm thuần, không I/O.
 */
public final class AccountRules {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}]+");
    private static final Pattern NICKNAME = Pattern.compile("[A-Za-z0-9_.]*");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private AccountRules() {
    }

    /**
     * BR-060: bỏ khoảng trắng hai đầu, chữ thường.
     */
    public static String normalizeEmail(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * BR-007: " Hà Nội " ≡ "ha noi".
     */
    public static String normalizeAnswer(String raw) {
        String decomposed =
                Normalizer.normalize(raw.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return MARKS.matcher(decomposed).replaceAll("").replace('đ', 'd');
    }

    public static void checkEmail(FieldErrors errors, String email) {
        errors.length("email", email, 5, 254);
        int at = email.indexOf('@');
        boolean domainHasDot = at >= 0 && email.substring(at + 1).matches("[^.].*\\..*[^.]");
        errors.check(
                EMAIL.matcher(email).matches() && domainHasDot, "email", FieldErrors.INVALID_FORMAT);
    }

    public static void checkPassword(FieldErrors errors, String field, String password) {
        errors.length(field, password, 8, 64);
        boolean strong =
                password.chars().anyMatch(Character::isLetter)
                        && password.chars().anyMatch(Character::isDigit);
        errors.check(strong, field, FieldErrors.INVALID_FORMAT);
    }

    public static void checkNickname(FieldErrors errors, String nickname) {
        errors.length("nickname", nickname, 3, 20);
        errors.check(NICKNAME.matcher(nickname).matches(), "nickname", FieldErrors.INVALID_FORMAT);
    }

    public static void checkDisplayName(FieldErrors errors, String displayName) {
        errors.length("display_name", displayName, 2, 30);
        errors.check(
                displayName.chars().noneMatch(Character::isISOControl),
                "display_name",
                FieldErrors.INVALID_FORMAT);
    }

    public static void checkAnswer(FieldErrors errors, String answer) {
        errors.length("security_answer", answer.strip(), 2, 50);
    }

    public static void checkDeviceName(FieldErrors errors, String deviceName) {
        errors.length("device_name", deviceName, 0, 60);
    }
}
