package com.carddraft.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml} has to declare every setting the application binds.
 *
 * <p>Not tidiness. A settings record that nothing declares is not a default — it is a setting that
 * no operator can reach, and it fails silently in the worst possible direction: the application
 * starts, connects to the default, and reports a plausible failure about something else entirely.
 * That is exactly how {@code card.db.*} went undeclared while {@code application.yml} carried a
 * confident, correct-looking {@code spring.db.*} that nothing ever bound: the database URL came
 * from {@code @DefaultValue}, {@code .env} was ignored, and every {@code @SpringBootTest} passed
 * because they set the properties themselves.
 *
 * <p>Four rules, and the third is what keeps the first two honest:
 *
 * <ol>
 *   <li>every bindable property is declared — the missing case;
 *   <li>every variable documented in {@code .env.example} is read — the documented-but-dead case;
 *   <li>every {@code card.*} key declared is bindable — the dead-key case, which is what a
 *       misfiled key looks like;
 *   <li>every framework key declared is one Spring or its dependencies actually expose — the
 *       misspelled-prefix case, where {@code spring.retry.max-attempts} reads as a setting and
 *       binds nothing because the real key is {@code spring.ai.retry.max-attempts}.
 * </ol>
 *
 * <p>The properties are read through {@code YamlPropertiesFactoryBean} rather than through the
 * application's own config-data processing. Config data would also import the developer's
 * {@code .env}, which is exactly the input whose contents this test must not depend on.
 */
class DeclaredConfigurationTest {

    /**
     * Where this application's own classes sit on the classpath.
     *
     * <p>Matched as a path fragment rather than read off a {@code ClassPathResource}, because a
     * {@code classpath*:} scan resolves against a discovered root and hands back a plain file
     * resource for the directory entries this project compiles to.
     */
    private static final String PACKAGE_PATH = "com/carddraft/";

    /**
     * Keys that exist for Docker Compose rather than for this application.
     *
     * <p>Compose reads {@code .env} natively; the application never sees these names. They belong
     * in the file because one file configures both readers, which is the point of it.
     */
    private static final Set<String> COMPOSE_ONLY = Set.of("PG_PORT", "TEMPORAL_PORT", "TEMPORAL_UI_PORT");

    /** {@code @ConditionalOnProperty} names, which are settings but not record components. */
    private static final Set<String> GATED_ON_PROPERTY =
            Set.of("card.metrics.enabled", "card.embedding.backfill-on-start", "card.temporal.worker.enabled");

    /** Prefixes owned by Spring or its dependencies rather than by this application. */
    private static final Set<String> FRAMEWORK_PREFIXES = Set.of("spring.", "server.", "management.", "logging.");

    private final Set<String> declared = declaredKeys();
    private final Set<String> bindable = bindableKeys();
    private final Set<String> frameworkProperties = frameworkPropertyNames();

    @Test
    void everyBindableSettingIsDeclared() {
        assertThat(missing(bindable, declared))
                .as("settings that application.yml does not declare, and so no operator can reach")
                .isEmpty();
    }

    @Test
    void everyVariableDocumentedForOperatorsIsRead() {
        Set<String> unread = new LinkedHashSet<>();
        for (String variable : operatorVariables()) {
            if (COMPOSE_ONLY.contains(variable)) {
                continue;
            }
            if (!applicationYaml().contains("${" + variable + ":")) {
                unread.add(variable);
            }
        }

        assertThat(unread)
                .as("variables .env.example documents that nothing reads — setting one has no effect")
                .isEmpty();
    }

    @Test
    void noDeclaredSettingIsDead() {
        Set<String> dead = new TreeSet<>();
        for (String key : declared) {
            if (key.startsWith("card.") && !bindable.contains(key)) {
                dead.add(key);
            }
        }

        assertThat(dead)
                .as("keys under card.* that nothing binds — a misfiled key reads as a setting and is not one")
                .isEmpty();
    }

