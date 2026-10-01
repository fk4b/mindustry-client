package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/** Hover trace of item and liquid transport. Draw only, no configs. */
public final class TransportScan{
    private static final Color itemOut = Color.valueOf("ffb060");
    private static final Color liquidOut = Color.valueOf("8ec8ff");
    private static final Color liquidIn = Color.valueOf("5ee0c5");
    private static final Seq<Building> queue = new Seq<>();
    private static final Seq<Building> next = new Seq<>();
    private static final IntSet seen = new IntSet();
    private static final IntMap<Building> parent = new IntMap<>();

    private TransportScan(){}

    public static void draw(){
        if(!Core.settings.getBool("transportscan", false)) return;
        if(state.isMenu() || !ui.hudfrag.shown || Core.scene.hasMouse() || Core.scene.hasDialog()) return;

        Tile tile = world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        Building origin = tile == null ? null : tile.build;
        if(origin == null || !isTransport(origin.block)) return;

        Draw.z(Layer.overlayUI);
        boolean liquid = isLiquid(origin.block);
        walk(origin, true, liquid);
        walk(origin, false, liquid);
        Drawf.square(origin.x, origin.y, origin.block.size * tilesize / 2f, 0f, liquid ? liquidOut : itemOut);
        Draw.reset();
    }

    private static void walk(Building origin, boolean outputs, boolean liquidHint){
        queue.clear();
        seen.clear();
        parent.clear();
        queue.add(origin);
        seen.add(origin.id);
        int head = 0;
        int steps = 0;
        while(head < queue.size && steps++ < 128){
            Building cur = queue.get(head++);
            if(outputs){
                if(cur != origin && !isTransport(cur.block)) continue;
                next.clear();
                collectOutputs(cur, parent.get(cur.id));
                for(int i = 0; i < next.size; i++){
                    Building n = next.get(i);
                    if(n == null || n == cur) continue;
                    arrow(cur, n, isLiquid(cur.block) || (liquidHint && isLiquid(n.block)), true);
                    if(isPipe(n.block) && !seen.contains(n.id)){
                        seen.add(n.id);
                        parent.put(n.id, cur);
                        queue.add(n);
                    }
                }
            }else{
                for(Building n : cur.proximity){
                    if(n == null || seen.contains(n.id)) continue;
                    boolean feeds = sendsTo(n, cur) || pullsFrom(cur, n);
                    if(!feeds) continue;
                    seen.add(n.id);
                    arrow(n, cur, isLiquid(n.block) || isLiquid(cur.block), false);
                    if(isPipe(n.block)) queue.add(n);
                }
                if(cur instanceof ItemBridge.ItemBridgeBuild bridge){
                    for(int i = 0; i < bridge.incoming.size; i++){
                        Building n = world.build(bridge.incoming.get(i));
                        if(n != null && !seen.contains(n.id)){
                            seen.add(n.id);
                            arrow(n, cur, isLiquid(n.block), false);
                            if(isPipe(n.block)) queue.add(n);
                        }
                    }
                }
            }
        }
    }

    private static void collectOutputs(Building build, Building prev){
        Block block = build.block;
        if(block instanceof Conveyor || block instanceof Duct || block instanceof StackConveyor || block instanceof Conduit){
            add(build.front());
            return;
        }
        if(block instanceof Junction || block instanceof DuctJunction || block instanceof LiquidJunction){
            if(prev != null){
                int back = build.relativeTo(prev);
                if(back >= 0) add(build.nearby((back + 2) % 4));
            }else{
                for(int i = 0; i < 4; i++) add(build.nearby(i));
            }
            return;
        }
        if(block instanceof DirectionBridge){
            DirectionBridge.DirectionBridgeBuild bridge = (DirectionBridge.DirectionBridgeBuild)build;
            Building link = bridge.findLink();
            if(link != null) add(link);
            else add(build.front());
            return;
        }
        if(block instanceof ItemBridge){
            ItemBridge.ItemBridgeBuild bridge = (ItemBridge.ItemBridgeBuild)build;
            Building link = world.build(bridge.link);
            if(link != null && ((ItemBridge)block).linkValid(build.tile, link.tile)) add(link);
            else add(build.front());
            return;
        }
        if(block instanceof MassDriver){
            MassDriver.MassDriverBuild driver = (MassDriver.MassDriverBuild)build;
            if(driver.linkValid()) add(world.build(driver.link));
            return;
        }
        if(block instanceof DirectionalUnloader){
            add(build.front());
            return;
        }
        if(block instanceof Sorter || block instanceof OverflowGate || block instanceof OverflowDuct || block instanceof DuctRouter){
            add(build.front());
            add(build.back());
            return;
        }
        if(block instanceof Router || block instanceof LiquidRouter || block instanceof Unloader || block instanceof Drill || block instanceof Pump){
            for(Building other : build.proximity){
                if(other != prev) add(other);
            }
        }
    }

    private static boolean sendsTo(Building from, Building to){
        next.clear();
        collectOutputs(from, null);
        if(next.contains(to)) return true;
        if(from.block instanceof Junction || from.block instanceof DuctJunction || from.block instanceof LiquidJunction){
            return from.relativeTo(to) >= 0;
        }
        return false;
    }

    private static boolean pullsFrom(Building unloader, Building storage){
        return (unloader.block instanceof Unloader || unloader.block instanceof DirectionalUnloader)
            && (storage.block instanceof StorageBlock || storage.block instanceof CoreBlock);
    }

    private static void add(Building build){
        if(build != null && !next.contains(build)) next.add(build);
    }

    private static void arrow(Building from, Building to, boolean liquid, boolean output){
        Color color = liquid ? (output ? liquidOut : liquidIn) : (output ? itemOut : Pal.heal);
        Drawf.arrow(from.x, from.y, to.x, to.y, tilesize * 0.65f, 2.2f, color);
    }

    private static boolean isPipe(Block block){
        return isTransport(block) && !(block instanceof Drill) && !(block instanceof Pump);
    }

    static boolean isTransport(Block block){
        return block instanceof Conveyor || block instanceof Duct || block instanceof Router
            || block instanceof Junction || block instanceof ItemBridge || block instanceof Sorter
            || block instanceof OverflowGate || block instanceof OverflowDuct || block instanceof DuctRouter
            || block instanceof StackConveyor || block instanceof MassDriver || block instanceof Unloader
            || block instanceof DirectionalUnloader || block instanceof DirectionBridge
            || block instanceof LiquidBlock || block instanceof Drill || block instanceof DuctJunction;
    }

    private static boolean isLiquid(Block block){
        return block instanceof Conduit || block instanceof LiquidRouter || block instanceof LiquidJunction
            || block instanceof LiquidBridge || block instanceof DirectionLiquidBridge || block instanceof Pump;
    }
}
