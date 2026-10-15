package dev.faultora.runtime;

import dev.faultora.model.security.ExtensionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOriginTest {
    private static final String REGISTRY = ExtensionRegistry.class.getName();
    private static final ExtensionPolicy EXTENSIONS = new ExtensionPolicy(Set.of(), false, 0, Set.of(), Set.of());
    @TempDir Path directory;

    enum Origin { NON_FILE, ABSENT, UNREADABLE }

    @ParameterizedTest
    @EnumSource(Origin.class)
    void unsupportedOriginsRefuseDiscoveryWithoutPoisoningClassInitialization(Origin origin) throws Exception {
        URL location = switch (origin) {
            case NON_FILE -> URI.create("https://synthetic.invalid/runtime.jar").toURL();
            case ABSENT -> null;
            case UNREADABLE -> directory.resolve("missing-runtime.jar").toUri().toURL();
        };
        Class<?> registry = Class.forName(REGISTRY, true, loader(location));

        for (int attempt = 0; attempt < 2; attempt++) {
            for (String method : Set.of("assertionProviders", "renderers", "importers")) {
                assertThatThrownBy(() -> registry.getMethod(method, ExtensionPolicy.class).invoke(null, EXTENSIONS))
                        .isInstanceOf(InvocationTargetException.class)
                        .cause().isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("Cannot verify the runtime artifact's");
            }
            assertThatThrownBy(() -> registry.getMethod("importerFor", String.class, ExtensionPolicy.class)
                    .invoke(null, "openapi", EXTENSIONS))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theSupportedModularLayoutStillLoadsShippedProviders() throws Exception {
        URL location = ExtensionRegistry.class.getProtectionDomain().getCodeSource().getLocation();
        Class<?> registry = Class.forName(REGISTRY, true, loader(location));
        Object result = registry.getMethod("assertionProviders", ExtensionPolicy.class).invoke(null, EXTENSIONS);
        assertThat((Map<?, ?>) result).isNotEmpty();
    }

    private static ClassLoader loader(URL location) {
        return new SecureClassLoader(RuntimeOriginTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(REGISTRY) && !name.startsWith(REGISTRY + "$")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = input.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length,
                                    location == null ? null : new CodeSource(location, (Certificate[]) null));
                        } catch (IOException unreadable) {
                            throw new ClassNotFoundException(name, unreadable);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
    }
}
