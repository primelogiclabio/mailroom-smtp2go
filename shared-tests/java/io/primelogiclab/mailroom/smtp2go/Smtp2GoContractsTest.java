package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.tck.FailureCase;
import io.primelogiclab.mailroom.tck.FailureContract;
import io.primelogiclab.mailroom.tck.HttpFailureCases;
import io.primelogiclab.mailroom.tck.MappingContract;
import io.primelogiclab.mailroom.tck.PrecheckContract;
import io.primelogiclab.mailroom.tck.RedactionContract;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

/** The TCK's contracts against a stub SMTP2GO: mapping, failures, redaction and precheck. */
class Smtp2GoContractsTest {

    static final String SUCCESS = success();

    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = StubHttpServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private Smtp2GoTransport transport(Secret apiKey) {
        return new Smtp2GoTransport("smtp2go", server.baseUri(), apiKey, Duration.ofMillis(300));
    }

    /** The standard HTTP cases, a read timeout, and SMTP2GO's own answers. */
    private List<FailureCase> failureCases() {
        List<FailureCase> cases = new ArrayList<FailureCase>(HttpFailureCases.standard(server));
        cases.add(HttpFailureCases.readTimeout(server, Duration.ofSeconds(2)));
        cases.add(answer("unknown key (captured)", StubResponse.status(400).json(fixtureText("smtp2go/errors/"
                + "unknown-api-key.json")), FailureKind.AUTHENTICATION).status(400));
        cases.add(answer("no key (captured)", StubResponse.status(403).json(fixtureText("smtp2go/errors/"
                + "missing-api-key.json")), FailureKind.AUTHENTICATION).status(403));
        cases.add(answer("endpoint not enabled", StubResponse.status(400).json("{\"request_id\":"
                + "\"00000000-0000-4000-8000-000000000001\",\"data\":{\"error_code\":\""
                + Smtp2GoTransport.ENDPOINT_PERMISSION_DENIED + "\",\"error\":\"You do not have permission to access "
                + "this API endpoint\"}}"), FailureKind.AUTHENTICATION).status(400));
        cases.add(answer("error code at the top level", StubResponse.status(400).json("{\"error_code\":\""
                + Smtp2GoTransport.ENDPOINT_PERMISSION_DENIED + "\",\"error\":\"no permission\"}"),
                FailureKind.AUTHENTICATION).status(400));
        cases.add(answer("rate limited without a wait", StubResponse.status(429).json("{}"), FailureKind.RATE_LIMITED)
                .status(429).retryAfter(Duration.ofMinutes(1)));
        cases.add(answer("200 with the email failed", StubResponse.status(200).json("{\"data\":{\"succeeded\":0,"
                + "\"failed\":1,\"failures\":[\"canary.to@tck-canary.example.com: sender not verified\"]}}"),
                FailureKind.REJECTED).status(200));
        cases.add(answer("200 without an email_id", StubResponse.status(200).json("{\"data\":{\"succeeded\":1,"
                + "\"failed\":0,\"failures\":[]}}"), FailureKind.UNKNOWN_OUTCOME).status(200));
        return cases;
    }

    private FailureCase answer(String name, final StubResponse response, FailureKind kind) {
        return FailureCase.of(name, () -> server.enqueue(response), kind);
    }

    @Nested
    class Mapping extends MappingContract {

        @Override
        protected MailTransport newTransport() {
            return transport(Secret.of("api-tck-key"));
        }

        @Override
        protected void acceptNextSend() {
            server.enqueue(StubResponse.status(200).json(SUCCESS));
        }

        @Override
        protected Email lastSent() {
            return Smtp2GoJson.decode(server.lastRequest().body());
        }
    }

    @Nested
    class Failures extends FailureContract {

        @Override
        protected MailTransport newTransport() {
            return transport(Secret.of("api-tck-key"));
        }

        @Override
        protected List<FailureCase> failureCases() {
            return Smtp2GoContractsTest.this.failureCases();
        }
    }

    @Nested
    class Redaction extends RedactionContract {

        @Override
        protected MailTransport newTransport(Secret secret) {
            return transport(secret);
        }

        @Override
        protected void acceptNextSend() {
            server.enqueue(StubResponse.status(200).json(SUCCESS));
        }

        @Override
        protected List<FailureCase> failureCases() {
            return Smtp2GoContractsTest.this.failureCases();
        }
    }

    @Nested
    class Prechecks extends PrecheckContract {

        @Override
        protected MailTransport newTransport() {
            return transport(Secret.of("api-tck-key"));
        }

        @Override
        protected Map<String, Email> rejectedEmails() {
            Map<String, Email> rejected = new LinkedHashMap<String, Email>();
            rejected.put("101 To recipients", base().to(recipients("to", 101)).build());
            rejected.put("101 Cc recipients", base().cc(recipients("cc", 101)).build());
            rejected.put("101 Bcc recipients", base().bcc(recipients("bcc", 101)).build());
            rejected.put("38 MB attached, over 50 MB once encoded",
                    base().attach(Attachment.of("big.bin", "application/octet-stream", new byte[38000000])).build());
            rejected.put("metadata longer than one header line", base().metadata("notes", repeat('x', 970)).build());
            rejected.put("no To recipient", Email.builder().from("sender@tck.example.com").cc("cc@tck.example.com")
                    .subject("Hi").text("Hello").build());
            rejected.put("no from address", Email.builder().to("to@tck.example.com").subject("Hi").text("Hello")
                    .build());
            rejected.put("no content and no template", Email.builder().from("sender@tck.example.com")
                    .to("to@tck.example.com").with(new Smtp2GoTransportTest.OtherTemplate()).build());
            return rejected;
        }

        private Email.Builder base() {
            return Email.builder().from("sender@tck.example.com").to("to@tck.example.com").subject("Hi")
                    .text("Hello");
        }
    }

    static List<Address> recipients(String prefix, int count) {
        List<Address> addresses = new ArrayList<Address>(count);
        for (int i = 1; i <= count; i++) {
            addresses.add(Address.of(prefix + i + "@tck.example.com"));
        }
        return addresses;
    }

    static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }

    private static String success() {
        return fixtureText("smtp2go/send/send-response.json");
    }

    static String fixtureText(String path) {
        try {
            return new String(FixturesTest.fixture(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
