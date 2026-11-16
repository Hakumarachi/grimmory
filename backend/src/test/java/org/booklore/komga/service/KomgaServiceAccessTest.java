package org.booklore.komga.service;

import org.booklore.exception.APIException;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.core.KomgaAccessGateway;
import org.booklore.komga.core.KomgaContentGateway;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.komga.progress.BookProgress;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.progress.KomgaReadState;
import org.booklore.komga.progress.ReadCounts;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.repository.KomgaLibraryRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.komga.dto.KomgaBookDto;
import org.booklore.komga.dto.KomgaLibraryDto;
import org.booklore.komga.dto.KomgaPageableDto;
import org.booklore.komga.dto.KomgaSeriesDto;
import org.booklore.komga.dto.KomgaSeriesSearchRequestDto;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.komga.repository.KomgaBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Library assignments and content restrictions on the Komga API: non-admin users only see
 * their libraries, hidden books and fully hidden series don't leak, and hidden or unknown
 * resources answer 404 instead of 500.
 */
@ExtendWith(MockitoExtension.class)
class KomgaServiceAccessTest {

    private static final String UNKNOWN = "Unknown Series";

    @Mock
    private KomgaBookRepository komgaBookRepository;

    @Mock
    private KomgaLibraryRepository komgaLibraryRepository;

    @Mock
    private KomgaMapper komgaMapper;

    @Mock
    private KomgaSettingsGateway settings;

    @Mock
    private KomgaAccessGateway accessGateway;

    @Mock
    private KomgaContentGateway contentGateway;

    @Mock
    private KomgaProgressGateway progressGateway;

    private KomgaLibraryService libraryService;
    private KomgaSeriesService seriesService;
    private KomgaBookService bookService;

    /** Books by id; book ids 1xx are in library 1, 2xx in library 2. */
    private final Map<Long, BookEntity> books = new HashMap<>();

