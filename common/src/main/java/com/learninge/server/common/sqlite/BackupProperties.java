package com.learninge.server.common.sqlite;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sao lưu tự động (LEARNINGE_BACKUP_*).
 *
 * @param dir rỗng = tắt
 */
@ConfigurationProperties("learninge.backup")
public record BackupProperties(
        @DefaultValue("") String dir,
        @DefaultValue("24") int intervalHours,
        @DefaultValue("14") int keep) {
}
