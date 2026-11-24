package com.learninge.server.edge.ops;

import com.learninge.server.common.config.Secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code LEARNINGE_DEMO_SEED}, {@code LEARNINGE_DEMO_PASSWORD}.
 */
@ConfigurationProperties("learninge.demo")
public record DemoProperties(boolean seed, Secret password) {
}
