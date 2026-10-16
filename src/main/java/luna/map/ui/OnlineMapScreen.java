package luna.map.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

/**
 * BlueMap-Link fuer die aktuelle Welt bzw. den aktuellen Server eintragen. Jede Dimension bekommt
 * ihre eigene BlueMap-Karte, damit z. B. die Nether-Karte nicht in der Oberwelt landet.
 */
public class OnlineMapScreen extends Screen {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final String END = "minecraft:the_end";
	/** Platzhalter fuer "keine Karte". */
	private static final BlueMapClient.MapInfo NONE = new BlueMapClient.MapInfo("", "–", 500);

	private final Screen parent;
	private EditBox url;
	private List<BlueMapClient.MapInfo> maps = List.of();
	/** Dimension -> gewaehlte Karte (NONE = keine). */
	private final Map<String, BlueMapClient.MapInfo> selected = new LinkedHashMap<>();
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
			selected.clear();
			status = Component.translatable("lunamap.online.removed");
			url.setValue("");
			rebuildWidgets();
		}).bounds(cx + 4, top + 24, 146, 20).build());
		remove.active = link != null;

		if (!maps.isEmpty()) {
			List<BlueMapClient.MapInfo> values = new ArrayList<>();
			values.add(NONE);
			values.addAll(maps);
			int y = top + 52;
			for (String dim : dimensions()) {
				BlueMapClient.MapInfo initial = selected.getOrDefault(dim, NONE);
				addRenderableWidget(CycleButton.builder(
								(BlueMapClient.MapInfo m) -> m == NONE ? Component.translatable("lunamap.online.none")
										: Component.literal(m.name() + " (" + m.id() + ")"), initial)
						.withValues(values)
						.create(cx - 150, y, 300, 20, dimensionName(dim), (b, v) -> selected.put(dim, v)));
				y += 22;
			}
			addRenderableWidget(Button.builder(Component.translatable("lunamap.waypoint.save"), b -> save())
					.bounds(cx - 150, y + 4, 300, 20).build());
		}
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
				selected.clear();
				BlueMapLink link = BlueMapSource.link();
				boolean sameServer = link != null && link.url.equals(connection.baseUrl());
				for (String dim : dimensions()) {
					selected.put(dim, sameServer && link.maps.containsKey(dim) ? find(link.maps.get(dim)) : guess(dim));
				}
				status = Component.translatable("lunamap.online.found", maps.size());
			}
			rebuildWidgets();
		}));
	}

	private BlueMapClient.MapInfo find(String id) {
		for (BlueMapClient.MapInfo m : maps) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return NONE;
	}

	/** Passende Karte zu einer Dimension raten (Name oder ID enthaelt "nether" / "end"). */
	private BlueMapClient.MapInfo guess(String dim) {
		String want = dim.equals(NETHER) ? "nether" : dim.equals(END) ? "end" : dim.equals(OVERWORLD) ? "" : dim.substring(dim.indexOf(':') + 1);
		BlueMapClient.MapInfo best = NONE;
		for (BlueMapClient.MapInfo m : maps) {
			String text = (m.id() + " " + m.name()).toLowerCase(Locale.ROOT);
			boolean nether = text.contains("nether") || text.contains("hell");
			boolean end = text.matches(".*\\bend\\b.*") || text.contains("the_end") || text.contains("_end");
			if (want.isEmpty()) {
				if (!nether && !end) {
					// bevorzugt die "normale" Karte, nicht z. B. eine 2D- oder Lowres-Variante
					if (best == NONE || m.id().length() < best.id().length()) {
						best = m;
					}
				}
			} else if (want.equals("nether") ? nether : want.equals("end") ? end : text.contains(want)) {
				if (best == NONE || m.id().length() < best.id().length()) {
					best = m;
				}
			}
		}
		return best;
	}

	private void save() {
		BlueMapLink link = new BlueMapLink();
		link.url = BlueMapLink.normalize(url.getValue());
		for (Map.Entry<String, BlueMapClient.MapInfo> entry : selected.entrySet()) {
			BlueMapClient.MapInfo m = entry.getValue();
			if (m != NONE) {
				link.maps.put(entry.getKey(), m.id());
				link.tileSizes.put(m.id(), m.tileSize());
			}
		}
		BlueMapSource.setLink(link);
		status = Component.translatable("lunamap.online.saved_all", link.maps.size());
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
		if (!maps.isEmpty()) {
			// Kartenauswahl offen: nur die Statuszeile unter "Speichern", sonst wird es auf kleinen Fenstern eng
			graphics.centeredText(font, status, width / 2, top + 52 + dimensions().size() * 22 + 29, LunaColors.WHITE);
			return;
		}
		int y = top + 52;
		graphics.centeredText(font, status, width / 2, y, LunaColors.WHITE);
		if (BlueMapSource.link() != null) {
			graphics.centeredText(font, Component.translatable("lunamap.online.tiles", BlueMapSource.loadedTiles(),
					BlueMapSource.failedTiles()), width / 2, y + 12, LunaColors.LAVENDER);
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
