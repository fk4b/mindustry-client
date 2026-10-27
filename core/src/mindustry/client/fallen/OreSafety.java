package mindustry.client.fallen;

import arc.struct.FloatSeq;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;

import static mindustry.Vars.player;

/**
 * Safe mining the way GL-Client does it ({@code fallen/miners/OreSafety}).
 * Veins are clustered once per map. Each refresh stores enemy turrets as
 * circles. Checks are point-in-circle tests, not a full-map grid and not A*.
 */
public final class OreSafety{
    public static class Cluster{
        public Item item;
        public float x, y;
        public int tiles;
        public boolean threatened;
        public boolean freeTiles;
        int[] sampleX, sampleY;
    }

    /** Tiles of slack outside a turret circle so a unit does not sit on the edge. */
    public static float turretSafeRadius = 3f;
    public static float spawnSafeRadius = 10f;

    private static final float PATH_BLOCKED_FRACTION = 0.1f;
    private static final int MAX_SAMPLES = 28;
    private static final float PATH_STEP = 16f;
    private static final int MAX_PATH_SAMPLES = 64;
    private static final int MAX_CLUSTER_TILES = 64;
    private static final int MAX_CLUSTER_RADIUS = 10;

    static final Seq<Cluster> clusters = new Seq<>();
    /** Enemy turrets and drop zones as x, y, radius triples. */
    private static final FloatSeq dangerTilesData = new FloatSeq();
    private static boolean scanned;
    private static long refreshedAt;

    private OreSafety(){}

    public static void reset(){
        clusters.clear();
        dangerTilesData.clear();
        scanned = false;
        refreshedAt = 0L;
    }

    public static boolean hasDanger(){
        return dangerTilesData.size > 0;
    }

    /** One vein scan per map, then a turret-circle refresh at most once a second. */
    public static void ensureFresh(){
        if(!scanned){
            scan();
            scanned = true;
        }
        if(Time.timeSinceMillis(refreshedAt) >= 1000L){
            refreshThreat(true);
            refreshedAt = Time.millis();
        }
    }

