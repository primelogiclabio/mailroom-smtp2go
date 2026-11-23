package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.http.JsonWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the JSON body of {@code POST /v3/email/send} from an email. Fields the email doesn't use are left out, so a
 * template send (G-2) can go without a subject or bodies.
 */
final class Smtp2GoRequest {

    /** Carries the email's tags, joined with {@code ", "}. */
    static final String TAGS_HEADER = "X-Mailroom-Tags";

    /** Carries the email's metadata as a compact JSON object, ASCII only. */
    static final String METADATA_HEADER = "X-Mailroom-Metadata";

    private Smtp2GoRequest() {
    }

    /**
     * Returns the request body.
     *
     * @param email an email with a from address
     * @return UTF-8 JSON
     */
    static byte[] body(Email email) {
        JsonWriter json = JsonWriter.object().field("sender", email.from().get().format());
        addresses(json, "to", email.to());
        addresses(json, "cc", email.cc());
        addresses(json, "bcc", email.bcc());
        json.field("subject", email.subject())
                .field("text_body", email.text())
                .field("html_body", email.html());
        headers(json, email);
        parts(json, "attachments", email.attachments(), false);
        parts(json, "inlines", email.attachments(), true);
        return json.toBytes();
    }

    private static void addresses(JsonWriter json, String field, List<Address> addresses) {
        if (!addresses.isEmpty()) {
            json.strings(field, formatted(addresses));
        }
    }

    private static List<String> formatted(List<Address> addresses) {
        List<String> formatted = new ArrayList<String>(addresses.size());
        for (Address address : addresses) {
            formatted.add(address.format());
        }
        return formatted;
    }

    /**
     * Reply-To first (SMTP2GO has no field for it), then the email's own headers in order, then tags and metadata.
     */
    private static void headers(JsonWriter json, Email email) {
        List<String[]> headers = new ArrayList<String[]>();
        if (!email.replyTo().isEmpty()) {
            headers.add(new String[] {"Reply-To", join(formatted(email.replyTo()))});
        }
        for (Map.Entry<String, String> header : email.headers().entrySet()) {
            headers.add(new String[] {header.getKey(), header.getValue()});
        }
        if (!email.tags().isEmpty()) {
            headers.add(new String[] {TAGS_HEADER, join(email.tags())});
        }
        if (!email.metadata().isEmpty()) {
            headers.add(new String[] {METADATA_HEADER, metadata(email.metadata())});
        }
        if (headers.isEmpty()) {
            return;
        }
        json.startArray("custom_headers");
        for (String[] header : headers) {
            json.startObject().field("header", header[0]).field("value", header[1]).end();
        }
        json.end();
    }

    /** The metadata as a compact JSON object, with every character above {@code ~} escaped as {@code \}{@code uXXXX}. */
    static String metadata(Map<String, String> metadata) {
        JsonWriter json = JsonWriter.object();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            json.field(entry.getKey(), entry.getValue());
        }
        return asciiOnly(json.toJson());
    }

    /** Escapes non-ASCII characters; safe on JSON text, since keys are ASCII and the rest sits inside strings. */
    private static String asciiOnly(String json) {
        StringBuilder ascii = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x7f) {
                ascii.append(c);
            } else {
                ascii.append(String.format("\\u%04x", (int) c));
            }
        }
        return ascii.toString();
    }

    private static String join(Iterable<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.toString();
    }

    /**
     * Regular attachments by filename, or inline parts with the content id as the filename, since SMTP2GO's HTML
     * refers to an inline as {@code cid:<filename>}.
     */
    private static void parts(JsonWriter json, String field, List<Attachment> attachments, boolean inline) {
        boolean started = false;
        for (Attachment attachment : attachments) {
            if (attachment.isInline() != inline) {
                continue;
            }
            if (!started) {
                json.startArray(field);
                started = true;
            }
            String filename = inline ? attachment.contentId().get() : attachment.filename().get();
            json.startObject()
                    .field("filename", filename)
                    .base64("fileblob", attachment.content())
                    .field("mimetype", attachment.contentType())
                    .end();
        }
        if (started) {
            json.end();
        }
    }
}
