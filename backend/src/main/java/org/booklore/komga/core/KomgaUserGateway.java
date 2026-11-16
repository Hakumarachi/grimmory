package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.entity.OpdsUserV2Entity;
import org.booklore.service.opds.OpdsUserV2Service;
import org.springframework.stereotype.Component;

/**
 * OPDS accounts, which are also the Komga API accounts.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaUserGateway {

    private final OpdsUserV2Service opdsUserV2Service;

    /** OPDS account by login, or {@code null}. */
    public OpdsUserV2Entity opdsUser(String username) {
        return opdsUserV2Service.findByUsername(username);
    }
}
