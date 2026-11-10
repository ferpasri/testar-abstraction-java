package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Json() {
    }

    static JsonNode parse(String text) {
        try {
            JsonNode value = MAPPER.readTree(text);
            require(value != null, "Expected JSON");
            return value;
        } catch (java.io.IOException error) {
            throw new AbstractionException("Invalid JSON: " + error.getMessage());
        }
    }

    static ObjectNode object(Object... pairs) {
        ObjectNode result = MAPPER.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            result.set((String) pairs[i], pairs[i + 1] instanceof JsonNode node ? node : MAPPER.valueToTree(pairs[i + 1]));
        }
        return result;
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    static List<JsonNode> list(JsonNode value) {
        List<JsonNode> result = new ArrayList<>();
        if (value != null) {
            value.forEach(result::add);
        }
        return result;
    }

    static JsonNode optional(JsonNode value, String key) {
        return value.has(key) ? value.get(key) : array();
    }

    static String canonical(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue().signum() == 0 ? "0" : value.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (value.isArray()) {
            return "[" + String.join(",", list(value).stream().map(Json::canonical).toList()) + "]";
        }
        if (value.isObject()) {
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            return "{" + String.join(",", keys.stream().map(key ->
                MAPPER.valueToTree(key).toString() + ":" + canonical(value.get(key))).toList()) + "}";
        }
        return value.toString();
    }

    static String hash(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String referenceKey(JsonNode reference) {
        ArrayNode parts = array().add(reference.path("source")).add(reference.path("run")).add(reference.path("observation"));
        if (reference.has("widget")) {
            parts.add(reference.get("widget"));
        }
        return canonical(parts);
    }

    static String pair(String first, String second) {
        return canonical(array().add(first).add(second));
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AbstractionException(message);
        }
    }

    static final class Budget {
        private long bytes;

        void account(JsonNode value) {
            bytes += canonical(value).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 32 * 1024 * 1024) {
                throw new AbstractionLimitException("Comparison data exceeds 32 MiB");
            }
        }
    }
}
