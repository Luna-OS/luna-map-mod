package luna.map.ui;

import java.util.List;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.data.Waypoint;
import luna.map.share.WaypointShare;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Liste der Wegpunkte in der aktuellen Dimension. */
public class WaypointListScreen extends Screen {
	private static final int PER_PAGE = 6;

	private final Screen parent;
	private int page;

	public WaypointListScreen(Screen parent) {
		super(Component.translatable("lunamap.waypoints"));
		this.parent = parent;
	}

	private List<Waypoint> waypoints() {
		return MapSession.waypoints() == null ? List.of() : MapSession.waypoints().in(MapSession.dimension());
	}

	@Override
	protected void init() {
		List<Waypoint> list = waypoints();
		int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.min(page, pages - 1);
		int top = 40;
		for (int i = 0; i < PER_PAGE; i++) {
			int index = page * PER_PAGE + i;
			if (index >= list.size()) {
				break;
			}
			Waypoint waypoint = list.get(index);
			int rowY = top + i * 26;
			addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.edit_short"),
					b -> minecraft.setScreenAndShow(WaypointEditScreen.edit(this, waypoint)))
					.bounds(width / 2 + 70, rowY, 60, 20).build());
			// im Chat teilen (Xaero-Format, Xaero-Spieler koennen ihn direkt uebernehmen)
			Button share = addRenderableWidget(Button.builder(Component.translatable("lunamap.share.button"), b -> {
				WaypointShare.send(waypoint);
				onClose();
			}).bounds(width / 2 + 16, rowY, 52, 20)
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("lunamap.share.button_hint")))
					.build());
			share.active = minecraft.player != null;
			// ein-/ausblenden: ausgeblendete Wegpunkte erscheinen nur noch hier in der Liste
			addRenderableWidget(Button.builder(Component.literal(waypoint.hidden ? "○" : "●"), b -> {
				waypoint.hidden = !waypoint.hidden;
				MapSession.waypoints().save();
				rebuildWidgets();
			}).bounds(width / 2 - 8, rowY, 20, 20)
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
							waypoint.hidden ? "lunamap.waypoint.show" : "lunamap.waypoint.hide")))
					.build());
		}
		int bottom = height - 28;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(width / 2 - 130, bottom, 30, 20).build());
		prev.active = page > 0;
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(width / 2 - 96, bottom, 30, 20).build());
		next.active = page < pages - 1;
		Button add = addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.add_here"), b -> {
			if (minecraft.player != null) {
				minecraft.setScreenAndShow(WaypointEditScreen.create(this, minecraft.player.getBlockX(),
						minecraft.player.getBlockY(), minecraft.player.getBlockZ()));
			}
		}).bounds(width / 2 - 62, bottom, 96, 20).build());
		add.active = MapSession.waypoints() != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 38, bottom, 92, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0xF0211948, 0xF0110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// welche Dimension: Oberwelt, Nether, End oder eine eigene (z. B. aus einem Datapack)
		graphics.centeredText(font, Component.empty().append(title).append(" – ").append(luna.map.data.Dimensions.name(MapSession.dimension())),
				width / 2, 16, LunaColors.MOON);
		List<Waypoint> list = waypoints();
		if (list.isEmpty()) {
			graphics.centeredText(font, Component.translatable("lunamap.waypoints.empty"), width / 2, 60, LunaColors.LAVENDER);
		}
		for (int i = 0; i < PER_PAGE; i++) {
			int index = page * PER_PAGE + i;
			if (index >= list.size()) {
				break;
			}
			Waypoint waypoint = list.get(index);
			int rowY = 40 + i * 26;
			MapDraw.badge(graphics, font, waypoint, width / 2 - 126, rowY + 10, false);
			int nameColor = waypoint.hidden ? 0xFF8C84B8 : LunaColors.WHITE;
			graphics.text(font, font.plainSubstrByWidth(waypoint.name, 100), width / 2 - 112, rowY + 2, nameColor);
			String coords = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			graphics.text(font, waypoint.hidden ? Component.translatable("lunamap.waypoint.hidden_coords", coords).getString() : coords,
					width / 2 - 112, rowY + 11, waypoint.hidden ? 0xFF6E6896 : LunaColors.LAVENDER);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
