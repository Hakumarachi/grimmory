package org.booklore.komga.mapper;

import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.dto.KomgaAuthorDto;
import org.booklore.komga.dto.KomgaBookDto;
import org.booklore.komga.dto.KomgaSeriesDto;
import org.booklore.komga.progress.BookProgress;
import org.booklore.komga.progress.ReadCounts;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 2: series and book metadata as Komga clients expect them. */
class KomgaMetadataFidelityTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

    private KomgaMapper mapper;

    @BeforeEach
    void setUp() {
        KomgaSettingsGateway settings = mock(KomgaSettingsGateway.class);
        when(settings.groupUnknown()).thenReturn(true);
        mapper = new KomgaMapper(settings);
    }

    @Test
    void knownTotalMakesTheSeriesEnded() {
        BookEntity one = book(1L, BookFileType.CBX, meta(1f).seriesTotal(3).build(), T1);
        BookEntity two = book(2L, BookFileType.CBX, meta(2f).seriesTotal(3).build(), T2);

        KomgaSeriesDto series = series(one, two);

        assertThat(series.getMetadata().getStatus()).isEqualTo("ENDED");
        assertThat(series.getMetadata().getTotalBookCount()).isEqualTo(3);

        KomgaSeriesDto unknown = series(book(3L, BookFileType.CBX, meta(1f).build(), T1));
        assertThat(unknown.getMetadata().getStatus()).isEqualTo("ONGOING");
        assertThat(unknown.getMetadata().getTotalBookCount()).isNull();
    }

    @Test
    void seriesAggregatesEveryBook() {
        BookEntity one = book(1L, BookFileType.CBX, meta(1f)
                .language("fr").publisher("Kana").ageRating(12)
                .categories(Set.of(CategoryEntity.builder().name("Action").build()))
                .tags(Set.of(TagEntity.builder().id(1L).name("École").build()))
                .build(), T2);
        BookEntity two = book(2L, BookFileType.CBX, meta(2f)
                .language("fr").publisher("Pika").ageRating(16).description("Résumé")
                .categories(Set.of(CategoryEntity.builder().id(2L).name("Comédie").build(), CategoryEntity.builder().id(3L).name("Action").build()))
                .build(), T1);
        BookEntity three = book(3L, BookFileType.CBX, meta(3f).language("en").publisher("Kana").build(), T2);

        KomgaSeriesDto series = series(one, two, three);

        assertThat(series.getMetadata().getGenres()).containsExactly("Action", "Comédie");
        assertThat(series.getMetadata().getTags()).containsExactly("École");
        assertThat(series.getMetadata().getLanguage()).isEqualTo("fr");
        assertThat(series.getMetadata().getPublisher()).isEqualTo("Kana");
        assertThat(series.getMetadata().getAgeRating()).isEqualTo(16);
        assertThat(series.getMetadata().getSummary()).isEqualTo("Résumé");
        assertThat(series.getCreated()).isEqualTo(T1);
        assertThat(series.getLastModified()).isEqualTo(T2);
        assertThat(series.getBooksMetadata().getCreated()).isEqualTo(T1);
    }

    @Test
    void readingDirectionFollowsMostBooks() {
        BookEntity one = book(1L, BookFileType.CBX, meta(1f).comicMetadata(comic("rtl")).build(), T1);
        BookEntity two = book(2L, BookFileType.CBX, meta(2f).comicMetadata(comic("rtl")).build(), T1);
        BookEntity three = book(3L, BookFileType.CBX, meta(3f).comicMetadata(comic("ltr")).build(), T1);

        assertThat(series(one, two, three).getMetadata().getReadingDirection()).isEqualTo("RIGHT_TO_LEFT");
        assertThat(series(book(4L, BookFileType.CBX, meta(1f).build(), T1)).getMetadata().getReadingDirection())
                .isEqualTo("LEFT_TO_RIGHT");
    }

    @Test
    void comicCreatorsKeepTheirRole() {
        ComicMetadataEntity comic = comic("ltr");
        comic.setCreatorMappings(Set.of(
                creator(1L, comic, "Akira Toriyama", ComicCreatorRole.PENCILLER),
                creator(2L, comic, "Ink Person", ComicCreatorRole.INKER),
                creator(3L, comic, "Cover Person", ComicCreatorRole.COVER_ARTIST)));
        BookEntity book = book(1L, BookFileType.CBX, meta(1f)
                .authors(List.of(AuthorEntity.builder().id(1L).name("Writer Person").build()))
                .comicMetadata(comic)
                .build(), T1);

        assertThat(mapper.toKomgaBookDto(book, BookProgress.NONE).getMetadata().getAuthors())
                .extracting(KomgaAuthorDto::getRole, KomgaAuthorDto::getName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("writer", "Writer Person"),
                        org.assertj.core.groups.Tuple.tuple("penciller", "Akira Toriyama"),
                        org.assertj.core.groups.Tuple.tuple("inker", "Ink Person"),
                        org.assertj.core.groups.Tuple.tuple("cover", "Cover Person"));
        assertThat(series(book).getBooksMetadata().getAuthors()).hasSize(4);
    }

    @Test
    void bookNumberIsWrittenLikeKomga() {
        assertThat(KomgaMapper.formatNumber(1f)).isEqualTo("1");
        assertThat(KomgaMapper.formatNumber(10.5f)).isEqualTo("10.5");
        assertThat(KomgaMapper.formatNumber(null)).isNull();

        KomgaBookDto book = mapper.toKomgaBookDto(book(1L, BookFileType.CBX, meta(12f).build(), T1), BookProgress.NONE);
        assertThat(book.getMetadata().getNumber()).isEqualTo("12");
        assertThat(book.getMetadata().getNumberSort()).isEqualTo(12f);
    }

    @Test
    void mediaStatusAndProfileTellWhatCanBeReadPageByPage() {
        assertThat(KomgaMapper.mediaStatus(BookFileType.CBX, 20)).isEqualTo("READY");
        assertThat(KomgaMapper.mediaStatus(BookFileType.CBX, 0)).isEqualTo("UNKNOWN");
        assertThat(KomgaMapper.mediaStatus(BookFileType.PDF, 0)).isEqualTo("UNKNOWN");
        assertThat(KomgaMapper.mediaStatus(BookFileType.EPUB, 0)).isEqualTo("READY");
        assertThat(KomgaMapper.mediaStatus(BookFileType.AUDIOBOOK, 0)).isEqualTo("UNSUPPORTED");

        KomgaBookDto fb2 = mapper.toKomgaBookDto(book(1L, BookFileType.FB2, meta(1f).build(), T1), BookProgress.NONE);
        assertThat(fb2.getMedia().getMediaProfile()).isEqualTo("EPUB");
    }

    // ==================== Helpers ====================

    private KomgaSeriesDto series(BookEntity... books) {
        List<BookEntity> list = List.of(books);
        return mapper.toKomgaSeriesDto("Series", 1L, list, new ReadCounts(0, list.size(), 0));
    }

    private static BookMetadataEntity.BookMetadataEntityBuilder meta(float number) {
        return BookMetadataEntity.builder().title("Vol " + number).seriesName("Series").seriesNumber(number).pageCount(10);
    }

    private static ComicMetadataEntity comic(String direction) {
        return ComicMetadataEntity.builder().readingDirection(direction).build();
    }

    private static ComicCreatorMappingEntity creator(Long id, ComicMetadataEntity comic, String name, ComicCreatorRole role) {
        return ComicCreatorMappingEntity.builder()
                .id(id)
                .comicMetadata(comic)
                .creator(ComicCreatorEntity.builder().id(id).name(name).build())
                .role(role)
                .build();
    }

    private static BookEntity book(Long id, BookFileType type, BookMetadataEntity metadata, Instant addedOn) {
        BookEntity book = BookEntity.builder()
                .id(id)
                .library(LibraryEntity.builder().id(1L).build())
                .metadata(metadata)
                .addedOn(addedOn)
                .build();
        book.setBookFiles(new java.util.HashSet<>(Set.of(BookFileEntity.builder()
                .id(id).book(book).bookType(type).isBookFormat(true).fileName("f" + id).fileSizeKb(1L).build())));
        return book;
    }
}
