package org.booklore.komga.config;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.web.KomgaEnabledInterceptor;
import org.booklore.komga.web.json.KomgaCleanInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the Komga API interceptors. Kept apart from the core {@code WebMvcConfig}
 * so the Komga module stays self-contained.
 */
@Configuration
@RequiredArgsConstructor
public class KomgaWebConfig implements WebMvcConfigurer {

    private final KomgaEnabledInterceptor komgaEnabledInterceptor;
    private final KomgaCleanInterceptor komgaCleanInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Same order as before: the API switch is checked first, then the clean mode is set up
        registry.addInterceptor(komgaEnabledInterceptor)
                .addPathPatterns("/komga/api/**");
        registry.addInterceptor(komgaCleanInterceptor)
                .addPathPatterns("/komga/api/**");
    }
}
