package com.veriborder.client.ui;

import net.minecraft.client.gui.GuiGraphics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 26.4.1 preview thumbnails for the custom cover base maps.
 *
 * <p>The registry stores a base map as a content hash plus (inside a snapshot) the
 * PNG itself as base64. The editor has to show a thumbnail of a map the client
 * file system does not have, so the payload is decoded once and downsampled to a
 * tiny {@value #GRID}x{@value #GRID} colour grid, which is then painted with plain
 * {@code GuiGraphics} fills.</p>
 *
 * <p>Deliberately no OpenGL texture is registered: a thumbnail is 8x8 cells, a
 * GL texture would add upload/eviction lifecycle to an editor that opens for a
 * few seconds, and a missing/oversized payload then simply degrades to "no
 * preview" instead of an invalid texture id.</p>
 *
 * <p>Decoding is defensive by design (base64, PNG header, pixel access all fail
 * for a corrupted payload) and results are cached per hash.</p>
 */
public final class TemplateTextures {
    /** Thumbnail resolution sampled from the uploaded PNG. */
    public static final int GRID = 8;

    private static final Map<String, int[][]> CACHE = new HashMap<>();
    private static final int CACHE_LIMIT = 64;

    private TemplateTextures() {
    }

    /**
     * Downsampled thumbnail of one base map.
     *
     * @param hash  base-map hash (cache key); may be blank for an unnamed payload
     * @param data  base64 PNG, as published in the registry snapshot
     * @return {@value #GRID}x{@value #GRID} ARGB cells, or {@code null} when the
     *         payload is missing or cannot be decoded
     */
    public static int[][] thumbnail(String hash, String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        String key = (hash == null ? "" : hash.trim()) + ":" + data.length();
        synchronized (CACHE) {
            int[][] cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        int[][] grid = decode(data);
        if (grid == null) {
            return null;
        }
        synchronized (CACHE) {
            if (CACHE.size() >= CACHE_LIMIT) {
                CACHE.clear();
            }
            CACHE.put(key, grid);
        }
        return grid;
    }

    /** Forgets every cached thumbnail (tests / language reload). */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static int[][] decode(String data) {
        try {
            String payload = data.trim();
            int comma = payload.indexOf(',');
            if (payload.startsWith("data:") && comma > 0) {
                payload = payload.substring(comma + 1);
            }
            byte[] bytes = Base64.getDecoder().decode(payload);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            int[][] grid = new int[GRID][GRID];
            for (int row = 0; row < GRID; row++) {
                for (int column = 0; column < GRID; column++) {
                    int x = Math.min(image.getWidth() - 1, column * image.getWidth() / GRID);
                    int y = Math.min(image.getHeight() - 1, row * image.getHeight() / GRID);
                    grid[row][column] = image.getRGB(x, y);
                }
            }
            return grid;
        } catch (RuntimeException | java.io.IOException failure) {
            return null;
        }
    }

    /**
     * Paints a decoded thumbnail into a {@code size x size} box. Cells are
     * ordered row by row; a semi-transparent pixel keeps its alpha so a map with
     * cut-outs shows the panel behind it.
     */
    public static void draw(GuiGraphics graphics, int x, int y, int size, int[][] grid) {
        if (grid == null || size <= 0) {
            return;
        }
        graphics.fill(x, y, x + size, y + size, 0xFF0B1520);
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                int cellX = x + column * size / GRID;
                int cellY = y + row * size / GRID;
                int cellRight = x + (column + 1) * size / GRID;
                int cellBottom = y + (row + 1) * size / GRID;
                graphics.fill(cellX, cellY, Math.max(cellX + 1, cellRight),
                        Math.max(cellY + 1, cellBottom), grid[row][column]);
            }
        }
        graphics.renderOutline(x, y, size, size, 0xFF2B4056);
    }
}