    @BeforeEach
    void setUp() {
        KomgaAccessResolver accessResolver = new KomgaAccessResolver(accessGateway, komgaBookRepository);
        libraryService = new KomgaLibraryService(komgaLibraryRepository, komgaMapper, accessResolver);
        KomgaProgressReader progressReader = new KomgaProgressReader(progressGateway);
        seriesService = new KomgaSeriesService(new KomgaSeriesFinder(komgaBookRepository, accessResolver),
                komgaMapper, accessResolver, settings, contentGateway, progressReader);
        bookService = new KomgaBookService(komgaBookRepository, komgaMapper, accessResolver, contentGateway, progressReader);
        lenient().when(accessGateway.restrictionSpecification(any())).thenReturn((root, query, cb) -> cb.conjunction());
        lenient().when(settings.groupUnknown()).thenReturn(true);
        lenient().when(komgaMapper.toKomgaSeriesDto(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> KomgaSeriesDto.builder()
                        .name(inv.getArgument(0))
                        .libraryId(String.valueOf((Long) inv.getArgument(1)))
                        .booksCount(((List<?>) inv.getArgument(2)).size())
                        .build());
        lenient().when(komgaMapper.toKomgaBookDto(any(), any()))
                .thenAnswer(inv -> KomgaBookDto.builder().id(((BookEntity) inv.getArgument(0)).getId().toString()).build());
        lenient().when(komgaMapper.toKomgaLibraryDto(any()))
                .thenAnswer(inv -> KomgaLibraryDto.builder().id(((LibraryEntity) inv.getArgument(0)).getId().toString()).build());
        // Users without restrictions see every book of their libraries
        lenient().when(accessGateway.applyRestrictions(anyList(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== Libraries ====================

    @Test
    void librariesAreLimitedToAssignedOnes() {
        loginAsUser(1L);
        when(komgaLibraryRepository.findAll()).thenReturn(List.of(library(1L), library(2L)));

        assertThat(libraryService.getAllLibraries()).extracting(KomgaLibraryDto::getId).containsExactly("1");
    }

    @Test
    void adminSeesEveryLibrary() {
        loginAsAdmin();
        when(komgaLibraryRepository.findAll()).thenReturn(List.of(library(1L), library(2L)));

        assertThat(libraryService.getAllLibraries()).extracting(KomgaLibraryDto::getId).containsExactly("1", "2");
    }

    @Test
    void otherLibraryAndUnknownLibraryAnswer404() {
        loginAsUser(1L);
        when(komgaLibraryRepository.findById(2L)).thenReturn(Optional.of(library(2L)));

        assertNotFound(() -> libraryService.getLibraryById(2L));
        assertNotFound(() -> libraryService.getLibraryById(3L));
    }

    // ==================== Series lists ====================

    @Test
    void seriesOfAnotherLibraryAreNotListed() {
        loginAsUser(1L);

        KomgaPageableDto<KomgaSeriesDto> result = seriesService.getAllSeries(2L, 0, 20, false);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString());
    }

    @Test
    void seriesWithoutLibraryFilterOnlyComeFromAssignedLibraries() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        KomgaPageableDto<KomgaSeriesDto> result = seriesService.getAllSeries(null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getName).containsExactly("Akira");
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGrouped(anyString());
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
    }

    @Test
    void searchDropsLibrariesTheUserCannotAccess() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);
        KomgaSeriesSearchRequestDto request = KomgaSeriesSearchRequestDto.builder()
                .condition(JsonMapper.builder().build().readTree("""
                        {"anyOf":[{"libraryId":{"operator":"is","value":"1"}},{"libraryId":{"operator":"is","value":"2"}}]}
                        """))
                .build();

        KomgaPageableDto<KomgaSeriesDto> result = seriesService.searchSeries(request, null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getLibraryId).containsExactly("1");
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
    }

    @Test
    void searchWithoutLibraryConditionOnlyCoversAssignedLibraries() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        KomgaPageableDto<KomgaSeriesDto> result = seriesService.searchSeries(null, null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getName).containsExactly("Akira");
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGrouped(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void restrictedUserDoesNotSeeFullyHiddenSeriesNorHiddenBooks() {
        loginAsUser(1L);
        restrict();
        seriesIn(1L, "Akira", 101L, 102L);
        seriesIn(1L, "Berserk", 103L);
        BookEntity hidden = books.get(102L);
        // SQL side: only the hidden books are loaded, 102 (one of two Akira books) and 103 (the only Berserk book)
        when(komgaBookRepository.findAll(any(Specification.class))).thenReturn(List.of(hidden, books.get(103L)));
        // In memory side: book 102 is hidden
        when(accessGateway.applyRestrictions(anyList(), eq(10L)))
                .thenAnswer(inv -> ((List<BookEntity>) inv.getArgument(0)).stream().filter(b -> b != hidden).toList());

        KomgaPageableDto<KomgaSeriesDto> result = seriesService.getAllSeries(null, 0, 20, false);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(series -> {
            assertThat(series.getName()).isEqualTo("Akira");
            assertThat(series.getBooksCount()).isEqualTo(1);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void userWithoutRestrictionsSkipsRestrictionFiltering() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        assertThat(seriesService.getAllSeries(null, 0, 20, false).getContent()).hasSize(1);
        verify(komgaBookRepository, never()).findAll(any(Specification.class));
        verify(accessGateway, never()).applyRestrictions(any(), any());
    }

    // ==================== Series by id ====================

    @Test
    void seriesOfAnotherLibraryAnswers404AndHasNoBooks() {
        loginAsUser(1L);

        assertNotFound(() -> seriesService.getSeriesById("2-berserk"));
        assertThat(seriesService.getBooksBySeries("2-berserk", 0, 20, false).getContent()).isEmpty();
        verify(komgaBookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
    }

    @Test
    void malformedOrUnknownSeriesIdAnswers404() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        assertNotFound(() -> seriesService.getSeriesById("akira"));
        assertNotFound(() -> seriesService.getSeriesById("x-akira"));
        assertNotFound(() -> seriesService.getSeriesById("1-unknown"));
        assertThat(seriesService.getBooksBySeries("x-akira", 0, 20, false).getContent()).isEmpty();
    }

    @Test
    void seriesWithOnlyHiddenBooksAnswers404() {
        loginAsUser(1L);
        restrict();
        seriesIn(1L, "Akira", 101L);
        when(accessGateway.applyRestrictions(anyList(), eq(10L))).thenReturn(List.of());

        assertNotFound(() -> seriesService.getSeriesById("1-akira"));
    }

    @Test
    void visibleSeriesIsReturned() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L, 102L);

        assertThat(seriesService.getSeriesById("1-akira").getName()).isEqualTo("Akira");
        assertThat(seriesService.getBooksBySeries("1-akira", 0, 20, false).getContent())
                .extracting(KomgaBookDto::getId).containsExactly("101", "102");
    }

    // ==================== Progress ====================

    @Test
    void seriesCountsAndBookProgressAreThoseOfTheCurrentUser() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L, 102L, 103L);
        seriesIn(1L, "Blame", 104L);
        when(progressGateway.bookProgress(eq(10L), anySet())).thenReturn(Map.of(
                101L, progress(101L, ReadStatus.READ),
                102L, progress(102L, ReadStatus.READING),
                104L, progress(104L, ReadStatus.WONT_READ)));

        seriesService.getAllSeries(null, 0, 20, false);

        // One progress lookup for the whole page, not one per series
        verify(progressGateway).bookProgress(10L, Set.of(101L, 102L, 103L, 104L));
        verify(komgaMapper).toKomgaSeriesDto(eq("Akira"), eq(1L), anyList(), eq(new ReadCounts(1, 1, 1)));
        verify(komgaMapper).toKomgaSeriesDto(eq("Blame"), eq(1L), anyList(), eq(new ReadCounts(0, 1, 0)));

        seriesService.getBooksBySeries("1-akira", 0, 20, false);
        verify(komgaMapper).toKomgaBookDto(eq(book(101L)), argThat(p -> p.state() == KomgaReadState.READ));
        verify(komgaMapper).toKomgaBookDto(eq(book(103L)), eq(BookProgress.NONE));
    }

