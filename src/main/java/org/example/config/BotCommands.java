package org.example.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class BotCommands {
    private static final Logger log = LoggerFactory.getLogger(BotCommands.class);

    private static final Map<String, List<String>> DEFAULTS = Map.of(
            "run", List.of("/run"),
            "stop", List.of("/stop"),
            "clear", List.of("/clear"),
            "runFree", List.of("/runFree"),
            "stopFree", List.of("/stopFree"),
            "pidory", List.of("/pidory")
    );

    private final Map<String, List<String>> commands;

    private BotCommands(Map<String, List<String>> commands) {
        this.commands = commands;
    }

    public static BotCommands load(Path path) {
        if (path != null && Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
                if (obj != null) {
                    Map<String, List<String>> loaded = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                        loaded.put(entry.getKey(), toList(entry.getValue()));
                    }
                    return new BotCommands(mergeWithDefaults(loaded));
                }
            } catch (IOException e) {
                log.warn("Failed to read commands configuration {}, using defaults", path, e);
            }
        }
        return new BotCommands(new HashMap<>(DEFAULTS));
    }

    private static Map<String, List<String>> mergeWithDefaults(Map<String, List<String>> custom) {
        Map<String, List<String>> combined = new HashMap<>();
        DEFAULTS.forEach((k, v) -> combined.put(k, new ArrayList<>(v)));
        custom.forEach((k, v) -> {
            if (!v.isEmpty()) {
                combined.put(k, new ArrayList<>(new LinkedHashSet<>(v)));
            }
        });
        return combined;
    }

    private static List<String> toList(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyList();
        }
        if (element.isJsonArray()) {
            List<String> items = new ArrayList<>();
            JsonArray array = element.getAsJsonArray();
            for (JsonElement el : array) {
                if (el.isJsonPrimitive()) {
                    items.add(el.getAsString());
                }
            }
            return items;
        }
        if (element.isJsonPrimitive()) {
            return List.of(element.getAsString());
        }
        return Collections.emptyList();
    }

    public boolean isRun(String text) {
        return matches("run", text);
    }

    public boolean isStop(String text) {
        return matches("stop", text);
    }

    public boolean isClear(String text) {
        return matches("clear", text);
    }

    public boolean isRunFree(String text) {
        return matches("runFree", text);
    }

    public boolean isStopFree(String text) {
        return matches("stopFree", text);
    }

    public boolean isPidory(String text) {
        return matches("pidory", text);
    }

    private boolean matches(String key, String text) {
        if (text == null) return false;
        List<String> variants = commands.getOrDefault(key, DEFAULTS.getOrDefault(key, List.of()));
        return variants.stream().anyMatch(cmd -> cmd.equalsIgnoreCase(text));
    }
}


