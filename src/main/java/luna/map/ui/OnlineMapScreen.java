package luna.map.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.online.BlueMapAuto;
import luna.map.online.BlueMapClient;
import luna.map.online.BlueMapLink;
import luna.map.online.BlueMapSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * BlueMap-Link fuer die aktuelle Welt bzw. den aktuellen Server eintragen. Die Karten werden den
 * Dimensionen automatisch zugeordnet (Name, dann live ueber die eigene Position auf der BlueMap).
 */
public class OnlineMapScreen extends Screen {
	private static final String OVERWORLD = BlueMapAuto.OVERWORLD;
	private static final String NETHER = BlueMapAuto.NETHER;
	private static final String END = BlueMapAuto.END;

	private final Screen parent;
	private EditBox url;
	private List<BlueMapClient.MapInfo> maps = List.of();
	private Component status = Component.empty();
	private boolean loading;

	public OnlineMapScreen(Screen parent) {
		super(Component.translatable("lunamap.online.title"));
		this.parent = parent;
	}

	/** Oberwelt, Nether, End und die Dimension, in der man gerade ist (falls eine eigene). */
	private static List<String> dimensions() {
		List<String> dims = new ArrayList<>(List.of(OVERWORLD, NETHER, END));
		String current = MapSession.dimension();
		if (current != null && !dims.contains(current)) {
			dims.add(current);
		}
		BlueMapLink link = BlueMapSource.link();
		if (link != null) {
			for (String dim : link.maps.keySet()) {
				if (!dims.contains(dim)) {
					dims.add(dim);
				}
			}
		}
		return dims;
	}

	private int top() {
		return 50;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = top();
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
			status = Component.translatable("lunamap.online.removed");
			url.setValue("");
			rebuildWidgets();
		}).bounds(cx + 4, top + 24, 146, 20).build());
		remove.active = link != null;

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - 100, height - 28, 200, 20).build());
	}

	private static Component dimensionName(String dim) {
		return switch (dim) {
			case OVERWORLD -> Component.translatable("lunamap.dim.overworld");
			case NETHER -> Component.translatable("lunamap.dim.nether");
			case END -> Component.translatable("lunamap.dim.end");
			default -> Component.literal(dim);
		};
	}

	private void connect() {
		String base = BlueMapLink.normalize(url.getValue());
		url.setValue(base);
		loading = true;
		status = Component.translatable("lunamap.online.loading");
		rebuildWidgets();
		BlueMapClient.connect(base).whenComplete((connection, error) -> minecraft.execute(() -> {
			loading = false;
			List<BlueMapClient.MapInfo> list = connection == null ? List.of() : connection.maps();
			if (connection != null) {
				url.setValue(connection.baseUrl());
			}
			if (error != null || list.isEmpty()) {
				maps = List.of();
				String reason = error != null && error.getCause() != null ? error.getCause().getMessage() : "keine Karten";
				if (reason != null && reason.length() > 60) {
					reason = reason.substring(0, 57) + "...";
				}
				status = Component.translatable("lunamap.online.failed", reason);
			} else {
				maps = new ArrayList<>(list);
				BlueMapLink old = BlueMapSource.link();
				BlueMapLink link = new BlueMapLink();
				link.url = connection.baseUrl();
				link.liveRoot = connection.liveRoot();
				for (BlueMapClient.MapInfo m : maps) {
					link.names.put(m.id(), m.name());
					link.tileSizes.put(m.id(), m.tileSize());
				}
				BlueMapAuto.assignAll(link);
				if (old != null && old.url.equals(link.url)) {
					// schon sicher erkannte Zuordnungen behalten
					for (Map.Entry<String, String> entry : old.confirmed.entrySet()) {
						if (link.names.containsKey(entry.getValue())) {
							link.maps.put(entry.getKey(), entry.getValue());
							link.confirmed.put(entry.getKey(), entry.getValue());
						}
					}
				}
				BlueMapSource.setLink(link);
				status = Component.translatable("lunamap.online.found", maps.size());
			}
			rebuildWidgets();
		}));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0xF0211948, 0xF0110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = top();
		graphics.centeredText(font, title, width / 2, 8, LunaColors.MOON);
		Component scope = MapSession.world() == null
				? Component.translatable("lunamap.import.no_world")
				: Component.translatable("lunamap.online.scope_server", MapSession.world());
		graphics.centeredText(font, scope, width / 2, 22, LunaColors.LAVENDER);
		graphics.text(font, Component.translatable("lunamap.online.url"), width / 2 - 150, top - 11, LunaColors.LAVENDER);
		int y = top + 52;
		graphics.centeredText(font, status, width / 2, y, LunaColors.WHITE);
		BlueMapLink link = BlueMapSource.link();
		if (link != null) {
			graphics.centeredText(font, Component.translatable("lunamap.online.tiles", BlueMapSource.loadedTiles(),
					BlueMapSource.failedTiles()), width / 2, y + 12, LunaColors.LAVENDER);
			// automatische Zuordnung anzeigen (kein Auswaehlen noetig)
			int line = y + 38;
			for (String dim : dimensions()) {
				if (line > height - 58) {
					break;
				}
				String id = link.maps.get(dim);
				Component map = id == null ? Component.translatable("lunamap.online.none")
						: Component.literal(link.names.getOrDefault(id, id) + (id.equals(link.confirmed.get(dim)) ? " ✔" : ""));
				graphics.centeredText(font, Component.empty().append(dimensionName(dim)).append(" → ").append(map),
						width / 2, line, id == null ? 0xFF8C84B8 : LunaColors.MOON);
				line += 11;
			}
		}
		String error = BlueMapSource.lastError();
		if (error != null) {
			graphics.centeredText(font, Component.translatable("lunamap.online.tile_error", error), width / 2, y + 24, 0xFFFF9B9B);
		}
		graphics.centeredText(font, Component.translatable("lunamap.online.hint"), width / 2, height - 44, 0xAAA896E8);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
