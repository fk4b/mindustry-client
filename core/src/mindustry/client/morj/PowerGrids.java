package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.power.*;

import static mindustry.Vars.*;

/** Colors each of your power networks on the minimap and full map. Draw only. */
public final class PowerGrids{
    private static final int maxRects = 2800;
    private static final Color[] palette = {
        Color.valueOf("5b8cff"), Color.valueOf("ffb020"), Color.valueOf("3dde8a"),
        Color.valueOf("e85d75"), Color.valueOf("c86bff"), Color.valueOf("4ad4d4"),
        Color.valueOf("f2f25a"), Color.valueOf("ff8ad4"), Color.valueOf("9ad14a"),
        Color.valueOf("ff7a3c"), Color.valueOf("7aa2ff"), Color.valueOf("d4a017")
    };
    private static final Seq<Grid> grids = new Seq<>();
    private static final Seq<Grid> building = new Seq<>();
    private static float acc = 999f;

    private PowerGrids(){}

    public static boolean enabled(){
        return Core.settings.getBool("powergrid", true);
    }

    public static void draw(float scaleFactor, boolean labels){
        if(!enabled() || state.isMenu() || player == null) return;
        acc += Time.delta;
        if(acc >= 45f){
            acc = 0f;
            rebuild();
        }
        if(grids.isEmpty()) return;

        int labelsLeft = labels ? 14 : 0;
        float labelGap = 28f * tilesize;

        for(int i = 0; i < grids.size; i++){
            Grid grid = grids.get(i);
            Draw.color(grid.color, 0.62f);
            float[] data = grid.rects.items;
            for(int p = 0; p < grid.rects.size; p += 3){
                Fill.rect(data[p], data[p + 1], data[p + 2], data[p + 2]);
            }
            Draw.color();
        }

        for(int i = 0; i < grids.size && labelsLeft > 0; i++){
            Grid graph = grids.get(i);
            if(graph.buildings < 8) continue;
            boolean crowded = false;
            for(int j = 0; j < i; j++){
                Grid other = grids.get(j);
                if(other.buildings >= 8 && Mathf.dst(graph.cx, graph.cy, other.cx, other.cy) < labelGap){
                    crowded = true;
                    break;
                }
            }
            if(crowded) continue;
            renderer.minimap.drawLabel(graph.cx, graph.cy, graph.text, Color.white, scaleFactor);
            labelsLeft--;
        }
        Draw.reset();
    }

    private static void rebuild(){
        building.clear();
        if(player == null){
            grids.clear();
            return;
        }
        Team team = player.team();
        Groups.powerGraph.each(updater -> {
            PowerGraph graph = updater.graph();
            if(graph == null || graph.all.isEmpty()) return;
            Building first = graph.all.first();
            if(first == null || first.team != team) return;

            Grid grid = new Grid();
            grid.color = new Color(palette[Math.floorMod(graph.getID(), palette.length)]);
            grid.buildings = graph.all.size;
            int stride = graph.all.size > 500 ? Mathf.ceil(graph.all.size / 500f) : 1;
            float sx = 0f, sy = 0f;
            int counted = 0;
            for(int i = 0; i < graph.all.size; i++){
                Building build = graph.all.get(i);
                if(build == null || !build.isAdded()) continue;
                if(state.rules.fog && !build.isDiscovered(team)) continue;
                sx += build.x;
                sy += build.y;
                counted++;
                if(i % stride != 0) continue;
                float side = build.block.size * tilesize;
                grid.rects.add(build.x, build.y, side);
            }
            if(counted == 0) return;
            grid.cx = sx / counted;
            grid.cy = sy / counted;
            if(!graph.hasPowerBalanceSamples()){
                grid.text = "?";
            }else{
                long perSec = Math.round(graph.getPowerBalance() * 60f);
                String body = UI.formatAmount(Math.abs(perSec));
                grid.text = (perSec > 0 ? "+" : perSec < 0 ? "-" : "") + body;
            }
            building.add(grid);
        });
        building.sort((a, b) -> Integer.compare(b.buildings, a.buildings));

        int used = 0;
        for(int i = 0; i < building.size; i++){
            Grid grid = building.get(i);
            int room = maxRects - used;
            if(room <= 0){
                grid.rects.clear();
                continue;
            }
            int rects = grid.rects.size / 3;
            if(rects > room){
                grid.rects.size = room * 3;
                rects = room;
            }
            used += rects;
        }
        grids.clear();
        grids.addAll(building);
    }

    private static class Grid{
        Color color = Color.white;
        final FloatSeq rects = new FloatSeq();
        float cx, cy;
        int buildings;
        String text = "";
    }
}
