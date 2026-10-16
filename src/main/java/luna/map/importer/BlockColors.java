package luna.map.importer;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;

/** Rechnet Block-IDs und Biome aus fremden Kartendaten in Luna-Map-Farben um (gleiche Logik wie der Chunk-Scanner). */
final class BlockColors {
	private final Registry<Biome> biomes;
	private final Biome fallbackBiome;
	private final Map<String, MapColor> mapColors = new HashMap<>();
	private final Map<String, Biome> biomeCache = new HashMap<>();

	BlockColors(Registry<Biome> biomes) {
		this.biomes = biomes;
		this.fallbackBiome = biomes.getValue(Identifier.withDefaultNamespace("plains"));
	}

	private MapColor mapColor(String blockId) {
		return mapColors.computeIfAbsent(blockId, id -> {
			Identifier key = Identifier.tryParse(id);
			Block block = key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(key);
			try {
				return block.defaultBlockState().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			} catch (RuntimeException e) {
				return MapColor.NONE;
			}
		});
	}

	private Biome biome(String biomeId) {
		if (biomeId == null) {
			return fallbackBiome;
		}
		return biomeCache.computeIfAbsent(biomeId, id -> {
			Identifier key = Identifier.tryParse(id);
			Biome biome = key == null ? null : biomes.getValue(key);
			return biome != null ? biome : fallbackBiome;
		});
	}

	/** Farbe einer Saeule, northHeight fuer die Hoehenschattierung. 0 = nichts zeichnen. */
	int color(String blockId, int height, int top, boolean waterOverlay, String biomeId, int x, int z, int northHeight) {
		Biome biome = biome(biomeId);
		if (waterOverlay || "minecraft:water".equals(blockId)) {
			int water = biome == null ? 0x3F76E4 : biome.getWaterColor();
			int depth = Math.max(0, top - height);
			return shade(water, 1.05f - Math.min(depth, 12) * 0.045f);
		}
		MapColor mapColor = mapColor(blockId);
		if (mapColor == MapColor.NONE) {
			return 0;
		}
		int rgb;
		if (mapColor == MapColor.GRASS && biome != null) {
			rgb = biome.getGrassColor(x, z);
		} else if (mapColor == MapColor.PLANT && biome != null) {
			rgb = shade(biome.getFoliageColor(), 0.85f);
		} else {
			rgb = mapColor.col;
		}
		float light = height > northHeight ? 1.12f : height < northHeight ? 0.84f : 1f;
		return shade(rgb, light);
	}

	static int shade(int rgb, float factor) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
		int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
