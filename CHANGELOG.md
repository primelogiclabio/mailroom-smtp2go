# Changelog

All notable changes to mailroom-smtp2go are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- SMTP2GO's errors and limits. An API key without the send endpoint enabled
  (`E_ApiResponseCodes.ENDPOINT_PERMISSION_DENIED`, read under `data` or at the top level) is `AUTHENTICATION`, a 200
  that reports the email as failed is `REJECTED`, and a 429 that says nothing about waiting retries after a minute.
  The precheck rejects more than 100 addresses in the To, Cc or Bcc list, a metadata header longer than one line (998
  characters), and an email over 50,000,000 bytes once encoded. Passes the TCK's failure, redaction and precheck
  contracts on both sets.
- `Smtp2GoOptions`, for SMTP2GO templates: a template id and its data, sent as `template_id` and the `template_data`
  object. With a template, the options are essential, so the email goes only through SMTP2GO transports, and they
  supply the content, so the email needs no subject or bodies. Without one, the precheck now rejects an email that has
  no subject or no body.
- `Smtp2GoTransport`, which sends through `POST /v3/email/send` with the key in `X-Smtp2go-Api-Key`, and
  `Smtp2GoTransportProvider` for `type: smtp2go` (keys `api-key`, `region`, `connect-timeout`, `read-timeout`),
  registered for `ServiceLoader`. `Smtp2GoRegion` picks the global, US, EU or AU host. The email's from address,
  recipients, subject, bodies, attachments and inline parts go to SMTP2GO's own fields. Reply-to addresses, custom
  headers, tags (`X-Mailroom-Tags`) and metadata (`X-Mailroom-Metadata`, compact JSON) go to `custom_headers`. SMTP2GO's
  `email_id` is the message id. Passes the TCK's mapping contract on both sets.
- Module skeleton on mailroom-parent 0.3.0 and mailroom 0.1.0-SNAPSHOT: `mailroom-smtp2go` (Java 21 set, tested on
  JDK 21 and 25) and `mailroom-smtp2go-java8` (Java 8 set, tested on JDK 8), each with its `Automatic-Module-Name`
  `io.primelogiclab.mailroom.smtp2go` and a smoke test, plus tests both sets share in `shared-tests/`.
- A sanitized SMTP2GO send response as a test fixture.
- CI and release workflows from mailroom-parent 0.3.0's templates; snapshots deploy from `main`.

[Unreleased]: https://github.com/primelogiclabio/mailroom-smtp2go/commits/main
