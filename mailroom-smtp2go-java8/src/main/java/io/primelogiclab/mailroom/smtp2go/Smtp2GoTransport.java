package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.http.FailureMapper;
import io.primelogiclab.mailroom.http.HttpEngine;
import io.primelogiclab.mailroom.http.HttpEngines;
import io.primelogiclab.mailroom.http.HttpRequest;
import io.primelogiclab.mailroom.http.HttpResponse;
import io.primelogiclab.mailroom.http.JsonValue;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.spi.TransportCapabilities;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends through SMTP2GO's {@code POST /v3/email/send}, with the API key in {@code X-Smtp2go-Api-Key}.
 *
 * <pre>{@code
 * MailTransport smtp2go = Smtp2GoTransport.builder()
 *         .apiKey(Secret.env("SMTP2GO_API_KEY"))
 *         .region(Smtp2GoRegion.EU)
 *         .build();
 * }</pre>
 *
 * <p>The result's message id is SMTP2GO's {@code email_id}. The transport sets no {@code Message-ID} header, so the
 * one SMTP2GO writes is {@code <E}<i>email_id</i>{@code @message-id.smtpcorp.com>}. Tags and metadata travel as the
 * {@code X-Mailroom-Tags} and {@code X-Mailroom-Metadata} headers, and reply-to addresses as a {@code Reply-To}
 * header. An {@link Smtp2GoOptions} template goes as {@code template_id} and {@code template_data}; an email with
 * one goes only through SMTP2GO transports. The key is read from its {@link Secret} for each request, so a rotated
 * key is picked up.
 *
 * <p>The precheck holds an email to SMTP2GO's limits: 100 addresses in each recipient list, 50 MB once encoded, and
 * a metadata header that fits on one line. Failures keep SMTP2GO's {@code error_code}. A key without the send
 * endpoint enabled is {@code AUTHENTICATION}, and a 200 that reports the email as failed is {@code REJECTED}.
 *
 * <p>Thread-safe. {@link #close()} closes the HTTP engine, found with {@link HttpEngines#load()}.
 *
 * @since 0.1.0
 */
public final class Smtp2GoTransport implements MailTransport {

    static final String PROVIDER_ID = "smtp2go";

    /** SMTP2GO's code for an API key that doesn't have the endpoint enabled. */
    static final String ENDPOINT_PERMISSION_DENIED = "E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED";

    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    private static final TransportCapabilities CAPABILITIES = TransportCapabilities.builder()
            .supports(Capability.TEXT, Capability.HTML, Capability.ATTACHMENTS, Capability.INLINE_IMAGES,
                    Capability.CUSTOM_HEADERS, Capability.TAGS, Capability.METADATA, Capability.MESSAGE_ID)
            .understands(Smtp2GoOptions.class)
            .maxMessageSize(Smtp2GoLimits.MAX_MESSAGE_SIZE)
            .build();

    private static final FailureMapper FAILURES = FailureMapper.builder(PROVIDER_ID)
            .providerCode(body -> {
                Optional<String> code = body.get("data").get("error_code").asString();
                return code.isPresent() ? code : body.get("error_code").asString();
            })
            .kind(400, ENDPOINT_PERMISSION_DENIED, FailureKind.AUTHENTICATION)
            .build();

    private final String name;
    private final String where;
    private final URI endpoint;
    private final Secret apiKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final HttpEngine engine;

    /** For tests: sends to {@code baseUri}, such as a stub server's, instead of a region's host. */
    Smtp2GoTransport(String name, URI baseUri, Secret apiKey, Duration readTimeout) {
        this(name, baseUri.toString(), baseUri, apiKey, HttpRequest.DEFAULT_CONNECT_TIMEOUT, readTimeout);
    }

    private Smtp2GoTransport(String name, String where, URI baseUri, Secret apiKey, Duration connectTimeout,
            Duration readTimeout) {
        this.name = name;
        this.where = where;
        String base = baseUri.toString();
        this.endpoint = URI.create(base + (base.endsWith("/") ? "" : "/") + "v3/email/send");
        this.apiKey = apiKey;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.engine = HttpEngines.load();
    }

    /**
     * Starts a transport.
     *
     * @return a builder
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@inheritDoc}
     *
     * @since 0.1.0
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Returns {@code smtp2go}.
     *
     * @return {@code smtp2go}
     * @since 0.1.0
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Text and HTML bodies, attachments, inline images, custom headers, tags, metadata, and a message id; understands
     * {@link Smtp2GoOptions}; at most 50,000,000 bytes.
     *
     * @return the capabilities
     * @since 0.1.0
     */
    @Override
    public TransportCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * Rejects an email without a from address or without a To recipient, which SMTP2GO requires; one without a
     * subject or without a body when no {@link Smtp2GoOptions} template supplies them; and one over SMTP2GO's
     * limits: more than 100 addresses in the To, Cc or Bcc list, a metadata header longer than one line (998
     * characters), or more than 50,000,000 bytes once encoded. The reason names no address.
     *
     * @param email the email
     * @return the verdict
     * @since 0.1.0
     */
    @Override
    public Precheck precheck(Email email) {
        Optional<String> problem = problem(email);
        return problem.isPresent() ? Precheck.reject(problem.get()) : Precheck.ok();
    }

    /**
     * Sends the email.
     *
     * @param email   the email
     * @param context the attempt; its deadline shortens the timeouts
     * @return the result, with SMTP2GO's {@code email_id} as the message id
     * @throws SendFailedException if SMTP2GO didn't take the email (also when a 200 reports it failed), couldn't be
     *                             reached, or its answer can't be read; {@code INVALID_MESSAGE} for an email
     *                             {@link #precheck(Email)} rejects
     * @since 0.1.0
     */
    @Override
    public SendResult send(Email email, SendContext context) {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(context, "context");
        try {
            Optional<String> problem = problem(email);
            if (problem.isPresent()) {
                throw SendFailedException.builder(FailureKind.INVALID_MESSAGE, problem.get()).build();
            }
            HttpResponse response = engine.execute(request(email, context));
            if (!response.isSuccessful()) {
                throw failure(response, context);
            }
            return SendResult.builder()
                    .transportName(name)
                    .providerId(PROVIDER_ID)
                    .providerMessageId(emailId(response))
                    .acceptedAt(context.clock().instant())
                    .build();
        } catch (SendFailedException e) {
            throw e.toBuilder().transportName(name).providerId(PROVIDER_ID).build();
        }
    }

    /**
     * Closes the HTTP engine.
     *
     * @since 0.1.0
     */
    @Override
    public void close() {
        engine.close();
    }

    /**
     * Returns the transport's name and region, never the key.
     *
     * @return {@code Smtp2GoTransport[name, region]}
     */
    @Override
    public String toString() {
        return "Smtp2GoTransport[" + name + ", " + where + "]";
    }

    private static Optional<String> problem(Email email) {
        if (!email.from().isPresent()) {
            return Optional.of("the email has no from address");
        }
        if (email.to().isEmpty()) {
            return Optional.of("SMTP2GO needs at least one To recipient");
        }
        if (!Smtp2GoRequest.templateId(email).isPresent()
                && (!email.subject().isPresent() || !email.text().isPresent() && !email.html().isPresent())) {
            return Optional.of("without an Smtp2GoOptions template, SMTP2GO needs a subject and a text or HTML body");
        }
        return Smtp2GoLimits.problem(email);
    }

    private HttpRequest request(Email email, SendContext context) {
        byte[] body = Smtp2GoRequest.body(email);
        HttpRequest.Builder request = HttpRequest.post(endpoint);
        try {
            request.header("X-Smtp2go-Api-Key", apiKey.reveal());
        } catch (IllegalStateException e) {
            throw SendFailedException.builder(FailureKind.AUTHENTICATION, "the API key is not available")
                    .cause(e).build();
        } catch (IllegalArgumentException e) {
            throw SendFailedException.builder(FailureKind.AUTHENTICATION,
                    "the API key has a character a header can't carry").build();
        }
        return request.header("Accept", "application/json")
                .json(body)
                .connectTimeout(capped(connectTimeout, context))
                .readTimeout(capped(readTimeout, context))
                .build();
    }

    /** The timeout, or the time the attempt has left when that is shorter (at least 1 ms). */
    private static Duration capped(Duration timeout, SendContext context) {
        Optional<Duration> left = context.timeLeft();
        if (!left.isPresent() || left.get().compareTo(timeout) >= 0) {
            return timeout;
        }
        return left.get().compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : left.get();
    }

    /** SMTP2GO's error as a failure; a rate limit that says nothing about waiting gets SMTP2GO's pause. */
    private static SendFailedException failure(HttpResponse response, SendContext context) {
        SendFailedException failure = FAILURES.failure(response, context.clock());
        if (failure.kind() == FailureKind.RATE_LIMITED && !failure.retryAfter().isPresent()) {
            return failure.toBuilder().retryAfter(Smtp2GoLimits.RATE_LIMIT_PAUSE).build();
        }
        return failure;
    }

    /**
     * SMTP2GO's {@code data.email_id}. A 200 can still report the email as failed, which is {@code REJECTED}; its
     * failure messages stay out of the exception, since they may name recipients. A success without an id can't
     * say whether the email went out.
     */
    private static String emailId(HttpResponse response) {
        JsonValue data = response.json().get("data");
        long failed = Math.max(data.get("failed").asLong().orElse(0), data.get("failures").size());
        if (failed > 0 && data.get("succeeded").asLong().orElse(0) == 0) {
            throw SendFailedException.builder(FailureKind.REJECTED, PROVIDER_ID + " answered "
                    + response.statusCode() + " but did not send the email (" + failed
                    + (failed == 1 ? " failure)" : " failures)"))
                    .statusCode(response.statusCode())
                    .build();
        }
        Optional<String> id = data.get("email_id").asString();
        if (!id.isPresent() || id.get().trim().isEmpty()) {
            throw SendFailedException.builder(FailureKind.UNKNOWN_OUTCOME,
                    PROVIDER_ID + " answered " + response.statusCode() + " without an email_id")
                    .statusCode(response.statusCode())
                    .build();
        }
        return id.get();
    }

    static Duration checkTimeout(String what, Duration timeout) {
        Objects.requireNonNull(timeout, what);
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("the " + what + " must be from 1 ms to " + Integer.MAX_VALUE + " ms");
        }
        return timeout;
    }

    /**
     * Builds an {@link Smtp2GoTransport}. Only the API key is required.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private String name = PROVIDER_ID;
        private Secret apiKey;
        private Smtp2GoRegion region = Smtp2GoRegion.GLOBAL;
        private Duration connectTimeout = HttpRequest.DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = HttpRequest.DEFAULT_READ_TIMEOUT;

        private Builder() {
        }

        /**
         * Sets the transport's name, used in routes, results and logs.
         *
         * @param name the name; {@code smtp2go} by default
         * @return this builder
         * @throws IllegalArgumentException if the name is blank
         * @since 0.1.0
         */
        public Builder name(String name) {
            Objects.requireNonNull(name, "name");
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("the name must not be blank");
            }
            this.name = name;
            return this;
        }

        /**
         * Sets the API key, which must have the {@code /email/send} endpoint enabled.
         *
         * @param apiKey the key, such as {@code Secret.env("SMTP2GO_API_KEY")}
         * @return this builder
         * @since 0.1.0
         */
        public Builder apiKey(Secret apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /**
         * Sets the API host.
         *
         * @param region the region; {@link Smtp2GoRegion#GLOBAL} by default
         * @return this builder
         * @since 0.1.0
         */
        public Builder region(Smtp2GoRegion region) {
            this.region = Objects.requireNonNull(region, "region");
            return this;
        }

        /**
         * Sets how long to wait for the connection, cut short by an attempt's deadline.
         *
         * @param timeout from 1 ms to {@code Integer.MAX_VALUE} ms; 10 seconds by default
         * @return this builder
         * @throws IllegalArgumentException if the timeout is out of range
         * @since 0.1.0
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = checkTimeout("connect timeout", timeout);
            return this;
        }

        /**
         * Sets how long to wait for SMTP2GO's answer, cut short by an attempt's deadline.
         *
         * @param timeout from 1 ms to {@code Integer.MAX_VALUE} ms; 30 seconds by default
         * @return this builder
         * @throws IllegalArgumentException if the timeout is out of range
         * @since 0.1.0
         */
        public Builder readTimeout(Duration timeout) {
            this.readTimeout = checkTimeout("read timeout", timeout);
            return this;
        }

        /**
         * Builds the transport, which loads its HTTP engine.
         *
         * @return the transport
         * @throws IllegalStateException if no API key was set
         * @since 0.1.0
         */
        public Smtp2GoTransport build() {
            if (apiKey == null) {
                throw new IllegalStateException("an API key is required: call apiKey(...)");
            }
            return new Smtp2GoTransport(name, region.key(), region.baseUri(), apiKey, connectTimeout, readTimeout);
        }
    }
}
