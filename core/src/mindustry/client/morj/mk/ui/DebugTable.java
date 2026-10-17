package mindustry.client.morj.mk.ui;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.util.Reflect;

public class DebugTable extends Table {
    public Color debugColor = new Color(Mathf.random(), Mathf.random(), Mathf.random(), 0.2f);

    @Override
    public void draw() {
        validate();
        if(isTransform()){
            applyTransform(computeTransform());
            drawBackground(0, 0);
            if(Reflect.<Boolean>get(Table.class, "clip")){
                Draw.flush();
                float padLeft = getMarginLeft(), padBottom = getMarginBottom();
                if(clipBegin(padLeft, padBottom, getWidth() - padLeft - getMarginRight(),
                        getHeight() - padBottom - getMarginTop())){
                    drawChildren();
                    Draw.flush();
                    clipEnd();
                }
            }else{
                drawChildren();
            }
            resetTransform();
        }else{
            drawBackground(x, y);
            Draw.color(debugColor.r, debugColor.g, debugColor.b, debugColor.a * parentAlpha);
            // why
            Fill.rect(x + width / 2, y + height / 2, width, height);
            super.draw();
        }
    }
}
