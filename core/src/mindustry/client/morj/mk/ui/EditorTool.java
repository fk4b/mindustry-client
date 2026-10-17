package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.func.Boolp;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.struct.LongSeq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.rules.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.Iterator;

import static mindustry.ui.Styles.squareTogglei;

public enum EditorTool {
    // How we assign default tool keys:
    // - System stuff take the bottom row
    // - Basic drawing takes the middle row
    // - Gamemode-specific stuff takes the top row
    // - Preferably keys close to the right so you can actually use them
    // - Fuck mnemonics
    // - Whatever feels right tbh
    // - Also undo is bound to 'z' and redo to 'Z'

    pencil(KeyCode.a) { // This is useful on not just mobile. Use left click.
        @Override
        public void start(ToolContext ctx) {
            mapbits(ctx).zero();
        }

        @Override
        public void touched(ToolContext ctx, int oldx, int oldy, int newx, int newy) {
            BitMap bits = mapbits(ctx);

            EditorTool.line(oldx, oldy, newx, newy, (x, y) -> {
                EditorTool.circle(x, y, MVars.toolOptions.radius, (x$1, y$1) -> {
                    if (ctx instanceof EraseToolContext) {
                        if (bits.toggled(x$1, y$1)) return;
                        bits.enable(x$1, y$1);
                    } else if (!ctx.unsizedBlocks() && !(ctx instanceof PreviewToolContext)) {
                        if (EditorTool.squareAny(x$1, y$1, MVars.toolOptions.selectedBlock().size, bits::toggled)) return;
                        EditorTool.square(x$1, y$1, MVars.toolOptions.selectedBlock().size, bits::enable);
                    }
                    ctx.setAny(x$1, y$1, MVars.toolOptions.selectedBlock());
                });
            });
        }
    },
    line(KeyCode.s) { // I ran out of mouse buttons. Idk, button 4 or smth.
        {
            draggable = Drag.Line;
        }

        @Override
        public void touched(ToolContext ctx, int oldx, int oldy, int newx, int newy) {
            if (!(ctx instanceof PreviewToolContext)) return;
            ctx.setAny(newx, newy, MVars.toolOptions.selectedBlock());
        }

        @Override
        public void touchedLine(ToolContext ctx, int x1, int y1, int x2, int y2) {
            boolean shift = Core.input.keyDown(KeyCode.shiftLeft) || Core.input.keyDown(KeyCode.shiftRight);

            BitMap bits = mapbits(ctx);
            bits.zero();

            if (shift) {
                if (Math.abs(x1 - x2) < Math.abs(y1 - y2)) x2 = x1;
                else y2 = y1;
            }

            EditorTool.line(x1, y1, x2, y2, (x, y) -> {
                EditorTool.circle(x, y, MVars.toolOptions.radius, (x$1, y$1) -> {
                    if (!ctx.unsizedBlocks()) {
                        if (EditorTool.squareAny(x$1, y$1, MVars.toolOptions.selectedBlock().size, bits::toggled)) return;
                        EditorTool.square(x$1, y$1, MVars.toolOptions.selectedBlock().size, bits::enable);
                    }
                    ctx.setAny(x$1, y$1, MVars.toolOptions.selectedBlock());
                });
            });
        }
    },
    fill(KeyCode.d) { // Button 5?
        @Override
        public void start(ToolContext ctx) {
            mapbits(ctx).zero();
        }

        @Override
        public void touched(ToolContext ctx, int x1, int y1, int x2, int y2) {
            if (!ctx.isLayer()) {
                ctx.setAny(x2, y2, MVars.toolOptions.selectedBlock());
                return;
            }
            BitMap bits = mapbits(ctx);

            LongSeq points = new LongSeq();

            EditorTool.line(x1, y1, x2, y2, (x, y) -> {
                if (x < 0 || y < 0 || x >= ctx.width() || y >= ctx.height()) return;
                Block selected = MVars.toolOptions.selectedBlock();
                boolean isBlock = !selected.isOverlay() && !selected.isFloor();
                Block target = selected.isOverlay()
                        ? ctx.overlay(x, y)
                        : selected.isFloor()
                        ? ctx.floor(x, y)
                        : ctx.block(x, y);
                Team targetTeam = isBlock ? ctx.team(x, y) : null;
                if (ctx.block(x, y) == selected && (!isBlock || ctx.team(x, y) == MVars.toolOptions.team())) return;

                points.add(Util.packxy(x2, y2));

                while (!points.isEmpty()) {
                    long point = points.pop();
                    int px = Util.unpackx(point);
                    int py = Util.unpacky(point);
                    if (bits.toggled(px, py)) continue;
                    if (px < 0 || py < 0 || px >= ctx.width() || py >= ctx.height()) continue;
                    Block at = selected.isOverlay()
                            ? ctx.overlay(px, py)
                            : selected.isFloor()
                            ? ctx.floor(px, py)
                            : ctx.block(px, py);
                    if (at != target) continue;
                    if (isBlock && ctx.team(px, py) != targetTeam) continue;
                    ctx.setAny(px, py, selected);
                    bits.enable(px, py);

                    points.add(Util.packxy(px + 1, py));
                    points.add(Util.packxy(px - 1, py));
                    points.add(Util.packxy(px, py + 1));
                    points.add(Util.packxy(px, py - 1));
                }
            });
        }
    },
    rect(KeyCode.f) {
        {
            draggable = Drag.Line;
        }

        @Override
        public void touched(ToolContext ctx, int oldx, int oldy, int newx, int newy) {
            if (!(ctx instanceof PreviewToolContext)) return;
            ctx.setAny(newx, newy, MVars.toolOptions.selectedBlock());
        }

        @Override
        public void touchedLine(ToolContext ctx, int x1, int y1, int x2, int y2) {
            boolean shift = Core.input.keyDown(KeyCode.shiftLeft) || Core.input.keyDown(KeyCode.shiftRight);

            Block block = MVars.toolOptions.selectedBlock();
            int size = block.size;
            int shiftx = x1 % size;
            int shifty = y1 % size;

            if (shift) {
                int dst = Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2)) / size * size;
                int signx = x2 >= x1 ? 1 : -1;
                int signy = y2 >= y1 ? 1 : -1;

                x2 = x1 + dst * signx;
                y2 = y1 + dst * signy;
            }

