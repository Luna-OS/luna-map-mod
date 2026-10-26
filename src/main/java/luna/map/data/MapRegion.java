package luna.map.data;

import com.mojang.blaze3d.platform.NativeImage;

import luna.map.LunaMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** 256x256 Bloecke Kartenfarben (ARGB, 0 = noch nicht erkundet) plus GPU-Textur fuer die Weltkarte. */
public final class MapRegion {
	public static final int SIZE = 256;
	public static final int SHIFT = 8;

	public final int x;
	public final int z;
	final int[] colors = new int[SIZE * SIZE];
	/** Hoehe des obersten Blocks (fuer die 3D-Ansicht), NO_HEIGHT = unbekannt. */
	final short[] heights = new short[SIZE * SIZE];
	public static final short NO_HEIGHT = Short.MIN_VALUE;
	/** Biome in 4x4-Feldern (so fein unterscheidet Minecraft sie ohnehin), Nummern aus Biomes. */
	public static final int BIOME_SHIFT = 2;
	public static final int BIOME_SIZE = SIZE >> BIOME_SHIFT;
	final short[] biomes = new short[BIOME_SIZE * BIOME_SIZE];
	/** Wie viele geaenderte Regionen in diesem Bild noch neu hochgeladen werden duerfen (Rest im naechsten Bild). */
	public static int uploadBudget = Integer.MAX_VALUE;
	boolean unsaved;
	/** Zaehlt jede Aenderung an Farben oder Hoehen (die 3D-Ansicht merkt daran, was sie neu rechnen muss). */
	int changes;
	private boolean textureDirty = true;
	private DynamicTexture texture;
	private Identifier textureId;
	long lastUsed;
	int touched;

	MapRegion(int x, int z) {
		this.x = x;
		this.z = z;
		java.util.Arrays.fill(heights, NO_HEIGHT);
	}

	short height(int localX, int localZ) {
		return heights[localZ * SIZE + localX];
	}

	void setHeight(int localX, int localZ, int y) {
		int i = localZ * SIZE + localX;
		short value = y == NO_HEIGHT ? NO_HEIGHT : (short) Math.max(-2048, Math.min(4095, y));
		if (heights[i] != value) {
			heights[i] = value;
			unsaved = true;
			changes++;
		}
	}

	short biome(int localX, int localZ) {
		return biomes[(localZ >> BIOME_SHIFT) * BIOME_SIZE + (localX >> BIOME_SHIFT)];
	}

	void setBiome(int localX, int localZ, short id) {
		int i = (localZ >> BIOME_SHIFT) * BIOME_SIZE + (localX >> BIOME_SHIFT);
		if (biomes[i] != id) {
			biomes[i] = id;
			unsaved = true;
		}
	}

	int get(int localX, int localZ) {
		return colors[localZ * SIZE + localX];
	}

	void set(int localX, int localZ, int argb) {
		int i = localZ * SIZE + localX;
		if (colors[i] != argb) {
			colors[i] = argb;
			unsaved = true;
			textureDirty = true;
			changes++;
		}
	}

	/** Textur fuer die Weltkarte, wird bei Aenderungen neu hochgeladen. Nur im Render-Thread aufrufen. */
	public Identifier texture(String owner, long frame) {
		lastUsed = frame;
		boolean fresh = texture == null;
		if (texture == null) {
			textureId = LunaMap.id("region/" + owner + "/" + x + "_" + z);
			texture = new DynamicTexture(() -> "Luna Map region " + x + "," + z, SIZE, SIZE, false);
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
			textureDirty = true;
		}
		// neue Texturen immer fuellen (sonst steht Speichermuell drin), Aenderungen nur im Rahmen des Budgets
		if (textureDirty && (fresh || uploadBudget-- > 0)) {
			NativeImage image = texture.getPixels();
			for (int lz = 0; lz < SIZE; lz++) {
				for (int lx = 0; lx < SIZE; lx++) {
					image.setPixel(lx, lz, colors[lz * SIZE + lx]);
				}
			}
			texture.upload();
			textureDirty = false;
		}
		return textureId;
	}

	boolean hasTexture() {
		return texture != null;
	}

	void releaseTexture() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			texture = null;
			textureId = null;
		}
	}
}
