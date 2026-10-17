package mindustry.client.morj.mk;

import arc.util.Nullable;
import mindustry.client.morj.mk.fs.FileSystem;
import mindustry.client.morj.mk.rules.MRules;
import mindustry.client.morj.mk.ui.*;
import mindustry.Vars;
import mindustry.editor.MapView;

public class MVars {
    private MVars() {}

    public static MRules rules;

    public static final int version = 10;
    public static MapView oldMapView;
    public static OMapView mapView;
    public static OMapEditor mapEditor;
    public static OEditorDialog editorDialog;
    public static OCustomRulesDialog customRulesDialog;
    public static ToolOptions toolOptions = new ToolOptions();
    public static @Nullable FileSystem fileSystem = null;
    public static Ui ui = new Ui();
    public static OTextureAtlas atlas;

    public static boolean patchEditorLoaded = false;

    private static final BitMap[] mapbitsPool = new BitMap[2];

    private static BitMap mapbits(int index) {
        BitMap b = mapbitsPool[index];
        if (b == null || b.width != Vars.world.width() || b.height != Vars.world.height()) {
            mapbitsPool[index] = b = BitMap.of(Vars.world.tiles);
        }
        return b;
    }

    public static BitMap mapbits() { return mapbits(0); }
    public static BitMap mapbits2() { return mapbits(1); }
}
