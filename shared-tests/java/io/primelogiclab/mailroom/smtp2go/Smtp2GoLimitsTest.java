package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import org.junit.jupiter.api.Test;

/** {@link Smtp2GoLimits}: the size estimate and where each limit starts. */
class Smtp2GoLimitsTest {

    private static Email.Builder minimal() {
        return Email.builder().from("a@example.com").to("b@example.com").subject("Hi").text("Hello");
    }

    @Test
    void base64GrowsByAThirdWithALineBreakEvery76Characters() {
        assertThat(Smtp2GoLimits.base64Size(0)).isZero();
        assertThat(Smtp2GoLimits.base64Size(1)).isEqualTo(4 + 2);
        assertThat(Smtp2GoLimits.base64Size(3)).isEqualTo(4 + 2);
        assertThat(Smtp2GoLimits.base64Size(57)).isEqualTo(76 + 2);
        assertThat(Smtp2GoLimits.base64Size(58)).isEqualTo(80 + 4);
        assertThat(Smtp2GoLimits.base64Size(37500000)).isEqualTo(50000000L + 2 * 657895);
    }

    @Test
    void utf8LengthCountsBytesWithoutEncoding() {
        assertThat(Smtp2GoLimits.utf8Length("a")).isEqualTo(1);
        assertThat(Smtp2GoLimits.utf8Length("é")).isEqualTo(2);
        assertThat(Smtp2GoLimits.utf8Length("€")).isEqualTo(3);
        assertThat(Smtp2GoLimits.utf8Length("😀")).isEqualTo(4);
    }

    @Test
    void theEstimateAddsHeaderLinesBodiesAndEncodedAttachments() {
        // From: a@example.com (21), To: b@example.com (19), Subject: Hi (13), the body (5)
        assertThat(Smtp2GoLimits.estimatedSize(minimal().build())).isEqualTo(58);
        assertThat(Smtp2GoLimits.estimatedSize(minimal().html("<p>é</p>").build())).isEqualTo(58 + 9);
        assertThat(Smtp2GoLimits.estimatedSize(minimal()
                .attach(Attachment.of("a.bin", "application/octet-stream", new byte[3])).build())).isEqualTo(58 + 6);
        // X-Mailroom-Tags: a, b (21 + 2), X-Mailroom-Metadata: {"k":"v"} (30 + 2)
        assertThat(Smtp2GoLimits.estimatedSize(minimal().tag("a", "b").metadata("k", "v").build()))
                .isEqualTo(58 + 23 + 32);
    }

    @Test
    void eachRecipientListTakes100() {
        assertThat(Smtp2GoLimits.problem(minimal().bcc(Smtp2GoContractsTest.recipients("bcc", 100)).build()))
                .isEmpty();
        assertThat(Smtp2GoLimits.problem(minimal().bcc(Smtp2GoContractsTest.recipients("bcc", 101)).build()))
                .hasValue("SMTP2GO takes at most 100 Bcc recipients; this email has 101");
        assertThat(Smtp2GoLimits.problem(Email.builder().from("a@example.com").subject("Hi").text("Hello")
                .to(Smtp2GoContractsTest.recipients("to", 100)).build())).isEmpty();
    }

    @Test
    void theMetadataLineHolds998Characters() {
        // X-Mailroom-Metadata: {"k":"…"} is 29 characters around the value
        Email fits = minimal().metadata("k", Smtp2GoContractsTest.repeat('x', 969)).build();
        Email over = minimal().metadata("k", Smtp2GoContractsTest.repeat('x', 970)).build();

        assertThat(Smtp2GoLimits.metadataLine(fits)).isEqualTo(998);
        assertThat(Smtp2GoLimits.problem(fits)).isEmpty();
        assertThat(Smtp2GoLimits.problem(over)).hasValue(
                "the metadata makes a 999-character X-Mailroom-Metadata header line, over the 998 one line can hold");
    }

    @Test
    void theSizeLimitIsFiftyMillionEncodedBytes() {
        Email under = minimal().attach(Attachment.of("a.bin", "application/octet-stream", new byte[36000000]))
                .build();
        Email over = minimal().attach(Attachment.of("a.bin", "application/octet-stream", new byte[37500000]))
                .build();

        assertThat(Smtp2GoLimits.problem(under)).isEmpty();
        assertThat(Smtp2GoLimits.problem(over)).hasValue("the email is about " + (58 + 50000000L + 2 * 657895)
                + " bytes once encoded, over SMTP2GO's limit of 50000000");
    }
}
