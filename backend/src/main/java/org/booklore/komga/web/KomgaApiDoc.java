package org.booklore.komga.web;

/** OpenAPI documentation shared by the Komga controllers (they all belong to one tag). */
final class KomgaApiDoc {

    static final String TAG = "Komga API";
    static final String TAG_DESCRIPTION = "Komga-compatible API endpoints. " +
            "All endpoints support a 'clean' query parameter (default: false). " +
            "When present (?clean or ?clean=true), responses exclude fields ending with 'Lock', null values, and empty arrays, " +
            "resulting in smaller and cleaner JSON payloads.";

    private KomgaApiDoc() {
    }
}