            int minx = Math.min(x1, x2) / size * size + shiftx;
            int miny = Math.min(y1, y2) / size * size + shifty;
            int maxx = Math.max(x1, x2) / size * size + shiftx;
            int maxy = Math.max(y1, y2) / size * size + shifty;

            for (int i = minx; i <= maxx; i += size) for (int o = miny; o <= maxy; o += size) ctx.setAny(i, o, block);
        }
    },
    // TODO: Make this thing work or smth.
    clipboard(KeyCode.c),

    // TODO: Make those into modes.
    // Forts tools.
    fortsPlotCarver(KeyCode.q) {
        {
            lockedBehind = Gamemodes.forts;
            blockTool = false;
            visibleIf = () -> !((Forts.Impl) MVars.rules.gamemode()).plotKind().name().equals("none");
        }
    },
    fortsPlotToggle(KeyCode.w) {
        {
            lockedBehind = Gamemodes.forts;
            blockTool = false;
            visibleIf = () -> !((Forts.Impl) MVars.rules.gamemode()).plotKind().name().equals("none");
        }

        @Override
        public void touched(ToolContext ctx, int oldx, int oldy, int newx, int newy) {
            if (!ctx.isLayer()) return;

            EditorTool.line(oldx, oldy, newx, newy, (x, y) -> {
                if (x < 0 || y < 0 || x >= ctx.width() || y >= ctx.height()) return;
                ((Forts.Impl) MVars.rules.gamemode())
                        .plotKind().setPlotInfo(x, y,
                                (ctx instanceof EraseToolContext)
                                        ? FortsPlotState.disabled
                                        : MVars.toolOptions.fortsPlotState,
                                MVars.toolOptions.team());
            });
        }

        @Override
        public void toolOptions(Table table) {
            float size = Vars.mobile ? 50f : 58f;

            table.label(() -> "@editor.mindurka.plotstate").left().pad(6).row();
            table.table(t -> {
                for (FortsPlotState state : FortsPlotState.values()) {
                    ImageButton button = new ImageButton(Vars.ui.getIcon("forts-plot-" + state), Styles.squareTogglei);
                    button.clicked(() -> MVars.toolOptions.fortsPlotState = state);
                    button.update(() -> button.setChecked(MVars.toolOptions.fortsPlotState == state));
                    t.add(button).size(size, size).left().tooltip("@plotstate." + state);
                }
            }).growX().left().row();
        }

        @Override
        public void stopped(ToolContext ctx) {
            ((Forts.Impl) MVars.rules.gamemode()).plotKind().save();
        }
    },

    // Hub tools.
    hubServerConfig(KeyCode.q) {
        {
            lockedBehind = Gamemodes.hub;
            blockTool = false;
            draggable = Drag.Line;
        }

        @Override
        public void toolOptions(Table table) {
            float size = Vars.mobile ? 50f : 58f;

            table.label(() -> "@editor.mindurka.servername").left().pad(6).row();
            table.field(MVars.toolOptions.hubServer, text -> {
                MVars.toolOptions.hubServer = text;
            }).growX().left().row();
        }

        @Override
        public void touchedLine(ToolContext ctx, int x1, int y1, int x2, int y2) {
            int size = Math.min(Math.abs(x1 - x2), Math.abs(y1 - y2));
            int dx = Integer.compare(x2, x1);
            int dy = Integer.compare(y2, y1);

            // This is on purpose, yes.
            if (ctx.isErase()) {
                MVars.rules.<Hub.Impl>gamemode().servers().removeAll(server -> server.contains(x1, y1));
                MVars.rules.<Hub.Impl>gamemode().saveServers();
            } else {
                if (ctx.isLayer()) {
                    Hub.Server server = new Hub.Server(MVars.toolOptions.hubServer, Math.min(x1, x2), Math.min(y1, y2), size + 1);
                    MVars.rules.<Hub.Impl>gamemode().addServer(server);
                } else for (int i = 0; i <= size; i++) for (int o = 0; o <= size; o++) {
                    int x = x1 + i * dx;
                    int y = y1 + o * dy;

                    ctx.setBlock(x, y, Blocks.tileLogicDisplay);
                }
            }
        }
    },
    castleRoomPlace(KeyCode.q) {
        {
            lockedBehind = Gamemodes.castle;
            blockTool = false;
        }

        @Override
        public void toolOptions(Table table) {
            float size = Vars.mobile ? 50f : 58f;

            table.label(() -> "@rules.mindurka.castle.block.cost").left().pad(6).row();
            table.field(String.valueOf(MVars.toolOptions.blockCostFor(MVars.toolOptions.current.selectedBlock)), text -> {
                if(!text.isEmpty())MVars.toolOptions.blockCost = Integer.parseInt(text);
            }).growX().left().row();
            table.label(() -> "@rules.status.invincible").left().pad(6).row();
            table.field(String.valueOf(MVars.toolOptions.invincible || MVars.toolOptions.current.selectedBlock instanceof Turret), text -> {
                if(!text.isEmpty())MVars.toolOptions.invincible = Boolean.parseBoolean(text);
            }).growX().left().row();
        }

        @Override
        public void start(ToolContext ctx) {
            mapbits(ctx).zero();
        }

        @Override
        public void touched(ToolContext ctx, int x1, int y1, int x2, int y2) {
            try {
                if (ctx instanceof PreviewToolContext) {
                    int size = MVars.toolOptions.current.selectedBlock.size;
                    int bx = x2 - size / 2;
                    int by = y2 - size / 2;
                    for (int dx = 0; dx < size; dx++) {
                        for (int dy = 0; dy < size; dy++) {
                            ((PreviewToolContext) ctx).bitmap.enable(bx + dx, by + dy);
                        }
                    }
                    PreviewToolContext p = (PreviewToolContext) ctx;
                    p.hasRegion = true;
                    p.startx = bx;
                    p.starty = by;
                    p.endx = bx + size - 1;
                    p.endy = by + size - 1;
                    return;
                }
                if (ctx.isErase()) {
                    MVars.rules.<Castle.Impl>gamemode().blocks().removeAll(block -> block.contains(x1, y1));
                    MVars.rules.<Castle.Impl>gamemode().saveBlocks();
                } else {
                    if (ctx.isLayer()) {
                        int bx = x1;
                        int by = y1;
                        Castle.CastleBlock b = new Castle.CastleBlock(MVars.toolOptions.current.selectedBlock, bx, by, MVars.toolOptions.blockCost, MVars.toolOptions.invincible || MVars.toolOptions.current.selectedBlock instanceof Turret);
                        MVars.rules.<Castle.Impl>gamemode().placeBlock(b);
                    }
                }
            }catch (NumberFormatException | NullPointerException ignored) {}//idc
        }
    },
    castleMinerPlacer(KeyCode.w) {
        {
            lockedBehind = Gamemodes.castle;
            blockTool = false;
        }

        @Override
        public void toolOptions(Table table) {
            float size = Vars.mobile ? 50f : 58f;

            table.label(() -> "@rules.mindurka.castle.item.interval").left().pad(6).row();
            table.field(String.valueOf(MVars.toolOptions.minerIntervalFor(MVars.toolOptions.selectedItemCastle)), text -> {
                MVars.toolOptions.minerInterval = Integer.parseInt(text);
            }).growX().left().row();
            table.label(() -> "@rules.mindurka.castle.item.amount").left().pad(6).row();
            table.field(String.valueOf(MVars.toolOptions.minerAmountFor(MVars.toolOptions.selectedItemCastle)), text -> {
                MVars.toolOptions.minerAmount = Integer.parseInt(text);
            }).growX().left().row();
            table.label(() -> "@rules.mindurka.castle.item.cost").left().pad(6).row();
            table.field(String.valueOf(MVars.toolOptions.minerCostFor(MVars.toolOptions.selectedItemCastle)), text -> {
                MVars.toolOptions.minerCost = Integer.parseInt(text);
            }).growX().left().row();
            table.label(() -> "@rules.mindurka.castle.item").left().pad(6).row();
            table.table(t -> {
                int i = 0;
                for (Item item : Vars.content.items()) {
                    if(i%6==0) t.row();
                    ImageButton button = new ImageButton(item.fullIcon, Styles.squareTogglei);
                    button.clicked(() -> {
                        MVars.toolOptions.selectedItemCastle = item;
                        MVars.editorDialog.rebuildBlockOptions();
                    });
                    button.update(() -> button.setChecked(MVars.toolOptions.selectedItemCastle == item));
                    t.add(button).size(size, size).left().tooltip(item.localizedName);
                    i++;
                }
            }).growX().left().row();
        }

        @Override
        public void start(ToolContext ctx) {
            mapbits(ctx).zero();
        }

        @Override
        public void touched(ToolContext ctx, int x1, int y1, int x2, int y2) {
            try {
                if (ctx instanceof PreviewToolContext) {
                    int size = MVars.toolOptions.current.selectedBlock.size;
                    int bx = x2 - size / 2;
                    int by = y2 - size / 2;
                    for (int dx = 0; dx < size; dx++) {
                        for (int dy = 0; dy < size; dy++) {
                            ((PreviewToolContext) ctx).bitmap.enable(bx + dx, by + dy);
                        }
                    }
                    PreviewToolContext p = (PreviewToolContext) ctx;
                    p.hasRegion = true;
                    p.startx = bx;
                    p.starty = by;
                    p.endx = bx + size - 1;
                    p.endy = by + size - 1;
                    return;
                }
                if (ctx.isErase()) {
                    ((Castle.Impl) MVars.rules.gamemode()).miners().removeAll(miner -> miner.contains(x1, y1));
                    ((Castle.Impl) MVars.rules.gamemode()).saveMiners();
                } else {
                    if (ctx.isLayer()) {
                        int bx = x1;
                        int by = y1;
                        Castle.CastleMiner b = new Castle.CastleMiner(MVars.toolOptions.current.selectedBlock, bx, by, MVars.toolOptions.minerCost, MVars.toolOptions.minerAmount, MVars.toolOptions.minerInterval,MVars.toolOptions.selectedItemCastle);
                        ((Castle.Impl) MVars.rules.gamemode()).addMiner(b);
                    }
                }
            } catch (NumberFormatException | NullPointerException ignored) {}//idc
        }
    },

    ;

    public enum Drag {
        Touch,
        Line,
        /**
         * Uses some cursed way to determine whether you're touching or
         * dragging and triggers the corresponding function accordingly.
         */
        Both,
    }

    EditorTool(KeyCode key) {
        // Maybe I'll let you rebind it later. Maybe.
        // TODO: Add this shit to settings for extra bloat.
        defaultKey = key;
    }

    private static BitMap mapbits(ToolContext ctx) {
        if (ctx instanceof PreviewToolContext) return MVars.mapbits2();
        return MVars.mapbits();
    }

    public boolean blockTool = true;
    private final KeyCode defaultKey;
    public Drag draggable = Drag.Touch;
    public @Nullable Gamemode lockedBehind = null;
    public @Nullable Boolp visibleIf = null;

    public KeyCode key() {
        return defaultKey;
    }

    public void start(ToolContext ctx) {}
    public void touched(ToolContext ctx, int oldx, int oldy, int newx, int newy) {}
    public void touchedLine(ToolContext ctx, int x1, int y1, int x2, int y2) { touched(ctx, x2, y2, x2, y2); }
    public void stopped(ToolContext ctx) {}

    public void toolOptions(Table table) {}

    public interface Consii {
        void get(int x, int y);
    }
    public interface Funcbii {
        boolean get(int x, int y);
    }

    public static void line(int startx, int starty, int endx, int endy, Consii cb) {
        float dist = Mathf.dst(startx, starty, endx, endy);
        float dx = (endx - startx) / dist;
        float dy = (endy - starty) / dist;

        int px = -1;
        int py = -1;

        float x = startx;
        float y = starty;

        cb.get(startx, starty);
        for (float i = 0; i < dist; i++) {
            int nx = Mathf.round(x);
            int ny = Mathf.round(y);

            x += dx;
            y += dy;

            if (nx != px || ny != py) {
                px = nx;
                py = ny;
                if (nx != startx || ny != starty) cb.get(nx, ny);
            }
        }
        if (startx != endx || starty != endy) cb.get(endx, endy);
    }

    public static int dst2(int x1, int y1, int x2, int y2) {
        return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2);
    }

    public static void circle(int x, int y, int r, Consii cb) {
        cb.get(x, y);
        for (int i = 1; i < r; i++) {
            for (int o = -i; o <= i; o++)
                if (dst2(x, y, x + o, y + i) <= r * r)
                    cb.get(x + o, y + i);
            for (int o = -i; o < i; o++)
                if (dst2(x, y, x + i, y + o) <= r * r)
                    cb.get(x + i, y + o);
            for (int o = -i; o < i; o++)
                if (dst2(x, y, x - i, y + o) <= r * r)
                    cb.get(x - i, y + o);
            for (int o = -i + 1; o < i; o++)
                if (dst2(x, y, x - o, y - i) <= r * r)
                    cb.get(x - o, y - i);
        }
    }

    public static void square(int x, int y, int size, Consii cb) {
        // I spent days debugging a `-` let's go
        x += size / 2 - size + 1;
        y += size / 2 - size + 1;

        for (int dx = 0; dx < size; dx++) for (int dy = 0; dy < size; dy++)
            cb.get(x + dx, y + dy);
    }

    public static boolean squareAny(int x, int y, int size, Funcbii cb) {
        x += size / 2 - size + 1;
        y += size / 2 - size + 1;

        for (int dx = 0; dx < size; dx++) for (int dy = 0; dy < size; dy++)
            if (cb.get(x + dx, y + dy)) return true;
        return false;
    }
}
