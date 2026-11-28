package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.Mailroom;
import io.primelogiclab.mailroom.Secret;
import io.primelogiclab.mailroom.spi.ConfigKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README's quick start, run as written (nothing is sent). Keep it in step with the README's "Quick start" and
 * "Templates"; the key table is checked against the provider itself.
 */
class ReadmeExamplesTest {

    private static final String PROPERTIES = "# mailroom.properties\n"
            + "default-from=Example App <no-reply@example.com>\n"
            + "transports.smtp2go.type=smtp2go\n"
            + "transports.smtp2go.region=global\n";

    @TempDir
    Path folder;

    @Test
    void theBuilderExampleBuildsAMailroom() {
        Smtp2GoTransport smtp2go = Smtp2GoTransport.builder()
                .apiKey(Secret.env("SMTP2GO_API_KEY"))
                .region(Smtp2GoRegion.GLOBAL)
                .build();

        try (Mailroom mailroom = Mailroom.builder()
                .defaultFrom("Example App <no-reply@example.com>")
                .transport(smtp2go)
                .build()) {
            Email email = Email.builder()
                    .to("someone@example.com")
                    .subject("Welcome")
                    .text("Thanks for signing up.")
                    .build();

            assertThat(mailroom.via("smtp2go")).isNotNull();
            assertThat(smtp2go.precheck(email.toBuilder().from("no-reply@example.com").build()).passed()).isTrue();
        }
    }

    @Test
    void thePropertiesExampleFindsTheTransport() throws IOException {
        File properties = folder.resolve("mailroom.properties").toFile();
        Files.write(properties.toPath(), PROPERTIES.getBytes(StandardCharsets.UTF_8));

        try (Mailroom mailroom = Mailroom.fromProperties(load(properties, "api-0123456789abcdef0123456789abcdef"))) {
            Email email = Email.builder().to("someone@example.com").subject("Welcome").text("Thanks for signing up.")
                    .build();
            assertThat(mailroom.via("smtp2go")).isNotNull();
            assertThat(email.subject()).hasValue("Welcome");
        }
        Map<String, String> unknownName = load(properties, "api-0123456789abcdef0123456789abcdef");
        unknownName.put("transports.smtp2go.regoin", "eu");
        assertThatThrownBy(() -> Mailroom.fromProperties(unknownName)).isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> Mailroom.fromProperties(load(properties, null)))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("api-key");
    }

    /** The README's loading code, with the key passed in rather than read from the environment. */
    private static Map<String, String> load(File path, String apiKey) throws IOException {
        Properties file = new Properties();
        try (Reader in = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            file.load(in);
        }
        Map<String, String> properties = new HashMap<String, String>();
        for (String name : file.stringPropertyNames()) {
            properties.put(name, file.getProperty(name));
        }
        if (apiKey != null) {
            properties.put("transports.smtp2go.api-key", apiKey);
        }
        return properties;
    }

    @Test
    void theTemplateExampleBuildsAnEmailWithoutContent() {
        Email welcome = Email.builder()
                .to("someone@example.com")
                .with(Smtp2GoOptions.builder()
                        .templateId("welcome")
                        .templateData("name", "Ann")
                        .build())
                .build();

        assertThat(welcome.subject()).isEmpty();
        assertThat(welcome.extension(Smtp2GoOptions.class).get().templateData()).containsEntry("name", "Ann");
    }

    @Test
    void theKeyTableMatchesTheProvider() throws IOException {
        Map<String, String> table = keyTable(new String(Files.readAllBytes(Paths.get("..", "README.md")),
                StandardCharsets.UTF_8));
        Map<String, String> provider = new LinkedHashMap<String, String>();
        for (ConfigKey key : new Smtp2GoTransportProvider().configKeys()) {
            provider.put(key.name(), key.isRequired() ? "required" : "`" + key.defaultValue().orElse("") + "`");
        }

        assertThat(table).isEqualTo(provider);
    }

    /** The README's "Key under transports.<name>." table, key to default, without the type row. */
    private static Map<String, String> keyTable(String readme) {
        List<String> lines = Arrays.asList(readme.split("\r?\n"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("| Key under `transports.<name>.`")) {
                start = i + 2;
            }
        }
        assertThat(start).as("the README's key table").isPositive();
        Map<String, String> table = new LinkedHashMap<String, String>();
        for (int i = start; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            String[] cells = lines.get(i).split("\\|");
            String key = cells[1].trim().replace("`", "");
            if (!key.equals("type")) {
                table.put(key, cells[2].trim());
            }
        }
        return table;
    }
}
