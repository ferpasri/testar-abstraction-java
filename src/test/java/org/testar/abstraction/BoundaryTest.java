package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.testar.abstraction.Json.*;

final class BoundaryTest {
    private JsonNode input;
    private JsonNode rules;

    @BeforeEach
    void example() throws Exception {
        var directory = PrepareConformance.prepared().resolve("action-cases/available-and-executed");
        input = Json.parse(Files.readString(directory.resolve("input.json")));
        rules = Abstraction.parseRules(Files.readString(directory.resolve("rules.json")));
    }

    private JsonNode preview() {
        return Abstraction.preview(input.get("sources"), rules, input.get("selected_runs"));
    }

    @Test
    void rejectsUnknownOptionsAndMismatchedParameters() {
        ObjectNode invalid = rules.deepCopy();
        invalid.put("execute_code", "anything");
        assertThrows(AbstractionException.class, () -> Abstraction.parseRules(invalid.toString()));
        ((ObjectNode) rules.get("actions")).set("parameters", object(
            "available", array().add(object("name", "planned", "keys", array().add("value"))),
            "executed", array().add(object("name", "actual", "keys", array().add("value")))));
        assertThrows(AbstractionException.class, () -> Abstraction.parseRules(rules.toString()));
    }

    @Test
    void rejectsChangedScopeAndRepeatedMembers() {
        JsonNode preview = preview();
        JsonNode changed = input.get("sources").deepCopy();
        ((ObjectNode) changed.get(0)).put("fingerprint", "changed");
        assertThrows(AbstractionException.class, () -> Abstraction.buildModel(preview, changed));
        ArrayNode members = (ArrayNode) preview.path("groups").get(0).get("members");
        members.add(members.get(0));
        assertThrows(AbstractionException.class, () -> Abstraction.buildModel(preview, input.get("sources")));
    }

    @Test
    void rejectsUnknownRunsAndDuplicateSources() {
        assertThrows(AbstractionException.class, () -> Abstraction.preview(input.get("sources"), rules,
            array().add(object("source", "missing", "run", "run"))));
        ArrayNode duplicate = input.get("sources").deepCopy();
        duplicate.add(duplicate.get(0));
        assertThrows(AbstractionException.class, () -> Abstraction.preview(duplicate, rules, input.get("selected_runs")));
    }

    @Test
    void duplicateAvailabilityDoesNotCreateAmbiguityAndActionsAreOptional() {
        ArrayNode available = (ArrayNode) input.get("sources").get(0).path("evidence").path("observations").get(0).get("available_actions");
        available.add(available.get(0).deepCopy());
        JsonNode model = Abstraction.buildModel(preview(), input.get("sources"));
        model.path("actionEvidence").path("actions").forEach(action -> assertFalse(action.path("ambiguous").asBoolean()));
        ((ObjectNode) rules).remove("actions");
        assertFalse(Abstraction.buildModel(preview(), input.get("sources")).has("actionEvidence"));
    }

    @Test
    void enforcesLimitsAndRejectsUnsuitableTransformValues() {
        assertThrows(AbstractionException.class, () -> Abstraction.parseRules(" ".repeat(65537)));
        Budget budget = new Budget();
        assertThrows(AbstractionLimitException.class, () -> {
            for (int i = 0; i < 33; i++) {
                budget.account(MAPPER.valueToTree("x".repeat(1048576)));
            }
        });
        assertThrows(AbstractionException.class, () -> Fields.project(object("text", 1),
            array().add(object("name", "text", "keys", array().add("text"), "transforms", array().add(object("op", "trim"))))));
    }

