package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Attempt;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.NoEligibleTransportException;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.http.JsonValue;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.tck.RecordedRequest;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import io.primelogiclab.mailroom.test.ScriptedTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Design scenario 5, "template pinned to one provider", with SMTP2GO: an email with an {@link Smtp2GoOptions} template
 * goes only through SMTP2GO transports, and never fails over to one that doesn't understand the options.
 */
class Smtp2GoTemplateRoutingTest {

    private static final String DOWN = "{\"data\":{\"error_code\":\"E_ApiResponseCodes.EXAMPLE\",\"error\":\"down\"}}";

    private final List<StubHttpServer> servers = new ArrayList<StubHttpServer>();
    private final List<Mailroom> mailrooms = new ArrayList<Mailroom>();

    @AfterEach
    void stop() {
        for (Mailroom mailroom : mailrooms) {
            mailroom.close();
        }
        for (StubHttpServer server : servers) {
            server.close();
        }
    }

    private StubHttpServer server() {
        StubHttpServer server = StubHttpServer.start();
        servers.add(server);
        return server;
    }

    private static Smtp2GoTransport smtp2go(String name, StubHttpServer server) {
        return new Smtp2GoTransport(name, server.baseUri(), Secret.of("api-test-0123456789"), Duration.ofSeconds(5));
    }

    private static ScriptedTransport sendGrid() {
        return ScriptedTransport.builder("backup").providerId("sendgrid").build();
    }

    private Mailroom mailroom(MailTransport first, MailTransport second) {
        Mailroom mailroom = Mailroom.builder().transport(first).transport(second)
                .defaultRoute(first.name(), second.name()).retryBackoff(Duration.ofMillis(1)).build();
        mailrooms.add(mailroom);
        return mailroom;
    }

    private static Email welcome() {
        return Email.builder().from("sender@example.com").to("player@example.com")
                .with(Smtp2GoOptions.builder().templateId("welcome").templateData("username", "Ann").build())
                .build();
    }

    private static Email plain() {
        return Email.builder().from("sender@example.com").to("player@example.com").subject("Hi").text("Hello").build();
    }

    private static JsonValue body(RecordedRequest request) {
        return JsonValue.parse(request.body());
    }

    @Test
    void theTemplateIsNeverSentThroughTheBackup() {
        StubHttpServer down = server();
        down.enqueue(StubResponse.status(503).json(DOWN), StubResponse.status(503).json(DOWN));
        ScriptedTransport backup = sendGrid();
        Mailroom mailroom = mailroom(smtp2go("primary", down), backup);

        SendFailedException e = failure(mailroom, welcome());

        assertThat(e.kind()).isEqualTo(FailureKind.UNAVAILABLE);
        assertThat(describe(e.attempts())).containsExactly("primary UNAVAILABLE", "primary UNAVAILABLE");
        backup.assertCalls(0);
        assertThat(down.requestCount()).isEqualTo(2);
        for (RecordedRequest request : down.requests()) {
            assertThat(body(request).get("template_id").asString()).hasValue("welcome");
        }
    }

    @Test
    void withoutATemplateTheBackupTakesOver() {
        StubHttpServer down = server();
        down.enqueue(StubResponse.status(503).json(DOWN), StubResponse.status(503).json(DOWN));
        ScriptedTransport backup = sendGrid();
        Mailroom mailroom = mailroom(smtp2go("primary", down), backup);

        SendResult result = mailroom.send(plain());

        assertThat(result.transportName()).isEqualTo("backup");
        backup.assertCalls(1);
    }

    @Test
    void noOtherTransportCanCarryTheTemplate() {
        StubHttpServer up = server();
        ScriptedTransport backup = sendGrid();
        Mailroom mailroom = mailroom(smtp2go("primary", up), backup);

        assertThatThrownBy(() -> mailroom.via("backup").send(welcome()))
                .isInstanceOfSatisfying(NoEligibleTransportException.class, e -> {
                    assertThat(e.skipReasons())
                            .containsEntry("backup", "doesn't understand Smtp2GoOptions (essential)");
                    assertThat(e.getMessage()).isEqualTo(
                            "No transport can carry this email: backup: doesn't understand Smtp2GoOptions (essential)");
                });
        backup.assertCalls(0);
        assertThat(up.requestCount()).isZero();
    }

    @Test
    void smtp2goSendsTheTemplateWithoutASubjectOrBodies() {
        StubHttpServer up = server();
        up.enqueue(StubResponse.status(200).json(Smtp2GoContractsTest.SUCCESS));
        ScriptedTransport backup = sendGrid();
        Mailroom mailroom = mailroom(smtp2go("primary", up), backup);

        SendResult result = mailroom.send(welcome());

        assertThat(result.transportName()).isEqualTo("primary");
        assertThat(result.providerMessageId()).hasValue("1Exm02-FixtureMail-0002");
        JsonValue body = body(up.lastRequest());
        assertThat(body.fields().keySet()).containsExactly("sender", "to", "template_id", "template_data");
        assertThat(body.get("template_data").get("username").asString()).hasValue("Ann");
        backup.assertCalls(0);
    }

    @Test
    void aSecondSmtp2goAccountTakesTheTemplate() {
        StubHttpServer down = server();
        down.enqueue(StubResponse.status(503).json(DOWN), StubResponse.status(503).json(DOWN));
        StubHttpServer up = server();
        up.enqueue(StubResponse.status(200).json(Smtp2GoContractsTest.SUCCESS));
        Mailroom mailroom = mailroom(smtp2go("first", down), smtp2go("second", up));

        SendResult result = mailroom.send(welcome());

        assertThat(result.transportName()).isEqualTo("second");
        assertThat(describe(result.attempts()))
                .containsExactly("first UNAVAILABLE", "first UNAVAILABLE", "second SUCCEEDED");
        assertThat(body(up.lastRequest()).get("template_id").asString()).hasValue("welcome");
    }

    private static SendFailedException failure(Mailroom mailroom, Email email) {
        try {
            mailroom.send(email);
        } catch (SendFailedException e) {
            return e;
        }
        throw new AssertionError("the send should have failed");
    }

    private static List<String> describe(List<Attempt> attempts) {
        List<String> out = new ArrayList<String>();
        for (Attempt a : attempts) {
            out.add(a.transportName() + " " + (a.succeeded() ? "SUCCEEDED" : a.failureKind().get().name()));
        }
        return out;
    }
}
