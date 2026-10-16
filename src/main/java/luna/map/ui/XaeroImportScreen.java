package luna.map.ui;

import java.util.List;

import luna.map.LunaColors;
import luna.map.data.MapSession;
import luna.map.importer.XaeroImporter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;

/** Waehlt die Xaero-Welt aus, deren Karte und Wegpunkte in die aktuelle Welt uebernommen werden. */
public class XaeroImportScreen extends Screen {
	private static final int MAX_ROWS = 6;

	private final Screen parent;
	private List<XaeroImporter.Source> sources = List.of();

	public XaeroImportScreen(Screen parent) {
		super(Component.translatable("lunamap.import.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		sources = XaeroImporter.sources(MapSession.world());
		int top = 56;
		for (int i = 0; i < Math.min(MAX_ROWS, sources.size()); i++) {
			XaeroImporter.Source source = sources.get(i);
			boolean match = XaeroImporter.matches(source, MapSession.world());
			Component label = Component.literal((match ? "★ " : "") + source.name())
					.append(Component.translatable("lunamap.import.counts", source.regions(), source.waypoints()));
			Button button = addRenderableWidget(Button.builder(label, b -> start(source))
					.bounds(width / 2 - 150, top + i * 24, 300, 20).build());
			button.active = MapSession.world() != null && !XaeroImporter.running();
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 - 100, height - 28, 200, 20).build());
	}

	@SuppressWarnings("unchecked")
	private void start(XaeroImporter.Source source) {
		if (minecraft.level == null) {
			return;
		}
		Registry<Biome> biomes = (Registry<Biome>) (Object) minecraft.level.registryAccess().lookupOrThrow(Registries.BIOME);
		XaeroImporter.start(source, biomes);
		rebuildWidgets();
	}

	@Override
	public void tick() {
		if (!XaeroImporter.running() && children().stream().anyMatch(c -> c instanceof Button b && !b.active)) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0xF0211948, 0xF0110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, 14, LunaColors.MOON);
		Component target = MapSession.world() == null
				? Component.translatable("lunamap.import.no_world")
				: Component.translatable("lunamap.import.target", MapSession.world());
		graphics.centeredText(font, target, width / 2, 28, LunaColors.LAVENDER);
		graphics.centeredText(font, Component.translatable("lunamap.import.hint"), width / 2, 40, 0xAAA896E8);
		if (sources.isEmpty()) {
			graphics.centeredText(font, Component.translatable("lunamap.import.none"), width / 2, 70, LunaColors.WHITE);
		}
		graphics.centeredText(font, statusText(), width / 2, height - 44, LunaColors.WHITE);
	}

	private static Component statusText() {
		String[] parts = XaeroImporter.status().split(":", 3);
		return switch (parts[0]) {
			case "running" -> Component.translatable("lunamap.import.running", parts[1], parts[2]);
			case "done" -> Component.translatable("lunamap.import.done", parts[1], parts[2]);
			case "error" -> Component.translatable("lunamap.import.error", parts.length > 1 ? parts[1] : "?");
			default -> Component.empty();
		};
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
