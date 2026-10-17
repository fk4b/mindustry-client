package mindustry.client.morj.mk.ui;

public interface SpecialEditorAction {
    boolean clicked(OMapView view, float mouseX, float mouseY);
    void preview(OMapView view, float mouseX, float mouseY);
}
