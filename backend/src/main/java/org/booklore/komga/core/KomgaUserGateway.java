package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.config.security.service.OpdsUserDetailsService;
import org.booklore.model.entity.OpdsUserV2Entity;
import org.booklore.service.opds.OpdsUserV2Service;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * OPDS accounts, which are also the Komga API accounts.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaUserGateway {

    private final OpdsUserV2Service opdsUserV2Service;
    private final OpdsUserDetailsService opdsUserDetailsService;

    /** OPDS account by login, or {@code null}. */
    public OpdsUserV2Entity opdsUser(String username) {
        return opdsUserV2Service.findByUsername(username);
    }

    /**
     * Security principal of an OPDS account, exactly as Basic authentication builds it, or {@code null}
     * when the account no longer exists.
     */
    public UserDetails userDetails(String username) {
        try {
            return opdsUserDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            return null;
        }
    }
}
