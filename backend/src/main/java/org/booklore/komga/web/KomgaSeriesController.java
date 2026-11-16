package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.dto.KomgaSeriesSearchRequestDto;
import org.booklore.komga.service.KomgaSeriesService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaSeriesController {

    private final KomgaSeriesService seriesService;
    private final KomgaJsonResponses json;

    @Operation(summary = "List series")
    @GetMapping("/v1/series")
    public ResponseEntity<String> getAllSeries(
            @Parameter(description = "Library ID filter") @RequestParam(required = false, name = "library_id") Long libraryId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Return all books without paging") @RequestParam(defaultValue = "false") boolean unpaged) {
        return json.ok(seriesService.getAllSeries(libraryId, page, size, unpaged));
    }

    @Operation(summary = "Search series",
            description = "Komga 1.19+ search API, used by Komga clients such as Mihon to browse and search. " +
                    "Supports libraryId and readStatus conditions, fullTextSearch on the series name and sorting by " +
                    "metadata.titleSort, relevance, createdDate, lastModifiedDate or random. Other conditions are ignored.")
    @PostMapping("/v1/series/list")
    public ResponseEntity<String> searchSeries(
            @RequestBody(required = false) KomgaSeriesSearchRequestDto request,
            @Parameter(description = "Sort, e.g. metadata.titleSort,asc") @RequestParam(required = false) String sort,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Return all series without paging") @RequestParam(defaultValue = "false") boolean unpaged) {
        return json.ok(seriesService.searchSeries(request, sort, page, size, unpaged));
    }

    @Operation(summary = "Get series details")
    @GetMapping("/v1/series/{seriesId}")
    public ResponseEntity<String> getSeries(
            @Parameter(description = "Series ID") @PathVariable String seriesId) {
        return json.ok(seriesService.getSeriesById(seriesId));
    }

    @Operation(summary = "List books in series")
    @GetMapping("/v1/series/{seriesId}/books")
    public ResponseEntity<String> getSeriesBooks(
            @Parameter(description = "Series ID") @PathVariable String seriesId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Return all books without paging") @RequestParam(defaultValue = "false") boolean unpaged) {
        return json.ok(seriesService.getBooksBySeries(seriesId, page, size, unpaged));
    }

    @Operation(summary = "Get series thumbnail")
    @GetMapping("/v1/series/{seriesId}/thumbnail")
    public ResponseEntity<Resource> getSeriesThumbnail(
            @Parameter(description = "Series ID") @PathVariable String seriesId) {
        Resource coverImage = seriesService.getSeriesThumbnail(seriesId);
        if (coverImage == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(coverImage);
    }
}
