package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.spi.Capability;
import io.primelogiclab.mailroom.spi.Precheck;
import io.primelogiclab.mailroom.spi.SendContext;
import io.primelogiclab.mailroom.tck.RecordedRequest;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One send against a stub SMTP2GO: the request it makes and how it reads the answer. */
class Smtp2GoTransportTest {

    private static final String KEY = "api-test-0123456789";
    private static final SendContext CONTEXT = SendContext.builder().build();

    private StubHttpServer server;
    private Smtp2GoTransport transport;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
        transport = new Smtp2GoTransport("primary", server.baseUri(), Secret.of(KEY), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        transport.close();
        server.close();
    }

    private static Email email() {
        return Email.builder().from("sender@example.com").to("to@example.com").subject("Hi").text("Hello").build();
    }

    @Test
    void postsJsonToTheSendEndpointWithTheKey() {
        server.enqueue(StubResponse.status(200).json(Smtp2GoContractsTest.SUCCESS));

        transport.send(email(), CONTEXT);

        RecordedRequest request = server.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v3/email/send");
        assertThat(request.header("X-Smtp2go-Api-Key")).hasValue(KEY);
        assertThat(request.header("Accept")).hasValue("application/json");
        assertThat(request.header("Content-Type").get()).startsWith("application/json");
        assertThat(Smtp2GoJson.decode(request.body()).subject()).hasValue("Hi");
    }

    @Test
    void theEmailIdBecomesTheMessageId() {
        server.enqueue(StubResponse.status(200).json(Smtp2GoContractsTest.SUCCESS));

        SendResult result = transport.send(email(), CONTEXT);

        assertThat(result.providerMessageId()).hasValue("1Exm02-FixtureMail-0002");
        assertThat(result.transportName()).isEqualTo("primary");
        assertThat(result.providerId()).isEqualTo("smtp2go");
        assertThat(transport.capabilities().supports(Capability.MESSAGE_ID)).isTrue();
    }

    @Test
    void aSuccessWithoutAnEmailIdIsAnUnknownOutcome() {
        server.enqueue(StubResponse.status(200).json("{\"data\":{\"succeeded\":1,\"failed\":0,\"failures\":[]}}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
                    assertThat(e.statusCode()).hasValue(200);
                    assertThat(e.transportName()).hasValue("primary");
                    assertThat(e.providerId()).hasValue("smtp2go");
                });
    }

    @Test
    void aSuccessThatReportsTheEmailFailedIsRejectedWithoutItsMessages() {
        server.enqueue(StubResponse.status(200).json("{\"data\":{\"succeeded\":0,\"failed\":1,"
                + "\"failures\":[\"to@example.com: sender not verified\"]}}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.REJECTED);
                    assertThat(e.statusCode()).hasValue(200);
                    assertThat(e.getMessage())
                            .isEqualTo("REJECTED via primary: smtp2go answered 200 but did not send the email "
                                    + "(1 failure)")
                            .doesNotContain("to@example.com");
                    assertThat(e.transportName()).hasValue("primary");
                });
    }

    @Test
    void failuresAloneMarkTheEmailFailed() {
        server.enqueue(StubResponse.status(200).json("{\"data\":{\"failures\":[\"one\",\"two\"]}}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.REJECTED);
                    assertThat(e.getMessage()).endsWith("(2 failures)");
                });
    }

