package luna.map.ui;

import com.mojang.blaze3d.platform.InputConstants;

import luna.map.LunaColors;
import luna.map.data.MapRegion;
import luna.map.data.MapSession;
import luna.map.data.MapStore;
import luna.map.data.Waypoint;
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
	/** Ohne BlueMap reicht 1/8; mit BlueMap-Uebersicht kann man bis zur ganzen Serverkarte heraus. */
	private static final double MIN_SCALE_OWN = 0.125;
	private static final double MIN_SCALE_ONLINE = 1 / 48.0;
	private static final double MAX_SCALE = 8;
	private static double savedScale = 1;

	private double centerX;
	private double centerZ;
	private double scale = savedScale;
	private long frame;

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
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width - 94, y, 90, 20).build());
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
			drawRegions(graphics, store);
			for (Waypoint waypoint : MapSession.waypoints().in(MapSession.dimension())) {
				int sx = screenX(waypoint.x + 0.5);
				int sy = screenY(waypoint.z + 0.5);
				MapDraw.waypoint(graphics, waypoint, sx, sy, 3);
				if (scale >= 0.5) {
					graphics.centeredText(font, waypoint.name, sx, sy + 6, LunaColors.WHITE);
				}
			}
			LocalPlayer player = minecraft.player;
			if (player != null) {
				MapDraw.arrow(graphics, (float) (width / 2.0 + (player.getX() - centerX) * scale),
						(float) (height / 2.0 + (player.getZ() - centerZ) * scale), player.getYRot(), 14);
			}
		}

		graphics.fill(0, 0, width, 16, 0xCC110D28);
		graphics.centeredText(font, MapSession.underground() ? Component.translatable("lunamap.worldmap.cave_title") : title,
				width / 2, 4, LunaColors.MOON);
		graphics.fill(0, height - 28, width, height, 0xCC110D28);
		int bx = (int) Math.floor(centerX + (mouseX - width / 2.0) / scale);
		int bz = (int) Math.floor(centerZ + (mouseY - height / 2.0) / scale);
		String coords = "X " + bx + "  Z " + bz;
		graphics.text(font, coords, width - font.width(coords) - 6, 4, LunaColors.LAVENDER);
		graphics.text(font, Component.translatable("lunamap.worldmap.hint"), 4, 20, 0xAAA896E8);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawRegions(GuiGraphicsExtractor graphics, MapStore store) {
		double halfW = width / 2.0 / scale;
		double halfH = height / 2.0 / scale;
		double blocksPerPixel = 1 / scale;
		if (BlueMapOverview.lodFor(blocksPerPixel) > 1 || scale < MIN_SCALE_OWN) {
			BlueMapOverview.draw(graphics, centerX - halfW, centerZ - halfH, centerX + halfW, centerZ + halfH,
					blocksPerPixel, this::screenXd, this::screenYd);
		} else {
			BlueMapSource.request((int) (centerX - halfW), (int) (centerZ - halfH), (int) (centerX + halfW), (int) (centerZ + halfH));
		}
		if (scale < MIN_SCALE_OWN) {
			// so weit draussen nur die Uebersicht, sonst waeren es hunderte Regionstexturen
			return;
		}
		int rx0 = (int) Math.floor((centerX - halfW) / MapRegion.SIZE);
		int rx1 = (int) Math.floor((centerX + halfW) / MapRegion.SIZE);
		int rz0 = (int) Math.floor((centerZ - halfH) / MapRegion.SIZE);
		int rz1 = (int) Math.floor((centerZ + halfH) / MapRegion.SIZE);
		for (int rz = rz0; rz <= rz1; rz++) {
			for (int rx = rx0; rx <= rx1; rx++) {
				MapRegion region = store.region(rx, rz, false);
				if (region == null) {
					continue;
				}
				Identifier texture = region.texture(store.textureOwner(), frame);
				int x0 = screenX(rx * MapRegion.SIZE);
				int x1 = screenX((rx + 1) * MapRegion.SIZE);
				int y0 = screenY(rz * MapRegion.SIZE);
				int y1 = screenY((rz + 1) * MapRegion.SIZE);
				graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x0, y0, 0, 0, x1 - x0, y1 - y0,
						MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE);
			}
		}
		store.trimTextures();
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
			int bx = (int) Math.floor(centerX + (event.x() - width / 2.0) / scale);
			int bz = (int) Math.floor(centerZ + (event.y() - height / 2.0) / scale);
			int by = minecraft.player != null ? minecraft.player.getBlockY() : 64;
			minecraft.setScreenAndShow(WaypointEditScreen.create(this, bx, by, bz));
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			centerX -= dx / scale;
			centerZ -= dy / scale;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double before = scale;
		double min = BlueMapOverview.available() ? MIN_SCALE_ONLINE : MIN_SCALE_OWN;
		scale = Math.max(min, Math.min(MAX_SCALE, scale * Math.pow(2, scrollY * 0.5)));
		// Punkt unter dem Mauszeiger bleibt stehen
		double mx = mouseX - width / 2.0;
		double my = mouseY - height / 2.0;
		centerX += mx / before - mx / scale;
		centerZ += my / before - my / scale;
		savedScale = scale;
		return true;
	}
}
