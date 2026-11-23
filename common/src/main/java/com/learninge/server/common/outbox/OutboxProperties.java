package com.learninge.server.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tham số relay (OUTBOX_*). */
@ConfigurationProperties("learninge.outbox")
public record OutboxProperties(
    @DefaultValue("200") int batch,
    @DefaultValue("15") int maxBackoffSecs,
    @DefaultValue("7") int retentionDays) {}
