package org.booklore.komga.series;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;

/**
 * The single rule giving the Komga series of a book (Grimmory has no series entity):
 * its series name; otherwise "Unknown Series" when books without series are grouped,
 * else its title, else its file name. The series name queries of {@code KomgaBookRepository}
 * implement the same rule in JPQL.
 */
public final class SeriesNaming {

    public static final String UNKNOWN_SERIES = "Unknown Series";

    private SeriesNaming() {
    }

    public static String nameOf(BookEntity book, boolean groupUnknown) {
        BookMetadataEntity metadata = book.getMetadata();
        if (metadata != null && metadata.getSeriesName() != null) {
            return metadata.getSeriesName();
        }
        if (groupUnknown) {
            return UNKNOWN_SERIES;
        }
        if (metadata != null && metadata.getTitle() != null) {
            return metadata.getTitle();
        }
        BookFileEntity file = book.getPrimaryBookFile();
        return file != null ? file.getFileName() : null;
    }
}
