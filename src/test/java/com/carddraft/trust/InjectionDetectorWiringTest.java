package com.carddraft.trust;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The injection detector the application builds is the one the settings describe.
 *
 * <p>Found by reading the wiring rather than the code: the detector carried both a no-arg
 * constructor and a {@link TrustSettings} one and no {@code @Autowired}, so Spring satisfied the
 * bean with the no-arg constructor and the configured {@code card.trust.max-opaque-fragment-length}
 * never reached the compiled pattern. A setting that reads as configured and does nothing is worse
 * than a missing one, because nothing reports it.
 *
 * <p>Asserted through a real context rather than by calling the parameterised constructor directly.
 * That constructor always worked, and constructing the detector here would have made the defect
 * invisible: what failed was Spring's choice between two constructors.
 */
class InjectionDetectorWiringTest {

    /**
     * Binds {@code card.trust.*} exactly as the application's own configuration does, and registers
     * the detector the way component scanning does.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TrustSettings.class)
    static class TrustSettingsBinding {}

    private ApplicationContextRunner contextWithOpaqueLength(String length) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TrustSettingsBinding.class, InjectionDetector.class)
                .withPropertyValues("card.trust.max-opaque-fragment-length=" + length);
    }

    @Test
    void theBoundOpaqueLengthReachesTheDetectorTheApplicationBuilds() {
        contextWithOpaqueLength("5").run(context -> {
            assertThat(context).hasNotFailed();
            InjectionDetector detector = context.getBean(InjectionDetector.class);

            assertThat(detector.inspect("#".repeat(6)).rules())
                    .as("six opaque characters exceed a configured bound of five")
                    .contains("long opaque insertion");
        });
    }

    @Test
    void theSameFragmentIsNotAnInsertionUnderTheLargerBound() {
        contextWithOpaqueLength("120").run(context -> {
            InjectionDetector detector = context.getBean(InjectionDetector.class);

            assertThat(detector.inspect("#".repeat(6)).rules())
                    .as("the configured bound is what decides, so the same six characters pass")
                    .doesNotContain("long opaque insertion");
        });
    }
}
