package org.booklore.komga.progress;

import org.booklore.komga.dto.KomgaReadProgressDto;

import java.time.Instant;

/**
 * Komga view of a user's progress on one book.
 *
 * @param state        read state
 * @param page         1-based current page when known (CBX / PDF), else {@code null}
 * @param percent      progress in percent (0-100) when known, used when the page is not
 * @param created      first known progress date
 * @param lastModified last known progress date
 */
public record BookProgress(KomgaReadState state, Integer page, Float percent, Instant created, Instant lastModified) {

    public static final BookProgress NONE = new BookProgress(KomgaReadState.UNREAD, null, null, null, null);

    /**
     * Komga {@code readProgress} of the book, {@code null} when unread. A read book is on its last page;
     * a book in progress is on its known page, else on the page matching its percentage, at least page 1.
     */
    public KomgaReadProgressDto toDto(int pagesCount) {
        if (state == KomgaReadState.UNREAD) {
            return null;
        }
        boolean completed = state == KomgaReadState.READ;
        int lastPage = Math.max(pagesCount, 1);
        int currentPage;
        if (completed) {
            currentPage = lastPage;
        } else if (page != null) {
            currentPage = page;
        } else if (percent != null && pagesCount > 0) {
            currentPage = (int) Math.ceil(percent / 100f * pagesCount);
        } else {
            currentPage = 1;
        }
        currentPage = Math.clamp(currentPage, 1, pagesCount > 0 ? pagesCount : Integer.MAX_VALUE);

        Instant modified = lastModified != null ? lastModified : Instant.EPOCH;
        return KomgaReadProgressDto.builder()
                .page(currentPage)
                .completed(completed)
                .readDate(modified)
                .created(created != null ? created : modified)
                .lastModified(modified)
                .deviceId("")
                .deviceName("")
                .build();
    }
}
