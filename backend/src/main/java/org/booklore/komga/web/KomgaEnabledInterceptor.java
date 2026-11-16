package org.booklore.komga.web;

import org.booklore.komga.config.ConditionalOnKomgaEnabled;

import org.booklore.komga.core.KomgaSettingsGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaEnabledInterceptor implements HandlerInterceptor {

    private final KomgaSettingsGateway settings;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        
        if (uri.startsWith("/komga/api/")) {
            boolean komgaEnabled = settings.apiEnabled();
            if (!komgaEnabled) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Komga API is disabled.");
                return false;
            }
        }
        return true;
    }
}