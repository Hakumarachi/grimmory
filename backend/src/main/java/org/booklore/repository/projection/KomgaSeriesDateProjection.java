package org.booklore.repository.projection;

import java.time.Instant;

/**
 * Lightweight per-book row used to compute Komga series dates (created / last modified)
 * without loading full book entities.
 */
public interface KomgaSeriesDateProjection {
    Long getLibraryId();
    String getSeriesName();
    String getTitle();
    String getFileName();
    Instant getAddedOn();
}
