package mindustry.client.morj.mk.rules;

import arc.Core;
import arc.func.Cons;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.ui.OMapView;
import mindustry.client.morj.mk.ui.SpecialEditorAction;
import mindustry.client.morj.mk.util.FormatException;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.Vars;
import mindustry.graphics.Pal;

@RequiredArgsConstructor
public class SchematicEditorAction implements SpecialEditorAction {
    private final int width, height;
    private final Cons<Schematic> accepted;

    @Getter
    private final Schematic.Options options;

    public SchematicEditorAction(int width, int height, Cons<Schematic> accepted) {
        this(width, height, accepted, new Schematic.Options() {{
            skipEmpty = true;
            skipAir = true;
            skipBuildings = true;
        }});
    }

    @Override
    public boolean clicked(OMapView view, float mouseX, float mouseY) {
        Point2 p = view.project(mouseX, mouseY);
        if (p.x < 0 || p.y < 0
                || p.x >= Vars.world.width() - width
                || p.y >= Vars.world.height() - height) return false;
        Schematic scheme;
        try {
            scheme = Schematic.of(Vars.world.tiles, p.x, p.y, width, height, options);
        } catch (FormatException e) {
            throw new RuntimeException("Unreachable!", e);
        }
        accepted.get(scheme);
        return true;
    }

    public void buildOptionsTable(Table table) {
        table.defaults().left().pad(2f);
        table.check(Core.bundle.get("schematic.skipEmpty", "Skip empty floors"), options.skipEmpty, v -> options.skipEmpty = v).row();
        table.check(Core.bundle.get("schematic.skipAir", "Skip air blocks"), options.skipAir, v -> options.skipAir = v).row();
        table.check(Core.bundle.get("schematic.skipBuildings", "Skip buildings"), options.skipBuildings, v -> options.skipBuildings = v).row();
        table.check(Core.bundle.get("schematic.skipNoOverlay", "Skip empty overlays"), options.skipNoOverlay, v -> options.skipNoOverlay = v).row();
    }

    @Override
    public void preview(OMapView view, float mouseX, float mouseY) {
        Point2 p = view.project(mouseX, mouseY);
        int px = p.x, py = p.y;
        Vec2 s = view.unproject(px, py);
        float sx = s.x, sy = s.y;
        s = view.unproject(px + width, py + height);

        Draw.color(Pal.accent);
        Lines.stroke(Scl.scl(2f));
        Lines.rect(sx, sy, s.x - sx, s.y - sy);
        Draw.reset();
    }
}
