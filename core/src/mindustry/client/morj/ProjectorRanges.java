package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.*;

import static mindustry.Vars.*;

/**
 * Filled ranges for the player's overdrive, mend, regen and force projectors.
 * Same shapes as MI2: a tinted disk, a pulsing mend polygon, a regen square, a shield polygon.
 */
public final class ProjectorRanges{
    private ProjectorRanges(){}

    public static boolean enabled(){
        return Core.settings.getBool("projectors", false);
    }

    public static void draw(){
        if(!enabled() || state.isMenu() || player == null) return;
        Team team = player.team();
        Rect cam = Core.camera.bounds(Tmp.r1);
        Groups.build.each(build -> {
            if(build == null || !build.isValid() || build.team != team) return;
            if(state.rules.fog && !build.isDiscovered(team)) return;
            if(build instanceof OverdriveProjector.OverdriveBuild over){
                drawOverdrive(over, cam);
            }else if(build instanceof MendProjector.MendBuild mend){
                drawMend(mend, cam);
            }else if(build instanceof RegenProjector.RegenProjectorBuild regen){
                drawRegen(regen, cam);
            }else if(build instanceof ForceProjector.ForceBuild force){
                drawForce(force, cam);
            }
        });
        Draw.reset();
    }

    private static void drawOverdrive(OverdriveProjector.OverdriveBuild build, Rect cam){
        OverdriveProjector block = (OverdriveProjector)build.block;
        float range = block.range + build.phaseHeat * block.phaseRangeBoost;
        if(range <= 1f || !seen(cam, build.x, build.y, range)) return;
        int sides = Math.max(8, (int)range / 4);

        Draw.color(block.baseColor, block.phaseColor, build.phaseHeat);
        Draw.mixcol(Color.black, 1f - Mathf.clamp(build.efficiency));
        Draw.alpha(0.12f);
        Fill.poly(build.x, build.y, sides, range);
        Lines.stroke(2f);
        Draw.alpha(build.efficiency > 0.01f ? 0.95f : 0.4f);
        Lines.circle(build.x, build.y, range);
        Draw.reset();
    }

    private static void drawMend(MendProjector.MendBuild build, Rect cam){
        if(build.efficiency <= 0f) return;
        MendProjector block = (MendProjector)build.block;
        float range = block.range + build.phaseHeat * block.phaseRangeBoost;
        if(range <= 1f || !seen(cam, build.x, build.y, range)) return;
        float pulse = Mathf.pow(1f - Mathf.clamp(build.charge / block.reload), 5f);

        Draw.color(block.baseColor);
        Draw.alpha(0.05f * Math.max(pulse, 0.35f));
        Fill.poly(build.x, build.y, 18, range);
        Lines.stroke(1.6f);
        Draw.color(block.baseColor);
        Draw.alpha(pulse > 0.1f ? 0.35f + pulse * 0.65f : 0.3f);
        Lines.poly(build.x, build.y, 18, range);
        Draw.reset();
    }

    private static void drawRegen(RegenProjector.RegenProjectorBuild build, Rect cam){
        RegenProjector block = (RegenProjector)build.block;
        float size = block.range * tilesize;
        if(size <= 1f || !seen(cam, build.x, build.y, size / 2f)) return;
        boolean live = build.efficiency > 0.01f;

        Draw.color(block.baseColor);
        Draw.alpha(live ? 0.12f : 0.05f);
        Fill.rect(build.x, build.y, size, size);
        Lines.stroke(2f);
        Draw.alpha(live ? 0.95f : 0.4f);
        Lines.rect(build.x - size / 2f, build.y - size / 2f, size, size);
        Draw.reset();
    }

    private static void drawForce(ForceProjector.ForceBuild build, Rect cam){
        ForceProjector block = (ForceProjector)build.block;
        float range = build.realRadius();
        if(range <= 1f || !seen(cam, build.x, build.y, range)) return;
        boolean live = build.efficiency > 0.01f && !build.broken;

        Draw.color(build.team.color);
        Draw.alpha(live ? 0.1f : 0.04f);
        Fill.poly(build.x, build.y, block.sides, range, block.shieldRotation);
        Lines.stroke(live ? 2f : 1f);
        Draw.alpha(live ? 0.9f : 0.35f);
        Lines.poly(build.x, build.y, block.sides, range, block.shieldRotation);
        Draw.reset();
    }

    private static boolean seen(Rect cam, float x, float y, float range){
        return cam.overlaps(x - range, y - range, range * 2f, range * 2f);
    }
}
