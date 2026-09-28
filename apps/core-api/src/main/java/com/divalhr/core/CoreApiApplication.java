package com.divalhr.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point for the DivalHR Core API deployable. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreApiApplication {

  /**
   * Starts the Core API.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(CoreApiApplication.class, args);
  }
}
