package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.komga.repository.KomgaBookWithoutSeriesProjection;
import org.booklore.komga.repository.KomgaSeriesDatesProjection;
import org.booklore.komga.series.SeriesIdCodec;
import org.booklore.komga.series.SeriesKey;
import org.booklore.komga.series.SeriesNaming;
import org.booklore.model.entity.BookEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Database side of virtual series: which series a user may see, their books, their dates,
 * and the resolution of a Komga series ID.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
class KomgaSeriesFinder {

    private final KomgaBookRepository komgaBookRepository;
    private final KomgaAccessResolver accessResolver;

    /** A series resolved from its Komga ID, with only the books the user may see. */
    record ResolvedSeries(String name, Long libraryId, List<BookEntity> books) {}

    /** Oldest and newest book added in a series. */
    record SeriesDates(Instant created, Instant lastModified) {
        SeriesDates merge(SeriesDates other) {
            return new SeriesDates(
                    created.isBefore(other.created) ? created : other.created,
                    lastModified.isAfter(other.lastModified) ? lastModified : other.lastModified);
        }
    }

    /**
     * Series the user may see, sorted by name. {@code requestedLibraryIds} empty means every library
     * the user can access; requested libraries the user cannot access are dropped.
     * <p>
     * Admins without library filter keep the cross-library listing (libraryId {@code null}).
     * Other users get one entry per accessible library. When the user has content restrictions,
     * series whose books are all hidden are removed, so they don't leak through names or counts.
     */
    List<SeriesKey> findVisibleSeries(Set<Long> requestedLibraryIds, KomgaAccess access, boolean groupUnknown) {
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

    /** Books of a series that the user may see, sorted by series number. */
    List<BookEntity> visibleBooks(SeriesKey series, KomgaAccess access, boolean groupUnknown) {
        return accessResolver.visibleBooks(findSeriesBooks(series, groupUnknown), access);
    }

    /**
     * Resolves a {@code <libraryId>-<slug>} series ID. Empty when the ID is malformed, the library
     * is not accessible to the user, no series matches, or all its books are hidden from the user.
     */
    Optional<ResolvedSeries> resolve(String seriesId, KomgaAccess access, boolean groupUnknown) {
        Optional<SeriesIdCodec.ParsedSeriesId> parsed = SeriesIdCodec.decode(seriesId);
        if (parsed.isEmpty() || !access.canSeeLibrary(parsed.get().libraryId())) {
            return Optional.empty();
        }
        Long libraryId = parsed.get().libraryId();

        // Find the series name that matches this slug
        Optional<String> matchedSeriesName = findSeriesNames(libraryId, groupUnknown).stream()
                .filter(parsed.get()::matches)
                .findFirst();
        if (matchedSeriesName.isEmpty()) {
            return Optional.empty();
        }

        // Load only the books for this specific series (already sorted by seriesNumber via query ORDER BY)
        String name = matchedSeriesName.get();
        List<BookEntity> books = visibleBooks(new SeriesKey(name, libraryId), access, groupUnknown);
        return books.isEmpty() ? Optional.empty() : Optional.of(new ResolvedSeries(name, libraryId, books));
    }

    /**
     * Computes the dates of every series, keyed both per library and across all libraries
     * (libraryId {@code null}). Series with a name are aggregated in SQL; only books without
     * series name are handled one by one, to derive their name like the series name queries do.
     */
    Map<SeriesKey, SeriesDates> seriesDates(boolean groupUnknown) {
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
}
