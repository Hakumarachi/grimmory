package org.booklore.komga.service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parsing of the Komga {@code series/list} request: library conditions and full text search. */
@Slf4j
final class KomgaSeriesSearch {

    private static final Pattern LUCENE_ESCAPE_PATTERN = Pattern.compile("\\\\(.)");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");

    private KomgaSeriesSearch() {
    }

    /** Collects the values of every {@code libraryId} leaf with operator {@code is}, at any depth. */
    static Set<Long> collectLibraryIds(JsonNode condition) {
        Set<Long> ids = new LinkedHashSet<>();
        collectLibraryIds(condition, ids);
        return ids;
    }

    private static void collectLibraryIds(JsonNode node, Set<Long> ids) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectLibraryIds(child, ids));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode libraryId = node.get("libraryId");
        if (libraryId != null && libraryId.isObject() && "is".equals(libraryId.path("operator").asString(""))) {
            try {
                ids.add(Long.parseLong(libraryId.path("value").asString("").trim()));
            } catch (NumberFormatException e) {
                log.debug("Ignoring non-numeric Komga libraryId condition: {}", libraryId);
            }
        }
        collectLibraryIds(node.get("allOf"), ids);
        collectLibraryIds(node.get("anyOf"), ids);
    }

    /** Splits a Komga full text search (Lucene-escaped) into normalized terms. */
    static List<String> searchTerms(String fullTextSearch) {
        if (fullTextSearch == null || fullTextSearch.isBlank()) {
            return List.of();
        }
        String unescaped = LUCENE_ESCAPE_PATTERN.matcher(fullTextSearch).replaceAll("$1");
        return Arrays.stream(normalizeForSearch(unescaped).split("\\s+"))
                .filter(term -> !term.isBlank())
                .toList();
    }

    /** True when the name contains every term (case and accent insensitive). */
    static boolean matchesAll(String name, List<String> terms) {
        String normalized = normalizeForSearch(name);
        return terms.stream().allMatch(normalized::contains);
    }

    private static String normalizeForSearch(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return DIACRITICS_PATTERN.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
