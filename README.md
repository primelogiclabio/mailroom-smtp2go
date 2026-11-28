# mailroom-smtp2go

Unofficial SMTP2GO API transport for [mailroom](https://github.com/primelogiclabio/mailroom): sends through
`POST /v3/email/send`, so a mailroom app can use SMTP2GO next to its other providers, with routing and failover.

> **Unofficial.** This project is not affiliated with, endorsed by or supported by SMTP2GO. "SMTP2GO" is a trademark
> of its owner and is used here only to say which service the library works with.

> **Status:** early development. The transport sends; SMTP2GO's own error codes, size and recipient limits, and
> template options are still to come, and there is no release yet. Snapshots are published from `main`.

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
