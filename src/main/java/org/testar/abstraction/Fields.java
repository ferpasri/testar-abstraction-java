package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import static org.testar.abstraction.Json.*;

final class Fields {
    private static final Pattern SPACE = Pattern.compile("[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+");
    private static final Pattern ENDS = Pattern.compile("^" + SPACE.pattern() + "|" + SPACE.pattern() + "$");
    record Projection(ArrayNode values, boolean informative) {
    }

    private Fields() {
    }

    static JsonNode select(JsonNode properties, JsonNode keys) {
        for (JsonNode key : keys) {
            if (properties.has(key.asText())) {
                return properties.get(key.asText());
            }
        }
        return null;
    }

    static Projection project(JsonNode properties, JsonNode fields) {
        ArrayNode values = array();
        boolean informative = false;
        for (JsonNode field : fields) {
            JsonNode value = select(properties, field.get("keys"));
            for (JsonNode segment : optional(field, "path")) {
                if (value != null && value.isObject()) {
                    value = value.get(segment.asText());
                } else if (value != null && value.isArray() && segment.asText().matches("0|[1-9][0-9]{0,8}")) {
                    value = value.get(Integer.parseInt(segment.asText()));
                } else {
                    value = null;
                }
            }
            if (value == null) {
                require(!field.path("required").asBoolean(), "Required field '" + field.path("name").asText() + "' has no captured property");
                values.add(object("name", field.get("name"), "reading", object("kind", "missing")));
                continue;
            }
            bounded(value);
            for (JsonNode transform : optional(field, "transforms")) {
                value = transform(value, transform);
                bounded(value);
            }
            informative = true;
            values.add(object("name", field.get("name"), "reading", object("kind", "value", "value", value)));
        }
        return new Projection(values, informative);
    }

    static JsonNode transform(JsonNode value, JsonNode transform) {
        require(value.isTextual(), "String transforms require a captured string");
        String text = value.textValue();
        String result = switch (transform.path("op").asText()) {
            case "trim" -> ENDS.matcher(text).replaceAll("");
            case "collapse_whitespace" -> SPACE.matcher(text).replaceAll(" ");
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "replace" -> replace(text, transform.path("find").asText(), transform.path("with").asText());
            case "map" -> transform.path("values").has(text) ? transform.path("values").get(text).asText() : text;
            case "url" -> Url.normalize(text, transform);
            default -> throw new AbstractionException("Unknown transform");
        };
        return TextNode.valueOf(result);
    }

    private static String replace(String text, String find, String replacement) {
        long count = 0;
        int offset = text.indexOf(find);
        while (offset >= 0) {
            count++;
            require(text.length() + count * (replacement.length() - find.length()) <= 1048576, "Replacement exceeds 1 MiB");
            offset = text.indexOf(find, offset + find.length());
        }
        return text.replace(find, replacement);
    }

    static boolean matches(JsonNode properties, JsonNode predicate) {
        JsonNode value = select(properties, predicate.get("keys"));
        if (value == null) {
            return false;
        }
        return switch (predicate.path("op").asText()) {
            case "present" -> true;
            case "contains" -> value.isTextual() && value.textValue().contains(predicate.path("value").asText());
            default -> canonical(value).equals(canonical(predicate.get("value")));
        };
    }

    static void bounded(JsonNode value) {
        require(canonical(value).getBytes(StandardCharsets.UTF_8).length <= 1048576, "Selected value exceeds 1 MiB");
    }
}
