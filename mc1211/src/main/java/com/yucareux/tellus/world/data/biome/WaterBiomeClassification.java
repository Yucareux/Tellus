package com.yucareux.tellus.world.data.biome;

import com.yucareux.tellus.Tellus;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WaterBiomeClassification {
    private static final String RESOURCE_PATH = "tellus/biome/water_biome_classification_system.csv";
    private static final Map<Integer, Map<String, ResourceKey<Biome>>> BIOME_MAP = new HashMap<>();
    private static final Map<Integer, ResourceKey<Biome>> FALLBACK_MAP = new HashMap<>();
    private static boolean loaded;

    private WaterBiomeClassification() {
    }

    public static ResourceKey<Biome> findBiomeKey(Boolean isOcean, String koppenCode) {
        ensureLoaded();
        if (koppenCode == null) {
            return null;
        } else {
            Map<String, ResourceKey<Biome>> byKoppen = BIOME_MAP.get(isOcean ? 1 : 0);
            return byKoppen == null ? null : byKoppen.get(koppenCode.toUpperCase(Locale.ROOT));
        }
    }

    public static ResourceKey<Biome> findFallbackKey(boolean isOcean) {
        ensureLoaded();
        return FALLBACK_MAP.get(isOcean ? 1 : 0);
    }


    private static void ensureLoaded() {
        if (!loaded) {
            synchronized (WaterBiomeClassification.class) {
                if (!loaded) {
                    load();
                    loaded = true;
                }
            }
        }
    }

    private static void load() {
        InputStream input = WaterBiomeClassification.class.getClassLoader().getResourceAsStream(RESOURCE_PATH);
        if (input == null) {
            Tellus.LOGGER.warn("Biome classification mapping not found at {}", RESOURCE_PATH);
        } else {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                boolean header = true;

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        if (header) {
                            header = false;
                        } else {
                            List<String> fields = parseCsvLine(line);
                            if (fields.size() >= 5) {
                                int esaCode;
                                try {
                                    esaCode = Integer.parseInt(fields.get(0).trim());
                                } catch (NumberFormatException var11) {
                                    continue;
                                }

                                String koppenCode = fields.get(2).trim();
                                String biomeId = fields.get(4).trim();
                                if (!biomeId.isEmpty()) {
                                    ResourceKey<Biome> biomeKey = toBiomeKey(biomeId);
                                    if ("NONE".equalsIgnoreCase(koppenCode)) {
                                        FALLBACK_MAP.put(esaCode, biomeKey);
                                    } else {
                                        String normalized = koppenCode.toUpperCase(Locale.ROOT);
                                        BIOME_MAP.computeIfAbsent(esaCode, unused -> new HashMap<>()).put(normalized, biomeKey);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException var13) {
                Tellus.LOGGER.warn("Failed to read biome classification mapping", var13);
            }
        }
    }

    private static ResourceKey<Biome> toBiomeKey(String biomeId) {
        ResourceLocation id = biomeId.contains(":") ? ResourceLocation.tryParse(biomeId) : ResourceLocation.fromNamespaceAndPath("minecraft", biomeId);
        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath("minecraft", "plains");
        }

        return ResourceKey.create(Registries.BIOME, id);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        fields.add(current.toString());
        return fields;
    }
}
