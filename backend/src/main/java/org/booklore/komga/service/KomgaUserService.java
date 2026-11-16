package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaUserGateway;
import org.booklore.komga.dto.KomgaUserDto;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.model.entity.OpdsUserV2Entity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Komga user behind an OPDS account. */
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaUserService {

    private final KomgaUserGateway userGateway;
    private final KomgaMapper komgaMapper;

    /** Komga user of an OPDS account, or {@code null} when the account does not exist. */
    public KomgaUserDto getCurrentUser(String username) {
        OpdsUserV2Entity opdsUser = userGateway.opdsUser(username);
        return opdsUser != null ? komgaMapper.toKomgaUserDto(opdsUser) : null;
    }
}
