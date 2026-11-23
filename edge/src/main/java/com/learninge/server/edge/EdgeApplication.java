package com.learninge.server.edge;

import com.learninge.server.common.ops.ServiceLauncher;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Điểm vào duy nhất của app (cổng 50051). */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(EdgeServiceConfig.class)
public class EdgeApplication {
  public static final String NAME = "edge";

  public static void main(String[] args) {
    ServiceLauncher.main(EdgeApplication.class, NAME, args);
  }
}
