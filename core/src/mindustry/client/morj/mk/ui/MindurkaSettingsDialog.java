package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.ScrollPane;
import arc.util.Align;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog;

public class MindurkaSettingsDialog extends Dialog {
    private SettingsMenuDialog.SettingsTable settingsTable = new SettingsMenuDialog.SettingsTable();

    public MindurkaSettingsDialog() {
        super(Core.bundle.get("settings.mindurka"));
        addCloseButton();
        setFillParent(true);
        title.setAlignment(Align.center);
        titleTable.row();
        titleTable.add(new Image()).growX().height(3f).pad(4f).get().setColor(Pal.accent);

        ScrollPane pane = new ScrollPane(settingsTable);
        pane.setFadeScrollBars(false);

        settingsTable.checkPref("mindurka.enableeditor", true);
        settingsTable.checkPref("mindurka.enablenet", true);
        settingsTable.checkPref("mindurka.enablechat", true);
        settingsTable.checkPref("mindurka.enableinput", true);
        settingsTable.checkPref("mindurka.devfeatures", false);

        settingsTable.checkPref("mindurka.integrations.patcheditor", true);

        settingsTable.sliderPref("mindurka.zoomsensitivity", 100, 1, 200, x -> x + "%");

        settingsTable.sliderPref("mindurka.guideslinewidth", 1, 1, 20, x -> x + "px");
        settingsTable.checkPref("mindurka.guidesoutline", false);

        top();

        cont.row();
        cont.add(pane);
    }

    @Override
    public void addCloseButton(){
        buttons.button("@back", Icon.left, this::hide).size(210f, 64f);

        keyDown(key -> {
            if(key == KeyCode.escape || key == KeyCode.back) hide();
        });
    }
}
