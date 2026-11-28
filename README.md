# mailroom-smtp2go

Unofficial SMTP2GO API transport for [mailroom](https://github.com/primelogiclabio/mailroom): sends through
`POST /v3/email/send`, so a mailroom app can use SMTP2GO next to its other providers, with routing and failover.

> **Unofficial.** This project is not affiliated with, endorsed by or supported by SMTP2GO. "SMTP2GO" is a trademark
> of its owner and is used here only to say which service the library works with.

> **Status:** complete for mailroom's first phase (sending, SMTP2GO templates, SMTP2GO's errors and limits, and a
> live smoke test), but not released yet. Snapshots are published from `main`.

## Quick start

### 1. Add the dependency

Pick the set by the Java your app runs on: `mailroom-smtp2go` on Java 21 or newer, `mailroom-smtp2go-java8` on Java 8
to 20. It brings `mailroom-core` and `mailroom-http` of the same set with it. The artifacts come from
`repo.primelogiclab.io`; add its repositories as shown in [Getting the snapshots](#getting-the-snapshots).

```xml
<dependency>
    <groupId>io.primelogiclab.mailroom</groupId>
    <artifactId>mailroom-smtp2go</artifactId>   <!-- or mailroom-smtp2go-java8 -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Set up SMTP2GO

- **A verified sender.** SMTP2GO only sends from a verified sender domain or single sender address. An email from any
  other address fails as `REJECTED`.
- **An API key** with the `/email/send` endpoint enabled (in the SMTP2GO app, under API Keys). Keep it out of your
  code and config files, for example in an environment variable such as `SMTP2GO_API_KEY`. A key SMTP2GO doesn't
  know only shows when you send: the send fails as `AUTHENTICATION`.
- **The region** your account uses: `global` (the default), `us`, `eu` or `au`.

### 3. Send an email

The same code works on both sets; it's written in Java 8 syntax.

```java
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.SendResult;
import io.primelogiclab.mailroom.smtp2go.Smtp2GoRegion;
import io.primelogiclab.mailroom.smtp2go.Smtp2GoTransport;

Smtp2GoTransport smtp2go = Smtp2GoTransport.builder()
        .apiKey(Secret.env("SMTP2GO_API_KEY"))   // read for each request, never logged
        .region(Smtp2GoRegion.GLOBAL)            // or US, EU, AU
        .build();

try (Mailroom mailroom = Mailroom.builder()
        .defaultFrom("Example App <no-reply@example.com>")   // a sender verified at SMTP2GO
        .transport(smtp2go)                                   // named "smtp2go"
        .build()) {
    Email email = Email.builder()
            .to("someone@example.com")
            .subject("Welcome")
            .text("Thanks for signing up.")
            .build();
    SendResult result = mailroom.send(email);
    String emailId = result.providerMessageId().orElse(null);   // SMTP2GO's email_id
}
```

`send` blocks until SMTP2GO takes the email. If it doesn't, `send` throws
`io.primelogiclab.mailroom.SendFailedException` (unchecked), whose `kind()` is a `FailureKind` (same package) that
says what went wrong and whether retrying makes sense; see [Errors and limits](#errors-and-limits). `Secret.env`
reads the variable at each send, so a missing variable fails the send as `AUTHENTICATION`. Closing the mailroom
closes the transport. Failed sends are also logged through `java.util.logging`, on the logger
`io.primelogiclab.mailroom`.

### Or configure it with properties

The transport registers itself for `type=smtp2go`, so `Mailroom.fromProperties` finds it on the classpath.

```properties
# mailroom.properties
default-from=Example App <no-reply@example.com>
transports.smtp2go.type=smtp2go
transports.smtp2go.region=global
```

```java
import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.Mailroom;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

// in a method that throws IOException; the file is read from the working directory
Properties file = new Properties();
try (Reader in = new InputStreamReader(new FileInputStream("mailroom.properties"), StandardCharsets.UTF_8)) {
    file.load(in);
}
Map<String, String> properties = new HashMap<String, String>();
for (String name : file.stringPropertyNames()) {
    properties.put(name, file.getProperty(name));
}
String apiKey = System.getenv("SMTP2GO_API_KEY");   // the key stays out of the file
if (apiKey != null) {
    properties.put("transports.smtp2go.api-key", apiKey);
}

try (Mailroom mailroom = Mailroom.fromProperties(properties)) {
    Email email = Email.builder().to("someone@example.com").subject("Welcome").text("Thanks for signing up.").build();
    mailroom.send(email);
}
```

`fromProperties` checks the configuration at once: a missing `api-key`, or a property name it doesn't know, throws
`io.primelogiclab.mailroom.InvalidConfigException` listing every problem. To read the file from the classpath, such as
`src/main/resources`, open it with `getClass().getResourceAsStream("/mailroom.properties")` instead.

| Key under `transports.<name>.` | Default | Meaning |
| --- | --- | --- |
| `type` | | `smtp2go` |
| `api-key` | required | API key with `/email/send` enabled |
| `region` | `global` | `global`, `us`, `eu` or `au` |
| `connect-timeout` | `10s` | How long to wait for the connection |
| `read-timeout` | `30s` | How long to wait for SMTP2GO's answer |

The mailroom-wide keys, such as `routing.default` for several transports, are in
[mailroom's README](https://github.com/primelogiclabio/mailroom#using-it).

## What it sends

The from address becomes SMTP2GO's `sender`, and To, Cc and Bcc go to `to`, `cc` and `bcc`, with display names. The
subject and bodies go to `subject`, `text_body` and `html_body`, and attachments go to `attachments`. Inline images go
to `inlines` under their content id, since SMTP2GO's HTML refers to an inline image as `cid:<filename>`.

Everything else goes in `custom_headers`, in this order:

- reply-to addresses, as a `Reply-To` header;
- the email's own headers;
- its tags, as `X-Mailroom-Tags: a, b`;
- its metadata, as `X-Mailroom-Metadata: {"key":"value"}`, a compact JSON object with non-ASCII escaped.

SMTP2GO's `email_id` becomes the send result's message id. The transport sets no `Message-ID` header, so the one
SMTP2GO writes is `<E{email_id}@message-id.smtpcorp.com>`.

## Templates

To send a template stored at SMTP2GO, attach `Smtp2GoOptions` with the template's id and the values it's filled in
with. The template supplies the subject and bodies, so the email needs none; any it has, SMTP2GO ignores.

```java
Email welcome = Email.builder()
        .to("someone@example.com")
        .with(Smtp2GoOptions.builder()
                .templateId("welcome")           // the template's id at SMTP2GO
                .templateData("name", "Ann")     // fills {{ name }}
                .build())
        .build();
```

The data goes as the `template_data` object; values may be text, numbers, booleans, null, and maps and lists of
those. An email with a template only goes through SMTP2GO transports: if none can take it, `send` throws
`NoEligibleTransportException` instead of failing over to a provider that would send it empty.

## Errors and limits

Before sending, the transport's precheck rejects an email SMTP2GO would refuse, so the router can try another
transport instead:

- more than 100 addresses in the To, Cc or Bcc list;
- more than 50,000,000 bytes once encoded (attachments grow by a third in base64);
- metadata whose `X-Mailroom-Metadata` header would be longer than one line (998 characters);
- no from address or no To recipient, or no subject or body without a template.

When SMTP2GO refuses an email, `SendFailedException.kind()` says what happened, and `providerCode()` holds SMTP2GO's
`error_code`; the error text, which can repeat addresses, is never kept.

| SMTP2GO answers | `kind()` | What mailroom does |
| --- | --- | --- |
| 401 or 403, a 400 `API_EXCEPTION` for an unknown key, a 400 `ENDPOINT_PERMISSION_DENIED` | `AUTHENTICATION` | Fails over |
| 200 that reports the email failed (an unverified sender, for one), 402, 404 | `REJECTED` | Fails over |
| Other 400s | `INVALID_MESSAGE` | Stops |
| 429 | `RATE_LIMITED`, retry after its `Retry-After` or 1 minute | Fails over |
| Other 5xx | `UNAVAILABLE` | Retries once, then fails over |
| 504, a timeout after sending, a 200 without an `email_id` | `UNKNOWN_OUTCOME` | Stops: the email may have gone out |

## Modules

Like every mailroom module, the transport comes as two sets of jars with the same version and API: one for Java 21
apps and one for Java 8 apps. Use the set that matches mailroom's own: each set depends only on its own mailroom
artifacts.

| Artifact | Set | Depends on |
| --- | --- | --- |
| `mailroom-smtp2go` | Java 21 | `mailroom-core`, `mailroom-http` |
| `mailroom-smtp2go-java8` | Java 8 | `mailroom-core-java8`, `mailroom-http-java8` |

## Getting the snapshots

Artifacts are published to `https://repo.primelogiclab.io`, which can be read without signing in. Add:

```xml
<repositories>
    <repository>
        <id>primelogiclab-releases</id>
        <url>https://repo.primelogiclab.io/releases</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
    <repository>
        <id>primelogiclab-snapshots</id>
        <url>https://repo.primelogiclab.io/snapshots</url>
        <releases><enabled>false</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

Coordinates: groupId `io.primelogiclab.mailroom`, artifactIds as above, version `0.1.0-SNAPSHOT`.

## Building

Requires Maven 3.9 or newer (or the included wrapper) and JDK 21 or newer to build:

```
./mvnw verify
```

`mailroom-smtp2go-java8` has a `java8-set` file, which tells
[mailroom-parent](https://github.com/primelogiclabio/mailroom-parent) 0.3.0 to build it for Java 8 and run its tests
on JDK 8. Each set is tested on its own JDKs: the Java 21 set on 21 and 25, the Java 8 set on 8 only, so a build that
runs the tests needs JDK 8 in `~/.m2/toolchains.xml` (see
[mailroom-parent](https://github.com/primelogiclabio/mailroom-parent#running-the-tests-on-another-jdk)). To test on
one JDK, pass its version, which skips the other set's tests:

```
./mvnw verify -Dtest.jdk=8
./mvnw verify -Dtest.jdk=25
```

Tests both sets share live in `shared-tests/java`; tests of one set's implementation stay in its module. CI runs the
Java 8 set's tests on JDK 8 and the Java 21 set's on JDK 21 and 25.

### Test fixtures

`shared-tests/resources/smtp2go/` holds real SMTP2GO payloads, loaded as the test resource `smtp2go/<path>`. Each file
is a request or response body, byte for byte as SMTP2GO sent it on the date given, with no trailing newline, apart
from sanitized values: email ids became `1ExmNN-FixtureMail-NNNN` and request ids `00000000-0000-4000-8000-…`.

| File | What |
| --- | --- |
| `smtp2go/send/send-response.json` | A successful `POST /v3/email/send` response, 21 November 2026 |
| `smtp2go/errors/unknown-api-key.json` | The 400 (`API_EXCEPTION`) for a key SMTP2GO doesn't know, 28 November 2026 |
| `smtp2go/errors/missing-api-key.json` | The 403 (`INVALID_IN_PAYLOAD`) for a request without a key, 28 November 2026 |

### Live smoke test

`Smtp2GoLiveTest` sends real email through SMTP2GO. It runs only when `SMTP2GO_API_KEY`, `SMTP2GO_TEST_SENDER` and
`SMTP2GO_TEST_RECIPIENT` are all set, so a plain `./mvnw verify` skips it. Each run, on each set:

- sends one email to the test recipient, from `mailroom-smtp2go live test` with a subject starting
  `[mailroom-smtp2go live test]` and a body that says what it is and links the run, with non-ASCII in the subject and
  a header; then waits up to 3 minutes for the Activity API to report it `delivered`, and prints the delivered headers;
- sends a template email when `SMTP2GO_TEST_TEMPLATE_ID` is set, with `{"name": "café ✓"}` as its data;
- checks SMTP2GO's answers to a made-up key, an unverified sender and, when `SMTP2GO_TEST_RESTRICTED_KEY` is set, a key
  without `/email/send`.

What it prints has every address masked. The key needs `/email/send` and `/activity/search` enabled, and the sender
must be verified in SMTP2GO. `SMTP2GO_REGION` picks `global` (the default), `us`, `eu` or `au`.

The `live` workflow runs it on JDK 21 and JDK 8, by hand from the Actions tab or when a pull request from this
repository gets the `live` label. It reads the variables above from repository secrets of the same names, and
`SMTP2GO_REGION` from a repository variable. To run it locally:

```powershell
$env:SMTP2GO_API_KEY = '<key>'
$env:SMTP2GO_TEST_SENDER = 'sender@your-verified-domain'
$env:SMTP2GO_TEST_RECIPIENT = 'you@example.com'
./mvnw verify -Dtest.jdk=21 -Dtest=Smtp2GoLiveTest '-Dsurefire.failIfNoSpecifiedTests=false'
```

## Compatibility

| mailroom-smtp2go | Runs on | Built with |
| --- | --- | --- |
| 0.1.x | Java 21, 25 | Maven 3.9+, JDK 21+ and a JDK 8 toolchain, [mailroom-parent](https://github.com/primelogiclabio/mailroom-parent) 0.3.0, mailroom 0.1.x |
| 0.1.x, `-java8` set | Java 8 | The same |

## License

[Apache License 2.0](LICENSE)
