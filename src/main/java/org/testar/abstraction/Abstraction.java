package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.testar.abstraction.Json.*;

/** Native rule interpretation and abstract model construction. */
public final class Abstraction {
    private Abstraction() {
    }

    /** Parse a bounded rule definition and reject unsupported options. */
    public static JsonNode parseRules(String text) {
        return Rules.parse(text);
    }

    /** Compare selected observations, retaining their original source-qualified references. */
    public static JsonNode preview(JsonNode sources, JsonNode rules, JsonNode selectedRuns) {
        JsonNode definition = Rules.parse(canonical(rules));
        EvidenceIndex index = new EvidenceIndex(sources, selectedRuns);
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        ArrayNode unmatched = array();
        Budget budget = new Budget();
        for (EvidenceIndex.Observation observation : index.observations.values()) {
            Projection.Result projected;
            try {
                projected = Projection.observation(observation, definition, budget);
                require(projected.informative(), "None of the selected fields has a captured value");
            } catch (AbstractionLimitException error) {
                throw error;
            } catch (AbstractionException error) {
                unmatched.add(object("reference", observation.reference(), "reason", error.getMessage()));
                continue;
            }
            budget.account(projected.comparison());
            String key = canonical(projected.comparison());
            ObjectNode group = groups.computeIfAbsent(key, unused -> object(
                "id", hash(array().add(definition).add(projected.comparison())),
                "comparison", projected.comparison(), "members", array(), "widgetCorrespondences", array()));
            ((ArrayNode) group.get("members")).add(object("reference", observation.reference(),
                "observationIndex", observation.data().get("observation_index"),
                "capture", observation.data().path("recorded_state").get("capture")));
            for (ObjectNode widget : projected.widgets()) {
                if (widget.path("informative").asBoolean()) {
                    ((ArrayNode) group.get("widgetCorrespondences")).add(widget);
                }
            }
        }
        for (ObjectNode group : groups.values()) {
            Map<String, ObjectNode> buckets = new java.util.TreeMap<>();
            for (JsonNode widget : group.get("widgetCorrespondences")) {
                String key = canonical(widget.get("description"));
                ObjectNode bucket = buckets.computeIfAbsent(key, unused -> object("description", widget.get("description"), "members", array()));
                ((ArrayNode) bucket.get("members")).add(widget.get("reference"));
            }
            ArrayNode correspondences = array();
            for (var entry : buckets.entrySet()) {
                ObjectNode bucket = entry.getValue();
                Set<String> seen = new HashSet<>();
                boolean ambiguous = false;
                for (JsonNode reference : bucket.get("members")) {
                    ObjectNode observation = reference.deepCopy();
                    observation.remove("widget");
                    ambiguous |= !seen.add(referenceKey(observation));
                }
                bucket.put("id", hash(array().add(group.path("id")).add(entry.getKey())));
                bucket.put("ambiguous", ambiguous);
                correspondences.add(bucket);
            }
            group.set("widgetCorrespondences", correspondences);
        }
        ArrayNode scope = array();
        index.sources.values().forEach(source -> scope.add(object("id", source.get("id"),
            "label", source.get("label"), "fingerprint", source.get("fingerprint"))));
        ArrayNode ordered = array();
        groups.values().stream().sorted(Comparator.comparing(group -> group.path("id").asText())).forEach(ordered::add);
        return object("definition", definition, "policyFingerprint", hash(definition), "interpreterVersion", "1.2.0",
            "inputScope", object("sources", scope, "runs", index.runs), "groups", ordered, "unmatched", unmatched).deepCopy();
    }

    /** Construct transitions using the preview and its original evidence sources. */
    public static JsonNode buildModel(JsonNode preview, JsonNode sources) {
        return Model.build(preview, sources);
    }
}
