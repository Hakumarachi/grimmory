package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaContentGateway;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.dto.KomgaBookDto;
import org.booklore.komga.dto.KomgaPageableDto;
import org.booklore.komga.dto.KomgaSeriesDto;
import org.booklore.komga.dto.KomgaSeriesSearchRequestDto;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.progress.KomgaReadState;
import org.booklore.komga.progress.ProgressSnapshot;
import org.booklore.komga.series.SeriesKey;
import org.booklore.komga.service.KomgaSeriesFinder.ResolvedSeries;
import org.booklore.komga.service.KomgaSeriesFinder.SeriesDates;
import org.booklore.model.entity.BookEntity;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Komga series: listing, search, details, books and thumbnail. */
@Slf4j
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaSeriesService {

    private final KomgaSeriesFinder seriesFinder;
    private final KomgaMapper komgaMapper;
    private final KomgaAccessResolver accessResolver;
    private final KomgaSettingsGateway settings;
    private final KomgaContentGateway contentGateway;
    private final KomgaProgressReader progressReader;

    public KomgaPageableDto<KomgaSeriesDto> getAllSeries(Long libraryId, int page, int size, boolean unpaged) {
        log.debug("Getting all series for libraryId: {}, page: {}, size: {}", libraryId, page, size);

        KomgaAccess access = accessResolver.current();
        boolean groupUnknown = settings.groupUnknown();

        // Get distinct series names directly from database (MUCH faster than loading all books)
        List<SeriesKey> series = seriesFinder.findVisibleSeries(libraryId != null ? Set.of(libraryId) : Set.of(), access, groupUnknown);

        log.debug("Found {} distinct series names from database (optimized)", series.size());

        return seriesPage(series, page, size, unpaged, groupUnknown, access);
    }

    /**
     * Komga {@code POST /api/v1/series/list}: search series with a condition tree,
     * a full text search term and a sort.
     * <p>
     * Supported: {@code libraryId} and {@code readStatus} conditions (any {@code allOf}/{@code anyOf} nesting,
     * operator {@code is}; several values of the same field match any of them),
     * {@code fullTextSearch} (case and accent insensitive match of every term on the series name),
     * sorts {@code metadata.titleSort}, {@code name}, {@code relevance}, {@code createdDate},
     * {@code lastModifiedDate} and {@code random}. Other conditions are ignored.
     */
    public KomgaPageableDto<KomgaSeriesDto> searchSeries(KomgaSeriesSearchRequestDto request, String sort,
                                                         int page, int size, boolean unpaged) {
        KomgaAccess access = accessResolver.current();
        boolean groupUnknown = settings.groupUnknown();

        Set<Long> libraryIds = request != null ? KomgaSeriesSearch.collectLibraryIds(request.getCondition()) : Set.of();
        List<String> terms = KomgaSeriesSearch.searchTerms(request != null ? request.getFullTextSearch() : null);

        List<SeriesKey> series = seriesFinder.findVisibleSeries(libraryIds, access, groupUnknown);

        if (!terms.isEmpty()) {
            series.removeIf(ref -> !KomgaSeriesSearch.matchesAll(ref.name(), terms));
        }

        Set<KomgaReadState> readStates = request != null ? KomgaSeriesSearch.collectReadStates(request.getCondition()) : Set.of();
        if (!readStates.isEmpty() && readStates.size() < KomgaReadState.values().length) {
            keepReadStates(series, readStates, access, groupUnknown);
        }

        sortSeries(series, sort, groupUnknown);

        log.debug("Komga series search: libraries={}, terms={}, sort={}, matches={}", libraryIds, terms, sort, series.size());

        return seriesPage(series, page, size, unpaged, groupUnknown, access);
    }

    public KomgaSeriesDto getSeriesById(String seriesId) {
        KomgaAccess access = accessResolver.current();
        ResolvedSeries series = seriesFinder.resolve(seriesId, access, settings.groupUnknown())
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Series not found: " + seriesId));
        ProgressSnapshot progress = progressReader.load(access, series.books());
        return komgaMapper.toKomgaSeriesDto(series.name(), series.libraryId(), series.books(), progress.counts(series.books()));
    }

    public KomgaPageableDto<KomgaBookDto> getBooksBySeries(String seriesId, int page, int size, boolean unpaged) {
        KomgaAccess access = accessResolver.current();
        // Unknown or hidden series: empty page, like a Komga search with a seriesId condition
        List<BookEntity> seriesBooks = visibleSeriesBooks(seriesId, access);
        return KomgaPaging.page(seriesBooks, page, size, unpaged, books -> {
            ProgressSnapshot progress = progressReader.load(access, books);
            return books.stream().map(book -> komgaMapper.toKomgaBookDto(book, progress.of(book))).toList();
        });
    }

    /** Thumbnail of a series: the one of its first visible book; {@code null} when there is none. */
    public Resource getSeriesThumbnail(String seriesId) {
        List<BookEntity> books = visibleSeriesBooks(seriesId, accessResolver.current());
        return books.isEmpty() ? null : contentGateway.thumbnail(books.getFirst().getId());
    }

    /** Visible books of a series, sorted by number; empty when the series is unknown or hidden. */
    private List<BookEntity> visibleSeriesBooks(String seriesId, KomgaAccess access) {
        return seriesFinder.resolve(seriesId, access, settings.groupUnknown())
                .map(ResolvedSeries::books)
                .orElse(List.of());
    }

    /** Pages the series, loading the books of the series on the requested page only. */
    private KomgaPageableDto<KomgaSeriesDto> seriesPage(List<SeriesKey> series, int page, int size,
                                                        boolean unpaged, boolean groupUnknown, KomgaAccess access) {
        return KomgaPaging.page(series, page, size, unpaged, pageSeries -> {
            Map<SeriesKey, List<BookEntity>> booksBySeries = new LinkedHashMap<>();
            for (SeriesKey ref : pageSeries) {
                try {
                    booksBySeries.put(ref, seriesFinder.visibleBooks(ref, access, groupUnknown));
                } catch (Exception e) {
                    log.error("Error loading series: {}", ref.name(), e);
                }
            }
            // One progress lookup for the whole page
            ProgressSnapshot progress = progressReader.load(access,
                    booksBySeries.values().stream().flatMap(List::stream).toList());

            List<KomgaSeriesDto> content = new ArrayList<>();
            booksBySeries.forEach((ref, seriesBooks) -> {
                try {
                    if (!seriesBooks.isEmpty()) {
                        Long libId = seriesBooks.getFirst().getLibrary().getId();
                        KomgaSeriesDto seriesDto = komgaMapper.toKomgaSeriesDto(ref.name(), libId, seriesBooks,
                                progress.counts(seriesBooks));
                        if (seriesDto != null) {
                            content.add(seriesDto);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error mapping series: {}", ref.name(), e);
                }
            });
            log.debug("Mapped {} series DTOs for this page", content.size());
            return content;
        });
    }

    /**
     * Keeps the series whose read status (for the current user) is one of {@code states}. Needs the books of
     * every candidate series, so it is only done when the request filters on the read status.
     */
    private void keepReadStates(List<SeriesKey> series, Set<KomgaReadState> states, KomgaAccess access, boolean groupUnknown) {
        Map<SeriesKey, List<BookEntity>> booksBySeries = new HashMap<>();
        for (SeriesKey ref : series) {
            booksBySeries.put(ref, seriesFinder.visibleBooks(ref, access, groupUnknown));
        }
        ProgressSnapshot progress = progressReader.load(access,
                booksBySeries.values().stream().flatMap(List::stream).toList());
        series.removeIf(ref -> {
            List<BookEntity> books = booksBySeries.get(ref);
            return books.isEmpty() || !states.contains(progress.counts(books).seriesState());
        });
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
                Map<SeriesKey, SeriesDates> dates = seriesFinder.seriesDates(groupUnknown);
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
}
