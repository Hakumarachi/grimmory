package org.booklore.service.komga;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.komga.KomgaPageableDto;
import org.booklore.model.dto.komga.KomgaSeriesDto;
import org.booklore.model.dto.komga.KomgaSeriesSearchRequestDto;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.repository.BookRepository;
import org.booklore.repository.projection.KomgaBookWithoutSeriesProjection;
import org.booklore.repository.projection.KomgaSeriesDatesProjection;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KomgaServiceSearchSeriesTest {

    private static final String UNKNOWN = "Unknown Series";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private BookRepository bookRepository;

    @Mock
    private KomgaMapper komgaMapper;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private AuthenticationService authenticationService;

    @Mock
    private UserContentRestrictionRepository restrictionRepository;

    @InjectMocks
    private KomgaService komgaService;

    @BeforeEach
    void setUp() {
        AppSettings appSettings = new AppSettings();
        appSettings.setKomgaGroupUnknown(true);
        lenient().when(appSettingService.getAppSettings()).thenReturn(appSettings);
        BookLoreUser.UserPermissions adminPermissions = new BookLoreUser.UserPermissions();
        adminPermissions.setAdmin(true);
        lenient().when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(1L).permissions(adminPermissions).build());
        lenient().when(komgaMapper.getUnknownSeriesName()).thenReturn(UNKNOWN);

        // Every series has one book in library 1, mapped to a DTO carrying its name
        lenient().when(bookRepository.findBooksBySeriesNameGrouped(anyString(), eq(UNKNOWN)))
                .thenAnswer(inv -> List.of(book(1L)));
        lenient().when(bookRepository.findBooksBySeriesNameGroupedByLibraryId(anyString(), anyLong(), eq(UNKNOWN)))
                .thenAnswer(inv -> List.of(book(inv.getArgument(1))));
        lenient().when(komgaMapper.toKomgaSeriesDto(anyString(), anyLong(), any()))
                .thenAnswer(inv -> KomgaSeriesDto.builder()
                        .name(inv.getArgument(0))
                        .libraryId(String.valueOf((Long) inv.getArgument(1)))
                        .build());
    }

    @Test
    void returnsAllSeriesWhenRequestIsEmpty() {
        when(bookRepository.findDistinctSeriesNamesGrouped(UNKNOWN)).thenReturn(List.of("Akira", "Berserk", "Claymore"));

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(null, null, 0, 20, false);

        assertThat(names(result)).containsExactly("Akira", "Berserk", "Claymore");
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getFirst()).isTrue();
        assertThat(result.getLast()).isTrue();
    }

    @Test
    void parsesMihonRequestAndFiltersByLibraryAndSearchTerm() {
        when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(2L, UNKNOWN))
                .thenReturn(List.of("008 Apprenti Espion", "Les Vieux Fourneaux", "L'Élève Ducobu"));

        // Body exactly as sent by the Mihon Komga extension (search term Lucene-escaped)
        KomgaSeriesSearchRequestDto request = request("""
                {"condition":{"allOf":[
                  {"deleted":{"operator":"isFalse"}},
                  {"anyOf":[{"libraryId":{"operator":"is","value":"2"}}]}
                ]},
                "fullTextSearch":"l\\\\'eleve"}
                """);

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(request, "relevance,desc", 0, 20, false);

        assertThat(names(result)).containsExactly("L'Élève Ducobu");
        verify(bookRepository, never()).findDistinctSeriesNamesGrouped(any());
    }

    @Test
    void everySearchTermMustMatch() {
        when(bookRepository.findDistinctSeriesNamesGrouped(UNKNOWN))
                .thenReturn(List.of("One Piece", "One Punch Man", "Piece of Cake"));

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(
                request("{\"fullTextSearch\":\"PIECE one\"}"), null, 0, 20, false);

        assertThat(names(result)).containsExactly("One Piece");
    }

    @Test
    void mergesSeveralLibrariesSortedByName() {
        when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(1L, UNKNOWN)).thenReturn(List.of("Blame!", "Dorohedoro"));
        when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(3L, UNKNOWN)).thenReturn(List.of("Asterix", "Coco"));

        KomgaSeriesSearchRequestDto request = request("""
                {"condition":{"allOf":[{"anyOf":[
                  {"libraryId":{"operator":"is","value":"1"}},
                  {"libraryId":{"operator":"is","value":"3"}},
                  {"libraryId":{"operator":"isNot","value":"2"}}
                ]}]}}
                """);

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(request, "metadata.titleSort,asc", 0, 20, false);

        assertThat(names(result)).containsExactly("Asterix", "Blame!", "Coco", "Dorohedoro");
        assertThat(result.getContent()).extracting(KomgaSeriesDto::getLibraryId).containsExactly("3", "1", "3", "1");
        verify(bookRepository, never()).findDistinctSeriesNamesGroupedByLibraryId(eq(2L), any());
    }

    @Test
    void sortsByTitleDescending() {
        when(bookRepository.findDistinctSeriesNamesGrouped(UNKNOWN)).thenReturn(List.of("A", "B", "C"));

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(null, "metadata.titleSort,desc", 0, 20, false);

        assertThat(names(result)).containsExactly("C", "B", "A");
    }

    @Test
    void sortsByLastModifiedDateDescendingForLatestUpdates() {
        when(bookRepository.findDistinctSeriesNamesGrouped(UNKNOWN)).thenReturn(List.of("Old", "Recent", "Unknown Series"));
        when(bookRepository.findKomgaSeriesDates()).thenReturn(List.of(
                seriesDates(1L, "Old", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                seriesDates(2L, "Old", "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z"), // same series in another library
                seriesDates(1L, "Recent", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z")
        ));
        when(bookRepository.findKomgaBooksWithoutSeries()).thenReturn(List.of(
                bookWithoutSeries(1L, "Loose", "2026-01-02T12:00:00Z")      // grouped as "Unknown Series"
        ));

        KomgaPageableDto<KomgaSeriesDto> latest = komgaService.searchSeries(null, "lastModifiedDate,desc", 0, 20, false);
        KomgaPageableDto<KomgaSeriesDto> created = komgaService.searchSeries(null, "createdDate,asc", 0, 20, false);

        // across libraries, "Old" spans 2026-01-01 .. 2026-01-03
        assertThat(names(latest)).containsExactly("Old", "Unknown Series", "Recent");
        assertThat(names(created)).containsExactly("Old", "Recent", "Unknown Series");
    }

    @Test
    void datesAreScopedPerLibraryWhenFilteringByLibrary() {
        when(bookRepository.findDistinctSeriesNamesGroupedByLibraryId(1L, UNKNOWN)).thenReturn(List.of("Old", "Recent"));
        when(bookRepository.findKomgaSeriesDates()).thenReturn(List.of(
                seriesDates(1L, "Old", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
                seriesDates(2L, "Old", "2026-01-03T00:00:00Z", "2026-01-03T00:00:00Z"), // ignored: other library
                seriesDates(1L, "Recent", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z")
        ));
        when(bookRepository.findKomgaBooksWithoutSeries()).thenReturn(List.of());

        KomgaPageableDto<KomgaSeriesDto> latest = komgaService.searchSeries(
                request("{\"condition\":{\"libraryId\":{\"operator\":\"is\",\"value\":\"1\"}}}"),
                "lastModifiedDate,desc", 0, 20, false);

        assertThat(names(latest)).containsExactly("Recent", "Old");
    }

    @Test
    void ungroupedBooksWithoutSeriesUseTitleThenFileName() {
        AppSettings appSettings = new AppSettings();
        appSettings.setKomgaGroupUnknown(false);
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
        when(bookRepository.findDistinctSeriesNamesUngrouped()).thenReturn(List.of("a-file.cbz", "Standalone"));
        when(bookRepository.findBooksBySeriesNameUngrouped(anyString())).thenAnswer(inv -> List.of(book(1L)));
        when(bookRepository.findKomgaSeriesDates()).thenReturn(List.of());
        when(bookRepository.findKomgaBooksWithoutSeries()).thenReturn(List.of(
                bookWithoutSeries(1L, "Standalone", "2026-01-01T00:00:00Z"),
                bookWithoutSeries(1L, null, "2026-01-05T00:00:00Z", "a-file.cbz")
        ));

        KomgaPageableDto<KomgaSeriesDto> latest = komgaService.searchSeries(null, "lastModifiedDate,desc", 0, 20, false);

        assertThat(names(latest)).containsExactly("a-file.cbz", "Standalone");
    }

    @Test
    void paginatesFilteredResults() {
        when(bookRepository.findDistinctSeriesNamesGrouped(UNKNOWN)).thenReturn(List.of("S1", "S2", "S3", "S4", "S5"));

        KomgaPageableDto<KomgaSeriesDto> result = komgaService.searchSeries(null, null, 1, 2, false);

        assertThat(names(result)).containsExactly("S3", "S4");
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getFirst()).isFalse();
        assertThat(result.getLast()).isFalse();
    }

    @Test
    void collectLibraryIdsIgnoresOtherOperatorsAndInvalidValues() {
        JsonNode condition = JSON.readTree("""
                {"allOf":[
                  {"libraryId":{"operator":"is","value":"5"}},
                  {"anyOf":[{"libraryId":{"operator":"is","value":"oops"}},{"libraryId":{"operator":"isNot","value":"6"}}]},
                  {"genre":{"operator":"is","value":"7"}}
                ]}
                """);

        assertThat(KomgaService.collectLibraryIds(condition)).containsExactly(5L);
        assertThat(KomgaService.collectLibraryIds(null)).isEmpty();
    }

    @Test
    void searchTermsAreUnescapedAndNormalized() {
        assertThat(KomgaService.searchTerms("  Spy\\-Family  \\(Édition\\) ")).containsExactly("spy-family", "(edition)");
        assertThat(KomgaService.searchTerms("   ")).isEmpty();
        assertThat(KomgaService.searchTerms(null)).isEmpty();
    }

    private static KomgaSeriesSearchRequestDto request(String json) {
        return JSON.readValue(json, KomgaSeriesSearchRequestDto.class);
    }

    private static List<String> names(KomgaPageableDto<KomgaSeriesDto> page) {
        return page.getContent().stream().map(KomgaSeriesDto::getName).toList();
    }

    private static BookEntity book(Long libraryId) {
        LibraryEntity library = new LibraryEntity();
        library.setId(libraryId);
        BookEntity book = new BookEntity();
        book.setLibrary(library);
        return book;
    }

    private static KomgaSeriesDatesProjection seriesDates(Long libraryId, String seriesName, String created, String lastModified) {
        return new KomgaSeriesDatesProjection() {
            @Override public Long getLibraryId() { return libraryId; }
            @Override public String getSeriesName() { return seriesName; }
            @Override public Instant getCreatedDate() { return Instant.parse(created); }
            @Override public Instant getLastModifiedDate() { return Instant.parse(lastModified); }
        };
    }

    private static KomgaBookWithoutSeriesProjection bookWithoutSeries(Long libraryId, String title, String addedOn) {
        return bookWithoutSeries(libraryId, title, addedOn, null);
    }

    private static KomgaBookWithoutSeriesProjection bookWithoutSeries(Long libraryId, String title, String addedOn, String fileName) {
        return new KomgaBookWithoutSeriesProjection() {
            @Override public Long getLibraryId() { return libraryId; }
            @Override public String getTitle() { return title; }
            @Override public String getFileName() { return fileName; }
            @Override public Instant getAddedOn() { return Instant.parse(addedOn); }
        };
    }
}
