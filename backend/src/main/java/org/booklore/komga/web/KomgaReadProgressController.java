package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.dto.KomgaReadProgressUpdateDto;
import org.booklore.komga.dto.KomgaTachiyomiReadProgressUpdateDto;
import org.booklore.komga.service.KomgaReadProgressService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaReadProgressController {

    private final KomgaReadProgressService readProgressService;
    private final KomgaJsonResponses json;

    @Operation(summary = "Mark book's read progress",
            description = "Mark the book as read ({completed: true}) or set the current page ({page}). " +
                    "Reaching the last page marks the book read. Pages are only supported for comics and PDFs.")
    @PatchMapping("/v1/books/{bookId}/read-progress")
    public ResponseEntity<Void> markBookReadProgress(
            @Parameter(description = "Book ID") @PathVariable Long bookId,
            @RequestBody(required = false) KomgaReadProgressUpdateDto readProgress) {
        readProgressService.updateBookProgress(bookId, readProgress);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark book as unread")
    @DeleteMapping("/v1/books/{bookId}/read-progress")
    public ResponseEntity<Void> deleteBookReadProgress(
            @Parameter(description = "Book ID") @PathVariable Long bookId) {
        readProgressService.deleteBookProgress(bookId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark series as read",
            description = "Marks every book of the series read. Several books require the bulk read status permission.")
    @PostMapping("/v1/series/{seriesId}/read-progress")
    public ResponseEntity<Void> markSeriesAsRead(
            @Parameter(description = "Series ID") @PathVariable String seriesId) {
        readProgressService.markSeriesRead(seriesId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark series as unread",
            description = "Marks every book of the series unread. Several books require the bulk read status permission.")
    @DeleteMapping("/v1/series/{seriesId}/read-progress")
    public ResponseEntity<Void> markSeriesAsUnread(
            @Parameter(description = "Series ID") @PathVariable String seriesId) {
        readProgressService.markSeriesUnread(seriesId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get series read progress (Mihon)", description = "Mihon tracker state of the series.")
    @GetMapping("/v2/series/{seriesId}/read-progress/tachiyomi")
    public ResponseEntity<String> getMihonReadProgress(
            @Parameter(description = "Series ID") @PathVariable String seriesId) {
        return json.ok(readProgressService.getTachiyomiProgress(seriesId));
    }

    @Operation(summary = "Update series read progress (Mihon)",
            description = "Marks read every book numbered up to lastBookNumberSortRead.")
    @PutMapping("/v2/series/{seriesId}/read-progress/tachiyomi")
    public ResponseEntity<Void> updateMihonReadProgress(
            @Parameter(description = "Series ID") @PathVariable String seriesId,
            @RequestBody KomgaTachiyomiReadProgressUpdateDto readProgress) {
        readProgressService.updateTachiyomiProgress(seriesId, readProgress.getLastBookNumberSortRead());
        return ResponseEntity.noContent().build();
    }
}
