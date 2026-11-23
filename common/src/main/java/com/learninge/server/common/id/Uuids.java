package com.learninge.server.common.id;

import java.util.regex.Pattern;

/**
 * Kiểm dạng chuẩn: UUID chữ thường, có gạch nối.
 */
public final class Uuids {
    private static final Pattern CANONICAL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private Uuids() {
    }

    public static boolean isCanonical(String value) {
        return CANONICAL.matcher(value).matches();
    }
}
