package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.http.JsonWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the JSON body of {@code POST /v3/email/send} from an email. Fields the email doesn't use are left out, so a
 * template send can go without a subject or bodies. An {@link Smtp2GoOptions} template adds {@code template_id}, and
 * {@code template_data} as a JSON object.
 */
final class Smtp2GoRequest {

    /** Carries the email's tags, joined with {@code ", "}. */
    static final String TAGS_HEADER = "X-Mailroom-Tags";

    /** Carries the email's metadata as a compact JSON object, ASCII only. */
    static final String METADATA_HEADER = "X-Mailroom-Metadata";

    private record Header(String name, String value) {
    }

    private Smtp2GoRequest() {
    }

    /**
     * Returns the request body.
     *
     * @param email an email with a from address
     * @return UTF-8 JSON
     */
    static byte[] body(Email email) {
        JsonWriter json = JsonWriter.object().field("sender", email.from().orElseThrow().format());
        addresses(json, "to", email.to());
        addresses(json, "cc", email.cc());
        addresses(json, "bcc", email.bcc());
        json.field("subject", email.subject())
                .field("text_body", email.text())
                .field("html_body", email.html());
        template(json, email);
        headers(json, email);
        parts(json, "attachments", email.attachments(), false);
        parts(json, "inlines", email.attachments(), true);
        return json.toBytes();
    }

    /**
     * Returns the id of the email's {@link Smtp2GoOptions} template.
     *
     * @param email the email
     * @return the template id, if the email has one
     */
    static Optional<String> templateId(Email email) {
        return email.extension(Smtp2GoOptions.class).flatMap(Smtp2GoOptions::templateId);
    }

    /** The template id, and its data unless there is none; SMTP2GO then ignores the subject and bodies. */
    private static void template(JsonWriter json, Email email) {
        email.extension(Smtp2GoOptions.class).ifPresent(options -> options.templateId().ifPresent(id -> {
            json.field("template_id", id);
            if (!options.templateData().isEmpty()) {
                json.value("template_data", options.templateData());
            }
        }));
    }

    private static void addresses(JsonWriter json, String field, List<Address> addresses) {
        if (!addresses.isEmpty()) {
            json.strings(field, formatted(addresses));
        }
    }

    private static List<String> formatted(List<Address> addresses) {
        return addresses.stream().map(Address::format).toList();
    }

    /**
     * Reply-To first (SMTP2GO has no field for it), then the email's own headers in order, then tags and metadata.
     */
    private static void headers(JsonWriter json, Email email) {
        List<Header> headers = new ArrayList<>();
        if (!email.replyTo().isEmpty()) {
            headers.add(new Header("Reply-To", String.join(", ", formatted(email.replyTo()))));
        }
        email.headers().forEach((name, value) -> headers.add(new Header(name, value)));
        if (!email.tags().isEmpty()) {
            headers.add(new Header(TAGS_HEADER, String.join(", ", email.tags())));
        }
        if (!email.metadata().isEmpty()) {
            headers.add(new Header(METADATA_HEADER, metadata(email.metadata())));
        }
        if (headers.isEmpty()) {
            return;
        }
        json.startArray("custom_headers");
        for (Header header : headers) {
            json.startObject().field("header", header.name()).field("value", header.value()).end();
        }
        json.end();
    }

    /** The metadata as a compact JSON object, with every character above {@code ~} escaped as {@code \}{@code uXXXX}. */
    static String metadata(Map<String, String> metadata) {
        JsonWriter json = JsonWriter.object();
        metadata.forEach(json::field);
        return asciiOnly(json.toJson());
    }

    /** Escapes non-ASCII characters; safe on JSON text, since keys are ASCII and the rest sits inside strings. */
    private static String asciiOnly(String json) {
        StringBuilder ascii = new StringBuilder(json.length());
        json.chars().forEach(c -> {
            if (c < 0x7f) {
                ascii.append((char) c);
            } else {
                ascii.append("\\u%04x".formatted(c));
            }
        });
        return ascii.toString();
    }

    /**
     * Regular attachments by filename, or inline parts with the content id as the filename, since SMTP2GO's HTML
     * refers to an inline as {@code cid:<filename>}.
     */
    private static void parts(JsonWriter json, String field, List<Attachment> attachments, boolean inline) {
        List<Attachment> parts = attachments.stream().filter(a -> a.isInline() == inline).toList();
        if (parts.isEmpty()) {
            return;
        }
        json.startArray(field);
        for (Attachment part : parts) {
            String filename = inline ? part.contentId().orElseThrow() : part.filename().orElseThrow();
            json.startObject()
                    .field("filename", filename)
                    .base64("fileblob", part.content())
                    .field("mimetype", part.contentType())
                    .end();
        }
        json.end();
    }
}
