package org.booklore.komga.config;

/**
 * Constants of the Komga API module.
 */
public final class KomgaModule {

    /** Loads the whole Komga module when {@code true} (default). Environment variable: {@code APP_KOMGA_ENABLED}. */
    public static final String ENABLED_PROPERTY = "app.komga.enabled";

    /** Base package of the module: everything Komga lives under it. */
    public static final String BASE_PACKAGE = "org.booklore.komga";

    private KomgaModule() {
    }
}
