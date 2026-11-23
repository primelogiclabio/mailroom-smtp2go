package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.InvalidConfigException;
import io.primelogiclab.mailroom.TransportRegistry;
import io.primelogiclab.mailroom.spi.ConfigKey;
import io.primelogiclab.mailroom.spi.MailTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Discovery, config keys and validation of {@link Smtp2GoTransportProvider}. */
class Smtp2GoTransportProviderTest {

    private static Map<String, String> properties(String... keysAndValues) {
        Map<String, String> properties = new HashMap<String, String>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }

    @Test
    void isFoundOnTheClasspath() {
        assertThat(TransportRegistry.discover().providerIds()).contains("smtp2go");
        assertThat(new Smtp2GoTransportProvider().id()).isEqualTo("smtp2go");
    }

    @Test
    void declaresItsKeys() {
        List<String> names = new ArrayList<String>();
        for (ConfigKey key : new Smtp2GoTransportProvider().configKeys()) {
            names.add(key.name());
        }
        assertThat(names).containsExactly("api-key", "region", "connect-timeout", "read-timeout");
        assertThat(Smtp2GoTransportProvider.API_KEY.isRequired()).isTrue();
        assertThat(Smtp2GoTransportProvider.API_KEY.isSecret()).isTrue();
        assertThat(Smtp2GoTransportProvider.REGION.defaultValue()).hasValue("global");
    }

    @Test
    void createsANamedTransportForTheRegion() {
        try (MailTransport transport = TransportRegistry.discover().create("backup", "smtp2go",
                properties("api-key", "api-k", "region", "EU", "read-timeout", "5s"))) {
            assertThat(transport).isInstanceOf(Smtp2GoTransport.class);
            assertThat(transport.name()).isEqualTo("backup");
            assertThat(transport.providerId()).isEqualTo("smtp2go");
            assertThat(transport.toString()).isEqualTo("Smtp2GoTransport[backup, eu]");
        }
    }

    @Test
    void theRegionDefaultsToGlobal() {
        try (MailTransport transport = TransportRegistry.discover().create("backup", "smtp2go",
                properties("api-key", "api-k"))) {
            assertThat(transport.toString()).isEqualTo("Smtp2GoTransport[backup, global]");
        }
    }

    @Test
    void theKeyIsRequired() {
        assertThatThrownBy(() -> TransportRegistry.discover().create("backup", "smtp2go", properties()))
                .isInstanceOfSatisfying(InvalidConfigException.class,
                        e -> assertThat(e.problems()).containsExactly("transport 'backup': 'api-key' is required"));
    }

    @Test
    void listsEveryBadValue() {
        assertThatThrownBy(() -> TransportRegistry.discover().create("backup", "smtp2go",
                properties("api-key", "api-k", "region", "mars", "read-timeout", "0s")))
                .isInstanceOfSatisfying(InvalidConfigException.class, e -> assertThat(e.problems()).containsExactly(
                        "transport 'backup': 'region' must be global, us, eu or au",
                        "transport 'backup': 'read-timeout' must be from 1ms to 2147483647ms"));
    }

    @Test
    void regionsHaveTheirHosts() {
        assertThat(Smtp2GoRegion.GLOBAL.baseUri()).isEqualTo(URI.create("https://api.smtp2go.com/"));
        assertThat(Smtp2GoRegion.US.baseUri()).isEqualTo(URI.create("https://us-api.smtp2go.com/"));
        assertThat(Smtp2GoRegion.EU.baseUri()).isEqualTo(URI.create("https://eu-api.smtp2go.com/"));
        assertThat(Smtp2GoRegion.AU.baseUri()).isEqualTo(URI.create("https://au-api.smtp2go.com/"));
        assertThat(Smtp2GoRegion.of(" Au ")).isEqualTo(Smtp2GoRegion.AU);
        assertThatThrownBy(() -> Smtp2GoRegion.of("mars")).isInstanceOf(IllegalArgumentException.class);
    }
}
