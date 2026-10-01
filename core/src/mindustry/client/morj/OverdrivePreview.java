package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.power.*;

import static mindustry.Vars.*;

/** Placement power forecast and a mark on overdrives already covered by a stronger dome. */
public final class OverdrivePreview{
    private OverdrivePreview(){}

    public static boolean enabled(){
        return Core.settings.getBool("overdrivepreview", true);
    }

    public static void draw(){
        if(!enabled() || state.isMenu() || player == null) return;
        drawCovered();
        drawForecast();
    }

    private static void drawCovered(){
        var list = ClientVars.overdrives;
        if(list == null || list.size < 2) return;
        Team team = player.team();
        Rect cam = Core.camera.bounds(Tmp.r1);
        for(int i = 0; i < list.size; i++){
            OverdriveProjector.OverdriveBuild inner = list.get(i);
            if(inner == null || !inner.isValid() || inner.team != team) continue;
            if(!cam.overlaps(inner.x - 40f, inner.y - 40f, 80f, 80f)) continue;
            float innerBoost = ((OverdriveProjector)inner.block).speedBoost;
            for(int j = 0; j < list.size; j++){
                if(i == j) continue;
                OverdriveProjector.OverdriveBuild dome = list.get(j);
                if(dome == null || !dome.isValid() || dome.team != team) continue;
                float domeBoost = ((OverdriveProjector)dome.block).speedBoost;
                if(domeBoost <= innerBoost + 0.01f) continue;
                if(Mathf.dst(dome.x, dome.y, inner.x, inner.y) > dome.realRange()) continue;
                Drawf.selected(inner, Tmp.c1.set(Pal.remove).a(0.85f));
                break;
            }
        }
    }

    private static void drawForecast(){
        Block block = control.input.block;
        if(!(block instanceof OverdriveProjector projector)) return;
        int cx = control.input.tileX(Core.input.mouseX());
        int cy = control.input.tileY(Core.input.mouseY());
        if(cx < 0 || cy < 0 || cx >= world.width() || cy >= world.height()) return;

        float wx = cx * tilesize + block.offset;
        float wy = cy * tilesize + block.offset;
        PowerGraph[] joined = new PowerGraph[8];
        int count = 0;

        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int tx = cx + block.sizeOffset + dx;
                int ty = cy + block.sizeOffset + dy;
                for(int dir = 0; dir < 4; dir++){
                    Building other = world.build(tx + Geometry.d4x(dir), ty + Geometry.d4y(dir));
                    if(other == null || other.power == null || !other.block.hasPower || !other.block.connectedPower) continue;
                    if(other.team != player.team()) continue;
                    count = add(joined, count, other.power.graph);
                }
            }
        }

        final int[] boxed = {count};
        indexer.eachBlock(player.team(), wx, wy, 24f * tilesize, other -> other.block instanceof PowerNode, other -> {
            PowerNode node = (PowerNode)other.block;
            for(int dx = 0; dx < block.size; dx++){
                for(int dy = 0; dy < block.size; dy++){
                    Tile occ = world.tile(cx + block.sizeOffset + dx, cy + block.sizeOffset + dy);
                    if(occ == null || !node.overlaps(other.tile, occ)) continue;
                    if(PowerNode.insulated(other.tileX(), other.tileY(), occ.x, occ.y)) continue;
                    boxed[0] = add(joined, boxed[0], other.power.graph);
                    return;
                }
            }
        });
        count = boxed[0];

        float usage = block.consPower == null ? 0f : block.consPower.usage;
        boolean any = count > 0;
        float sum = 0f;
        for(int i = 0; i < count; i++) sum += joined[i].getPowerBalance();
        float predicted = sum - usage;
        Color color = !any ? Color.lightGray : predicted >= -0.01f ? Pal.heal : Pal.remove;

        Drawf.dashCircle(wx, wy, projector.range + 3f, color);

        Font font = Fonts.outline;
        if(font == null) return;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        // Placement label, not a damage popup: 0.42/zoom was only a few pixels tall.
        font.getData().setScale(1.65f / Math.max(renderer.camerascale, 0.05f));
        Draw.z(Layer.overlayUI);
        String text = !any
            ? Core.bundle.get("client.morj.overdrive.none")
            : Core.bundle.format("client.morj.overdrive.delta", perSec(sum), perSec(predicted));
        font.setColor(color);
        font.draw(text, wx - 160f, wy + block.size * tilesize * 0.5f + 22f, 320f, Align.center, false);
        font.setColor(Color.white);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.reset();
    }

    private static int add(PowerGraph[] joined, int count, PowerGraph graph){
        if(graph == null || count >= joined.length) return count;
        for(int i = 0; i < count; i++) if(joined[i] == graph) return count;
        joined[count] = graph;
        return count + 1;
    }

    private static String perSec(float perTick){
        long value = Math.round(perTick * 60f);
        String body = UI.formatAmount(Math.abs(value));
        return (value > 0 ? "+" : value < 0 ? "-" : "") + body;
    }
}
