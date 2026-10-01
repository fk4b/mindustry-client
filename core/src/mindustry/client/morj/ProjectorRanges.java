package mindustry.client.morj;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.blocks.defense.*;

import static mindustry.Vars.*;

/** Circles for the player's overdrive, mend, regen and force projectors. */
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
            float range = rangeOf(build);
            if(range <= 1f || range > 500f) return;
            if(!cam.overlaps(build.x - range, build.y - range, range * 2f, range * 2f)) return;

            boolean live = build.efficiency > 0.01f;
            if(build instanceof ForceProjector.ForceBuild force){
                ForceProjector block = (ForceProjector)force.block;
                Draw.color(force.broken ? Pal.remove : team.color, live ? 0.9f : 0.35f);
                Lines.stroke(live ? 1.25f : 0.7f);
                Lines.poly(force.x, force.y, block.sides, range, block.shieldRotation);
            }else if(build.block instanceof RegenProjector regen){
                Drawf.dashSquare(Tmp.c1.set(regen.baseColor).a(live ? 0.9f : 0.35f), build.x, build.y, range);
            }else if(build instanceof MendProjector.MendBuild){
                Drawf.dashCircle(build.x, build.y, range, Tmp.c1.set(Pal.heal).a(live ? 0.95f : 0.35f));
            }else{
                Drawf.dashCircle(build.x, build.y, range, Tmp.c1.set(Pal.accent).a(live ? 0.9f : 0.35f));
            }
        });
        Draw.reset();
    }

    private static float rangeOf(Building build){
        if(build instanceof OverdriveProjector.OverdriveBuild over) return over.realRange();
        if(build instanceof MendProjector.MendBuild mend){
            MendProjector block = (MendProjector)mend.block;
            return block.range + mend.phaseHeat * block.phaseRangeBoost;
        }
        if(build instanceof ForceProjector.ForceBuild force) return force.realRadius();
        if(build.block instanceof RegenProjector regen) return regen.range * tilesize;
        return 0f;
    }
}
