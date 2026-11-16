package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaShelfGateway;
import org.booklore.komga.dto.KomgaCollectionDto;
import org.booklore.komga.dto.KomgaPageableDto;
import org.booklore.komga.mapper.KomgaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Komga collections, built from the user's magic shelves. */
@Slf4j
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaCollectionService {

    private final KomgaShelfGateway shelfGateway;
    private final KomgaMapper komgaMapper;

    public KomgaPageableDto<KomgaCollectionDto> getCollections(int page, int size, boolean unpaged) {
        log.debug("Getting collections, page: {}, size: {}, unpaged: {}", page, size, unpaged);

        // Series count is 0 for now: magic shelves are not resolved to series yet
        List<KomgaCollectionDto> collections = shelfGateway.magicShelves().stream()
                .map(shelf -> komgaMapper.toKomgaCollectionDto(shelf, 0))
                .sorted(Comparator.comparing(KomgaCollectionDto::getName))
                .toList();

        return KomgaPaging.page(collections, page, size, unpaged, pageItems -> pageItems);
    }
}
