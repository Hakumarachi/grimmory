package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.service.progress.ReadingProgressService;
import org.springframework.stereotype.Component;

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
}
