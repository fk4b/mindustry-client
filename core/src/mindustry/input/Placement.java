package mindustry.input;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.pooling.*;
import mindustry.entities.units.*;
import mindustry.gen.Building;
import mindustry.world.*;
import mindustry.content.Blocks;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.Build;

import java.util.*;

import static mindustry.Vars.*;

interface BridgePlacer{
    boolean unlockedNow();

    boolean positionsValid(int x1, int y1, int x2, int y2);

    void applyToPlans(BuildPlan cur, BuildPlan other);
}

class ItemBridgePlacer implements BridgePlacer{
    private final ItemBridge bridge;

    ItemBridgePlacer(ItemBridge bridge){
        this.bridge = bridge;
    }

    @Override
    public boolean unlockedNow(){
        return bridge.unlockedNow();
    }

    @Override
    public boolean positionsValid(int x1, int y1, int x2, int y2){
        return bridge.positionsValid(x1, y1, x2, y2);
    }

    @Override
    public void applyToPlans(BuildPlan cur, BuildPlan other){
        cur.block = bridge;
        other.block = bridge;
        other.config = new Point2(cur.x - other.x, cur.y - other.y);
    }
}

class DirectionBridgePlacer implements BridgePlacer{
    private final DirectionBridge bridge;

    DirectionBridgePlacer(DirectionBridge bridge){
        this.bridge = bridge;
    }

    @Override
    public boolean unlockedNow(){
        return bridge.unlockedNow();
    }

    @Override
    public boolean positionsValid(int x1, int y1, int x2, int y2){
        return bridge.positionsValid(x1, y1, x2, y2);
    }

    @Override
    public void applyToPlans(BuildPlan cur, BuildPlan other){
        cur.block = bridge;
        other.block = bridge;
    }
}

public class Placement{
    private static final Seq<BuildPlan> plans1 = new Seq<>();
    private static final Seq<Point2> tmpPoints = new Seq<>(), tmpPoints2 = new Seq<>();
    private static final NormalizeResult result = new NormalizeResult();
    private static final NormalizeDrawResult drawResult = new NormalizeDrawResult();
    private static final Bresenham2 bres = new Bresenham2();
    private static final Seq<Point2> points = new Seq<>();
    private static final IntSeq tmpInts = new IntSeq(), tmpInts2 = new IntSeq();

    //for pathfinding
    private static final IntFloatMap costs = new IntFloatMap();
    private static final IntIntMap parents = new IntIntMap();
    private static final IntSet closed = new IntSet();

    /** Copper / titanium / armored — existing belts that plastanium should not cut. */
    public static boolean isLowTierConveyor(Block b){
        return b == Blocks.conveyor || b == Blocks.titaniumConveyor || b == Blocks.armoredConveyor;
    }

    public static boolean isConduitLine(Block b){
        return b == Blocks.conduit || b == Blocks.pulseConduit || b == Blocks.platedConduit;
    }

    public static boolean isStackConveyorLine(Block b){
        return b instanceof StackConveyor;
    }

    /** Line direction at plan i: 0 = horizontal, 1 = vertical, -1 = unknown. */
    private static int planAxis(Seq<BuildPlan> plans, int i){
        BuildPlan p = plans.get(i);
        if(i > 0){
            BuildPlan prev = plans.get(i - 1);
            if(prev.x == p.x && prev.y != p.y) return 1;
            if(prev.y == p.y && prev.x != p.x) return 0;
        }
        if(i + 1 < plans.size){
            BuildPlan next = plans.get(i + 1);
            if(next.x == p.x && next.y != p.y) return 1;
            if(next.y == p.y && next.x != p.x) return 0;
        }
        return -1;
    }

    private static boolean isItemFamily(Block b){
        return isLowTierConveyor(b) || b == Blocks.itemBridge || b == Blocks.phaseConveyor;
    }

    private static boolean isLiquidFamily(Block b){
        return isConduitLine(b) || b == Blocks.bridgeConduit || b == Blocks.phaseConduit;
    }

    private static boolean sameCrossHost(Block host, Block b){
        if(isItemFamily(host)) return isItemFamily(b);
        if(isLiquidFamily(host)) return isLiquidFamily(b);
        if(isStackConveyorLine(host)) return b == host;
        return b == host;
    }

    private static boolean isGapSkip(Block b){
        return b == Blocks.air || b == Blocks.junction || b == Blocks.liquidJunction;
    }

    /** Existing hop nodes that should be skipped (then broken) when extending a jump. */
    public static boolean isHopBridge(Block b){
        return b == Blocks.itemBridge || b == Blocks.phaseConveyor
            || b == Blocks.bridgeConduit || b == Blocks.phaseConduit
            || b instanceof DirectionBridge;
    }

    /** True if the gap between two line plans already has a hop — do not rebuild it. */
    public static boolean existingHopBetween(BuildPlan a, BuildPlan b){
        if(a == null || b == null) return false;
        if(a.x != b.x && a.y != b.y) return false;
        int dx = Integer.signum(b.x - a.x), dy = Integer.signum(b.y - a.y);
        int x = a.x + dx, y = a.y + dy;
        while(x != b.x || y != b.y){
            Tile t = world.tile(x, y);
            if(t != null && isHopBridge(t.block())) return true;
            x += dx;
            y += dy;
        }
        return false;
    }

    /**
     * Walk along the belt/pipe away from the crossing. First matching tile is the endpoint
     * (right next to the plastanium), not a distant belt beyond empty ground.
     */
    private static Tile walkCrossHost(int x, int y, int rot, int dir, IntSet occupied, int max, Block host, boolean gaps){
        int dx = Geometry.d4x(rot) * dir;
        int dy = Geometry.d4y(rot) * dir;
        for(int i = 0; i < max; i++){
            x += dx;
            y += dy;
            Tile t = world.tile(x, y);
            if(t == null) return null;
            if(occupied.contains(Point2.pack(x, y))) continue;
            Block b = t.block();
            if(isStackConveyorLine(b)) continue;
            if(sameCrossHost(host, b)){
                // Far hop of another plastanium road — do not steal it.
                if(isHopBridge(b) && occupied != null && !partOfThisCrossing(x, y, occupied)) return null;
                return t;
            }
            if(gaps && isGapSkip(b)) continue;
            if(b == Blocks.junction || b == Blocks.liquidJunction) continue;
            return null;
        }
        return null;
    }

    /** Plastanium tiles (plans + already built) in a row along rot, including (x, y). */
    private static int countStrip(int x, int y, int rot, IntSet occupied){
        int n = 0;
        if(occupied != null && occupied.contains(Point2.pack(x, y))) n++;
        else{
            Tile t = world.tile(x, y);
            if(t != null && isStackConveyorLine(t.block())) n++;
        }
        for(int dir = -1; dir <= 1; dir += 2){
            int cx = x, cy = y;
            for(int i = 0; i < 16; i++){
                cx += Geometry.d4x(rot) * dir;
                cy += Geometry.d4y(rot) * dir;
                if(occupied != null && occupied.contains(Point2.pack(cx, cy))){
                    n++;
                    continue;
                }
                Tile t = world.tile(cx, cy);
                if(t != null && isStackConveyorLine(t.block())){
                    n++;
                    continue;
                }
                break;
            }
        }
        return n;
    }

    private static int hostWalkRange(Block host){
        if(host == Blocks.phaseConveyor) return ((ItemBridge)Blocks.phaseConveyor).range;
        if(host == Blocks.phaseConduit) return ((ItemBridge)Blocks.phaseConduit).range;
        if(isConduitLine(host) || host == Blocks.bridgeConduit){
            int r = ((ItemBridge)Blocks.bridgeConduit).range;
            if(Blocks.phaseConduit.unlockedNow()) r = Math.max(r, ((ItemBridge)Blocks.phaseConduit).range);
            return r;
        }
        int r = ((ItemBridge)Blocks.itemBridge).range;
        if(Blocks.phaseConveyor.unlockedNow()) r = Math.max(r, ((ItemBridge)Blocks.phaseConveyor).range);
        return r;
    }

