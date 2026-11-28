package io.primelogiclab.mailroom.smtp2go;

import io.primelogiclab.mailroom.EmailExtension;
import io.primelogiclab.mailroom.http.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SMTP2GO's options for one email: a template stored at SMTP2GO, and the data it is filled in with.
 *
 * <pre>{@code
 * Email welcome = Email.builder()
 *     .to(user.email())
 *     .with(Smtp2GoOptions.builder()
 *         .templateId("welcome")
 *         .templateData("username", user.name())
 *         .build())
 *     .build();
 * }</pre>
 *
 * <p>With a template id, the options are {@linkplain #essential() essential}, since a template means nothing at
 * another provider, and they {@linkplain #providesContent() supply the content}, so the email needs no subject or
 * bodies. SMTP2GO ignores any it has. The transport sends the id as {@code template_id} and the data as the
 * {@code template_data} object.
 *
 * <p>Immutable and thread-safe. The template data is copied when the options are built.
 *
 * @since 0.1.0
 */
public final class Smtp2GoOptions implements EmailExtension {

    private final String templateId;
    private final Map<String, Object> templateData;

    private Smtp2GoOptions(String templateId, Map<String, Object> templateData) {
        this.templateId = templateId;
        this.templateData = templateData;
    }

    /**
     * Starts a set of options.
     *
     * @return a builder with no template
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the id of the SMTP2GO template.
     *
     * @return the template id, if one is set
     * @since 0.1.0
     */
    public Optional<String> templateId() {
        return Optional.ofNullable(templateId);
    }

    /**
     * Returns the values the template is filled in with.
     *
     * @return the data, in the order it was added, possibly empty; neither it nor the maps and lists inside it can
     *         be changed
     * @since 0.1.0
     */
    public Map<String, Object> templateData() {
        return templateData;
    }

    /**
     * Returns true when a template is set, so the email only goes through an SMTP2GO transport.
     *
     * @return true with a template id
     * @since 0.1.0
     */
    @Override
    public boolean essential() {
        return templateId != null;
    }

    /**
     * Returns true when a template is set, since the template supplies the subject and bodies.
     *
     * @return true with a template id
     * @since 0.1.0
     */
    @Override
    public boolean providesContent() {
        return templateId != null;
    }

    /**
     * Compares the template id and data.
     *
     * @param other the object to compare with
     * @return true for options with the same template id and data
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Smtp2GoOptions)) {
            return false;
        }
        Smtp2GoOptions that = (Smtp2GoOptions) other;
        return Objects.equals(templateId, that.templateId) && templateData.equals(that.templateData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, templateData);
    }

    /**
     * Describes the options without the template data's values, which may hold personal data, such as
     * {@code Smtp2GoOptions[templateId=welcome, templateData=2 entries]}.
     *
     * @return a description that is safe to log
     */
    @Override
    public String toString() {
        if (templateId == null) {
            return "Smtp2GoOptions[no template]";
        }
        int size = templateData.size();
        return "Smtp2GoOptions[templateId=" + templateId + ", templateData=" + size
                + (size == 1 ? " entry]" : " entries]");
    }

    /** A copy that can't be changed: maps, lists and arrays copied all the way down, text as String. */
    private static Object frozen(Object value) {
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Map) {
            return frozenMap((Map<?, ?>) value);
        }
        if (value instanceof Iterable) {
            List<Object> list = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                list.add(frozen(item));
            }
            return Collections.unmodifiableList(list);
        }
        if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            List<Object> list = new ArrayList<Object>(array.length);
            for (Object item : array) {
                list.add(frozen(item));
            }
            return Collections.unmodifiableList(list);
        }
        return value;
    }

    private static Map<String, Object> frozenMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put((String) entry.getKey(), frozen(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Builds {@link Smtp2GoOptions}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private String templateId;
        private final Map<String, Object> templateData = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        /**
         * Sets the SMTP2GO template to send.
         *
         * @param templateId the template's id at SMTP2GO
         * @return this builder
         * @throws IllegalArgumentException if the id is blank or holds a control character
         * @since 0.1.0
         */
        public Builder templateId(String templateId) {
            Objects.requireNonNull(templateId, "templateId");
            if (isBlank(templateId)) {
                throw new IllegalArgumentException("the template id must not be blank");
            }
            for (int i = 0; i < templateId.length(); i++) {
                if (Character.isISOControl(templateId.charAt(i))) {
                    throw new IllegalArgumentException("the template id must not contain control characters");
                }
            }
            this.templateId = templateId;
            return this;
        }

        /** Like Java 11's {@code String.isBlank()}: empty, or white space only. */
        private static boolean isBlank(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Replaces the template data with these entries. Values may be text, numbers, booleans, null, and maps with
         * String keys, lists and arrays of those.
         *
         * @param templateData the data, such as {@code Collections.singletonMap("username", "Ann")}
         * @return this builder
         * @since 0.1.0
         */
        public Builder templateData(Map<String, ?> templateData) {
            Objects.requireNonNull(templateData, "templateData");
            this.templateData.clear();
            this.templateData.putAll(templateData);
            return this;
        }

        /**
         * Adds one entry to the template data, replacing an entry with the same name.
         *
         * @param name  the variable's name in the template
         * @param value the value, of the types {@link #templateData(Map)} takes
         * @return this builder
         * @since 0.1.0
         */
        public Builder templateData(String name, Object value) {
            templateData.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options; later changes to this builder, or to the maps and lists given to it, don't affect them
         * @throws IllegalStateException    if there is template data but no template id
         * @throws IllegalArgumentException if the template data holds a value JSON can't carry, such as a
         *                                  {@code byte[]}, an {@code Optional}, NaN or a map with a non-String key,
         *                                  or a map or list that contains itself
         * @since 0.1.0
         */
        public Smtp2GoOptions build() {
            if (templateId == null && !templateData.isEmpty()) {
                throw new IllegalStateException("template data needs a template id: call templateId(...)");
            }
            try {
                JsonWriter.object().value("template_data", templateData).toBytes();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the template data can't be sent as JSON: " + e.getMessage(), e);
            }
            return new Smtp2GoOptions(templateId, frozenMap(templateData));
        }
    }
}
