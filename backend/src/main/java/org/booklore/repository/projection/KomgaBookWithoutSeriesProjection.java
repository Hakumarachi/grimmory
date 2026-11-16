package org.booklore.repository.projection;

import java.time.Instant;

/**
 * A book without series name, with the fields needed to derive its Komga series name
 * (title, first book-format file name) and its added date.
 */
public interface KomgaBookWithoutSeriesProjection {
    Long getLibraryId();
    String getTitle();
    String getFileName();
    Instant getAddedOn();
}
