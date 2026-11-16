package org.booklore.komga.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code PUT /v2/series/{id}/read-progress/tachiyomi}: every book up to this number is read. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaTachiyomiReadProgressUpdateDto {
    private Float lastBookNumberSortRead;
}
