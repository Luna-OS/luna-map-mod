package luna.map.ui;

import com.mojang.blaze3d.platform.InputConstants;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Minimap mit der Maus verschieben (ziehen) und in der Groesse aendern (Ecke ziehen oder Mausrad). */
public class MinimapEditScreen extends Screen {
	private static final int HANDLE = 8;

	private final Screen parent;
	private boolean moving;
	private boolean resizing;
	private double grabX;
	private double grabY;
	private int anchorX;
	private int anchorY;

	public MinimapEditScreen(Screen parent) {
		super(Component.translatable("lunamap.edit.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("lunamap.edit.reset"), b -> {
			LunaConfig config = LunaConfig.get();
			config.posX = 1;
			config.posY = 0;
			config.mapSize = 96;
		}).bounds(width / 2 - 102, height - 28, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 2, height - 28, 100, 20).build());
	}

	private int[] rect() {
		LunaConfig config = LunaConfig.get();
		int[] pos = MinimapHud.position(minecraft, config, null);
		return new int[] {pos[0], pos[1], config.mapSize};
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, 0x60110D28);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		MinimapHud.INSTANCE.draw(graphics);
		int[] r = rect();
		int x1 = r[0] + r[2];
		int y1 = r[1] + r[2];
		graphics.outline(r[0] - 4, r[1] - 4, r[2] + 8, r[2] + 8, LunaColors.MOON);
		graphics.fill(x1 - HANDLE + 4, y1 - HANDLE + 4, x1 + 4, y1 + 4, LunaColors.PINK);
		// Texte oben, damit die Minimap sie nicht verdeckt, wenn man sie in die Mitte zieht
		graphics.fill(0, 0, width, 40, 0xC0110D28);
		graphics.centeredText(font, title, width / 2, 4, LunaColors.MOON);
		graphics.centeredText(font, Component.translatable("lunamap.edit.hint"), width / 2, 16, LunaColors.LAVENDER);
		graphics.centeredText(font, Component.translatable("lunamap.edit.size", r[2]), width / 2, 28, LunaColors.WHITE);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int[] r = rect();
		int x1 = r[0] + r[2];
		int y1 = r[1] + r[2];
		if (event.x() >= x1 - HANDLE && event.x() <= x1 + 6 && event.y() >= y1 - HANDLE && event.y() <= y1 + 6) {
			resizing = true;
			anchorX = r[0];
			anchorY = r[1];
			return true;
		}
		if (event.x() >= r[0] - 4 && event.x() <= x1 + 4 && event.y() >= r[1] - 4 && event.y() <= y1 + 4) {
			moving = true;
			grabX = event.x() - r[0];
			grabY = event.y() - r[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		LunaConfig config = LunaConfig.get();
		if (resizing) {
			// obere linke Ecke bleibt stehen, nur die Groesse aendert sich
			int size = (int) Math.round(Math.max(event.x() - anchorX, event.y() - anchorY));
			config.mapSize = Math.max(LunaConfig.MIN_SIZE, Math.min(LunaConfig.MAX_SIZE, size));
			moveTo(config, anchorX, anchorY);
			return true;
		}
		if (moving) {
			moveTo(config, event.x() - grabX, event.y() - grabY);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	/** Linke obere Ecke (GUI-Pixel) in die gespeicherten Anteile umrechnen. */
	private void moveTo(LunaConfig config, double x, double y) {
		int size = config.mapSize;
		double freeX = Math.max(1, width - size - 2 * MinimapHud.MARGIN);
		double freeY = Math.max(1, height - size - 2 * MinimapHud.MARGIN - MinimapHud.textBelow(config));
		config.posX = Math.max(0, Math.min(1, (x - MinimapHud.MARGIN) / freeX));
		config.posY = Math.max(0, Math.min(1, (y - MinimapHud.MARGIN) / freeY));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		moving = false;
		resizing = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		LunaConfig config = LunaConfig.get();
		int[] r = rect();
		config.mapSize = Math.max(LunaConfig.MIN_SIZE, Math.min(LunaConfig.MAX_SIZE, config.mapSize + (int) Math.signum(scrollY) * 8));
		moveTo(config, r[0], r[1]);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		LunaConfig.get().save();
		minecraft.setScreenAndShow(parent);
	}
}
