package org.booklore.komga.progress;

/** Read, unread and in progress book counts of a series, for one user. */
public record ReadCounts(int read, int unread, int inProgress) {
}
