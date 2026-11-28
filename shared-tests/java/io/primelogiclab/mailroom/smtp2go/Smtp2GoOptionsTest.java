package io.primelogiclab.mailroom.smtp2go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.primelogiclab.mailroom.Email;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link Smtp2GoOptions}: when it pins and supplies an email, and what its builder takes. */
class Smtp2GoOptionsTest {

    private static Smtp2GoOptions.Builder welcome() {
        return Smtp2GoOptions.builder().templateId("welcome");
    }

    @Test
    void aTemplateIsEssentialAndSuppliesTheContent() {
        Smtp2GoOptions options = welcome().build();

        assertThat(options.templateId()).hasValue("welcome");
        assertThat(options.templateData()).isEmpty();
        assertThat(options.essential()).isTrue();
        assertThat(options.providesContent()).isTrue();
    }

    @Test
    void aTemplateEmailNeedsNoSubjectOrBodies() {
        Smtp2GoOptions options = welcome().build();

        Email email = Email.builder().to("to@example.com").with(options).build();

        assertThat(email.subject()).isEmpty();
        assertThat(email.extension(Smtp2GoOptions.class)).hasValue(options);
    }

    @Test
    void withoutATemplateTheOptionsChangeNothing() {
        Smtp2GoOptions options = Smtp2GoOptions.builder().build();

        assertThat(options.templateId()).isEmpty();
        assertThat(options.essential()).isFalse();
        assertThat(options.providesContent()).isFalse();
        assertThat(options).hasToString("Smtp2GoOptions[no template]");
    }

    @Test
    void templateDataNeedsATemplateId() {
        assertThatThrownBy(() -> Smtp2GoOptions.builder().templateData("username", "Ann").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("template data needs a template id: call templateId(...)");
    }

    @Test
    void theTemplateIdMustBeUsable() {
        assertThatThrownBy(() -> Smtp2GoOptions.builder().templateId(" \t"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("the template id must not be blank");
        assertThatThrownBy(() -> Smtp2GoOptions.builder().templateId(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("the template id must not be blank");
        assertThatThrownBy(() -> Smtp2GoOptions.builder().templateId("wel\ncome"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the template id must not contain control characters");
        assertThatThrownBy(() -> Smtp2GoOptions.builder().templateId(null))
                .isInstanceOf(NullPointerException.class).hasMessage("templateId");
    }

    @Test
    void entriesAddUpAndAMapReplacesThem() {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("plan", "deluxe");
        plan.put("seats", 5);

        Smtp2GoOptions added = welcome().templateData("username", "Ann").templateData("plan", "basic")
                .templateData("username", "Bob").build();
        Smtp2GoOptions replaced = welcome().templateData("username", "Ann").templateData(plan).build();

        assertThat(added.templateData()).containsExactly(entry("username", "Bob"), entry("plan", "basic"));
        assertThat(replaced.templateData()).containsExactly(entry("plan", "deluxe"), entry("seats", 5));
    }

    @Test
    void valuesJsonCantCarryAreRejected() {
        List<Object> loop = new ArrayList<Object>();
        loop.add(loop);
        Map<Object, Object> numberKey = new HashMap<Object, Object>();
        numberKey.put(1, "one");

        for (Object bad : Arrays.<Object>asList(new byte[] {1}, Optional.of("x"), Double.NaN, new Object(), loop,
                numberKey)) {
            assertThatThrownBy(() -> welcome().templateData("bad", bad).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("the template data can't be sent as JSON: ");
        }
    }

    @Test
    void theDataIsCopiedAndCantBeChanged() {
        StringBuilder name = new StringBuilder("Ann");
        List<Object> tags = new ArrayList<Object>(Arrays.asList("new", "eu"));
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("name", "deluxe");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("username", name);
        data.put("tags", tags);
        data.put("plan", plan);
        data.put("codes", new Object[] {"A1", 2});
        Smtp2GoOptions.Builder builder = welcome().templateData(data);

        Smtp2GoOptions options = builder.build();
        name.append("ie");
        tags.add("vip");
        plan.put("name", "basic");
        data.put("extra", true);
        builder.templateData("later", 1);

        assertThat(options.templateData()).containsExactly(entry("username", "Ann"),
                entry("tags", Arrays.asList("new", "eu")), entry("plan", Collections.singletonMap("name", "deluxe")),
                entry("codes", Arrays.<Object>asList("A1", 2)));
        assertThatThrownBy(() -> options.templateData().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) options.templateData().get("tags")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) options.templateData().get("plan")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) options.templateData().get("codes")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void optionsWithTheSameTemplateAndDataAreEqual() {
        Smtp2GoOptions options = welcome().templateData("username", "Ann").build();

        assertThat(options).isEqualTo(welcome().templateData("username", "Ann").build())
                .hasSameHashCodeAs(welcome().templateData("username", "Ann").build())
                .isNotEqualTo(welcome().templateData("username", "Bob").build())
                .isNotEqualTo(Smtp2GoOptions.builder().templateId("reset").templateData("username", "Ann").build())
                .isNotEqualTo(Smtp2GoOptions.builder().build());
    }

    @Test
    void toStringNamesTheTemplateButNotTheValues() {
        assertThat(welcome().templateData("username", "Ann").templateData("plan", "deluxe").build())
                .hasToString("Smtp2GoOptions[templateId=welcome, templateData=2 entries]");
        assertThat(welcome().templateData("username", "Ann").build())
                .hasToString("Smtp2GoOptions[templateId=welcome, templateData=1 entry]");
        assertThat(welcome().build()).hasToString("Smtp2GoOptions[templateId=welcome, templateData=0 entries]");
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new java.util.AbstractMap.SimpleEntry<String, Object>(key, value);
    }
}
