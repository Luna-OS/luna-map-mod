package luna.map.ui;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.data.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Wegpunkt anlegen oder bearbeiten. */
public class WaypointEditScreen extends Screen {
	private final Screen parent;
	private final Waypoint waypoint;
	private final boolean isNew;
	private EditBox name;
	private EditBox x;
	private EditBox y;
	private EditBox z;
	private int color;
	private EditBox hex;

	private WaypointEditScreen(Screen parent, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "lunamap.waypoint.new" : "lunamap.waypoint.edit"));
		this.parent = parent;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.color = waypoint.color;
	}

	public static WaypointEditScreen create(Screen parent, int x, int y, int z) {
		int count = MapSession.waypoints().count(MapSession.dimension());
		String name = Component.translatable("lunamap.waypoint.default_name", count + 1).getString();
		int color = LunaColors.WAYPOINT[count % LunaColors.WAYPOINT.length];
		return new WaypointEditScreen(parent, new Waypoint(name, x, y, z, color, MapSession.dimension()), true);
	}

	public static WaypointEditScreen edit(Screen parent, Waypoint waypoint) {
		return new WaypointEditScreen(parent, waypoint, false);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 60;
		name = addRenderableWidget(new EditBox(font, cx - 100, top, 200, 20, Component.translatable("lunamap.waypoint.name")));
		name.setMaxLength(32);
		name.setValue(waypoint.name);
		x = numberBox(cx - 100, top + 36, waypoint.x, "X");
		y = numberBox(cx - 32, top + 36, waypoint.y, "Y");
		z = numberBox(cx + 36, top + 36, waypoint.z, "Z");
		hex = addRenderableWidget(new EditBox(font, cx + 40, top + 64, 60, 20, Component.literal("#RRGGBB")));
		hex.setMaxLength(7);
		hex.setValue(String.format("#%06X", color & 0xFFFFFF));
		hex.setResponder(value -> {
			Integer parsed = parseHex(value);
			if (parsed != null) {
				color = parsed;
			}
		});
		addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.save"), b -> save())
				.bounds(cx - 100, top + 96, 98, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(cx + 2, top + 96, 98, 20).build());
		if (!isNew) {
			addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.delete"), b -> {
				MapSession.waypoints().remove(waypoint);
				onClose();
			}).bounds(cx - 100, top + 120, 200, 20).build());
		}
		setInitialFocus(name);
	}

	private static Integer parseHex(String value) {
		String v = value.strip();
		if (v.startsWith("#")) {
			v = v.substring(1);
		}
		if (!v.matches("[0-9a-fA-F]{6}")) {
			return null;
		}
		return 0xFF000000 | Integer.parseInt(v, 16);
	}

	/** Farbfelder: Position des i-ten Felds (links oben), 2 Reihen. */
	private int[] swatch(int i) {
		int top = height / 2 - 60;
		int perRow = (LunaColors.WAYPOINT.length + 1) / 2;
		return new int[] {width / 2 - 100 + (i % perRow) * 20, top + 62 + (i / perRow) * 12};
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < LunaColors.WAYPOINT.length; i++) {
			int[] p = swatch(i);
			if (event.x() >= p[0] && event.x() < p[0] + 18 && event.y() >= p[1] && event.y() < p[1] + 10) {
				color = LunaColors.WAYPOINT[i];
				hex.setValue(String.format("#%06X", color & 0xFFFFFF));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private EditBox numberBox(int bx, int by, int value, String label) {
		EditBox box = addRenderableWidget(new EditBox(font, bx, by, 64, 20, Component.literal(label)));
		box.setValue(Integer.toString(value));
		box.setMaxLength(9);
		return box;
	}

	private void save() {
		if (MapSession.waypoints() == null) {
			onClose();
			return;
		}
		try {
			waypoint.x = Integer.parseInt(x.getValue().trim());
			waypoint.y = Integer.parseInt(y.getValue().trim());
			waypoint.z = Integer.parseInt(z.getValue().trim());
		} catch (NumberFormatException e) {
			return;
		}
		waypoint.name = name.getValue().isBlank() ? "?" : name.getValue().strip();
		waypoint.color = color;
		if (isNew) {
			MapSession.waypoints().add(waypoint);
		} else {
			MapSession.waypoints().save();
		}
		onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0xE0211948, 0xE0110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = height / 2 - 60;
		graphics.centeredText(font, title, width / 2, top - 24, LunaColors.MOON);
		graphics.text(font, Component.translatable("lunamap.waypoint.name"), width / 2 - 100, top - 10, LunaColors.LAVENDER);
		graphics.text(font, "X", width / 2 - 100, top + 26, LunaColors.LAVENDER);
		graphics.text(font, "Y", width / 2 - 32, top + 26, LunaColors.LAVENDER);
		graphics.text(font, "Z", width / 2 + 36, top + 26, LunaColors.LAVENDER);
		for (int i = 0; i < LunaColors.WAYPOINT.length; i++) {
			int[] p = swatch(i);
			boolean chosen = (LunaColors.WAYPOINT[i] | 0xFF000000) == (color | 0xFF000000);
			graphics.fill(p[0] - 1, p[1] - 1, p[0] + 19, p[1] + 11, chosen ? LunaColors.MOON : LunaColors.OUTLINE_DARK);
			graphics.fill(p[0], p[1], p[0] + 18, p[1] + 10, LunaColors.WAYPOINT[i]);
		}
		graphics.fill(width / 2 + 104, top + 66, width / 2 + 120, top + 82, LunaColors.MOON);
		graphics.fill(width / 2 + 105, top + 67, width / 2 + 119, top + 81, 0xFF000000 | color);
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
