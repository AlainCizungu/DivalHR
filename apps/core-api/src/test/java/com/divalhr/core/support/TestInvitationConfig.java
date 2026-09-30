package com.divalhr.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the identity provider and SMTP adapters with in-memory fakes in integration tests. Each
 * fake is the single primary bean of its port type.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInvitationConfig {

  @Bean
  @Primary
  FakeIdentityDirectory fakeIdentityDirectory() {
    return new FakeIdentityDirectory();
  }

  @Bean
  @Primary
  RecordingInvitationMailer recordingInvitationMailer() {
    return new RecordingInvitationMailer();
  }
}
