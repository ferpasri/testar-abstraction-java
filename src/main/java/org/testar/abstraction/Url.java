package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.testar.abstraction.Json.*;

final class Url {
    private Url() {
    }

    static String normalize(String value, JsonNode transform) {
        try {
            // Preserve malformed escapes in path parameters for name comparison.
            String escaped = value.replaceAll("%(?![a-fA-F0-9]{2})", "%25");
            URI address = URI.create(escaped);
            require(address.isAbsolute(), "Expected an absolute URL");
            String scheme = address.getScheme().toLowerCase(Locale.ROOT);
            String authority = address.getRawAuthority();
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("ftp")) {
                require(authority != null && address.getHost() != null, "Expected an absolute URL host");
                String host = address.getHost().toLowerCase(Locale.ROOT);
                int port = address.getPort();
                boolean defaultPort = port == 80 && scheme.equals("http") || port == 443 && scheme.equals("https")
                    || port == 21 && scheme.equals("ftp");
                authority = (address.getRawUserInfo() == null ? "" : address.getRawUserInfo() + "@") + host
                    + (port < 0 || defaultPort ? "" : ":" + port);
            }
            String path = address.getRawPath();
            if (path == null) {
                path = address.getRawSchemeSpecificPart().split("\\?", 2)[0];
            }
            path = restoreMalformed(path, value);
            List<String> segments = new ArrayList<>();
            for (String segment : path.split("/", -1)) {
                String[] pieces = segment.split(";", -1);
                List<String> kept = new ArrayList<>();
                kept.add(pieces[0]);
                for (int i = 1; i < pieces.length; i++) {
                    String name = decodePathName(pieces[i].split("=", 2)[0]);
                    boolean remove = name != null && list(optional(transform, "remove_path_parameters")).stream()
                        .anyMatch(item -> item.asText().equals(name));
                    if (!remove) {
                        kept.add(pieces[i]);
                    }
                }
                segments.add(String.join(";", kept));
            }
            path = String.join("/", segments);
            if (authority != null && path.isEmpty()) {
                path = "/";
            }
            String query = address.getRawQuery();
            if (transform.has("remove_query") && !transform.get("remove_query").isEmpty() && query != null) {
                List<String> pairs = new ArrayList<>();
                for (String pair : query.split("&")) {
                    if (pair.isEmpty()) {
                        continue;
                    }
                    String[] parts = pair.split("=", 2);
                    String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    if (list(transform.get("remove_query")).stream().noneMatch(item -> item.asText().equals(key))) {
                        String reading = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                        pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(reading, StandardCharsets.UTF_8));
                    }
                }
                query = String.join("&", pairs);
            }
            String fragment = transform.path("remove_fragment").asBoolean() ? null : address.getRawFragment();
            return scheme + ":" + (authority == null ? "" : "//" + authority) + path
                + (query == null || query.isEmpty() ? "" : "?" + query)
                + (fragment == null || fragment.isEmpty() ? "" : "#" + fragment);
        } catch (IllegalArgumentException error) {
            throw new AbstractionException("The selected value is not an absolute URL");
        }
    }

    private static String restoreMalformed(String path, String original) {
        // Undo only escapes introduced to let URI parse the original address.
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("%(?![a-fA-F0-9]{2})").matcher(original);
        while (matcher.find()) {
            String suffix = original.substring(matcher.start() + 1, Math.min(original.length(), matcher.start() + 3));
            path = path.replace("%25" + suffix, "%" + suffix);
        }
        return path;
    }

    private static String decodePathName(String name) {
        try {
            if (name.matches(".*%(?![a-fA-F0-9]{2}).*")) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int offset = 0; offset < name.length();) {
                if (name.charAt(offset) == '%') {
                    bytes.write(Integer.parseInt(name.substring(offset + 1, offset + 3), 16));
                    offset += 3;
                } else {
                    int point = name.codePointAt(offset);
                    bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
                    offset += Character.charCount(point);
                }
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (IllegalArgumentException | CharacterCodingException error) {
            return null;
        }
    }
}