    // ==================== Books ====================

    @Test
    void bookOfAnotherLibraryOrHiddenOrMissingAnswers404() {
        loginAsUser(1L);
        restrict();
        BookEntity other = book(201L);
        BookEntity hidden = book(102L);
        when(komgaBookRepository.findByIdWithBookFiles(201L)).thenReturn(Optional.of(other));
        when(komgaBookRepository.findByIdWithBookFiles(102L)).thenReturn(Optional.of(hidden));
        when(accessGateway.applyRestrictions(List.of(hidden), 10L)).thenReturn(List.of());

        assertNotFound(() -> bookService.getBookById(201L));
        assertNotFound(() -> bookService.getBookPages(201L));
        assertNotFound(() -> bookService.getBookPageImage(201L, 1, false));
        assertNotFound(() -> bookService.getBookById(102L));
        assertNotFound(() -> bookService.getBookById(999L));
    }

    @Test
    void visibleBookIsReturned() {
        loginAsUser(1L);
        when(komgaBookRepository.findByIdWithBookFiles(101L)).thenReturn(Optional.of(book(101L)));

        assertThat(bookService.getBookById(101L).getId()).isEqualTo("101");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bookListOfNonAdminIsFilteredInSql() {
        loginAsUser(1L);
        Page<BookEntity> page = new PageImpl<>(List.of(book(101L)), Pageable.ofSize(20), 1);
        when(komgaBookRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        KomgaPageableDto<KomgaBookDto> result = bookService.getAllBooks(null, 0, 20);

        assertThat(result.getContent()).extracting(KomgaBookDto::getId).containsExactly("101");
        verify(komgaBookRepository, never()).findAllWithMetadataPaged(any());
        verify(komgaBookRepository, never()).findAllWithMetadataByLibraryIdPaged(anyLong(), any());
    }

    @Test
    void bookListOfAdminKeepsTheFastQueries() {
        loginAsAdmin();
        when(komgaBookRepository.findAllWithMetadataPaged(any()))
                .thenReturn(new PageImpl<>(List.of(book(101L), book(201L)), Pageable.ofSize(20), 2));

        assertThat(bookService.getAllBooks(null, 0, 20).getContent()).hasSize(2);
    }

    @Test
    void bookListNormalizesInvalidPageAndSize() {
        loginAsAdmin();
        when(komgaBookRepository.findAllWithMetadataPaged(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(book(101L)), PageRequest.of(0, 20), 1));

        KomgaPageableDto<KomgaBookDto> result = bookService.getAllBooks(null, -1, 0);

        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getContent()).hasSize(1);
    }

    // ==================== Helpers ====================

    private void loginAsAdmin() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        when(accessGateway.currentUser())
                .thenReturn(BookLoreUser.builder().id(1L).permissions(permissions).build());
    }

    private void loginAsUser(Long... libraryIds) {
        List<Library> libraries = java.util.Arrays.stream(libraryIds).map(id -> Library.builder().id(id).build()).toList();
        when(accessGateway.currentUser()).thenReturn(BookLoreUser.builder()
                .id(10L)
                .permissions(new BookLoreUser.UserPermissions())
                .assignedLibraries(libraries)
                .build());
    }

    private void restrict() {
        when(accessGateway.restrictions(10L)).thenReturn(List.of(new UserContentRestrictionEntity()));
    }

    /** Declares a series of library {@code libraryId} made of the given books. */
    private void seriesIn(Long libraryId, String name, Long... bookIds) {
        List<String> names = new java.util.ArrayList<>(
                Optional.ofNullable(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, UNKNOWN)).orElse(List.of()));
        names.add(name);
        lenient().when(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, UNKNOWN)).thenReturn(names);
        List<BookEntity> seriesBooks = java.util.Arrays.stream(bookIds).map(this::book).toList();
        seriesBooks.forEach(b -> b.setMetadata(BookMetadataEntity.builder().seriesName(name).build()));
        lenient().when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId(name, libraryId, UNKNOWN)).thenReturn(seriesBooks);
    }

    private UserBookProgressEntity progress(Long bookId, ReadStatus status) {
        return UserBookProgressEntity.builder().book(book(bookId)).readStatus(status).build();
    }

    private BookEntity book(Long id) {
        return books.computeIfAbsent(id, key -> BookEntity.builder().id(key).library(library(key / 100)).build());
    }

    private static LibraryEntity library(Long id) {
        return LibraryEntity.builder().id(id).build();
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(APIException.class)
                .extracting(e -> ((APIException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