    @Test
    void aKeyWithoutTheEndpointIsAnAuthenticationFailure() {
        server.enqueue(StubResponse.status(400).json("{\"data\":{\"error_code\":\""
                + Smtp2GoTransport.ENDPOINT_PERMISSION_DENIED + "\",\"error\":\"no permission\"}}"));
        server.enqueue(StubResponse.status(400).json("{\"error_code\":\"" + Smtp2GoTransport.ENDPOINT_PERMISSION_DENIED
                + "\",\"error\":\"no permission\"}"));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                    .isInstanceOfSatisfying(SendFailedException.class, e -> {
                        assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION);
                        assertThat(e.providerCode()).hasValue(Smtp2GoTransport.ENDPOINT_PERMISSION_DENIED);
                    });
        }
    }

    @Test
    void anUnknownKeyIsAnAuthenticationFailureButOtherApiExceptionsAreNot() {
        server.enqueue(StubResponse.status(400)
                .json(Smtp2GoContractsTest.fixtureText("smtp2go/errors/unknown-api-key.json")));
        server.enqueue(StubResponse.status(400).json("{\"data\":{\"error\":\"Something else went wrong\","
                + "\"error_code\":\"" + Smtp2GoTransport.API_EXCEPTION + "\"}}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION);
                    assertThat(e.providerCode()).hasValue(Smtp2GoTransport.API_EXCEPTION);
                    assertThat(e.getMessage()).doesNotContain("API User");
                });
        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
    }

    @Test
    void aRateLimitWithoutAWaitPausesForAMinute() {
        server.enqueue(StubResponse.status(429).json("{}"));
        server.enqueue(StubResponse.status(429).header("Retry-After", "5").json("{}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.RATE_LIMITED);
                    assertThat(e.retryAfter()).hasValue(Duration.ofMinutes(1));
                });
        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.retryAfter()).hasValue(Duration.ofSeconds(5)));
    }

    @Test
    void anUnreadableSuccessIsAnUnknownOutcome() {
        server.enqueue(StubResponse.status(200).body("<html>", "text/html"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN_OUTCOME);
                    assertThat(e.transportName()).hasValue("primary");
                });
    }

    @Test
    void anErrorKeepsSmtp2gosCodeAndNamesTheTransport() {
        server.enqueue(StubResponse.status(400).json("{\"data\":{\"error_code\":\"E_ApiResponseCodes.EXAMPLE\","
                + "\"error\":\"Something about to@example.com\"}}"));

        assertThatThrownBy(() -> transport.send(email(), CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class, e -> {
                    assertThat(e.statusCode()).hasValue(400);
                    assertThat(e.providerCode()).hasValue("E_ApiResponseCodes.EXAMPLE");
                    assertThat(e.transportName()).hasValue("primary");
                    assertThat(e.providerId()).hasValue("smtp2go");
                    assertThat(e.getMessage()).doesNotContain("to@example.com");
                });
    }

    @Test
    void anEmailWithoutAToRecipientIsRejectedBeforeAnyRequest() {
        Email ccOnly = Email.builder().from("sender@example.com").cc("cc@example.com").subject("Hi").text("Hello")
                .build();

        Precheck precheck = transport.precheck(ccOnly);
        assertThat(precheck.passed()).isFalse();
        assertThat(precheck.reason()).hasValue("SMTP2GO needs at least one To recipient");
        assertThatThrownBy(() -> transport.send(ccOnly, CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void anEmailWithoutAFromAddressIsRejectedBeforeAnyRequest() {
        Email noFrom = Email.builder().to("to@example.com").subject("Hi").text("Hello").build();

        assertThat(transport.precheck(noFrom).reason()).hasValue("the email has no from address");
        assertThatThrownBy(() -> transport.send(noFrom, CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
        assertThat(server.requestCount()).isZero();
        assertThat(transport.precheck(email()).passed()).isTrue();
    }

    @Test
    void anEmailWithoutContentOrATemplateIsRejectedBeforeAnyRequest() {
        Email noContent = Email.builder().from("sender@example.com").to("to@example.com").with(new OtherTemplate())
                .build();
        Email subjectOnly = Email.builder().from("sender@example.com").to("to@example.com").subject("Hi")
                .with(new OtherTemplate()).build();

        String reason = "without an Smtp2GoOptions template, SMTP2GO needs a subject and a text or HTML body";
        assertThat(transport.precheck(noContent).reason()).hasValue(reason);
        assertThat(transport.precheck(subjectOnly).reason()).hasValue(reason);
        assertThatThrownBy(() -> transport.send(noContent, CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void anEmailOverSmtp2gosLimitsIsRejectedBeforeAnyRequest() {
        Email cc = email().toBuilder().cc(Smtp2GoContractsTest.recipients("cc", 101)).build();
        Email metadata = email().toBuilder().metadata("notes", Smtp2GoContractsTest.repeat('x', 970)).build();
        Email big = email().toBuilder()
                .attach(Attachment.of("big.bin", "application/octet-stream", new byte[38000000])).build();

        assertThat(transport.precheck(cc).reason())
                .hasValue("SMTP2GO takes at most 100 Cc recipients; this email has 101");
        assertThat(transport.precheck(metadata).reason()).hasValue("the metadata makes a 1003-character "
                + "X-Mailroom-Metadata header line, over the 998 one line can hold");
        assertThat(transport.precheck(big).reason().get()).startsWith("the email is about ")
                .endsWith(" bytes once encoded, over SMTP2GO's limit of 50000000");
        assertThatThrownBy(() -> transport.send(cc, CONTEXT))
                .isInstanceOfSatisfying(SendFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_MESSAGE));
        assertThat(server.requestCount()).isZero();
        assertThat(transport.capabilities().maxMessageSize()).hasValue(50000000L);
    }

    @Test
    void understandsItsOptionsAndTakesATemplateEmail() {
        Email template = Email.builder().from("sender@example.com").to("to@example.com")
                .with(Smtp2GoOptions.builder().templateId("welcome").build()).build();

        assertThat(transport.capabilities().understands(Smtp2GoOptions.class)).isTrue();
        assertThat(transport.precheck(template).passed()).isTrue();
    }

    @Test
    void aKeyThatCantBeReadIsAnAuthenticationFailure() {
        Supplier<String> missing = () -> null;
        try (Smtp2GoTransport noKey = new Smtp2GoTransport("primary", server.baseUri(), Secret.of(missing),
                Duration.ofSeconds(5))) {
            assertThatThrownBy(() -> noKey.send(email(), CONTEXT))
                    .isInstanceOfSatisfying(SendFailedException.class, e -> {
                        assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION);
                        assertThat(e.transportName()).hasValue("primary");
                    });
        }
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void theDeadlineShortensTheReadTimeout() {
        server.enqueue(StubResponse.status(200).json(Smtp2GoContractsTest.SUCCESS).delay(Duration.ofSeconds(3)));
        SendContext soon = SendContext.builder().deadline(Instant.now().plusMillis(300)).build();
        long started = System.nanoTime();

        assertThatThrownBy(() -> transport.send(email(), soon)).isInstanceOf(SendFailedException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void toStringNamesTheTransportButNotTheKey() {
        assertThat(transport.toString()).startsWith("Smtp2GoTransport[primary, ").doesNotContain(KEY);
        try (Smtp2GoTransport built = Smtp2GoTransport.builder().apiKey(Secret.of(KEY)).region(Smtp2GoRegion.EU)
                .build()) {
            assertThat(built.toString()).isEqualTo("Smtp2GoTransport[smtp2go, eu]");
            assertThat(built.name()).isEqualTo("smtp2go");
        }
    }

    @Test
    void theBuilderNeedsAKeyAndChecksItsValues() {
        assertThatThrownBy(() -> Smtp2GoTransport.builder().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Smtp2GoTransport.builder().name(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Smtp2GoTransport.builder().readTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Smtp2GoTransport.builder().connectTimeout(Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Another provider's template: it supplies the content but isn't essential, so the router lets SMTP2GO see it. */
    static final class OtherTemplate implements EmailExtension {

        @Override
        public boolean providesContent() {
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OtherTemplate;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
