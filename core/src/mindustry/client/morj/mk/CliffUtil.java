package mindustry.client.morj.mk;

import arc.math.geom.Geometry;
import mindustry.client.morj.mk.ui.ToolContext;
import mindustry.Vars;
import mindustry.content.Blocks;

public class CliffUtil {
    private CliffUtil() {}

    private static boolean contains(int x, int y) {
        return x >= 0 && y >= 0 && x < Vars.world.width() && y < Vars.world.height();
    }

    private static byte sides(
            boolean s5, boolean s6, boolean s7,
            boolean s4,             boolean s0,
            boolean s3, boolean s2, boolean s1
    ) {
        byte cliffs = (byte) 0;
        if (s0) cliffs = Util.enable(cliffs, 0);
        if (s1) cliffs = Util.enable(cliffs, 1);
        if (s2) cliffs = Util.enable(cliffs, 2);
        if (s3) cliffs = Util.enable(cliffs, 3);
        if (s4) cliffs = Util.enable(cliffs, 4);
        if (s5) cliffs = Util.enable(cliffs, 5);
        if (s6) cliffs = Util.enable(cliffs, 6);
        if (s7) cliffs = Util.enable(cliffs, 7);
        return cliffs;
    }

    private static byte adjs(
                        boolean s6,
            boolean s4,             boolean s0,
                        boolean s2
    ) {
        byte cliffs = (byte) 0;
        if (s0) cliffs = Util.enable(cliffs, 0);
        if (s2) cliffs = Util.enable(cliffs, 2);
        if (s4) cliffs = Util.enable(cliffs, 4);
        if (s6) cliffs = Util.enable(cliffs, 6);
        return cliffs;
    }

    public static byte recalculateCliff(ToolContext ctx, int x, int y) {
        // if (ctx.block(x, y) != Blocks.cliff) return 0;
        // Bruteforcing this shit.

        int bits = 0;
        for (int i = 0; i < 8; i++) {
            int dx = Geometry.d8[i].x;
            int dy = Geometry.d8[i].y;
            boolean toggled = ctx.block(x + dx, y + dy) == Blocks.cliff || !contains(x + dx, y + dy) || MVars.toolOptions.fakeCliffsMap().toggled(x + dx, y + dy);

            if (!toggled) bits |= (1 << i);

            // Tile other = ctx.get(tile.x + Geometry.d8[i].x, tile.y + Geometry.d8[i].y);
            // if(other != null && !other.block().isStatic()){
            //     rotation |= (1 << i);
            // }
        }

        return (byte) bits;

        // byte walls = sides(
        //         ctx.block(x - 1, y + 1) == Blocks.cliff || !contains(x - 1, y + 1) || MVars.toolOptions.fakeCliffsMap().toggled(x - 1, y + 1),
        //         ctx.block(x, y + 1) == Blocks.cliff || !contains(x, y + 1) || MVars.toolOptions.fakeCliffsMap().toggled(x, y + 1),
        //         ctx.block(x + 1, y + 1) == Blocks.cliff || !contains(x + 1, y + 1) || MVars.toolOptions.fakeCliffsMap().toggled(x + 1, y + 1),
        //         ctx.block(x - 1, y) == Blocks.cliff || !contains(x - 1, y) || MVars.toolOptions.fakeCliffsMap().toggled(x - 1, y),
        //         ctx.block(x + 1, y) == Blocks.cliff || !contains(x + 1, y) || MVars.toolOptions.fakeCliffsMap().toggled(x + 1, y),
        //         ctx.block(x - 1, y - 1) == Blocks.cliff || !contains(x - 1, y - 1) || MVars.toolOptions.fakeCliffsMap().toggled(x - 1, y - 1),
        //         ctx.block(x, y - 1) == Blocks.cliff || !contains(x, y - 1) || MVars.toolOptions.fakeCliffsMap().toggled(x, y - 1),
        //         ctx.block(x + 1, y - 1) == Blocks.cliff || !contains(x + 1, y - 1) || MVars.toolOptions.fakeCliffsMap().toggled(x + 1, y - 1)
        // );
        // byte less = adjs(
        //         ctx.block(x, y + 1) == Blocks.cliff || !contains(x, y + 1) || MVars.toolOptions.fakeCliffsMap().toggled(x, y + 1),
        //         ctx.block(x - 1, y) == Blocks.cliff || !contains(x - 1, y) || MVars.toolOptions.fakeCliffsMap().toggled(x - 1, y),
        //         ctx.block(x + 1, y) == Blocks.cliff || !contains(x + 1, y) || MVars.toolOptions.fakeCliffsMap().toggled(x + 1, y),
        //         ctx.block(x, y - 1) == Blocks.cliff || !contains(x, y - 1) || MVars.toolOptions.fakeCliffsMap().toggled(x, y - 1)
        // );

        // boolean X = true;
        // boolean O = false;

        // byte cliffs = (byte) 0xff;

        // // The code you write when you give up.
        // if (walls == sides(
        //         X, X, X,
        //         X,    X,
        //         X, X, X
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (walls == sides(
        //         O, X, X,
        //         X,    X,
        //         X, X, X
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (walls == sides(
        //         X, X, O,
        //         X,    X,
        //         X, X, X
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (walls == sides(
        //         X, X, X,
        //         X,    X,
        //         X, X, O
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            X,
        //         X,    O,
        //            X
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    X,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            X,
        //         X,    X,
        //            O
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, X, O
        // );
        // else if (less == adjs(
        //            X,
        //         O,    X,
        //            X
        // )) cliffs = sides(
        //         O, O, O,
        //         X,    O,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            O,
        //         X,    X,
        //            X
        // )) cliffs = sides(
        //         O, X, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            O,
        //         O,    X,
        //            O
        // )) cliffs = sides(
        //         X, X, X,
        //         X,    O,
        //         X, X, X
        // );
        // else if (less == adjs(
        //            O,
        //         O,    O,
        //            X
        // )) cliffs = sides(
        //         X, O, O,
        //         O,    X,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            O,
        //         X,    O,
        //            O
        // )) cliffs = sides(
        //         X, X, X,
        //         O,    X,
        //         X, X, X
        // );
        // else if (less == adjs(
        //            X,
        //         O,    O,
        //            O
        // )) cliffs = sides(
        //         X, O, X,
        //         X,    X,
        //         X, X, X
        // );
        // else if (less == adjs(
        //            O,
        //         X,    X,
        //            O
        // )) cliffs = sides(
        //         O, X, O,
        //         O,    O,
        //         O, X, O
        // );
        // else if (less == adjs(
        //            X,
        //         O,    O,
        //            X
        // )) cliffs = sides(
        //         O, O, O,
        //         X,    X,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            X,
        //         X,    O,
        //            O
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         O, O, X
        // );
        // else if (less == adjs(
        //            X,
        //         O,    X,
        //            O
        // )) cliffs = sides(
        //         O, O, O,
        //         O,    O,
        //         X, O, O
        // );
        // else if (less == adjs(
        //            O,
        //         O,    X,
        //            X
        // )) cliffs = sides(
        //         X, O, O,
        //         O,    O,
        //         O, O, O
        // );
        // else if (less == adjs(
        //            O,
        //         X,    O,
        //            X
        // )) cliffs = sides(
        //         O, O, X,
        //         O,    O,
        //         O, O, O
        // );

        // return cliffs;
    }
}
