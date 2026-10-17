package mindustry.client.morj.mk.rules;

import arc.Core;
import arc.func.Func;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import arc.struct.ByteSeq;
import arc.struct.IntMap;
import arc.util.Log;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.Vars;
import mindustry.game.Team;
import net.jpountz.lz4.LZ4Factory;

import java.util.Arrays;
import java.util.Base64;

public class FortsRectangularStates {
    private final Color fillColor = new Color();

    private final FortsPlotState[] states;
    private final Team[] teams;
    private final int startX;
    private final int startY;
    private final int wallSize;
    private final int jX;
    private final int jY;
    private final int plotsX;
    private final int plotsY;
    private final int width;
    private final int height;

    private IntMap<Schematic> centerParts;
    private IntMap<Schematic> horizontalWalls;
    private IntMap<Schematic> verticalWalls;
    private IntMap<Schematic> intersectionParts;

    public FortsRectangularStates(int width, int height, int wallSize, int shiftX, int shiftY,
                                  int mapWidth, int mapHeight, String statesData) {
        this.wallSize = wallSize;

        jX = width + wallSize;
        jY = height + wallSize;

        startX = ((shiftX % jX) + jX) % jX;
        startY = ((shiftY % jY) + jY) % jY;

        plotsX = (mapWidth - startX + wallSize) / jX;
        plotsY = (mapHeight - startY + wallSize) / jY;

        states = new FortsPlotState[plotsX * plotsY];
        teams = new Team[plotsX * plotsY];
        Arrays.fill(states, FortsPlotState.enabled);
        Arrays.fill(teams, Team.derelict);

        this.width = width;
        this.height = height;

        if (statesData.startsWith("x0")) {
            try {
                byte[] data = LZ4Factory.fastestInstance().safeDecompressor().decompress(Base64.getDecoder().decode(statesData.substring(2)), 1024 * 64);
                int ptr = 0;

                for (int i = 0; i < states.length; i++) {
                    int ordinal = data[ptr++] & 0xff;
                    if (ordinal >= FortsPlotState.values().length) {
                        Log.warn("Invalid plot state! ("+ordinal+" >= "+FortsPlotState.values().length+")");
                        Arrays.fill(states, FortsPlotState.enabled);
                        Arrays.fill(teams, Team.derelict);
                        return;
                    }
                    states[i] = FortsPlotState.values()[ordinal];
                    if (states[i].placed()) teams[i] = Team.all[data[ptr++] & 0xff];
                }

                if (ptr != data.length) {
                    Log.warn("Invalid plot states format! Did not reach the end of data.");
                    Arrays.fill(states, FortsPlotState.enabled);
                    Arrays.fill(teams, Team.derelict);
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                Log.warn("Invalid plot states format!");
                Arrays.fill(states, FortsPlotState.enabled);
                Arrays.fill(teams, Team.derelict);
            }
        } else {
            if (statesData.length() != states.length * 3) {
                Log.warn("Incorrect plot states length! ("+statesData.length()+" vs "+(states.length * 3)+")");
                return;
            }
            for (int i = 0; i < states.length; i++) {
                char ch = statesData.charAt(i * 3);
                if (ch < 'a' || ch - 'a' >= FortsPlotState.values().length) {
                    Log.warn("Invalid plot state at position "+(i * 3)+"!");
                    return;
                }

                char a = statesData.charAt(i * 3 + 1);
                char b = statesData.charAt(i * 3 + 2);

                if (!(a >= '0' && a <= '9') && !(a >= 'a' && a <= 'f')) {
                    Log.warn("Invalid hexadecimal at position "+(i * 3 + 1)+"!");
                    return;
                }
                if (!(b >= '0' && b <= '9') && !(b >= 'a' && b <= 'f')) {
                    Log.warn("Invalid hexadecimal at position "+(i * 3 + 2)+"!");
                    return;
                }
            }
            for (int i = 0; i < states.length; i++) {
                char ch = statesData.charAt(i * 3);
                states[i] = FortsPlotState.values()[ch - 'a'];

                char a = statesData.charAt(i * 3 + 1);
                char b = statesData.charAt(i * 3 + 2);

                int team = ((a >= '0' && a <= '9') ? a - '0' : a - 'a' + 10) * 16 +
                        ((b >= '0' && b <= '9') ? b - '0' : b - 'a' + 10);
                teams[i] = Team.all[team];
            }
        }
    }

    public void drawEditorGuides() {
        float lineWidth = Core.settings.getInt("mindurka.guideslinewidth", 1);
        boolean outline = Core.settings.getBool("mindurka.guidesoutline", false);

        Color stroke;
        for (int i = 0; i < plotsX; i++) for (int o = 0; o < plotsY; o++) {
            int x = startX + jX * i;
            int y = startY + jY * o;

            Vec2 s = MVars.mapView.unproject(x, y);
            float sx = s.x, sy = s.y;
            s = MVars.mapView.unproject(x + width, y + height);

            Team team = teams[i + o * plotsX];
            FortsPlotState state = states[i + o * plotsX];

            stroke = state.stroke();

            if (state.fill(fillColor, team).a != 0) {
                Draw.color(fillColor);
                float w = s.x - sx;
                float h = s.y - sy;
                Fill.rect(sx + w / 2, sy + h / 2, w, h);
            }

            if (outline) {
                Draw.color(state.outline());
                Lines.stroke(Scl.scl(lineWidth + 2));
                Lines.rect(sx - 1, sy - 1, s.x - sx + 2, s.y - sy + 2);
                Draw.reset();
            }

            Draw.color(stroke);
            Lines.stroke(Scl.scl(lineWidth));
            Lines.rect(sx, sy, s.x - sx, s.y - sy);
            Draw.reset();
        }
    }

    public void setPlotInfo(int x, int y, FortsPlotState state, Team team) {
        if (x < startX || y < startY) return;
        int plotX = (x - startX) / jX;
        int plotY = (y - startY) / jY;
        if (plotX >= plotsX || plotY >= plotsY) return;
        if ((x - startX) % jX >= width || (y - startY) % jY >= height) return;

        int i = plotX + plotY * plotsX;

        states[i] = state;
        teams[i] = team;
    }

    public String save() {
        // Plots format v2:
        // - Compressed
        // - Doesn't save useless data
        // You may not be able to know from the length if data is valid, but honestly who cares.
        ByteSeq data = new ByteSeq();

        for (int i = 0; i < states.length; i++) {
            FortsPlotState state = states[i];
            data.add((byte) state.ordinal());
            if (state.placed()) {
                data.add((byte) teams[i].id);
            }
        }

        // Marvelous.
        return "x0" + Base64.getEncoder().withoutPadding().encodeToString(LZ4Factory.fastestInstance().highCompressor().compress(data.items, 0, data.size));

        // StringBuilder str = new StringBuilder(states.length * 3);
        // for (int i = 0; i < states.length; i++) {
        //     str.append((char) ('a' + states[i].ordinal()));

        //     int b = teams[i].id;
        //     int a = b / 16;
        //     b %= 16;

        //     str.append((char) (a >= 10 ? a + 'a' - 10 : a + '0'));
        //     str.append((char) (b >= 10 ? b + 'a' - 10 : b + '0'));
        // }
        // return str.toString();
    }

    public boolean placePlot(Team team, Schematic scheme, int x, int y) {
        return placePlot(team, scheme, x, y, false);
    }
    public boolean placePlot(Team team, Schematic scheme, int x, int y, boolean ignoreAdjacent) {
        if (!placePlotNoOverride(team, scheme, x, y, ignoreAdjacent)) return false;

        int plotX = (x - startX) / jX;
        int plotY = (y - startY) / jY;

        int i = plotX + plotY * plotsX;

        states[i] = FortsPlotState.placed;
        teams[i] = team;

        return true;
    }
    boolean placePlotNoOverride(Team team, Schematic scheme, int x, int y, boolean ignoreAdjacent) {
        if (scheme.width != width + wallSize * 2 || scheme.height != height + wallSize * 2) {
            Log.warn("Could not place plot scheme! (size; "+scheme.width+"x"+scheme.height+" vs "+(width + wallSize * 2)+"x"+(height + wallSize * 2)+")");
            return false;
        }
        if (x < startX || y < startY) {
            Log.warn("Could not place plot scheme! (start; "+x+" < "+startX+" or "+y+" < "+startY+")");
            return false;
        }
        int plotX = (x - startX) / jX;
        int plotY = (y - startY) / jY;
        if (plotX >= plotsX || plotY >= plotsY) {
            Log.warn("Could not place plot scheme! (plots; "+plotX+" >= "+plotsX+" or "+plotY+" >= "+plotsY+")");
            return false;
        }
        if ((x - startX) % jX >= width || (y - startY) % jY >= height) {
            int vx = (x - startX) % jX;
            int vy = (y - startY) % jY;
            Log.warn("Could not place plot scheme! (start; "+vx+" >= "+width+" or "+vy+" >= "+height+")");
            return false;
        }

        int i = plotX + plotY * plotsX;
        if (centerParts != null && centerParts.containsKey(i)) {
            Log.warn("Could not place plot scheme! Center part is already placed");
            return false;
        }
        if (!ignoreAdjacent) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                if (dx + plotX < 0 || dy + plotY < 0) continue;
                if (dx + plotX >= plotsX || dy + plotY >= plotsY) continue;
                int nx = dx + plotX + (dy + plotY) * plotsY;
                if (states[nx].placeTemplate() && team != teams[nx]) {
                    Log.warn("Could not place plot scheme! There is an enemy plot at ("+plotX+"x"+plotY+")");
                    return false;
                }
            }
        }

