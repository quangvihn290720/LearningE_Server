package com.learninge.server.common.mongo;

import com.learninge.server.common.config.Secret;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Mỗi dịch vụ chỉ nhận URI và tên database của mình. URI chứa mật khẩu nên là {@link Secret}. */
@ConfigurationProperties("learninge.mongo")
public record MongoProperties(Secret uri, String database) {}
