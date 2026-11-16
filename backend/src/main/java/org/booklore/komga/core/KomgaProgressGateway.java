package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.dto.request.BookFileProgress;
import org.booklore.model.dto.request.ReadProgressRequest;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.service.book.BookService;
import org.booklore.service.progress.ReadingProgressService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading progress of a Grimmory user, through the core progress service (the one used by the web
 * reader, Kobo and KOReader), so the Komga API sees exactly the same state.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaProgressGateway {

    private final ReadingProgressService readingProgressService;
    private final BookService bookService;

    /** Book-level progress (read status, dates, legacy positions), by book id. */
    public Map<Long, UserBookProgressEntity> bookProgress(Long userId, Set<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        return readingProgressService.fetchUserProgress(userId, bookIds);
    }

    /** Latest file-level progress (page or position, percentage), by book id. */
    public Map<Long, UserBookFileProgressEntity> fileProgress(Long userId, Set<Long> bookIds) {
        return readingProgressService.fetchUserFileProgress(userId, bookIds);
    }

    /**
     * Records the current user's position in a book, like the web reader does: the file progress is saved,
     * the read status follows the percentage (read at the end), and KOReader / Hardcover syncs are notified.
     */
    public void savePosition(Long bookId, Long bookFileId, int page, float percent) {
        ReadProgressRequest request = new ReadProgressRequest();
        request.setBookId(bookId);
        request.setFileProgress(new BookFileProgress(bookFileId, String.valueOf(page), null, percent, null, null));
        readingProgressService.updateReadProgress(request);
    }

    /**
     * Sets the current user's read status of books, like "mark as read / unread" in the web interface.
     * Several books at once require the {@code CAN_BULK_RESET_BOOK_READ_STATUS} permission (403 otherwise).
     */
    public void setReadStatus(List<Long> bookIds, ReadStatus status) {
        bookService.updateReadStatus(bookIds, status.name());
    }
}
