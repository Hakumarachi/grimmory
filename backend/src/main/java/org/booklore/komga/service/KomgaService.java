package org.booklore.komga.service;

import org.booklore.komga.config.ConditionalOnKomgaEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.core.KomgaContentGateway;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.core.KomgaShelfGateway;
import org.booklore.komga.core.KomgaUserGateway;
import org.booklore.komga.dto.*;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.komga.repository.KomgaBookWithoutSeriesProjection;
import org.booklore.komga.repository.KomgaLibraryRepository;
import org.booklore.komga.repository.KomgaSeriesDatesProjection;
import org.booklore.komga.series.SeriesIdCodec;
import org.booklore.komga.series.SeriesKey;
import org.booklore.komga.series.SeriesNaming;
import org.booklore.model.dto.MagicShelf;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.OpdsUserV2Entity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaService {

    private static final Pattern LUCENE_ESCAPE_PATTERN = Pattern.compile("\\\\(.)");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");
    private final KomgaBookRepository komgaBookRepository;
    private final KomgaLibraryRepository komgaLibraryRepository;
    private final KomgaMapper komgaMapper;
    private final KomgaAccessResolver accessResolver;
    private final KomgaSettingsGateway settings;
    private final KomgaContentGateway contentGateway;
    private final KomgaShelfGateway shelfGateway;
    private final KomgaUserGateway userGateway;

    public List<KomgaLibraryDto> getAllLibraries() {
        KomgaAccess access = accessResolver.current();
        return komgaLibraryRepository.findAll().stream()
                .filter(library -> access.canSeeLibrary(library.getId()))
                .map(komgaMapper::toKomgaLibraryDto)
                .toList();
    }

    public KomgaLibraryDto getLibraryById(Long libraryId) {
        KomgaAccess access = accessResolver.current();
        LibraryEntity library = komgaLibraryRepository.findById(libraryId)
                .filter(found -> access.canSeeLibrary(found.getId()))
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return komgaMapper.toKomgaLibraryDto(library);
    }

    public KomgaPageableDto<KomgaSeriesDto> getAllSeries(Long libraryId, int page, int size, boolean unpaged) {
        log.debug("Getting all series for libraryId: {}, page: {}, size: {}", libraryId, page, size);

        KomgaAccess access = accessResolver.current();
        boolean groupUnknown = settings.groupUnknown();

        // Get distinct series names directly from database (MUCH faster than loading all books)
        List<SeriesKey> series = findVisibleSeries(libraryId != null ? Set.of(libraryId) : Set.of(), access, groupUnknown);

        log.debug("Found {} distinct series names from database (optimized)", series.size());

        return buildSeriesPage(series, page, size, unpaged, groupUnknown, access);
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
        KomgaAccess access = accessResolver.current();
        boolean groupUnknown = settings.groupUnknown();

        Set<Long> libraryIds = request != null ? collectLibraryIds(request.getCondition()) : Set.of();
        List<String> terms = searchTerms(request != null ? request.getFullTextSearch() : null);

        List<SeriesKey> series = findVisibleSeries(libraryIds, access, groupUnknown);

        if (!terms.isEmpty()) {
            series.removeIf(ref -> {
                String name = normalizeForSearch(ref.name());
                return terms.stream().anyMatch(term -> !name.contains(term));
            });
        }

        sortSeries(series, sort, groupUnknown);

        log.debug("Komga series search: libraries={}, terms={}, sort={}, matches={}", libraryIds, terms, sort, series.size());

        return buildSeriesPage(series, page, size, unpaged, groupUnknown, access);
    }

    /**
     * Series the user may see, sorted by name. {@code requestedLibraryIds} empty means every library
     * the user can access; requested libraries the user cannot access are dropped.
     * <p>
     * Admins without library filter keep the cross-library listing (libraryId {@code null}).
     * Other users get one entry per accessible library. When the user has content restrictions,
     * series whose books are all hidden are removed, so they don't leak through names or counts.
     */
    private List<SeriesKey> findVisibleSeries(Set<Long> requestedLibraryIds, KomgaAccess access, boolean groupUnknown) {
        List<SeriesKey> series = new ArrayList<>();
        if (access.admin() && requestedLibraryIds.isEmpty()) {
            findSeriesNames(null, groupUnknown).forEach(name -> series.add(new SeriesKey(name, null)));
            return series;
        }

        List<Long> libraryIds = (requestedLibraryIds.isEmpty() ? access.libraryIds() : requestedLibraryIds).stream()
                .filter(access::canSeeLibrary)
                .toList();
        for (Long libraryId : libraryIds) {
            findSeriesNames(libraryId, groupUnknown).forEach(name -> series.add(new SeriesKey(name, libraryId)));
        }
        if (libraryIds.size() > 1) {
            series.sort(Comparator.comparing(SeriesKey::name, String.CASE_INSENSITIVE_ORDER));
        }

        if (access.restricted() && !series.isEmpty()) {
            series.removeAll(fullyHiddenSeries(libraryIds, access, groupUnknown));
        }
        return series;
    }

    /**
     * Series whose books are all hidden by the user's content restrictions. Only the hidden books
     * are loaded (usually a handful), then each series holding one is counted.
     */
    private Set<SeriesKey> fullyHiddenSeries(Collection<Long> libraryIds, KomgaAccess access, boolean groupUnknown) {
        Map<SeriesKey, Integer> hiddenCounts = new HashMap<>();
        for (BookEntity book : komgaBookRepository.findAll(accessResolver.hiddenBooksSpec(libraryIds, access))) {
            hiddenCounts.merge(new SeriesKey(SeriesNaming.nameOf(book, groupUnknown), book.getLibrary().getId()), 1, Integer::sum);
        }
        Set<SeriesKey> fullyHidden = new HashSet<>();
        hiddenCounts.forEach((ref, hidden) -> {
            if (hidden >= findSeriesBooks(ref, groupUnknown).size()) {
                fullyHidden.add(ref);
            }
        });
        return fullyHidden;
    }

    private List<String> findSeriesNames(Long libraryId, boolean groupUnknown) {
        if (groupUnknown) {
            // Books without series are grouped as "Unknown Series"
            return libraryId != null
                    ? komgaBookRepository.findDistinctSeriesNamesGroupedByLibraryId(libraryId, SeriesNaming.UNKNOWN_SERIES)
                    : komgaBookRepository.findDistinctSeriesNamesGrouped(SeriesNaming.UNKNOWN_SERIES);
        }
        // Each book without series gets its own entry
        return libraryId != null
                ? komgaBookRepository.findDistinctSeriesNamesUngroupedByLibraryId(libraryId)
                : komgaBookRepository.findDistinctSeriesNamesUngrouped();
    }

    private List<BookEntity> findSeriesBooks(SeriesKey series, boolean groupUnknown) {
        if (series.libraryId() != null) {
            return groupUnknown
                    ? komgaBookRepository.findBooksBySeriesNameGroupedByLibraryId(series.name(), series.libraryId(), SeriesNaming.UNKNOWN_SERIES)
                    : komgaBookRepository.findBooksBySeriesNameUngroupedByLibraryId(series.name(), series.libraryId());
        }
        // For all libraries without library filter
        return groupUnknown
                ? komgaBookRepository.findBooksBySeriesNameGrouped(series.name(), SeriesNaming.UNKNOWN_SERIES)
                : komgaBookRepository.findBooksBySeriesNameUngrouped(series.name());
    }

    private KomgaPageableDto<KomgaSeriesDto> buildSeriesPage(List<SeriesKey> series, int page, int size,
                                                             boolean unpaged, boolean groupUnknown, KomgaAccess access) {
        int totalElements = series.size();
        List<SeriesKey> pageSeries;
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
        for (SeriesKey ref : pageSeries) {
            try {
                List<BookEntity> seriesBooks = accessResolver.visibleBooks(findSeriesBooks(ref, groupUnknown), access);
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

    private void sortSeries(List<SeriesKey> series, String sort, boolean groupUnknown) {
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
                Map<SeriesKey, SeriesDates> dates = seriesDates(groupUnknown);
                boolean created = "createdDate".equals(field);
                Comparator<SeriesKey> byDate = Comparator.comparing(
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
    private Map<SeriesKey, SeriesDates> seriesDates(boolean groupUnknown) {
        Map<SeriesKey, SeriesDates> dates = new HashMap<>();
        for (KomgaSeriesDatesProjection row : komgaBookRepository.findKomgaSeriesDates()) {
            addSeriesDates(dates, row.getSeriesName(), row.getLibraryId(), row.getCreatedDate(), row.getLastModifiedDate());
        }

        String unknownSeriesName = groupUnknown ? SeriesNaming.UNKNOWN_SERIES : null;
        for (KomgaBookWithoutSeriesProjection row : komgaBookRepository.findKomgaBooksWithoutSeries()) {
            String name = groupUnknown
                    ? unknownSeriesName
                    : (row.getTitle() != null ? row.getTitle() : row.getFileName());
            addSeriesDates(dates, name, row.getLibraryId(), row.getAddedOn(), row.getAddedOn());
        }
        return dates;
    }

    private static void addSeriesDates(Map<SeriesKey, SeriesDates> dates, String name, Long libraryId,
                                       Instant created, Instant lastModified) {
        if (name == null || created == null || lastModified == null) {
            return;
        }
        SeriesDates value = new SeriesDates(created, lastModified);
        dates.merge(new SeriesKey(name, libraryId), value, SeriesDates::merge);
        dates.merge(new SeriesKey(name, null), value, SeriesDates::merge);
    }

    /** A series resolved from its Komga ID, with only the books the user may see. */
    private record ResolvedSeries(String name, Long libraryId, List<BookEntity> books) {}

    /**
     * Resolves a {@code <libraryId>-<slug>} series ID. Empty when the ID is malformed, the library
     * is not accessible to the user, no series matches, or all its books are hidden from the user.
     */
    private Optional<ResolvedSeries> resolveSeries(String seriesId, KomgaAccess access) {
        Optional<SeriesIdCodec.ParsedSeriesId> parsed = SeriesIdCodec.decode(seriesId);
        if (parsed.isEmpty() || !access.canSeeLibrary(parsed.get().libraryId())) {
            return Optional.empty();
        }
        Long libraryId = parsed.get().libraryId();
        boolean groupUnknown = settings.groupUnknown();

        // Find the series name that matches this slug
        Optional<String> matchedSeriesName = findSeriesNames(libraryId, groupUnknown).stream()
                .filter(parsed.get()::matches)
                .findFirst();
        if (matchedSeriesName.isEmpty()) {
            return Optional.empty();
        }

        // Load only the books for this specific series (already sorted by seriesNumber via query ORDER BY)
        String name = matchedSeriesName.get();
        List<BookEntity> books = accessResolver.visibleBooks(findSeriesBooks(new SeriesKey(name, libraryId), groupUnknown), access);
        return books.isEmpty() ? Optional.empty() : Optional.of(new ResolvedSeries(name, libraryId, books));
    }

    public KomgaSeriesDto getSeriesById(String seriesId) {
        ResolvedSeries series = resolveSeries(seriesId, accessResolver.current())
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Series not found: " + seriesId));
        return komgaMapper.toKomgaSeriesDto(series.name(), series.libraryId(), series.books());
    }

    public KomgaPageableDto<KomgaBookDto> getBooksBySeries(String seriesId, int page, int size, boolean unpaged) {
        // Unknown or hidden series: empty page, like a Komga search with a seriesId condition
        List<BookEntity> seriesBooks = resolveSeries(seriesId, accessResolver.current())
                .map(ResolvedSeries::books)
                .orElse(List.of());
        
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
        KomgaAccess access = accessResolver.current();
        Pageable pageable = PageRequest.of(page, size);
        Page<BookEntity> bookPage;

        if (!access.admin()) {
            // Only accessible libraries, minus books hidden by content restrictions (filtered in SQL so counts stay right)
            Set<Long> libraryIds = libraryId == null ? access.libraryIds()
                    : access.canSeeLibrary(libraryId) ? Set.of(libraryId) : Set.of();
            bookPage = komgaBookRepository.findAll(accessResolver.visibleBooksSpec(libraryIds, access), pageable);
        } else if (libraryId != null) {
            bookPage = komgaBookRepository.findAllWithMetadataByLibraryIdPaged(libraryId, pageable);
        } else {
            bookPage = komgaBookRepository.findAllWithMetadataPaged(pageable);
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
        BookEntity book = accessResolver.requireVisibleBook(bookId);
        return komgaMapper.toKomgaBookDto(book);
    }

    public List<KomgaPageDto> getBookPages(Long bookId) {
        BookEntity book = accessResolver.requireVisibleBook(bookId);
        
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

    public KomgaPageableDto<KomgaCollectionDto> getCollections(int page, int size, boolean unpaged) {
        log.debug("Getting collections, page: {}, size: {}, unpaged: {}", page, size, unpaged);
        
        List<MagicShelf> magicShelves = shelfGateway.magicShelves();
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
    
    /** 404 unless the current user may see the book. */
    public void requireBookAccess(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
    }

    /** Thumbnail of a book the user may see; {@code null} when the book has none. */
    public Resource getBookThumbnail(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
        return contentGateway.thumbnail(bookId);
    }

    /** Thumbnail of a series: the one of its first visible book; {@code null} when there is none. */
    public Resource getSeriesThumbnail(String seriesId) {
        KomgaPageableDto<KomgaBookDto> books = getBooksBySeries(seriesId, 0, 1, false);
        if (books.getContent().isEmpty()) {
            return null;
        }
        return contentGateway.thumbnail(Long.parseLong(books.getContent().getFirst().getId()));
    }

    /** File of a book the user may see. */
    public ResponseEntity<StreamingResponseBody> downloadBook(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
        return contentGateway.download(bookId);
    }

    /** Komga user of an OPDS account, or {@code null} when the account does not exist. */
    public KomgaUserDto getCurrentUser(String username) {
        OpdsUserV2Entity opdsUser = userGateway.opdsUser(username);
        return opdsUser != null ? komgaMapper.toKomgaUserDto(opdsUser) : null;
    }

    public Resource getBookPageImage(Long bookId, Integer pageNumber, boolean convertToPng) throws IOException {
        log.debug("Getting page {} from book {} (convert to PNG: {})", pageNumber, bookId, convertToPng);
        
        BookEntity book = accessResolver.requireVisibleBook(bookId);

        // Stream the page to a ByteArrayOutputStream; throws if the page does not exist
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        contentGateway.streamPage(bookId, book.getPrimaryBookFile().getBookType(), pageNumber, outputStream);
        
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
