package mindustry.client.morj.mk.ui;

import arc.input.KeyCode;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.world.meta.Env;

public class EnvDialog extends Dialog {
    private Table envsTable;

    public EnvDialog() {
        shown(this::setup);
    }

    void setup() {
        cont.clear();

        cont.label(() -> "@rules.env").center().padBottom(30f).row();

        cont.table(t -> envsTable = t).row();
        check("@rules.env.terrestrial", Env.terrestrial);
        check("@rules.env.space", Env.space);
        check("@rules.env.underwater", Env.underwater);
        check("@rules.env.spores", Env.spores);
        check("@rules.env.scorching", Env.scorching);
        check("@rules.env.groundOil", Env.groundOil);
        check("@rules.env.groundWater", Env.groundWater);
        check("@rules.env.oxygen", Env.oxygen);

        cont.button("@close", this::hide).padTop(30f).width(300f).row();

        keyDown(KeyCode.escape, this::hide);
    }

    void check(String text, int env) {
        envsTable.check(text, (Vars.state.rules.env & env) != 0, b -> drop(b ? (Vars.state.rules.env |= env) : (Vars.state.rules.env &= ~env))).pad(6).left().row();
    }

    void drop(int x) {}
}
