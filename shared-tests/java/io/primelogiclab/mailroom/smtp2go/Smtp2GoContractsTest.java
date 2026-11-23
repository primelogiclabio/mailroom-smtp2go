package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.spi.MailTransport;
import io.primelogiclab.mailroom.tck.MappingContract;
import io.primelogiclab.mailroom.tck.StubHttpServer;
import io.primelogiclab.mailroom.tck.StubResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

/** The TCK's contracts against a stub SMTP2GO. Failure, redaction and precheck contracts join in G-3. */
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

    private static String success() {
        try {
            return new String(FixturesTest.fixture("smtp2go/send/send-response.json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
