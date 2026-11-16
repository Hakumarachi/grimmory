package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.dto.MagicShelf;
import org.booklore.service.MagicShelfService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shelves of the current user, exposed as Komga collections.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaShelfGateway {

    private final MagicShelfService magicShelfService;

    /** Magic shelves of the current user, plus the public ones. */
    public List<MagicShelf> magicShelves() {
        return magicShelfService.getUserShelves();
    }
}
