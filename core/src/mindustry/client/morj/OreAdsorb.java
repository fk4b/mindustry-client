package mindustry.client.morj;

import arc.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * While the player is already mining, stick the target to the nearest vein of that same item.
 * Does not switch to another resource and does not start mining on its own.
 */
public final class OreAdsorb{
    private OreAdsorb(){}

    public static void update(){
        if(!Core.settings.getBool("oreadsorb", true)) return;
        if(state.isMenu() || player == null || Core.scene.hasMouse()) return;
        Unit unit = player.unit();
        if(unit == null || unit.mineTile == null) return;
        Tile center = unit.tileOn();
        if(center == null) return;
        // The clicked tile decides the item. Later frames only move between veins of that item.
        Item want = unit.getMineResult(unit.mineTile);
        if(want == null) return;

        int radius = Math.max(1, (int)Math.ceil(unit.type.mineRange / 8f));
        center.circle(radius, tile -> {
            Tile current = unit.mineTile;
            if(current == null || tile == current) return;
            Item next = unit.getMineResult(tile);
            if(next == want
                && player.dst(current) > player.dst(tile)
                && unit.validMine(tile)
                && unit.acceptsItem(next)){
                unit.mineTile = tile;
            }
        });
    }
}
