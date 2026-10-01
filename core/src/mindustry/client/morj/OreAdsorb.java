package mindustry.client.morj;

import arc.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * While the player is already mining, stick the target to the nearest valid ore in range.
 * Sand loses to any other ore. Does not start mining on its own.
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

        int radius = Math.max(1, (int)Math.ceil(unit.type.mineRange / 8f));
        center.circle(radius, tile -> {
            Tile current = unit.mineTile;
            if(current == null) return;
            Item next = unit.getMineResult(tile);
            if((player.dst(current) > player.dst(tile) || current.drop() == Items.sand)
                && unit.validMine(tile)
                && next != null
                && unit.acceptsItem(next)
                && tile.drop() != Items.sand){
                unit.mineTile = tile;
            }
        });
    }
}
