package org.booklore.komga.progress;

import org.booklore.model.enums.ReadStatus;

/** The three read states of Komga, mapped from Grimmory's richer {@link ReadStatus}. */
public enum KomgaReadState {
    UNREAD,
    IN_PROGRESS,
    READ;

    /**
     * {@code READ} is read; reading, re-reading, partially read and paused are in progress.
     * Everything else (unread, unset, no progress, and also won't read / abandoned, which Komga
     * cannot express) is unread.
     */
    public static KomgaReadState of(ReadStatus status) {
        if (status == null) {
            return UNREAD;
        }
        return switch (status) {
            case READ -> READ;
            case READING, RE_READING, PARTIALLY_READ, PAUSED -> IN_PROGRESS;
            case UNREAD, UNSET, WONT_READ, ABANDONED -> UNREAD;
        };
    }
}
