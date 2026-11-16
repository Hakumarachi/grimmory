package org.booklore.komga.progress;

/** Read, unread and in progress book counts of a series, for one user. */
public record ReadCounts(int read, int unread, int inProgress) {

    /** Komga read status of the series: read when every book is read, unread when none is started. */
    public KomgaReadState seriesState() {
        if (inProgress == 0 && unread == 0 && read > 0) {
            return KomgaReadState.READ;
        }
        if (read == 0 && inProgress == 0) {
            return KomgaReadState.UNREAD;
        }
        return KomgaReadState.IN_PROGRESS;
    }
}
