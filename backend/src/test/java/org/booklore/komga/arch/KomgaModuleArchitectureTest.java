package org.booklore.komga.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Komga module removable and its dependencies on the core explicit, by reading the sources:
 * <ul>
 *   <li>no core class imports the Komga module;</li>
 *   <li>inside the module, only {@code komga.core} (the gateways) imports core services,
 *       repositories, security, configuration, mappers or controllers. The rest of the module
 *       may use core entities, DTOs, enums and exceptions.</li>
 * </ul>
 */
class KomgaModuleArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/org/booklore");
    private static final Path MODULE = MAIN.resolve("komga");
    private static final Path GATEWAYS = MODULE.resolve("core");

    private static final Pattern MODULE_IMPORT = Pattern.compile("^import\\s+(static\\s+)?org\\.booklore\\.komga\\.", Pattern.MULTILINE);
    private static final Pattern CORE_INTERNALS_IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?org\\.booklore\\.(service|repository|security|config|mapper|controller|app)\\.",
            Pattern.MULTILINE);

    @Test
    void coreDoesNotDependOnTheKomgaModule() throws IOException {
        assertThat(violations(javaFiles(MAIN).filter(file -> !file.startsWith(MODULE)), MODULE_IMPORT))
                .as("core classes importing org.booklore.komga")
                .isEmpty();
    }

    @Test
    void onlyGatewaysUseCoreInternals() throws IOException {
        assertThat(violations(javaFiles(MODULE).filter(file -> !file.startsWith(GATEWAYS)), CORE_INTERNALS_IMPORT))
                .as("Komga classes outside komga.core importing core services, repositories, security or config")
                .isEmpty();
    }

    @Test
    void sourcesAreFound() throws IOException {
        assertThat(javaFiles(GATEWAYS)).isNotEmpty();
    }

    private static Stream<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList().stream();
        }
    }

    private static List<String> violations(Stream<Path> files, Pattern forbidden) {
        List<String> violations = new ArrayList<>();
        files.forEach(file -> {
            try {
                var matcher = forbidden.matcher(Files.readString(file));
                while (matcher.find()) {
                    violations.add(MAIN.relativize(file) + ": " + matcher.group().trim() + "…");
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return violations;
    }
}
