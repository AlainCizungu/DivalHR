package com.divalhr.core.operations.application;

/**
 * A receipt ready to download: the verified media type and the bytes. The controller adds the
 * attachment disposition, a server-generated file name, {@code nosniff} and {@code no-store}.
 *
 * @param mediaType verified media type
 * @param content the bytes
 */
public record ExpenseReceiptDownload(String mediaType, byte[] content) {

  @Override
  public String toString() {
    return "ExpenseReceiptDownload[" + mediaType + "]";
  }
}
