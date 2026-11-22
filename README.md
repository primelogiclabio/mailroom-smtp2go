# mailroom-smtp2go

Unofficial SMTP2GO API transport for [mailroom](https://github.com/primelogiclabio/mailroom): sends through
`POST /v3/email/send`, so a mailroom app can use SMTP2GO next to its other providers, with routing and failover.

> **Unofficial.** This project is not affiliated with, endorsed by or supported by SMTP2GO. "SMTP2GO" is a trademark
> of its owner and is used here only to say which service the library works with.

> **Status:** early development. The modules are empty skeletons; the transport arrives in the next steps, and there
> is no release yet. Snapshots are published from `main`.

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
is a request or response body, byte for byte as SMTP2GO sent it on 21 November 2026, with no trailing newline, apart
from sanitized values: email ids became `1ExmNN-FixtureMail-NNNN` and request ids `00000000-0000-4000-8000-…`.

| File | What |
| --- | --- |
| `smtp2go/send/send-response.json` | A successful `POST /v3/email/send` response |

## Compatibility

| mailroom-smtp2go | Runs on | Built with |
| --- | --- | --- |
| 0.1.x | Java 21, 25 | Maven 3.9+, JDK 21+ and a JDK 8 toolchain, [mailroom-parent](https://github.com/primelogiclabio/mailroom-parent) 0.3.0, mailroom 0.1.x |
| 0.1.x, `-java8` set | Java 8 | The same |

## License

[Apache License 2.0](LICENSE)
