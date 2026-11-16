package org.booklore.model.dto.komga;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * Body of Komga's {@code POST /api/v1/series/list} search request.
 * <p>
 * {@code condition} is a tree of {@code allOf} / {@code anyOf} nodes whose leaves are
 * {@code {"<field>": {"operator": "...", "value": ...}}}. Only {@code libraryId} leaves are
 * honoured; other conditions are ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KomgaSeriesSearchRequestDto {
    private JsonNode condition;
    private String fullTextSearch;
}
