package mindustry.client.morj.drill;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/**
 * Places drill plans on the player's unit. Bridge drills follow the AutoDrill grid,
 * larger drills are packed by ore covered, and beam drills get ducts and beam nodes.
 * Plans stay local until a unit builds them.
 */
public final class DrillLayout{
    public enum Direction{
        right(1, 0, 0), up(0, 1, 1), left(-1, 0, 2), down(0, -1, 3);

        public final int x, y, r;

        Direction(int x, int y, int r){
            this.x = x;
            this.y = y;
            this.r = r;
        }

        public int primary(int px, int py){
            return px * x + py * y;
        }

        public int secondary(int px, int py){
            return px * y + py * x;
        }

        public Direction opposite(){
            return switch(this){
                case right -> left;
                case up -> down;
                case left -> right;
                case down -> up;
            };
        }
    }

    private DrillLayout(){}

    /** @return plans queued, 0 if nothing fit, -1 if there is no unit */
    public static int fill(Tile tile, Block block, Direction direction){
        if(tile == null || block == null || player == null || player.unit() == null) return -1;
        Seq<BuildPlan> plans = new Seq<>();
        if(block instanceof BeamDrill beam){
            if(direction == null) direction = Direction.right;
            wall(tile, beam, direction, plans);
        }else if(block instanceof Drill drill){
            if(drill.size == 2){
                if(direction == null) direction = Direction.right;
                bridge(tile, drill, direction, plans);
            }else{
                area(tile, drill, plans);
            }
        }
        if(plans.isEmpty()) return 0;
        Unit unit = player.unit();
        int added = 0;
        for(BuildPlan plan : plans){
            boolean exists = false;
            for(BuildPlan have : unit.plans){
                if(!have.breaking && have.x == plan.x && have.y == plan.y && have.block == plan.block){
                    exists = true;
                    break;
                }
            }
            if(exists) continue;
            unit.addBuild(plan);
            added++;
        }
        return added;
    }

    private static int cap(){
        return Mathf.clamp(Core.settings.getInt("autodrill-maxtiles", 200), 25, 500);
    }

    private static int minOres(Drill drill){
        if(drill == Blocks.pneumaticDrill) return 2;
        if(drill == Blocks.laserDrill) return 5;
        if(drill.size >= 4) return 9;
        return 1;
    }

    private static void bridge(Tile source, Drill drill, Direction direction, Seq<BuildPlan> plans){
        Team team = player.team();
        Item want = source.drop();
        if(want == null) return;
        int max = drill == Blocks.pneumaticDrill ? Math.max(25, (int)(cap() * 0.75f)) : cap();
        Seq<Tile> tiles = connected(source, max, false, direction);
        expand(tiles, drill.size / 2);

        Seq<Tile> bridges = new Seq<>();
        Seq<Tile> drills = new Seq<>();
        for(Tile t : tiles){
            if(isBridgeTile(t)) bridges.add(t);
            if(isDrillTile(t)) drills.add(t);
        }

        int need = minOres(drill);
        drills.retainAll(t -> {
            ObjectIntMap.Entry<Item> ore = countOre(t, drill);
            if(ore == null || ore.key != want || ore.value < need) return false;
            if(!canPlace(drill, team, t.x, t.y, 0)) return false;
            boolean link = false;
            for(Point2 edge : Edges.getEdges(drill.size)){
                Tile n = world.tile(t.x + edge.x, t.y + edge.y);
                if(n == null || !isBridgeTile(n)) continue;
                if(bridges.contains(n) || canPlace(Blocks.itemBridge, team, n.x, n.y, 0)){
                    if(!bridges.contains(n)) bridges.add(n);
                    link = true;
                }
            }
            return link;
        });

        Tile outer = bridges.max(t -> direction.x == 0 ? t.y * direction.y : t.x * direction.x);
        if(outer == null) return;
        Tile outlet = outer.nearby(direction.x * 3, direction.y * 3);
        if(outlet == null) return;
        bridges.add(outlet);
        bridges.sort(t -> t.dst2(outlet.worldx(), outlet.worldy()));

        for(Tile t : drills){
            accept(plans, team, new BuildPlan(t.x, t.y, 0, drill));
        }

        Seq<BuildPlan> links = new Seq<>();
        for(Tile t : bridges){
            Tile partner = partner(bridges, t);
            if(partner == null) partner = existingBridge(t);
            if(t == outlet){
                boolean incoming = false;
                for(Tile o : bridges){
                    if(o != outlet && Math.abs(o.x - outlet.x) + Math.abs(o.y - outlet.y) == 3){
                        incoming = true;
                        break;
                    }
                }
                if(!incoming && existingBridge(outlet) == null) continue;
                accept(links, team, new BuildPlan(t.x, t.y, 0, Blocks.itemBridge, new Point2()));
            }else if(partner != null){
                accept(links, team, new BuildPlan(t.x, t.y, 0, Blocks.itemBridge, new Point2(partner.x - t.x, partner.y - t.y)));
            }
        }
        pruneBridges(links);
        for(BuildPlan link : links){
            if(!overlaps(link.block, link.x, link.y, plans)) plans.add(link);
        }
    }

