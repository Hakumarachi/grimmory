package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Component;

/**
 * Komga settings stored in the core application settings.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaSettingsGateway {

    private final AppSettingService appSettingService;

    /** Runtime switch of the Komga API (admin setting); {@code false} answers 403. */
    public boolean apiEnabled() {
        return appSettingService.getAppSettings().isKomgaApiEnabled();
    }

    /** Whether books without series are grouped into one "Unknown Series". */
    public boolean groupUnknown() {
        return appSettingService.getAppSettings().isKomgaGroupUnknown();
    }
}
