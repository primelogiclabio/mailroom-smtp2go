package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.http.JsonValue;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The request body {@link Smtp2GoRequest} builds, field by field. */
class Smtp2GoRequestTest {

    private static Email.Builder minimal() {
        return Email.builder().from("Sender <sender@example.com>").to("to@example.com").subject("Hi").text("Hello");
    }

    private static String json(Email email) {
        return new String(Smtp2GoRequest.body(email), StandardCharsets.UTF_8);
    }

    private static JsonValue parsed(Email email) {
        return JsonValue.parse(Smtp2GoRequest.body(email));
    }

    @Test
    void aMinimalEmailHasOnlyTheFieldsItUses() {
        assertThat(json(minimal().build())).isEqualTo(
                "{\"sender\":\"Sender <sender@example.com>\",\"to\":[\"to@example.com\"],\"subject\":\"Hi\","
                        + "\"text_body\":\"Hello\"}");
    }

    @Test
    void recipientsKeepTheirDisplayNames() {
        JsonValue body = parsed(minimal().to("Two <two@example.com>").cc("\"Last, First\" <cc@example.com>")
                .bcc("bcc@example.com").build());

        assertThat(strings(body.get("to"))).containsExactly("to@example.com", "Two <two@example.com>");
        assertThat(strings(body.get("cc"))).containsExactly("\"Last, First\" <cc@example.com>");
        assertThat(strings(body.get("bcc"))).containsExactly("bcc@example.com");
    }

    @Test
    void unicodeTravelsAsUtf8() {
        JsonValue body = parsed(minimal().from("Zoë <zoe@example.com>").subject("Grüße 👋").text("你好 😀").build());

        assertThat(body.get("sender").asString()).hasValue("Zoë <zoe@example.com>");
        assertThat(body.get("subject").asString()).hasValue("Grüße 👋");
        assertThat(body.get("text_body").asString()).hasValue("你好 😀");
    }

    @Test
    void bothBodiesGoInTheirOwnFields() {
        JsonValue body = parsed(minimal().html("<p>Hello</p>").build());

        assertThat(body.get("text_body").asString()).hasValue("Hello");
        assertThat(body.get("html_body").asString()).hasValue("<p>Hello</p>");
    }

    @Test
    void replyToHeadersTagsAndMetadataBecomeCustomHeadersInThatOrder() {
        JsonValue body = parsed(minimal()
                .replyTo("\"Last, First\" <reply@example.com>", "other@example.com")
                .header("X-Trace", "t-1")
                .header("In-Reply-To", "<abc@example.com>")
                .tag("welcome", "tck")
                .metadata("order_id", "A-1042")
                .build());

        assertThat(headers(body)).containsExactly(
                "Reply-To: \"Last, First\" <reply@example.com>, other@example.com",
                "X-Trace: t-1",
                "In-Reply-To: <abc@example.com>",
                "X-Mailroom-Tags: welcome, tck",
                "X-Mailroom-Metadata: {\"order_id\":\"A-1042\"}");
    }

    @Test
    void metadataIsCompactJsonWithNonAsciiEscaped() {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("order_id", "A-1042");
        metadata.put("note", "Zoë \"quoted\" 😀");

        assertThat(Smtp2GoRequest.metadata(metadata))
                .isEqualTo("{\"order_id\":\"A-1042\",\"note\":\"Zo\\u00eb \\\"quoted\\\" \\ud83d\\ude00\"}");
        assertThat(JsonValue.parse(Smtp2GoRequest.metadata(metadata)).get("note").asString())
                .hasValue("Zoë \"quoted\" 😀");
    }

    @Test
    void noCustomHeadersFieldWithoutHeaders() {
        assertThat(parsed(minimal().build()).get("custom_headers").isMissing()).isTrue();
    }

