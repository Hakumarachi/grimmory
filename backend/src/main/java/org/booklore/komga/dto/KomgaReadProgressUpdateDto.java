package org.booklore.komga.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of {@code PATCH /v1/books/{id}/read-progress}: a page, or {@code completed: true}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KomgaReadProgressUpdateDto {
    private Integer page;
    private Boolean completed;
}