        if (centerParts == null) centerParts = new IntMap<>();
        centerParts.put(i, Schematic.of(Vars.world.tiles,
                startX + plotX * jX, startY + plotY * jY,
                width, height));
        scheme.paste(wallSize, wallSize, scheme.width - wallSize * 2, scheme.height - wallSize * 2, Vars.world.tiles,
                startX + plotX * jX, startY + plotY * jY);

        if (intersectionParts == null) intersectionParts = new IntMap<>();
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) {
            int ii = (plotX + dx) + (plotY + dy) * (plotsX + 1);
            if (intersectionParts.containsKey(ii)) continue;
            int tx = startX + (plotX + dx) * jX - wallSize;
            int ty = startY + (plotY + dy) * jY - wallSize;
            intersectionParts.put(ii, Schematic.of(Vars.world.tiles, tx, ty, wallSize, wallSize));
            scheme.paste(dx * jX, dy * jY, wallSize, wallSize, Vars.world.tiles, tx, ty);
        }

        if (verticalWalls == null) verticalWalls = new IntMap<>();
        if (horizontalWalls == null) horizontalWalls = new IntMap<>();
        for (int d = 0; d <= 1; d++) {
            int ix = (plotX + d) + plotY * (plotsX + 1);
            int iy = plotX + (plotY + d) * plotsX;

            if (!horizontalWalls.containsKey(ix)) {
                int tx = startX + (plotX + d) * jX - wallSize;
                int ty = startY + plotY * jY;
                horizontalWalls.put(ix, Schematic.of(Vars.world.tiles, tx, ty, wallSize, height));
                scheme.paste(jX * d, wallSize, wallSize, height, Vars.world.tiles, tx, ty);
            }

            if (!verticalWalls.containsKey(iy)) {
                int tx = startX + plotX * jX;
                int ty = startY + (plotY + d) * jY - wallSize;
                verticalWalls.put(iy, Schematic.of(Vars.world.tiles, tx, ty, width, wallSize));
                scheme.paste(wallSize, jY * d, width, wallSize, Vars.world.tiles, tx, ty);
            }
        }

        return true;
    }

    // public boolean removePlot(Schematic scheme, int x, int y) {
    //     if (scheme.width != width + wallSize * 2 || scheme.height != height + wallSize * 2) return false;
    //     if (x < startX || y < startY) return false;
    //     int plotX = (x - startX) / jX;
    //     int plotY = (y - startY) / jY;
    //     if (plotX >= plotsX || plotY >= plotsY) return false;
    //     if ((x - startX) % jX >= width || (y - startY) % jY >= height) return false;

    //     int i = plotX + plotY * plotsX;
    //     if (centerParts == null || !centerParts.containsKey(i)) return false;

    //     centerParts.remove(i).paste(Vars.world.tiles, startX + plotX * jX, startY + plotY * jY);

    //     states[i] = FortsPlotState.enabled;

    //     intersectionParts: for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) {
    //         int ii = (plotX + dx) + (plotY + dy) * (plotsX + 1);
    //         if (!intersectionParts.containsKey(ii)) continue;

    //         for (int ddx = -1; ddx <= 0; ddx++) for (int ddy = -1; ddy <= 0; ddy++) {
    //             int px = plotX + dx + ddx, py = plotY + dy + ddy;
    //             if (px < 0 || py < 0 || px >= plotsX || py >= plotsY) continue;

    //             int iii = px + py * plotsX;
    //             if (states[iii].placed()) break intersectionParts;
    //         }

    //         int tx = startX + (plotX + dx) * jX - wallSize;
    //         int ty = startY + (plotY + dy) * jY - wallSize;
    //         intersectionParts.remove(ii).paste(Vars.world.tiles, tx, ty);
    //     }

    //     return true;
    // }

    public void placeDefaultPlots(Func<Team, Schematic> scheme) {
        // Log.info("Trying to place default plots ("+plotsY+"x"+plotsY+")");
        for (int x = 0; x < plotsX; x++) for (int y = 0; y < plotsY; y++) {
            int i = x + y * plotsX;
            if (states[i].placeTemplate()) {
                Team team = teams[i];
                Schematic schem = scheme.get(team);
                if (schem == null) {
                    Log.warn("There is no plot scheme for "+team.name+"!");
                    continue;
                }
                placePlotNoOverride(teams[i], schem, startX + x * jX, startY + y * jY, true);
            }
        }
    }

    /**
     * <b>Highly unsafe!</b> Revert all plot schematics without modifying any states.
     */
    public void removeAllPlots() {
        Schematic.Options options = new Schematic.Options().noNet().skipBuildings();

        if (centerParts == null) return;

        Util.forEach(centerParts, (key, value) -> {
            int plotX = key % plotsX;
            int plotY = key / plotsX;
            value.paste(Vars.world.tiles, startX + plotX * jX, startY + plotY * jY, options);
        });

        Util.forEach(intersectionParts, (key, value) -> {
            int plotX = key % (plotsX + 1);
            int plotY = key / (plotsX + 1);
            value.paste(Vars.world.tiles, startX + plotX * jX - wallSize, startY + plotY * jY - wallSize, options);
        });

        Util.forEach(horizontalWalls, (key, value) -> {
            int plotX = key % (plotsX + 1);
            int plotY = key / (plotsX + 1);
            value.paste(Vars.world.tiles, startX + plotX * jX - wallSize, startY + plotY * jY, options);
        });

        Util.forEach(verticalWalls, (key, value) -> {
            int plotX = key % plotsX;
            int plotY = key / plotsX;
            value.paste(Vars.world.tiles, startX + plotX * jX, startY + plotY * jY - wallSize, options);
        });

        centerParts = null;
        intersectionParts = null;
        horizontalWalls = null;
        verticalWalls = null;
    }
}
