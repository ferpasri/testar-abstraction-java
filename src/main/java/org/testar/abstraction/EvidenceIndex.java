package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.testar.abstraction.Json.*;

final class EvidenceIndex {
    record Observation(JsonNode reference, JsonNode data) {
    }
    record Execution(String source, String run, JsonNode record) {
    }
    final Map<String, JsonNode> sources = new LinkedHashMap<>();
    final Map<String, Observation> observations = new LinkedHashMap<>();
    final Map<String, JsonNode> actions = new LinkedHashMap<>();
    final java.util.ArrayList<Execution> executions = new java.util.ArrayList<>();
    final ArrayNode runs = array();

    EvidenceIndex(JsonNode suppliedSources, JsonNode selectedRuns) {
        Set<String> selected = new HashSet<>();
        selectedRuns.forEach(item -> selected.add(pair(item.path("source").asText(), item.path("run").asText())));
        Set<String> known = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        long widgetCount = 0;
        for (JsonNode source : list(suppliedSources).stream().sorted(Comparator.comparing(item -> item.path("id").asText())).toList()) {
            String sourceId = source.path("id").asText();
            require(sourceIds.add(sourceId) && fingerprints.add(source.path("fingerprint").asText()), "Source identities and fingerprints must be unique");
            JsonNode evidence = source.get("evidence");
            Set<String> includedRuns = new HashSet<>();
            for (JsonNode run : evidence.path("runs")) {
                String key = pair(sourceId, run.path("id").asText());
                known.add(key);
                if (selected.contains(key)) {
                    includedRuns.add(run.path("id").asText());
                }
            }
            if (includedRuns.isEmpty()) {
                continue;
            }
            sources.put(sourceId, source);
            includedRuns.stream().sorted().forEach(run -> runs.add(object("source", sourceId, "run", run)));
            Map<String, String> sequences = new LinkedHashMap<>();
            evidence.path("sequences").forEach(item -> sequences.put(item.path("id").asText(), item.path("test_run").asText()));
            evidence.path("actions").forEach(action -> actions.put(pair(sourceId, action.path("id").asText()), action));
            List<JsonNode> ordered = list(evidence.path("observations"));
            ordered.sort(Comparator.comparing((JsonNode item) -> item.path("test_sequence").asText())
                .thenComparing(item -> item.path("observation_index").bigIntegerValue())
                .thenComparing(item -> item.path("id").asText()));
            for (JsonNode observation : ordered) {
                String run = sequences.get(observation.path("test_sequence").asText());
                require(run != null, "Observation refers to an unavailable sequence");
                if (!includedRuns.contains(run)) {
                    continue;
                }
                ObjectNode reference = object("source", sourceId, "run", run, "observation", observation.get("id"));
                require(!observations.containsKey(referenceKey(reference)), "Repeated observation identity");
                observations.put(referenceKey(reference), new Observation(reference, observation));
                widgetCount += observation.path("recorded_state").path("widgets").size();
                if (observations.size() > 10000 || widgetCount > 100000) {
                    throw new AbstractionLimitException("Selected observations or widgets exceed the processing limit");
                }
            }
            for (JsonNode record : evidence.path("executions")) {
                String run = sequences.get(record.path("execution").path("test_sequence").asText());
                require(run != null, "Execution refers to an unavailable sequence");
                if (includedRuns.contains(run)) {
                    executions.add(new Execution(sourceId, run, record));
                }
            }
        }
        require(known.containsAll(selected), "A selected run is unavailable");
        executions.sort(Comparator.comparing(Execution::source).thenComparing(item -> item.record.path("execution").path("id").asText()));
    }
}
