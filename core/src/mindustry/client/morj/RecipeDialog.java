package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.consumers.*;

import static mindustry.Vars.*;

/**
 * Local recipe list and a building count for one unit per second.
 * Rates assume a building running at full speed, without optional boosters.
 */
public class RecipeDialog extends BaseDialog{
    private static RecipeDialog instance;

    private final Seq<Recipe> recipes = new Seq<>();
    private final ObjectMap<UnlockableContent, Recipe> picked = new ObjectMap<>();
    private final Seq<UnlockableContent> goods = new Seq<>();
    private TextField search;
    private UnlockableContent selected;
    private Table list;
    private Table body;

    private RecipeDialog(){
        super("@client.recipe.title");
        addCloseButton();
        shown(this::build);
        onResize(this::build);
    }

    public static void open(){
        if(instance == null) instance = new RecipeDialog();
        instance.show();
    }

    private void build(){
        collect();
        cont.clear();
        cont.top();

        cont.table(side -> {
            side.top();
            search = side.field("", text -> refill()).growX().get();
            search.setMessageText("@client.recipe.search");
            ScrollPane pane = side.pane(items -> {
                list = items.top().left();
            }).grow().padTop(6f).get();
            pane.setScrollingDisabledX(true);
        }).width(230f).growY().padRight(8f);

        body = cont.table().grow().top().left().get();
        refill();
        showSelected();
    }

    private void collect(){
        recipes.clear();
        goods.clear();
        ObjectSet<UnlockableContent> seen = new ObjectSet<>();
        for(Block block : content.blocks()){
            if(block.isHidden()) continue;
            if(block instanceof GenericCrafter crafter) addCrafter(crafter);
            else if(block instanceof Separator separator) addSeparator(separator);
        }
        for(Recipe recipe : recipes){
            for(Flow flow : recipe.out){
                if(seen.add(flow.what)) goods.add(flow.what);
            }
        }
        goods.sortComparing(g -> g.localizedName);
    }

    private void addCrafter(GenericCrafter block){
        if(block.craftTime <= 0f) return;
        Recipe recipe = recipeOf(block);
        float scale = 60f / block.craftTime;
        if(block.outputItems != null){
            for(ItemStack stack : block.outputItems){
                if(stack.item != null && stack.amount > 0) recipe.out.add(new Flow(stack.item, stack.amount * scale, false));
            }
        }
        if(block.outputLiquids != null){
            for(LiquidStack stack : block.outputLiquids){
                if(stack.liquid != null && stack.amount > 0f) recipe.out.add(new Flow(stack.liquid, stack.amount * 60f, false));
            }
        }
        readInputs(block, recipe, scale);
        if(recipe.out.any()) recipes.add(recipe);
    }

    private void addSeparator(Separator block){
        if(block.craftTime <= 0f || block.results == null || block.results.length == 0) return;
        int sum = 0;
        for(ItemStack stack : block.results) sum += stack.amount;
        if(sum <= 0) return;
        Recipe recipe = recipeOf(block);
        recipe.random = true;
        float crafts = 60f / block.craftTime;
        for(ItemStack stack : block.results){
            if(stack.item == null || stack.amount <= 0) continue;
            recipe.out.add(new Flow(stack.item, crafts * stack.amount / sum, false));
        }
        readInputs(block, recipe, crafts);
        if(recipe.out.any()) recipes.add(recipe);
    }

    private void readInputs(Block block, Recipe recipe, float crafts){
        if(block.consumers == null) return;
        for(Consume consume : block.consumers){
            boolean optional = consume.optional || consume.booster;
            if(consume instanceof ConsumeItems items){
                for(ItemStack stack : items.items){
                    if(stack.item != null && stack.amount > 0) recipe.in.add(new Flow(stack.item, stack.amount * crafts, optional));
                }
            }else if(consume instanceof ConsumeLiquid liquid && liquid.liquid != null){
                recipe.in.add(new Flow(liquid.liquid, liquid.amount * 60f, optional));
            }else if(consume instanceof ConsumeLiquids liquids){
                for(LiquidStack stack : liquids.liquids){
                    if(stack.liquid != null && stack.amount > 0f) recipe.in.add(new Flow(stack.liquid, stack.amount * 60f, optional));
                }
            }
        }
    }

    private Recipe recipeOf(Block block){
        Recipe recipe = new Recipe();
        recipe.block = block;
        return recipe;
    }

    private void refill(){
        if(list == null) return;
        String query = search == null || search.getText() == null ? "" : search.getText().toLowerCase();
        list.clear();
        boolean any = false;
        for(UnlockableContent good : goods){
            if(!query.isEmpty() && !good.localizedName.toLowerCase().contains(query)) continue;
            any = true;
            UnlockableContent pick = good;
            list.button(button -> {
                button.left();
                button.image(good.uiIcon).size(24f).padRight(6f);
                button.add(good.localizedName).growX().left();
            }, Styles.clearTogglei, () -> {
                selected = pick;
                showSelected();
            }).checked(b -> selected == pick).growX().height(32f).padBottom(2f).row();
        }
        if(!any) list.add("@client.recipe.none").color(Color.lightGray);
    }

