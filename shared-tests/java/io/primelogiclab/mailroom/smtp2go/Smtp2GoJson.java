package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.http.JsonValue;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Decodes a recorded {@code POST /v3/email/send} body back into an email: this transport's statement of how it maps
 * the model, for the TCK's mapping contract. Reply-To and the {@code X-Mailroom-*} headers become fields again;
 * inline parts come back under their filename as the content id.
 */
final class Smtp2GoJson {

    private Smtp2GoJson() {
    }

    static Email decode(byte[] body) {
        JsonValue json = JsonValue.parse(body);
        Email.Builder email = Email.builder().from(text(json.get("sender")));
        for (JsonValue to : json.get("to").elements()) {
            email.to(text(to));
        }
        for (JsonValue cc : json.get("cc").elements()) {
            email.cc(text(cc));
        }
        for (JsonValue bcc : json.get("bcc").elements()) {
            email.bcc(text(bcc));
        }
        if (json.get("subject").asString().isPresent()) {
            email.subject(text(json.get("subject")));
        }
        if (json.get("text_body").asString().isPresent()) {
            email.text(text(json.get("text_body")));
        }
        if (json.get("html_body").asString().isPresent()) {
            email.html(text(json.get("html_body")));
        }
        for (JsonValue header : json.get("custom_headers").elements()) {
            header(email, text(header.get("header")), text(header.get("value")));
        }
        for (JsonValue part : json.get("attachments").elements()) {
            email.attach(Attachment.of(text(part.get("filename")), text(part.get("mimetype")), bytes(part)));
        }
        for (JsonValue part : json.get("inlines").elements()) {
            email.attach(Attachment.inline(text(part.get("filename")), text(part.get("mimetype")), bytes(part)));
        }
        return email.build();
    }

    private static void header(Email.Builder email, String name, String value) {
        if (name.equalsIgnoreCase("Reply-To")) {
            for (String address : addressList(value)) {
                email.replyTo(address);
            }
        } else if (name.equalsIgnoreCase(Smtp2GoRequest.TAGS_HEADER)) {
            for (String tag : value.split(", ")) {
                email.tag(tag);
            }
        } else if (name.equalsIgnoreCase(Smtp2GoRequest.METADATA_HEADER)) {
            for (Map.Entry<String, JsonValue> entry : JsonValue.parse(value).fields().entrySet()) {
                email.metadata(entry.getKey(), text(entry.getValue()));
            }
        } else {
            email.header(name, value);
        }
    }

    /** Splits a header's address list at commas outside quoted display names. */
    static List<String> addressList(String value) {
        List<String> addresses = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                addresses.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addresses.add(current.toString().trim());
        return addresses;
    }

    private static byte[] bytes(JsonValue part) {
        return Base64.getDecoder().decode(text(part.get("fileblob")));
    }

    private static String text(JsonValue value) {
        if (!value.asString().isPresent()) {
            throw new AssertionError("expected a string in the request, but it was " + value.type());
        }
        return value.asString().get();
    }
}
