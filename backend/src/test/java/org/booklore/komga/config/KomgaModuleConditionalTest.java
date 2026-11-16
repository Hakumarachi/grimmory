package org.booklore.komga.config;

import org.booklore.komga.web.json.KomgaJsonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Komga module can be switched off entirely with {@code app.komga.enabled=false}.
 */
class KomgaModuleConditionalTest {

    @Test
    void everyKomgaBeanIsConditionalOnTheModuleSwitch() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        // Test-only configurations of the module are on the test classpath too
        scanner.addExcludeFilter(new AnnotationTypeFilter(TestConfiguration.class));
        Set<BeanDefinition> beans = scanner.findCandidateComponents(KomgaModule.BASE_PACKAGE);

        assertThat(beans).as("Komga beans found by component scan").isNotEmpty();
        for (BeanDefinition bean : beans) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            assertThat(AnnotatedElementUtils.hasAnnotation(type, ConditionalOnKomgaEnabled.class))
                    .as("%s must be annotated with @ConditionalOnKomgaEnabled", type.getName())
                    .isTrue();
        }
    }

    @Test
    void moduleIsLoadedByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(KomgaJsonConfig.class)
                .run(context -> assertThat(context).hasBean(KomgaJsonConfig.KOMGA_CLEAN_OBJECT_MAPPER));
    }

    @Test
    void moduleIsNotLoadedWhenDisabled() {
        for (String value : List.of("false", "FALSE")) {
            new ApplicationContextRunner()
                    .withUserConfiguration(KomgaJsonConfig.class)
                    .withPropertyValues(KomgaModule.ENABLED_PROPERTY + "=" + value)
                    .run(context -> assertThat(context).doesNotHaveBean(KomgaJsonConfig.KOMGA_CLEAN_OBJECT_MAPPER));
        }
    }
}
