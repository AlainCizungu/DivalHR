package com.divalhr.core.platform.pagination;

import java.util.UUID;

/**
 * The last row of a page, from which the next page continues.
 *
 * @param code normalized code of the last row
 * @param id id of the last row
 */
public record KeysetPosition(String code, UUID id) {}
