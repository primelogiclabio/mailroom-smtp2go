package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.http.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** The SMTP2GO fixtures reach the test classpath of both sets, and mailroom-http reads them. */
class FixturesTest {

    @Test
    void readsTheSendResponse() throws IOException {
        JsonValue response = JsonValue.parse(fixture("smtp2go/send/send-response.json"));
        assertThat(response.get("data").get("succeeded").asLong().getAsLong()).isEqualTo(1);
        assertThat(response.get("data").get("failed").asLong().getAsLong()).isZero();
        assertThat(response.get("data").get("email_id").asString()).contains("1Exm02-FixtureMail-0002");
    }

    /** A fixture's bytes, loaded as a test resource; Java 8 syntax, since both sets compile it. */
    static byte[] fixture(String path) throws IOException {
        try (InputStream in = FixturesTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
