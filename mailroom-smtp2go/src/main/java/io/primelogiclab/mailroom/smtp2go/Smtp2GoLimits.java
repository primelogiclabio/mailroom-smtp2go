package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Attachment;
import io.primelogiclab.mailroom.Email;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * SMTP2GO's documented limits for {@code POST /v3/email/send}, and the checks against them. Limits change, so every
 * number the transport relies on lives here.
 */
final class Smtp2GoLimits {

    /** SMTP2GO takes "up to 100" addresses in each of {@code to}, {@code cc} and {@code bcc}. */
    static final int MAX_RECIPIENTS_PER_LIST = 100;

    /**
     * The API's 50 MB limit, "including content, attachments and headers", read as 50,000,000 bytes of the encoded
     * email, so the precheck rejects too early rather than too late.
     */
    static final long MAX_MESSAGE_SIZE = 50_000_000L;

    /** The longest line RFC 5322 allows, without its CRLF; the metadata header has no point to fold it at. */
    static final int MAX_HEADER_LINE = 998;

    /** How long SMTP2GO pauses a client that sent too many requests: "no less than 1 minute". */
    static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(1);

    private static final int BASE64_LINE = 76;

    private Smtp2GoLimits() {
    }

    /**
     * Tells what about the email is over a limit: a recipient list, the metadata header's line, or the size.
     *
     * @param email the email
     * @return the problem, naming no address; empty if the email is within every limit
     */
    static Optional<String> problem(Email email) {
        Optional<String> list = list("To", email.to())
                .or(() -> list("Cc", email.cc()))
                .or(() -> list("Bcc", email.bcc()));
        if (list.isPresent()) {
            return list;
        }
        int metadataLine = metadataLine(email);
        if (metadataLine > MAX_HEADER_LINE) {
            return Optional.of("the metadata makes a " + metadataLine + "-character " + Smtp2GoRequest.METADATA_HEADER
                    + " header line, over the " + MAX_HEADER_LINE + " one line can hold");
        }
        long size = estimatedSize(email);
        if (size > MAX_MESSAGE_SIZE) {
            return Optional.of("the email is about " + size + " bytes once encoded, over SMTP2GO's limit of "
                    + MAX_MESSAGE_SIZE);
        }
        return Optional.empty();
    }

    private static Optional<String> list(String field, List<Address> addresses) {
        if (addresses.size() <= MAX_RECIPIENTS_PER_LIST) {
            return Optional.empty();
        }
        return Optional.of("SMTP2GO takes at most " + MAX_RECIPIENTS_PER_LIST + " " + field
                + " recipients; this email has " + addresses.size());
    }

    /** The length of the {@code X-Mailroom-Metadata} line, or 0 without metadata. */
    static int metadataLine(Email email) {
        if (email.metadata().isEmpty()) {
            return 0;
        }
        return Smtp2GoRequest.METADATA_HEADER.length() + 2 + Smtp2GoRequest.metadata(email.metadata()).length();
    }

    /**
     * Estimates the size of the email SMTP2GO builds: the header lines, the UTF-8 bodies, and each attachment and
     * inline part in base64 with a line break every 76 characters.
     *
     * @param email the email
     * @return the estimate in bytes
     */
    static long estimatedSize(Email email) {
        long size = 0;
        if (email.from().isPresent()) {
            size += line("From", email.from().get().format());
        }
        size += addressLine("To", email.to()) + addressLine("Cc", email.cc())
                + addressLine("Reply-To", email.replyTo());
        if (email.subject().isPresent()) {
            size += line("Subject", email.subject().get());
        }
        for (var header : email.headers().entrySet()) {
            size += line(header.getKey(), header.getValue());
        }
        if (!email.tags().isEmpty()) {
            size += line(Smtp2GoRequest.TAGS_HEADER, String.join(", ", email.tags()));
        }
        int metadataLine = metadataLine(email);
        if (metadataLine > 0) {
            size += metadataLine + 2;
        }
        size += email.text().map(Smtp2GoLimits::utf8Length).orElse(0L);
        size += email.html().map(Smtp2GoLimits::utf8Length).orElse(0L);
        for (Attachment attachment : email.attachments()) {
            size += base64Size(attachment.size());
        }
        return size;
    }

    /** The size of {@code bytes} in base64, with a CRLF after every 76 characters and after the last line. */
    static long base64Size(long bytes) {
        long encoded = (bytes + 2) / 3 * 4;
        long lines = (encoded + BASE64_LINE - 1) / BASE64_LINE;
        return encoded + 2 * lines;
    }

    private static long addressLine(String name, List<Address> addresses) {
        if (addresses.isEmpty()) {
            return 0;
        }
        return line(name, String.join(", ", addresses.stream().map(Address::format).toList()));
    }

    /** {@code Name: value} and its CRLF. */
    private static long line(String name, String value) {
        return utf8Length(name) + 2 + utf8Length(value) + 2;
    }

    /** The UTF-8 length of the text, without encoding it. */
    static long utf8Length(String text) {
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isSurrogate(c)) {
                length += 2;
            } else {
                length += 3;
            }
        }
        return length;
    }
}