    public static void scan(){
        clusters.clear();
        if(Vars.world == null || Vars.world.width() == 0 || Vars.world.height() == 0) return;

        int w = Vars.world.width(), h = Vars.world.height();
        Item[] dropGrid = new Item[w * h];
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                Tile t = Vars.world.tile(x, y);
                if(t == null) continue;
                Item drop = t.drop();
                if(drop == null) drop = t.wallDrop();
                dropGrid[x + y * w] = drop;
            }
        }

        boolean[] visited = new boolean[w * h];
        IntSeq queue = new IntSeq();

        for(int start = 0; start < w * h; start++){
            if(visited[start] || dropGrid[start] == null) continue;

            Item item = dropGrid[start];
            queue.clear();
            queue.add(start);
            visited[start] = true;

            int startX = start % w, startY = start / w;
            long sumX = 0, sumY = 0;
            int head = 0;

            while(head < queue.size && queue.size < MAX_CLUSTER_TILES){
                int cur = queue.get(head++);
                int cx = cur % w, cy = cur / w;
                sumX += cx;
                sumY += cy;

                for(int dy = -1; dy <= 1; dy++){
                    for(int dx = -1; dx <= 1; dx++){
                        if(dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if(nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        if(Math.abs(nx - startX) > MAX_CLUSTER_RADIUS || Math.abs(ny - startY) > MAX_CLUSTER_RADIUS) continue;

                        int nidx = nx + ny * w;
                        if(visited[nidx] || dropGrid[nidx] != item) continue;

                        visited[nidx] = true;
                        queue.add(nidx);
                        if(queue.size >= MAX_CLUSTER_TILES) break;
                    }
                    if(queue.size >= MAX_CLUSTER_TILES) break;
                }
            }

            while(head < queue.size){
                int cur = queue.get(head++);
                sumX += (cur % w);
                sumY += (cur / w);
            }

            int count = queue.size;
            if(count == 0) continue;

            Cluster c = new Cluster();
            c.item = item;
            c.tiles = count;
            c.x = (sumX / (float)count) * 8f + 4f;
            c.y = (sumY / (float)count) * 8f + 4f;

            int sampleCount = Math.min(MAX_SAMPLES, count);
            int stride = Math.max(1, count / sampleCount);
            c.sampleX = new int[sampleCount];
            c.sampleY = new int[sampleCount];
            for(int i = 0; i < sampleCount; i++){
                int idx = queue.get(i * stride);
                c.sampleX[i] = idx % w;
                c.sampleY[i] = idx / w;
            }
            clusters.add(c);
        }
    }

    public static void refreshThreat(boolean enabled){
        dangerTilesData.clear();
        if(enabled && player != null){
            for(Building b : Groups.build){
                if(b == null || !b.isValid()) continue;
                if(b.team == player.team() || b.team == Team.derelict) continue;
                if(!(b.block instanceof Turret turret)) continue;
                if(!turret.targetAir && !turret.targetGround) continue;
                dangerTilesData.add(b.x, b.y, turret.range + turretSafeRadius * 8f);
            }

            if(Vars.spawner != null && Vars.state != null && Vars.state.rules != null){
                float total = Vars.state.rules.dropZoneRadius + spawnSafeRadius * 8f;
                for(Tile spawnTile : Vars.spawner.getSpawns()){
                    if(spawnTile == null) continue;
                    dangerTilesData.add(spawnTile.worldx(), spawnTile.worldy(), total);
                }
            }
        }

        for(Cluster c : clusters){
            c.threatened = false;
            int free = 0;
            if(c.sampleX == null) continue;
            for(int i = 0; i < c.sampleX.length; i++){
                Tile t = Vars.world.tile(c.sampleX[i], c.sampleY[i]);
                if(t == null) continue;
                if(!t.solid()) free++;
                if(isThreatened(t.worldx(), t.worldy())) c.threatened = true;
            }
            c.freeTiles = free > 0;
        }
    }

    public static boolean isThreatened(float x, float y){
        if(ThreatRadar.isThreatened(x, y)) return true;
        float[] d = dangerTilesData.items;
        for(int i = 0; i < dangerTilesData.size; i += 3){
            float dx = x - d[i], dy = y - d[i + 1];
            float r = d[i + 2];
            if(dx * dx + dy * dy <= r * r) return true;
        }
        return false;
    }

    public static boolean itemAvailable(Item item, boolean safetyEnabled){
        if(player == null || player.team() == null) return false;
        if(player.team().cores().isEmpty()) return false;

        if(!safetyEnabled){
            for(Cluster c : clusters){
                if(c.item == item && c.freeTiles) return true;
            }
            return false;
        }

        for(Building core : player.team().cores()){
            if(isCoreOreSafe(core, item)) return true;
        }
        return false;
    }

    public static boolean isItemSafeForUnit(Unit u, Item item){
        if(u == null) return false;
        Building nearestCore = u.closestCore();
        if(nearestCore == null) return false;
        return isCoreOreSafe(nearestCore, item);
    }

    public static boolean isCoreOreSafe(Building core, Item item){
        Cluster nearest = nearestCluster(core, item);
        return nearest != null && !nearest.threatened && pathSafe(core.x, core.y, nearest.x, nearest.y);
    }

    static Cluster nearestCluster(Building core, Item item){
        Cluster nearest = null;
        float minDst = Float.MAX_VALUE;
        for(Cluster c : clusters){
            if(c.item != item || !c.freeTiles) continue;
            float dst = (c.x - core.x) * (c.x - core.x) + (c.y - core.y) * (c.y - core.y);
            if(dst < minDst){
                minDst = dst;
                nearest = c;
            }
        }
        return nearest;
    }

    /** Core whose nearest vein of this item is safe, and that core is the vein's closest core. */
    public static Building relocationCore(Unit u, Item item){
        if(u == null || u.team == null) return null;
        Building own = u.closestCore();
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(Building core : u.team.cores()){
            if(core == own || isThreatened(core.x, core.y) || !isCoreOreSafe(core, item)) continue;
            Cluster vein = nearestCluster(core, item);
            if(vein == null) continue;
            if(Vars.state == null || Vars.state.teams == null) continue;
            if(Vars.state.teams.closestCore(vein.x, vein.y, u.team) != core) continue;
            if(!pathSafe(u.x, u.y, core.x, core.y)) continue;
            float dst = u.dst2(core);
            if(dst < bestDst){
                bestDst = dst;
                best = core;
            }
        }
        return best;
    }

    public static boolean pathSafe(float fromX, float fromY, float toX, float toY){
        float dist = (float)Math.sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY));
        int steps = Math.min(MAX_PATH_SAMPLES, Math.max(2, (int)(dist / PATH_STEP)));
        int blocked = 0;
        for(int i = 0; i <= steps; i++){
            float t = i / (float)steps;
            if(isThreatened(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)) blocked++;
        }
        return blocked / (float)(steps + 1) <= PATH_BLOCKED_FRACTION;
    }

    public static Building nearestSafeCore(Unit u){
        if(u == null || u.team == null) return null;
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(Building core : u.team.cores()){
            if(core == null || !core.isValid() || isThreatened(core.x, core.y)) continue;
            float dst = u.dst2(core);
            if(dst < bestDst){
                bestDst = dst;
                best = core;
            }
        }
        return best;
    }

    /** A free sample of the nearest safe vein, or null when every vein is under fire. */
    public static Tile nearestSafeTile(Unit unit, Item item){
        if(item == null) return null;
        Building home = unit != null ? unit.closestCore() : (player != null && player.team() != null ? player.team().core() : null);
        float hx = home != null ? home.x : (unit != null ? unit.x : 0f);
        float hy = home != null ? home.y : (unit != null ? unit.y : 0f);

        Cluster best = null;
        float bestD = Float.MAX_VALUE;
        for(Cluster c : clusters){
            if(c.item != item || !c.freeTiles || c.threatened) continue;
            if(home != null && !pathSafe(home.x, home.y, c.x, c.y)) continue;
            if(unit != null && !pathSafe(unit.x, unit.y, c.x, c.y)) continue;
            float d = (c.x - hx) * (c.x - hx) + (c.y - hy) * (c.y - hy);
            if(d < bestD){
                bestD = d;
                best = c;
            }
        }
        if(best == null || best.sampleX == null) return null;

        Tile tile = null;
        float td = Float.MAX_VALUE;
        for(int i = 0; i < best.sampleX.length; i++){
            Tile t = Vars.world.tile(best.sampleX[i], best.sampleY[i]);
            if(t == null || t.solid()) continue;
            if(isThreatened(t.worldx(), t.worldy())) continue;
            float d = (t.worldx() - hx) * (t.worldx() - hx) + (t.worldy() - hy) * (t.worldy() - hy);
            if(d < td){
                td = d;
                tile = t;
            }
        }
        return tile;
    }
}
