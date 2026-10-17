package mindustry.client.morj.mk.rules;

import arc.func.Cons;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.ui.OMapView;
import mindustry.client.morj.mk.ui.SpecialEditorAction;
import mindustry.Vars;
import mindustry.graphics.Pal;

@RequiredArgsConstructor
public class PointEditorAction implements SpecialEditorAction {
    private final Cons<Point2> accepted;

    @Override
    public boolean clicked(OMapView view, float mouseX, float mouseY) {
        Point2 p = view.project(mouseX, mouseY);
        if (p.x < 0 || p.y < 0
                || p.x >= Vars.world.width()
                || p.y >= Vars.world.height()) return false;
        accepted.get(p.cpy());
        return true;
    }

    @Override
    public void preview(OMapView view, float mouseX, float mouseY) {
        Point2 p = view.project(mouseX, mouseY);

        if (p.x < 0 || p.y < 0 || p.x >= Vars.world.width() || p.y >= Vars.world.height()) {
            return;
        }

        Vec2 center = view.unprojectCenter(p.x, p.y);
        float r = Scl.scl(48f);
        Draw.color(Pal.accent);
        Lines.stroke(Scl.scl(2f));

        Lines.circle(center.x, center.y, r);
        Lines.line(center.x - r, center.y, center.x + r, center.y);
        Lines.line(center.x, center.y - r, center.x, center.y + r);
        Draw.reset();
    }
}
