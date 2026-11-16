package org.booklore.komga.progress;

import org.booklore.model.entity.BookEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/** Progress of one user on a set of books, loaded at once. Books without progress are unread. */
public record ProgressSnapshot(Map<Long, BookProgress> byBookId) {

    public static final ProgressSnapshot EMPTY = new ProgressSnapshot(Collections.emptyMap());

    public BookProgress of(BookEntity book) {
        return byBookId.getOrDefault(book.getId(), BookProgress.NONE);
    }

    public ReadCounts counts(Collection<BookEntity> books) {
        int read = 0;
        int inProgress = 0;
        for (BookEntity book : books) {
            switch (of(book).state()) {
                case READ -> read++;
                case IN_PROGRESS -> inProgress++;
                case UNREAD -> { }
            }
        }
        return new ReadCounts(read, books.size() - read - inProgress, inProgress);
    }
}
