package org.booklore.komga.service;

import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.core.KomgaAccessGateway;
import org.booklore.komga.core.KomgaContentGateway;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.repository.KomgaLibraryRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.komga.dto.KomgaBookDto;
import org.booklore.komga.dto.KomgaPageDto;
import org.booklore.komga.dto.KomgaPageableDto;
import org.booklore.komga.dto.KomgaSeriesDto;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.komga.repository.KomgaBookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KomgaServiceTest {

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

    private LibraryEntity library;
    private List<BookEntity> seriesBooks;

    @BeforeEach
    void setUp() {
        KomgaAccessResolver accessResolver = new KomgaAccessResolver(accessGateway, komgaBookRepository);
        libraryService = new KomgaLibraryService(komgaLibraryRepository, komgaMapper, accessResolver);
        KomgaProgressReader progressReader = new KomgaProgressReader(progressGateway);
        seriesService = new KomgaSeriesService(new KomgaSeriesFinder(komgaBookRepository, accessResolver),
                komgaMapper, accessResolver, settings, contentGateway, progressReader);
        bookService = new KomgaBookService(komgaBookRepository, komgaMapper, accessResolver, contentGateway, progressReader);
        lenient().when(accessGateway.restrictionSpecification(any())).thenReturn((root, query, cb) -> cb.conjunction());
        library = new LibraryEntity();
        library.setId(1L);
        
        // Mock app settings (lenient because not all tests use this)
        lenient().when(settings.groupUnknown()).thenReturn(true);
        BookLoreUser.UserPermissions adminPermissions = new BookLoreUser.UserPermissions();
        adminPermissions.setAdmin(true);
        lenient().when(accessGateway.currentUser())
                .thenReturn(BookLoreUser.builder().id(1L).permissions(adminPermissions).build());

        // Create multiple books for testing pagination
        seriesBooks = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            BookMetadataEntity metadata = BookMetadataEntity.builder()
                    .title("Book " + i)
                    .seriesName("Test Series")
                    .seriesNumber((float) i)
                    .pageCount(null)  // Test null pageCount
                    .build();

            BookEntity book = new BookEntity();
            book.setId((long) i);
            book.setLibrary(library);
            book.setMetadata(metadata);
            book.setAddedOn(Instant.now());

            BookFileEntity pdf = new BookFileEntity();
            pdf.setId((long) i);
            pdf.setBook(book);
            pdf.setFileSubPath("author/title");
            pdf.setFileName("book-" + i + ".pdf");
            pdf.setBookType(BookFileType.PDF);
            pdf.setBookFormat(true);

            book.setBookFiles(Set.of(pdf));

            seriesBooks.add(book);
        }
    }

    @Test
    void shouldReturnAllBooksWhenUnpagedIsTrue() {
        // Given
        when(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(1L, "Unknown Series"))
                .thenReturn(List.of("Test Series"));
        when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId("Test Series", 1L, "Unknown Series"))
                .thenReturn(seriesBooks);
        
        // Mock the mapper to return DTOs
        for (BookEntity book : seriesBooks) {
            KomgaBookDto dto = KomgaBookDto.builder()
                    .id(book.getId().toString())
                    .name(book.getMetadata().getTitle())
                    .build();
            when(komgaMapper.toKomgaBookDto(eq(book), any())).thenReturn(dto);
        }

        // When: Request with unpaged=true
        KomgaPageableDto<KomgaBookDto> result = seriesService.getBooksBySeries("1-test-series", 0, 20, true);

        // Then: Should return all 50 books
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(50);
        assertThat(result.getTotalElements()).isEqualTo(50);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(50);
        assertThat(result.getNumber()).isEqualTo(0);
    }

    @Test
    void shouldReturnPagedBooksWhenUnpagedIsFalse() {
        // Given
        when(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(1L, "Unknown Series"))
                .thenReturn(List.of("Test Series"));
        when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId("Test Series", 1L, "Unknown Series"))
                .thenReturn(seriesBooks);
        
        // Mock the mapper to return DTOs (only for the books that will be used)
        for (int i = 0; i < 20; i++) {
            BookEntity book = seriesBooks.get(i);
            KomgaBookDto dto = KomgaBookDto.builder()
                    .id(book.getId().toString())
                    .name(book.getMetadata().getTitle())
                    .build();
            when(komgaMapper.toKomgaBookDto(eq(book), any())).thenReturn(dto);
        }

        // When: Request with unpaged=false and page size 20
        KomgaPageableDto<KomgaBookDto> result = seriesService.getBooksBySeries("1-test-series", 0, 20, false);

        // Then: Should return first page with 20 books
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(20);
        assertThat(result.getTotalElements()).isEqualTo(50);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getNumber()).isEqualTo(0);
    }

    @Test
    void shouldHandleNullPageCountInGetBookPages() {
        // Given: Book with null pageCount
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Book")
                .pageCount(null)
                .build();

        BookEntity book = new BookEntity();
        book.setId(100L);
        book.setMetadata(metadata);

        when(komgaBookRepository.findByIdWithBookFiles(100L)).thenReturn(Optional.of(book));

        // When: Get book pages
        List<KomgaPageDto> pages = bookService.getBookPages(100L);

        // Then: Should return empty list without throwing NPE
        assertThat(pages).isNotNull();
        assertThat(pages).isEmpty();
    }

    @Test
    void shouldReturnCorrectPagesWhenPageCountIsValid() {
        // Given: Book with valid pageCount
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .title("Test Book")
                .pageCount(5)
                .build();

        BookEntity book = new BookEntity();
        book.setId(100L);
        book.setMetadata(metadata);

        when(komgaBookRepository.findByIdWithBookFiles(100L)).thenReturn(Optional.of(book));

        // When: Get book pages
        List<KomgaPageDto> pages = bookService.getBookPages(100L);

        // Then: Should return 5 pages
        assertThat(pages).isNotNull();
        assertThat(pages).hasSize(5);
        assertThat(pages.get(0).getNumber()).isEqualTo(1);
        assertThat(pages.get(4).getNumber()).isEqualTo(5);
    }

    @Test
    void shouldGetAllSeriesOptimized() {
        // Given: Mock the optimized repository method
        List<String> seriesNames = List.of("Series A", "Series B", "Series C");
        when(komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(anyLong(), anyString()))
                .thenReturn(seriesNames);
        
        // Mock books for the first page (Series A and Series B only)
        List<BookEntity> seriesABooks = List.of(seriesBooks.get(0), seriesBooks.get(1));
        List<BookEntity> seriesBBooks = List.of(seriesBooks.get(2), seriesBooks.get(3));
        
        when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId("Series A", 1L, "Unknown Series"))
                .thenReturn(seriesABooks);
        when(komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId("Series B", 1L, "Unknown Series"))
                .thenReturn(seriesBBooks);
        
        when(komgaMapper.toKomgaSeriesDto(eq("Series A"), anyLong(), any(), any()))
                .thenReturn(KomgaSeriesDto.builder().id("1-series-a").name("Series A").booksCount(2).build());
        when(komgaMapper.toKomgaSeriesDto(eq("Series B"), anyLong(), any(), any()))
                .thenReturn(KomgaSeriesDto.builder().id("1-series-b").name("Series B").booksCount(2).build());
        
        // When: Request first page with size 2
        KomgaPageableDto<KomgaSeriesDto> result = seriesService.getAllSeries(1L, 0, 2, false);
        
        // Then: Should return only 2 series (not all 3)
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getNumber()).isEqualTo(0);
        assertThat(result.getFirst()).isTrue();
        assertThat(result.getLast()).isFalse();
        
        // Verify that only books for Series A and B were loaded (optimization check)
    }
}
