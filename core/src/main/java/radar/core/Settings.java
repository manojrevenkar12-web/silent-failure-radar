package radar.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads config/*.json. Each reader names its file, so tests can check the world never reads the radar's settings. */
public final class Settings {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Settings() {}

    public static JsonNode load(String name) {
        Path p = Path.of("config", name + ".json");
        try {
            if (Files.exists(p)) return JSON.readTree(Files.readString(p));
            try (InputStream in = Settings.class.getResourceAsStream("/config/" + name + ".json")) {
                if (in == null) throw new IllegalStateException("missing config/" + name + ".json");
                return JSON.readTree(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read config/" + name + ".json", e);
        }
    }
}
