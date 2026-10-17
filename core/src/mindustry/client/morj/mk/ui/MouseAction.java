package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.util.Log;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Tile;

public abstract class MouseAction {
    void move(float mouseX, float mouseY) {}
    void end(float mouseX, float mouseY) {}
    void update() {}
    boolean cancelForgets() { return true; }

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    static class Draw extends MouseAction {
        public float startX;
        public float startY;

        public final EditorTool tool;

        public float mouseX;
        public float mouseY;

        public static Draw begin(float mouseX, float mouseY, EditorTool tool) {
            tool.start(LayerToolContext.i);
            return new Draw(mouseX, mouseY, tool, mouseX, mouseY);
        }

        @Override
        void move(float newX, float newY) {
            mouseX = newX;
            mouseY = newY;

            if (tool.draggable == EditorTool.Drag.Touch) {
                Point2 p = MVars.mapView.project(startX, startY);
                int sx = p.x, sy = p.y;
                p = MVars.mapView.project(mouseX, mouseY);
                tool.touched(LayerToolContext.i, sx, sy, p.x, p.y);

                startX = mouseX;
                startY = mouseY;
            }
        }

        @Override
        void end(float mouseX, float mouseY) {
            if (tool.draggable == EditorTool.Drag.Line) {
                Point2 p = MVars.mapView.project(startX, startY);
                int sx = p.x, sy = p.y;
                p = MVars.mapView.project(mouseX, mouseY);
                tool.touchedLine(LayerToolContext.i, sx, sy, p.x, p.y);
            }
            tool.stopped(LayerToolContext.i);
        }
    }

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    static class Erase extends MouseAction {
        private float startX;
        private float startY;

        private final EditorTool tool;

        private float mouseX;
        private float mouseY;

        public static Erase begin(float mouseX, float mouseY, EditorTool tool) {
            tool.start(EraseToolContext.i);
            return new Erase(mouseX, mouseY, tool, mouseX, mouseY);
        }

        @Override
        void move(float newX, float newY) {
            mouseX = newX;
            mouseY = newY;

            if (tool.draggable == EditorTool.Drag.Touch) {
                Point2 p = MVars.mapView.project(startX, startY);
                int sx = p.x, sy = p.y;
                p = MVars.mapView.project(mouseX, mouseY);
                tool.touched(EraseToolContext.i, sx, sy, p.x, p.y);

                startX = mouseX;
                startY = mouseY;
            }
        }

        @Override
        void end(float mouseX, float mouseY) {
            if (tool.draggable == EditorTool.Drag.Line) {
                Point2 p = MVars.mapView.project(startX, startY);
                int sx = p.x, sy = p.y;
                p = MVars.mapView.project(mouseX, mouseY);
                tool.touchedLine(EraseToolContext.i, sx, sy, p.x, p.y);
            }
            tool.stopped(EraseToolContext.i);
        }
    }

    static class Drag extends MouseAction {
        private float mouseX;
        private float mouseY;
        private int moving = 0;

        private Drag(float mouseX, float mouseY) {
            this.mouseX = mouseX;
            this.mouseY = mouseY;
        }

        public static Drag begin(float mouseX, float mouseY) {
            return new Drag(mouseX, mouseY);
        }

        @Override
        void move(float newX, float newY) {
            if (mouseX != newX || mouseY != newY) moving++;
            MVars.mapView.moveByScaled(newX - mouseX, newY - mouseY);
            mouseX = newX;
            mouseY = newY;
        }

        @Override
        void update() {
            if (Core.input.ctrl()) MVars.mapView.mouseAction(CtrlZoom.begin(mouseY));
        }

        @Override
        void end(float mouseX, float mouseY) {
            if (moving > 1) return;

            Point2 point = MVars.mapView.project(mouseX, mouseY);
            if (point.x < 0 || point.y < 0) return;
            if (point.x >= Vars.world.width() || point.y >= Vars.world.width()) return;
            Tile tile = Vars.world.tile(point.x, point.y);

            if (tile.block() != Blocks.air) MVars.toolOptions.current.selectedBlock = tile.block();
            else if (tile.overlay() != Blocks.air) MVars.toolOptions.current.selectedBlock = tile.overlay();
            else MVars.toolOptions.current.selectedBlock = tile.floor();

            if (tile.block() == Blocks.cliff) {
                MVars.toolOptions.cliffSides = tile.data;
            }

            if (tile.build != null) MVars.toolOptions.current.team = tile.team();

            if (MVars.toolOptions.current.selectedBlock.saveData) MVars.toolOptions.current.selectedBlock.editorPicked(tile);
            MVars.editorDialog.rebuildBlockOptions();
        }
    }

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    static class CtrlZoom extends MouseAction {
        private float mouseY;

        public static CtrlZoom begin(float mouseY) {
            return new CtrlZoom(mouseY);
        }

        @Override
        void move(float newX, float newY) {
            MVars.mapView.logScaleBy((mouseY - newY) / 80);
            mouseY = newY;
        }

        @Override
        void update() {
            if (!Core.input.ctrl()) MVars.mapView.mouseAction(Drag.begin(MVars.mapView.mousex(), mouseY));
        }
    }

    static class TouchDrag extends MouseAction {
        private float midX;
        private float midY;
        private float dst;
        private boolean active;

        void recalc() {
            active = MVars.mapView.activeTouches() == 2;
            if (!active) return;

            midX = mid(MVars.mapView.activeTouches[0].x, MVars.mapView.activeTouches[1].x);
            midY = mid(MVars.mapView.activeTouches[0].y, MVars.mapView.activeTouches[1].y);
            dst = Mathf.dst(MVars.mapView.activeTouches[0].x, MVars.mapView.activeTouches[0].y, MVars.mapView.activeTouches[1].x, MVars.mapView.activeTouches[1].y);
        }

        float mid(float a, float b) {
            return (a + b) / 2;
        }

        public static TouchDrag begin() {
            TouchDrag o = new TouchDrag();
            o.recalc();
            return o;
        }

        @Override
        void update() {
            float prevMidX = midX;
            float prevMidY = midY;
            float prevDst = dst;
            boolean prevActive = active;

            recalc();

            if (!prevActive || !active) return;

            MVars.mapView.moveByScaled(midX - prevMidX, midY - prevMidY);
            MVars.mapView.logScaleBy((prevDst - dst) / 200);
        }
    }

    static class Cancelled extends MouseAction {
        public static Cancelled begin() { return new Cancelled(); }
    }
}
