package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MailpitImageTest {

  private static final Path ROOT = Path.of(System.getProperty("divalhr.repoRoot", "../.."));

  @Test
  void composeAndTheContainerTestUseTheSameDigestPinnedMailpit() throws IOException {
    Matcher matcher =
        Pattern.compile("image: (axllent/mailpit:[^\\s]+)")
            .matcher(Files.readString(ROOT.resolve("infrastructure/docker/compose.yaml")));
    assertThat(matcher.find()).as("mailpit image in compose.yaml").isTrue();
    String compose = matcher.group(1);
    assertThat(compose).matches("axllent/mailpit:v[0-9.]+@sha256:[0-9a-f]{64}");
    assertThat(MailpitImage.PINNED).isEqualTo(compose);
  }
}
