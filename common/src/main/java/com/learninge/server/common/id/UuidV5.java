package com.learninge.server.common.id;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * UUIDv5 (SHA-1, RFC 9562 §5.5). JDK chỉ có {@code nameUUIDFromBytes} = v3/MD5 nên phải tự viết.
 *
 * <p>SHA-1 ở đây chỉ để tạo định danh tất định, không phải để bảo mật.
 */
public final class UuidV5 {
    /**
     * Hằng cố định vĩnh viễn, app dùng cùng giá trị (GRPC-API §Bộ mặc định).
     */
    public static final UUID NS_LEARNINGE = UUID.fromString("a7039332-6c5e-4d97-8f86-2dc4e4e084cd");

    private UuidV5() {
    }

    /**
     * Uuid bộ "Sổ tay của tôi" của một người dùng: server và app tính ra cùng giá trị.
     */
    public static String notebook(String userId) {
        return of(NS_LEARNINGE, "notebook:" + userId).toString();
    }

    public static UUID of(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(
                    ByteBuffer.allocate(16)
                            .putLong(namespace.getMostSignificantBits())
                            .putLong(namespace.getLeastSignificantBits())
                            .array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
