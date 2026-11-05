package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.testar.abstraction.Json.*;

final class Projection {
    record Result(ObjectNode comparison, List<ObjectNode> widgets, boolean informative) {
    }

    private Projection() {
    }

    static Result observation(EvidenceIndex.Observation indexed, JsonNode definition, Budget budget) {
        JsonNode state = indexed.data().get("recorded_state");
        Fields.Projection fields = Fields.project(state.get("properties"), definition.get("state"));
        ObjectNode comparison = object("state", fields.values());
        if (definition.path("capture").asText().equals("same")) {
            comparison.set("capture", state.get("capture"));
        }
        if (!definition.has("widgets")) {
            return new Result(comparison, List.of(), fields.informative());
        }
        require(!state.path("capture").asText().equals("unavailable"), "Widget capture is unavailable");
        JsonNode policy = definition.get("widgets");
        Map<String, JsonNode> widgets = new LinkedHashMap<>();
        state.path("widgets").forEach(widget -> {
            require(!widgets.containsKey(widget.path("id").asText()), "Widget identities must be unique");
            widgets.put(widget.path("id").asText(), widget);
        });
        Map<String, List<String>> paths = new LinkedHashMap<>();
        Set<String> excluded = new HashSet<>();
        for (var entry : widgets.entrySet()) {
            List<String> path = new ArrayList<>();
            JsonNode current = entry.getValue();
            while (current != null) {
                String id = current.path("id").asText();
                require(!path.contains(id), "Widget hierarchy contains a cycle");
                path.add(id);
                require(path.size() <= 128, "Widget hierarchy exceeds depth 128");
                JsonNode parent = current.path("parent");
                if (!parent.isNull()) {
                    require(widgets.containsKey(parent.asText()), "Widget parent is unavailable");
                }
                current = parent.isNull() ? null : widgets.get(parent.asText());
            }
            paths.put(entry.getKey(), path);
            if (list(optional(policy, "exclude")).stream().anyMatch(predicate -> Fields.matches(entry.getValue().get("properties"), predicate))) {
                excluded.add(entry.getKey());
            }
        }
        if (policy.path("descendants").asText().equals("exclude")) {
            Set<String> initiallyExcluded = new HashSet<>(excluded);
            paths.forEach((key, path) -> {
                if (path.stream().anyMatch(initiallyExcluded::contains)) {
                    excluded.add(key);
                }
            });
        }
        Map<String, ObjectNode> selected = new LinkedHashMap<>();
        boolean informative = fields.informative();
        for (var entry : widgets.entrySet()) {
            if (excluded.contains(entry.getKey())) {
                continue;
            }
            Fields.Projection projected = Fields.project(entry.getValue().get("properties"), policy.get("fields"));
            informative |= projected.informative();
            ObjectNode reference = indexed.reference().deepCopy();
            reference.put("widget", entry.getKey());
            selected.put(entry.getKey(), object("reference", reference, "fields", projected.values(),
                "description", projected.values(), "informative", projected.informative()));
        }
        ArrayNode descriptions = array();
        if (policy.path("hierarchy").asText().equals("flat")) {
            for (ObjectNode item : selected.values()) {
                descriptions.add(item.get("fields"));
            }
            descriptions = ordered(descriptions, policy);
        } else {
            Map<String, List<String>> children = new LinkedHashMap<>();
            for (String key : selected.keySet()) {
                String parent = paths.get(key).stream().skip(1).filter(selected::containsKey).findFirst().orElse("");
                children.computeIfAbsent(parent, unused -> new ArrayList<>()).add(key);
            }
            descriptions = serialize("", array(), selected, children, policy);
        }
        comparison.set("widgets", descriptions);
        for (ObjectNode item : selected.values()) {
            if (item.path("informative").asBoolean()) {
                budget.account(item.get("description"));
            }
        }
        return new Result(comparison, new ArrayList<>(selected.values()), informative);
    }

    private static ArrayNode serialize(String parent, ArrayNode path, Map<String, ObjectNode> selected,
                                      Map<String, List<String>> children, JsonNode policy) {
        ArrayNode values = array();
        for (String key : children.getOrDefault(parent, List.of())) {
            ObjectNode item = selected.get(key);
            ArrayNode nextPath = path.deepCopy().add(item.get("fields"));
            item.set("description", object("path", nextPath));
            values.add(object("fields", item.get("fields"), "children", serialize(key, nextPath, selected, children, policy)));
        }
        return ordered(values, policy);
    }

    private static ArrayNode ordered(ArrayNode values, JsonNode policy) {
        if (!policy.path("order").asText().equals("sort")) {
            return values;
        }
        ArrayNode result = array();
        list(values).stream().sorted(Comparator.comparing(Json::canonical)).forEach(result::add);
        return result;
    }
}
