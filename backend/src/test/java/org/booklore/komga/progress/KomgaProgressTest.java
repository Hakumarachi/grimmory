package org.booklore.komga.progress;

import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.komga.dto.KomgaReadProgressDto;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KomgaProgressTest {

    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-01T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "READ, READ",
            "READING, IN_PROGRESS", "RE_READING, IN_PROGRESS", "PARTIALLY_READ, IN_PROGRESS", "PAUSED, IN_PROGRESS",
            "UNREAD, UNREAD", "UNSET, UNREAD", "WONT_READ, UNREAD", "ABANDONED, UNREAD"
    })
    void grimmoryStatusesMapToKomgaStates(ReadStatus status, KomgaReadState expected) {
        assertThat(KomgaReadState.of(status)).isEqualTo(expected);
        assertThat(KomgaReadState.of(null)).isEqualTo(KomgaReadState.UNREAD);
    }

    @Test
    void unreadBookHasNoReadProgress() {
        assertThat(BookProgress.NONE.toDto(20)).isNull();
    }

    @Test
    void readBookIsCompletedOnItsLastPage() {
        KomgaReadProgressDto dto = new BookProgress(KomgaReadState.READ, 3, null, T1, T2).toDto(20);
        assertThat(dto.getCompleted()).isTrue();
        assertThat(dto.getPage()).isEqualTo(20);
        assertThat(dto.getCreated()).isEqualTo(T1);
        assertThat(dto.getReadDate()).isEqualTo(T2);
        assertThat(dto.getLastModified()).isEqualTo(T2);
        assertThat(new BookProgress(KomgaReadState.READ, null, null, null, null).toDto(0).getPage()).isEqualTo(1);
    }

    @Test
    void bookInProgressUsesItsPageThenItsPercentageThenPageOne() {
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, 7, 90f, T1, T1).toDto(20).getPage()).isEqualTo(7);
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, null, 42f, T1, T1).toDto(20).getPage()).isEqualTo(9);
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, null, null, T1, T1).toDto(20).getPage()).isEqualTo(1);
        // Out of range pages are clamped
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, 99, null, T1, T1).toDto(20).getPage()).isEqualTo(20);
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, 0, null, T1, T1).toDto(20).getPage()).isEqualTo(1);
        assertThat(new BookProgress(KomgaReadState.IN_PROGRESS, 5, null, T1, T1).toDto(20).getCompleted()).isFalse();
    }

    @Test
    void cbxPageComesFromFileProgressThenLegacyColumn() {
        BookEntity book = book(1L, BookFileType.CBX);
        UserBookProgressEntity progress = UserBookProgressEntity.builder()
                .readStatus(ReadStatus.READING).cbxProgress(4).lastReadTime(T1).build();
        UserBookFileProgressEntity file = UserBookFileProgressEntity.builder()
                .positionData("12").progressPercent(60f).lastReadTime(T2).build();

        BookProgress fromFile = KomgaProgressReader.toBookProgress(book, progress, file);
        assertThat(fromFile.page()).isEqualTo(12);
        assertThat(fromFile.created()).isEqualTo(T1);
        assertThat(fromFile.lastModified()).isEqualTo(T2);

        assertThat(KomgaProgressReader.toBookProgress(book, progress, null).page()).isEqualTo(4);
    }

    @Test
    void epubOnlyHasAPercentage() {
        BookEntity book = book(1L, BookFileType.EPUB);
        UserBookProgressEntity progress = UserBookProgressEntity.builder()
                .readStatus(ReadStatus.READING).epubProgressPercent(50f).build();
        UserBookFileProgressEntity file = UserBookFileProgressEntity.builder()
                .positionData("epubcfi(/6/4)").build();

        BookProgress result = KomgaProgressReader.toBookProgress(book, progress, file);
        assertThat(result.page()).isNull();
        assertThat(result.percent()).isEqualTo(50f);

        UserBookProgressEntity koreader = UserBookProgressEntity.builder()
                .readStatus(ReadStatus.READING).koreaderProgressPercent(0.25f).build();
        assertThat(KomgaProgressReader.toBookProgress(book, koreader, null).percent()).isEqualTo(25f);
    }

    @Test
    void readerLoadsBookAndFileProgressOnceAndCounts() {
        KomgaProgressGateway gateway = mock(KomgaProgressGateway.class);
        BookEntity read = book(1L, BookFileType.CBX);
        BookEntity reading = book(2L, BookFileType.CBX);
        BookEntity unread = book(3L, BookFileType.CBX);
        when(gateway.bookProgress(eq(7L), anySet())).thenReturn(Map.of(
                1L, UserBookProgressEntity.builder().readStatus(ReadStatus.READ).build(),
                2L, UserBookProgressEntity.builder().readStatus(ReadStatus.PAUSED).build()));
        when(gateway.fileProgress(eq(7L), anySet())).thenReturn(Map.of());

        ProgressSnapshot snapshot = new KomgaProgressReader(gateway)
                .load(new KomgaAccess(7L, false, Set.of(), List.of()), List.of(read, reading, unread));

        verify(gateway).bookProgress(7L, Set.of(1L, 2L, 3L));
        verify(gateway).fileProgress(7L, Set.of(1L, 2L));
        assertThat(snapshot.of(unread)).isEqualTo(BookProgress.NONE);
        assertThat(snapshot.counts(List.of(read, reading, unread))).isEqualTo(new ReadCounts(1, 1, 1));
    }

    @Test
    void readerSkipsTheFileQueryWithoutAnyProgress() {
        KomgaProgressGateway gateway = mock(KomgaProgressGateway.class);
        when(gateway.bookProgress(eq(7L), anySet())).thenReturn(Map.of());

        ProgressSnapshot snapshot = new KomgaProgressReader(gateway)
                .load(new KomgaAccess(7L, false, Set.of(), List.of()), List.of(book(1L, BookFileType.CBX)));

        assertThat(snapshot).isEqualTo(ProgressSnapshot.EMPTY);
        verify(gateway, never()).fileProgress(any(), any());
        assertThat(new KomgaProgressReader(gateway).load(null, List.of())).isEqualTo(ProgressSnapshot.EMPTY);
    }

    private static BookEntity book(Long id, BookFileType type) {
        BookEntity book = BookEntity.builder().id(id).build();
        BookFileEntity file = BookFileEntity.builder().id(id).book(book).bookType(type).isBookFormat(true).build();
        book.setBookFiles(Set.of(file));
        return book;
    }
}
