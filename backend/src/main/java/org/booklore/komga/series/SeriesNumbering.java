package org.booklore.komga.series;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;

/** Number of a book in its series, as Komga clients see it ({@code metadata.numberSort}). */
public final class SeriesNumbering {

    private SeriesNumbering() {
    }

    /** The series number, 1 when unknown (same default as the book DTO). */
    public static float numberSort(BookEntity book) {
        BookMetadataEntity metadata = book.getMetadata();
        return metadata != null && metadata.getSeriesNumber() != null ? metadata.getSeriesNumber() : 1f;
    }
}
