package com.divalhr.core.people.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Reads the upload body before any transaction starts (MVP-020, A20-5, A20-6): at most {@code
 * maxBytes} bytes, counted while streaming (one byte more answers {@code 413 IMPORT_FILE_TOO_LARGE}
 * without reading further), and the whole body within {@code timeout} ({@code 408
 * IMPORT_UPLOAD_TIMEOUT}). A single blocked read is bounded by the server's connection timeout
 * ({@code server.tomcat.connection-timeout}), so the worst case is the deadline plus that timeout.
 * Nothing is stored on any failure.
 */
public final class UploadBody {

  private UploadBody() {}

  /**
   * Reads the body.
   *
   * @param in request body
   * @param maxBytes byte cap
   * @param timeout deadline for the whole body
   * @param clock clock
   * @return the bytes
   */
  public static byte[] read(InputStream in, int maxBytes, Duration timeout, Clock clock) {
    Instant deadline = Instant.now(clock).plus(timeout);
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
    byte[] buffer = new byte[8 * 1024];
    try {
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (out.size() + read > maxBytes) {
          throw new ApiException(ErrorCode.IMPORT_FILE_TOO_LARGE, Map.of());
        }
        out.write(buffer, 0, read);
        if (Instant.now(clock).isAfter(deadline)) {
          throw new ApiException(ErrorCode.IMPORT_UPLOAD_TIMEOUT, Map.of());
        }
      }
    } catch (IOException interrupted) {
      // The client stopped sending, or the connection timed out between packets.
      throw new ApiException(ErrorCode.IMPORT_UPLOAD_TIMEOUT, Map.of());
    }
    return out.toByteArray();
  }
}