    private static Tile partner(Seq<Tile> bridges, Tile tile){
        for(Tile t : bridges){
            if(t != tile && Math.abs(t.x - tile.x) + Math.abs(t.y - tile.y) == 3) return t;
        }
        return null;
    }

    private static Tile existingBridge(Tile tile){
        if(tile == null) return null;
        int[] off = {3, 0, -3, 0, 0, 3, 0, -3};
        for(int i = 0; i < off.length; i += 2){
            Tile t = world.tile(tile.x + off[i], tile.y + off[i + 1]);
            if(t != null && t.block() == Blocks.itemBridge && t.build != null) return t;
        }
        return null;
    }

    private static void pruneBridges(Seq<BuildPlan> links){
        boolean changed = true;
        while(changed){
            changed = false;
            for(int i = links.size - 1; i >= 0; i--){
                BuildPlan plan = links.get(i);
                Point2 cfg = plan.config instanceof Point2 p ? p : new Point2();
                if(cfg.x == 0 && cfg.y == 0){
                    boolean hit = false;
                    for(BuildPlan other : links){
                        if(other == plan || !(other.config instanceof Point2 o)) continue;
                        if(other.x + o.x == plan.x && other.y + o.y == plan.y){
                            hit = true;
                            break;
                        }
                    }
                    if(!hit && existingBridge(world.tile(plan.x, plan.y)) == null){
                        links.remove(i);
                        changed = true;
                    }
                }else{
                    int tx = plan.x + cfg.x, ty = plan.y + cfg.y;
                    boolean hit = false;
                    for(BuildPlan other : links){
                        if(other.x == tx && other.y == ty){
                            hit = true;
                            break;
                        }
                    }
                    Tile target = world.tile(tx, ty);
                    if(!hit && (target == null || target.block() != Blocks.itemBridge || target.build == null)){
                        links.remove(i);
                        changed = true;
                    }
                }
            }
        }
    }

    private static boolean isDrillTile(Tile tile){
        int x = tile.x, y = tile.y;
        return switch(Math.floorMod(x, 6)){
            case 0, 2 -> Math.floorMod(y - 1, 6) == 0;
            case 1 -> { int m = Math.floorMod(y - 3, 6); yield m == 0 || m == 2; }
            case 3, 5 -> Math.floorMod(y - 4, 6) == 0;
            case 4 -> { int m = Math.floorMod(y, 6); yield m == 0 || m == 2; }
            default -> false;
        };
    }

    private static boolean isBridgeTile(Tile tile){
        return tile.x % 3 == 0 && tile.y % 3 == 0;
    }

