package mindustry.client.morj.mk.ui;

import mindustry.client.morj.mk.MVars;

public enum EditorMode {
    normal {
        {
            downsizeBlock = false;
        }
    },
    zoom {
        {
            enableTools = false;
        }

        @Override
        public MouseAction drawAction(float mouseX, float mouseY) {
            return MouseAction.Drag.begin(mouseX, mouseY);
        }

        @Override
        public MouseAction eraseAction(float mouseX, float mouseY) {
            return MouseAction.Drag.begin(mouseX, mouseY);
        }
    },
    eraser {
        @Override
        public MouseAction drawAction(float mouseX, float mouseY) {
            return MouseAction.Erase.begin(mouseX, mouseY, MVars.toolOptions.tool);
        }

        @Override
        public MouseAction eraseAction(float mouseX, float mouseY) {
            return MouseAction.Drag.begin(mouseX, mouseY);
        }
    },
    // TODO: Implement this thing, yes.

    ;

    public boolean downsizeBlock = true;
    public boolean enableTools = true;

    public MouseAction drawAction(float mouseX, float mouseY) {
        return MouseAction.Draw.begin(mouseX, mouseY, MVars.toolOptions.tool);
    }
    public MouseAction eraseAction(float mouseX, float mouseY) {
        return MouseAction.Erase.begin(mouseX, mouseY, MVars.toolOptions.tool);
    }

    public ToolContext drawContext() {
        return LayerToolContext.i;
    }
    public ToolContext eraseContext() {
        return EraseToolContext.i;
    }
}
