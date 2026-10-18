package luna.map.ui;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import luna.map.LunaMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mob-Koepfe fuers Radar. Das Gesicht wird aus dem Modell des Mobs gelesen (Teil "head", vordere Flaeche), die
 * Textur kommt vom Renderer – so passen auch Varianten (Farben, Babys) und viele Mods. Geht etwas nicht, bleibt
 * es beim farbigen Punkt.
 */
final class MobHeads {
	/** Gesicht als Ausschnitt der Textur, Koordinaten 0..1. */
	private record Face(float u0, float v0, float u1, float v1) {
	}

	private record CachedTexture(Identifier id, long until) {
	}

	/** Virtuelle Texturgroesse fuer blit (UVs sind normiert, so bleiben sie ganzzahlig genau). */
	private static final int VIRTUAL = 4096;
	private static final long TEXTURE_CACHE_MS = 1500;

	private static final Map<EntityType<?>, Face> FACES = new HashMap<>();
	private static final Map<Integer, CachedTexture> TEXTURES = new HashMap<>();
	private static Field cubesField;
	private static Field childrenField;
	private static boolean reflectionFailed;
	private static long lastCleanup;

	private MobHeads() {
	}

	/**
	 * Zeichnet den Kopf des Mobs mittig auf (x, y). border = Rahmenfarbe (gelb feindlich, gruen friedlich).
	 * false, wenn es keinen Kopf gibt – dann zeichnet der Aufrufer den Punkt.
	 */
	static boolean draw(GuiGraphicsExtractor graphics, Entity entity, double x, double y, int size, int border, float alpha) {
		if (!(entity instanceof LivingEntity living)) {
			return false;
		}
		Face face = face(living);
		if (face == null) {
			return false;
		}
		Identifier texture = texture(living);
		if (texture == null) {
			return false;
		}
		int a = (int) (alpha * 255) << 24;
		int x0 = (int) Math.round(x - size / 2.0);
		int y0 = (int) Math.round(y - size / 2.0);
		graphics.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, a | (border & 0xFFFFFF));
		int u = Math.round(Math.min(face.u0(), face.u1()) * VIRTUAL);
		int v = Math.round(Math.min(face.v0(), face.v1()) * VIRTUAL);
		int uw = Math.round(Math.abs(face.u1() - face.u0()) * VIRTUAL);
		int vh = Math.round(Math.abs(face.v1() - face.v0()) * VIRTUAL);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x0, y0, u, v, size, size, uw, vh, VIRTUAL, VIRTUAL, a | 0xFFFFFF);
		return true;
	}

	/** Aktuelle Textur des Mobs (kurz zwischengespeichert, weil der Renderer dafuer einiges ausrechnet). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Identifier texture(LivingEntity entity) {
		long now = System.currentTimeMillis();
		if (now - lastCleanup > 10_000) {
			lastCleanup = now;
			TEXTURES.values().removeIf(c -> c.until() < now);
		}
		CachedTexture cached = TEXTURES.get(entity.getId());
		if (cached != null && cached.until() > now) {
			return cached.id();
		}
		Identifier id = null;
		try {
			EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
			if (renderer instanceof LivingEntityRenderer living) {
				EntityRenderState state = renderer.createRenderState(entity, 1f);
				id = living.getTextureLocation((net.minecraft.client.renderer.entity.state.LivingEntityRenderState) state);
			}
		} catch (RuntimeException e) {
			id = null;
		}
		TEXTURES.put(entity.getId(), new CachedTexture(id, now + TEXTURE_CACHE_MS));
		return id;
	}

	private static Face face(LivingEntity entity) {
		EntityType<?> type = entity.getType();
		if (FACES.containsKey(type)) {
			return FACES.get(type);
		}
		Face face = null;
		try {
			EntityRenderer<?, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
			if (renderer instanceof LivingEntityRenderer<?, ?, ?> living) {
				ModelPart head = findPart(living.getModel().root(), "head");
				if (head != null) {
					face = frontFace(head);
				}
			}
		} catch (RuntimeException | ReflectiveOperationException e) {
			LunaMap.LOGGER.debug("Kein Radar-Kopf fuer {}", type, e);
		}
		FACES.put(type, face);
		return face;
	}

	/** Teil mit dem Namen irgendwo im Modellbaum suchen (Breitensuche). */
	@SuppressWarnings("unchecked")
	private static ModelPart findPart(ModelPart root, String name) throws ReflectiveOperationException {
		if (!reflect()) {
			return null;
		}
		ArrayDeque<ModelPart> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			ModelPart part = queue.poll();
			Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
			if (children.containsKey(name)) {
				return children.get(name);
			}
			queue.addAll(children.values());
		}
		return null;
	}

	/** Vordere Flaeche (zeigt nach -Z) des groessten Wuerfels im Kopf. */
	@SuppressWarnings("unchecked")
	private static Face frontFace(ModelPart head) throws ReflectiveOperationException {
		List<ModelPart.Cube> cubes = (List<ModelPart.Cube>) cubesField.get(head);
		ModelPart.Cube best = null;
		float bestVolume = 0;
		for (ModelPart.Cube cube : cubes) {
			float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
			if (volume > bestVolume) {
				bestVolume = volume;
				best = cube;
			}
		}
		if (best == null) {
			return null;
		}
		for (ModelPart.Polygon polygon : best.polygons) {
			if (polygon.normal().z() < -0.9f) {
				float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
				for (ModelPart.Vertex vertex : polygon.vertices()) {
					u0 = Math.min(u0, vertex.u());
					v0 = Math.min(v0, vertex.v());
					u1 = Math.max(u1, vertex.u());
					v1 = Math.max(v1, vertex.v());
				}
				if (u1 - u0 > 0 && v1 - v0 > 0) {
					return new Face(u0, v0, u1, v1);
				}
			}
		}
		return null;
	}

	private static boolean reflect() {
		if (cubesField != null) {
			return true;
		}
		if (reflectionFailed) {
			return false;
		}
		try {
			cubesField = ModelPart.class.getDeclaredField("cubes");
			cubesField.setAccessible(true);
			childrenField = ModelPart.class.getDeclaredField("children");
			childrenField.setAccessible(true);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			reflectionFailed = true;
			cubesField = null;
			LunaMap.LOGGER.warn("Radar-Koepfe nicht verfuegbar: {}", e.toString());
			return false;
		}
	}

	/** Bei Ressourcen-Neuladen (Texturpakete) alles neu bestimmen. */
	static void clear() {
		FACES.clear();
		TEXTURES.clear();
	}
}
