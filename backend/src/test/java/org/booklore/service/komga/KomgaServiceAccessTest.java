package org.booklore.service.komga;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.APIException;
import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.dto.komga.KomgaBookDto;
import org.booklore.model.dto.komga.KomgaLibraryDto;
import org.booklore.model.dto.komga.KomgaPageableDto;
import org.booklore.model.dto.komga.KomgaSeriesDto;
import org.booklore.model.dto.komga.KomgaSeriesSearchRequestDto;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private BookRepository bookRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private KomgaMapper komgaMapper;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private ContentRestrictionService contentRestrictionService;

    @Mock
    private UserContentRestrictionRepository restrictionRepository;

    @Mock
    private AuthenticationService authenticationService;

    @InjectMocks
    private KomgaService komgaService;

    /** Books by id; book ids 1xx are in library 1, 2xx in library 2. */
    private final Map<Long, BookEntity> books = new HashMap<>();

    @BeforeEach
    void setUp() {
        AppSettings appSettings = new AppSettings();
        appSettings.setKomgaGroupUnknown(true);
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);
        lenient().when(komgaMapper.getUnknownSeriesName()).thenReturn(UNKNOWN);
        lenient().when(komgaMapper.toKomgaSeriesDto(anyString(), anyLong(), any()))
                .thenAnswer(inv -> KomgaSeriesDto.builder()
                        .name(inv.getArgument(0))
                        .libraryId(String.valueOf((Long) inv.getArgument(1)))
                        .booksCount(((List<?>) inv.getArgument(2)).size())
                        .build());
        lenient().when(komgaMapper.toKomgaBookDto(any()))
                .thenAnswer(inv -> KomgaBookDto.builder().id(((BookEntity) inv.getArgument(0)).getId().toString()).build());
        lenient().when(komgaMapper.toKomgaLibraryDto(any()))
                .thenAnswer(inv -> KomgaLibraryDto.builder().id(((LibraryEntity) inv.getArgument(0)).getId().toString()).build());
        // Users without restrictions see every book of their libraries
        lenient().when(contentRestrictionService.applyRestrictions(anyList(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== Libraries ====================

    @Test
    void librariesAreLimitedToAssignedOnes() {
        loginAsUser(1L);
        when(libraryRepository.findAll()).thenReturn(List.of(library(1L), library(2L)));

        assertThat(komgaService.getAllLibraries()).extracting(KomgaLibraryDto::getId).containsExactly("1");
    }

    @Test
    void adminSeesEveryLibrary() {
        loginAsAdmin();
        when(libraryRepository.findAll()).thenReturn(List.of(library(1L), library(2L)));

        assertThat(komgaService.getAllLibraries()).extracting(KomgaLibraryDto::getId).containsExactly("1", "2");
    }

    @Test
    void otherLibraryAndUnknownLibraryAnswer404() {
        loginAsUser(1L);
        when(libraryRepository.findById(2L)).thenReturn(Optional.of(library(2L)));

        assertNotFound(() -> komgaService.getLibraryById(2L));
        assertNotFound(() -> komgaService.getLibraryById(3L));
    }

    // ==================== Series lists ====================

    @Test
    void seriesOfAnotherLibraryAreNotListed() {
        loginAsUser(1L);

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.getAllSeries(2L, 0, 20, false);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString());
    }

    @Test
    void seriesWithoutLibraryFilterOnlyComeFromAssignedLibraries() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.getAllSeries(null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getName).containsExactly("Akira");
        verify(bookRepository, never()).findDistinctSeriesNamesGrouped(anyString());
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
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

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(request, null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getLibraryId).containsExactly("1");
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
    }

    @Test
    void searchWithoutLibraryConditionOnlyCoversAssignedLibraries() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(null, null, 0, 20, false);

        assertThat(result.getContent()).extracting(KomgaSeriesDto::getName).containsExactly("Akira");
        verify(bookRepository, never()).findDistinctSeriesNamesGrouped(anyString());
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
        when(bookRepository.findAll(any(Specification.class))).thenReturn(List.of(hidden, books.get(103L)));
        // In memory side: book 102 is hidden
        when(contentRestrictionService.applyRestrictions(anyList(), eq(10L)))
                .thenAnswer(inv -> ((List<BookEntity>) inv.getArgument(0)).stream().filter(b -> b != hidden).toList());

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.getAllSeries(null, 0, 20, false);

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

        assertThat(komgaService.getAllSeries(null, 0, 20, false).getContent()).hasSize(1);
        verify(bookRepository, never()).findAll(any(Specification.class));
        verifyNoInteractions(contentRestrictionService);
    }

    // ==================== Series by id ====================

    @Test
    void seriesOfAnotherLibraryAnswers404AndHasNoBooks() {
        loginAsUser(1L);

        assertNotFound(() -> komgaService.getSeriesById("2-berserk"));
        assertThat(komgaService.getBooksBySeries("2-berserk", 0, 20, false).getContent()).isEmpty();
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), anyString());
    }

    @Test
    void malformedOrUnknownSeriesIdAnswers404() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L);

        assertNotFound(() -> komgaService.getSeriesById("akira"));
        assertNotFound(() -> komgaService.getSeriesById("x-akira"));
        assertNotFound(() -> komgaService.getSeriesById("1-unknown"));
        assertThat(komgaService.getBooksBySeries("x-akira", 0, 20, false).getContent()).isEmpty();
    }

    @Test
    void seriesWithOnlyHiddenBooksAnswers404() {
        loginAsUser(1L);
        restrict();
        seriesIn(1L, "Akira", 101L);
        when(contentRestrictionService.applyRestrictions(anyList(), eq(10L))).thenReturn(List.of());

        assertNotFound(() -> komgaService.getSeriesById("1-akira"));
    }

    @Test
    void visibleSeriesIsReturned() {
        loginAsUser(1L);
        seriesIn(1L, "Akira", 101L, 102L);

        assertThat(komgaService.getSeriesById("1-akira").getName()).isEqualTo("Akira");
        assertThat(komgaService.getBooksBySeries("1-akira", 0, 20, false).getContent())
                .extracting(KomgaBookDto::getId).containsExactly("101", "102");
    }

    // ==================== Books ====================

    @Test
    void bookOfAnotherLibraryOrHiddenOrMissingAnswers404() {
        loginAsUser(1L);
        restrict();
        BookEntity other = book(201L);
        BookEntity hidden = book(102L);
        when(bookRepository.findByIdWithBookFiles(201L)).thenReturn(Optional.of(other));
        when(bookRepository.findByIdWithBookFiles(102L)).thenReturn(Optional.of(hidden));
        when(contentRestrictionService.applyRestrictions(List.of(hidden), 10L)).thenReturn(List.of());

        assertNotFound(() -> komgaService.getBookById(201L));
        assertNotFound(() -> komgaService.getBookPages(201L));
        assertNotFound(() -> komgaService.getBookPageImage(201L, 1, false));
        assertNotFound(() -> komgaService.getBookById(102L));
        assertNotFound(() -> komgaService.getBookById(999L));
    }

    @Test
    void visibleBookIsReturned() {
        loginAsUser(1L);
        when(bookRepository.findByIdWithBookFiles(101L)).thenReturn(Optional.of(book(101L)));

        assertThat(komgaService.getBookById(101L).getId()).isEqualTo("101");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bookListOfNonAdminIsFilteredInSql() {
        loginAsUser(1L);
        Page<BookEntity> page = new PageImpl<>(List.of(book(101L)), Pageable.ofSize(20), 1);
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        KomgaPageableDto<KomgaBookDto> result = komgaService.getAllBooks(null, 0, 20);

        assertThat(result.getContent()).extracting(KomgaBookDto::getId).containsExactly("101");
        verify(bookRepository, never()).findAllWithMetadataPaged(any());
        verify(bookRepository, never()).findAllWithMetadataByLibraryIdPaged(anyLong(), any());
    }

    @Test
    void bookListOfAdminKeepsTheFastQueries() {
        loginAsAdmin();
        when(bookRepository.findAllWithMetadataPaged(any()))
                .thenReturn(new PageImpl<>(List.of(book(101L), book(201L)), Pageable.ofSize(20), 2));

        assertThat(komgaService.getAllBooks(null, 0, 20).getContent()).hasSize(2);
    }

    // ==================== Helpers ====================

    private void loginAsAdmin() {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(1L).permissions(permissions).build());
    }

    private void loginAsUser(Long... libraryIds) {
        List<Library> libraries = java.util.Arrays.stream(libraryIds).map(id -> Library.builder().id(id).build()).toList();
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder()
                .id(10L)
                .permissions(new BookLoreUser.UserPermissions())
                .assignedLibraries(libraries)
                .build());
    }

    private void restrict() {
        when(restrictionRepository.findByUserId(10L)).thenReturn(List.of(new UserContentRestrictionEntity()));
    }

    /** Declares a series of library {@code libraryId} made of the given books. */
    private void seriesIn(Long libraryId, String name, Long... bookIds) {
        List<String> names = new java.util.ArrayList<>(
                Optional.ofNullable(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, UNKNOWN)).orElse(List.of()));
        names.add(name);
        lenient().when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, UNKNOWN)).thenReturn(names);
        List<BookEntity> seriesBooks = java.util.Arrays.stream(bookIds).map(this::book).toList();
        seriesBooks.forEach(b -> b.setMetadata(BookMetadataEntity.builder().seriesName(name).build()));
        lenient().when(bookRepository.findBooksBySeriesNameGroupedByLibraryId(name, libraryId, UNKNOWN)).thenReturn(seriesBooks);
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
