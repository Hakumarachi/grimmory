package org.booklore.service.komga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.komga.KomgaMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.dto.komga.*;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.projection.KomgaBookWithoutSeriesProjection;
import org.booklore.repository.projection.KomgaSeriesDatesProjection;
import org.booklore.service.MagicShelfService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.service.reader.PdfReaderService;
import org.booklore.service.restriction.ContentRestrictionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaService {

    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LUCENE_ESCAPE_PATTERN = Pattern.compile("\\\\(.)");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");
    private final BookRepository bookRepository;
    private final LibraryRepository libraryRepository;
    private final KomgaMapper komgaMapper;
    private final MagicShelfService magicShelfService;
    private final CbxReaderService cbxReaderService;
    private final PdfReaderService pdfReaderService;
    private final AppSettingService appSettingService;
    private final ContentRestrictionService contentRestrictionService;


    public boolean validateBookContentAccess(BookLoreUser user, Long bookId) {
        if (user == null) {
            return false;
        }

        if (user.getPermissions() != null && user.getPermissions().isAdmin()) {
            return true;
        }

        BookEntity book = bookRepository.findById(bookId)
                .orElse(null);

        if (book == null) {
            return false;
        }

        boolean hasLibraryAccess = user.getAssignedLibraries() != null && user.getAssignedLibraries().stream()
                .anyMatch(library -> library.getId().equals(book.getLibrary().getId()));

        if (!hasLibraryAccess) {
            return false;
        }

        List<BookEntity> filtered = contentRestrictionService.applyRestrictions(List.of(book), user.getId());
        if (filtered.isEmpty()) {
            return false;
        }

        return true;
    }


    public List<KomgaLibraryDto> getAllLibraries() {
        return libraryRepository.findAll().stream()
                .map(komgaMapper::toKomgaLibraryDto)
                .toList();
    }

    public KomgaLibraryDto getLibraryById(Long libraryId) {
        LibraryEntity library = libraryRepository.findById(libraryId)
                .orElseThrow(() -> new RuntimeException("Library not found"));
        return komgaMapper.toKomgaLibraryDto(library);
    }

    public KomgaPageableDto<KomgaSeriesDto> getAllSeries(Long libraryId, int page, int size, boolean unpaged) {
        log.debug("Getting all series for libraryId: {}, page: {}, size: {}", libraryId, page, size);

        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();

        // Get distinct series names directly from database (MUCH faster than loading all books)
        List<SeriesRef> series = findSeriesNames(libraryId, groupUnknown).stream()
                .map(name -> new SeriesRef(name, libraryId))
                .toList();

        log.debug("Found {} distinct series names from database (optimized)", series.size());

        return buildSeriesPage(series, page, size, unpaged, groupUnknown);
    }

    /**
     * Komga {@code POST /api/v1/series/list}: search series with a condition tree,
     * a full text search term and a sort.
     * <p>
     * Supported: {@code libraryId} conditions (any {@code allOf}/{@code anyOf} nesting, operator {@code is}),
     * {@code fullTextSearch} (case and accent insensitive match of every term on the series name),
     * sorts {@code metadata.titleSort}, {@code name}, {@code relevance}, {@code createdDate},
     * {@code lastModifiedDate} and {@code random}. Other conditions are ignored.
     */
    public KomgaPageableDto<KomgaSeriesDto> searchSeries(KomgaSeriesSearchRequestDto request, String sort,
                                                         int page, int size, boolean unpaged) {
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();

        Set<Long> libraryIds = request != null ? collectLibraryIds(request.getCondition()) : Set.of();
        List<String> terms = searchTerms(request != null ? request.getFullTextSearch() : null);

        List<SeriesRef> series = new ArrayList<>();
        if (libraryIds.isEmpty()) {
            findSeriesNames(null, groupUnknown).forEach(name -> series.add(new SeriesRef(name, null)));
        } else {
            for (Long libraryId : libraryIds) {
                findSeriesNames(libraryId, groupUnknown).forEach(name -> series.add(new SeriesRef(name, libraryId)));
            }
            if (libraryIds.size() > 1) {
                series.sort(Comparator.comparing(SeriesRef::name, String.CASE_INSENSITIVE_ORDER));
            }
        }

        if (!terms.isEmpty()) {
            series.removeIf(ref -> {
                String name = normalizeForSearch(ref.name());
                return terms.stream().anyMatch(term -> !name.contains(term));
            });
        }

        sortSeries(series, sort, groupUnknown);

        log.debug("Komga series search: libraries={}, terms={}, sort={}, matches={}", libraryIds, terms, sort, series.size());

        return buildSeriesPage(series, page, size, unpaged, groupUnknown);
    }

    /** A Komga series: its name, and the library it is scoped to ({@code null} = all libraries). */
    private record SeriesRef(String name, Long libraryId) {}

    private List<String> findSeriesNames(Long libraryId, boolean groupUnknown) {
        if (groupUnknown) {
            // Books without series are grouped as "Unknown Series"
            return libraryId != null
                    ? bookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, komgaMapper.getUnknownSeriesName())
                    : bookRepository.findDistinctSeriesNamesGrouped(komgaMapper.getUnknownSeriesName());
        }
        // Each book without series gets its own entry
        return libraryId != null
                ? bookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId)
                : bookRepository.findDistinctSeriesNamesUngrouped();
    }

    private List<BookEntity> findSeriesBooks(SeriesRef series, boolean groupUnknown) {
        if (series.libraryId() != null) {
            return groupUnknown
                    ? bookRepository.findBooksBySeriesNameGroupedByLibraryId(series.name(), series.libraryId(), komgaMapper.getUnknownSeriesName())
                    : bookRepository.findBooksBySeriesNameUngroupedByLibraryId(series.name(), series.libraryId());
        }
        // For all libraries without library filter
        return groupUnknown
                ? bookRepository.findBooksBySeriesNameGrouped(series.name(), komgaMapper.getUnknownSeriesName())
                : bookRepository.findBooksBySeriesNameUngrouped(series.name());
    }

    private KomgaPageableDto<KomgaSeriesDto> buildSeriesPage(List<SeriesRef> series, int page, int size,
                                                             boolean unpaged, boolean groupUnknown) {
        int totalElements = series.size();
        List<SeriesRef> pageSeries;
        int actualPage;
        int actualSize;
        int totalPages;

        if (unpaged) {
            pageSeries = series;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);

            pageSeries = series.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }

        // Now load books only for the series on this page (optimized - only loads what's needed)
        List<KomgaSeriesDto> content = new ArrayList<>();
        for (SeriesRef ref : pageSeries) {
            try {
                List<BookEntity> seriesBooks = findSeriesBooks(ref, groupUnknown);
                if (!seriesBooks.isEmpty()) {
                    Long libId = seriesBooks.getFirst().getLibrary().getId();
                    KomgaSeriesDto seriesDto = komgaMapper.toKomgaSeriesDto(ref.name(), libId, seriesBooks);
                    if (seriesDto != null) {
                        content.add(seriesDto);
                    }
                }
            } catch (Exception e) {
                log.error("Error mapping series: {}", ref.name(), e);
            }
        }

        log.debug("Mapped {} series DTOs for this page", content.size());

        return KomgaPageableDto.<KomgaSeriesDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    /** Collects the values of every {@code libraryId} leaf with operator {@code is}, at any depth. */
    static Set<Long> collectLibraryIds(JsonNode condition) {
        Set<Long> ids = new LinkedHashSet<>();
        collectLibraryIds(condition, ids);
        return ids;
    }

    private static void collectLibraryIds(JsonNode node, Set<Long> ids) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectLibraryIds(child, ids));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode libraryId = node.get("libraryId");
        if (libraryId != null && libraryId.isObject() && "is".equals(libraryId.path("operator").asString(""))) {
            try {
                ids.add(Long.parseLong(libraryId.path("value").asString("").trim()));
            } catch (NumberFormatException e) {
                log.debug("Ignoring non-numeric Komga libraryId condition: {}", libraryId);
            }
        }
        collectLibraryIds(node.get("allOf"), ids);
        collectLibraryIds(node.get("anyOf"), ids);
    }

    /** Splits a Komga full text search (Lucene-escaped) into normalized terms. */
    static List<String> searchTerms(String fullTextSearch) {
        if (fullTextSearch == null || fullTextSearch.isBlank()) {
            return List.of();
        }
        String unescaped = LUCENE_ESCAPE_PATTERN.matcher(fullTextSearch).replaceAll("$1");
        return Arrays.stream(normalizeForSearch(unescaped).split("\\s+"))
                .filter(term -> !term.isBlank())
                .toList();
    }

    private static String normalizeForSearch(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return DIACRITICS_PATTERN.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private void sortSeries(List<SeriesRef> series, String sort, boolean groupUnknown) {
        String field = null;
        boolean descending = false;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());
        }

        if (field == null) {
            return; // keep database order (series name ascending)
        }

        switch (field) {
            case "random" -> Collections.shuffle(series);
            case "createdDate", "lastModifiedDate" -> {
                Map<SeriesRef, SeriesDates> dates = seriesDates(groupUnknown);
                boolean created = "createdDate".equals(field);
                Comparator<SeriesRef> byDate = Comparator.comparing(
                        ref -> {
                            SeriesDates d = dates.get(ref);
                            return d == null ? null : created ? d.created() : d.lastModified();
                        },
                        Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));
                // Stable sort: series with the same date keep their name order
                series.sort(descending ? byDate.reversed() : byDate);
            }
            default -> {
                // metadata.titleSort, name, relevance...: series are already sorted by name
                if (descending) {
                    Collections.reverse(series);
                }
            }
        }
    }

    /** Oldest and newest book added in a series. */
    private record SeriesDates(Instant created, Instant lastModified) {
        SeriesDates merge(SeriesDates other) {
            return new SeriesDates(
                    created.isBefore(other.created) ? created : other.created,
                    lastModified.isAfter(other.lastModified) ? lastModified : other.lastModified);
        }
    }

    /**
     * Computes the dates of every series, keyed both per library and across all libraries
     * (libraryId {@code null}). Series with a name are aggregated in SQL; only books without
     * series name are handled one by one, to derive their name like the series name queries do.
     */
    private Map<SeriesRef, SeriesDates> seriesDates(boolean groupUnknown) {
        Map<SeriesRef, SeriesDates> dates = new HashMap<>();
        for (KomgaSeriesDatesProjection row : bookRepository.findKomgaSeriesDates()) {
            addSeriesDates(dates, row.getSeriesName(), row.getLibraryId(), row.getCreatedDate(), row.getLastModifiedDate());
        }

        String unknownSeriesName = groupUnknown ? komgaMapper.getUnknownSeriesName() : null;
        for (KomgaBookWithoutSeriesProjection row : bookRepository.findKomgaBooksWithoutSeries()) {
            String name = groupUnknown
                    ? unknownSeriesName
                    : (row.getTitle() != null ? row.getTitle() : row.getFileName());
            addSeriesDates(dates, name, row.getLibraryId(), row.getAddedOn(), row.getAddedOn());
        }
        return dates;
    }

    private static void addSeriesDates(Map<SeriesRef, SeriesDates> dates, String name, Long libraryId,
                                       Instant created, Instant lastModified) {
        if (name == null || created == null || lastModified == null) {
            return;
        }
        SeriesDates value = new SeriesDates(created, lastModified);
        dates.merge(new SeriesRef(name, libraryId), value, SeriesDates::merge);
        dates.merge(new SeriesRef(name, null), value, SeriesDates::merge);
    }

    public KomgaSeriesDto getSeriesById(String seriesId) {
        // Parse seriesId to extract library and series name
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid series ID");
        }
        
        Long libraryId = Long.parseLong(parts[0]);
        String seriesSlug = parts[1];
        
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        
        // Get distinct series names for this library to find the matching name by slug
        List<String> seriesNames;
        if (groupUnknown) {
            seriesNames = bookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, komgaMapper.getUnknownSeriesName());
        } else {
            seriesNames = bookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId);
        }
        
        // Find the series name that matches this slug
        String matchedSeriesName = seriesNames.stream()
                .filter(name -> {
                    String slug = NON_ALPHANUMERIC_PATTERN.matcher(name.toLowerCase()).replaceAll("-");
                    return slug.equals(seriesSlug);
                })
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Series not found"));
        
        // Load only the books for this specific series
        List<BookEntity> seriesBooks;
        if (groupUnknown) {
            seriesBooks = bookRepository.findBooksBySeriesNameGroupedByLibraryId(
                    matchedSeriesName, libraryId, komgaMapper.getUnknownSeriesName());
        } else {
            seriesBooks = bookRepository.findBooksBySeriesNameUngroupedByLibraryId(
                    matchedSeriesName, libraryId);
        }
        
        if (seriesBooks.isEmpty()) {
            throw new RuntimeException("Series not found");
        }
        
        return komgaMapper.toKomgaSeriesDto(matchedSeriesName, libraryId, seriesBooks);
    }

    public KomgaPageableDto<KomgaBookDto> getBooksBySeries(String seriesId, int page, int size, boolean unpaged) {
        // Parse seriesId to extract library and series name
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            throw new RuntimeException("Invalid series ID");
        }
        
        Long libraryId = Long.parseLong(parts[0]);
        String seriesSlug = parts[1];
        
        boolean groupUnknown = appSettingService.getAppSettings().isKomgaGroupUnknown();
        
        // Get distinct series names for this library to find the matching name by slug
        List<String> seriesNames;
        if (groupUnknown) {
            seriesNames = bookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, komgaMapper.getUnknownSeriesName());
        } else {
            seriesNames = bookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId);
        }
        
        // Find the series name that matches this slug
        String matchedSeriesName = seriesNames.stream()
                .filter(name -> {
                    String slug = NON_ALPHANUMERIC_PATTERN.matcher(name.toLowerCase()).replaceAll("-");
                    return slug.equals(seriesSlug);
                })
                .findFirst()
                .orElse(null);
        
        // Load only the books for this specific series (already sorted by seriesNumber via query ORDER BY)
        List<BookEntity> seriesBooks;
        if (matchedSeriesName == null) {
            seriesBooks = List.of();
        } else if (groupUnknown) {
            seriesBooks = bookRepository.findBooksBySeriesNameGroupedByLibraryId(
                    matchedSeriesName, libraryId, komgaMapper.getUnknownSeriesName());
        } else {
            seriesBooks = bookRepository.findBooksBySeriesNameUngroupedByLibraryId(
                    matchedSeriesName, libraryId);
        }
        
        // Handle unpaged mode
        int totalElements = seriesBooks.size();
        List<KomgaBookDto> content;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            // Return all books without pagination
            content = seriesBooks.stream()
                    .map(book -> komgaMapper.toKomgaBookDto(book))
                    .toList();
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            // Paginate
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            content = seriesBooks.subList(fromIndex, toIndex).stream()
                    .map(book -> komgaMapper.toKomgaBookDto(book))
                    .toList();
            actualPage = page;
            actualSize = size;
        }
        
        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaPageableDto<KomgaBookDto> getAllBooks(Long libraryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BookEntity> bookPage;
        
        if (libraryId != null) {
            bookPage = bookRepository.findAllWithMetadataByLibraryIdPaged(libraryId, pageable);
        } else {
            bookPage = bookRepository.findAllWithMetadataPaged(pageable);
        }
        
        List<KomgaBookDto> content = bookPage.getContent().stream()
                .map(book -> komgaMapper.toKomgaBookDto(book))
                .toList();
        
        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(page)
                .size(size)
                .numberOfElements(content.size())
                .totalElements((int) bookPage.getTotalElements())
                .totalPages(bookPage.getTotalPages())
                .first(page == 0)
                .last(page >= bookPage.getTotalPages() - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaBookDto getBookById(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        return komgaMapper.toKomgaBookDto(book);
    }

    public List<KomgaPageDto> getBookPages(Long bookId) {
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        
        BookMetadataEntity metadata = book.getMetadata();
        Integer pageCount = metadata != null && metadata.getPageCount() != null ? metadata.getPageCount() : 0;
        
        List<KomgaPageDto> pages = new ArrayList<>();
        if (pageCount > 0) {
            for (int i = 1; i <= pageCount; i++) {
                pages.add(KomgaPageDto.builder()
                        .number(i)
                        .fileName("page-" + i)
                        .mediaType("image/jpeg")
                        .build());
            }
        }
        
        return pages;
    }

    private Map<String, List<BookEntity>> groupBooksBySeries(List<BookEntity> books) {
        Map<String, List<BookEntity>> seriesMap = new HashMap<>();
        
        for (BookEntity book : books) {
            String seriesName = komgaMapper.getBookSeriesName(book);
            seriesMap.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(book);
        }
        
        return seriesMap;
    }
    
    public KomgaPageableDto<KomgaCollectionDto> getCollections(int page, int size, boolean unpaged) {
        log.debug("Getting collections, page: {}, size: {}, unpaged: {}", page, size, unpaged);
        
        List<MagicShelf> magicShelves = magicShelfService.getUserShelves();
        log.debug("Found {} magic shelves", magicShelves.size());
        
        // Convert to collection DTOs - for now, series count is 0 since we don't have 
        // the series filter implementation
        List<KomgaCollectionDto> allCollections = magicShelves.stream()
                .map(shelf -> komgaMapper.toKomgaCollectionDto(shelf, 0))
                .sorted(Comparator.comparing(KomgaCollectionDto::getName))
                .toList();
        
        log.debug("Mapped to {} collection DTOs", allCollections.size());
        
        // Handle unpaged mode
        int totalElements = allCollections.size();
        List<KomgaCollectionDto> content;
        int actualPage;
        int actualSize;
        int totalPages;
        
        if (unpaged) {
            content = allCollections;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            // Paginate
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            
            content = allCollections.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }
        
        return KomgaPageableDto.<KomgaCollectionDto>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }
    
    public Resource getBookPageImage(Long bookId, Integer pageNumber, boolean convertToPng) throws IOException {
        log.debug("Getting page {} from book {} (convert to PNG: {})", pageNumber, bookId, convertToPng);
        
        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

        boolean isPDF = book.getPrimaryBookFile().getBookType() == BookFileType.PDF;
     
        // Stream the page to a ByteArrayOutputStream
        // streamPageImage will throw if page does not exist
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Make sure pages are cached
        if (isPDF) {
            pdfReaderService.getAvailablePages(bookId);
            pdfReaderService.streamPageImage(bookId, pageNumber, outputStream);
        } else {
            cbxReaderService.getAvailablePages(bookId);
            cbxReaderService.streamPageImage(bookId, pageNumber, outputStream);
        }
        
        byte[] imageData = outputStream.toByteArray();
        
        // If conversion to PNG is requested, convert the image
        if (convertToPng) {
            imageData = convertImageToPng(imageData);
        }
        
        return new ByteArrayResource(imageData);
    }
    
    private byte[] convertImageToPng(byte[] imageData) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageData);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Failed to read image data");
            }
            
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}
