package org.booklore.komga.series;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Komga series IDs: {@code <libraryId>-<slug of the series name>}.
 */
public final class SeriesIdCodec {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    /** A decoded series ID: the library and the slug to match against series names. */
    public record ParsedSeriesId(Long libraryId, String slug) {

        public boolean matches(String seriesName) {
            return SeriesIdCodec.slug(seriesName).equals(slug);
        }
    }

    private SeriesIdCodec() {
    }

    public static String encode(Long libraryId, String seriesName) {
        return libraryId + "-" + slug(seriesName);
    }

    /** Empty when the ID is not {@code <number>-<slug>}. */
    public static Optional<ParsedSeriesId> decode(String seriesId) {
        if (seriesId == null) {
            return Optional.empty();
        }
        String[] parts = seriesId.split("-", 2);
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParsedSeriesId(Long.parseLong(parts[0]), parts[1]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String slug(String seriesName) {
        // Default-locale lowercasing kept on purpose: it is how existing IDs were built
        return NON_ALPHANUMERIC.matcher(seriesName.toLowerCase()).replaceAll("-");
    }
}
