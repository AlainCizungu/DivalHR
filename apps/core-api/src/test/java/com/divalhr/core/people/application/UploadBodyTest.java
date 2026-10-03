package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** MVP-020 (A20-5, A20-6): the body is read under a byte cap and a deadline. */
class UploadBodyTest {

  /** A clock that advances one second per reading. */
  private static final class Ticking extends Clock {
    private Instant now = Instant.parse("2026-10-03T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      now = now.plusSeconds(1);
      return now;
    }
  }

  @Test
  void theCapIsExactAndOneMoreByteIsRefused() {
    byte[] exact = new byte[1024];
    assertThat(
            UploadBody.read(
                new ByteArrayInputStream(exact), 1024, Duration.ofSeconds(30), Clock.systemUTC()))
        .hasSize(1024);
    ApiException tooLarge =
        catchThrowableOfType(
            ApiException.class,
            () ->
                UploadBody.read(
                    new ByteArrayInputStream(new byte[1025]),
                    1024,
                    Duration.ofSeconds(30),
                    Clock.systemUTC()));
    assertThat(tooLarge.code()).isEqualTo(ErrorCode.IMPORT_FILE_TOO_LARGE);
  }

  /** Returns one byte per read, forever. */
  private static final class Trickle extends InputStream {
    @Override
    public int read() {
      return 'a';
    }

    @Override
    public int read(byte[] b, int off, int len) {
      b[off] = 'a';
      return 1;
    }
  }

  /** Fails like a reset connection. */
  private static final class Broken extends InputStream {
    @Override
    public int read() throws IOException {
      throw new IOException("reset");
    }
  }

  /** Fills every read completely, forever, and counts what it handed out. */
  private static final class Endless extends InputStream {
    private long consumed;

    @Override
    public int read() {
      consumed++;
      return 'a';
    }

    @Override
    public int read(byte[] b, int off, int len) {
      Arrays.fill(b, off, off + len, (byte) 'a');
      consumed += len;
      return len;
    }
  }

  @Test
  void aSlowBodyTimesOutAndABrokenOneIsATimeoutToo() throws IOException {
    try (InputStream slow = new Trickle()) {
      ApiException timeout =
          catchThrowableOfType(
              ApiException.class,
              () -> UploadBody.read(slow, 1024, Duration.ofSeconds(5), new Ticking()));
      assertThat(timeout.code()).isEqualTo(ErrorCode.IMPORT_UPLOAD_TIMEOUT);
    }
    try (InputStream broken = new Broken()) {
      ApiException timeout =
          catchThrowableOfType(
              ApiException.class,
              () -> UploadBody.read(broken, 1024, Duration.ofSeconds(5), Clock.systemUTC()));
      assertThat(timeout.code()).isEqualTo(ErrorCode.IMPORT_UPLOAD_TIMEOUT);
    }
  }

  @Test
  void anEndlessBodyIsCutOffJustAfterTheCap() throws IOException {
    int cap = 2 * 1024 * 1024;
    try (Endless endless = new Endless()) {
      ApiException tooLarge =
          catchThrowableOfType(
              ApiException.class,
              () -> UploadBody.read(endless, cap, Duration.ofSeconds(30), Clock.systemUTC()));
      assertThat(tooLarge.code()).isEqualTo(ErrorCode.IMPORT_FILE_TOO_LARGE);
      // At most one read buffer beyond the cap is ever consumed (A20-3, A20-6).
      assertThat(endless.consumed).isLessThanOrEqualTo(cap + 8 * 1024L);
    }
  }
}
