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
	boolean unsaved;
	private boolean textureDirty = true;
	private DynamicTexture texture;
	private Identifier textureId;
	long lastUsed;
	int touched;

	MapRegion(int x, int z) {
		this.x = x;
		this.z = z;
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
		}
	}

	/** Textur fuer die Weltkarte, wird bei Aenderungen neu hochgeladen. Nur im Render-Thread aufrufen. */
	public Identifier texture(String owner, long frame) {
		lastUsed = frame;
		if (texture == null) {
			textureId = LunaMap.id("region/" + owner + "/" + x + "_" + z);
			texture = new DynamicTexture(() -> "Luna Map region " + x + "," + z, SIZE, SIZE, false);
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
			textureDirty = true;
		}
		if (textureDirty) {
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
