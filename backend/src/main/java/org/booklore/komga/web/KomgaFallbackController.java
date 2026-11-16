package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.booklore.exception.ErrorResponse;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Komga routes Grimmory does not implement (501) and unknown Komga routes (404). */
@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
public class KomgaFallbackController {

    @Operation(summary = "Unimplemented Komga API Endpoints")
    @ApiResponse(responseCode = "501", description = "Not Implemented")
    @RequestMapping(
            value = {
                    "/v1/readlists",
                    "/v1/series/new",
                    "/v1/series/updated",
                    "/v1/series/genres",
                    "/v1/books/ondeck",
                    "/v1/books/list",
                    "/v1/books/*/readlists",
                    "/v1/books/*/manifest",
            },
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH}
    )
    public ResponseEntity<ErrorResponse> notImplemented() {
        return ResponseEntity
                .status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ErrorResponse(HttpStatus.NOT_IMPLEMENTED.value(), "Not Implemented"));
    }

    @Operation(summary = "Catch-all for Komga API", description = "Catch-all endpoint for unhandled Komga API requests.")
    @ApiResponse(responseCode = "404", description = "Not Found")
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<?> catchAll() {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found"));
    }
}
