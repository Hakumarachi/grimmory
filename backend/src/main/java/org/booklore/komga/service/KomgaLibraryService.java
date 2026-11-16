package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.dto.KomgaLibraryDto;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.repository.KomgaLibraryRepository;
import org.booklore.model.entity.LibraryEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Komga libraries: only those the user may see. */
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaLibraryService {

    private final KomgaLibraryRepository komgaLibraryRepository;
    private final KomgaMapper komgaMapper;
    private final KomgaAccessResolver accessResolver;

    public List<KomgaLibraryDto> getAllLibraries() {
        KomgaAccess access = accessResolver.current();
        return komgaLibraryRepository.findAll().stream()
                .filter(library -> access.canSeeLibrary(library.getId()))
                .map(komgaMapper::toKomgaLibraryDto)
                .toList();
    }

    public KomgaLibraryDto getLibraryById(Long libraryId) {
        KomgaAccess access = accessResolver.current();
        LibraryEntity library = komgaLibraryRepository.findById(libraryId)
                .filter(found -> access.canSeeLibrary(found.getId()))
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));
        return komgaMapper.toKomgaLibraryDto(library);
    }
}
