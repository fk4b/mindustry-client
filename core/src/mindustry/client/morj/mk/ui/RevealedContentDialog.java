package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.func.Boolf;
import arc.graphics.Color;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Reflect;
import mindustry.client.morj.mk.Util;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.editor.BannedContentDialog;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.type.Category;
import mindustry.ui.Styles;

public class RevealedContentDialog<T extends UnlockableContent> extends BannedContentDialog<T> {
    public RevealedContentDialog(String title, ContentType type, Boolf<T> pred) {
        super(title, type, pred);
    }

    private String contentSearch() {
        return Reflect.get(BannedContentDialog.class, this, "contentSearch");
    }
    private void contentSearch(String newValue) {
        Reflect.set(BannedContentDialog.class, this, "contentSearch", newValue);
    }

    private Category selectedCategory() {
        return Reflect.get(BannedContentDialog.class, this, "selectedCategory");
    }
    private void selectedCategory(Category newValue) {
        Reflect.set(BannedContentDialog.class, this, "selectedCategory", newValue);
    }

    private Seq<T> filteredContent() {
        return Reflect.get(BannedContentDialog.class, this, "filteredContent");
    }
    private void filteredContent(Seq<T> newValue) {
        Reflect.set(BannedContentDialog.class, this, "filteredContent", newValue);
    }

    private ObjectSet<T> contentSet() {
        return Reflect.get(BannedContentDialog.class, this, "contentSet");
    }
    private void contentSet(ObjectSet<T> newValue) {
        Reflect.set(BannedContentDialog.class, this, "contentSet", newValue);
    }

    private Table selectedTable() {
        return Reflect.get(BannedContentDialog.class, this, "selectedTable");
    }
    private void selectedTable(Table newValue) {
        Reflect.set(BannedContentDialog.class, this, "selectedTable", newValue);
    }

    private Table deselectedTable() {
        return Reflect.get(BannedContentDialog.class, this, "deselectedTable");
    }
    private void deselectedTable(Table newValue) {
        Reflect.set(BannedContentDialog.class, this, "deselectedTable", newValue);
    }

    private void rebuildTables() {
        Reflect.invoke(BannedContentDialog.class, this, "rebuildTables", Util.noargs);
    }

    @Override
    public void build() {
        final ContentType type = Reflect.get(BannedContentDialog.class, this, "type");
        final Boolf<T> pred = Reflect.get(BannedContentDialog.class, this, "pred");

        cont.clear();

        Cell<Table> cell = cont.table(t -> {
            t.table(s -> {
                s.label(() -> "@search").padRight(10);
                TextField field = s.field(contentSearch(), value -> {
                    contentSearch(value.trim().replaceAll(" +", " ").toLowerCase());
                    rebuildTables();
                }).get();
                s.button(Icon.cancel, Styles.emptyi, () -> {
                    contentSearch("");
                    field.setText("");
                    rebuildTables();
                }).padLeft(10f).size(35f);
            });
            if(type == ContentType.block){
                t.row();
                t.table(c -> {
                    c.marginTop(8f);
                    c.defaults().marginRight(4f);
                    for(Category category : Category.values()){
                        c.button(Vars.ui.getIcon(category.name()), Styles.squareTogglei, () -> {
                            if(selectedCategory() == category){
                                selectedCategory(null);
                            }else{
                                selectedCategory(category);
                            }
                            rebuildTables();
                        }).size(45f).update(i -> i.setChecked(selectedCategory() == category)).padLeft(4f);
                    }
                    c.add("").padRight(4f);
                }).center();
            }
        });
        cont.row();
        if(!Core.graphics.isPortrait()) cell.colspan(2);

        filteredContent(Vars.content.<T>getBy(type).select(pred));
        if(!contentSearch().isEmpty()) filteredContent().removeAll(content -> !content.localizedName.toLowerCase().contains(contentSearch().toLowerCase()));

        cont.table(table -> {
            if(type == ContentType.block){
                table.add("@revealedblocks").color(Color.valueOf("f25555")).padBottom(-1).top().row();
            }else{
                table.add("@revealedunits").color(Color.valueOf("f25555")).padBottom(-1).top().row();
            }

            table.image().color(Color.valueOf("f25555")).height(3f).padBottom(5f).fillX().expandX().top().row();
            table.pane(this::selectedTable).fill().expand().row();
            table.button("@addall", Icon.add, () -> {
                contentSet().addAll(filteredContent());
                rebuildTables();
            }).disabled(button -> contentSet().toSeq().containsAll(filteredContent())).padTop(10f).bottom().fillX();
        }).fill().expandY().uniform();

        if(Core.graphics.isPortrait()) cont.row();

        Cell<Table> cell2 = cont.table(table -> {
            if(type == ContentType.block){
                table.add("@hiddenblocks").color(Pal.accent).padBottom(-1).top().row();
            }else{
                table.add("@hiddenunits").color(Pal.accent).padBottom(-1).top().row();
            }

            table.image().color(Pal.accent).height(3f).padBottom(5f).fillX().top().row();
            table.pane(this::deselectedTable).fill().expand().row();
            table.button("@addall", Icon.add, () -> {
                contentSet().removeAll(filteredContent());
                rebuildTables();
            }).disabled(button -> {
                Seq<T> array = Vars.content.getBy(type);
                array = array.copy();
                array.removeAll(contentSet().toSeq());
                return array.containsAll(filteredContent());
            }).padTop(10f).bottom().fillX();
        }).fill().expandY().uniform();
        if(Core.graphics.isPortrait()){
            cell2.padTop(10f);
        }else{
            cell2.padLeft(10f);
        }

        rebuildTables();
    }
}
