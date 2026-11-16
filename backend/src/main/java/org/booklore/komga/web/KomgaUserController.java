package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.dto.KomgaUserDto;
import org.booklore.komga.service.KomgaUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaUserController {

    private final KomgaUserService userService;
    private final KomgaJsonResponses json;

    @Operation(summary = "Get current user details")
    @GetMapping("/v2/users/me")
    public ResponseEntity<String> getCurrentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        KomgaUserDto user = userService.getCurrentUser(authentication.getName());
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return json.ok(user);
    }
}
