package org.booklore.komga.series;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesNamingAndIdTest {

    @Test
    void seriesNameComesFromMetadataThenUnknownThenTitleThenFile() {
        assertThat(SeriesNaming.nameOf(book("One Piece", "Tome 1"), false)).isEqualTo("One Piece");
        assertThat(SeriesNaming.nameOf(book(null, "Standalone"), true)).isEqualTo(SeriesNaming.UNKNOWN_SERIES);
        assertThat(SeriesNaming.nameOf(book(null, "Standalone"), false)).isEqualTo("Standalone");
    }

    @Test
    void bookWithoutMetadataFallsBackToItsFileName() {
        BookEntity book = book(null, null);
        book.setMetadata(null);

        // Used to throw a NullPointerException when unknown series were not grouped
        assertThat(SeriesNaming.nameOf(book, false)).isEqualTo("file.cbz");
    }

    @Test
    void seriesIdRoundTrip() {
        String id = SeriesIdCodec.encode(3L, "L'Élève Ducobu");

        assertThat(id).isEqualTo("3-l-l-ve-ducobu");
        SeriesIdCodec.ParsedSeriesId parsed = SeriesIdCodec.decode(id).orElseThrow();
        assertThat(parsed.libraryId()).isEqualTo(3L);
        assertThat(parsed.matches("L'Élève Ducobu")).isTrue();
        assertThat(parsed.matches("Ducobu")).isFalse();
    }

    @Test
    void malformedSeriesIdsAreRejected() {
        assertThat(SeriesIdCodec.decode("akira")).isEmpty();
        assertThat(SeriesIdCodec.decode("x-akira")).isEmpty();
        assertThat(SeriesIdCodec.decode(null)).isEmpty();
    }

    private static BookEntity book(String seriesName, String title) {
        BookEntity book = new BookEntity();
        BookFileEntity file = new BookFileEntity();
        file.setFileName("file.cbz");
        file.setBookFormat(true);
        file.setBook(book);
        book.setBookFiles(Set.of(file));
        book.setMetadata(BookMetadataEntity.builder().seriesName(seriesName).title(title).build());
        return book;
    }
}
