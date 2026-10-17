package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.func.Boolc;
import arc.func.Boolf;
import arc.func.Boolp;
import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Floatc;
import arc.func.Floatp;
import arc.func.Intc;
import arc.func.Intp;
import arc.func.Prov;
import arc.graphics.Color;
import arc.math.geom.Point2;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.struct.OrderedMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;

import mindustry.client.morj.mk.MVars;

/**
 * Utility class for writing rules.
 * <p>
 * Exists to dump all the bloat from {@link mindustry.ui.dialogs.CustomRulesDialog} somewhere where
 * widgets can reuse them.
 */
public class RulesWrite {
    private final OLoadoutDialog loadoutDialog = new OLoadoutDialog();

    private static final Boolp TRUE = () -> true;

    private final Table root;
    /**
     * A hack to add the category if there's a property that
     * matches the search.
     */
    private @Nullable String categoryNameToAdd = null;
    private @Nullable RulesWrite parent = null;
    private String filter;
    private boolean canPlaceSpacer = false;
    private boolean shownCategory = false;

    public RulesWrite(Table root, String filter) {
        this.root = root;
        this.filter = filter.toLowerCase();
    }

    /** Add corrective space because Mindustry is stupid. */
    private Cell<Table> correctiveSpace() {
        return root.table().width(20);
    }

    private boolean shouldAdd(String tlkey) {
        // TODO: Use a better algorithm.
        if (!Core.bundle.get(tlkey).toLowerCase().contains(filter)) return false;

        // TODO: Potentially bad on performance?
        if (!shownCategory) {
            while (true) {
                RulesWrite cursor = this;

                while (cursor.parent != null && cursor.categoryNameToAdd == null)
                    cursor = cursor.parent;

                if (cursor.categoryNameToAdd == null || cursor.parent == null) break;

                String name = Core.bundle.get("rules.title." + cursor.categoryNameToAdd);

                cursor.parent.root.add(name).color(Pal.accent).pad(6).padLeft(20).fillX().left().row();
                cursor.parent.root.image().color(Pal.accent).height(3f).pad(6).padLeft(20).padRight(20).fillX().row();
                cursor.parent.root.add(cursor.root).fillX().pad(0);
                cursor.parent.root.row();

                cursor.categoryNameToAdd = null;
            }
            shownCategory = true;
        }
        canPlaceSpacer = true;

        return true;
    }

    public void clear() {
        root.clear();
        canPlaceSpacer = false;
    }
    public void clear(String newFilter) {
        clear();
        filter = newFilter;
    }

    public interface AddItem<T> {
        void add(String tlKey, T value);
    }
    public <T> void selection(String tlKey, Cons<AddItem<T>> addItem, Cons<T> onClick, T def) {
        if (!shouldAdd(tlKey)) return;

        correctiveSpace();
        root.table(Tex.button, t -> {
            t.margin(10f);
            ButtonGroup<?> group = new ButtonGroup<>();
            TextButton.TextButtonStyle style = Styles.flatTogglet;

            t.defaults().size(140f, 50f);

            final Seq<TextButton> buttons = new Seq<>();

            addItem.get(new AddItem<T>() {
                @Override
                public void add(String tlKey, T value) {
                    @SuppressWarnings("unchecked")
                    Cell<TextButton>[] button = new Cell[1]; // Java
                    button[0] = t.button(Core.bundle.get(tlKey), style, () -> {
                        buttons.each(x -> x.setChecked(false));
                        button[0].get().setChecked(true);
                        onClick.get(value);
                    }).group(group);
                    button[0].get().setChecked(value == null ? def == null : value.equals(def));
                    if (buttons.size % 3 == 2) button[0].row();
                    buttons.add(button[0].get());
                }
            });
        }).left().pad(6).fill(false).expand(false, false);
        correctiveSpace().row();
    }
    public <T> void selectionRaw(String key, Cons<AddItem<T>> addItem, Cons<T> onClick, T def) {
        if (!shouldAdd(key)) return;

        correctiveSpace();
        root.table(Tex.button, t -> {
            t.margin(10f);
            ButtonGroup<?> group = new ButtonGroup<>();
            TextButton.TextButtonStyle style = Styles.flatTogglet;

            t.defaults().size(140f, 50f);

            final Seq<TextButton> buttons = new Seq<>();

            addItem.get((name, value) -> {
                Cell<TextButton>[] button = new Cell[1];

                button[0] = t.button(name, style, () -> {
                    buttons.each(x -> x.setChecked(false));
                    button[0].get().setChecked(true);
                    onClick.get(value);
                }).group(group);

                button[0].get().setChecked(value == null ? def == null : value.equals(def));

                if (buttons.size % 3 == 2) button[0].row();
                buttons.add(button[0].get());
            });
        }).left().pad(6);
        correctiveSpace().row();
    }

