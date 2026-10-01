package mindustry.client.morj;

import arc.*;
import arc.graphics.g2d.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.blocks.liquid.*;

import static mindustry.Vars.*;

/** Liquid-colored mark in the middle of a conduit. Does not change flow. */
public final class DuctMarks{
    private static boolean on;

    private DuctMarks(){}

    public static void refresh(){
        on = Core.settings.getBool("ductcolor", false);
    }

    public static void mark(Conduit.ConduitBuild build){
        if(!on || build == null || build.liquids == null || build.liquids.currentAmount() <= 0.01f) return;
        Liquid liquid = build.liquids.current();
        if(liquid == null) return;
        Draw.z(Layer.blockOver + 0.01f);
        Draw.color(Tmp.c1.set(liquid.color).a(0.92f));
        Fill.square(build.x, build.y, 1.15f);
        Draw.color();
    }
}
