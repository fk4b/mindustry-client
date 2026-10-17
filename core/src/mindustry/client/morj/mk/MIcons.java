package mindustry.client.morj.mk;

import arc.Core;
import arc.graphics.Texture;
import arc.graphics.Texture.TextureFilter;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import mindustry.gen.Icon;

public class MIcons {
    private MIcons() {}

    private static TextureRegionDrawable sheet(String path){
        Texture tex = new Texture(Core.files.internal(path));
        tex.setFilter(TextureFilter.linear);
        return new TextureRegionDrawable(new TextureRegion(tex));
    }

    private static final TextureRegionDrawable cliffIcons = sheet("mindurka/cliff-icons.png");
    public static final TextureRegionDrawable cliffAuto = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 128, 128, 128, 128));
    public static final TextureRegionDrawable cliff0 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 256, 128, 128, 128));
    public static final TextureRegionDrawable cliff1 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 256, 256, 128, 128));
    public static final TextureRegionDrawable cliff2 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 128, 256, 128, 128));
    public static final TextureRegionDrawable cliff3 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 0, 256, 128, 128));
    public static final TextureRegionDrawable cliff4 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 0, 128, 128, 128));
    public static final TextureRegionDrawable cliff5 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 0, 0, 128, 128));
    public static final TextureRegionDrawable cliff6 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 128, 0, 128, 128));
    public static final TextureRegionDrawable cliff7 = new TextureRegionDrawable(new TextureRegion(cliffIcons.getRegion(), 256, 0, 128, 128));

    private static final TextureRegionDrawable icons = sheet("mindurka/icons.png");
    public static final TextureRegionDrawable fortsPlotToggle = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 0, 0, 128, 128));
    public static final TextureRegionDrawable fortsPlotCarver = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 128, 0, 128, 128));
    public static final TextureRegionDrawable hubServerConfig = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 256, 0, 128, 128));
    public static final TextureRegionDrawable castleRoomPlace = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 512, 0, 128, 128));
    public static final TextureRegionDrawable castleMinerPlacer = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 640, 0, 128, 128));
    public static final TextureRegionDrawable clipboard = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 384, 0, 128, 128));
    public static final TextureRegionDrawable blendNormal = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 0, 128, 128, 128));
    public static final TextureRegionDrawable blendReplace = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 128, 128, 128, 128));
    public static final TextureRegionDrawable blendUnder = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 256, 128, 128, 128));
    public static final TextureRegionDrawable floorsAsOverlaysOff = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 384, 128, 128, 128));
    public static final TextureRegionDrawable floorsAsOverlaysOn = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 512, 128, 128, 128));
    public static final TextureRegionDrawable fortsPlotDisabled = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 0, 256, 128, 128));
    public static final TextureRegionDrawable fortsPlotEnabled = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 128, 256, 128, 128));
    public static final TextureRegionDrawable fortsPlotPlaced = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 256, 256, 128, 128));
    public static final TextureRegionDrawable fortsPlotLocked = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 384, 256, 128, 128));
    public static final TextureRegionDrawable fortsPlotStatic = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 512, 256, 128, 128));
    public static final TextureRegionDrawable fortsPlotGhost = new TextureRegionDrawable(new TextureRegion(icons.getRegion(), 640, 256, 128, 128));

    public static void load() {
        Icon.icons.put("cliff-auto", cliffAuto);
        Icon.icons.put("cliff-0", cliff0);
        Icon.icons.put("cliff-1", cliff1);
        Icon.icons.put("cliff-2", cliff2);
        Icon.icons.put("cliff-3", cliff3);
        Icon.icons.put("cliff-4", cliff4);
        Icon.icons.put("cliff-5", cliff5);
        Icon.icons.put("cliff-6", cliff6);
        Icon.icons.put("cliff-7", cliff7);
        Icon.icons.put("fortsPlotToggle", fortsPlotToggle);
        Icon.icons.put("fortsPlotCarver", fortsPlotCarver);
        Icon.icons.put("hubServerConfig", hubServerConfig);
        Icon.icons.put("castleRoomPlace", castleRoomPlace);
        Icon.icons.put("castleMinerPlacer", castleMinerPlacer);
        Icon.icons.put("tool-clipboard", clipboard);
        Icon.icons.put("blend-normal", blendNormal);
        Icon.icons.put("blend-replace", blendReplace);
        Icon.icons.put("blend-under", blendUnder);
        Icon.icons.put("floors-as-overlays-on", floorsAsOverlaysOn);
        Icon.icons.put("floors-as-overlays-off", floorsAsOverlaysOff);
        Icon.icons.put("forts-plot-disabled", fortsPlotDisabled);
        Icon.icons.put("forts-plot-enabled", fortsPlotEnabled);
        Icon.icons.put("forts-plot-placed", fortsPlotPlaced);
        Icon.icons.put("forts-plot-locked", fortsPlotLocked);
        Icon.icons.put("forts-plot-static", fortsPlotStatic);
        Icon.icons.put("forts-plot-ghost", fortsPlotGhost);
        Icon.icons.put("rect", fortsPlotEnabled);
    }
}