    private static ItemBridge pickCrossBridge(Block host, int dist, int strip){
        if(isLiquidFamily(host)){
            ItemBridge liquid = (ItemBridge)Blocks.bridgeConduit;
            ItemBridge phase = (ItemBridge)Blocks.phaseConduit;
            if(phase.unlockedNow() && (host == Blocks.phaseConduit || dist > liquid.range || strip >= 4)) return phase;
            return liquid.unlockedNow() ? liquid : null;
        }
        ItemBridge items = (ItemBridge)Blocks.itemBridge;
        ItemBridge phase = (ItemBridge)Blocks.phaseConveyor;
        // 4+ plastanium in a row, already a phase hop, or item-bridge range is too short.
        if(phase.unlockedNow() && (host == Blocks.phaseConveyor || dist > items.range || strip >= 4)) return phase;
        return items.unlockedNow() ? items : null;
    }

    /** Input links to output. Output gets Integer -1 (0 connections) so parallel hops do not cross. */
    private static void addBeltBridge(Seq<BuildPlan> extra, Seq<BuildPlan> breaks, IntSet used, ItemBridge bridge, Tile from, Tile to, IntSet occupied){
        if(from == null || to == null || bridge == null) return;
        if(!bridge.unlockedNow()) return;
        if(!bridge.positionsValid(from.x, from.y, to.x, to.y)) return;
        boolean liquid = bridge instanceof mindustry.world.blocks.liquid.LiquidBridge;
        if(isStackConveyorLine(from.block()) || isStackConveyorLine(to.block())) return;
        if(liquid && (isItemFamily(from.block()) || isItemFamily(to.block()))) return;
        if(!liquid && (isLiquidFamily(from.block()) || isLiquidFamily(to.block()))) return;
        if(occupied != null && (occupied.contains(Point2.pack(from.x, from.y)) || occupied.contains(Point2.pack(to.x, to.y)))) return;
        int a = Point2.pack(from.x, from.y);
        int b = Point2.pack(to.x, to.y);
        if(used.contains(a) || used.contains(b)) return;
        used.add(a);
        used.add(b);
        breakInnerBridges(breaks, from, to, occupied);
        Point2 link = new Point2(to.x - from.x, to.y - from.y);
        placeOrRelink(extra, breaks, from, bridge, link, occupied);
        placeOrRelink(extra, breaks, to, bridge, Integer.valueOf(-1), occupied);
        addPhasePowerNodes(extra, used, occupied, from, to, bridge);
    }

    /** Remove leftover hop bridges on the pipe/belt between the new endpoints. */
    private static void breakInnerBridges(Seq<BuildPlan> breaks, Tile from, Tile to, IntSet occupied){
        if(from.x != to.x && from.y != to.y) return;
        int dx = Integer.signum(to.x - from.x), dy = Integer.signum(to.y - from.y);
        int x = from.x + dx, y = from.y + dy;
        while(x != to.x || y != to.y){
            int packed = Point2.pack(x, y);
            if(occupied == null || !occupied.contains(packed)){
                Tile t = world.tile(x, y);
                if(t != null && t.build != null && isHopBridge(t.block()) && partOfThisCrossing(x, y, occupied)
                    && packed != Point2.pack(from.x, from.y) && packed != Point2.pack(to.x, to.y)){
                    breaks.add(new BuildPlan(x, y));
                }
            }
            x += dx;
            y += dy;
        }
    }

    private static boolean adjacentToOccupied(int x, int y, IntSet occupied){
        if(occupied == null) return true;
        if(occupied.contains(Point2.pack(x, y))) return true;
        for(int d = 0; d < 4; d++){
            if(occupied.contains(Point2.pack(x + Geometry.d4x(d), y + Geometry.d4y(d)))) return true;
        }
        return false;
    }

    /**
     * Hop belongs to this placement: adjacent to the new line or to any connected
     * plastanium/surge already touching it (2nd, 3rd, 4th row in a strip).
     */
    private static boolean partOfThisCrossing(int x, int y, IntSet line){
        if(line == null || line.isEmpty()) return true;
        if(adjacentToOccupied(x, y, line)) return true;
        IntSet strip = new IntSet();
        IntSeq q = new IntSeq();
        line.each(p -> {
            strip.add(p);
            q.add(p);
        });
        for(int i = 0; i < q.size && i < 64; i++){
            int p = q.get(i);
            int sx = Point2.x(p), sy = Point2.y(p);
            for(int d = 0; d < 4; d++){
                int nx = sx + Geometry.d4x(d), ny = sy + Geometry.d4y(d);
                int np = Point2.pack(nx, ny);
                if(strip.contains(np)) continue;
                Tile t = world.tile(nx, ny);
                if(t != null && isStackConveyorLine(t.block())){
                    strip.add(np);
                    q.add(np);
                }
            }
        }
        return adjacentToOccupied(x, y, strip);
    }

    private static boolean existingConfigMatches(Building build, Object config){
        if(build instanceof ItemBridge.ItemBridgeBuild ib){
            if(config instanceof Integer i) return ib.link == i;
            if(config instanceof Point2 p) return ib.link == Point2.pack(ib.tile.x + p.x, ib.tile.y + p.y);
        }
        if(build.power != null && config instanceof Integer packed){
            return build.power.links.contains(packed);
        }
        return false;
    }

    /**
     * Keep existing hop endpoints (only re-link). Place a new bridge on belt/pipe.
     * Never demolish the far hop of the previous plastanium row.
     */
    private static void placeOrRelink(Seq<BuildPlan> extra, Seq<BuildPlan> breaks, Tile t, ItemBridge bridge, Object config, IntSet occupied){
        if(occupied != null && occupied.contains(Point2.pack(t.x, t.y))) return;
        if(t.build != null && isHopBridge(t.block())){
            if(occupied != null && !partOfThisCrossing(t.x, t.y, occupied)) return;
            if(t.block() == bridge){
                if(!existingConfigMatches(t.build, config)){
                    extra.add(new BuildPlan(t.x, t.y, 0, bridge, config));
                }
                return;
            }
            // item bridge → phase (4+ plastanium): break then place, validPlace-replace is not enough
            breaks.add(new BuildPlan(t.x, t.y));
            extra.add(new BuildPlan(t.x, t.y, 0, bridge, config));
            return;
        }
        extra.add(new BuildPlan(t.x, t.y, 0, bridge, config));
    }

    private static final ObjectSet<Building> noRemoving = new ObjectSet<>();

    private static class NodePlace{
        int x, y;
        Block block;
        NodePlace(int x, int y, Block block){
            this.x = x;
            this.y = y;
            this.block = block;
        }
    }

    /** Power node next to each phase bridge. Only empty tiles; large node if small does not fit. */
    private static void addPhasePowerNodes(Seq<BuildPlan> extra, IntSet used, IntSet occupied, Tile from, Tile to, ItemBridge bridge){
        if(bridge != Blocks.phaseConveyor && bridge != Blocks.phaseConduit) return;
        if(!Blocks.powerNode.unlockedNow()) return;
        IntSet blocked = occupied != null ? occupied : used;

        if(linkExistingNode(from.x, from.y, blocked, extra) && linkExistingNode(to.x, to.y, blocked, extra)) return;

        NodePlace a = findEmptyNodeSpot(from.x, from.y, blocked, used);
        NodePlace b = findEmptyNodeSpot(to.x, to.y, blocked, used);
        if(a != null && b != null && a.x == b.x && a.y == b.y && a.block == b.block) b = null;

        if(a != null) markFootprint(used, a.block, a.x, a.y);
        if(b != null) markFootprint(used, b.block, b.x, b.y);

        emitPhaseNode(extra, a, from, to, b, true);
        emitPhaseNode(extra, b, to, from, a, false);
    }

