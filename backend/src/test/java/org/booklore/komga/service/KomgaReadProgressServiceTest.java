package org.booklore.komga.service;

import org.booklore.exception.APIException;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.core.KomgaAccessGateway;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.dto.KomgaReadProgressUpdateDto;
import org.booklore.komga.dto.KomgaTachiyomiReadProgressDto;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Read progress writes and the Mihon tracker, always after the access check. */
@ExtendWith(MockitoExtension.class)
class KomgaReadProgressServiceTest {

    private static final String UNKNOWN = "Unknown Series";
    private static final long USER = 10L;

    @Mock
    private KomgaBookRepository komgaBookRepository;

    @Mock
    private KomgaAccessGateway accessGateway;

    @Mock
    private KomgaSettingsGateway settings;

    @Mock
    private KomgaProgressGateway progressGateway;

    private KomgaReadProgressService service;

    private final Map<Long, ReadStatus> statuses = new HashMap<>();

    @BeforeEach
    void setUp() {
        KomgaAccessResolver accessResolver = new KomgaAccessResolver(accessGateway, komgaBookRepository);
        service = new KomgaReadProgressService(accessResolver, new KomgaSeriesFinder(komgaBookRepository, accessResolver),
                settings, progressGateway, new KomgaProgressReader(progressGateway));
        lenient().when(settings.groupUnknown()).thenReturn(true);
        lenient().when(accessGateway.applyRestrictions(anyList(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accessGateway.currentUser()).thenReturn(BookLoreUser.builder()
                .id(USER)
                .permissions(new BookLoreUser.UserPermissions())
                .assignedLibraries(List.of(Library.builder().id(1L).build()))
                .build());
        lenient().when(progressGateway.bookProgress(eq(USER), anySet())).thenAnswer(inv -> {
            Map<Long, UserBookProgressEntity> rows = new HashMap<>();
            for (Long id : (Set<Long>) inv.getArgument(1)) {
                if (statuses.containsKey(id)) {
                    rows.put(id, UserBookProgressEntity.builder().readStatus(statuses.get(id)).build());
                }
            }
            return rows;
        });
    }

    // ==================== Books ====================

    @Test
    void completedMarksTheBookRead() {
        visibleBook(101L, BookFileType.CBX, 10);

        service.updateBookProgress(101L, KomgaReadProgressUpdateDto.builder().completed(true).build());

        verify(progressGateway).setReadStatus(List.of(101L), ReadStatus.READ);
        verify(progressGateway, never()).savePosition(anyLong(), anyLong(), anyInt(), anyFloat());
    }

    @Test
    void pageBecomesTheCurrentPosition() {
        visibleBook(101L, BookFileType.CBX, 10);

        service.updateBookProgress(101L, KomgaReadProgressUpdateDto.builder().page(5).completed(false).build());

        verify(progressGateway).savePosition(101L, 1101L, 5, 50f);
        verify(progressGateway, never()).setReadStatus(anyList(), any());
    }

    @Test
    void invalidPageRequestsAnswer400WithoutWriting() {
        visibleBook(101L, BookFileType.CBX, 10);
        visibleBook(102L, BookFileType.EPUB, 10);
        visibleBook(103L, BookFileType.PDF, 0);

        assertStatus(() -> service.updateBookProgress(101L, KomgaReadProgressUpdateDto.builder().page(11).build()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.updateBookProgress(101L, KomgaReadProgressUpdateDto.builder().page(0).build()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.updateBookProgress(101L, KomgaReadProgressUpdateDto.builder().build()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.updateBookProgress(101L, null), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.updateBookProgress(102L, KomgaReadProgressUpdateDto.builder().page(2).build()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.updateBookProgress(103L, KomgaReadProgressUpdateDto.builder().page(2).build()), HttpStatus.BAD_REQUEST);
        verifyNoWrite();
    }

    @Test
    void hiddenOrUnknownBookAnswers404WithoutWriting() {
        BookEntity otherLibrary = book(201L, BookFileType.CBX, 10);
        when(komgaBookRepository.findByIdWithBookFiles(201L)).thenReturn(Optional.of(otherLibrary));
        when(komgaBookRepository.findByIdWithBookFiles(999L)).thenReturn(Optional.empty());

        assertStatus(() -> service.updateBookProgress(201L, KomgaReadProgressUpdateDto.builder().completed(true).build()), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.deleteBookProgress(201L), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.updateBookProgress(999L, KomgaReadProgressUpdateDto.builder().page(1).build()), HttpStatus.NOT_FOUND);
        verifyNoWrite();
    }

    @Test
    void deleteMarksTheBookUnread() {
        visibleBook(101L, BookFileType.CBX, 10);

        service.deleteBookProgress(101L);

        verify(progressGateway).setReadStatus(List.of(101L), ReadStatus.UNREAD);
    }

    // ==================== Series ====================

    @Test
    void seriesIsMarkedReadOrUnreadAtOnce() {
        series("Akira", 1f, 2f);

        service.markSeriesRead("1-akira");
        service.markSeriesUnread("1-akira");

        verify(progressGateway).setReadStatus(List.of(101L, 102L), ReadStatus.READ);
        verify(progressGateway).setReadStatus(List.of(101L, 102L), ReadStatus.UNREAD);
    }

    @Test
    void unknownSeriesAnswers404() {
        assertStatus(() -> service.markSeriesRead("1-nope"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.getTachiyomiProgress("2-akira"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.updateTachiyomiProgress("x", 3f), HttpStatus.NOT_FOUND);
        verifyNoWrite();
    }

    // ==================== Mihon tracker ====================

    @Test
    void trackerStateStopsAtTheFirstUnreadBook() {
        series("Akira", 2f, 1f, 3f, 4f, 5f);
        statuses.put(102L, ReadStatus.READ);      // number 1
        statuses.put(101L, ReadStatus.READ);      // number 2
        statuses.put(103L, ReadStatus.READING);   // number 3
        statuses.put(104L, ReadStatus.READ);      // number 4, after a gap

        KomgaTachiyomiReadProgressDto progress = service.getTachiyomiProgress("1-akira");

        assertThat(progress.getBooksCount()).isEqualTo(5);
        assertThat(progress.getBooksReadCount()).isEqualTo(3);
        assertThat(progress.getBooksInProgressCount()).isEqualTo(1);
        assertThat(progress.getBooksUnreadCount()).isEqualTo(1);
        assertThat(progress.getLastReadContinuousNumberSort()).isEqualTo(2f);
        assertThat(progress.getMaxNumberSort()).isEqualTo(5f);
    }

    @Test
    void trackerStateOfAnUnreadSeriesStartsAtZero() {
        series("Akira", 1f, 2f);

        assertThat(service.getTachiyomiProgress("1-akira").getLastReadContinuousNumberSort()).isZero();
    }

    @Test
    void trackerUpdateMarksUnreadBooksUpToTheNumberOneByOne() {
        series("Akira", 1f, 2f, 3f, 4f);
        statuses.put(102L, ReadStatus.READ);

        service.updateTachiyomiProgress("1-akira", 3f);

        // One book per call: the tracker does not need the bulk read status permission
        verify(progressGateway).setReadStatus(List.of(101L), ReadStatus.READ);
        verify(progressGateway).setReadStatus(List.of(103L), ReadStatus.READ);
        verify(progressGateway, never()).setReadStatus(eq(List.of(102L)), any());
        verify(progressGateway, never()).setReadStatus(eq(List.of(104L)), any());
        assertStatus(() -> service.updateTachiyomiProgress("1-akira", null), HttpStatus.BAD_REQUEST);
    }

    // ==================== Helpers ====================

    private void visibleBook(Long id, BookFileType type, int pageCount) {
        when(komgaBookRepository.findByIdWithBookFiles(id)).thenReturn(Optional.of(book(id, type, pageCount)));
    }

    /** Series of library 1; book ids are 101, 102... in the given number order. */
    private void series(String name, float... numbers) {
        lenient().when(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(1L, UNKNOWN)).thenReturn(List.of(name));
        List<BookEntity> books = new ArrayList<>();
        for (int i = 0; i < numbers.length; i++) {
            BookEntity book = book(101L + i, BookFileType.CBX, 10);
            book.getMetadata().setSeriesName(name);
            book.getMetadata().setSeriesNumber(numbers[i]);
            books.add(book);
        }
        lenient().when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId(name, 1L, UNKNOWN)).thenReturn(books);
    }

    private static BookEntity book(Long id, BookFileType type, int pageCount) {
        BookEntity book = BookEntity.builder()
                .id(id)
                .library(LibraryEntity.builder().id(id / 100).build())
                .metadata(BookMetadataEntity.builder().pageCount(pageCount).build())
                .build();
        book.setBookFiles(new HashSet<>(Set.of(
                BookFileEntity.builder().id(1000L + id).book(book).bookType(type).isBookFormat(true).build())));
        return book;
    }

    private void verifyNoWrite() {
        verify(progressGateway, never()).setReadStatus(anyList(), any());
        verify(progressGateway, never()).savePosition(anyLong(), anyLong(), anyInt(), anyFloat());
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call)
                .isInstanceOf(APIException.class)
                .extracting(e -> ((APIException) e).getStatus())
                .isEqualTo(status);
    }
}
