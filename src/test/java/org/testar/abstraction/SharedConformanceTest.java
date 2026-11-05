package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.abstraction.Json.*;

final class SharedConformanceTest {
    @TestFactory
    Stream<DynamicTest> publishedCases() throws Exception {
        Path root = PrepareConformance.prepared();
        JsonNode manifest = Json.parse(Files.readString(root.resolve("manifest.json")));
        List<DynamicTest> cases = new ArrayList<>();
        for (String suiteName : List.of("states", "actions")) {
            JsonNode suite = manifest.path("suites").get(suiteName);
            for (JsonNode name : suite.get("cases")) {
                Path directory = root.resolve(suite.path("directory").asText()).resolve(name.asText());
                cases.add(DynamicTest.dynamicTest(suiteName + "/" + name.asText(), () -> {
                    JsonNode input = Json.parse(Files.readString(directory.resolve("input.json")));
                    JsonNode rules = Abstraction.parseRules(Files.readString(directory.resolve("rules.json")));
                    JsonNode expected = normalize(Json.parse(Files.readString(directory.resolve("expected.json"))));
                    for (boolean reverse : List.of(false, true)) {
                        if (reverse) {
                            ((ObjectNode) input).set("sources", reversed(input.get("sources")));
                            ((ObjectNode) input).set("selected_runs", reversed(input.get("selected_runs")));
                            for (JsonNode source : input.get("sources")) {
                                ObjectNode evidence = (ObjectNode) source.get("evidence");
                                List<String> fields = new ArrayList<>();
                                evidence.fieldNames().forEachRemaining(fields::add);
                                fields.forEach(key -> evidence.set(key, reversed(evidence.get(key))));
                            }
                        }
                        JsonNode before = input.deepCopy();
                        JsonNode preview = Abstraction.preview(input.get("sources"), rules, input.get("selected_runs"));
                        JsonNode model = Abstraction.buildModel(preview, input.get("sources"));
                        assertEquals(expected, normalize(semantic(preview, model, suiteName)));
                        assertEquals(before, input, "Input evidence must stay unchanged");
                    }
                }));
            }
        }
        return cases.stream();
    }

    private static ArrayNode reversed(JsonNode values) {
        List<JsonNode> items = list(values);
        Collections.reverse(items);
        return MAPPER.valueToTree(items);
    }

    static JsonNode normalize(JsonNode value) {
        if (value.isArray()) {
            ArrayNode result = array();
            list(value).stream().map(SharedConformanceTest::normalize).sorted(Comparator.comparing(Json::canonical)).forEach(result::add);
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = object();
            value.properties().forEach(entry -> result.set(entry.getKey(), normalize(entry.getValue())));
            return result;
        }
        if (value.isNumber()) {
            return MAPPER.valueToTree(value.decimalValue().stripTrailingZeros());
        }
        return value;
    }

    private static JsonNode semantic(JsonNode preview, JsonNode model, String suite) {
        Map<String, JsonNode> members = new HashMap<>();
        model.get("nodes").forEach(node -> members.put(node.path("id").asText(), node.get("members")));
        ArrayNode transitions = array();
        if (suite.equals("states")) {
            ArrayNode groups = array();
            ArrayNode unmatched = array();
            ArrayNode order = array();
            ArrayNode unlinked = array();
            ArrayNode correspondences = array();
            for (JsonNode group : preview.get("groups")) {
                ArrayNode references = array();
                group.get("members").forEach(member -> references.add(member.get("reference")));
                groups.add(references);
                group.get("widgetCorrespondences").forEach(item -> correspondences.add(object("members", item.get("members"), "ambiguous", item.get("ambiguous"))));
            }
            preview.get("unmatched").forEach(item -> unmatched.add(item.get("reference")));
            for (JsonNode edge : model.get("edges")) {
                if (edge.path("kind").asText().equals("execution")) {
                    transitions.add(object("from", members.get(edge.path("source").asText()), "to", members.get(edge.path("target").asText()),
                        "action_type", edge.get("actionType"), "executions", edge.get("executions")));
                }
                edge.get("orderLinks").forEach(order::add);
            }
            model.get("unlinkedExecutions").forEach(item -> unlinked.add(object("reference", item.get("reference"), "status", item.get("status"))));
            return object("groups", groups, "unmatched", unmatched, "transitions", transitions,
                "observation_order_links", order, "unlinked_executions", unlinked, "widget_correspondences", correspondences);
        }
        JsonNode evidence = model.get("actionEvidence");
        ArrayNode actions = array();
        ArrayNode unmatchedAvailable = array();
        ArrayNode unmatchedExecutions = array();
        for (JsonNode action : evidence.get("actions")) {
            ArrayNode executions = array();
            for (JsonNode item : action.get("executions")) {
                ObjectNode execution = item.deepCopy();
                execution.put("diagnostic", item.has("diagnostic") && !item.path("diagnostic").asText().isEmpty());
                executions.add(execution);
            }
            actions.add(object("state", members.get(action.path("sourceNode").asText()), "comparison", action.get("comparison"),
                "availability", action.get("availability"), "executions", executions, "ambiguous", action.get("ambiguous")));
        }
        evidence.get("unmatchedAvailableActions").forEach(item -> unmatchedAvailable.add(object("reference", item.get("reference"),
            "state", members.get(item.path("sourceNode").asText()), "recordedExecutions", item.get("recordedExecutions"),
            "diagnostic", !item.path("reason").asText().isEmpty())));
        evidence.get("unmatchedExecutions").forEach(item -> unmatchedExecutions.add(object("reference", item.get("reference"),
            "diagnostic", !item.path("reason").asText().isEmpty(), "possible_actions", item.path("possibleActionIds").size(),
            "recordedAvailableActions", item.get("recordedAvailableActions"))));
        for (JsonNode edge : model.get("edges")) {
            if (edge.path("kind").asText().equals("execution")) {
                transitions.add(object("executions", edge.get("executions"), "matched_action", edge.has("abstractAction")));
            }
        }
        return object("actions", actions, "unmatched_available", unmatchedAvailable, "unmatched_executions", unmatchedExecutions, "transitions", transitions);
    }
}