    /** Reuse a node already in range instead of placing. */
    private static boolean linkExistingNode(int px, int py, IntSet occupied, Seq<BuildPlan> extra){
        if(player == null) return false;
        Building best = null;
        float bestD = Float.MAX_VALUE;
        float wx = px * tilesize, wy = py * tilesize;
        for(Building b : player.team().data().getBuildings(Blocks.powerNode)){
            if(b == null || b.power == null) continue;
            if(occupied.contains(Point2.pack(b.tileX(), b.tileY()))) continue;
            if(!nodesLinkable(b.block, b.tileX(), b.tileY(), px, py)) continue;
            float d = Mathf.dst2(b.x, b.y, wx, wy);
            if(d < bestD){ bestD = d; best = b; }
        }
        for(Building b : player.team().data().getBuildings(Blocks.powerNodeLarge)){
            if(b == null || b.power == null) continue;
            if(occupied.contains(Point2.pack(b.tileX(), b.tileY()))) continue;
            if(!nodesLinkable(b.block, b.tileX(), b.tileY(), px, py)) continue;
            float d = Mathf.dst2(b.x, b.y, wx, wy);
            if(d < bestD){ bestD = d; best = b; }
        }
        if(best == null) return false;
        int packed = Point2.pack(px, py);
        if(!best.power.links.contains(packed)){
            extra.add(new BuildPlan(best.tileX(), best.tileY(), 0, best.block, packed));
        }
        return true;
    }

    private static NodePlace findEmptyNodeSpot(int px, int py, IntSet occupied, IntSet used){
        NodePlace s = searchEmptyNode(px, py, Blocks.powerNode, occupied, used, Math.max(2, (int)((PowerNode)Blocks.powerNode).laserRange));
        if(s != null) return s;
        if(Blocks.powerNodeLarge.unlockedNow()){
            s = searchEmptyNode(px, py, Blocks.powerNodeLarge, occupied, used, Math.max(3, (int)((PowerNode)Blocks.powerNodeLarge).laserRange));
            if(s != null) return s;
        }
        return null;
    }

