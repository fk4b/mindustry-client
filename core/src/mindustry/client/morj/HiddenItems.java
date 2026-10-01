package mindustry.client.morj;

import arc.*;
import arc.math.*;

/** Opacity of items drawn inside bridges, junctions and routers. 0 hides them. */
public final class HiddenItems{
    public static float alpha = 0.7f;

    private HiddenItems(){}

    public static void refresh(){
        alpha = Mathf.clamp(Core.settings.getInt("hiddenitemopacity", 70) / 100f);
    }
}
