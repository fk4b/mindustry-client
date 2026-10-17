package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.gl.FrameBuffer;
import arc.util.Nullable;
import mindustry.graphics.g3d.PlanetParams;
import mindustry.graphics.g3d.PlanetRenderer;

import static mindustry.Vars.*;

public class PlanetBackgroundDrawer {
    private static @Nullable FrameBuffer backgroundBuffer;
    private static final PlanetRenderer planets = new PlanetRenderer();

    private static boolean changed = true;

    public static void update() {
        changed = true;
    }

    public static @Nullable Texture draw() {
        if (state.rules.planetBackground == null || (state.rules.backgroundTexture != null && !state.rules.backgroundTexture.isEmpty())) {
            dispose();
            return null;
        }

        int size = Math.max(Core.graphics.getWidth(), Core.graphics.getHeight());

        boolean resized = false;
        if (backgroundBuffer == null) {
            resized = true;
            backgroundBuffer = new FrameBuffer(size, size);
        }

        if (changed || resized || backgroundBuffer.resizeCheck(size, size)) {
            changed = false;
            
            backgroundBuffer.begin(Color.clear);

            PlanetParams params = state.rules.planetBackground;
            params.viewW = size;
            params.viewH = size;
            params.alwaysDrawAtmosphere = true;
            params.drawUi = false;

            if (params.camPos == null || params.camPos.isZero()) {
                params.camPos = new arc.math.geom.Vec3(0f, 0f, 1f);
            }

            planets.render(params);

            backgroundBuffer.end();
        }

        return backgroundBuffer.getTexture();
    }

    public static void dispose() {
        if (backgroundBuffer != null) {
            backgroundBuffer.dispose();
            backgroundBuffer = null;
        }
    }
}
