package org.booklore.komga.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mihon tracker view of a series ({@code GET /v2/series/{id}/read-progress/tachiyomi}). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaTachiyomiReadProgressDto {
    private Integer booksCount;
    private Integer booksReadCount;
    private Integer booksUnreadCount;
    private Integer booksInProgressCount;
    /** Number of the last book read without gap from the first one, 0 when the first book is not read. */
    private Float lastReadContinuousNumberSort;
    private Float maxNumberSort;
}
