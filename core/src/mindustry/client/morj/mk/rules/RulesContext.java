package mindustry.client.morj.mk.rules;

import lombok.AllArgsConstructor;
import mindustry.game.Rules;

@AllArgsConstructor
public class RulesContext {
    public final MRules customRules;
    public final Rules rules;
    public int mapWidth;
    public int mapHeight;
}
