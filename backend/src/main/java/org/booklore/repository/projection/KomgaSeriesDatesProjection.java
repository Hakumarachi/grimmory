package org.booklore.repository.projection;

import java.time.Instant;

/**
 * Created / last modified dates of a Komga series (oldest / newest book added),
 * aggregated in SQL per library and series name.
 */
public interface KomgaSeriesDatesProjection {
    Long getLibraryId();
    String getSeriesName();
    Instant getCreatedDate();
    Instant getLastModifiedDate();
}
