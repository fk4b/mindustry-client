package mindustry.client.morj.mk.ui;

import arc.func.Cons;
import mindustry.editor.MapLoadDialog;
import mindustry.maps.Map;

public class OMapLoadDialog extends MapLoadDialog {
    public OMapLoadDialog(Cons<Map> loader) {
        super(loader);
    }
}
