package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.TransportConfig;
import io.primelogiclab.mailroom.spi.TransportProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates {@link Smtp2GoTransport}s from configuration, for {@code type: smtp2go}. Found through
 * {@link java.util.ServiceLoader}.
 *
 * <table>
 *   <caption>Keys</caption>
 *   <tr><th>Key</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code api-key}</td><td>required</td><td>API key with {@code /email/send} enabled; a secret</td></tr>
 *   <tr><td>{@code region}</td><td>{@code global}</td><td>{@code global}, {@code us}, {@code eu} or {@code au}</td></tr>
 *   <tr><td>{@code connect-timeout}</td><td>{@code 10s}</td><td>how long to wait for the connection</td></tr>
 *   <tr><td>{@code read-timeout}</td><td>{@code 30s}</td><td>how long to wait for SMTP2GO's answer</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
public final class Smtp2GoTransportProvider implements TransportProvider {

    static final ConfigKey API_KEY = ConfigKey.of("api-key").required().secret()
            .description("SMTP2GO API key with /email/send enabled");
    static final ConfigKey REGION = ConfigKey.of("region").withDefault("global")
            .description("API region: global, us, eu or au");
    static final ConfigKey CONNECT_TIMEOUT = ConfigKey.of("connect-timeout").withDefault("10s")
            .description("How long to wait for the connection");
    static final ConfigKey READ_TIMEOUT = ConfigKey.of("read-timeout").withDefault("30s")
            .description("How long to wait for SMTP2GO's answer");

    private static final Set<ConfigKey> KEYS = Collections.unmodifiableSet(new LinkedHashSet<ConfigKey>(
            Arrays.asList(API_KEY, REGION, CONNECT_TIMEOUT, READ_TIMEOUT)));

    /**
     * Creates the provider; {@link java.util.ServiceLoader} calls this.
     *
     * @since 0.1.0
     */
    public Smtp2GoTransportProvider() {
    }

    /**
     * Returns {@code smtp2go}.
     *
     * @return {@code smtp2go}
     * @since 0.1.0
     */
    @Override
    public String id() {
        return Smtp2GoTransport.PROVIDER_ID;
    }

    /**
     * Returns {@code api-key}, {@code region}, {@code connect-timeout} and {@code read-timeout}.
     *
     * @return the keys
     * @since 0.1.0
     */
    @Override
    public Set<ConfigKey> configKeys() {
        return KEYS;
    }

    /**
     * Creates a transport.
     *
     * @param name   the transport's name
     * @param config the transport's settings
     * @return the transport
     * @throws InvalidConfigException if the region or a timeout isn't valid, listing every problem
     * @since 0.1.0
     */
    @Override
    public MailTransport create(String name, TransportConfig config) {
        List<String> problems = new ArrayList<String>();
        Smtp2GoTransport.Builder builder = Smtp2GoTransport.builder().name(name)
                .apiKey(config.secret(API_KEY.name()).get());
        try {
            builder.region(Smtp2GoRegion.of(config.require(REGION.name())));
        } catch (IllegalArgumentException e) {
            problems.add("transport '" + name + "': 'region' must be global, us, eu or au");
        }
        try {
            builder.connectTimeout(timeout(config, CONNECT_TIMEOUT));
        } catch (InvalidConfigException e) {
            problems.addAll(e.problems());
        }
        try {
            builder.readTimeout(timeout(config, READ_TIMEOUT));
        } catch (InvalidConfigException e) {
            problems.addAll(e.problems());
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigException(problems);
        }
        return builder.build();
    }

    /** The key's duration, checked against the builder's range. */
    private static Duration timeout(TransportConfig config, ConfigKey key) {
        Duration timeout = config.duration(key.name()).get();
        try {
            Smtp2GoTransport.checkTimeout(key.name(), timeout);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException("transport '" + config.name() + "': '" + key.name()
                    + "' must be from 1ms to " + Integer.MAX_VALUE + "ms");
        }
        return timeout;
    }
}
