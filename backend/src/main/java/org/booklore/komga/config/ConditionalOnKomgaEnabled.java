package org.booklore.komga.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Loads a Komga API bean only when the module is enabled ({@code app.komga.enabled}, default {@code true};
 * environment variable {@code APP_KOMGA_ENABLED}). When disabled, no Komga controller, security chain or
 * interceptor is registered at all, unlike the runtime setting {@code komgaApiEnabled} which only answers 403.
 * <p>
 * Every Spring bean of the {@code org.booklore.komga} package must carry this annotation
 * (checked by {@code KomgaModuleConditionalTest}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(name = KomgaModule.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnKomgaEnabled {
}
