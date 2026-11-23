package io.primelogiclab.mailroom.smtp2go;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The SMTP2GO API host to send through. An account's data lives in one region, and the global host serves every
 * account.
 *
 * @since 0.1.0
 */
public enum Smtp2GoRegion {

    /** {@code api.smtp2go.com}, which serves every account; the default. */
    GLOBAL("global", "https://api.smtp2go.com/"),

    /** {@code us-api.smtp2go.com}. */
    US("us", "https://us-api.smtp2go.com/"),

    /** {@code eu-api.smtp2go.com}. */
    EU("eu", "https://eu-api.smtp2go.com/"),

    /** {@code au-api.smtp2go.com}. */
    AU("au", "https://au-api.smtp2go.com/");

    private final String key;
    private final URI baseUri;

    Smtp2GoRegion(String key, String baseUri) {
        this.key = key;
        this.baseUri = URI.create(baseUri);
    }

    /**
     * Returns the region's name in configuration: {@code global}, {@code us}, {@code eu} or {@code au}.
     *
     * @return the name
     * @since 0.1.0
     */
    public String key() {
        return key;
    }

    /**
     * Returns the host's root, such as {@code https://api.smtp2go.com/}; the API's paths start with {@code v3/}.
     *
     * @return the root URI, ending in {@code /}
     * @since 0.1.0
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * Finds a region by its {@linkplain #key() name}, in any case.
     *
     * @param key {@code global}, {@code us}, {@code eu} or {@code au}
     * @return the region
     * @throws IllegalArgumentException if no region has that name
     * @since 0.1.0
     */
    public static Smtp2GoRegion of(String key) {
        Objects.requireNonNull(key, "key");
        return switch (key.strip().toLowerCase(Locale.ROOT)) {
            case "global" -> GLOBAL;
            case "us" -> US;
            case "eu" -> EU;
            case "au" -> AU;
            default -> throw new IllegalArgumentException("not an SMTP2GO region: use global, us, eu or au");
        };
    }
}
