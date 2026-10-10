package com.carddraft.core;

import java.util.List;

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

/**
 * Reports the database settings a process started in this working directory would actually bind.
 *
 * <p>A separate process, because the question cannot be asked from inside one: whether a
 * {@code .env} beside the application was found is a fact about the directory the process was
 * started in, and a test that shares its own working directory can only ever observe whatever
 * happens to be there. Starting a child process in a directory this test controls is the only way
 * to ask the question as a stranger would ask it — {@code .env} copied next to the checkout, then
 * the service started.
 *
 * <p>The mechanism under test is production's own: {@code ConfigDataEnvironmentPostProcessor} is
 * what reads {@code application.yml} and its {@code spring.config.import} at startup, and
 * {@link Binder} over the resulting environment is what {@code @ConfigurationProperties} binds
 * through. Nothing here is a stand-in for the real thing, which is the only reason a red result
 * from this probe is worth believing.
 */
public final class EnvFileConfigurationProbe {

    private static final List<String> PROBED_KEYS =
            List.of("card.db.url", "card.db.username", "card.db.pool-size", "card.db.connection-timeout");

    private EnvFileConfigurationProbe() {}

    public static void main(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        Binder binder = Binder.get(environment);
        for (String key : PROBED_KEYS) {
            System.out.println(
                    "BOUND " + key + "=" + binder.bind(key, String.class).orElse("<absent>"));
        }

        CardDatabaseSettings settings = binder.bindOrCreate("card.db", Bindable.of(CardDatabaseSettings.class));
        System.out.println("SETTINGS url=" + settings.url());
        System.out.println("SETTINGS username=" + settings.username());
        System.out.println("SETTINGS poolSize=" + settings.poolSize());
        System.out.println("SETTINGS connectionTimeout=" + settings.connectionTimeout());
    }
}
