package mindustry.client.morj.mk.util;

import arc.Core;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.Label;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Reflect;
import lombok.AllArgsConstructor;
import mindustry.ui.dialogs.LoadoutDialog;

public class Report {
    private Report() {}

    @AllArgsConstructor
    private static class CurrentElement {
        final Group group;
        int iter;
    }

    private static void potentiallyRelevantElementInfo(String space, Element element) {
        if (element instanceof Label) {
            Log.err(space + "| label (non-null): " + ((Label) element).getText());
            return;
        }

        if (element instanceof LoadoutDialog) {
            Log.err(space + "| updater (non-null): " + (Reflect.get(LoadoutDialog.class, element, "updater") != null));
            Log.err(space + "| resetter (non-null): " + (Reflect.get(LoadoutDialog.class, element, "resetter") != null));
            return;
        }
    }

    /** Inspect the environment and print everything possibly relevant. */
    public static void withException(Throwable t) {
        Log.err("A fatal error has occurred! Bailing out!");
        Log.err("Scene tree (" + Core.scene.getClass().getCanonicalName() + "):");

        Seq<CurrentElement> nesting = Seq.with(new CurrentElement(Core.scene.root, 0));

        while (!nesting.isEmpty()) {
            CurrentElement element = nesting.get(nesting.size - 1);
            if (element.group.getChildren().size == element.iter) {
                nesting.pop();
                continue;
            }

            StringBuilder line = new StringBuilder(nesting.size * 2 + 128);
            String space;
            {
                StringBuilder spaceB = new StringBuilder(nesting.size * 2);
                for (int i = 0; i < nesting.size; i++) {
                    line.append("- ");
                    spaceB.append("  ");
                }
                space = spaceB.toString();
            }
            Element child = element.group.getChildren().get(element.iter++);

            line.append(element.group.name).append(" (").append(element.group.getClass().getCanonicalName()).append(")");

            if (child instanceof Group) {
                Group group = ((Group) child);
                line.append(", children (").append(group.getChildren().size).append("):");
                nesting.add(new CurrentElement(group, 0));
            }

            Log.err(line.toString());
            potentiallyRelevantElementInfo(space, child);
        }

        throw new RuntimeException(t);
    }
}
