package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.testar.abstraction.Json.*;

final class Actions {
    private record Binding(ObjectNode group, ObjectNode availability) {
    }

    private Actions() {
    }

    private static ArrayNode selected(JsonNode properties, JsonNode fields) {
        Fields.Projection projected = Fields.project(properties, fields);
        require(fields.isEmpty() || projected.informative(), "No captured values for selected action fields");
        ArrayNode values = array();
        list(projected.values()).stream().sorted(Comparator.comparing(item -> item.path("name").asText())).forEach(values::add);
        return values;
    }

    private static ObjectNode project(EvidenceIndex index, JsonNode reference, String description, JsonNode target,
                                      JsonNode rules, JsonNode parameters) {
        JsonNode action = index.actions.get(pair(reference.path("source").asText(), description));
        require(action != null, "Original action description is unavailable");
        JsonNode operation = Fields.project(object("operation", action.get("action_type")),
            array().add(object("name", "operation", "keys", array().add("operation"),
                "transforms", optional(rules, "type")))).values().get(0).path("reading").get("value");
        require(!Fields.transform(operation, object("op", "trim")).asText().isEmpty(), "Normalized action type must contain text");
        ArrayNode targetValues = array();
        if (!optional(rules, "target").isEmpty()) {
            EvidenceIndex.Observation observation = index.observations.get(referenceKey(reference));
            JsonNode widget = observation == null ? null : list(observation.data().path("recorded_state").path("widgets"))
                .stream().filter(item -> item.get("id").equals(target)).findFirst().orElse(null);
            require(widget != null, "Bound target widget is unavailable");
            targetValues = selected(widget.get("properties"), rules.get("target"));
        }
        ArrayNode inputs = array();
        if (rules.has("parameters")) {
            inputs = selected(parameters == null ? action.get("properties") : parameters,
                rules.get("parameters").get(parameters == null ? "available" : "executed"));
        }
        return object("operation", operation, "description", selected(action.get("properties"), optional(rules, "description")),
            "target", targetValues, "parameters", inputs);
    }

    private static ObjectNode groupFor(Map<String, ObjectNode> groups, String node, JsonNode comparison) {
        String key = canonical(array().add(node).add(comparison));
        return groups.computeIfAbsent(key, unused -> object("id", hash(TextNode.valueOf(key)), "sourceNode", node,
            "comparison", comparison, "actionType", comparison.get("operation"), "availability", array(),
            "executions", array(), "ambiguous", false));
    }

    static ObjectNode build(EvidenceIndex index, Map<String, String> nodes, JsonNode rules) {
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        Map<String, Binding> bindings = new HashMap<>();
        Map<String, Set<String>> seen = new HashMap<>();
        ObjectNode result = object("actions", array(), "unmatchedAvailableActions", array(), "unmatchedExecutions", array());
        Budget budget = new Budget();
        long records = index.executions.size();
        for (var observation : index.observations.values()) {
            records += observation.data().path("available_actions").size();
        }
        if (records > 100000) {
            throw new AbstractionLimitException("Action comparison exceeds 100,000 records");
        }
        for (var observation : index.observations.values()) {
            String node = nodes.get(referenceKey(observation.reference()));
            for (JsonNode available : observation.data().path("available_actions")) {
                ObjectNode reference = observation.reference().deepCopy();
                reference.set("actionDescription", available.get("action_description"));
                reference.set("targetWidget", available.get("target_widget"));
                String key = canonical(reference);
                if (bindings.containsKey(key)) {
                    continue;
                }
                ObjectNode availability = object("reference", reference, "scope", observation.data().get("available_action_scope"),
                    "recordedExecutions", array());
                ObjectNode comparison;
                try {
                    comparison = project(index, observation.reference(), available.path("action_description").asText(),
                        available.get("target_widget"), rules, null);
                } catch (AbstractionException error) {
                    ((ArrayNode) result.get("unmatchedAvailableActions")).add(object("reference", reference, "sourceNode", node,
                        "reason", error.getMessage(), "recordedExecutions", availability.get("recordedExecutions")));
                    bindings.put(key, new Binding(null, availability));
                    continue;
                }
                budget.account(comparison);
                ObjectNode group = groupFor(groups, node, comparison);
                Set<String> observations = seen.computeIfAbsent(group.path("id").asText(), unused -> new HashSet<>());
                if (!observations.add(referenceKey(observation.reference()))) {
                    group.put("ambiguous", true);
                }
                ((ArrayNode) group.get("availability")).add(availability);
                bindings.put(key, new Binding(group, availability));
            }
        }
        for (var indexed : index.executions) {
            JsonNode record = indexed.record();
            JsonNode execution = record.get("execution");
            ObjectNode reference = object("source", indexed.source(), "run", indexed.run(), "execution", execution.get("id"));
            ObjectNode observation = object("source", indexed.source(), "run", indexed.run(), "observation", execution.get("source_observation"));
            ObjectNode availableReference = observation.deepCopy();
            availableReference.set("actionDescription", execution.get("action_description"));
            availableReference.set("targetWidget", execution.get("target_widget"));
            Binding binding = bindings.get(canonical(availableReference));
            ArrayNode recorded = array();
            if (binding != null) {
                ((ArrayNode) binding.availability.get("recordedExecutions")).add(reference);
                recorded.add(binding.availability.get("reference"));
            }
            String node = nodes.get(referenceKey(observation));
            ObjectNode comparison = null;
            String reason = "Source observation is unavailable";
            try {
                comparison = project(index, observation, execution.path("action_description").asText(),
                    execution.get("target_widget"), rules, execution.get("parameters"));
            } catch (AbstractionException error) {
                reason = error.getMessage();
            }
            if (comparison != null) {
                budget.account(comparison);
            }
            if (node == null || comparison == null) {
                ArrayNode possible = array();
                if (binding != null && binding.group != null) {
                    possible.add(binding.group.get("id"));
                }
                ((ArrayNode) result.get("unmatchedExecutions")).add(object("reference", reference, "sourceNode", node,
                    "reason", reason, "possibleActionIds", possible, "recordedAvailableActions", recorded));
                continue;
            }
            ObjectNode group = groupFor(groups, node, comparison);
            if (group.path("ambiguous").asBoolean() && (binding == null || binding.group != group)) {
                ((ArrayNode) result.get("unmatchedExecutions")).add(object("reference", reference, "sourceNode", node,
                    "reason", "Selected fields match multiple available actions in one observation",
                    "possibleActionIds", array().add(group.get("id")), "recordedAvailableActions", recorded));
                continue;
            }
            ObjectNode item = object("reference", reference, "status", record.get("status"),
                "relationship", binding == null ? "abstraction" : "recorded", "recordedAvailableActions", recorded);
            if (binding != null && binding.group != null && binding.group != group) {
                item.put("diagnostic", "Executed values differ from available-action values");
            }
            ((ArrayNode) group.get("executions")).add(item);
        }
        groups.values().stream().sorted(Comparator.comparing(item -> item.path("id").asText()))
            .forEach(item -> ((ArrayNode) result.get("actions")).add(item));
        return result;
    }
}
