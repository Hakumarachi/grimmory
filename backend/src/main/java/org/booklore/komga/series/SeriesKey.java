package org.booklore.komga.series;

/**
 * A virtual Komga series: a series name within a library. {@code libraryId} is {@code null}
 * when the series is listed across all libraries (admin listing without library filter).
 */
public record SeriesKey(String name, Long libraryId) {
}
