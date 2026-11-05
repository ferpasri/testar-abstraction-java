package org.testar.abstraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;
import static org.testar.abstraction.Json.*;

/** Prepare published fixtures for development tests. */
public final class PrepareConformance {
    private static final Path CACHE = Path.of(".cache", "specs");

    private PrepareConformance() {
    }

    public static void main(String[] arguments) throws Exception {
        JsonNode releases = Json.parse(new String(download("https://api.github.com/repos/ferpasri/testar-abstraction-specs/releases"), java.nio.charset.StandardCharsets.UTF_8));
        JsonNode release = list(releases).stream().filter(item -> !item.path("draft").asBoolean())
            .max(Comparator.comparing(item -> item.path("published_at").asText())).orElseThrow();
        byte[] archive = download(release.path("zipball_url").asText());
        String digest = digest(archive);
        Files.createDirectories(CACHE);
        Path temporary = Files.createTempDirectory(CACHE, "download-").toAbsolutePath().normalize();
        ObjectNode files = object();
        try {
            long total = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String[] parts = entry.getName().split("/", 2);
                    if (entry.isDirectory() || parts.length != 2 || !parts[1].startsWith("conformance/")) {
                        continue;
                    }
                    String name = parts[1];
                    Path path = temporary.resolve(name).normalize();
                    require(path.startsWith(temporary) && !name.contains("\\") && !files.has(name), "Unsafe or repeated archive path");
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        total += count;
                        require(total <= 64 * 1024 * 1024, "Expanded archive exceeds 64 MiB");
                        content.write(buffer, 0, count);
                    }
                    byte[] bytes = content.toByteArray();
                    Files.createDirectories(path.getParent());
                    Files.write(path, bytes);
                    files.put(name, digest(bytes));
                }
            }
            JsonNode manifest = Json.parse(Files.readString(temporary.resolve("conformance/manifest.json")));
            verifyCompatibility(manifest);
            int count = 0;
            for (JsonNode suite : manifest.get("suites")) {
                for (JsonNode name : suite.get("cases")) {
                    for (String file : java.util.List.of("input.json", "rules.json", "expected.json")) {
                        Json.parse(Files.readString(temporary.resolve("conformance").resolve(suite.path("directory").asText()).resolve(name.asText()).resolve(file)));
                    }
                    count++;
                }
            }
            Path directory = CACHE.resolve(digest);
            if (!Files.exists(directory)) {
                Files.move(temporary, directory);
            }
            ObjectNode selection = object("release", release.get("tag_name"), "directory", digest, "archive_sha256", digest, "files", files);
            Path pending = CACHE.resolve("current.tmp");
            Files.writeString(pending, selection.toPrettyString());
            Files.move(pending, CACHE.resolve("current.json"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Prepared " + release.path("tag_name").asText() + ": " + count + " shared cases");
        } finally {
            if (Files.exists(temporary)) {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    static Path prepared() throws Exception {
        require(Files.exists(CACHE.resolve("current.json")), "Prepare shared cases with the documented Maven command before testing");
        JsonNode selection = Json.parse(Files.readString(CACHE.resolve("current.json")));
        Path directory = CACHE.resolve(selection.path("directory").asText()).toAbsolutePath().normalize();
        require(directory.startsWith(CACHE.toAbsolutePath().normalize()), "Unsafe cache selection");
        var entries = selection.path("files").properties().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            Path path = directory.resolve(entry.getKey()).normalize();
            require(path.startsWith(directory) && digest(Files.readAllBytes(path)).equals(entry.getValue().asText()), "Prepared conformance file changed");
        }
        verifyCompatibility(Json.parse(Files.readString(directory.resolve("conformance/manifest.json"))));
        return directory.resolve("conformance");
    }

    private static void verifyCompatibility(JsonNode manifest) throws Exception {
        var fields = Json.parse(Files.readString(Path.of("compatibility.json"))).properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            require(field.getValue().equals(manifest.get(field.getKey())), "Unsupported published conformance behavior");
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "testar-abstraction-conformance").build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            require(response.statusCode() == 200, "Conformance download failed: HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
            require(bytes.length <= 16 * 1024 * 1024, "Download exceeds 16 MiB");
            return bytes;
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
