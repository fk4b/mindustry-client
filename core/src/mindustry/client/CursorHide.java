package mindustry.client;

import arc.*;
import arc.math.geom.*;
import mindustry.client.navigation.*;
import mindustry.client.utils.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * Hides the reported aim cursor from other players while not shooting.
 * Real mouse is still used locally for aim/command; only the network/host-visible
 * position is pinned to the unit.
 */
public final class CursorHide {
    private CursorHide() {}

    /** True when setting is on and the player is not currently shooting. */
    public static boolean shouldHide() {
        return Core.settings.getBool("hidecursor")
            && player != null
            && !player.shooting
            && !player.dead();
    }

    /** Side-panel toggle: look semi-transparent like in assist, while just flying. */
    public static boolean smartTransparency() {
        return Core.settings.getBool("smarttransparency");
    }

    /** Embed ASSISTING bits so other Foo clients draw this player with formation alpha. */
    public static boolean sendAssistingFlag() {
        return Navigation.currentlyFollowing instanceof AssistPath || smartTransparency();
    }

    /**
     * Pins reported aim to the unit while not shooting:
     * - {@link Unit#aim} so {@code Main.floatEmbed()}/{@code clientSnapshot} send unit pos
     * - {@link Player#mouseX}/{@link Player#mouseY} so host entity sync matches
     * Local mouse / command / build still use {@link Core#input} mouse world independently.
     */
    public static void applyReportedCursor(Unit unit) {
        if (player == null || unit == null) return;
        if (shouldHide()) {
            unit.aim(unit.x, unit.y);
            player.mouseX = unit.x;
            player.mouseY = unit.y;
        } else {
            player.mouseX = unit.aimX();
            player.mouseY = unit.aimY();
        }
    }

    /**
     * Adjusts the aim vector used in {@code clientSnapshot} after {@link Main#floatEmbed()}.
     * Safety net if movement did not run this tick. Mutates {@code aimPos}.
     */
    public static Vec2 adjustSnapshotAim(Vec2 aimPos) {
        if (!shouldHide() || player == null) return aimPos;

        Unit unit = player.unit();
        float ax = unit.x;
        float ay = unit.y;

        boolean show = Core.settings.getBool("displayasuser");
        boolean assist = sendAssistingFlag();

        if (assist && show) {
            return aimPos.set(
                FloatEmbed.embedInFloat(ax, ClientVars.FOO_USER),
                FloatEmbed.embedInFloat(ay, ClientVars.ASSISTING)
            );
        }
        if (assist) {
            return aimPos.set(
                FloatEmbed.embedInFloat(ax, ClientVars.ASSISTING),
                FloatEmbed.embedInFloat(ay, ClientVars.ASSISTING)
            );
        }
        if (show) {
            return aimPos.set(
                FloatEmbed.embedInFloat(ax, ClientVars.FOO_USER),
                FloatEmbed.embedInFloat(ay, ClientVars.FOO_USER)
            );
        }
        return aimPos.set(ax, ay);
    }
}
