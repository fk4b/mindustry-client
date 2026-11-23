package mindustry.client;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.navigation.*;
import mindustry.client.utils.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * Hides the reported aim cursor from other players while not shooting.
 * Real mouse is still used locally for aim/command; only the network/host-visible
 * position is replaced with a decoy (own unit by default).
 */
public final class CursorHide {
    private CursorHide() {}

    public static final int MODE_SELF = 0, MODE_ENEMY_UNIT = 1, MODE_RANDOM = 2, MODE_BREAKER = 3;

    private static final Vec2 decoy = new Vec2();
    private static int decoyMode = -1, decoyPlayerId = -1;
    private static float decoyUntil, decoyX, decoyY;

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
            Vec2 target = hiddenTarget(unit);
            unit.aim(target.x, target.y);
            player.mouseX = target.x;
            player.mouseY = target.y;
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
        if (!shouldHide() || player == null || player.unit() == null) return aimPos;

        Vec2 target = hiddenTarget(player.unit());
        boolean show = Core.settings.getBool("displayasuser");
        boolean assist = sendAssistingFlag();

        if (assist && show) {
            return aimPos.set(
                FloatEmbed.embedInFloat(target.x, ClientVars.FOO_USER),
                FloatEmbed.embedInFloat(target.y, ClientVars.ASSISTING)
            );
        }
        if (assist) {
            return aimPos.set(
                FloatEmbed.embedInFloat(target.x, ClientVars.ASSISTING),
                FloatEmbed.embedInFloat(target.y, ClientVars.ASSISTING)
            );
        }
        if (show) {
            return aimPos.set(
                FloatEmbed.embedInFloat(target.x, ClientVars.FOO_USER),
                FloatEmbed.embedInFloat(target.y, ClientVars.FOO_USER)
            );
        }
        return aimPos.set(target.x, target.y);
    }

    /** Returns the currently selected decoy position. Safe to call from the render/update thread only. */
    private static Vec2 hiddenTarget(Unit unit) {
        int mode = Mathf.clamp(Core.settings.getInt("hidecursormode", MODE_SELF), MODE_SELF, MODE_BREAKER);

        if (mode == MODE_SELF) {
            decoyMode = MODE_SELF;
            decoyPlayerId = -1;
            return decoy.set(unit.x, unit.y);
        }

        Player tracked = decoyPlayerId == -1 ? null : Groups.player.getByID(decoyPlayerId);
        boolean invalidTarget = decoyPlayerId != -1 && !validPlayerUnit(tracked);
        boolean stoppedBreaking = mode == MODE_BREAKER && tracked != null && validPlayerUnit(tracked) && !isBreaking(tracked.unit());
        boolean expired = Time.time >= decoyUntil;
        if (mode != decoyMode || expired || invalidTarget || stoppedBreaking || !Float.isFinite(decoyX) || !Float.isFinite(decoyY))
            pickTarget(unit, mode);

        //keep following a chosen player while the decoy is alive
        tracked = decoyPlayerId == -1 ? null : Groups.player.getByID(decoyPlayerId);
        if ((mode == MODE_ENEMY_UNIT || mode == MODE_BREAKER) && validPlayerUnit(tracked)) {
            setDecoy(tracked.unit().x, tracked.unit().y);
        }

        return decoy.set(decoyX, decoyY);
    }

    private static void pickTarget(Unit unit, int mode) {
        decoyMode = mode;
        decoyUntil = Time.time + 90f;
        decoyPlayerId = -1;

        if (mode == MODE_ENEMY_UNIT) {
            Seq<Player> candidates = new Seq<>();
            for (Player other : Groups.player) {
                if (other == null || other == player || other.dead() || other.team() == player.team()) continue;
                if (validPlayerUnit(other)) candidates.add(other);
            }
            if (candidates.isEmpty()) {
                setDecoy(unit.x, unit.y);
                return;
            }
            Player target = candidates.random();
            decoyPlayerId = target.id;
            setDecoy(target.unit().x, target.unit().y);
        } else if (mode == MODE_RANDOM) {
            setDecoy(Mathf.random(world.width() * tilesize), Mathf.random(world.height() * tilesize));
        } else if (mode == MODE_BREAKER) {
            Seq<Player> candidates = new Seq<>();
            for (Player other : Groups.player) {
                if (other == null || other.dead() || !validPlayerUnit(other)) continue;
                if (isBreaking(other.unit())) candidates.add(other);
            }
            if (candidates.isEmpty()) {
                setDecoy(unit.x, unit.y);
                return;
            }
            Player target = candidates.random();
            decoyPlayerId = target.id;
            setDecoy(target.unit().x, target.unit().y);
        } else {
            setDecoy(unit.x, unit.y);
        }
    }

    private static boolean validPlayerUnit(Player p) {
        return p != null && !p.dead() && p.unit() != null && p.unit().isValid();
    }

    private static boolean isBreaking(Unit u) {
        return u != null && u.isBuilding() && u.activelyBuilding() && u.buildPlan() != null && u.buildPlan().breaking;
    }

    private static void setDecoy(float x, float y) {
        decoyX = x;
        decoyY = y;
    }
}
