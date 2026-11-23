package com.learninge.server.gamification;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Dịch vụ XP và bảng xếp hạng tuần (cổng 50055). */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(GamificationServiceConfig.class)
public class GamificationApplication {
  public static final String NAME = "gamification";

  public static void main(String[] args) {
    ServiceLauncher.main(GamificationApplication.class, NAME, args);
  }
}
