package luna.map.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.online.BlueMapClient;
import luna.map.online.BlueMapLink;
import luna.map.online.BlueMapSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** BlueMap-Link fuer die aktuelle Welt bzw. den aktuellen Server eintragen. */
public class OnlineMapScreen extends Screen {
	private final Screen parent;
	private EditBox url;
	private List<BlueMapClient.MapInfo> maps = List.of();
	private BlueMapClient.MapInfo selected;
	private Component status = Component.empty();
	private boolean loading;

	public OnlineMapScreen(Screen parent) {
		super(Component.translatable("lunamap.online.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 50;
		String previous = url != null ? url.getValue() : null;
		url = addRenderableWidget(new EditBox(font, cx - 150, top, 300, 20, Component.translatable("lunamap.online.url")));
		url.setMaxLength(300);
		BlueMapLink link = BlueMapSource.link();
		if (previous != null) {
			url.setValue(previous);
		} else if (link != null) {
			url.setValue(link.url);
		}
		url.setHint(Component.literal("https://map.dein-server.de/"));

		Button connect = addRenderableWidget(Button.builder(Component.translatable("lunamap.online.connect"), b -> connect())
				.bounds(cx - 150, top + 24, 146, 20).build());
		connect.active = MapSession.world() != null && !loading;
		Button remove = addRenderableWidget(Button.builder(Component.translatable("lunamap.online.remove"), b -> {
			BlueMapSource.setLink(null);
			maps = List.of();
			selected = null;
			status = Component.translatable("lunamap.online.removed");
			url.setValue("");
			rebuildWidgets();
		}).bounds(cx + 4, top + 24, 146, 20).build());
		remove.active = link != null;

		if (!maps.isEmpty()) {
			addRenderableWidget(CycleButton.builder((BlueMapClient.MapInfo m) -> Component.literal(m.name() + " (" + m.id() + ")"), selected)
					.withValues(maps)
					.create(cx - 150, top + 52, 300, 20, Component.translatable("lunamap.online.map"),
							(b, v) -> selected = v));
			addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.save"), b -> save())
					.bounds(cx - 150, top + 76, 300, 20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - 100, height - 28, 200, 20).build());
	}

	private void connect() {
		String base = BlueMapLink.normalize(url.getValue());
		url.setValue(base);
		loading = true;
		status = Component.translatable("lunamap.online.loading");
		rebuildWidgets();
		BlueMapClient.fetchMaps(base).whenComplete((list, error) -> minecraft.execute(() -> {
			loading = false;
			if (error != null || list.isEmpty()) {
				maps = List.of();
				String reason = error != null && error.getCause() != null ? error.getCause().getMessage() : "keine Karten";
				if (reason != null && reason.length() > 60) {
					reason = reason.substring(0, 57) + "...";
				}
				status = Component.translatable("lunamap.online.failed", reason);
			} else {
				maps = new ArrayList<>(list);
				selected = guess(maps);
				status = Component.translatable("lunamap.online.found", maps.size());
			}
			rebuildWidgets();
		}));
	}

	/** Passende Karte zur aktuellen Dimension raten. */
	private static BlueMapClient.MapInfo guess(List<BlueMapClient.MapInfo> maps) {
		String dim = MapSession.dimension() == null ? "" : MapSession.dimension();
		String want = dim.contains("nether") ? "nether" : dim.contains("end") ? "end" : "";
		BlueMapLink link = BlueMapSource.link();
		if (link != null && link.maps.containsKey(dim)) {
			for (BlueMapClient.MapInfo m : maps) {
				if (m.id().equals(link.maps.get(dim))) {
					return m;
				}
			}
		}
		for (BlueMapClient.MapInfo m : maps) {
			String id = m.id().toLowerCase(Locale.ROOT) + " " + m.name().toLowerCase(Locale.ROOT);
			boolean other = id.contains("nether") || id.contains("end");
			if (want.isEmpty() ? !other : id.contains(want)) {
				return m;
			}
		}
		return maps.get(0);
	}

	private void save() {
		if (selected == null || MapSession.dimension() == null) {
			return;
		}
		BlueMapLink link = BlueMapSource.link();
		String base = BlueMapLink.normalize(url.getValue());
		if (link == null || !base.equals(link.url)) {
			link = new BlueMapLink();
			link.url = base;
		}
		link.maps.put(MapSession.dimension(), selected.id());
		link.tileSizes.put(selected.id(), selected.tileSize());
		BlueMapSource.setLink(link);
		status = Component.translatable("lunamap.online.saved", selected.name());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0xF0211948, 0xF0110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = height / 2 - 50;
		graphics.centeredText(font, title, width / 2, top - 46, LunaColors.MOON);
		Component scope = MapSession.world() == null
				? Component.translatable("lunamap.import.no_world")
				: Component.translatable("lunamap.online.scope", MapSession.world(), MapSession.dimension());
		graphics.centeredText(font, scope, width / 2, top - 32, LunaColors.LAVENDER);
		graphics.text(font, Component.translatable("lunamap.online.url"), width / 2 - 150, top - 11, LunaColors.LAVENDER);
		graphics.centeredText(font, status, width / 2, top + 104, LunaColors.WHITE);
		if (BlueMapSource.link() != null) {
			graphics.centeredText(font, Component.translatable("lunamap.online.tiles", BlueMapSource.loadedTiles(),
					BlueMapSource.failedTiles()), width / 2, top + 128, LunaColors.LAVENDER);
		}
		String error = BlueMapSource.lastError();
		if (error != null) {
			graphics.centeredText(font, Component.translatable("lunamap.online.tile_error", error), width / 2, top + 116, 0xFFFF9B9B);
		}
		graphics.centeredText(font, Component.translatable("lunamap.online.hint"), width / 2, height - 44, 0xAAA896E8);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
