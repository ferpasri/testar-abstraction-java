package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static org.testar.abstraction.Json.*;

final class Rules {
    private Rules() {
    }

    static JsonNode parse(String text) {
        require(text.getBytes(StandardCharsets.UTF_8).length <= 65536, "Rules exceed 64 KiB");
        JsonNode rule = Json.parse(text);
        shape(rule, Set.of("version", "name", "state", "capture"), Set.of("widgets", "actions"));
        require(rule.path("version").isIntegralNumber() && rule.path("version").intValue() == 1, "Unsupported rule language");
        text(rule.get("name"), 256, 1);
        fields(rule.get("state"), 0);
        require(Set.of("same", "ignore").contains(rule.path("capture").asText()), "Unknown capture comparison");
        require(!rule.get("state").isEmpty() || rule.has("widgets"), "Select state or widget fields");
        if (rule.has("widgets")) {
            JsonNode widgets = rule.get("widgets");
            shape(widgets, Set.of("fields", "hierarchy", "order", "descendants"), Set.of("exclude"));
            fields(widgets.get("fields"), 1);
            require(Set.of("flat", "tree").contains(widgets.path("hierarchy").asText()), "Unknown hierarchy");
            require(Set.of("preserve", "sort").contains(widgets.path("order").asText()), "Unknown order");
            require(Set.of("exclude", "keep").contains(widgets.path("descendants").asText()), "Unknown descendant rule");
            JsonNode predicates = optional(widgets, "exclude");
            count(predicates, 0, 32);
            for (JsonNode predicate : predicates) {
                String operation = predicate.path("op").asText();
                require(Set.of("present", "equals", "contains").contains(operation), "Unknown predicate");
                shape(predicate, operation.equals("present") ? Set.of("keys", "op") : Set.of("keys", "op", "value"), Set.of());
                strings(predicate.get("keys"), 1, 8);
                if (operation.equals("contains")) {
                    text(predicate.get("value"), 4096, 0);
                }
            }
        }
        if (rule.has("actions")) {
            JsonNode actions = rule.get("actions");
            shape(actions, Set.of(), Set.of("type", "description", "target", "parameters"));
            transforms(optional(actions, "type"));
            fields(optional(actions, "description"), 0);
            fields(optional(actions, "target"), 0);
            if (actions.has("parameters")) {
                JsonNode parameters = actions.get("parameters");
                shape(parameters, Set.of("available", "executed"), Set.of());
                fields(parameters.get("available"), 0);
                fields(parameters.get("executed"), 0);
                require(names(parameters.get("available")).equals(names(parameters.get("executed"))), "Parameter fields need the same names");
            }
        }
        return rule;
    }

    private static Set<String> names(JsonNode fields) {
        Set<String> names = new HashSet<>();
        fields.forEach(field -> names.add(field.path("name").asText()));
        return names;
    }

    private static void fields(JsonNode fields, int minimum) {
        count(fields, minimum, 32);
        Set<String> names = new HashSet<>();
        for (JsonNode field : fields) {
            shape(field, Set.of("name", "keys"), Set.of("path", "required", "transforms"));
            text(field.get("name"), 256, 1);
            require(names.add(field.get("name").asText()), "Field names must be unique");
            strings(field.get("keys"), 1, 8);
            if (field.has("path")) {
                strings(field.get("path"), 1, 16);
            }
            require(!field.has("required") || field.get("required").isBoolean(), "required must be boolean");
            transforms(optional(field, "transforms"));
        }
    }

    private static void transforms(JsonNode transforms) {
        count(transforms, 0, 12);
        for (JsonNode transform : transforms) {
            switch (transform.path("op").asText()) {
                case "trim", "collapse_whitespace", "lowercase" -> shape(transform, Set.of("op"), Set.of());
                case "replace" -> {
                    shape(transform, Set.of("op", "find", "with"), Set.of());
                    text(transform.get("find"), 4096, 1);
                    text(transform.get("with"), 4096, 0);
                }
                case "map" -> {
                    shape(transform, Set.of("op", "values"), Set.of());
                    JsonNode values = transform.get("values");
                    require(values.isObject() && values.size() <= 128, "Invalid mapping");
                    values.properties().forEach(entry -> {
                        require(entry.getKey().length() <= 4096, "Mapping key exceeds 4096 characters");
                        text(entry.getValue(), 4096, 0);
                    });
                }
                case "url" -> {
                    shape(transform, Set.of("op"), Set.of("remove_path_parameters", "remove_query", "remove_fragment"));
                    for (String key : Set.of("remove_path_parameters", "remove_query")) {
                        if (transform.has(key)) {
                            strings(transform.get(key), 0, 32);
                        }
                    }
                    require(!transform.has("remove_fragment") || transform.get("remove_fragment").isBoolean(), "remove_fragment must be boolean");
                }
                default -> throw new AbstractionException("Unknown transform");
            }
        }
    }

    private static void text(JsonNode value, int maximum, int minimum) {
        require(value != null && value.isTextual() && value.textValue().length() >= minimum && value.textValue().length() <= maximum, "Expected bounded text");
    }

    private static void count(JsonNode value, int minimum, int maximum) {
        require(value != null && value.isArray() && value.size() >= minimum && value.size() <= maximum, "Invalid list length");
    }

    private static void strings(JsonNode values, int minimum, int maximum) {
        count(values, minimum, maximum);
        values.forEach(value -> text(value, 256, 1));
    }

    private static void shape(JsonNode value, Set<String> required, Set<String> optional) {
        require(value != null && value.isObject(), "Expected an object");
        Set<String> keys = new HashSet<>();
        value.fieldNames().forEachRemaining(keys::add);
        require(keys.containsAll(required), "Missing rule fields");
        keys.removeAll(required);
        require(optional.containsAll(keys), "Unknown rule fields");
    }
}