    private static void area(Tile source, Drill drill, Seq<BuildPlan> plans){
        Team team = player.team();
        Item want = source.drop();
        if(want == null) return;
        int max = Math.max(25, cap() / (drill.size >= 3 ? 2 : 1));
        Seq<Tile> tiles = connected(source, max, false, null);
        expand(tiles, drill.size / 2);
        int need = minOres(drill);
        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> ore = countOre(t, drill);
            return ore != null && ore.key == want && ore.value >= need && canPlace(drill, team, t.x, t.y, 0);
        });
        tiles.sort(t -> {
            ObjectIntMap.Entry<Item> ore = countOre(t, drill);
            return ore == null ? 0 : -ore.value;
        });
        Seq<Tile> chosen = new Seq<>();
        for(Tile t : tiles){
            BuildPlan plan = new BuildPlan(t.x, t.y, 0, drill);
            if(accept(plans, team, plan)) chosen.add(t);
        }
        if(Core.settings.getBool("autodrill-support", true)){
            support(chosen, drill, team, plans);
        }
    }

    private static void support(Seq<Tile> chosen, Drill drill, Team team, Seq<BuildPlan> plans){
        boolean liquid = false, power = false;
        if(drill.consumers != null){
            for(var c : drill.consumers){
                if(c instanceof mindustry.world.consumers.ConsumeLiquid || c instanceof mindustry.world.consumers.ConsumeLiquids) liquid = true;
                if(c instanceof mindustry.world.consumers.ConsumePower) power = true;
            }
        }
        Block node = Blocks.powerNode.environmentBuildable() ? Blocks.powerNode : Blocks.beamNode;
        for(Tile t : chosen){
            if(liquid && Blocks.waterExtractor.environmentBuildable()){
                placeNear(t, drill.size, Blocks.waterExtractor, team, plans);
            }
            if(power && node.environmentBuildable()){
                placeNear(t, drill.size, node, team, plans);
            }
        }
    }

    private static void placeNear(Tile tile, int size, Block block, Team team, Seq<BuildPlan> plans){
        for(Tile n : around(tile.x, tile.y, size, block.size)){
            if(accept(plans, team, new BuildPlan(n.x, n.y, 0, block))) return;
        }
    }

    private static void wall(Tile source, BeamDrill drill, Direction direction, Seq<BuildPlan> plans){
        Team team = player.team();
        Item want = source.wallDrop();
        if(want == null || direction == null) return;
        Seq<Tile> ore = wallTiles(source, direction, want);
        Direction back = direction.opposite();
        Point2 drillOff = drillOffset(direction, drill);
        Point2 ductOff = ductOffset(back, drill);
        IntSet used = new IntSet();
        Seq<Tile> bores = new Seq<>();

        for(Tile oreTile : ore){
            for(int i = 0; i < drill.range; i++){
                Tile bore = oreTile.nearby((i + 1) * -direction.x + drillOff.x, (i + 1) * -direction.y + drillOff.y);
                if(bore == null) continue;
                BuildPlan plan = new BuildPlan(bore.x, bore.y, direction.r, drill);
                if(!canPlace(drill, team, bore.x, bore.y, direction.r) || overlaps(drill, bore.x, bore.y, plans)) continue;
                int sa = direction.secondary(bore.x, bore.y);
                boolean busy = false;
                for(int j = -(drill.size - 1) / 2; j <= drill.size / 2; j++){
                    if(used.contains(sa + j)) busy = true;
                }
                if(busy) continue;
                for(int j = -(drill.size - 1) / 2; j <= drill.size / 2; j++) used.add(sa + j);
                bores.add(bore);
                plans.add(plan);
                break;
            }
        }
        if(bores.isEmpty()) return;

        Seq<Tile> ducts = new Seq<>();
        for(Tile bore : bores){
            for(int i = -(drill.size - 1) / 2; i <= drill.size / 2; i++){
                Tile duct = bore.nearby(-ductOff.x + back.x + i * Math.abs(direction.y), -ductOff.y + back.y + i * Math.abs(direction.x));
                if(duct != null) ducts.add(duct);
            }
        }
        if(ducts.isEmpty()) return;

        Tile end = null;
        int best = Integer.MIN_VALUE;
        for(Tile duct : ducts){
            boolean owned = false;
            for(Tile bore : bores){
                if(direction.secondary(bore.x, bore.y) == direction.secondary(duct.x, duct.y)){
                    owned = true;
                    break;
                }
            }
            if(owned) continue;
            int axis = -direction.primary(duct.x, duct.y);
            if(end == null || axis > best){
                end = duct;
                best = axis;
            }
        }
        if(end == null) return;
        final Tile outlet = end;

        Seq<Tile> line = new Seq<>();
        line.add(outlet);
        ducts.sort(t -> t.dst2(outlet));
        for(Tile duct : ducts){
            if(line.contains(duct)) continue;
            Tile closest = line.min(t -> t.dst2(duct));
            if(closest == null) continue;
            int cx = duct.x, cy = duct.y;
            int gx = closest.x, gy = closest.y;
            int guard = 0;
            while((cx != gx || cy != gy) && guard++ < 80){
                Tile step = world.tile(cx, cy);
                if(step != null && !line.contains(step)) line.add(step);
                int pa = direction.primary(cx, cy), sa = direction.secondary(cx, cy);
                int paGoal = direction.primary(gx, gy), saGoal = direction.secondary(gx, gy);
                if((pa < paGoal && sa == saGoal) || pa > paGoal){
                    int s = Math.abs(pa) < Math.abs(paGoal) ? 1 : -1;
                    cx += Math.abs(direction.x) * s;
                    cy += Math.abs(direction.y) * s;
                }else{
                    int s = Math.abs(sa) < Math.abs(saGoal) ? 1 : -1;
                    cx += Math.abs(direction.y) * s;
                    cy += Math.abs(direction.x) * s;
                }
            }
        }

        line.sort(t -> outlet.dst(t));
        Seq<Tile> visited = new Seq<>();
        visited.add(outlet);
        Seq<Tile> work = line.copy();
        int spins = 0;
        while(!work.isEmpty() && spins++ < line.size + 2){
            Tile from = null, to = null;
            for(Tile next : work){
                Tile adj = visited.find(t -> next.relativeTo(t) != -1);
                if(adj != null){
                    from = adj;
                    to = next;
                    break;
                }
            }
            if(to == null) break;
            visited.add(to);
            work.remove(to);
            if(to == outlet){
                accept(plans, team, new BuildPlan(to.x, to.y, back.r, Blocks.duct));
                accept(plans, team, new BuildPlan(to.x + back.x, to.y + back.y, back.r, Blocks.duct));
            }else if(from != null){
                accept(plans, team, new BuildPlan(to.x, to.y, to.relativeTo(from), Blocks.duct));
            }
        }

        Tile outerBore = bores.max(t -> -direction.primary(t.x, t.y));
        if(outerBore == null) return;
        for(Tile bore : bores){
            int nx = Math.abs(direction.x) * outerBore.x + Math.abs(direction.y) * bore.x - ductOff.x + back.x * 2;
            int ny = Math.abs(direction.y) * outerBore.y + Math.abs(direction.x) * bore.y - ductOff.y + back.y * 2;
            Tile node = world.tile(nx, ny);
            int hops = 0;
            while(node != null && hops++ < 8){
                accept(plans, team, new BuildPlan(node.x, node.y, 0, Blocks.beamNode));
                if(node.dst(bore) <= 10f * tilesize) break;
                node = node.nearby(direction.x * 5, direction.y * 5);
            }
        }
    }

    private static Seq<Tile> wallTiles(Tile start, Direction direction, Item want){
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        IntSet seen = new IntSet();
        queue.addLast(start);
        int max = Math.max(10, cap() / 4);
        while(!queue.isEmpty() && tiles.size < max){
            Tile current = queue.removeFirst();
            if(!seen.add(current.pos())) continue;
            if(current.wallDrop() != want) continue;
            for(int x = -2; x <= 2; x++){
                for(int y = -2; y <= 2; y++){
                    if(x == 0 && y == 0) continue;
                    Tile n = current.nearby(x, y);
                    if(n == null || seen.contains(n.pos())) continue;
                    Tile front = n.nearby(-direction.x, -direction.y);
                    if(front != null && !front.solid()) queue.addLast(n);
                }
            }
            tiles.add(current);
        }
        tiles.retainAll(t -> {
            int pa = direction.primary(t.x, t.y);
            int sa = direction.secondary(t.x, t.y);
            for(Tile other : tiles){
                if(other != t && direction.secondary(other.x, other.y) == sa && direction.primary(other.x, other.y) < pa) return false;
            }
            return true;
        });
        tiles.sort(t -> direction.secondary(t.x, t.y));
        return tiles;
    }

    private static Point2 drillOffset(Direction direction, Block block){
        int off = block.size / 2;
        return switch(direction){
            case right, down -> new Point2(-off, 0);
            case up -> new Point2(0, -off);
            case left -> new Point2(-off, -1);
        };
    }

    private static Point2 ductOffset(Direction direction, Block block){
        int low = (block.size - 1) / 2;
        int high = block.size / 2;
        return switch(direction){
            case right -> new Point2(-high, 0);
            case up -> new Point2(0, -high);
            case left, down -> new Point2(low, 0);
        };
    }

    private static Seq<Tile> connected(Tile start, int max, boolean wall, Direction direction){
        Queue<Tile> queue = new Queue<>();
        Seq<Tile> tiles = new Seq<>();
        IntSet seen = new IntSet();
        queue.addLast(start);
        Item want = wall ? start.wallDrop() : start.drop();
        Block probe = Blocks.copperWall.environmentBuildable() ? Blocks.copperWall : Blocks.berylliumWall;
        while(!queue.isEmpty() && tiles.size < max){
            Tile current = queue.removeFirst();
            if(!seen.add(current.pos())) continue;
            if(!Build.validPlace(probe, player.team(), current.x, current.y, 0)) continue;
            Item drop = wall ? current.wallDrop() : current.drop();
            if(drop != want) continue;
            for(int x = -1; x <= 1; x++){
                for(int y = -1; y <= 1; y++){
                    if(x == 0 && y == 0) continue;
                    Tile n = current.nearby(x, y);
                    if(n != null && !seen.contains(n.pos())) queue.addLast(n);
                }
            }
            tiles.add(current);
        }
        tiles.sort(t -> t.pos());
        return tiles;
    }

    private static void expand(Seq<Tile> tiles, int radius){
        if(radius <= 0) return;
        IntSet have = new IntSet();
        for(Tile t : tiles) have.add(t.pos());
        Seq<Tile> extra = new Seq<>();
        for(Tile tile : tiles){
            for(int dx = -radius; dx <= radius; dx++){
                for(int dy = -radius; dy <= radius; dy++){
                    if(dx == 0 && dy == 0) continue;
                    Tile n = tile.nearby(dx, dy);
                    if(n != null && have.add(n.pos())) extra.add(n);
                }
            }
        }
        tiles.addAll(extra);
    }

    private static ObjectIntMap.Entry<Item> countOre(Tile tile, Drill drill){
        ObjectIntMap<Item> ore = new ObjectIntMap<>();
        for(Tile other : tile.getLinkedTilesAs(drill, new Seq<>())){
            if(drill.canMine(other)) ore.increment(drill.getDrop(other), 0, 1);
        }
        Item best = null;
        int count = 0;
        for(Item item : ore.keys()){
            int n = ore.get(item, 0);
            if(best == null || (!item.lowPriority && best.lowPriority) || (item.lowPriority == best.lowPriority && n > count)){
                best = item;
                count = n;
            }
        }
        if(best == null) return null;
        ObjectIntMap.Entry<Item> entry = new ObjectIntMap.Entry<>();
        entry.key = best;
        entry.value = count;
        return entry;
    }

    private static Seq<Tile> around(int x, int y, int size, int other){
        int off1 = (size % 2 == 1 && other % 2 == 0) ? 1 : 0;
        int reach = size + ((other * 2 - 1) / 2);
        Seq<Tile> out = new Seq<>();
        for(Point2 edge : Edges.getEdges(reach)){
            Tile t = world.tile(x - off1 + edge.x, y - off1 + edge.y);
            if(t != null) out.add(t);
        }
        return out;
    }

    private static boolean accept(Seq<BuildPlan> plans, Team team, BuildPlan plan){
        if(plan.block == null) return false;
        if(!canPlace(plan.block, team, plan.x, plan.y, plan.rotation)) return false;
        if(overlaps(plan.block, plan.x, plan.y, plans)) return false;
        plans.add(plan);
        return true;
    }

    private static boolean canPlace(Block block, Team team, int x, int y, int rotation){
        int low = (block.size - 1) / 2;
        int high = block.size / 2;
        if(x - low < 0 || y - low < 0 || x + high >= world.width() || y + high >= world.height()) return false;
        return Build.validPlace(block, team, x, y, rotation);
    }

    private static boolean overlaps(Block block, int x, int y, Seq<BuildPlan> plans){
        Rect next = rect(x, y, block);
        for(BuildPlan plan : plans){
            if(plan.block != null && next.overlaps(rect(plan.x, plan.y, plan.block))) return true;
        }
        return false;
    }

    private static Rect rect(int x, int y, Block block){
        int off = (block.size - 1) / 2;
        return new Rect(x - off, y - off, block.size, block.size);
    }
}
