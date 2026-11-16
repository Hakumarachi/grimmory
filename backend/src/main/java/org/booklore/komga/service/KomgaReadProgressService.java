package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.dto.KomgaReadProgressUpdateDto;
import org.booklore.komga.dto.KomgaTachiyomiReadProgressDto;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.progress.KomgaReadState;
import org.booklore.komga.progress.ProgressSnapshot;
import org.booklore.komga.progress.ReadCounts;
import org.booklore.komga.series.SeriesNumbering;
import org.booklore.komga.service.KomgaSeriesFinder.ResolvedSeries;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Komga read progress of the current user: per book, per series, and the Mihon tracker.
 * Every write goes through the core progress services (web reader, Kobo and KOReader stay consistent),
 * in a read-write transaction, after the access check.
 */
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
public class KomgaReadProgressService {

    private final KomgaAccessResolver accessResolver;
    private final KomgaSeriesFinder seriesFinder;
    private final KomgaSettingsGateway settings;
    private final KomgaProgressGateway progressGateway;
    private final KomgaProgressReader progressReader;

    /**
     * {@code completed: true} marks the book read; otherwise {@code page} (1 to the page count) becomes the
     * current position, and reaching the last page marks the book read. Pages are only known for comics and PDFs.
     */
    @Transactional
    public void updateBookProgress(Long bookId, KomgaReadProgressUpdateDto update) {
        BookEntity book = accessResolver.requireVisibleBook(bookId);
        if (update != null && Boolean.TRUE.equals(update.getCompleted())) {
            progressGateway.setReadStatus(List.of(book.getId()), ReadStatus.READ);
            return;
        }
        if (update == null || update.getPage() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("page must be specified if completed is false or null");
        }

        BookFileEntity file = book.getPrimaryBookFile();
        if (file == null || (file.getBookType() != BookFileType.CBX && file.getBookType() != BookFileType.PDF)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Page progress is only supported for comic and PDF books");
        }
        Integer pageCount = book.getMetadata() != null ? book.getMetadata().getPageCount() : null;
        if (pageCount == null || pageCount < 1) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("The page count of this book is unknown");
        }
        int page = update.getPage();
        if (page < 1 || page > pageCount) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Page argument (" + page + ") must be within 1 and book page count (" + pageCount + ")");
        }
        progressGateway.savePosition(book.getId(), file.getId(), page, page * 100f / pageCount);
    }

    /** Marks the book unread. */
    @Transactional
    public void deleteBookProgress(Long bookId) {
        BookEntity book = accessResolver.requireVisibleBook(bookId);
        progressGateway.setReadStatus(List.of(book.getId()), ReadStatus.UNREAD);
    }

    /** Marks every visible book of the series read (bulk permission required for several books). */
    @Transactional
    public void markSeriesRead(String seriesId) {
        progressGateway.setReadStatus(bookIds(requireSeries(seriesId, accessResolver.current())), ReadStatus.READ);
    }

    /** Marks every visible book of the series unread (bulk permission required for several books). */
    @Transactional
    public void markSeriesUnread(String seriesId) {
        progressGateway.setReadStatus(bookIds(requireSeries(seriesId, accessResolver.current())), ReadStatus.UNREAD);
    }

    /** Mihon tracker state of the series. */
    @Transactional(readOnly = true)
    public KomgaTachiyomiReadProgressDto getTachiyomiProgress(String seriesId) {
        KomgaAccess access = accessResolver.current();
        List<BookEntity> books = byNumber(requireSeries(seriesId, access));
        ProgressSnapshot progress = progressReader.load(access, books);
        ReadCounts counts = progress.counts(books);

        float lastReadContinuous = 0f;
        for (BookEntity book : books) {
            if (progress.of(book).state() != KomgaReadState.READ) {
                break;
            }
            lastReadContinuous = SeriesNumbering.numberSort(book);
        }
        float maxNumber = (float) books.stream().mapToDouble(SeriesNumbering::numberSort).max().orElse(0);

        return KomgaTachiyomiReadProgressDto.builder()
                .booksCount(books.size())
                .booksReadCount(counts.read())
                .booksUnreadCount(counts.unread())
                .booksInProgressCount(counts.inProgress())
                .lastReadContinuousNumberSort(lastReadContinuous)
                .maxNumberSort(maxNumber)
                .build();
    }

    /**
     * Mihon tracker: every book numbered up to {@code lastBookNumberSortRead} that is not read yet is marked
     * read. Books are marked one by one, like Komga does, so the tracker works without the bulk permission.
     */
    @Transactional
    public void updateTachiyomiProgress(String seriesId, Float lastBookNumberSortRead) {
        if (lastBookNumberSortRead == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("lastBookNumberSortRead is required");
        }
        KomgaAccess access = accessResolver.current();
        List<BookEntity> books = requireSeries(seriesId, access);
        ProgressSnapshot progress = progressReader.load(access, books);
        for (BookEntity book : byNumber(books)) {
            if (SeriesNumbering.numberSort(book) <= lastBookNumberSortRead
                    && progress.of(book).state() != KomgaReadState.READ) {
                progressGateway.setReadStatus(List.of(book.getId()), ReadStatus.READ);
            }
        }
    }

    private List<BookEntity> requireSeries(String seriesId, KomgaAccess access) {
        return seriesFinder.resolve(seriesId, access, settings.groupUnknown())
                .map(ResolvedSeries::books)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Series not found: " + seriesId));
    }

    private static List<BookEntity> byNumber(List<BookEntity> books) {
        return books.stream().sorted(Comparator.comparingDouble(SeriesNumbering::numberSort)).toList();
    }

    private static List<Long> bookIds(List<BookEntity> books) {
        return books.stream().map(BookEntity::getId).toList();
    }
}
