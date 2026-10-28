package com.divalhr.core.operations.application;

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
 * Reads a receipt body before any transaction starts (MVP-081, D81-5, D81-10): at most {@link
 * ReceiptVerifier#MAX_BYTES} bytes, counted while streaming (one byte more answers {@code 413
 * EXPENSE_RECEIPT_TOO_LARGE} without reading further), and the whole body within the deadline
 * ({@code 408 EXPENSE_RECEIPT_UPLOAD_TIMEOUT}). A single blocked read is bounded by the server's
 * connection timeout. Authorization has already run; nothing is stored on any failure.
 */
public final class ReceiptBody {

  /** Deadline for the whole body. */
  public static final Duration TIMEOUT = Duration.ofSeconds(30);

  private ReceiptBody() {}

  /**
   * Refuses a declared length beyond the cap before a byte is read.
   *
   * @param contentLength the declared {@code Content-Length}, or -1 when unknown
   */
  public static void requireDeclaredLength(long contentLength) {
    if (contentLength > ReceiptVerifier.MAX_BYTES) {
      throw new ApiException(ErrorCode.EXPENSE_RECEIPT_TOO_LARGE, Map.of());
    }
  }

  /**
   * Reads the body.
   *
   * @param in request body
   * @param clock clock
   * @return the bytes
   */
  public static byte[] read(InputStream in, Clock clock) {
    Instant deadline = Instant.now(clock).plus(TIMEOUT);
    ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
    byte[] buffer = new byte[8 * 1024];
    try {
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (out.size() + read > ReceiptVerifier.MAX_BYTES) {
          throw new ApiException(ErrorCode.EXPENSE_RECEIPT_TOO_LARGE, Map.of());
        }
        out.write(buffer, 0, read);
        if (Instant.now(clock).isAfter(deadline)) {
          throw new ApiException(ErrorCode.EXPENSE_RECEIPT_UPLOAD_TIMEOUT, Map.of());
        }
      }
    } catch (IOException interrupted) {
      throw new ApiException(ErrorCode.EXPENSE_RECEIPT_UPLOAD_TIMEOUT, Map.of());
    }
    return out.toByteArray();
  }
}
