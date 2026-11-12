package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Выделил область - все свои блоки в ней заменяются на улучшенные (медный конвейер на титановый, стены на
 * следующий уровень и т.д.). Замена идёт обычными планами стройки, так что ресурсы тратит и строит сам игрок.
 */
public class AreaUpgrade{
    private static boolean hooked, dragging;
    private static Table overlay;
    private static float sx, sy, ex, ey;
    /** Что ставить по умолчанию вместо блока. Остальное можно выбрать в окне. */
    private static final ObjectMap<Block, Block> presets = new ObjectMap<>();
    private static final Rect rect = new Rect();

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                if(overlay != null) close();
                return;
            }
            if(Core.scene.getKeyboardFocus() == null && Core.input.keyTap(Binding.area_upgrade)) toggle();
            if(overlay != null && dragging){
                Vec2 m = Core.input.mouseWorld();
                ex = m.x;
                ey = m.y;
            }
        });

        Events.run(Trigger.draw, () -> {
            if(overlay != null && dragging && state.isGame()) drawSelection();
        });

        Events.on(WorldLoadEvent.class, e -> close());
    }

    public static boolean active(){
        return overlay != null;
    }

    public static void toggle(){
        if(overlay != null){
            close();
            return;
        }
        if(player.unit() == null || !player.unit().canBuild()){
            ui.showInfoFade(Core.bundle.get("client.upgrade.cannotbuild"));
            return;
        }
        dragging = false;
        overlay = new Overlay();
        Core.scene.add(overlay);
    }

    private static void close(){
        dragging = false;
        if(overlay != null) overlay.remove();
        overlay = null;
    }

    private static void loadPresets(){
        if(presets.size > 0) return;
        preset(Blocks.conveyor, Blocks.titaniumConveyor);
        preset(Blocks.conduit, Blocks.pulseConduit);
        preset(Blocks.mechanicalDrill, Blocks.pneumaticDrill);
        preset(Blocks.copperWall, Blocks.titaniumWall);
        preset(Blocks.copperWallLarge, Blocks.titaniumWallLarge);
        preset(Blocks.titaniumWall, Blocks.thoriumWall);
        preset(Blocks.titaniumWallLarge, Blocks.thoriumWallLarge);
        preset(Blocks.plastaniumWall, Blocks.thoriumWall);
        preset(Blocks.plastaniumWallLarge, Blocks.thoriumWallLarge);
        preset(Blocks.thoriumWall, Blocks.surgeWall);
        preset(Blocks.thoriumWallLarge, Blocks.surgeWallLarge);
        preset(Blocks.phaseWall, Blocks.surgeWall);
        preset(Blocks.phaseWallLarge, Blocks.surgeWallLarge);
    }

    private static void preset(Block from, Block to){
        presets.put(from, to);
    }

    private static boolean available(Block b){
        return b != null && b.isPlaceable() && b.unlockedNow() && b.environmentBuildable() && b.requirements.length > 0;
    }

    /** Блоки, которыми игра разрешает заменить этот блок на месте. */
    private static Seq<Block> candidates(Block from){
        return content.blocks().select(b -> b != from && b.size == from.size && b.canReplace(from) && available(b));
    }

    private static @Nullable Block defaultTarget(Block from, Seq<Block> options){
        loadPresets();
        Block want = presets.get(from);
        //если нужного уровня нет (запрещён/не открыт), берём следующий по цепочке
        int guard = 0;
        while(want != null && !options.contains(want) && guard++ < 5) want = presets.get(want);
        return want != null && options.contains(want) ? want : null;
    }

    private static void selectionRect(Rect out){
        out.set(Math.min(sx, ex), Math.min(sy, ey), Math.abs(ex - sx), Math.abs(ey - sy));
    }

    /** Свои постройки, центр которых попал в область. */
    private static Seq<Building> buildingsIn(Rect r){
        Seq<Building> out = new Seq<>();
        int x0 = Math.max(0, world.toTile(r.x)), y0 = Math.max(0, world.toTile(r.y));
        int x1 = Math.min(world.width() - 1, world.toTile(r.x + r.width)), y1 = Math.min(world.height() - 1, world.toTile(r.y + r.height));
        for(int y = y0; y <= y1; y++){
            for(int x = x0; x <= x1; x++){
                Tile t = world.tile(x, y);
                if(t == null || t.build == null || t.build.tile != t) continue;
                if(t.build.team != player.team()) continue;
                out.add(t.build);
            }
        }
        return out;
    }

    private static void drawSelection(){
        selectionRect(rect);
        Draw.z(Layer.overlayUI);
        loadPresets();
        if(rect.width * rect.height < 200_000f * tilesize * tilesize){
            for(Building b : buildingsIn(rect)){
                boolean ok = presets.containsKey(b.block) || candidates(b.block).any();
                if(!ok) continue;
                Draw.color(Pal.accent, 0.25f);
                Fill.square(b.x, b.y, b.block.size * tilesize / 2f);
            }
        }
        Lines.stroke(2f, Pal.accent);
        Lines.rect(rect);
        Draw.reset();
    }

    private static void finishSelection(){
        dragging = false;
        selectionRect(rect);
        close();
        if(rect.width < 1f && rect.height < 1f) return;

        Seq<Building> builds = buildingsIn(rect);
        ObjectMap<Block, Seq<Building>> byType = new ObjectMap<>();
        for(Building b : builds){
            byType.get(b.block, Seq::new).add(b);
        }

        ObjectMap<Block, Seq<Block>> options = new ObjectMap<>();
        for(Block from : byType.keys()){
            Seq<Block> c = candidates(from);
            if(c.any()) options.put(from, c);
        }
        if(options.isEmpty()){
            ui.showInfoFade(Core.bundle.get("client.upgrade.none"));
            return;
        }
        showDialog(byType, options);
    }

    private static void showDialog(ObjectMap<Block, Seq<Building>> byType, ObjectMap<Block, Seq<Block>> options){
        BaseDialog dialog = new BaseDialog("@client.upgrade.title");
        ObjectMap<Block, Block> chosen = new ObjectMap<>();
        Seq<Block> sources = options.keys().toSeq().sort(b -> -byType.get(b).size);
        for(Block from : sources){
            Block def = defaultTarget(from, options.get(from));
            if(def != null) chosen.put(from, def);
        }

        Label cost = new Label("");
        Runnable updateCost = () -> cost.setText(costText(byType, chosen));

        Table list = new Table();
        list.defaults().pad(3f).left();
        for(Block from : sources){
            Seq<Block> opts = options.get(from);
            list.image(from.uiIcon).size(32f);
            list.add(from.localizedName + " [lightgray]x" + byType.get(from).size).width(220f).wrap();
            list.image(Icon.rightSmall).size(24f);

            Table pick = new Table();
            ButtonGroup<ImageButton> group = new ButtonGroup<>();
            group.setMinCheckCount(1);
            ImageButton skip = pick.button(Icon.cancelSmall, Styles.clearTogglei, () -> {
                chosen.remove(from);
                updateCost.run();
            }).size(40f).tooltip("@client.upgrade.skip").get();
            group.add(skip);
            skip.setChecked(!chosen.containsKey(from));
            for(Block to : opts){
                ImageButton btn = pick.button(new TextureRegionDrawable(to.uiIcon), Styles.clearTogglei, 32f, () -> {
                    chosen.put(from, to);
                    updateCost.run();
                }).size(40f).tooltip(to.localizedName).get();
                group.add(btn);
                btn.setChecked(chosen.get(from) == to);
            }
            list.pane(Styles.noBarPane, pick).height(48f).growX();
            list.row();
        }

        dialog.cont.pane(list).grow().row();
        dialog.cont.add(cost).padTop(8f).left();
        updateCost.run();

        dialog.addCloseButton();
        dialog.buttons.button("@client.upgrade.apply", Icon.upOpen, () -> {
            dialog.hide();
            apply(byType, chosen);
        }).size(210f, 64f);
        dialog.show();
    }

    private static String costText(ObjectMap<Block, Seq<Building>> byType, ObjectMap<Block, Block> chosen){
        ObjectIntMap<Item> need = new ObjectIntMap<>();
        for(var e : chosen){
            int count = byType.get(e.key).size;
            for(ItemStack s : e.value.requirements){
                need.increment(s.item, Mathf.round(s.amount * state.rules.buildCostMultiplier) * count);
            }
        }
        if(need.size == 0) return Core.bundle.get("client.upgrade.nothing");
        var core = player.core();
        StringBuilder sb = new StringBuilder(Core.bundle.get("client.upgrade.cost")).append(' ');
        for(var e : need){
            boolean enough = state.rules.infiniteResources || core != null && core.items.get(e.key) >= e.value;
            sb.append(enough ? "[white]" : "[scarlet]").append(e.key.emoji()).append(e.value).append("  ");
        }
        return sb.toString();
    }

    private static void apply(ObjectMap<Block, Seq<Building>> byType, ObjectMap<Block, Block> chosen){
        Unit unit = player.unit();
        if(unit == null || !unit.canBuild()){
            ui.showInfoFade(Core.bundle.get("client.upgrade.cannotbuild"));
            return;
        }
        int queued = 0;
        for(var e : chosen){
            Block to = e.value;
            for(Building b : byType.get(e.key)){
                if(!b.isValid() || b.block != e.key) continue;
                if(!Build.validPlace(to, player.team(), b.tileX(), b.tileY(), b.rotation)) continue;
                BuildPlan plan = new BuildPlan(b.tileX(), b.tileY(), b.rotation, to, b.config());
                unit.addBuild(plan);
                queued++;
            }
        }
        ui.showInfoFade(Core.bundle.get("client.upgrade.queued").replace("{0}", Integer.toString(queued)));
    }

    private static class Overlay extends Table{
        Overlay(){
            setFillParent(true);
            touchable = Touchable.enabled;
            top();
            Table panel = new Table(Styles.black6);
            panel.margin(8f);
            panel.touchable = Touchable.enabled;
            panel.add("@client.upgrade.hint").padRight(8f);
            panel.button(Icon.cancelSmall, Styles.clearNonei, AreaUpgrade::close).size(36f);
            add(panel).padTop(40f);

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(event.targetActor != Overlay.this) return false;
                    if(button == KeyCode.mouseRight){
                        Core.app.post(AreaUpgrade::close);
                        return true;
                    }
                    if(button != KeyCode.mouseLeft) return false;
                    Vec2 m = Core.input.mouseWorld();
                    sx = ex = m.x;
                    sy = ey = m.y;
                    dragging = true;
                    return true;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(button == KeyCode.mouseLeft && dragging) Core.app.post(AreaUpgrade::finishSelection);
                }
            });
        }
    }
}
