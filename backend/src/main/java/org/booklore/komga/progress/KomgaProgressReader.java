package org.booklore.komga.progress;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaProgressGateway;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads the progress of the current user on a set of books (two queries, whatever the number of books)
 * and converts it to the Komga model.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaProgressReader {

    private final KomgaProgressGateway progressGateway;

    public ProgressSnapshot load(KomgaAccess access, Collection<BookEntity> books) {
        if (books.isEmpty()) {
            return ProgressSnapshot.EMPTY;
        }
        Set<Long> bookIds = books.stream().map(BookEntity::getId).collect(Collectors.toSet());
        Map<Long, UserBookProgressEntity> bookProgress = progressGateway.bookProgress(access.userId(), bookIds);
        if (bookProgress.isEmpty()) {
            return ProgressSnapshot.EMPTY;
        }
        Map<Long, UserBookFileProgressEntity> fileProgress = progressGateway.fileProgress(access.userId(), bookProgress.keySet());

        Map<Long, BookProgress> byBookId = new HashMap<>();
        for (BookEntity book : books) {
            UserBookProgressEntity progress = bookProgress.get(book.getId());
            if (progress != null) {
                byBookId.put(book.getId(), toBookProgress(book, progress, fileProgress.get(book.getId())));
            }
        }
        return new ProgressSnapshot(byBookId);
    }

    static BookProgress toBookProgress(BookEntity book, UserBookProgressEntity progress, UserBookFileProgressEntity fileProgress) {
        KomgaReadState state = KomgaReadState.of(progress.getReadStatus());
        BookFileType type = Optional.ofNullable(book.getPrimaryBookFile()).map(BookFileEntity::getBookType).orElse(null);

        Integer page = null;
        Float percent = null;
        if (fileProgress != null) {
            if (type == BookFileType.CBX || type == BookFileType.PDF) {
                page = parsePage(fileProgress.getPositionData());
            }
            percent = fileProgress.getProgressPercent();
        }
        if (page == null) {
            page = legacyPage(progress, type);
        }
        if (percent == null) {
            percent = legacyPercent(progress, type);
        }

        List<Instant> dates = Stream.of(
                        progress.getLastReadTime(),
                        progress.getReadStatusModifiedTime(),
                        progress.getDateFinished(),
                        fileProgress != null ? fileProgress.getLastReadTime() : null)
                .filter(Objects::nonNull)
                .toList();
        Instant created = dates.stream().min(Comparator.naturalOrder()).orElse(null);
        Instant lastModified = dates.stream().max(Comparator.naturalOrder()).orElse(null);
        return new BookProgress(state, page, percent, created, lastModified);
    }

    @SuppressWarnings("deprecation")
    private static Integer legacyPage(UserBookProgressEntity progress, BookFileType type) {
        if (type == BookFileType.CBX) {
            return progress.getCbxProgress();
        }
        if (type == BookFileType.PDF) {
            return progress.getPdfProgress();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Float legacyPercent(UserBookProgressEntity progress, BookFileType type) {
        Float percent = null;
        if (type != null) {
            percent = switch (type) {
                case CBX -> progress.getCbxProgressPercent();
                case PDF -> progress.getPdfProgressPercent();
                case EPUB, FB2, MOBI, AZW3 -> progress.getEpubProgressPercent();
                case AUDIOBOOK -> null;
            };
        }
        if (percent == null && progress.getKoreaderProgressPercent() != null) {
            percent = progress.getKoreaderProgressPercent() * 100f;
        }
        if (percent == null) {
            percent = progress.getKoboProgressPercent();
        }
        return percent;
    }

    private static Integer parsePage(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
