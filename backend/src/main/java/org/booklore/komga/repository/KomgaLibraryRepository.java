package org.booklore.komga.repository;

import org.booklore.model.entity.LibraryEntity;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the core libraries for the Komga API.
 */
public interface KomgaLibraryRepository extends Repository<LibraryEntity, Long> {

    List<LibraryEntity> findAll();

    Optional<LibraryEntity> findById(Long id);
}
