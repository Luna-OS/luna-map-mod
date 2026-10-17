package luna.map.data;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import luna.map.LunaColors;
import luna.map.LunaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Todespunkte: beim Tod entsteht "Tod" (Totenkopf). Stirbt man erneut, bevor man ihn erreicht hat, wird der alte zu
 * "Vorheriger Tod" (grauer Totenkopf). Erreicht man einen Todespunkt, verschwindet er.
 */
public final class DeathPoints {
	public static final int DEATH_COLOR = 0xFFE05A6E;
	public static final int OLD_DEATH_COLOR = 0xFF8C84B8;
	/** So nah muss man herankommen (Bloecke). */
	private static final double REACH = 3.5;
	/** Erst "scharf", wenn man nach dem Tod einmal weiter weg war (sonst verschwindet er beim Wiederbeleben vor Ort). */
	private static final double ARM_DISTANCE = 8;

	private static boolean wasDead;
	private static int timer;
	private static final Set<Waypoint> ARMED = Collections.newSetFromMap(new IdentityHashMap<>());

	private DeathPoints() {
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		WaypointStore store = MapSession.waypoints();
		if (player == null || store == null || MapSession.dimension() == null) {
			wasDead = false;
			return;
		}
		boolean dead = player.isDeadOrDying();
		if (dead && !wasDead && LunaConfig.get().deathPoints) {
			onDeath(mc, player, store);
		}
		wasDead = dead;
		if (dead || ++timer < 10) {
			return;
		}
		timer = 0;
		for (Waypoint waypoint : store.in(MapSession.dimension())) {
			if (!waypoint.isDeath()) {
				continue;
			}
			double dx = waypoint.x + 0.5 - player.getX();
			double dy = waypoint.y - player.getY();
			double dz = waypoint.z + 0.5 - player.getZ();
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > ARM_DISTANCE) {
				ARMED.add(waypoint);
			} else if (distance < REACH && ARMED.remove(waypoint)) {
				store.remove(waypoint);
				mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("lunamap.death.reached", waypoint.name)
						.withStyle(Style.EMPTY.withColor(LunaColors.LAVENDER & 0xFFFFFF)));
			}
		}
	}

	private static void onDeath(Minecraft mc, LocalPlayer player, WaypointStore store) {
		// der bisherige Tod wird zum "vorherigen Tod", einen noch aelteren braucht man nicht mehr
		for (Waypoint waypoint : store.all()) {
			if (waypoint.isOldDeath()) {
				store.remove(waypoint);
				ARMED.remove(waypoint);
			}
		}
		for (Waypoint waypoint : store.all()) {
			if (waypoint.isDeath()) {
				waypoint.type = Waypoint.OLD_DEATH;
				waypoint.name = Component.translatable("lunamap.death.old_name").getString();
				waypoint.color = OLD_DEATH_COLOR;
			}
		}
		Waypoint death = new Waypoint(Component.translatable("lunamap.death.name").getString(),
				player.getBlockX(), player.getBlockY(), player.getBlockZ(), DEATH_COLOR, MapSession.dimension());
		death.type = Waypoint.DEATH;
		store.add(death);
		mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("lunamap.death.set",
				death.x + ", " + death.y + ", " + death.z).withStyle(Style.EMPTY.withColor(DEATH_COLOR & 0xFFFFFF)));
	}
}