    private void showSelected(){
        body.clear();
        body.top().left();
        if(selected == null){
            body.add("@client.recipe.empty").color(Color.lightGray);
            return;
        }
        body.table(head -> {
            head.image(selected.uiIcon).size(32f).padRight(8f);
            head.add(selected.localizedName).color(Pal.accent);
        }).left().row();
        body.add("@client.recipe.hint").color(Color.lightGray).padBottom(6f).left().row();

        Seq<Recipe> made = recipes.select(r -> r.makes(selected));
        if(made.isEmpty()){
            body.add("@client.recipe.none").color(Color.lightGray).left();
            return;
        }
        for(Recipe recipe : made){
            body.table(Styles.black3, card -> {
                card.margin(6f);
                card.table(title -> {
                    title.left();
                    title.image(recipe.block.uiIcon).size(28f).padRight(6f);
                    title.add(recipe.block.localizedName).color(Pal.accent);
                    if(recipe.random) title.add("  " + Core.bundle.get("client.recipe.random")).color(Color.lightGray);
                }).growX().left().row();
                Flow produced = recipe.output(selected);
                card.add(Core.bundle.format("client.recipe.rate", format(produced.perSec))).left().padTop(2f).row();
                card.table(inputs -> {
                    inputs.left();
                    for(Flow flow : recipe.in){
                        inputs.image(flow.what.uiIcon).size(18f);
                        String text = format(flow.perSec) + Core.bundle.get("client.recipe.per");
                        if(flow.optional) text += " " + Core.bundle.get("client.recipe.opt");
                        inputs.add(text).padRight(8f);
                    }
                }).left().padTop(2f).row();
                card.button("@client.recipe.use", () -> {
                    picked.put(selected, recipe);
                    showSelected();
                }).growX().padTop(4f);
            }).growX().padBottom(6f).left().row();
        }
        Recipe current = picked.get(selected);
        if(current == null || !current.makes(selected)) current = best(selected);
        if(current != null) showChain(current);
    }

    private void showChain(Recipe root){
        Seq<Line> lines = new Seq<>();
        ObjectFloatMap<UnlockableContent> raw = new ObjectFloatMap<>();
        walk(selected, 1f, 0, new ObjectSet<>(), lines, raw, root);

        body.add("@client.recipe.balance").color(Pal.accent).padTop(8f).left().row();
        body.pane(tree -> {
            tree.top().left();
            for(Line line : lines){
                tree.table(row -> {
                    row.left();
                    row.add("").width(14f * line.depth);
                    if(line.block != null){
                        row.image(line.block.uiIcon).size(20f).padRight(4f);
                        row.add(line.block.localizedName + " x" + format(line.buildings)).padRight(8f);
                    }
                    if(line.what != null) row.image(line.what.uiIcon).size(18f).padRight(4f);
                    if(line.raw) row.add(Core.bundle.format("client.recipe.raw", format(line.perSec))).color(Pal.accent);
                    else if(line.cycle) row.add("@client.recipe.cycle").color(Color.scarlet);
                    else if(line.what != null) row.add(format(line.perSec) + Core.bundle.get("client.recipe.per"));
                }).left().row();
            }
            if(!raw.isEmpty()){
                tree.add("@client.recipe.total").color(Pal.accent).padTop(6f).left().row();
                for(ObjectFloatMap.Entry<UnlockableContent> entry : raw.entries()){
                    tree.table(row -> {
                        row.left();
                        row.image(entry.key.uiIcon).size(18f).padRight(4f);
                        row.add(entry.key.localizedName + "  " + format(entry.value) + Core.bundle.get("client.recipe.per"));
                    }).left().row();
                }
            }
        }).growX().height(180f).padTop(4f);
    }

    private void walk(UnlockableContent what, float need, int depth, ObjectSet<UnlockableContent> stack, Seq<Line> lines, ObjectFloatMap<UnlockableContent> raw, Recipe forced){
        if(need < 0.001f || depth > 10) return;
        Recipe recipe = depth == 0 && forced != null ? forced : picked.get(what, best(what));
        if(recipe == null || !recipe.makes(what)){
            Line line = new Line();
            line.depth = depth;
            line.what = what;
            line.perSec = need;
            line.raw = true;
            lines.add(line);
            raw.increment(what, 0f, need);
            return;
        }
        float each = recipe.output(what).perSec;
        if(each <= 0f) return;
        float buildings = need / each;
        Line line = new Line();
        line.depth = depth;
        line.block = recipe.block;
        line.what = what;
        line.perSec = need;
        line.buildings = buildings;
        lines.add(line);
        if(!stack.add(what)){
            Line loop = new Line();
            loop.depth = depth + 1;
            loop.cycle = true;
            lines.add(loop);
            return;
        }
        for(Flow flow : recipe.in){
            if(flow.optional) continue;
            walk(flow.what, buildings * flow.perSec, depth + 1, stack, lines, raw, null);
        }
        stack.remove(what);
    }

    private Recipe best(UnlockableContent what){
        Recipe found = null;
        float rate = -1f;
        boolean open = false;
        for(Recipe recipe : recipes){
            if(!recipe.makes(what)) continue;
            boolean usable = recipe.block.environmentBuildable() && recipe.block.unlockedNow();
            float out = recipe.output(what).perSec;
            if(found == null || (usable && !open) || (usable == open && out > rate)){
                found = recipe;
                rate = out;
                open = usable;
            }
        }
        return found;
    }

    private static String format(float value){
        if(value >= 100f) return Integer.toString(Math.round(value));
        if(value >= 10f) return Strings.fixed(value, 1);
        return Strings.fixed(value, 2);
    }

    private static class Flow{
        final UnlockableContent what;
        final float perSec;
        final boolean optional;

        Flow(UnlockableContent what, float perSec, boolean optional){
            this.what = what;
            this.perSec = perSec;
            this.optional = optional;
        }
    }

    private static class Recipe{
        Block block;
        boolean random;
        final Seq<Flow> in = new Seq<>();
        final Seq<Flow> out = new Seq<>();

        boolean makes(UnlockableContent what){
            return output(what) != null;
        }

        Flow output(UnlockableContent what){
            for(Flow flow : out) if(flow.what == what) return flow;
            return null;
        }
    }

    private static class Line{
        int depth;
        Block block;
        UnlockableContent what;
        float perSec, buildings;
        boolean raw, cycle;
    }
}
