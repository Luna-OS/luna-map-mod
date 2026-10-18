package luna.map.ui;

import com.mojang.blaze3d.platform.InputConstants;

import luna.map.LunaColors;
import luna.map.data.LodCache;
import luna.map.data.MapRegion;
import luna.map.data.MapSession;
import luna.map.data.MapStore;
import luna.map.data.Waypoint;
import luna.map.online.BlueMapHires;
import luna.map.online.BlueMapOverview;
import luna.map.online.BlueMapSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Vollbild-Weltkarte: ziehen zum Verschieben, Mausrad zum Zoomen, Rechtsklick fuer einen Wegpunkt. */
public class WorldMapScreen extends Screen {
	private static final double MIN_SCALE = 1 / 48.0;
	private static final double MAX_SCALE = 32;
	private static double savedScale = 1;
	/** 3D: Neigung (0 = von oben, 1 = flach) und Drehung (Bogenmass, 0 = Norden oben). */
	private static double savedTilt;
	private static double savedYaw;
	/** In 3D nicht weiter herauszoomen, sonst wird es zu weit und zu teuer. */
	private static final double MIN_SCALE_3D = 0.35;

	private double centerX;
	private double centerZ;
	private double scale = savedScale;
	private double tilt = savedTilt;
	private double yaw = savedYaw;
	private long frame;
	private long lastInteraction;
	private final Map3DView view3d = new Map3DView();
	private Button toggle3d;

	public WorldMapScreen() {
		super(Component.translatable("lunamap.worldmap.title"));
	}

