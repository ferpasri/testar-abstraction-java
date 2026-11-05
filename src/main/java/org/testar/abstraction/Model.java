package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.testar.abstraction.Json.*;

final class Model {
    private Model() {
    }

    static JsonNode build(JsonNode preview, JsonNode supplied) {
        ArrayNode scoped = array();
        for (JsonNode source : preview.path("inputScope").path("sources")) {
            List<JsonNode> matches = list(supplied).stream().filter(item -> item.path("id").equals(source.get("id"))).toList();
            require(matches.size() == 1 && matches.get(0).path("fingerprint").equals(source.get("fingerprint")),
                "Original evidence is missing or its fingerprint has changed");
            scoped.add(matches.get(0));
        }
        EvidenceIndex index = new EvidenceIndex(scoped, preview.path("inputScope").path("runs"));
        ArrayNode nodes = array();
        Map<String, String> nodeByObservation = new HashMap<>();
        Set<String> identifiers = new HashSet<>();
        for (JsonNode group : preview.path("groups")) {
            String id = group.path("id").asText();
            require(!group.path("members").isEmpty() && identifiers.add(id), "Groups need distinct identities and original observations");
            ArrayNode members = array();
            for (JsonNode member : group.get("members")) {
                JsonNode reference = member.get("reference");
                String key = referenceKey(reference);
                require(index.observations.containsKey(key) && !nodeByObservation.containsKey(key), "Group observation is unavailable or repeated");
                nodeByObservation.put(key, id);
                members.add(reference);
            }
            nodes.add(object("id", id, "groupId", id, "members", members));
        }
        Map<String, String> reasons = new HashMap<>();
        preview.path("unmatched").forEach(item -> reasons.put(referenceKey(item.get("reference")), item.path("reason").asText()));
        for (var entry : index.observations.entrySet()) {
            if (!nodeByObservation.containsKey(entry.getKey())) {
                String id = "unmatched:" + entry.getKey();
                nodeByObservation.put(entry.getKey(), id);
                nodes.add(object("id", id, "members", array().add(entry.getValue().reference()),
                    "unmatchedReason", reasons.getOrDefault(entry.getKey(), "Observation was not grouped")));
            }
        }
        ObjectNode actionEvidence = preview.path("definition").has("actions")
            ? Actions.build(index, nodeByObservation, preview.path("definition").get("actions")) : null;
        Map<String, JsonNode> actionByExecution = new HashMap<>();
        if (actionEvidence != null) {
            for (JsonNode action : actionEvidence.get("actions")) {
                action.path("executions").forEach(item -> actionByExecution.put(canonical(item.get("reference")), action));
            }
        }
        Map<String, ObjectNode> edges = new LinkedHashMap<>();
        ArrayNode unlinked = array();
        Set<String> completed = new HashSet<>();
        for (var indexed : index.executions) {
            JsonNode record = indexed.record();
            JsonNode execution = record.get("execution");
            ObjectNode reference = object("source", indexed.source(), "run", indexed.run(), "execution", execution.get("id"));
            ObjectNode origin = object("source", indexed.source(), "run", indexed.run(), "observation", execution.get("source_observation"));
            ObjectNode target = object("source", indexed.source(), "run", indexed.run(), "observation", record.path("resultingObservation").asText(""));
            String sourceNode = nodeByObservation.get(referenceKey(origin));
            String targetNode = nodeByObservation.get(referenceKey(target));
            JsonNode description = index.actions.get(pair(indexed.source(), execution.path("action_description").asText()));
            if (record.path("status").asText().equals("completed") && record.hasNonNull("resultingObservation")) {
                completed.add(pair(referenceKey(origin), referenceKey(target)));
            }
            if (!record.path("status").asText().equals("completed") || sourceNode == null || targetNode == null || description == null) {
                unlinked.add(object("reference", reference, "sourceNode", sourceNode, "status", record.get("status"),
                    "reason", "Execution has no completed connection in the selected evidence"));
                continue;
            }
            JsonNode action = actionByExecution.get(canonical(reference));
            String operation = (action == null ? description.get("action_type") : action.get("actionType")).asText();
            JsonNode unique = actionEvidence == null ? null : action == null ? reference : action.get("id");
            ObjectNode edge = edge(edges, "execution", sourceNode, targetNode, operation, unique);
            if (action != null) {
                edge.set("abstractAction", action.get("id"));
            }
            ((ArrayNode) edge.get("executions")).add(reference);
        }
        Map<String, List<EvidenceIndex.Observation>> sequences = new LinkedHashMap<>();
        for (var indexed : index.observations.values()) {
            String key = pair(indexed.reference().path("source").asText(), indexed.data().path("test_sequence").asText());
            sequences.computeIfAbsent(key, unused -> new ArrayList<>()).add(indexed);
        }
        for (var observations : sequences.values()) {
            observations.sort(Comparator.comparing(item -> item.data().path("observation_index").bigIntegerValue()));
            for (int i = 1; i < observations.size(); i++) {
                JsonNode origin = observations.get(i - 1).reference();
                JsonNode target = observations.get(i).reference();
                if (!completed.contains(pair(referenceKey(origin), referenceKey(target)))) {
                    ObjectNode edge = edge(edges, "observation_order", nodeByObservation.get(referenceKey(origin)),
                        nodeByObservation.get(referenceKey(target)), null, null);
                    ((ArrayNode) edge.get("orderLinks")).add(object("source", origin, "target", target));
                }
            }
        }
        ObjectNode result = object("nodes", ordered(nodes), "edges", ordered(MAPPER.valueToTree(edges.values())), "unlinkedExecutions", unlinked);
        if (actionEvidence != null) {
            result.set("actionEvidence", actionEvidence);
        }
        return result.deepCopy();
    }

    private static ObjectNode edge(Map<String, ObjectNode> edges, String kind, String source, String target,
                                   String operation, JsonNode unique) {
        String key = canonical(array().add(kind).add(source).add(target).add(operation).add(unique));
        return edges.computeIfAbsent(key, unused -> {
            ObjectNode edge = object("id", hash(MAPPER.valueToTree(key)), "kind", kind, "source", source,
                "target", target, "executions", array(), "orderLinks", array());
            if (operation != null) {
                edge.put("actionType", operation);
            }
            return edge;
        });
    }

    private static ArrayNode ordered(JsonNode values) {
        ArrayNode result = array();
        list(values).stream().sorted(Comparator.comparing(item -> item.path("id").asText())).forEach(result::add);
        return result;
    }
}
