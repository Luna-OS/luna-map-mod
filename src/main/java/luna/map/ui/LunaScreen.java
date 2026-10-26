package luna.map.ui;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.LunaMap;
import luna.map.data.MapSession;
import luna.map.update.Updater;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Einstellungen im Luna-Look (auch ueber Mod Menu erreichbar). */
public class LunaScreen extends Screen {
	private static final Identifier AXO = LunaMap.id("textures/gui/axo.png");
	private static final int AXO_W = 26;
	private static final int AXO_H = 17;
	private static final int AXO_SCALE = 2;

	/** Was unter der Minimap steht. */
	private enum Info {
		BOTH(true, true), COORDINATES(true, false), BIOME(false, true), OFF(false, false);

		final boolean coordinates;
		final boolean biome;

		Info(boolean coordinates, boolean biome) {
			this.coordinates = coordinates;
			this.biome = biome;
		}

		static Info of(LunaConfig config) {
			for (Info info : values()) {
				if (info.coordinates == config.coordinates && info.biome == config.biome) {
					return info;
				}
			}
			return BOTH;
		}
	}

	private final Screen parent;

	public LunaScreen(Screen parent) {
		super(Component.translatable("lunamap.title"));
		this.parent = parent;
	}

	private int top() {
		return height / 2 - 58;
	}

	@Override
	protected void init() {
		LunaConfig config = LunaConfig.get();
		int left = width / 2 - 155;
		int right = width / 2 + 5;
		int top = top();

		addRenderableWidget(CycleButton.onOffBuilder(config.minimap)
				.create(left, top, 150, 20, Component.translatable("lunamap.option.minimap"), (b, v) -> config.minimap = v));
		addRenderableWidget(Button.builder(Component.translatable("lunamap.edit.button"),
				b -> minecraft.setScreenAndShow(new MinimapEditScreen(this))).bounds(right, top, 150, 20).build());
		addRenderableWidget(CycleButton.builder((LunaConfig.Zoom z) -> Component.translatable("lunamap.zoom." + z.name().toLowerCase()), config.zoom)
				.withValues(LunaConfig.Zoom.values())
				.create(left, top + 22, 150, 20, Component.translatable("lunamap.option.zoom"), (b, v) -> config.zoom = v));
		addRenderableWidget(CycleButton.builder((Info i) -> Component.translatable("lunamap.info." + i.name().toLowerCase()), Info.of(config))
				.withValues(Info.values())
				.create(right, top + 22, 150, 20, Component.translatable("lunamap.option.info"), (b, v) -> {
					config.coordinates = v.coordinates;
					config.biome = v.biome;
				}));
		addRenderableWidget(CycleButton.onOffBuilder(config.caveMode)
				.create(left, top + 44, 150, 20, Component.translatable("lunamap.option.caves"), (b, v) -> config.caveMode = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.radar)
				.create(right, top + 44, 150, 20, Component.translatable("lunamap.option.radar"), (b, v) -> config.radar = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.worldWaypoints)
				.create(left, top + 66, 150, 20, Component.translatable("lunamap.option.world_waypoints"), (b, v) -> config.worldWaypoints = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.autoUpdate)
				.create(right, top + 66, 150, 20, Component.translatable("lunamap.option.auto_update"), (b, v) -> config.autoUpdate = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.deathPoints)
				.withTooltip(v -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("lunamap.option.death_points_hint")))
				.create(left, top + 88, 150, 20, Component.translatable("lunamap.option.death_points"), (b, v) -> config.deathPoints = v));
		Button xaero = addRenderableWidget(Button.builder(Component.translatable("lunamap.import.button"),
				b -> minecraft.setScreenAndShow(new XaeroImportScreen(this))).bounds(left, top + 110, 150, 20).build());
		Button online = addRenderableWidget(Button.builder(Component.translatable("lunamap.online.button"),
				b -> minecraft.setScreenAndShow(new OnlineMapScreen(this))).bounds(right, top + 110, 150, 20).build());
		addRenderableWidget(CycleButton.onOffBuilder(config.rotateMinimap)
				.create(right, top + 88, 150, 20, Component.translatable("lunamap.option.rotate"), (b, v) -> config.rotateMinimap = v));
		xaero.active = MapSession.world() != null;
		online.active = MapSession.world() != null;
		Button reset = addRenderableWidget(Button.builder(Component.translatable("lunamap.reset.button"),
				b -> minecraft.setScreenAndShow(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
					if (yes) {
						MapSession.resetDimension(minecraft);
					}
					minecraft.setScreenAndShow(this);
				}, Component.translatable("lunamap.reset.title", luna.map.data.Dimensions.name(MapSession.dimension())),
						Component.translatable("lunamap.reset.message")))).bounds(left, top + 132, 150, 20).build());
		reset.active = MapSession.store() != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(right, top + 132, 150, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, LunaColors.NIGHT_2, LunaColors.NIGHT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int axoW = AXO_W * AXO_SCALE;
		int axoH = AXO_H * AXO_SCALE;
		int top = top();
		int axoY = top - 22 - axoH;
		graphics.blit(RenderPipelines.GUI_TEXTURED, AXO, width / 2 - axoW / 2, axoY, 0, 0,
				axoW, axoH, AXO_W, AXO_H, AXO_W, AXO_H);
		graphics.centeredText(font, Component.literal(title.getString() + " " + Updater.currentVersion()), width / 2, axoY - 12, LunaColors.MOON);
		graphics.centeredText(font, ShaderStatus.describe(), width / 2, top - 14, LunaColors.LAVENDER);
		graphics.centeredText(font, Component.translatable("lunamap.keys_hint"), width / 2, top + 156, 0xAAA896E8);
		Component update = Updater.statusText();
		if (update != null) {
			graphics.centeredText(font, update, width / 2, top + 168, LunaColors.PINK);
		}
	}

	@Override
	public void onClose() {
		LunaConfig.get().save();
		minecraft.setScreenAndShow(parent);
	}
}
