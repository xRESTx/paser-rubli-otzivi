package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Loads runtime configuration for the bot.
 *
 * Precedence (highest first):
 * 1) ./bot.properties in working directory (if exists)
 * 2) classpath resource bot.properties (src/main/resources)
 */
public final class BotConfig {
    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);
    private static final String RESOURCE_NAME = "bot.properties";

    private final Properties props;

    private BotConfig(Properties props) {
        this.props = props;
    }

    public static BotConfig load() {
        Properties props = new Properties();

        // 2) classpath defaults
        try (InputStream is = BotConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (is != null) {
                props.load(is);
            } else {
                log.warn("Classpath resource {} not found", RESOURCE_NAME);
            }
        } catch (IOException e) {
            log.warn("Failed to load classpath {}", RESOURCE_NAME, e);
        }

        // 1) working dir override
        Path override = Path.of(RESOURCE_NAME);
        if (Files.exists(override)) {
            try (InputStream is = Files.newInputStream(override)) {
                Properties p2 = new Properties();
                p2.load(is);
                props.putAll(p2);
            } catch (IOException e) {
                log.warn("Failed to load override config from {}", override.toAbsolutePath(), e);
            }
        } else {
        }

        return new BotConfig(props);
    }

    public String getRequired(String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required property: " + key + " (configure in bot.properties)");
        }
        return value.trim();
    }

    public String getOptional(String key) {
        String value = props.getProperty(key);
        return value == null ? null : value.trim();
    }

    public Path getPath(String key, String defaultValue) {
        String value = getOptional(key);
        if (value == null || value.isBlank()) {
            value = Objects.requireNonNull(defaultValue);
        }
        return Path.of(value.trim());
    }
}


