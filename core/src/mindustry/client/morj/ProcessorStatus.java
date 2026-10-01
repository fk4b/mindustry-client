package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mindustry.Vars.*;

/** Stopped line, long-wait ring, and logic flags above units. Draw only. */
public final class ProcessorStatus{
    private static final Color[] flagColors = {
        Color.valueOf("ff5a5a"), Color.valueOf("ffb020"), Color.valueOf("ffe14a"),
        Color.valueOf("3dde8a"), Color.valueOf("4ad4d4"), Color.valueOf("5b8cff"),
        Color.valueOf("c86bff"), Color.valueOf("ff8ad4")
    };
    private static int flagsDrawn;

    private ProcessorStatus(){}

    public static boolean enabled(){
        return Core.settings.getBool("procstatus", true);
    }

    public static void draw(){
        if(!enabled() || state.isMenu() || player == null) return;
        Font font = Fonts.outline;
        if(font == null) return;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.36f / Math.max(renderer.camerascale, 0.05f));
        Draw.z(Layer.overlayUI);

        Rect cam = Core.camera.bounds(Tmp.r1).grow(tilesize * 6f);
        Groups.build.each(build -> {
            if(!(build instanceof LogicBuild logic) || !logic.isValid()) return;
            if(!cam.contains(logic.x, logic.y)) return;
            LExecutor exec = logic.executor;
            if(exec == null || exec.instructions == null || exec.counter == null) return;
            int index = (int)exec.counter.numval;
            if(index < 0 || index >= exec.instructions.length) return;
            LInstruction current = exec.instructions[index];
            if(exec.stop){
                label(font, logic.x, logic.y + logic.block.size * tilesize * 0.55f + 4f, "L" + index, Pal.remove);
            }else if(current instanceof WaitI wait && wait.value != null && wait.curTime > 0.5f && wait.value.num() > 0f){
                float frac = Mathf.clamp(wait.curTime / wait.value.numf());
                Draw.color(Pal.accent, 0.9f);
                Lines.stroke(1.6f);
                Lines.arc(logic.x, logic.y, logic.block.size * tilesize * 0.42f, frac, 90f);
                Draw.color();
                label(font, logic.x, logic.y + logic.block.size * tilesize * 0.55f + 4f, "L" + index, Pal.accent);
            }
        });

        flagsDrawn = 0;
        Groups.unit.intersect(cam.x, cam.y, cam.width, cam.height, unit -> {
            if(flagsDrawn >= 48 || unit == null || unit.dead() || unit.flag == 0d) return;
            if(unit.inFogTo(player.team())) return;
            flagsDrawn++;
            int idx = Math.floorMod((int)Math.round(unit.flag), flagColors.length);
            String text = Math.abs(unit.flag - Math.rint(unit.flag)) < 1e-4
                ? Long.toString(Math.round(unit.flag))
                : Strings.fixed((float)unit.flag, 2);
            label(font, unit.x, unit.y + unit.hitSize * 0.55f + 3f, text, flagColors[idx]);
        });

        font.setColor(Color.white);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
        Draw.reset();
    }

    private static void label(Font font, float x, float y, String text, Color color){
        font.setColor(color);
        font.draw(text, x - 24f, y, 48f, Align.center, false);
    }
}
