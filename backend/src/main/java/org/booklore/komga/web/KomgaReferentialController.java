package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaReferentialController {

    private final KomgaJsonResponses json;

    @Operation(summary = "List genres, tags, publishers or authors",
            description = "Not tracked by Grimmory: always returns an empty list so Komga clients can load their filters.")
    @GetMapping({"/v1/genres", "/v1/tags", "/v1/publishers", "/v1/authors"})
    public ResponseEntity<String> getEmptyReferential() {
        return json.ok(List.of());
    }
}