	@Override
	protected void init() {
		LocalPlayer player = minecraft.player;
		if (player != null && frame == 0) {
			centerX = player.getX();
			centerZ = player.getZ();
		}
		int y = height - 24;
		addRenderableWidget(Button.builder(Component.translatable("lunamap.worldmap.center"), b -> {
			if (minecraft.player != null) {
				centerX = minecraft.player.getX();
				centerZ = minecraft.player.getZ();
			}
		}).bounds(4, y, 90, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoints"),
				b -> minecraft.setScreenAndShow(new WaypointListScreen(this))).bounds(98, y, 90, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("lunamap.settings"),
				b -> minecraft.setScreenAndShow(new LunaScreen(this))).bounds(192, y, 90, 20).build());
		toggle3d = addRenderableWidget(Button.builder(Component.literal(in3d() ? "2D" : "3D"), b -> {
			if (in3d()) {
				tilt = 0;
				yaw = 0;
			} else {
				tilt = 0.55;
				scale = Math.max(scale, 1);
			}
			remember();
		}).bounds(286, y, 40, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("lunamap.worldmap.3d_hint")))
				.build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width - 94, y, 90, 20).build());
	}

	private boolean in3d() {
		return tilt > 0.001 || Math.abs(yaw) > 0.001;
	}

	private void remember() {
		savedTilt = tilt;
		savedYaw = yaw;
		savedScale = scale;
		lastInteraction = System.currentTimeMillis();
		if (toggle3d != null) {
			toggle3d.setMessage(Component.literal(in3d() ? "2D" : "3D"));
		}
	}

	/** Bildschirmposition eines Punktes in der Welt (2D oder 3D), null wenn nicht sichtbar. */
	private float[] toScreen(double x, double y, double z) {
		if (in3d()) {
			return view3d.project(x, y, z);
		}
		return new float[] {(float) screenXd(x), (float) screenYd(z)};
	}

	/** Blockposition unter der Maus, null ueber dem Horizont. */
	private double[] fromScreen(double mouseX, double mouseY) {
		if (in3d()) {
			return view3d.ground(mouseX, mouseY);
		}
		return new double[] {centerX + (mouseX - width / 2.0) / scale, centerZ + (mouseY - height / 2.0) / scale};
	}

	@Override
	public void removed() {
		view3d.release();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, LunaColors.NIGHT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		frame++;
		MapStore store = MapSession.viewStore();
		if (store != null) {
			// alles Kartige nur zwischen Kopf- und Fusszeile (sonst ragen z. B. Spielernamen hinein)
			graphics.enableScissor(0, 16, width, height - 28);
			boolean threeD = in3d();
			if (threeD) {
				boolean moving = System.currentTimeMillis() - lastInteraction < 250;
				view3d.draw(graphics, store, centerX, centerZ, scale, yaw, tilt, width, height, moving);
				// blockgenaue BlueMap-Kacheln (mit Hoehen) fuer den Bereich holen
				int reach = (int) Math.min(1200, height / scale * 2 + 300);
				BlueMapSource.request((int) centerX - reach, (int) centerZ - reach, (int) centerX + reach, (int) centerZ + reach);
			} else {
				drawRegions(graphics, store);
			}
			// nah dran: BlueMap-Details mit echten Block-Texturen obendrauf
			if (!threeD && scale >= BlueMapHires.MIN_PIXELS_PER_BLOCK && !MapSession.underground()) {
				double halfW = width / 2.0 / scale;
				double halfH = height / 2.0 / scale;
				BlueMapHires.draw(graphics, centerX - halfW, centerZ - halfH, centerX + halfW, centerZ + halfH,
						this::screenXd, this::screenYd);
			}
			for (Waypoint waypoint : MapSession.waypoints().visible(MapSession.dimension())) {
				float[] pos = toScreen(waypoint.x + 0.5, waypoint.y + 1, waypoint.z + 0.5);
				if (pos == null) {
					continue;
				}
				int sx = Math.round(pos[0]);
				int sy = Math.round(pos[1]);
				MapDraw.badge(graphics, font, waypoint, sx, sy, false);
				if (scale >= 0.5) {
					graphics.centeredText(font, waypoint.name, sx, sy + 8, LunaColors.WHITE);
				}
			}
			LocalPlayer player = minecraft.player;
			if (player != null && luna.map.LunaConfig.get().radar && minecraft.level != null) {
				for (RadarIcons.Kind kind : RadarIcons.Kind.values()) {
					for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
						if (entity == player || RadarIcons.kind(entity) != kind) {
							continue;
						}
						float[] pos = toScreen(entity.getX(), entity.getY(), entity.getZ());
						if (pos == null) {
							continue;
						}
						double ex = pos[0];
						double ez = pos[1];
						double dy = threeD ? 0 : entity.getY() - player.getY();
						if (kind == RadarIcons.Kind.PLAYER && RadarIcons.drawHead(graphics, entity, ex, ez, dy, 10)) {
							graphics.centeredText(font, entity.getName(), (int) Math.round(ex), (int) Math.round(ez) + 8, LunaColors.WHITE);
						} else {
							RadarIcons.draw(graphics, kind, ex, ez, dy);
						}
					}
				}
			}
			if (player != null) {
				float[] pos = toScreen(player.getX(), player.getY(), player.getZ());
				if (pos != null) {
					// in 3D dreht sich die Karte mit, der Pfeil muss das ausgleichen
					MapDraw.arrow(graphics, pos[0], pos[1], (float) (player.getYRot() - Math.toDegrees(yaw)), 14);
				}
			}
			graphics.disableScissor();
		}

		graphics.fill(0, 0, width, 16, 0xCC110D28);
		graphics.centeredText(font, MapSession.underground() ? Component.translatable("lunamap.worldmap.cave_title") : title,
				width / 2, 4, LunaColors.MOON);
		graphics.fill(0, height - 28, width, height, 0xCC110D28);
		double[] under = fromScreen(mouseX, mouseY);
		if (under != null) {
			String coords = "X " + (int) Math.floor(under[0]) + "  Z " + (int) Math.floor(under[1]);
			graphics.text(font, coords, width - font.width(coords) - 6, 4, LunaColors.LAVENDER);
		}
		String hint = Component.translatable(in3d() ? "lunamap.worldmap.hint_3d" : "lunamap.worldmap.hint").getString();
		graphics.text(font, font.plainSubstrByWidth(hint, width - 8), 4, 20, 0xAAA896E8);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawRegions(GuiGraphicsExtractor graphics, MapStore store) {
		double halfW = width / 2.0 / scale;
		double halfH = height / 2.0 / scale;
		double blocksPerPixel = 1 / scale;
		boolean far = BlueMapOverview.lodFor(blocksPerPixel) > 1;
		// Nah dran liegt die BlueMap-Uebersicht als Grundlage darunter, bis die blockgenauen Kacheln da sind
		if (far || !MapSession.underground()) {
			BlueMapOverview.draw(graphics, centerX - halfW, centerZ - halfH, centerX + halfW, centerZ + halfH,
					blocksPerPixel, this::screenXd, this::screenYd);
		}
		if (!far) {
			BlueMapSource.request((int) (centerX - halfW), (int) (centerZ - halfH), (int) (centerX + halfW), (int) (centerZ + halfH));
		}
		// Detailstufe: nah blockgenau, weiter weg zusammengefasste Kacheln (4 bzw. 16 Bloecke pro Pixel)
		int level = scale >= 0.25 ? 0 : scale >= 1 / 16.0 ? 1 : 2;
		if (level > 0) {
			int span = LodCache.span(level);
			int tx0 = Math.floorDiv((int) Math.floor(centerX - halfW), span);
			int tx1 = Math.floorDiv((int) Math.floor(centerX + halfW), span);
			int tz0 = Math.floorDiv((int) Math.floor(centerZ - halfH), span);
			int tz1 = Math.floorDiv((int) Math.floor(centerZ + halfH), span);
			for (int tz = tz0; tz <= tz1; tz++) {
				for (int tx = tx0; tx <= tx1; tx++) {
					Identifier texture = store.lod().texture(level, tx, tz, frame);
					if (texture != null) {
						blitTile(graphics, texture, tx * span, tz * span, span, LodCache.TEX);
					}
				}
			}
			store.lod().trim(frame);
			return;
		}
		int rx0 = (int) Math.floor((centerX - halfW) / MapRegion.SIZE);
		int rx1 = (int) Math.floor((centerX + halfW) / MapRegion.SIZE);
		int rz0 = (int) Math.floor((centerZ - halfH) / MapRegion.SIZE);
		int rz1 = (int) Math.floor((centerZ + halfH) / MapRegion.SIZE);
		store.loadBudget = 8;
		MapRegion.uploadBudget = 4;
		try {
			for (int rz = rz0; rz <= rz1; rz++) {
				for (int rx = rx0; rx <= rx1; rx++) {
					MapRegion region = store.region(rx, rz, false);
					if (region != null) {
						blitTile(graphics, region.texture(store.textureOwner(), frame), rx * MapRegion.SIZE, rz * MapRegion.SIZE,
								MapRegion.SIZE, MapRegion.SIZE);
					}
				}
			}
		} finally {
			store.loadBudget = Integer.MAX_VALUE;
			MapRegion.uploadBudget = Integer.MAX_VALUE;
		}
		store.trimTextures(frame);
	}

	private void blitTile(GuiGraphicsExtractor graphics, Identifier texture, int blockX, int blockZ, int span, int texSize) {
		int x0 = screenX(blockX);
		int x1 = screenX(blockX + span);
		int y0 = screenY(blockZ);
		int y1 = screenY(blockZ + span);
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x0, y0, 0, 0, x1 - x0, y1 - y0, texSize, texSize, texSize, texSize);
	}

	private double screenXd(double blockX) {
		return width / 2.0 + (blockX - centerX) * scale;
	}

	private double screenYd(double blockZ) {
		return height / 2.0 + (blockZ - centerZ) * scale;
	}

	private int screenX(double blockX) {
		return (int) Math.round(width / 2.0 + (blockX - centerX) * scale);
	}

	private int screenY(double blockZ) {
		return (int) Math.round(height / 2.0 + (blockZ - centerZ) * scale);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && MapSession.store() != null && event.y() > 16
				&& event.y() < height - 28) {
			double[] under = fromScreen(event.x(), event.y());
			if (under == null) {
				return false;
			}
			int bx = (int) Math.floor(under[0]);
			int bz = (int) Math.floor(under[1]);
			int by = minecraft.player != null ? minecraft.player.getBlockY() : 64;
			minecraft.setScreenAndShow(WaypointEditScreen.create(this, bx, by, bz));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
			// Mausrad gedrueckt: seitlich ziehen dreht, hoch/runter neigt
			yaw += dx * 0.012;
			yaw = Math.IEEEremainder(yaw, Math.PI * 2);
			tilt = Math.max(0, Math.min(1, tilt - dy * 0.006));
			if (in3d()) {
				scale = Math.max(scale, MIN_SCALE_3D);
			}
			remember();
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (in3d()) {
				// verschieben passend zur Blickrichtung; je flacher, desto mehr Boden pro Pixel nach hinten
				double sinPitch = Math.max(0.35, Math.sin(Math.toRadians(90 - tilt * 65)));
				double rightX = Math.cos(yaw), rightZ = Math.sin(yaw);
				double fwdX = Math.sin(yaw), fwdZ = -Math.cos(yaw);
				centerX -= (rightX * dx - fwdX * dy / sinPitch) / scale;
				centerZ -= (rightZ * dx - fwdZ * dy / sinPitch) / scale;
				lastInteraction = System.currentTimeMillis();
			} else {
				centerX -= dx / scale;
				centerZ -= dy / scale;
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double before = scale;
		scale = Math.max(in3d() ? MIN_SCALE_3D : MIN_SCALE, Math.min(MAX_SCALE, scale * Math.pow(2, scrollY * 0.5)));
		if (in3d()) {
			remember(); // in 3D zur Bildmitte zoomen
			return true;
		}
		// Punkt unter dem Mauszeiger bleibt stehen
		double mx = mouseX - width / 2.0;
		double my = mouseY - height / 2.0;
		centerX += mx / before - mx / scale;
		centerZ += my / before - my / scale;
		savedScale = scale;
		return true;
	}
}
