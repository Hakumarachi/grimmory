package org.booklore.komga.service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import org.booklore.komga.progress.KomgaReadState;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Parsing of the Komga {@code series/list} request: library and read status conditions, full text search. */
@Slf4j
final class KomgaSeriesSearch {

    private static final Pattern LUCENE_ESCAPE_PATTERN = Pattern.compile("\\\\(.)");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}+");

    private KomgaSeriesSearch() {
    }

    /** Collects the values of every {@code libraryId} leaf with operator {@code is}, at any depth. */
    static Set<Long> collectLibraryIds(JsonNode condition) {
        Set<Long> ids = new LinkedHashSet<>();
        collectIs(condition, "libraryId", value -> {
            try {
                ids.add(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                log.debug("Ignoring non-numeric Komga libraryId condition: {}", value);
            }
        });
        return ids;
    }

    /** Collects the values of every {@code readStatus} leaf with operator {@code is}, at any depth. */
    static Set<KomgaReadState> collectReadStates(JsonNode condition) {
        Set<KomgaReadState> states = EnumSet.noneOf(KomgaReadState.class);
        collectIs(condition, "readStatus", value -> {
            try {
                states.add(KomgaReadState.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.debug("Ignoring unknown Komga readStatus condition: {}", value);
            }
        });
        return states;
    }

    /** Calls {@code consumer} with the value of every {@code field} leaf with operator {@code is}, at any depth. */
    private static void collectIs(JsonNode node, String field, Consumer<String> consumer) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectIs(child, field, consumer));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode leaf = node.get(field);
        if (leaf != null && leaf.isObject() && "is".equals(leaf.path("operator").asString(""))) {
            consumer.accept(leaf.path("value").asString(""));
        }
        collectIs(node.get("allOf"), field, consumer);
        collectIs(node.get("anyOf"), field, consumer);
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
