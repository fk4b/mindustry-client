package mindustry.client.morj.mk.rules;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import mindustry.content.StatusEffects;
import mindustry.game.Team;
import mindustry.gen.Icon;

// I did have fun with those yes
public enum FortsPlotState {
    /** Plot is disabled and will be removed from the map as the game starts.
     *  <p>
     *  This is the only plot type that does not respect the assigned team.
     */
    disabled,
    /** Plot can be placed. */
    enabled,
    /** Plot is placed. */
    placed,
    /**
     * Plot is placed and cannot be destroyed while team still exists. Otherwise,
     * acts as if plot is {@link #placed}.
     */
    locked,
    /** Plot cannot be destroyed. */
    static_,
    /** Game pretends there's a plot there. */
    ghost,

    ;

    private static final Color[] stroke = new Color[] {
            new Color(0x3D3846ff),
            new Color(0xE0B310ff),
            new Color(0xE0B310ff),
            new Color(0x9BE4F8ff),
            new Color(0xC25A00ff),
            new Color(0xCDCDCDff),
    };
    private static final Color[] outlineColor = new Color[] {
            new Color(0xCDCDCDff),
            new Color(0x5D2C72ff),
            new Color(0x5D2C72ff),
            new Color(0x2A1E13ff),
            new Color(0x0C2D55ff),
            Color.black,
    };
    private static final Color[] fill = new Color[] {
            Color.clear,
            Color.clear,
            null,
            null,
            null,
            null,
    };

    @Override
    public String toString() {
        if (this == static_) return "static";
        return name();
    }

    public Color outline() { return outlineColor[ordinal()]; }
    public Color stroke() { return stroke[ordinal()]; }
    public Color fill(Color copyTo, Team team) {
        Color color = copyTo == null ? new Color() : copyTo;
        Color target = fill[ordinal()];
        if (target == null) target = team.color;
        color.set(target);
        if (color.a > 0.4f) color.a = 0.4f;
        return color;
    }

    /** Check if scheme needs to be placed. */
    public boolean placeTemplate() {
        switch (this) {
            case placed:
            case locked:
            case static_:
                return true;
            default:
                return false;
        }
    }

    /** Check if plot is considered "placed". */
    public boolean placed() {
        switch (this) {
            case placed:
            case locked:
            case static_:
            case ghost:
                return true;
            default:
                return false;
        }
    }
}
