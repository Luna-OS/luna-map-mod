package luna.map.ui;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import luna.map.LunaMap;
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

	private final Screen parent;

	public LunaScreen(Screen parent) {
		super(Component.translatable("lunamap.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		LunaConfig config = LunaConfig.get();
		int left = width / 2 - 155;
		int right = width / 2 + 5;
		int top = height / 2 - 40;

		addRenderableWidget(CycleButton.onOffBuilder(config.minimap)
				.create(left, top, 150, 20, Component.translatable("lunamap.option.minimap"),
						(b, v) -> config.minimap = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.coordinates)
				.create(right, top, 150, 20, Component.translatable("lunamap.option.coordinates"),
						(b, v) -> config.coordinates = v));
		addRenderableWidget(CycleButton.builder((LunaConfig.Size s) -> Component.translatable("lunamap.size." + s.name().toLowerCase()), config.size)
				.withValues(LunaConfig.Size.values())
				.create(left, top + 24, 150, 20, Component.translatable("lunamap.option.size"),
						(b, v) -> config.size = v));
		addRenderableWidget(CycleButton.builder((LunaConfig.Zoom z) -> Component.translatable("lunamap.zoom." + z.name().toLowerCase()), config.zoom)
				.withValues(LunaConfig.Zoom.values())
				.create(right, top + 24, 150, 20, Component.translatable("lunamap.option.zoom"),
						(b, v) -> config.zoom = v));
		addRenderableWidget(CycleButton.builder((LunaConfig.Corner c) -> Component.translatable("lunamap.corner." + c.name().toLowerCase()), config.corner)
				.withValues(LunaConfig.Corner.values())
				.create(left, top + 48, 150, 20, Component.translatable("lunamap.option.corner"),
						(b, v) -> config.corner = v));
		addRenderableWidget(CycleButton.onOffBuilder(config.worldWaypoints)
				.create(right, top + 48, 150, 20, Component.translatable("lunamap.option.world_waypoints"),
						(b, v) -> config.worldWaypoints = v));
		Button xaero = addRenderableWidget(Button.builder(Component.translatable("lunamap.import.button"),
				b -> minecraft.setScreenAndShow(new XaeroImportScreen(this))).bounds(left, top + 76, 150, 20).build());
		Button online = addRenderableWidget(Button.builder(Component.translatable("lunamap.online.button"),
				b -> minecraft.setScreenAndShow(new OnlineMapScreen(this))).bounds(right, top + 76, 150, 20).build());
		xaero.active = luna.map.data.MapSession.world() != null;
		online.active = luna.map.data.MapSession.world() != null;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 - 100, top + 104, 200, 20).build());
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
		int top = height / 2 - 40;
		int axoY = top - 22 - axoH;
		graphics.blit(RenderPipelines.GUI_TEXTURED, AXO, width / 2 - axoW / 2, axoY, 0, 0,
				axoW, axoH, AXO_W, AXO_H, AXO_W, AXO_H);
		graphics.centeredText(font, title, width / 2, axoY - 12, LunaColors.MOON);
		graphics.centeredText(font, ShaderStatus.describe(), width / 2, top - 14, LunaColors.LAVENDER);
		graphics.centeredText(font, Component.translatable("lunamap.keys_hint"), width / 2, top + 130, 0xAAA896E8);
	}

	@Override
	public void onClose() {
		LunaConfig.get().save();
		minecraft.setScreenAndShow(parent);
	}
}
