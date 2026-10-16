package luna.map;

import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Gemeinsamer Einstieg fuer Minimap und Weltkarte im Luna-Look. */
public class LunaScreen extends Screen {
	private static final Identifier AXO = LunaMap.id("textures/gui/axo.png");
	private static final int AXO_W = 26;
	private static final int AXO_H = 17;
	private static final int AXO_SCALE = 3;

	private static final int NIGHT_TOP = 0xFF211948;
	private static final int NIGHT_BOTTOM = 0xFF110D28;
	private static final int MOON = 0xFFFFECA0;
	private static final int LAVENDER = 0xFFA896E8;

	private final Screen parent;

	public LunaScreen(Screen parent) {
		super(Component.translatable("lunamap.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		int y = height / 2 + 4;
		addSettingsButton(x, y, "lunamap.button.minimap", "xaerominimap");
		addSettingsButton(x, y + 24, "lunamap.button.worldmap", "xaeroworldmap");
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(x, y + 56, 200, 20).build());
	}

	private void addSettingsButton(int x, int y, String key, String modId) {
		EntrypointContainer<ModMenuApi> entry = settingsEntrypoint(modId);
		Button button = addRenderableWidget(Button.builder(Component.translatable(key), b -> {
			try {
				minecraft.setScreenAndShow(entry.getEntrypoint().getModConfigScreenFactory().create(this));
			} catch (RuntimeException | LinkageError e) {
				LunaMap.LOGGER.warn("Einstellungen von {} konnten nicht geoeffnet werden", modId, e);
			}
		}).bounds(x, y, 200, 20).build());
		button.active = entry != null;
	}

	/** Xaeros eigener Mod-Menu-Einstiegspunkt, ueber den sich dessen Einstellungen oeffnen. */
	private static EntrypointContainer<ModMenuApi> settingsEntrypoint(String modId) {
		for (EntrypointContainer<ModMenuApi> entry : FabricLoader.getInstance()
				.getEntrypointContainers("modmenu", ModMenuApi.class)) {
			if (entry.getProvider().getMetadata().getId().equals(modId)) {
				return entry;
			}
		}
		return null;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, NIGHT_TOP, NIGHT_BOTTOM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int axoW = AXO_W * AXO_SCALE;
		int axoH = AXO_H * AXO_SCALE;
		int axoY = height / 2 - 30 - axoH;
		graphics.blit(RenderPipelines.GUI_TEXTURED, AXO, width / 2 - axoW / 2, axoY, 0, 0,
				axoW, axoH, AXO_W, AXO_H, AXO_W, AXO_H);
		graphics.centeredText(font, title, width / 2, axoY - 14, MOON);
		graphics.centeredText(font, ShaderStatus.describe(), width / 2, height / 2 - 18, LAVENDER);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
