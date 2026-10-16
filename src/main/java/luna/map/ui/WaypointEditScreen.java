package luna.map.ui;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.data.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
		java.util.List<Integer> colors = new java.util.ArrayList<>(java.util.Arrays.stream(LunaColors.WAYPOINT).boxed().toList());
		if (!colors.contains(color)) {
			colors.add(0, color);
		}
		addRenderableWidget(CycleButton.builder((Integer c) -> indexOf(c) < 0
						? Component.translatable("lunamap.color.custom")
						: Component.translatable("lunamap.color." + indexOf(c)), color)
				.withValues(colors)
				.create(cx - 100, top + 64, 200, 20, Component.translatable("lunamap.waypoint.color"),
						(button, value) -> color = value));
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

	private static int indexOf(int color) {
		for (int i = 0; i < LunaColors.WAYPOINT.length; i++) {
			if (LunaColors.WAYPOINT[i] == color) {
				return i;
			}
		}
		return -1;
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
		graphics.fill(width / 2 + 104, top + 66, width / 2 + 120, top + 82, color);
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
