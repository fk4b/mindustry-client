package mindustry.client.morj.mk.rules;

import arc.struct.IntMap;
import arc.util.Log;
import arc.util.Strings;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.client.morj.mk.util.FormatException;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.maps.Map;

public class FortsPlotKindSquare extends FortsPlotKind {
    public static final String NAME = "square";
    public static final String SIZE = PREFIX+".size";
    public static final String WALLS_SIZE = PREFIX+".walls_size";
    public static final String SHIFT_X = PREFIX+".shift_x";
    public static final String SHIFT_Y = PREFIX+".shift_y";
    public static final String DATA = PREFIX+".states";
    public static final String SCHEMATIC_HEAD = PREFIX+".schematic.";
    public static boolean keyIsSchematic(String key) {
        return Util.keyHasHeadByte(key, SCHEMATIC_HEAD);
    }
    private static String keySchematic(Team team) {
        return PREFIX+".schematic."+team.id;
    }
    private static Team keySchematicTeam(String key) {
        return Team.all[Util.keyHeadByte(key, SCHEMATIC_HEAD)];
    }

    public class Impl extends FortsPlotKind.Impl implements FortsPlotKindRectangular {
        protected Impl(RulesContext rc) {
            super(rc);

            final Rules rules = rc.rules;

            String plotStatesS;

            try (TagRead read = TagRead.of(rules)) {
                size = read.r(SIZE, 6);
                wallsSize = read.r(WALLS_SIZE, 1);
                shiftX = read.r(SHIFT_X, 0);
                shiftY = read.r(SHIFT_Y, 0);
                plotStatesS = read.r(DATA, "");

                for (String key : rules.tags.keys()) {
                    if (!keyIsSchematic(key)) continue;
                    Team team = keySchematicTeam(key);

                    try {
                        Schematic scheme = Schematic.of(rules.tags.get(key));
                        if (scheme != Schematic.EMPTY) plotSchematic.put(team.id, scheme);
                    } catch (FormatException e) {
                        Vars.ui.showException("Failed to parse plot scheme for team " + team.coloredName(), e);
                        Log.err("Failed to parse plot scheme for team " + team.id, e);
                    }
                }
            }

            plotStates = new FortsRectangularStates(size, size, wallsSize, shiftX, shiftY,
                    rc.mapWidth, rc.mapHeight, plotStatesS);
        }

        private void changePlotStates() {
            String plotStatesS;

            try (TagRead read = TagRead.of(rc.rules)) {
                plotStatesS = read.r(DATA, "");
            }

            plotStates = new FortsRectangularStates(size, size, wallsSize, shiftX, shiftY,
                    rc.mapWidth, rc.mapHeight, plotStatesS);
        }

        @Override
        public void onStart() {
            plotStates.placeDefaultPlots(this::plotSchematic);
        }

        @Override
        public void editingResumed() {
            plotStates.removeAllPlots();
        }

        @Override
        public void save() {
            plotStates.save();
        }

        private int size;
        public int size() { return size; }
        @Override public int plotWidth() { return size; }
        @Override public int plotHeight() { return size; }
        public Impl size(int value) {
            size = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(SIZE, value); }
            changePlotStates();
            return this;
        }

        private int wallsSize;
        @Override public int wallsSize() { return wallsSize; }
        public Impl wallsSize(int value) {
            wallsSize = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(WALLS_SIZE, value); }
            changePlotStates();
            return this;
        }

        private int shiftX;
        @Override public int shiftX() { return shiftX; }
        public Impl shiftX(int value) {
            shiftX = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(SHIFT_X, value); }
            changePlotStates();
            return this;
        }

        private int shiftY;
        @Override public int shiftY() { return shiftY; }
        public Impl shiftY(int value) {
            shiftY = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(SHIFT_Y, value); }
            changePlotStates();
            return this;
        }

        private final IntMap<Schematic> plotSchematic = new IntMap<>();
        public Schematic plotSchematic(Team team) {
            return plotSchematic.get(team.id, Schematic.EMPTY);
        }
        public Impl plotSchematic(Team team, Schematic value) {
            if (value == null) {
                rc.rules.tags.remove(keySchematic(team));
                plotSchematic.remove(team.id);
            }
            else {
                Log.info("Writing schematic for team "+team.name+", size=("+value.width+"x"+value.height+")");
                plotSchematic.put(team.id, value);
                try (TagWrite write = TagWrite.of(rc.rules)) { write.w(keySchematic(team), value); }
            }
            return this;
        }

        private FortsRectangularStates plotStates;

        @Override
        void remove() {
            rc.rules.tags.remove(SIZE);
            rc.rules.tags.remove(WALLS_SIZE);
            rc.rules.tags.remove(SHIFT_X);
            rc.rules.tags.remove(SHIFT_Y);
            rc.rules.tags.remove(DATA);
            rc.rules.tags.keys().toSeq().each(FortsPlotKindSquare::keyIsSchematic, rc.rules.tags::remove);
        }

        @Override
        public void writeRules(RulesWrite write) {
            write.i("rules.mindurka.forts.plot.size", this::size, this::size);
            write.i("rules.mindurka.forts.plot.wall", this::wallsSize, this::wallsSize);
            write.i("rules.mindurka.forts.plot.shiftX", this::shiftX, this::shiftX);
            write.i("rules.mindurka.forts.plot.shiftY", this::shiftY, this::shiftY);
        }

        @Override
        public void writeTeamRules(Team team, RulesWrite write) {
            write.button("rules.mindurka.forts.plot.schematic", () -> {
                MVars.mapView.editorAction = new SchematicEditorAction(
                        wallsSize() * 2 + size, wallsSize() * 2 + size,
                        scheme -> plotSchematic(team, scheme)
                );
                MVars.customRulesDialog.hide();
            });
        }

        @Override
        public void drawEditorGuides() {
            plotStates.drawEditorGuides();
        }

        @Override
        public void setPlotInfo(int x, int y, FortsPlotState state, Team team) {
            plotStates.setPlotInfo(x, y, state, team);
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(DATA, plotStates.save()); }
        }
    }

    @Override public String name() { return NAME; }

    @Override
    Impl create(RulesContext rc) {
        return new Impl(rc);
    }
}