    @Test
    void attachmentsCarryFilenameBase64AndMimetype() {
        byte[] bytes = {0, 1, 2, (byte) 0xff};
        JsonValue body = parsed(minimal().attach(Attachment.of("bytes.bin", "application/octet-stream", bytes))
                .build());

        JsonValue part = body.get("attachments").get(0);
        assertThat(body.get("attachments").size()).isEqualTo(1);
        assertThat(part.get("filename").asString()).hasValue("bytes.bin");
        assertThat(part.get("fileblob").asString()).hasValue(Base64.getEncoder().encodeToString(bytes));
        assertThat(part.get("mimetype").asString()).hasValue("application/octet-stream");
        assertThat(body.get("inlines").isMissing()).isTrue();
    }

    @Test
    void inlinePartsGoUnderTheirContentId() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        JsonValue body = parsed(minimal().html("<img src=\"cid:logo\">")
                .attach(Attachment.inline("logo", "logo.png", "image/png", png))
                .attach(Attachment.of("notes.txt", "text/plain", "n".getBytes(StandardCharsets.UTF_8)))
                .build());

        JsonValue inline = body.get("inlines").get(0);
        assertThat(body.get("inlines").size()).isEqualTo(1);
        assertThat(inline.get("filename").asString()).hasValue("logo");
        assertThat(inline.get("fileblob").asString()).hasValue(Base64.getEncoder().encodeToString(png));
        assertThat(inline.get("mimetype").asString()).hasValue("image/png");
        assertThat(body.get("attachments").size()).isEqualTo(1);
        assertThat(body.get("attachments").get(0).get("filename").asString()).hasValue("notes.txt");
    }

    @Test
    void aTemplateEmailSendsTheTemplateInsteadOfASubjectOrBodies() {
        JsonValue body = parsed(Email.builder().from("sender@example.com").to("to@example.com")
                .with(Smtp2GoOptions.builder().templateId("welcome").templateData("username", "Ann").build())
                .build());

        assertThat(body.fields().keySet()).containsExactly("sender", "to", "template_id", "template_data");
        assertThat(body.get("template_id").asString()).hasValue("welcome");
        assertThat(body.get("template_data").get("username").asString()).hasValue("Ann");
    }

    @Test
    void templateDataIsAJsonObjectWithNestedValues() {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("name", "deluxe");
        plan.put("seats", 5);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("username", "Zoë 👋");
        data.put("trial", true);
        data.put("credit", new BigDecimal("12.50"));
        data.put("plan", plan);
        data.put("tags", Arrays.asList("new", "eu"));
        data.put("coupon", null);
        Email email = Email.builder().from("sender@example.com").to("to@example.com")
                .with(Smtp2GoOptions.builder().templateId("welcome").templateData(data).build()).build();

        assertThat(json(email)).endsWith("\"template_id\":\"welcome\",\"template_data\":{\"username\":\"Zoë 👋\","
                + "\"trial\":true,\"credit\":12.50,\"plan\":{\"name\":\"deluxe\",\"seats\":5},"
                + "\"tags\":[\"new\",\"eu\"],\"coupon\":null}}");
    }

    @Test
    void aTemplateWithoutDataSendsNoTemplateData() {
        JsonValue body = parsed(Email.builder().from("sender@example.com").to("to@example.com")
                .with(Smtp2GoOptions.builder().templateId("welcome").build()).build());

        assertThat(body.fields().keySet()).containsExactly("sender", "to", "template_id");
    }

    @Test
    void aSubjectAndBodiesStillGoAlongWithATemplate() {
        JsonValue body = parsed(minimal().with(Smtp2GoOptions.builder().templateId("welcome").build()).build());

        assertThat(body.fields().keySet()).containsExactly("sender", "to", "subject", "text_body", "template_id");
    }

    @Test
    void optionsWithoutATemplateAddNothing() {
        assertThat(json(minimal().with(Smtp2GoOptions.builder().build()).build())).isEqualTo(json(minimal().build()));
    }

    private static List<String> strings(JsonValue array) {
        List<String> strings = new java.util.ArrayList<String>();
        for (JsonValue element : array.elements()) {
            strings.add(element.asString().get());
        }
        return strings;
    }

    private static List<String> headers(JsonValue body) {
        List<String> headers = new java.util.ArrayList<String>();
        for (JsonValue header : body.get("custom_headers").elements()) {
            headers.add(header.get("header").asString().get() + ": " + header.get("value").asString().get());
        }
        return headers;
    }
}
