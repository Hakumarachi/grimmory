package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.service.KomgaLibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaLibraryController {

    private final KomgaLibraryService libraryService;
    private final KomgaJsonResponses json;

    @Operation(summary = "List all libraries")
    @GetMapping("/v1/libraries")
    public ResponseEntity<String> getAllLibraries() {
        return json.ok(libraryService.getAllLibraries());
    }

    @Operation(summary = "Get library details")
    @GetMapping("/v1/libraries/{libraryId}")
    public ResponseEntity<String> getLibrary(
            @Parameter(description = "Library ID") @PathVariable Long libraryId) {
        return json.ok(libraryService.getLibraryById(libraryId));
    }
}