    private static NodePlace searchEmptyNode(int px, int py, Block block, IntSet occupied, IntSet used, int maxDist){
        for(int dist = 1; dist <= maxDist; dist++){
            for(int dy = -dist; dy <= dist; dy++){
                for(int dx = -dist; dx <= dist; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != dist) continue;
                    int x = px + dx, y = py + dy;
                    if(!nodeTilesEmpty(block, x, y, occupied, used)) continue;
                    if(!nodesLinkable(block, x, y, px, py)) continue;
                    return new NodePlace(x, y, block);
                }
            }
        }
        return null;
    }

    private static boolean nodeTilesEmpty(Block block, int x, int y, IntSet occupied, IntSet used){
        int off = (block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int tx = x + dx - off, ty = y + dy - off;
                int packed = Point2.pack(tx, ty);
                if(occupied.contains(packed) || used.contains(packed)) return false;
                Tile t = world.tile(tx, ty);
                if(t == null || t.build != null) return false;
                if(t.block() != Blocks.air) return false;
                if(t.floor().isDeep() && !block.placeableLiquid) return false;
            }
        }
        return true;
    }

    private static void emitPhaseNode(Seq<BuildPlan> extra, NodePlace spot, Tile phase, Tile otherPhase, NodePlace otherNode, boolean linkBothIfSolo){
        if(spot == null || phase == null) return;
        Seq<Point2> links = new Seq<>();
        links.add(new Point2(phase.x - spot.x, phase.y - spot.y));
        if(linkBothIfSolo && otherNode == null && otherPhase != null
            && nodesLinkable(spot.block, spot.x, spot.y, otherPhase.x, otherPhase.y)){
            links.add(new Point2(otherPhase.x - spot.x, otherPhase.y - spot.y));
        }
        if(otherNode != null && nodesLinkable(spot.block, spot.x, spot.y, otherNode.x, otherNode.y)){
            links.add(new Point2(otherNode.x - spot.x, otherNode.y - spot.y));
        }
        BuildPlan plan = new BuildPlan(spot.x, spot.y, 0, spot.block);
        plan.config = links.toArray(Point2.class);
        extra.add(plan);
    }

    private static boolean tryCrossHop(Seq<BuildPlan> extra, Seq<BuildPlan> breaks, IntSet used, IntSet occupied, Tile tile, int rot, Block host){
        // Only phase-to-phase lines have empty gaps; copper/titanium/pipes must stop at the first break
        // or the hop lands a phase bridge many tiles away.
        boolean gaps = host == Blocks.phaseConveyor || host == Blocks.phaseConduit;
        int max = Math.max(2, hostWalkRange(host));
        // rot 0-3: from = behind (upstream), to = ahead (downstream) along the belt.
        Tile from = walkCrossHost(tile.x, tile.y, rot, -1, occupied, max, host, gaps);
        Tile to = walkCrossHost(tile.x, tile.y, rot, 1, occupied, max, host, gaps);
        if(from == null || to == null) return false;
        int dist = Math.max(Math.abs(to.x - from.x), Math.abs(to.y - from.y));
        int strip = countStrip(tile.x, tile.y, rot, occupied);
        addBeltBridge(extra, breaks, used, pickCrossBridge(host, dist, strip), from, to, occupied);
        return used.contains(Point2.pack(from.x, from.y));
    }

    /** 0-3 facing of an existing hop, or -1. */
    private static int rotationFromBridge(Tile tile, ItemBridge.ItemBridgeBuild ib){
        if(ib.link != -1){
            Tile o = world.tile(ib.link);
            if(o != null && (o.x == tile.x || o.y == tile.y)) return tile.absoluteRelativeTo(o.x, o.y);
        }
        if(ib.incoming != null && ib.incoming.size > 0){
            Tile o = world.tile(ib.incoming.first());
            if(o != null) return o.absoluteRelativeTo(tile.x, tile.y);
        }
        return -1;
    }

    /** Conveyor/pipe facing (0-3). Looks at the tile, then neighbors, then hop-bridge links. */
    private static int inferHostRotation(Tile tile, Block host, IntSet occupied){
        if(tile.build != null){
            if(isLowTierConveyor(tile.block()) || isConduitLine(tile.block())) return tile.build.rotation;
            if(tile.build instanceof ItemBridge.ItemBridgeBuild ib){
                int r = rotationFromBridge(tile, ib);
                if(r >= 0) return r;
            }
        }
        for(int dist = 1; dist <= 3; dist++){
            for(int d = 0; d < 4; d++){
                int nx = tile.x + Geometry.d4x(d) * dist;
                int ny = tile.y + Geometry.d4y(d) * dist;
                if(occupied != null && occupied.contains(Point2.pack(nx, ny))) continue;
                Tile n = world.tile(nx, ny);
                if(n == null || n.build == null) continue;
                if(isStackConveyorLine(n.block())) continue;
                if(!sameCrossHost(host, n.block())) continue;
                if(isLowTierConveyor(n.block()) || isConduitLine(n.block())) return n.build.rotation;
                if(n.build instanceof ItemBridge.ItemBridgeBuild ib){
                    int r = rotationFromBridge(n, ib);
                    if(r >= 0) return r;
                }
            }
        }
        return -1;
    }

    /**
     * Stack conveyor line (plastanium / surge) stays. Existing belts / phase / pipes / surge
     * that the line crosses become bridges hopping over it. Output node has no link.
     */
    public static void applyPlastaniumCrossBridges(Seq<BuildPlan> plans, ItemBridge bridge){
        if(plans == null || plans.isEmpty()) return;
        if(!Core.settings.getBool("plastaniumcrossbridges", true)) return;

        IntSet occupied = new IntSet();
        for(BuildPlan p : plans){
            occupied.add(Point2.pack(p.x, p.y));
        }

        IntSet used = new IntSet();
        Seq<BuildPlan> extra = new Seq<>();
        Seq<BuildPlan> breaks = new Seq<>();

        // Demolish whatever the plastanium will sit on (pipes, belts, old hops) first —
        // stack conveyors cannot replace liquids, so a lone P would otherwise leave the pipe.
        for(BuildPlan p : plans){
            if(p.breaking || p.block == null) continue;
            Tile t = world.tile(p.x, p.y);
            if(t != null && t.build != null && t.block() != p.block){
                breaks.add(new BuildPlan(p.x, p.y));
            }
        }

        for(int i = 0; i < plans.size; i++){
            BuildPlan p = plans.get(i);
            Tile tile = world.tile(p.x, p.y);
            if(tile == null) continue;
            Block host = tile.block();
            // Adjacent plastanium/surge is not a host — do not plant hop bridges on it.
            if(isStackConveyorLine(host)) continue;
            if(!isLowTierConveyor(host) && host != Blocks.phaseConveyor && host != Blocks.itemBridge
                && !isConduitLine(host) && host != Blocks.phaseConduit && host != Blocks.bridgeConduit) continue;

            int rot = inferHostRotation(tile, host, occupied);
            if(rot >= 0){
                tryCrossHop(extra, breaks, used, occupied, tile, rot, host);
                continue;
            }
            // Unknown facing: hop perpendicular to the plastanium line, both ways.
            int lineAx = planAxis(plans, i);
            for(int ax = 0; ax <= 1; ax++){
                if(lineAx == ax) continue;
                if(tryCrossHop(extra, breaks, used, occupied, tile, ax, host)) break;
                if(tryCrossHop(extra, breaks, used, occupied, tile, ax + 2, host)) break;
            }
        }

        extra.removeAll(p -> occupied.contains(Point2.pack(p.x, p.y)));
        for(int i = breaks.size - 1; i >= 0; i--){
            plans.insert(0, breaks.get(i));
        }
        plans.addAll(extra);
    }

    private static boolean isRelocatablePowerNode(Building b){
        return b != null && (b.block == Blocks.powerNode || b.block == Blocks.powerNodeLarge);
    }

    private static boolean nodeOverlaps(Building node, int x, int y){
        int off = (node.block.size - 1) / 2;
        int ox = node.tileX() - off, oy = node.tileY() - off;
        return x >= ox && y >= oy && x < ox + node.block.size && y < oy + node.block.size;
    }

    private static void markFootprint(IntSet set, Block block, int x, int y){
        int off = (block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                set.add(Point2.pack(x + dx - off, y + dy - off));
            }
        }
    }

    private static boolean footprintFree(Block block, int x, int y, IntSet conveyor, IntSet used, ObjectSet<Building> removing){
        int off = (block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int tx = x + dx - off, ty = y + dy - off;
                int packed = Point2.pack(tx, ty);
                if(conveyor.contains(packed) || used.contains(packed)) return false;
                Tile t = world.tile(tx, ty);
                if(t == null) return false;
                if(t.floor().isDeep() && !block.placeableLiquid) return false;
                if(t.build != null){
                    if(removing.contains(t.build)) continue; // node being relocated, tile will be empty
                    return false; // never break other buildings
                }
                if(t.block() != Blocks.air) return false;
            }
        }
        return true;
    }

    private static Point2 findNodeSpot(int cx, int cy, int sdx, int sdy, Block block, IntSet conveyor, IntSet used, ObjectSet<Building> removing, int maxDist){
        int ldx = -sdy, ldy = sdx;
        for(int dist = 1; dist <= maxDist; dist++){
            int bx = cx + sdx * dist;
            int by = cy + sdy * dist;
            for(int slide = 0; slide <= maxDist; slide++){
                int[] offs = slide == 0 ? new int[]{0} : new int[]{slide, -slide};
                for(int s : offs){
                    int x = bx + ldx * s;
                    int y = by + ldy * s;
                    if(footprintFree(block, x, y, conveyor, used, removing)){
                        return new Point2(x, y);
                    }
                }
            }
        }
        return null;
    }

    /** Empty tile for a node; if the original size does not fit, try the large node. */
    private static Point2 findNodeSpotOrLarge(int cx, int cy, int sdx, int sdy, Block[] block, IntSet conveyor, IntSet used, ObjectSet<Building> removing, int maxDist){
        Point2 p = findNodeSpot(cx, cy, sdx, sdy, block[0], conveyor, used, removing, maxDist);
        if(p != null) return p;
        if(block[0] != Blocks.powerNodeLarge && Blocks.powerNodeLarge.unlockedNow()){
            int largeDist = Math.max(maxDist, (int)((PowerNode)Blocks.powerNodeLarge).laserRange);
            p = findNodeSpot(cx, cy, sdx, sdy, Blocks.powerNodeLarge, conveyor, used, removing, largeDist);
            if(p != null){
                block[0] = Blocks.powerNodeLarge;
                return p;
            }
        }
        return null;
    }

    private static boolean nodesLinkable(Block block, int x1, int y1, int x2, int y2){
        if(!(block instanceof PowerNode pn)) return true;
        float range = pn.laserRange * tilesize;
        float o = block.offset;
        return Mathf.dst(x1 * tilesize + o, y1 * tilesize + o, x2 * tilesize + o, y2 * tilesize + o) <= range;
    }

    /**
     * Plastanium line hits a small/large power node: break it and place two of the same
     * type on opposite sides of the belt so they can link across immediately.
     */
    public static void applyPlastaniumNodeRelocate(Seq<BuildPlan> plans){
        if(plans == null || plans.isEmpty() || player == null) return;

        IntSet occupied = new IntSet();
        for(BuildPlan p : plans){
            if(!p.breaking && p.block != null) occupied.add(Point2.pack(p.x, p.y));
        }

        ObjectSet<Building> nodes = new ObjectSet<>();
        IntMap<int[]> hit = new IntMap<>();
        for(int i = 0; i < plans.size; i++){
            BuildPlan p = plans.get(i);
            if(p.breaking) continue;
            Tile t = world.tile(p.x, p.y);
            if(t == null || t.build == null) continue;
            Building b = t.build;
            if(b.team != player.team() || !isRelocatablePowerNode(b)) continue;
            nodes.add(b);
            int axis = planAxis(plans, i);
            hit.put(b.pos(), new int[]{p.x, p.y, axis});
        }
        if(nodes.isEmpty()) return;

        IntSet used = new IntSet();
        Seq<BuildPlan> breaks = new Seq<>();
        Seq<BuildPlan> places = new Seq<>();
        IntSet broken = new IntSet();

        for(Building node : nodes){
            if(!broken.add(node.pos())) continue;
            breaks.add(new BuildPlan(node.tileX(), node.tileY()));

            int[] h = hit.get(node.pos());
            int cx = h != null ? h[0] : node.tileX();
            int cy = h != null ? h[1] : node.tileY();
            int axis = h != null ? h[2] : -1;
            if(axis < 0) axis = 0;

            Block block = node.block;
            int maxDist = Math.max(8, block instanceof PowerNode pn ? (int)pn.laserRange : 8);
            int sdx = axis == 0 ? 0 : 1;
            int sdy = axis == 0 ? 1 : 0;

            Block[] ba = {block}, bb = {block};
            Point2 a = findNodeSpotOrLarge(cx, cy, sdx, sdy, ba, occupied, used, nodes, maxDist);
            Point2 b = findNodeSpotOrLarge(cx, cy, -sdx, -sdy, bb, occupied, used, nodes, maxDist);
            if(a == null && b == null && axis == 0){
                a = findNodeSpotOrLarge(cx, cy, 1, 0, ba, occupied, used, nodes, maxDist);
                b = findNodeSpotOrLarge(cx, cy, -1, 0, bb, occupied, used, nodes, maxDist);
            }

            if(a != null) markFootprint(used, ba[0], a.x, a.y);
            if(b != null) markFootprint(used, bb[0], b.x, b.y);

            Seq<Point2> linksA = new Seq<>();
            Seq<Point2> linksB = new Seq<>();
            if(a != null && b != null && nodesLinkable(ba[0], a.x, a.y, b.x, b.y)){
                linksA.add(new Point2(b.x - a.x, b.y - a.y));
                linksB.add(new Point2(a.x - b.x, a.y - b.y));
            }
            if(node.power != null){
                for(int i = 0; i < node.power.links.size; i++){
                    Building other = world.build(node.power.links.get(i));
                    if(other == null || nodes.contains(other)) continue;
                    if(a != null) linksA.add(new Point2(other.tileX() - a.x, other.tileY() - a.y));
                    if(b != null) linksB.add(new Point2(other.tileX() - b.x, other.tileY() - b.y));
                }
            }

            int maxLinksA = ba[0] instanceof PowerNode pn ? pn.maxNodes : 10;
            int maxLinksB = bb[0] instanceof PowerNode pn ? pn.maxNodes : 10;
            if(linksA.size > maxLinksA) linksA.truncate(maxLinksA);
            if(linksB.size > maxLinksB) linksB.truncate(maxLinksB);

            if(a != null){
                BuildPlan pa = new BuildPlan(a.x, a.y, 0, ba[0]);
                if(linksA.size > 0) pa.config = linksA.toArray();
                places.add(pa);
            }
            if(b != null){
                BuildPlan pb = new BuildPlan(b.x, b.y, 0, bb[0]);
                if(linksB.size > 0) pb.config = linksB.toArray();
                places.add(pb);
            }
        }

        for(int i = breaks.size - 1; i >= 0; i--){
            plans.insert(0, breaks.get(i));
        }
        plans.addAll(places);
    }

    public static Seq<Point2> pathfindLine(boolean conveyors, int startX, int startY, int endX, int endY){
        Pools.freeAll(points);
        points.clear();
        if(conveyors && Core.settings.getBool("conveyorpathfinding")){
            if(astar(startX, startY, endX, endY)){
                return points;
            }else{
                return normalizeLine(startX, startY, endX, endY);
            }
        }else{
            return bres.lineNoDiagonal(startX, startY, endX, endY, Pools.get(Point2.class, Point2::new), points);
        }
    }

    /**
     * Tile can host a bridge: free/replaceable + validPlace, or already this bridge.
     * Solid non-replaceable blocks are never hosts (avoids placing into walls).
     */
    public static boolean canHostBridge(Block block, int x, int y, int rotation){
        if(block == null) return false;
        Tile t = world.tile(x, y);
        if(t == null) return false;
        if(t.block() == block) return true;
        if(t.build != null && t.build.block == block) return true;
        // hard obstacle — cannot stand a bridge here
        if(!t.block().alwaysReplace && (block == null || !block.canReplace(t.block()))) return false;
        if(t.floor().isDeep() && !block.placeableLiquid) return false;
        return Build.validPlace(block, player.team(), x, y, rotation, false);
    }

    /**
     * Dense tile path around obstacles (same rules as conveyor A*, always on).
     * Fills {@code out} with owned Point2 copies. Returns false if unreachable.
     */
    public static boolean tilePathAround(int startX, int startY, int endX, int endY, Block block, Seq<Point2> out){
        out.clear();
        if(startX == endX && startY == endY){
            out.add(new Point2(startX, startY));
            return true;
        }
        // temporarily ensure heuristics see the right block
        Block prev = control.input != null ? control.input.block : null;
        if(control.input != null) control.input.block = block;
        try{
            Pools.freeAll(points);
            points.clear();
            if(!astar(startX, startY, endX, endY)) return false;
            for(Point2 p : points){
                out.add(new Point2(p.x, p.y));
            }
            return out.size > 0;
        }finally{
            if(control.input != null) control.input.block = prev;
        }
    }

    /**
     * Bridge hop A*: land only on hostable tiles; each step is an orthogonal jump of 1..range.
     * Can span over obstacles between landings. End must be hostable (no wall endpoints).
     */
    public static boolean findBridgePath(int startX, int startY, int endX, int endY, int range, Block block, int rotation, Seq<Point2> out){
        out.clear();
        if(block == null || range < 1) return false;
        if(startX == endX && startY == endY){
            out.add(new Point2(startX, startY));
            return true;
        }

        // wide margin so we can walk around large bases
        int manh = Math.abs(endX - startX) + Math.abs(endY - startY);
        int margin = Math.max(48, manh + range * 6);
        int minX = Math.min(startX, endX) - margin;
        int maxX = Math.max(startX, endX) + margin;
        int minY = Math.min(startY, endY) - margin;
        int maxY = Math.max(startY, endY) + margin;

        costs.clear();
        closed.clear();
        parents.clear();

        int startPos = Point2.pack(startX, startY);
        int endPos = Point2.pack(endX, endY);
        Tile endTile = world.tile(endX, endY);
        if(endTile == null) return false;
        // refuse solid wall as endpoint (that's "crashing into" the obstacle)
        boolean endOk = canHostBridge(block, endX, endY, rotation)
            || endTile.block().alwaysReplace
            || endTile.block() == block
            || block.canReplace(endTile.block());
        if(!endOk) return false;

        int nodeLimit = 20000;
        int totalNodes = 0;

        PQueue<Tile> queue = new PQueue<>(64, (Tile a, Tile b) -> Float.compare(
            costs.get(a.pos(), 0f) + bridgeHeuristic(a.x, a.y, endX, endY, range),
            costs.get(b.pos(), 0f) + bridgeHeuristic(b.x, b.y, endX, endY, range)
        ));

        Tile startTile = world.tile(startX, startY);
        if(startTile == null) return false;
        queue.add(startTile);
        costs.put(startPos, 0f);

        boolean found = false;
        while(!queue.empty() && totalNodes++ < nodeLimit){
            Tile cur = queue.poll();
            if(cur == null) break;
            int cpos = cur.pos();
            if(!closed.add(cpos)) continue;
            if(cpos == endPos){
                found = true;
                break;
            }

            float base = costs.get(cpos, 0f);
            for(int d = 0; d < 4; d++){
                int dx = Geometry.d4x(d), dy = Geometry.d4y(d);
                for(int dist = 1; dist <= range; dist++){
                    int nx = cur.x + dx * dist;
                    int ny = cur.y + dy * dist;
                    if(nx < minX || nx > maxX || ny < minY || ny > maxY) break;
                    Tile child = world.tile(nx, ny);
                    if(child == null) break;

                    boolean isStart = nx == startX && ny == startY;
                    boolean isEnd = nx == endX && ny == endY;
                    // every landing except the click-start must be free / hostable
                    if(!isStart){
                        if(isEnd){
                            if(!endOk) continue;
                        }else if(!canHostBridge(block, nx, ny, rotation)){
                            continue;
                        }
                    }
                    if(closed.contains(child.pos())) continue;

                    float step = 1f + (range - dist) * 0.02f;
                    float newCost = base + step;
                    float old = costs.get(child.pos(), Float.POSITIVE_INFINITY);
                    if(newCost < old){
                        costs.put(child.pos(), newCost);
                        parents.put(child.pos(), cpos);
                        queue.add(child);
                    }
                }
            }
        }

        if(!found) return false;

        int curPos = endPos;
        int guard = 0;
        while(guard++ < nodeLimit){
            out.add(new Point2(Point2.x(curPos), Point2.y(curPos)));
            if(curPos == startPos) break;
            int p = parents.get(curPos, -1);
            if(p == -1){
                out.clear();
                return false;
            }
            curPos = p;
        }
        out.reverse();
        return out.size > 0;
    }

    private static float bridgeHeuristic(int x, int y, int endX, int endY, int range){
        int manh = Math.abs(x - endX) + Math.abs(y - endY);
        return manh / (float)Math.max(1, range);
    }

    /**
     * Shift-mode: route that never places intermediate nodes on solids.
     * 1) hop-A* (span over obstacles, land on free tiles)
     * 2) else dense tile A* around obstacles + max-range sampling
     * Never falls back to a straight L through walls.
     */
    public static boolean buildBridgePath(int startX, int startY, int endX, int endY, int range, Block block, int rotation, Seq<Point2> out){
        out.clear();
        if(block == null) return false;

        // 1) dense walk-around first — never steps on solids (best "обход препятствий")
        Seq<Point2> dense = new Seq<>();
        if(tilePathAround(startX, startY, endX, endY, block, dense)){
            sampleBridgeNodes(dense, range, out);
            sanitizeBridgeNodes(out, block, rotation, range, startX, startY, endX, endY);
            if(out.size > 0) return true;
        }

        // 2) hop A* — can span over obstacles between free landings
        if(findBridgePath(startX, startY, endX, endY, range, block, rotation, out)){
            sanitizeBridgeNodes(out, block, rotation, range, startX, startY, endX, endY);
            return out.size > 0;
        }

        // 3) last resort: only start (+ end if hostable and in orthogonal range)
        out.clear();
        out.add(new Point2(startX, startY));
        if((startX == endX || startY == endY)
                && Math.abs(startX - endX) + Math.abs(startY - endY) <= range
                && canHostBridge(block, endX, endY, rotation)){
            out.add(new Point2(endX, endY));
        }
        return out.size > 0;
    }

    /** Drop intermediate nodes on solids; re-link with free corners / hostable hops only. */
    public static void sanitizeBridgeNodes(Seq<Point2> nodes, Block block, int rotation, int range, int startX, int startY, int endX, int endY){
        if(nodes.size <= 1) return;
        int r = Math.max(1, range);
        Seq<Point2> cleaned = new Seq<>();
        for(int i = 0; i < nodes.size; i++){
            Point2 p = nodes.get(i);
            boolean isStart = p.x == startX && p.y == startY;
            if(isStart || canHostBridge(block, p.x, p.y, rotation)){
                if(cleaned.isEmpty() || cleaned.peek().x != p.x || cleaned.peek().y != p.y){
                    cleaned.add(new Point2(p.x, p.y));
                }
            }
        }
        Seq<Point2> linked = new Seq<>();
        if(cleaned.size > 0) linked.add(cleaned.first());
        for(int i = 1; i < cleaned.size; i++){
            Point2 a = linked.peek();
            Point2 b = cleaned.get(i);
            if(a.x == b.x || a.y == b.y){
                appendHostableSegment(linked, a.x, a.y, b.x, b.y, block, rotation, r);
            }else{
                Point2 c1 = new Point2(b.x, a.y);
                Point2 c2 = new Point2(a.x, b.y);
                boolean h1 = canHostBridge(block, c1.x, c1.y, rotation);
                boolean h2 = canHostBridge(block, c2.x, c2.y, rotation);
                Point2 corner = h1 ? c1 : (h2 ? c2 : null);
                if(corner != null){
                    appendHostableSegment(linked, a.x, a.y, corner.x, corner.y, block, rotation, r);
                    Point2 tip = linked.peek();
                    appendHostableSegment(linked, tip.x, tip.y, b.x, b.y, block, rotation, r);
                }
                // else skip unreachable b
            }
        }
        nodes.clear();
        nodes.addAll(linked);
    }

    /** Append free landings from (x1,y1) exclusive to (x2,y2) inclusive, stepping ≤ range, only hostable. */
    private static void appendHostableSegment(Seq<Point2> out, int x1, int y1, int x2, int y2, Block block, int rotation, int range){
        if(x1 == x2 && y1 == y2) return;
        if(x1 != x2 && y1 != y2) return;
        int dx = Integer.signum(x2 - x1);
        int dy = Integer.signum(y2 - y1);
        int dist = Math.abs(x2 - x1) + Math.abs(y2 - y1);
        int traveled = 0;
        int cx = x1, cy = y1;
        while(traveled < dist){
            int step = Math.min(range, dist - traveled);
            // prefer full step if landing is hostable; else shrink
            boolean placed = false;
            for(int s = step; s >= 1; s--){
                int nx = cx + dx * s, ny = cy + dy * s;
                boolean atEnd = (nx == x2 && ny == y2);
                if(atEnd || canHostBridge(block, nx, ny, rotation)){
                    if(out.peek().x != nx || out.peek().y != ny) out.add(new Point2(nx, ny));
                    cx = nx; cy = ny;
                    traveled += s;
                    placed = true;
                    break;
                }
            }
            if(!placed){
                // blocked along the segment — stop without adding solid tiles
                break;
            }
        }
    }

    /**
     * Place bridge nodes along a dense path at max {@code range} spacing on orthogonal runs.
     */
    public static void sampleBridgeNodes(Seq<Point2> path, int range, Seq<Point2> out){
        out.clear();
        if(path.isEmpty()) return;
        if(path.size == 1 || range < 1){
            Point2 p = path.first();
            out.add(new Point2(p.x, p.y));
            return;
        }

        // collapse to orthogonal segment endpoints (long runs)
        Seq<Point2> corners = new Seq<>();
        corners.add(new Point2(path.first().x, path.first().y));
        for(int i = 1; i < path.size; i++){
            Point2 prev = corners.peek();
            Point2 cur = path.get(i);
            Point2 next = i + 1 < path.size ? path.get(i + 1) : null;
            if(next == null){
                if(prev.x != cur.x || prev.y != cur.y) corners.add(new Point2(cur.x, cur.y));
            }else{
                int dx1 = Integer.signum(cur.x - prev.x), dy1 = Integer.signum(cur.y - prev.y);
                int dx2 = Integer.signum(next.x - cur.x), dy2 = Integer.signum(next.y - cur.y);
                // also treat non-ortho step as corner
                if(dx1 != dx2 || dy1 != dy2 || (cur.x != prev.x && cur.y != prev.y)){
                    if(prev.x != cur.x || prev.y != cur.y) corners.add(new Point2(cur.x, cur.y));
                }
            }
        }

        out.add(new Point2(corners.first().x, corners.first().y));
        for(int i = 1; i < corners.size; i++){
            Point2 a = out.peek();
            Point2 b = corners.get(i);
            appendSegmentNodes(out, a.x, a.y, b.x, b.y, range);
        }
    }

    /** Append max-range spaced nodes from (x1,y1) exclusive toward (x2,y2) inclusive. */
    public static void appendSegmentNodes(Seq<Point2> out, int x1, int y1, int x2, int y2, int range){
        if(x1 == x2 && y1 == y2) return;
        if(x1 != x2 && y1 != y2){
            appendSegmentNodes(out, x1, y1, x2, y1, range);
            Point2 mid = out.peek();
            appendSegmentNodes(out, mid.x, mid.y, x2, y2, range);
            return;
        }
        int dx = Integer.signum(x2 - x1);
        int dy = Integer.signum(y2 - y1);
        int dist = Math.abs(x2 - x1) + Math.abs(y2 - y1);
        int traveled = 0;
        int cx = x1, cy = y1;
        while(traveled + range < dist){
            cx += dx * range;
            cy += dy * range;
            traveled += range;
            out.add(new Point2(cx, cy));
        }
        if(cx != x2 || cy != y2){
            out.add(new Point2(x2, y2));
        }
    }

    /**
     * Alt diagonal: staircase toward cursor, then nodes spaced by range with free corners.
     * Looks diagonal; does not force L-shape.
     */
    public static void diagonalBridgeNodes(int x1, int y1, int x2, int y2, int range, Block block, int rotation, Seq<Point2> out){
        out.clear();
        if(x1 == x2 && y1 == y2){
            out.add(new Point2(x1, y1));
            return;
        }

        Seq<Point2> path = pathfindLine(false, x1, y1, x2, y2);
        if(path.isEmpty()){
            out.add(new Point2(x1, y1));
            return;
        }

        // owned copies — path points are pooled
        Seq<Point2> owned = new Seq<>(path.size);
        for(Point2 p : path) owned.add(new Point2(p.x, p.y));

        // sample every `range` steps along path, then fix non-ortho with free corners
        Seq<Point2> raw = new Seq<>();
        for(int i = 0; i < owned.size; i += Math.max(1, range)){
            Point2 p = owned.get(i);
            raw.add(new Point2(p.x, p.y));
        }
        Point2 last = owned.peek();
        if(raw.isEmpty() || raw.peek().x != last.x || raw.peek().y != last.y){
            raw.add(new Point2(last.x, last.y));
        }

        out.add(raw.first());
        for(int i = 1; i < raw.size; i++){
            Point2 a = out.peek();
            Point2 b = raw.get(i);
            if(a.x == b.x || a.y == b.y){
                // same axis — may still be > range; space it
                appendHostableSegment(out, a.x, a.y, b.x, b.y, block, rotation, range);
                // if end not reached (blocked), still try to add b if hostable
                Point2 tip = out.peek();
                if((tip.x != b.x || tip.y != b.y) && canHostBridge(block, b.x, b.y, rotation)){
                    // only if orthogonal and within range of tip
                    if((tip.x == b.x || tip.y == b.y)
                            && Math.abs(tip.x - b.x) + Math.abs(tip.y - b.y) <= range){
                        out.add(new Point2(b.x, b.y));
                    }
                }
            }else{
                // diagonal pair — pick free corner
                Point2 c1 = new Point2(b.x, a.y);
                Point2 c2 = new Point2(a.x, b.y);
                boolean h1 = canHostBridge(block, c1.x, c1.y, rotation) || (c1.x == x1 && c1.y == y1) || (c1.x == x2 && c1.y == y2);
                boolean h2 = canHostBridge(block, c2.x, c2.y, rotation) || (c2.x == x1 && c2.y == y1) || (c2.x == x2 && c2.y == y2);
                Point2 corner = null;
                if(h1 && h2){
                    // prefer corner closer to line / already on path
                    corner = c1; // default
                }else if(h1) corner = c1;
                else if(h2) corner = c2;

                if(corner != null){
                    appendHostableSegment(out, a.x, a.y, corner.x, corner.y, block, rotation, range);
                    Point2 tip = out.peek();
                    appendHostableSegment(out, tip.x, tip.y, b.x, b.y, block, rotation, range);
                    tip = out.peek();
                    if((tip.x != b.x || tip.y != b.y) && canHostBridge(block, b.x, b.y, rotation)
                            && (tip.x == b.x || tip.y == b.y)
                            && Math.abs(tip.x - b.x) + Math.abs(tip.y - b.y) <= range){
                        out.add(new Point2(b.x, b.y));
                    }
                }else{
                    // no free corner — try to pathfind hop between a and b
                    Seq<Point2> sub = new Seq<>();
                    if(findBridgePath(a.x, a.y, b.x, b.y, range, block, rotation, sub) && sub.size > 1){
                        for(int k = 1; k < sub.size; k++){
                            Point2 s = sub.get(k);
                            if(out.peek().x != s.x || out.peek().y != s.y) out.add(s);
                        }
                    }
                    // else skip b
                }
            }
        }

        if(out.isEmpty()) out.add(new Point2(x1, y1));
    }

    /** Normalize two points into one straight line, no diagonals. */
    public static Seq<Point2> normalizeLine(int startX, int startY, int endX, int endY){
        Pools.freeAll(points);
        points.clear();
        if(Math.abs(startX - endX) > Math.abs(startY - endY)){
            //go width
            for(int i = 0; i <= Math.abs(startX - endX); i++){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX + i * Mathf.sign(endX - startX), startY));
            }
        }else{
            //go height
            for(int i = 0; i <= Math.abs(startY - endY); i++){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX, startY + i * Mathf.sign(endY - startY)));
            }
        }
        return points;
    }

    /** Normalize two points into a rectangle. */
    public static Seq<Point2> normalizeRectangle(int startX, int startY, int endX, int endY, int blockSize){
        Pools.freeAll(points);
        points.clear();

        int minX = Math.min(startX, endX), minY = Math.min(startY, endY), maxX = Math.max(startX, endX), maxY = Math.max(startY, endY);

        for(int y = 0; y <= maxY - minY; y += blockSize){
            for(int x = 0; x <= maxX - minX; x += blockSize){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX + x * Mathf.sign(endX - startX), startY + y * Mathf.sign(endY - startY)));
            }
        }

        return points;
    }

    public static Seq<Point2> upgradeLine(int startX, int startY, int endX, int endY){
        closed.clear();
        Pools.freeAll(points);
        points.clear();
        var build = world.build(startX, startY);
        points.add(Pools.obtain(Point2.class, Point2::new).set(startX, startY));
        while(build instanceof ChainedBuilding chain && (build.tile.x != endX || build.tile.y != endY) && closed.add(build.id)){
            if(chain.next() == null) return pathfindLine(true, startX, startY, endX, endY);
            build = chain.next();
            points.add(Pools.obtain(Point2.class, Point2::new).set(build.tile.x, build.tile.y));
        }
        return points;
    }

    /** Calculates optimal node placement for nodes with spacing. Used for bridges and power nodes. */
    public static void calculateNodes(Seq<Point2> points, Block block, int rotation, Boolf2<Point2, Point2> overlapper){
        var base = tmpPoints2;
        var result = tmpPoints.clear();

        base.selectFrom(points, p -> p == points.first() || p == points.peek() || Build.validPlace(block, player.team(), p.x, p.y, rotation));
        boolean addedLast = false;

        outer:
        for(int i = 0; i < base.size; ){
            var point = base.get(i);
            result.add(point);
            if(i == base.size - 1) addedLast = true;

            //find the furthest node that overlaps this one
            for(int j = base.size - 1; j > i; j--){
                var other = base.get(j);
                boolean over = overlapper.get(point, other);

                if(over){
                    //add node to list and start searching for node that overlaps the next one
                    i = j;
                    continue outer;
                }
            }

            //if it got here, that means nothing was found. try to proceed to the next node anyway
            i++;
        }

        if(!addedLast && !base.isEmpty()) result.add(base.peek());

        points.clear();
        points.addAll(result);
    }

    public static boolean isSidePlace(Seq<BuildPlan> plans){
        return plans.size > 1 && Mathf.mod(Tile.relativeTo(plans.first().x, plans.first().y, plans.get(1).x, plans.get(1).y) - plans.first().rotation, 2) == 1;
    }

    public static void calculateBridges(Seq<BuildPlan> plans, ItemBridge bridge){
        calculateBridges(plans, bridge, false, t -> false);
    }

    private static void calculateBridges(Seq<BuildPlan> plans, BridgePlacer bridge, boolean hasJunction, Boolf<Block> avoid){
        //common checks
        if(isSidePlace(plans) || plans.size == 0) return;

        //check for orthogonal placement + unlocked state
        if(!(plans.first().x == plans.peek().x || plans.first().y == plans.peek().y) || !bridge.unlockedNow()){
            return;
        }

        smartCalculateBridges(plans, bridge, hasJunction, avoid);
    }

    private static void smartCalculateBridges(Seq<BuildPlan> plans, BridgePlacer bridge, boolean hasJunction, Boolf<Block> avoid){
        Boolf<BuildPlan> placeable = plan ->
        (plan.placeable(player.team()) || (plan.tile() != null && plan.tile().block() == plan.block && plan.tile().interactable(player.team()))) &&  //don't count the same block as inaccessible
        !(plan != plans.first() && plan.build() != null && plan.build().rotation != plan.rotation && avoid.get(plan.tile().block()));

        var result = plans1.clear();

        // Use DP for smarter bridge placement
        final int conveyorCost = 3;
        final int junctionCost = 30;
        final int bridgeCost = 200;
        final int bridgeOverEmptyPenalty = 5;
        final int infCost = Integer.MAX_VALUE / 2; // Avoid overflow when adding

        int N = plans.size;
        var dp = tmpInts.setSize(2 * N);
        var parent = tmpInts2.setSize(2 * N);
        Arrays.fill(dp, 0, 2 * N, infCost);
        Arrays.fill(parent, 0, 2 * N, -1);
        dp[0] = 0;
        dp[N] = bridgeCost;

        for(int i = 1; i < N; i++){
            var cur = plans.get(i);
            boolean canPlace = placeable.get(cur);
            boolean needJunction = hasJunction && (cur.tile() == null || avoid.get(cur.tile().block()));

            if(!canPlace && !needJunction){
                continue;
            }

            if(canPlace){
                dp[i] = dp[i - 1] + conveyorCost;
            }else{
                dp[i] = dp[i - 1] + junctionCost;
            }
            parent[i] = i - 1;

            if(dp[i] < infCost && canPlace){
                dp[N + i] = dp[i] + bridgeCost;
                parent[N + i] = i - 1;
            }

            // Consider bridges from all previous positions
            if(i >= 2 && canPlace){
                int emptyPenalty = 0;
                if(placeable.get(plans.get(i - 1))){
                    emptyPenalty += bridgeOverEmptyPenalty;
                }

                for(int j = i - 2; j >= 0; j--){
                    var other = plans.get(j);
                    if(!bridge.positionsValid(cur.x, cur.y, other.x, other.y)){
                        break; // No need to check further back if this one is out of range
                    }

                    if(placeable.get(other) && !existingHopBetween(other, cur)){
                        int cost = dp[N + j] + bridgeCost + emptyPenalty;
                        if(dp[N + i] > cost){
                            dp[N + i] = cost;
                            parent[N + i] = j;
                        }
                        emptyPenalty += bridgeOverEmptyPenalty;
                    }
                }
            }

            if(dp[N + i] < dp[i]){
                dp[i] = dp[N + i];
                parent[i] = parent[N + i];
            }

            if(canPlace && dp[i] >= infCost){
                // Unable to connect, restart a new segment
                dp[i] = 0;
                dp[N + i] = bridgeCost;
            }
        }

        // Backtrack to assign bridges
        int bridgeMode = 0;
        for(int i = N - 1; i >= 0; ){
            var cur = plans.get(i);
            int p = parent[bridgeMode + i];

            if(p == -1 || p == i - 1){
                // No connection, connected by conveyor, or junction, no bridge needed
                result.add(cur);
                bridgeMode = 0;
                i--;
            }else{
                // Connected by bridge, assign it
                var other = plans.get(p);
                bridge.applyToPlans(cur, other);
                result.add(cur);
                i = p;
                bridgeMode = N;
            }
        }

        result.reverse();
        plans.set(result);
    }

    /**
     * ItemBridge bridging over gaps in a line (used by Conveyor/Conduit in this client).
     * Signature must stay: (Seq, ItemBridge, boolean, Boolf) — see Conveyor.handlePlacementLine.
     */
    public static void calculateBridges(Seq<BuildPlan> plans, ItemBridge bridge, boolean hasJunction, Boolf<Block> avoid){
        calculateBridges(plans, new ItemBridgePlacer(bridge), hasJunction, avoid);
    }

    public static void calculateBridges(Seq<BuildPlan> plans, DirectionBridge bridge, boolean hasJunction, Boolf<Block> avoid){
        calculateBridges(plans, new DirectionBridgePlacer(bridge), hasJunction, avoid);
    }

    private static float tileHeuristic(Tile tile, Tile other){
        Block block = control.input.block;

        if(!Build.validPlace(block, player.team(), other.x, other.y, -1)){ //-1 to allow placing right-facing conv on right-facing conv
            return 20;
            //why is this 20? I forgot how A* works but isn't that a bit low? Pathfinder uses 6000 right?
            //the planner seems to work fine anyway
        }else{
            if(parents.containsKey(tile.pos())){
                Tile prev = world.tile(parents.get(tile.pos(), 0));
                if(tile.relativeTo(prev) != other.relativeTo(tile)){
                    return 8;
                }
            }
        }
        return 1;
    }

    private static float distanceHeuristic(int x1, int y1, int x2, int y2){
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private static boolean validNode(Tile tile, Tile other){
        Block block = control.input.block;
        if(block != null && block.canReplace(other.block())){
            return true;
        }else{
            return other.block().alwaysReplace;
        }
    }

    private static boolean astar(int startX, int startY, int endX, int endY){
        Tile start = world.tile(startX, startY);
        Tile end = world.tile(endX, endY);
        if(start == end || start == null || end == null) return false;

        costs.clear();
        closed.clear();
        parents.clear();

        int nodeLimit = 10000;
        int totalNodes = 0;

        PQueue<Tile> queue = new PQueue<>(10, (a, b) -> Float.compare(costs.get(a.pos(), 0f) + distanceHeuristic(a.x, a.y, end.x, end.y), costs.get(b.pos(), 0f) + distanceHeuristic(b.x, b.y, end.x, end.y)));
        queue.add(start);
        boolean found = false;
        while(!queue.empty() && totalNodes++ < nodeLimit){
            Tile next = queue.poll();
            float baseCost = costs.get(next.pos(), 0f);
            if(next == end){
                found = true;
                break;
            }
            closed.add(Point2.pack(next.x, next.y));
            for(Point2 point : Geometry.d4){
                int newx = next.x + point.x, newy = next.y + point.y;
                Tile child = world.tile(newx, newy);
                if(child != null && validNode(next, child)){
                    if(closed.add(child.pos())){
                        parents.put(child.pos(), next.pos());
                        costs.put(child.pos(), tileHeuristic(next, child) + baseCost);
                        queue.add(child);
                    }
                }
            }
        }

        if(!found) return false;
        int total = 0;

        points.add(Pools.obtain(Point2.class, Point2::new).set(endX, endY));

        Tile current = end;
        while(current != start && total++ < nodeLimit){
            if(current == null) return false;
            int newPos = parents.get(current.pos(), -1);

            if(newPos == -1) return false;

            points.add(Pools.obtain(Point2.class, Point2::new).set(Point2.x(newPos), Point2.y(newPos)));
            current = world.tile(newPos);
        }

        points.reverse();

        return true;
    }

    /**
     * Normalizes a placement area and returns the result, ready to be used for drawing a rectangle.
     * Returned x2 and y2 will <i>always</i> be greater than x and y.
     * @param block block that will be drawn
     * @param startx starting X coordinate
     * @param starty starting Y coordinate
     * @param endx ending X coordinate
     * @param endy ending Y coordinate
     * @param snap whether to snap to a line
     * @param maxLength maximum length of area
     */
    public static NormalizeDrawResult normalizeDrawArea(Block block, int startx, int starty, int endx, int endy, boolean snap, int maxLength, float scaling){
        normalizeArea(startx, starty, endx, endy, 0, snap, maxLength);

        float offset = block.offset;

        drawResult.x = result.x * tilesize;
        drawResult.y = result.y * tilesize;
        drawResult.x2 = result.x2 * tilesize;
        drawResult.y2 = result.y2 * tilesize;

        drawResult.x -= block.size * scaling * tilesize / 2;
        drawResult.x2 += block.size * scaling * tilesize / 2;


        drawResult.y -= block.size * scaling * tilesize / 2;
        drawResult.y2 += block.size * scaling * tilesize / 2;

        drawResult.x += offset;
        drawResult.y += offset;
        drawResult.x2 += offset;
        drawResult.y2 += offset;

        return drawResult;
    }

    /**
     * Normalizes a placement area and returns the result.
     * Returned x2 and y2 will <i>always</i> be greater than x and y.
     * @param tilex starting X coordinate
     * @param tiley starting Y coordinate
     * @param endx ending X coordinate
     * @param endy ending Y coordinate
     * @param snap whether to snap to a line
     * @param rotation placement rotation
     * @param maxLength maximum length of area
     */
    public static NormalizeResult normalizeArea(int tilex, int tiley, int endx, int endy, int rotation, boolean snap, int maxLength){
        if(snap){
            if(Math.abs(tilex - endx) > Math.abs(tiley - endy)){
                endy = tiley;
            }else{
                endx = tilex;
            }
        }

        if(maxLength > 0){
            if(Math.abs(endx - tilex) > maxLength){
                endx = Mathf.sign(endx - tilex) * maxLength + tilex;
            }

            if(Math.abs(endy - tiley) > maxLength){
                endy = Mathf.sign(endy - tiley) * maxLength + tiley;
            }
        }

        int dx = endx - tilex, dy = endy - tiley;

        if(Math.abs(dx) > Math.abs(dy)){
            if(dx >= 0){
                rotation = 0;
            }else{
                rotation = 2;
            }
        }else if(Math.abs(dx) < Math.abs(dy)){
            if(dy >= 0){
                rotation = 1;
            }else{
                rotation = 3;
            }
        }

        if(endx < tilex){
            int t = endx;
            endx = tilex;
            tilex = t;
        }
        if(endy < tiley){
            int t = endy;
            endy = tiley;
            tiley = t;
        }

        result.x2 = endx;
        result.y2 = endy;
        result.x = tilex;
        result.y = tiley;
        result.rotation = rotation;

        return result;
    }

    public static class NormalizeDrawResult{
        public float x, y, x2, y2;
    }

    public static class NormalizeResult{
        public int x, y, x2, y2, rotation;
    }
}
