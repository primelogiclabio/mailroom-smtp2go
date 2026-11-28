package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.http.JsonValue;
import org.junit.jupiter.api.Test;

/** The live test's helpers, which run without SMTP2GO: what it prints must never show an address. */
class LiveTestHelpersTest {

    @Test
    void addressesAreMaskedWhereverTheyAppear() {
        assertThat(Smtp2GoLiveTest.mask("From: \"Mailroom Lïve\" <sender@example.com>, to@example.net; x@y.org"))
                .isEqualTo("From: \"Mailroom Lïve\" <***@***>, ***@***; ***@***");
        assertThat(Smtp2GoLiveTest.mask("Message-Id: <E1Exm02-Fixture@message-id.smtpcorp.com>"))
                .isEqualTo("Message-Id: <***@***>");
        assertThat(Smtp2GoLiveTest.mask("no address here")).isEqualTo("no address here");
    }

    @Test
    void aHeaderIsFoundCaseInsensitivelyAndUnfolded() {
        String headers = "Subject: =?utf-8?q?Gr=C3=BC=C3=9Fe?=\n =?utf-8?q?_=E2=9C=93?=\nx-live-note: Grüße\n"
                + "To: someone@example.net\n";

        assertThat(Smtp2GoLiveTest.headerLine(headers, "Subject"))
                .isEqualTo("Subject: =?utf-8?q?Gr=C3=BC=C3=9Fe?= =?utf-8?q?_=E2=9C=93?=");
        assertThat(Smtp2GoLiveTest.headerLine(headers, "X-Live-Note")).isEqualTo("x-live-note: Grüße");
        assertThat(Smtp2GoLiveTest.headerLine(headers, "X-Mailroom-Tags")).isEqualTo("X-Mailroom-Tags: (absent)");
    }

    @Test
    void theShapeShowsFieldNamesAndOnlyTheErrorCode() {
        JsonValue body = JsonValue.parse("{\"request_id\":\"r-1\",\"data\":{\"error\":\"Bad sender a@example.com\","
                + "\"error_code\":\"E_ApiResponseCodes.EXAMPLE\",\"field_validation_errors\":{\"fieldname\":\"sender\","
                + "\"message\":\"text\"}}}");

        assertThat(Smtp2GoLiveTest.shape(body, "")).isEqualTo("request_id, data.error, "
                + "data.error_code=E_ApiResponseCodes.EXAMPLE, data.field_validation_errors.fieldname=sender, "
                + "data.field_validation_errors.message");
    }
}
