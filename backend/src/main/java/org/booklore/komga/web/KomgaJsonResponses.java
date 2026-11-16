package org.booklore.komga.web;

import lombok.extern.slf4j.Slf4j;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.web.json.KomgaJsonConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes Komga JSON responses with the dedicated "clean" mapper. */
@Slf4j
@ConditionalOnKomgaEnabled
@Component
class KomgaJsonResponses {

    private final ObjectMapper komgaCleanObjectMapper;

    KomgaJsonResponses(@Qualifier(KomgaJsonConfig.KOMGA_CLEAN_OBJECT_MAPPER) ObjectMapper komgaCleanObjectMapper) {
        this.komgaCleanObjectMapper = komgaCleanObjectMapper;
    }

    ResponseEntity<String> ok(Object body) {
        try {
            String json = komgaCleanObjectMapper.writeValueAsString(body);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        } catch (Exception e) {
            log.error("Failed to serialize Komga response", e);
            return ResponseEntity.status(500).build();
        }
    }
}
