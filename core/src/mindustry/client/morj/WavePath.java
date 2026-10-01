package mindustry.client.morj;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.client.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/** Static line from each wave spawn to the core. The moving markers from the spawn-time slider stay as they are. */
public final class WavePath{
    private static final Seq<FloatSeq> lines = new Seq<>();
    private static final IntSeq kinds = new IntSeq();
    private static int count;
    private static float acc = 999f;

    private WavePath(){}

    static{
        Events.on(WorldLoadEvent.class, e -> {
            count = 0;
            acc = 999f;
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool("wavepath", true);
    }

    public static void update(){
        if(!enabled() || state.isMenu() || !state.rules.waves || ClientVars.spawnTime < 0f || spawner.countSpawns() >= 50){
            count = 0;
            acc = 999f;
            return;
        }
        acc += Time.delta;
        if(acc < 90f) return;
        acc = 0f;
        rebuild();
    }

    public static void draw(){
        if(!enabled() || count <= 0) return;
        Draw.z(Layer.overlayUI);
        for(int i = 0; i < count; i++){
            FloatSeq line = lines.get(i);
            if(line.size < 4) continue;
            if(kinds.get(i) == 0) Draw.color(state.rules.waveTeam.color, 0.75f);
            else if(kinds.get(i) == 1) Draw.color(Color.sky, 0.55f);
            else Draw.color(Color.white, 0.35f);
            Lines.stroke(kinds.get(i) == 2 ? 1f : 1.4f);
            Lines.beginLine();
            for(int p = 0; p < line.size; p += 2){
                Lines.linePoint(line.items[p], line.items[p + 1]);
            }
            Lines.endLine();
        }
        Draw.reset();
    }

    private static void rebuild(){
        count = 0;
        Pathfinder.Flowfield ground = pathfinder.getField(state.rules.waveTeam, Pathfinder.costGround, Pathfinder.fieldCore);
        Pathfinder.Flowfield naval = pathfinder.getField(state.rules.waveTeam, Pathfinder.costNaval, Pathfinder.fieldCore);
        int[] walks = {0};
        spawner.eachGroundSpawn((Intc2)(x, y) -> {
            if(walks[0] >= 24) return;
            Tile tile = world.tile(x, y);
            if(tile == null) return;
            walks[0]++;
            trace(tile, ground, 0);
            trace(tile, naval, 1);
        });
        int[] flyers = {0};
        spawner.eachFlyerSpawn((Floatc2)(x, y) -> {
            if(flyers[0] >= 16) return;
            flyers[0]++;
            Building core = Geometry.findClosest(x, y, indexer.getEnemy(state.rules.waveTeam, BlockFlag.core));
            if(core == null) return;
            FloatSeq line = take();
            line.add(x, y, core.x, core.y);
            kinds.add(2);
        });
    }

    private static void trace(Tile start, Pathfinder.Flowfield field, int kind){
        FloatSeq line = take();
        Tile tile = start;
        line.add(tile.worldx(), tile.worldy());
        for(int step = 0; step < 700; step++){
            Tile next = pathfinder.getTargetTile(tile, field);
            if(next == null || next == tile) break;
            line.add(next.worldx(), next.worldy());
            tile = next;
        }
        if(line.size < 4){
            count--;
            return;
        }
        kinds.add(kind);
    }

    private static FloatSeq take(){
        FloatSeq line;
        if(count < lines.size){
            line = lines.get(count);
            line.clear();
        }else{
            line = new FloatSeq();
            lines.add(line);
        }
        if(kinds.size > count) kinds.size = count;
        count++;
        return line;
    }
}