    public static class BoolCtl {
        public static final BoolCtl throwaway = new BoolCtl();

        public Boolp enabled = TRUE;
        public BoolCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }
    }
    public BoolCtl w(String tlKey, Boolp def, Boolc onClick) {
        if (!shouldAdd(tlKey)) return BoolCtl.throwaway;

        final BoolCtl ctl = new BoolCtl();

        correctiveSpace();
        Cell<CheckBox> cell = root.check(Core.bundle.get(tlKey), onClick).checked(def.get())
                .update(it -> {
                    it.setChecked(def.get());
                    it.setDisabled(!ctl.enabled.get());
                });
        cell.pad(6).get().left();
        correctiveSpace().row();
        root.row();

        return ctl;
    }
    public BoolCtl b(String tlKey, Boolp def, Boolc onClick) {
        return w(tlKey, def, onClick);
    }

    public static class StrCtl {
        public static final StrCtl throwaway = new StrCtl();

        public Boolp enabled = TRUE;
        public StrCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }

        String _prevValue;
    }

    public StrCtl s(String tlKey, Prov<String> def, Cons<String> onClick) {
        if (!shouldAdd(tlKey)) return StrCtl.throwaway;

        final StrCtl ctl = new StrCtl();
        ctl._prevValue = def.get();

        correctiveSpace();
        Cell<Table> cell = root.table(t -> {
            t.left();
            t.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0)
                    .update(a -> a.setColor(ctl.enabled.get() ? Color.white : Color.gray));
            final TextField[] field = new TextField[1];
            field[0] = t.field(ctl._prevValue, s -> {
                        ctl._prevValue = s;
                        onClick.get(s);
                    })
                    .update(a -> {
                        a.setDisabled(!ctl.enabled.get());
                        String v = def.get();
                        boolean notFocused = a.getScene() == null || a.getScene().getKeyboardFocus() != a;
                        if (notFocused || !v.equals(ctl._prevValue)) {
                            ctl._prevValue = v;
                            a.setText(v);
                        }
                    }).marginTop(0).marginBottom(0).width(120f).left().get();
        }).padTop(0);
        cell.pad(6).get().left();
        correctiveSpace().row();
        root.row();

        return ctl;
    }

    public static class IntCtl {
        public static final IntCtl throwaway = new IntCtl();

        private Label label;

        public Boolp enabled = TRUE;
        public IntCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }

        public int min = Integer.MIN_VALUE;
        public IntCtl min(int value) {
            min = value;
            return this;
        }

        public int max = Integer.MAX_VALUE;
        public IntCtl max(int value) {
            max = value;
            return this;
        }

        public IntCtl range(int min, int max) {
            min(min);
            max(max);
            return this;
        }

        public IntCtl label(String raw) {
            if (label != null) label.setText(raw);
            return this;
        }

        int _prevValue;
    }
    public IntCtl w(String tlKey, Intp def, Intc onClick) {
        if (!shouldAdd(tlKey)) return IntCtl.throwaway;

        final IntCtl ctl = new IntCtl();
        ctl._prevValue = def.get();

        correctiveSpace();
        Cell<Table> cell = root.table(t -> {
            t.left();
            ctl.label = t.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0)
                    .update(a -> a.setColor(ctl.enabled.get() ? Color.white : Color.gray)).get();
            t.field(ctl._prevValue + "", s -> {
                        int i = Strings.parseInt(s);
                        ctl._prevValue = i;
                        onClick.get(i);
                    })
                    .update(a -> {
                        a.setDisabled(!ctl.enabled.get());
                        int i = def.get();
                        if (ctl._prevValue != i) {
                            ctl._prevValue = i;
                            a.setText(i + "");
                        }
                    }).marginTop(0).marginBottom(0)
                    .valid(f -> Strings.parseInt(f) >= ctl.min && Strings.parseInt(f) <= ctl.max).width(120f).left();
        }).padTop(0);
        cell.pad(6).get().left();
        correctiveSpace().row();
        root.row();

        return ctl;
    }
    public IntCtl i(String tlKey, Intp def, Intc onClick) {
        return w(tlKey, def, onClick);
    }

    public static class PointCtl {
        public static final PointCtl throwaway = new PointCtl();

        public Boolp enabled = TRUE;
        public PointCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }

        int _prevX, _prevY;
    }
    public PointCtl point(String tlKey, Prov<Point2> def, Cons<Point2> onClick) {
        if (!shouldAdd(tlKey)) return PointCtl.throwaway;

        final PointCtl ctl = new PointCtl();
        ctl._prevX = def.get().x;
        ctl._prevY = def.get().y;

        correctiveSpace();
        Cell<Table> cell = root.table(t -> {
            t.left();
            t.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0)
                    .update(a -> a.setColor(ctl.enabled.get() ? Color.white : Color.gray));
            t.field(ctl._prevX + "", s -> {
                        int v = Strings.parseInt(s);
                        ctl._prevX = v;
                        onClick.get(new Point2(ctl._prevX, ctl._prevY));
                    })
                    .update(a -> {
                        a.setDisabled(!ctl.enabled.get());
                        int v = def.get().x;
                        if (ctl._prevX != v) { ctl._prevX = v; a.setText(v + ""); }
                    }).marginTop(0).marginBottom(0)
                    .valid(Strings::canParseInt).width(80f).left();
            t.add("  ").padLeft(2).padRight(2);
            t.field(ctl._prevY + "", s -> {
                        int v = Strings.parseInt(s);
                        ctl._prevY = v;
                        onClick.get(new Point2(ctl._prevX, ctl._prevY));
                    })
                    .update(a -> {
                        a.setDisabled(!ctl.enabled.get());
                        int v = def.get().y;
                        if (ctl._prevY != v) { ctl._prevY = v; a.setText(v + ""); }
                    }).marginTop(0).marginBottom(0)
                    .valid(Strings::canParseInt).width(80f).left();
        }).padTop(0);
        cell.pad(6).get().left();
        correctiveSpace().row();
        root.row();

        return ctl;
    }

    public static class PointsCtl {
        public static final PointsCtl throwaway = new PointsCtl();

        public Boolp enabled = TRUE;
        public PointsCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }
    }
    public PointsCtl points(String tlKey, Prov<Seq<Point2>> def, Cons<Seq<Point2>> onClick) {
        if (!shouldAdd(tlKey)) return PointsCtl.throwaway;

        final PointsCtl ctl = new PointsCtl();
        final Seq<Point2> current = new Seq<>(def.get());
        final Table[] rowsTable = {null};

        correctiveSpace();
        Cell<Table> outerCell = root.table(outer -> {
            outer.left();
            outer.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0)
                    .update(a -> a.setColor(ctl.enabled.get() ? Color.white : Color.gray));
            outer.table(inner -> {
                rowsTable[0] = inner;
                rebuildPointRows(inner, current, ctl, def, onClick);
            }).left();
        }).padTop(0);
        outerCell.pad(6).get().left();
        correctiveSpace().row();
        root.row();

        return ctl;
    }

    private void pickPoint(Cons<Point2> callback) {
        MVars.mapView.editorAction = new mindustry.client.morj.mk.rules.PointEditorAction(p -> {
            callback.get(p);
            MVars.customRulesDialog.show();
        });
        MVars.customRulesDialog.hide();
    }

    private void rebuildPointRows(Table t, Seq<Point2> current, PointsCtl ctl,
                                  Prov<Seq<Point2>> def, Cons<Seq<Point2>> onClick) {
        t.clear();
        t.defaults().left();

        // + picks a tile in the world and appends it
        t.button("+", () -> pickPoint(p -> {
            current.add(p);
            onClick.get(new Seq<>(current));
            rebuildPointRows(t, current, ctl, def, onClick);
        })).size(32f).padBottom(2).left().row();

        for (int idx = 0; idx < current.size; idx++) {
            final int i = idx;
            final int[] prev = {current.get(i).x, current.get(i).y};

            t.table(row -> {
                // "~" button: re-pick this entry's position from the world
                row.button("~", () -> pickPoint(p -> {
                    prev[0] = p.x;
                    prev[1] = p.y;
                    current.set(i, p);
                    onClick.get(new Seq<>(current));
                })).size(32f).padRight(4).left();

                row.field(prev[0] + "", s -> {
                            int v = Strings.parseInt(s);
                            prev[0] = v;
                            current.set(i, new Point2(v, current.get(i).y));
                            onClick.get(new Seq<>(current));
                        })
                        .update(a -> {
                            a.setDisabled(!ctl.enabled.get());
                            Seq<Point2> d = def.get();
                            if (i < d.size && d.get(i).x != prev[0]) {
                                prev[0] = d.get(i).x;
                                a.setText(prev[0] + "");
                            }
                        })
                        .valid(Strings::canParseInt).width(80f).marginTop(0).marginBottom(0).left();
                row.add("  ").padLeft(2).padRight(2);
                row.field(prev[1] + "", s -> {
                            int v = Strings.parseInt(s);
                            prev[1] = v;
                            current.set(i, new Point2(current.get(i).x, v));
                            onClick.get(new Seq<>(current));
                        })
                        .update(a -> {
                            a.setDisabled(!ctl.enabled.get());
                            Seq<Point2> d = def.get();
                            if (i < d.size && d.get(i).y != prev[1]) {
                                prev[1] = d.get(i).y;
                                a.setText(prev[1] + "");
                            }
                        })
                        .valid(Strings::canParseInt).width(80f).marginTop(0).marginBottom(0).left();
                row.button("-", () -> {
                    current.remove(i);
                    onClick.get(new Seq<>(current));
                    rebuildPointRows(t, current, ctl, def, onClick);
                }).size(32f).padLeft(4).left();
            }).left().row();
        }
    }

    public static class FloatCtl {
        public static final FloatCtl throwaway = new FloatCtl();

        public Boolp enabled = TRUE;
        public FloatCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }

        public float min = Float.MIN_VALUE;
        public FloatCtl min(float value) {
            min = value;
            return this;
        }

        public float max = Float.MAX_VALUE;
        public FloatCtl max(float value) {
            max = value;
            return this;
        }

        public FloatCtl range(float min, float max) {
            min(min);
            max(max);
            return this;
        }

        float _prevValue;
    }
    public FloatCtl w(String tlKey, Floatp def, Floatc onClick) {
        if (!shouldAdd(tlKey)) return FloatCtl.throwaway;

        final FloatCtl ctl = new FloatCtl();
        ctl._prevValue = def.get();

        correctiveSpace();
        Cell<Table> cell = root.table(t -> {
            t.left();
            t.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0)
                    .update(a -> a.setColor(ctl.enabled.get() ? Color.white : Color.gray));
            t.field(ctl._prevValue + "", s -> {
                        float f = Strings.parseFloat(s);
                        ctl._prevValue = f;
                        onClick.get(f);
                    })
                    .update(a -> {
                        a.setDisabled(!ctl.enabled.get());
                        float f = def.get();
                        if (f != ctl._prevValue) {
                            ctl._prevValue = f;
                            a.setText(f + "");
                        }
                    }).marginTop(0).marginBottom(0)
                    .valid(f -> Strings.parseFloat(f) >= ctl.min && Strings.parseFloat(f) <= ctl.max).width(120f).left();
        }).padTop(0);
        cell.pad(6).padTop(0).get().left().marginTop(0);
        correctiveSpace().row();
        root.row();

        return ctl;
    }
    public FloatCtl f(String tlKey, Floatp def, Floatc onClick) {
        return w(tlKey, def, onClick);
    }

    public void loadout(String tlKey, int capacity, Prov<Seq<ItemStack>> loadout, Boolf<Item> verifier,
                        Runnable resetter, Runnable updater, Runnable hider) {
        button(tlKey, () -> loadoutDialog.show(capacity, loadout.get(), verifier,
                resetter, updater, hider));
    }

    public void color(String tlKey, Prov<Color> def, Cons<Color> onClick) {
        if (!shouldAdd(tlKey)) return;

        correctiveSpace();
        root.button(b -> {
            b.left();
            b.table(Tex.pane, in -> in.stack(new Image(Tex.alphaBg), new Image(Tex.whiteui) {{
                update(() -> setColor(def.get()));
            }}).grow()).margin(4).size(50).padRight(10);
            b.add("@"+tlKey);
        }, () -> Vars.ui.picker.show(def.get(), onClick)).left().width(300f).padLeft(6);
        correctiveSpace().row();
    }

    public void button(String tlKey, Runnable click) {
        if (!shouldAdd(tlKey)) return;

        correctiveSpace();
        root.button("@" + tlKey, click).left().width(300f).padLeft(6);
        correctiveSpace().row();
    }

    public void teams(String tlKey, Cons2<Team, RulesWrite> sectionConf, Boolf<Team> enabled) {
        if (!shouldAdd(tlKey)) return;

        final Table table = new Table();
        correctiveSpace();
        root.add(table).left();
        correctiveSpace().row();
        table.defaults().left();

        class TeamData {
            boolean shown;
            final Table container = new Table();

            TextureRegionDrawable buttonIcon() { return shown ? Icon.downOpen : Icon.upOpen; }

            TeamData(final Team team) { this(team, false); }
            TeamData(final Team team, final boolean shown) {
                this.shown = shown;

                container.button(team.coloredName(), Icon.downOpen, Styles.togglet, () -> this.shown = !this.shown)
                        .marginLeft(14).width(260).height(55).update(t -> {
                            ((Image) t.getChildren().get(1)).setDrawable(buttonIcon());
                            t.setChecked(this.shown);
                        }).left().padBottom(2).row();
                container.collapser(c -> {
                    c.defaults().left();
                    RulesWrite write = new RulesWrite(c, filter);
                    write.parent = RulesWrite.this;
                    sectionConf.get(team, write);
                }, () -> this.shown).left().padBottom(2).fillX().row();
                table.add(container).fillX().padLeft(6).row();
            }

            void remove() {
                table.removeChild(container);
            }
        }

        final OrderedMap<Team, TeamData> dataList = new OrderedMap<>();

        class AbstractTeamData {
            Team team = null;
            final Table mainContainer = new Table();
            final Button button;
            Table contentsTable;

            TextureRegionDrawable buttonIcon() { return (team == null || dataList.containsKey(team)) ? Icon.cancel : button.isChecked() ? Icon.downOpen : Icon.upOpen; }

            AbstractTeamData() {
                button = new Button(Styles.togglet);

                Image image = new Image(Icon.cancel);
                Cell<Image> imageCell = button.add(image).left().size(Icon.cancel.imageSize() / Scl.scl(1)).padLeft(-((Icon.downOpen.imageSize() + Icon.cancel.imageSize() / 2) / Scl.scl(1)));

                final TextField textField = new TextField();
                textField.setValidator(text -> {
                    if (!Strings.canParsePositiveInt(text)) return false;
                    int v = Strings.parseInt(text);
                    return v >= 0 && v < 255;
                });
                textField.changed(() -> {
                    if (!textField.isValid()) team = null;
                    else team = Team.all[Strings.parseInt(textField.getText())];

                    image.setDrawable(buttonIcon());
                    contentsTable.clear();
                    if (team == null || dataList.containsKey(team)) {
                        button.setChecked(false);
                    } else {
                        textField.color.set(team.color);
                        RulesWrite write = new RulesWrite(contentsTable, filter);
                        write.parent = RulesWrite.this;
                        sectionConf.get(team, write);
                    }
                });
                button.add(textField).fillX().pad(0).margin(0);

                button.clicked(() -> {
                    if (!textField.isValid() || dataList.containsKey(team)) {
                        button.setChecked(false);
                        return;
                    }

                    image.setDrawable(buttonIcon());
                });

                mainContainer.add(button).marginLeft(14).width(260).height(55).padBottom(2).left().row();

                mainContainer.collapser(c -> {
                    c.clear();
                    contentsTable = c;
                    c.defaults().left();
                }, button::isChecked).left().marginBottom(2).row();

                table.add(mainContainer).left().padLeft(6);
            }
        }

        for (Team team : Team.baseTeams) {
            dataList.put(team, new TeamData(team));
        }
        for (Team team : Team.all) {
            if (dataList.containsKey(team)) continue;
            if (!enabled.get(team) && team.core()==null) continue;
            dataList.put(team, new TeamData(team));
        }

        new AbstractTeamData();
    }

    public static class BlockCtl {
        private Boolp enabled = TRUE;
        private Boolf<Block> filter = block -> true;

        public BlockCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }
        public BlockCtl filter(Boolf<Block> value) {
            filter = value;
            return this;
        }
    }
    public BlockCtl block(String tlKey, Cons<Block> onClick, Prov<Block> def) {
        if (!shouldAdd(tlKey)) return new BlockCtl();

        BlockCtl ctl = new BlockCtl();
        final Table[] table = new Table[1];

        correctiveSpace();
        root.button(b -> {
            b.left();
            table[0] = b.table(Tex.pane, in -> in.stack(new Image(Tex.alphaBg), new Image(new TextureRegionDrawable(def.get().uiIcon, 4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height))) {{
                update(() -> {
                    b.setDisabled(!ctl.enabled.get());
                    b.setChecked(false);
                    setDrawable(new TextureRegionDrawable(
                            def.get().uiIcon,
                            4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height)
                    ));
                });
            }}).grow()).margin(4).size(50).padRight(10).get();
            b.add("@"+tlKey);
        }, () -> new BlockSelectDialog("@" + tlKey).show(ctl.filter, block -> {
            Image image = (Image) ((Stack) (table[0].getChildren().get(0))).getChildren().get(1);
            image.setDrawable(new TextureRegionDrawable(block.uiIcon, 4f / Math.min(block.uiIcon.width, block.uiIcon.height)));
            onClick.get(block);
        }, def.get())).left().width(300f).padLeft(6);
        correctiveSpace().row();

        return ctl;
    }

    public static class UnitCtl {
        private Boolf<UnitType> filter = unit -> true;

        public UnitCtl filter(Boolf<UnitType> value) {
            filter = value;
            return this;
        }
    }
    public UnitCtl unit(String tlKey, Cons<UnitType> onClick, Prov<UnitType> def) {
        if (!shouldAdd(tlKey)) return new UnitCtl();

        UnitCtl ctl = new UnitCtl();
        final Table[] table = new Table[1];

        correctiveSpace();
        root.button(b -> {
            b.left();
            table[0] = b.table(Tex.pane, in -> in.stack(new Image(Tex.clear), new Image(new TextureRegionDrawable(def.get().uiIcon, 4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height))) {{
                update(() -> {
                    b.setChecked(false);
                    setDrawable(new TextureRegionDrawable(
                            def.get().uiIcon,
                            4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height)
                    ));
                });
            }}).grow()).margin(4).size(50).padRight(10).get();
            b.add("@"+tlKey);
        }, () -> new UnitSelectDialog("@" + tlKey).show(ctl.filter, unit -> {
            Image image = (Image) ((Stack) (table[0].getChildren().get(0))).getChildren().get(1);
            image.setDrawable(new TextureRegionDrawable(unit.uiIcon, 4f / Math.min(unit.uiIcon.width, unit.uiIcon.height)));
            onClick.get(unit);
        }, def.get())).left().width(300f).padLeft(6);
        correctiveSpace().row();

        return ctl;
    }

    public static class ItemCtl {
        private Boolf<Item> filter = item -> true;

        public ItemCtl filter(Boolf<Item> value) {
            filter = value;
            return this;
        }
    }
    public ItemCtl item(String tlKey, Cons<Item> onClick, Prov<Item> def) {
        if (!shouldAdd(tlKey)) return new ItemCtl();

        ItemCtl ctl = new ItemCtl();
        final Table[] table = new Table[1];

        correctiveSpace();
        root.button(b -> {
            b.left();
            table[0] = b.table(Tex.pane, in -> in.stack(new Image(Tex.clear), new Image(new TextureRegionDrawable(def.get().uiIcon, 4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height))) {{
                update(() -> {
                    b.setChecked(false);
                    setDrawable(new TextureRegionDrawable(
                            def.get().uiIcon,
                            4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height)
                    ));
                });
            }}).grow()).margin(4).size(50).padRight(10).get();
            b.add("@"+tlKey);
        }, () -> new ItemSelectDialog("@" + tlKey).show(ctl.filter, item -> {
            Image image = (Image) ((Stack) (table[0].getChildren().get(0))).getChildren().get(1);
            image.setDrawable(new TextureRegionDrawable(item.uiIcon, 4f / Math.min(item.uiIcon.width, item.uiIcon.height)));
            onClick.get(item);
        }, def.get())).left().width(300f).padLeft(6);
        correctiveSpace().row();

        return ctl;
    }

    public static class StatusCtl {
        private Boolp enabled = TRUE;
        private Boolf<StatusEffect> filter = status -> true;

        public StatusCtl filter(Boolf<StatusEffect> value) {
            filter = value;
            return this;
        }
    }
    public StatusCtl status(String tlKey, Cons<StatusEffect> onClick, Prov<StatusEffect> def) {
        if (!shouldAdd(tlKey)) return new StatusCtl();

        StatusCtl ctl = new StatusCtl();
        final Table[] table = new Table[1];

        correctiveSpace();
        root.button(b -> {
            b.left();
            table[0] = b.table(Tex.pane, in -> in.stack(new Image(Tex.clear), new Image(new TextureRegionDrawable(def.get().uiIcon, 4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height))) {{
                update(() -> {
                    b.setChecked(false);
                    b.setDisabled(!ctl.enabled.get());
                    setDrawable(new TextureRegionDrawable(
                            def.get().uiIcon,
                            4f / Math.min(def.get().uiIcon.width, def.get().uiIcon.height)
                    ));
                });
            }}).grow()).margin(4).size(50).padRight(10).get();
            b.add("@"+tlKey);
        }, () -> new StatusSelectDialog("@" + tlKey).show(ctl.filter, status -> {
            Image image = (Image) ((Stack) (table[0].getChildren().get(0))).getChildren().get(1);
            image.setDrawable(new TextureRegionDrawable(status.uiIcon, 4f / Math.min(status.uiIcon.width, status.uiIcon.height)));
            onClick.get(status);
        }, def.get())).left().width(300f).padLeft(6);
        correctiveSpace().row();

        return ctl;
    }

    public static class TeamCtl {
        private Boolp enabled = TRUE;

        public TeamCtl enabled(Boolp value) {
            enabled = value;
            return this;
        }
    }
    public TeamCtl team(String tlKey, Cons<Team> onClick, Prov<Team> def) {
        if (!shouldAdd(tlKey)) return new TeamCtl();

        TeamCtl ctl = new TeamCtl();
        final Table[] table = new Table[1];

        correctiveSpace();
        root.table(t -> {
            t.left();
            t.label(() -> "@"+tlKey);

            final TextField field = new TextField();
            field.setValidator(text -> {
                if (!Strings.canParsePositiveInt(text)) return false;
                int v = Strings.parseInt(text);
                return v >= 0 && v < 255;
            });
            field.update(() -> {
                field.setDisabled(!ctl.enabled.get());
            });
            field.changed(() -> {
                if (!field.isValid()) return;
                Team team = Team.all[Strings.parseInt(field.getText())];
                field.color.set(team.color);
                onClick.get(team);
            });
            field.color.set(def.get().color);
            field.setText("" + def.get().id);

            for (Team team : Team.baseTeams) {
                ImageButton button = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
                button.setSize(32, 32);
                button.margin(4);
                button.getImageCell().grow();
                button.update(() -> {
                    button.setChecked(def.get() == team);
                    button.setDisabled(!ctl.enabled.get());
                });
                button.getStyle().imageUpColor = team.color;
                button.clicked(() -> {
                    onClick.get(team);
                    field.setText("" + team.id);
                    field.color.set(team.color);
                });
                t.add(button).size(50, 50).left();
            }

            t.add(field).width(70).pad(2);
        }).left().padLeft(6);
        correctiveSpace().row();

        return ctl;
    }

    public void platformSources(String tlKey,
                                Prov<Seq<mindustry.client.morj.mk.util.Schematic>> def,
                                Cons<mindustry.client.morj.mk.util.Schematic> onAdd,
                                Cons<Integer> onRemove) {
        if (!shouldAdd(tlKey)) return;

        final Table[] rowsTable = {null};

        correctiveSpace();
        root.table(outer -> {
            outer.left();
            outer.add("@" + tlKey).left().padRight(5).marginTop(0).marginBottom(0);
            outer.table(inner -> {
                rowsTable[0] = inner;
                rebuildPlatformSourceRows(inner, def, onAdd, onRemove, tlKey);
            }).left();
        }).padTop(0).pad(6).left();
        correctiveSpace().row();
        root.row();
    }

    private void rebuildPlatformSourceRows(Table t,
                                           Prov<Seq<mindustry.client.morj.mk.util.Schematic>> def,
                                           Cons<mindustry.client.morj.mk.util.Schematic> onAdd,
                                           Cons<Integer> onRemove,
                                           String tlKey) {
        t.clear();
        t.defaults().left();

        t.button("+", () -> {
            MVars.mapView.editorAction = new mindustry.client.morj.mk.rules.SchematicEditorAction(
                    mindustry.client.morj.mk.rules.Castle.PLATFORM_SIZE,
                    mindustry.client.morj.mk.rules.Castle.PLATFORM_SIZE,
                    scheme -> {
                        onAdd.get(scheme);
                        rebuildPlatformSourceRows(t, def, onAdd, onRemove, tlKey);
                    }
            );
            MVars.customRulesDialog.hide();
        }).size(32f).padBottom(2).left().row();

        Seq<mindustry.client.morj.mk.util.Schematic> list = def.get();
        for (int idx = 0; idx < list.size; idx++) {
            final int i = idx;
            StringBuilder platformString = new StringBuilder();
            StringBuilder stringTemp = new StringBuilder();
            Seq<Floor> floors = Seq.with(list.get(i).floors);
            int count = 0;
            for (int j = floors.size - 1; j >= 0; j--) {
                stringTemp.insert(0,floors.get(j).emoji());
                if (++count % 5 == 0){
                    platformString.append(stringTemp+"\n");
                    stringTemp.setLength(0);
                }
            }
            t.table(row -> {
                row.add().size(32f).padRight(4);
                row.label(() -> (i+1) + ". \n" + platformString).left().padRight(8);
                row.button("-", () -> {
                    onRemove.get(i);
                    rebuildPlatformSourceRows(t, def, onAdd, onRemove, tlKey);
                }).size(32f).padLeft(4).left();
            }).left().row();
        }
    }

    public void spacer() {
        if (!canPlaceSpacer) return;
        canPlaceSpacer = false;

        root.table().fillX().height(16f).minHeight(16f).maxHeight(16f);
        root.row();
    }

    public RulesWrite table() {
        RulesWrite write = new RulesWrite(new Table(), filter);
        write.parent = this;
        write.canPlaceSpacer = canPlaceSpacer;
        write.root.left().defaults().left().pad(0);
        correctiveSpace().width(0);
        root.add(write.root).fillX().left();
        correctiveSpace().width(0).row();
        return write;
    }

    public RulesWrite category(String name) {
        RulesWrite write = new RulesWrite(new Table(), filter);
        write.parent = this;
        write.categoryNameToAdd = name;
        canPlaceSpacer = false;
        write.root.left().defaults().left().pad(0);
        return write;
    }
    public void category(String name, Cons<RulesWrite> cb) {
        cb.get(category(name));
    }

    public TreeNode tree(String label, Cons<RulesWrite> contents, boolean expanded) {
        final TreeNode node = new TreeNode(label, expanded);

        correctiveSpace();
        root.button(label, Icon.downOpen, Styles.togglet, () -> node.shown = !node.shown)
                .width(260).height(55).update(t -> {
                    ((Image) t.getChildren().get(1)).setDrawable(node.buttonIcon());
                    t.setChecked(node.shown);
                }).left().padLeft(14).padBottom(2);
        correctiveSpace().row();

        final Table contentTable = new Table();
        contentTable.left();
        contentTable.defaults().left();

        correctiveSpace();
        root.collapser(c -> {
            c.add(contentTable).padLeft(14).left().growX().row();
        }, () -> node.shown).left().padBottom(2).fillX();
        correctiveSpace().row();

        final RulesWrite child = new RulesWrite(contentTable, filter);
        contents.get(child);

        return node;
    }

    public TreeNode tree(String label, Cons<RulesWrite> contents) {
        return tree(label, contents, false);
    }

    public interface AddTreeEntry<T> {
        void add(String label, T value);
    }

    public <T> void treeAddEntry(String tlKey,
                                 Cons<AddTreeEntry<T>> addEntry,
                                 Cons<T> onClick,
                                 T def) {
        treeAddEntry(tlKey, addEntry, onClick, def, (value, child) -> {});
    }

    public <T> void treeAddEntry(String tlKey,
                                 Cons<AddTreeEntry<T>> addEntry,
                                 Cons<T> onClick,
                                 T def,
                                 Cons2<T, RulesWrite> sectionConf) {
        if (!shouldAdd(tlKey)) return;

        final Table container = new Table();
        correctiveSpace();
        root.add(container).fillX();
        correctiveSpace().row();
        container.defaults().left();

        class AddRow {
            boolean shown = false;
            final Table mainContainer = new Table();

            TextureRegionDrawable buttonIcon() { return shown ? Icon.downOpen : Icon.upOpen; }

            AddRow() {
                final Button headerButton = new Button(Styles.togglet);

                final Image arrowIcon = new Image(Icon.upOpen);
                headerButton.add(arrowIcon).size(Icon.upOpen.imageSize() / Scl.scl(1)).padLeft(4);
                headerButton.add(new Image(Icon.add)).size(Icon.add.imageSize() / Scl.scl(1)).padLeft(6);

                headerButton.clicked(() -> {
                    shown = !shown;
                    arrowIcon.setDrawable(buttonIcon());
                    headerButton.setChecked(shown);
                });
                headerButton.update(() -> {
                    arrowIcon.setDrawable(buttonIcon());
                    headerButton.setChecked(shown);
                });

                mainContainer.add(headerButton)
                        .marginLeft(14).height(55).padBottom(2).left().row();

                mainContainer.collapser(picker -> {
                    picker.defaults().left();
                    picker.table(Tex.button, t -> {
                        t.margin(10f);
                        t.defaults().size(140f, 50f);
                        final Seq<TextButton> buttons = new Seq<>();
                        final ButtonGroup<?> group = new ButtonGroup<>();

                        addEntry.get((label, value) -> {
                            @SuppressWarnings("unchecked")
                            Cell<TextButton>[] btn = new Cell[1];
                            btn[0] = t.button(label, Styles.flatTogglet, () -> {
                                buttons.each(b -> b.setChecked(false));
                                btn[0].get().setChecked(true);
                                onClick.get(value);

                                final Table nodeTable = new Table();
                                nodeTable.defaults().left();
                                final TreeNode node = new TreeNode(label, true);

                                nodeTable.button(label, Icon.downOpen, Styles.togglet,
                                                () -> node.shown = !node.shown)
                                        .marginLeft(14).width(260).height(55).update(th -> {
                                            ((Image) th.getChildren().get(1)).setDrawable(node.buttonIcon());
                                            th.setChecked(node.shown);
                                        }).left().padBottom(2).row();

                                nodeTable.collapser(c -> {
                                    c.defaults().left();
                                    RulesWrite child = new RulesWrite(c, filter);
                                    child.parent = RulesWrite.this;
                                    sectionConf.get(value, child);
                                }, () -> node.shown).left().padBottom(2).fillX().row();

                                container.removeChild(mainContainer);
                                container.add(nodeTable).fillX().row();
                                container.add(mainContainer).fillX().row();

                                shown = false;
                            }).group(group);

                            btn[0].get().setChecked(value == null ? def == null : value.equals(def));
                            if (buttons.size % 3 == 2) btn[0].row();
                            buttons.add(btn[0].get());
                        });
                    }).left().pad(6).fill(false).expand(false, false).row();
                }, () -> shown).left().padBottom(2).fillX().row();

                container.add(mainContainer).fillX().row();
            }
        }

        new AddRow();
    }

    public static class TreeNode {
        public boolean shown;
        private final String label;

        TreeNode(String label, boolean shown) {
            this.label = label;
            this.shown = shown;
        }

        TextureRegionDrawable buttonIcon() {
            return shown ? Icon.downOpen : Icon.upOpen;
        }

        public String label() { return label; }
    }
}
