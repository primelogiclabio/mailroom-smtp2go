package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.primelogiclab.mailroom.Address;
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.FailureKind;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendFailedException;
import io.primelogiclab.mailroom.http.HttpEngine;
import io.primelogiclab.mailroom.http.HttpEngines;
import io.primelogiclab.mailroom.http.HttpRequest;
import io.primelogiclab.mailroom.http.HttpResponse;
import io.primelogiclab.mailroom.http.JsonValue;
import io.primelogiclab.mailroom.http.JsonWriter;
import io.primelogiclab.mailroom.spi.SendContext;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Sends real email through SMTP2GO. Runs only when {@code SMTP2GO_API_KEY}, {@code SMTP2GO_TEST_SENDER} (a verified
 * sender) and {@code SMTP2GO_TEST_RECIPIENT} are all set, as the live workflow sets them from secrets; see the README's
 * "Live smoke test". The key needs {@code /email/send} and {@code /activity/search}.
 *
 * <p>Everything it prints has its addresses masked, since the workflow's logs are public.
 */
@EnabledIfEnvironmentVariable(named = "SMTP2GO_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SMTP2GO_TEST_SENDER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SMTP2GO_TEST_RECIPIENT", matches = ".+")
class Smtp2GoLiveTest {

    private static final Duration DELIVERY_WAIT = Duration.ofMinutes(3);
    private static final long POLL_MILLIS = 5000;
    private static final List<String> FAILED_EVENTS = Arrays.asList("hard-bounced", "soft-bounced", "rejected",
            "spam");
    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>\"',;:()\\[\\]]+@[^\\s<>\"',;:()\\[\\]]+");
    private static final SendContext CONTEXT = SendContext.builder().build();
    private static final String SUBJECT = "[mailroom-smtp2go live test] ";

    private final Smtp2GoRegion region = Smtp2GoRegion.of(env("SMTP2GO_REGION", "global"));
    private final String sender = Address.parse(System.getenv("SMTP2GO_TEST_SENDER")).address();
    private final String recipient = System.getenv("SMTP2GO_TEST_RECIPIENT");
    private final String run = env("GITHUB_RUN_ID", "local");
    private final String set = "Java " + System.getProperty("java.specification.version").replace("1.8", "8") + " set";
    private final String where = System.getenv("GITHUB_RUN_ID") == null ? "a local run"
            : env("GITHUB_SERVER_URL", "https://github.com") + "/" + env("GITHUB_REPOSITORY", "") + "/actions/runs/"
                    + System.getenv("GITHUB_RUN_ID");

    private Smtp2GoTransport transport;
    private HttpEngine engine;

    @BeforeEach
    void start() {
        transport = transport(Secret.env("SMTP2GO_API_KEY"));
        engine = HttpEngines.load();
    }

    @AfterEach
    void stop() {
        transport.close();
        engine.close();
    }

    @Test
    void smtp2goDeliversARealEmail() {
        Email email = Email.builder()
                .from(Address.of("mailroom-smtp2go live test", sender))
                .to(recipient)
                .subject(SUBJECT + set + ", run " + run + " ✓ café")
                .text(explanation("to check delivery") + "\n\nThe ✓ and café check non-ASCII text.")
                .html("<p>" + explanation("to check delivery") + "</p><p>The ✓ and café check non-ASCII text.</p>")
                .header("X-Live-Note", "naïve café ✓")
                .tag("live")
                .metadata("run", run).metadata("set", set).metadata("note", "café")
                .build();

        String id = transport.send(email, CONTEXT).providerMessageId().get();
        log("sent " + id + " on " + set);
        JsonValue delivered = awaitDelivery(id);

        String headers = delivered.get("headers").asString().orElse("");
        for (String name : Arrays.asList("From", "Subject", "X-Live-Note", Smtp2GoRequest.TAGS_HEADER,
                Smtp2GoRequest.METADATA_HEADER)) {
            log("delivered header " + headerLine(headers, name));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SMTP2GO_TEST_TEMPLATE_ID", matches = ".+")
    void aTemplateGoesOutWithItsData() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", "café ✓");
        data.put("run", run);
        data.put("set", set);
        Email email = Email.builder().from(sender).to(recipient)
                .with(Smtp2GoOptions.builder().templateId(System.getenv("SMTP2GO_TEST_TEMPLATE_ID"))
                        .templateData(data).build())
                .build();

        String id = transport.send(email, CONTEXT).providerMessageId().get();
        log("sent template email " + id + " with template_data as a JSON object");
        awaitDelivery(id);
    }

    /** Runs every check before failing, so one surprise doesn't hide the answers after it. */
    @Test
    void errorAnswersAreClassified() {
        Email plain = plain(sender);
        List<String> wrong = new ArrayList<String>();

        expect(wrong, "made-up key", failure("made-up key", "api-00000000000000000000000000000000", plain),
                FailureKind.AUTHENTICATION);

        Email unverified = plain("mailroom-live-unverified@example.com");
        try {
            String id = transport.send(unverified, CONTEXT).providerMessageId().get();
            log("unverified sender: accepted as " + id + "; SMTP2GO didn't check the sender here");
        } catch (SendFailedException e) {
            report("unverified sender", e, System.getenv("SMTP2GO_API_KEY"), unverified);
        }

        String restricted = System.getenv("SMTP2GO_TEST_RESTRICTED_KEY");
        if (restricted == null || restricted.isEmpty()) {
            log("key without /email/send: skipped, SMTP2GO_TEST_RESTRICTED_KEY isn't set");
        } else {
            expect(wrong, "key without /email/send", failure("key without /email/send", restricted, plain),
                    FailureKind.AUTHENTICATION);
        }
        assertThat(wrong).as("answers classified differently than expected").isEmpty();
    }

    private static void expect(List<String> wrong, String what, SendFailedException failure, FailureKind kind) {
        if (failure == null) {
            wrong.add(what + ": SMTP2GO accepted the send");
        } else if (failure.kind() != kind) {
            wrong.add(what + ": " + failure.kind() + ", expected " + kind);
        }
    }

    private Smtp2GoTransport transport(Secret key) {
        return Smtp2GoTransport.builder().name("live").apiKey(key).region(region).build();
    }

    private Email plain(String from) {
        return Email.builder().from(from).to(recipient).subject(SUBJECT + set + ", run " + run + ", error check")
                .text(explanation("to check SMTP2GO's error answers") + " If it arrived, SMTP2GO accepted a send "
                        + "the test expected it to refuse.")
                .build();
    }

    /** Says what the email is, so a test recipient knows it's expected. */
    private String explanation(String why) {
        return "This email was sent by the live smoke test of mailroom-smtp2go (github.com/primelogiclabio/"
                + "mailroom-smtp2go) " + why + " through SMTP2GO, from " + where + ". It needs no action.";
    }

    /** Sends with a key that should fail, and reports what came back: the failure, or null if SMTP2GO took it. */
    private SendFailedException failure(String what, String key, Email email) {
        Smtp2GoTransport failing = transport(Secret.of(key));
        try {
            String id = failing.send(email, CONTEXT).providerMessageId().get();
            log(what + ": accepted as " + id);
            return null;
        } catch (SendFailedException e) {
            report(what, e, key, email);
            return e;
        } finally {
            failing.close();
        }
    }

    /**
     * Logs the failure's kind, status and code, and, when SMTP2GO answered, the field names of its raw answer to the
     * same request, which show where {@code error_code} sits. Never logs the error text, which may repeat addresses.
     */
    private void report(String what, SendFailedException e, String key, Email email) {
        String status = e.statusCode().isPresent() ? String.valueOf(e.statusCode().getAsInt()) : "none";
        StringBuilder line = new StringBuilder(what).append(": ").append(e.kind()).append(", status ").append(status)
                .append(", code ").append(e.providerCode().orElse("none"));
        if (e.statusCode().isPresent()) {
            HttpResponse raw = post("v3/email/send", key, Smtp2GoRequest.body(email));
            if (raw.isSuccessful()) {
                line.append("; the repeated raw request was accepted");
            } else {
                Optional<JsonValue> body = raw.tryJson();
                line.append("; raw ").append(raw.statusCode()).append(" with fields ")
                        .append(body.isPresent() ? shape(body.get(), "") : "(not JSON)");
            }
        }
        log(line.toString());
    }

    /** Waits for SMTP2GO's {@code delivered} event; a bounce or reject, or no delivery in time, fails the test. */
    private JsonValue awaitDelivery(String emailId) {
        long deadline = System.nanoTime() + DELIVERY_WAIT.toNanos();
        List<String> seen = new ArrayList<String>();
        while (true) {
            for (JsonValue event : activity(emailId).get("data").get("events").elements()) {
                String name = event.get("event").asString().orElse("");
                if (name.equals("delivered")) {
                    log("delivered " + emailId + " (" + event.get("date").asString().orElse("") + ")");
                    return event;
                }
                if (FAILED_EVENTS.contains(name)) {
                    fail(emailId + " was " + name + ": " + mask(event.get("smtp_response").asString().orElse("")));
                }
                if (!seen.contains(name)) {
                    seen.add(name);
                }
            }
            if (System.nanoTime() > deadline) {
                fail("no delivered event for " + emailId + " within " + DELIVERY_WAIT.toMinutes() + " minutes; saw "
                        + seen);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + emailId, e);
            }
        }
    }

    private JsonValue activity(String emailId) {
        byte[] body = JsonWriter.object().field("search_email_id", emailId).field("include_headers", true).toBytes();
        HttpResponse response = post("v3/activity/search", System.getenv("SMTP2GO_API_KEY"), body);
        assertThat(response.statusCode()).as("activity search status; the key needs /activity/search")
                .isEqualTo(200);
        return response.json();
    }

    private HttpResponse post(String path, String key, byte[] body) {
        URI uri = URI.create(region.baseUri().toString() + path);
        return engine.execute(HttpRequest.post(uri).header("X-Smtp2go-Api-Key", key)
                .header("Accept", "application/json").json(body).build());
    }

    /** The JSON's field paths, such as {@code request_id, data.error_code=E_..., data.error}, with no other values. */
    static String shape(JsonValue json, String prefix) {
        List<String> paths = new ArrayList<String>();
        for (Map.Entry<String, JsonValue> field : json.fields().entrySet()) {
            String path = prefix + field.getKey();
            JsonValue value = field.getValue();
            if (value.type() == JsonValue.Type.OBJECT) {
                paths.add(shape(value, path + "."));
            } else if (field.getKey().equals("error_code") || field.getKey().equals("fieldname")) {
                paths.add(path + "=" + value.asString().orElse("?"));
            } else {
                paths.add(path);
            }
        }
        return String.join(", ", paths);
    }

    /** The header and its folded continuation lines, unfolded, or {@code Name: (absent)}. */
    static String headerLine(String headers, String name) {
        String[] lines = headers.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                StringBuilder line = new StringBuilder(lines[i]);
                while (i + 1 < lines.length && !lines[i + 1].isEmpty()
                        && Character.isWhitespace(lines[i + 1].charAt(0))) {
                    line.append(' ').append(lines[++i].trim());
                }
                return line.toString();
            }
        }
        return name + ": (absent)";
    }

    static String mask(String text) {
        return ADDRESS.matcher(text).replaceAll("***@***");
    }

    private static void log(String text) {
        System.out.println("[live] " + mask(text));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