    @Test
    void jsonReadingKeepsDistinctCapturedValuesSeparate() {
        // Two captured measurements differ beyond ordinary floating-point precision.
        JsonNode values = Json.parse("[1, 1.00, 18446744073709551615, 1.8446744073709551615e19,"
            + "0.10000000000000000001, 0.10000000000000000002]");
        assertEquals(canonical(values.get(0)), canonical(values.get(1)));
        assertEquals(canonical(values.get(2)), canonical(values.get(3)));
        assertNotEquals(canonical(values.get(4)), canonical(values.get(5)));
        assertEquals(canonical(values), canonical(Json.parse(values.toString())));

        // Numeric widget exclusions retain the captured decimal value.
        JsonNode definition = Json.parse("""
            {"version":1,"name":"Precise exclusion","state":[{"name":"page","keys":["page"]}],
             "capture":"same","widgets":{"fields":[{"name":"value","keys":["value"]}],
             "hierarchy":"flat","order":"sort","descendants":"keep",
             "exclude":[{"keys":["value"],"op":"equals","value":0.10000000000000000001}]}}
            """);
        JsonNode policy = Abstraction.parseRules(definition.toString());
        ObjectNode observation = object("recorded_state", object(
            "properties", object("page", "measurements"), "capture", "complete",
            "widgets", array()
                .add(object("id", "excluded", "parent", null, "properties", object("value", values.get(4))))
                .add(object("id", "retained", "parent", null, "properties", object("value", values.get(5))))));
        var indexed = new EvidenceIndex.Observation(
            object("source", "source", "run", "run", "observation", "observation"), observation);
        Projection.Result result = Projection.observation(indexed, policy, new Budget());
        assertEquals(1, result.widgets().size());
        assertEquals("retained", result.widgets().get(0).path("reference").path("widget").asText());
    }

    @Test
    void hierarchyCyclesStayUnmatched() {
        JsonNode observation = input.get("sources").get(0).path("evidence").path("observations").get(0);
        ObjectNode widget = (ObjectNode) observation.path("recorded_state").path("widgets").get(0);
        widget.set("parent", widget.get("id"));
        ((ObjectNode) rules).set("widgets", object("fields", array().add(object("name", "name", "keys", array().add("ui.name"))),
            "hierarchy", "tree", "order", "sort", "descendants", "keep"));
        assertFalse(preview().path("unmatched").isEmpty());
    }

    @Test
    void treeFilteringPromotesRetainedChildrenOrExcludesDescendants() {
        ObjectNode reference = object("source", "source", "run", "run", "observation", "observation");
        ObjectNode observation = object("recorded_state", object("properties", object("page", "form"), "capture", "complete",
            "widgets", array()
                .add(object("id", "root", "parent", null, "properties", object("name", "Window")))
                .add(object("id", "dynamic", "parent", "root", "properties", object("name", "Panel", "dynamic", true)))
                .add(object("id", "child", "parent", "dynamic", "properties", object("name", "Submit")))));
        ObjectNode policy = object("fields", array().add(object("name", "name", "keys", array().add("name"))),
            "exclude", array().add(object("keys", array().add("dynamic"), "op", "equals", "value", true)),
            "hierarchy", "tree", "order", "sort", "descendants", "keep");
        ObjectNode definition = object("state", array().add(object("name", "page", "keys", array().add("page"))),
            "capture", "same", "widgets", policy);
        var indexed = new EvidenceIndex.Observation(reference, observation);
        Projection.Result result = Projection.observation(indexed, definition, new Budget());
        assertEquals(1, result.comparison().path("widgets").size());
        assertEquals(1, result.comparison().path("widgets").get(0).path("children").size());
        assertEquals(2, result.widgets().size());
        policy.put("descendants", "exclude");
        result = Projection.observation(indexed, definition, new Budget());
        assertTrue(result.comparison().path("widgets").get(0).path("children").isEmpty());
        assertEquals(1, result.widgets().size());
    }

    @Test
    void oversizedActionOperationRemainsUnmatched() {
        ObjectNode action = (ObjectNode) input.get("sources").get(0).path("evidence").path("actions").get(0);
        action.put("action_type", "x".repeat(1048577));
        JsonNode model = Abstraction.buildModel(preview(), input.get("sources"));
        assertFalse(model.path("actionEvidence").path("unmatchedAvailableActions").isEmpty());
    }
}