    @Test
    void everyFrameworkSettingIsExposedBySpring() {
        Set<String> unknown = new TreeSet<>();
        for (String key : declared) {
            if (FRAMEWORK_PREFIXES.stream().noneMatch(key::startsWith)) {
                continue;
            }
            if (!isExposedByMetadata(key)) {
                unknown.add(key);
            }
        }

        assertThat(unknown)
                .as("keys under a framework prefix that neither Spring nor its dependencies expose "
                        + "— a misspelled prefix reads as a setting and binds nothing")
                .isEmpty();
    }

    private Set<String> missing(Set<String> wanted, Set<String> present) {
        Set<String> absent = new TreeSet<>(wanted);
        absent.removeAll(present);
        return absent;
    }

    /**
     * Every property a {@code @ConfigurationProperties} type binds, found by scanning the compiled
     * classes rather than from a list here — a list would itself drift, and a new settings record
     * that nobody added to it would pass this test having declared nothing.
     */
    private Set<String> bindableKeys() {
        Set<String> keys = new TreeSet<>(GATED_ON_PROPERTY);
        for (Class<?> type : applicationClasses()) {
            ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
            if (annotation == null) {
                continue;
            }
            for (var component : type.getRecordComponents()) {
                keys.add(annotation.value() + "." + kebab(component.getName()));
            }
        }
        return keys;
    }

    private List<Class<?>> applicationClasses() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Class<?>> types = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/carddraft/**/*.class")) {
                String location = resource.getURI().toString();
                String className = location.substring(location.lastIndexOf(PACKAGE_PATH))
                        .replace('/', '.')
                        .replaceAll("\\.class$", "");
                try {
                    types.add(Class.forName(className, false, resolver.getClassLoader()));
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    unreadable.add(className);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not scan the application's compiled classes", e);
        }

        assertThat(unreadable)
                .as("classes on the classpath that could not be loaded — the scan below would miss them")
                .isEmpty();
        assertThat(types).as("the class scan found the application's classes").isNotEmpty();
        return types;
    }

    private Set<String> declaredKeys() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml parsed").isNotNull();

        Set<String> keys = new TreeSet<>();
        for (String name : properties.stringPropertyNames()) {
            keys.add(name.replaceAll("\\[\\d+\\]", ""));
        }
        return keys;
    }

    private Set<String> operatorVariables() {
        Set<String> variables = new LinkedHashSet<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(".env.example"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not read .env.example from " + Path.of(".").toAbsolutePath(), e);
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            variables.add(trimmed.substring(0, trimmed.indexOf('=')));
        }
        assertThat(variables).as(".env.example parsed").isNotEmpty();
        return variables;
    }

    private String applicationYaml() {
        try (InputStream yaml = new ClassPathResource("application.yml").getInputStream()) {
            return new String(yaml.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read application.yml", e);
        }
    }

    /**
     * Whether the metadata names the key itself or an ancestor, because a map property such as
     * {@code logging.level} exposes {@code logging.level.root} without listing every key.
     */
    private boolean isExposedByMetadata(String key) {
        String candidate = key;
        while (true) {
            if (frameworkProperties.contains(candidate)) {
                return true;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return false;
            }
            candidate = candidate.substring(0, dot);
        }
    }

    /**
     * Every property named by Spring's configuration metadata on the classpath.
     *
     * <p>Read from the metadata rather than from a list here: a list would drift from the
     * dependencies it describes, and the whole point is to catch a key a dependency does not have.
     * Only entries under {@code properties} count — a {@code groups} name is a prefix, not a key
     * anything binds.
     */
    private Set<String> frameworkPropertyNames() {
        Set<String> names = new TreeSet<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource resource : resolver.getResources("classpath*:META-INF/*spring-configuration-metadata.json")) {
                JsonNode metadata;
                try (InputStream in = resource.getInputStream()) {
                    metadata = new ObjectMapper().readTree(in);
                }
                for (JsonNode property : metadata.path("properties")) {
                    String name = property.path("name").asString("");
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read Spring's configuration metadata", e);
        }

        assertThat(names)
                .as("Spring's configuration metadata was found on the classpath")
                .isNotEmpty();
        return names;
    }

    private static String kebab(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
