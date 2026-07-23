package dev.faultora.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {

    @Test
    void apacheWireAndHeaderLoggingAreDisabled() {
        Logger wire = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.wire");
        Logger headers = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.headers");

        assertThat(wire.getEffectiveLevel()).isEqualTo(Level.OFF);
        assertThat(headers.getEffectiveLevel()).isEqualTo(Level.OFF);
    }

    @Test
    void rootLoggerDoesNotEnableDebug() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(root.getEffectiveLevel().isGreaterOrEqual(Level.INFO)).isTrue();
    }
}
