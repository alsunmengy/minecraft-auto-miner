package com.nous.autominer.schematic;

import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Reads Litematica (.litematic) blueprint files and extracts material lists.
 * <p>
 * Litematica uses a palette-based NBT format:
 * - Palette maps block names to indices
 * - BlockStates is a compacted long array storing palette indices
 * - Size defines the XYZ dimensions
 * <p>
 * For the auto-miner, we mainly need the palette to determine required materials.
 */
public class SchematicReader {
    private static final Logger LOGGER = LoggerFactory.getLogger("auto-miner-schematic");

    private String name = "";
    private String author = "";
    private String description = "";
    private int[] size = new int[]{0, 0, 0};
    private int[] position = new int[]{0, 0, 0};
    private List<String> palette = new ArrayList<>();
    private long[] blockStates = new long[0];
    private boolean loaded = false;

    /**
     * Load and parse a .litematic file.
     *
     * @param filePath Path to the .litematic file
     * @return true if loaded successfully
     */
    public boolean load(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.getName().endsWith(".litematic")) {
            LOGGER.warn("File not found or not a .litematic: {}", filePath);
            return false;
        }

        try {
            NbtCompound root = NbtIo.readCompressed(file.toPath(), NbtSizeTracker.ofUnlimitedBytes());

            // Metadata
            NbtCompound meta = root.getCompound("Metadata").orElse(null);
            if (meta != null) {
                name = meta.getString("Name").orElse(file.getName());
                author = meta.getString("Author").orElse("");
                description = meta.getString("Description").orElse("");
            }

            // Regions
            NbtCompound regions = root.getCompound("Regions").orElse(null);
            if (regions == null || regions.getKeys().isEmpty()) {
                LOGGER.warn("No regions in .litematic file");
                return false;
            }

            // Process the first region
            String firstRegion = regions.getKeys().iterator().next();
            NbtCompound region = regions.getCompound(firstRegion).orElse(null);
            if (region == null) {
                LOGGER.warn("Region '{}' is empty", firstRegion);
                return false;
            }

            // Size
            size = region.getIntArray("Size").orElse(new int[]{0, 0, 0});
            if (size.length < 3) {
                LOGGER.warn("Invalid size in .litematic");
                return false;
            }

            // Position
            position = region.getIntArray("Position").orElse(new int[]{0, 0, 0});

            // Palette — read mappings
            palette = new ArrayList<>();
            NbtCompound paletteTag = region.getCompound("Palette").orElse(null);
            if (paletteTag != null) {
                for (String blockName : paletteTag.getKeys()) {
                    int index = paletteTag.getInt(blockName).orElse(0);
                    while (palette.size() <= index) {
                        palette.add(null);
                    }
                    palette.set(index, blockName);
                }
            }

            // BlockStates
            blockStates = region.getLongArray("BlockStates").orElse(new long[0]);

            loaded = true;
            LOGGER.info("Loaded schematic '{}' by {} ({}x{}x{}, {} unique blocks)",
                    name, author, size[0], size[1], size[2], palette.size());
            return true;

        } catch (IOException e) {
            LOGGER.error("Failed to load .litematic: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the list of unique block IDs (material list) from the palette,
     * excluding air.
     *
     * @return Map of block ID → count needed
     */
    public Map<String, Integer> getMaterialList() {
        Map<String, Integer> materials = new LinkedHashMap<>();

        if (!loaded || blockStates.length == 0) {
            return materials;
        }

        int totalBlocks = size[0] * size[1] * size[2];
        int bitsPerBlock = Math.max(2, 64 - Long.numberOfLeadingZeros(palette.size() - 1));
        long mask = (1L << bitsPerBlock) - 1;

        for (int i = 0; i < totalBlocks; i++) {
            int longIndex = (i * bitsPerBlock) / 64;
            int bitOffset = (i * bitsPerBlock) % 64;
            int paletteIndex;

            if (longIndex >= blockStates.length) break;

            if (bitOffset + bitsPerBlock <= 64) {
                paletteIndex = (int) ((blockStates[longIndex] >>> bitOffset) & mask);
            } else {
                // Spans two longs
                int bitsInFirst = 64 - bitOffset;
                int bitsInSecond = bitsPerBlock - bitsInFirst;
                long firstPart = blockStates[longIndex] >>> bitOffset;
                long secondPart = blockStates[longIndex + 1] << bitsInFirst >>> (64 - bitsInSecond);
                paletteIndex = (int) ((firstPart | secondPart) & mask);
            }

            if (paletteIndex >= 0 && paletteIndex < palette.size()) {
                String blockName = palette.get(paletteIndex);
                if (blockName != null && !isAir(blockName)) {
                    materials.merge(blockName, 1, Integer::sum);
                }
            }
        }

        return materials;
    }

    /**
     * Get a formatted material summary string for the LLM.
     */
    public String getMaterialSummary() {
        Map<String, Integer> materials = getMaterialList();
        if (materials.isEmpty()) {
            return "No materials needed (empty schematic or air only)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Materials needed for '").append(name).append("':\n");
        int total = 0;
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            // Strip "minecraft:" prefix for readability
            String display = entry.getKey().replace("minecraft:", "");
            sb.append("  - ").append(display).append(": ").append(entry.getValue()).append("\n");
            total += entry.getValue();
        }
        sb.append("Total non-air blocks: ").append(total);
        return sb.toString();
    }

    /**
     * Check if the loaded schematic has a specific block type.
     */
    public boolean containsBlock(String blockId) {
        String fullId = blockId.contains(":") ? blockId : "minecraft:" + blockId;
        for (String entry : palette) {
            if (entry != null && entry.equals(fullId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAir(String blockName) {
        return blockName.equals("minecraft:air") || blockName.equals("air");
    }

    // --- Getters ---

    public String getName() { return name; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public int[] getSize() { return size; }
    public int[] getPosition() { return position; }
    public List<String> getPalette() { return Collections.unmodifiableList(palette); }
    public boolean isLoaded() { return loaded; }
    public int getTotalBlocks() { return size[0] * size[1] * size[2]; }

    /**
     * Scan the schematics directory for available blueprints.
     *
     * @param schematicsDir Path to the Litematica schematics folder
     * @return List of .litematic file names
     */
    public static List<String> scanSchematicsDir(String schematicsDir) {
        List<String> schematics = new ArrayList<>();
        File dir = new File(schematicsDir);
        if (!dir.isDirectory()) return schematics;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".litematic"));
        if (files != null) {
            for (File f : files) {
                schematics.add(f.getName());
            }
        }
        Collections.sort(schematics);
        return schematics;
    }
}
