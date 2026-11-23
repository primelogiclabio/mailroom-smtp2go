/**
 * The SMTP2GO API transport for mailroom: {@link io.primelogiclab.mailroom.smtp2go.Smtp2GoTransport} sends through
 * {@code POST /v3/email/send}, and {@link io.primelogiclab.mailroom.smtp2go.Smtp2GoTransportProvider} creates it from
 * configuration ({@code type: smtp2go}, keys {@code api-key}, {@code region}, {@code connect-timeout} and
 * {@code read-timeout}).
 *
 * <p>How an email maps: the from address becomes {@code sender}; To, Cc and Bcc go to {@code to}, {@code cc} and
 * {@code bcc}; the subject and bodies to {@code subject}, {@code text_body} and {@code html_body}. Reply-to
 * addresses, the email's own headers, its tags ({@code X-Mailroom-Tags: a, b}) and its metadata
 * ({@code X-Mailroom-Metadata}, a compact JSON object) go to {@code custom_headers}. Attachments go to
 * {@code attachments}, and inline parts to {@code inlines} under their content id, since SMTP2GO's HTML refers to an
 * inline part by its filename. SMTP2GO's {@code email_id} becomes the result's message id.
 *
 * <p>Unofficial: not affiliated with, endorsed by or supported by SMTP2GO.
 */
package io.primelogiclab.mailroom.smtp2go;
