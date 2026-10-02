package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.scene.ui.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.Binding;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

public class FDAutoShoot {
    private static final Seq<Building> targetBuilds = new Seq<>();
    private static Position lastTargetPos = null;
    private static Entityc manualTarget = null;
    private static boolean isHealingMode = false;
    public static boolean viewUnitAim = false;

    /** Star button and the auto-target key are one switch. */
    public static void setEnabled(boolean on){
        Core.settings.put("autotarget", on);
        Core.settings.put("smarttargeting", on);
        if(!on){
            manualTarget = null;
            lastTargetPos = null;
            if(player != null && !Core.input.keyDown(Binding.select)){
                player.shooting = false;
                Unit unit = player.unit();
                if(unit != null) unit.controlWeapons(true, false);
            }
        }
    }

    public static boolean enabled(){
        return Core.settings.getBool("autotarget", false);
    }

    /** Right click on the aim button. */
    public static void openMenu(){
        BaseDialog dialog = new BaseDialog("@client.aim.title");
        ButtonGroup<TextButton> group = new ButtonGroup<>();
        group.setMinCheckCount(1);
        group.setMaxCheckCount(1);

        TextButton nearest = new TextButton("@client.aim.nearest", Styles.togglet);
        TextButton low = new TextButton("@client.aim.lowhp", Styles.togglet);
        nearest.changed(() -> {
            if(nearest.isChecked()) Core.settings.put("aimmode", 0);
        });
        low.changed(() -> {
            if(low.isChecked()) Core.settings.put("aimmode", 1);
        });
        group.add(nearest);
        group.add(low);
        nearest.setChecked(Core.settings.getInt("aimmode", 0) != 1);
        low.setChecked(Core.settings.getInt("aimmode", 0) == 1);

        dialog.cont.defaults().growX().pad(3f);
        dialog.cont.add(nearest).row();
        dialog.cont.add(low).row();
        dialog.cont.add("@client.aim.pick").color(Color.lightGray).wrap().width(380f).left().padBottom(8f).row();
        dialog.cont.check("@client.aim.constant", Core.settings.getBool("constantfire", false), on -> Core.settings.put("constantfire", on)).left().padTop(4f).row();
        dialog.cont.add("@client.aim.constant.note").color(Color.lightGray).wrap().width(380f).left().row();
        dialog.addCloseButton();
        dialog.show();
    }

    public static void update() {
        boolean auto = Core.settings.getBool("autotarget", false);
        if(Core.settings.getBool("smarttargeting", false) != auto){
            Core.settings.put("smarttargeting", auto);
        }
        if(!auto){
            manualTarget = null;
            lastTargetPos = null;
            if(player != null && !Core.input.keyDown(Binding.select)){
                player.shooting = false;
                Unit unit = player.unit();
                if(unit != null) unit.controlWeapons(true, false);
            }
            return;
        }
        if(player.unit() == null || player.unit().mining() || player.unit().isBuilding()) return;

        Unit playerUnit = player.unit();
        if (playerUnit == null || playerUnit.mounts.length == 0) {
            lastTargetPos = null;
            return;
        }

        // --- 1. ПРОВЕРКА РУЧНОЙ ЦЕЛИ ---
        handleManualTargetSelection();

        if (manualTarget != null) {
            if (!validateEntity(manualTarget)) {
                manualTarget = null;
            }
        }

        // --- 2. РУЧНАЯ ПОПРАВКА (ЛКМ) ---
        if (Core.input.keyDown(Binding.select) && !Core.input.keyDown(KeyCode.space)) {
            //lastTargetPos = null;
            return;
        }

        if (playerUnit instanceof Mechc && playerUnit.isFlying()) {
            lastTargetPos = null;
            player.shooting = false;
            return;
        }

        float overrange = Core.settings.getFloat("overrange", 0f);
        float multiplier = Math.max(0.1f, 1f + overrange / 100f);

        float range = playerUnit.range() * multiplier;
        boolean constant = Core.settings.getBool("constantfire", false);
        float unitSearch = constant ? Math.max(range * 6f, 520f) : range;
        isHealingMode = false;

        // --- ВЫБОР ЦЕЛИ ПО ПРИОРИТЕТАМ ---
        Position finalTarget = null;

        // Приоритет №1: Ручная цель
        if (manualTarget != null) {
            finalTarget = (Position) manualTarget;
        }

        // Приоритет №2: Вражеские юниты. Постоянный огонь ищет дальше радиуса оружия.
        if (finalTarget == null && !Core.settings.getBool("ignoreunit", false)) {
            finalTarget = pickEnemy(playerUnit, unitSearch);
        }

        // Приоритет №3: Хил союзных зданий
        if (finalTarget == null && !Core.settings.getBool("ignoreheal", false)) {
            boolean canHeal = playerUnit.type.canHeal;
            if (canHeal) {
                finalTarget = Units.closestBuilding(player.team(), playerUnit.x, playerUnit.y, range, b -> b.damaged() && b.isValid());
                if (finalTarget != null) isHealingMode = true;
            }
        }

        // Приоритет №4: Вражеские здания (по списку весов)
        if (finalTarget == null) {
            targetBuilds.clear();
            Units.nearbyBuildings(playerUnit.x, playerUnit.y, range, b -> {
                if (b.team != player.team() && b.isValid() && getPriority(b) > 0f) {
                    targetBuilds.add(b);
                }
            });

            if (!targetBuilds.isEmpty()) {
                if (targetBuilds.size > 1) {
                    targetBuilds.sort((a, b) -> {
                        float pa = getPriority(a), pb = getPriority(b);
                        if (pa != pb) return Float.compare(pb, pa);
                        return Float.compare(playerUnit.dst2(a), playerUnit.dst2(b));
                    });
                }
                finalTarget = targetBuilds.first();
            }
        }

        lastTargetPos = finalTarget;

        if (finalTarget != null) {
            shootAt(playerUnit, finalTarget);
        } else {
            player.shooting = false;
        }
    }

    /** Nearest enemy, or the one with the least health. Constant fire uses a wider search. */
    private static Unit pickEnemy(Unit self, float search){
        boolean lowest = Core.settings.getInt("aimmode", 0) == 1;
        if(!lowest){
            return Units.closestEnemy(self.team, self.x, self.y, search, u -> enemyUnit(self, u));
        }
        float search2 = search * search;
        final Unit[] best = {null};
        final float[] bestHp = {Float.POSITIVE_INFINITY};
        final float[] bestDst = {Float.POSITIVE_INFINITY};
        Groups.unit.each(u -> {
            if(!enemyUnit(self, u)) return;
            float dst2 = self.dst2(u);
            if(dst2 > search2) return;
            float hp = u.health;
            if(hp < bestHp[0] || (hp == bestHp[0] && dst2 < bestDst[0])){
                best[0] = u;
                bestHp[0] = hp;
                bestDst[0] = dst2;
            }
        });
        return best[0];
    }

    private static boolean enemyUnit(Unit self, Unit other){
        return other != self && other.targetable(self.team) && other.checkTarget(self.type.targetAir, self.type.targetGround);
    }

    private static void handleManualTargetSelection() {
        if (Core.input.keyDown(KeyCode.space) && Core.input.keyTap(Binding.select)) {
            Unit mouseUnit = Units.closestEnemy(null, player.mouseX, player.mouseY, 16f, u -> u.targetable(player.team()));
            if (mouseUnit != null) {
                manualTarget = mouseUnit;
                lastTargetPos = mouseUnit;
            } else {
                Building mouseBuild = world.buildWorld(player.mouseX, player.mouseY);
                if (mouseBuild != null && mouseBuild.team != player.team()) {
                    manualTarget = mouseBuild;
                    lastTargetPos = mouseBuild;
                } else {
                    manualTarget = null;
                }
            }
        }
    }

    private static boolean validateEntity(Entityc e) {
        if (e instanceof Unit u) return u.isAdded() && !u.dead;
        if (e instanceof Building b) return b.isValid();
        return false;
    }

    private static void shootAt(Unit unit, Position target) {
        float bulletSpeed = unit.type.hasWeapons() ? unit.type.weapons.first().bullet.speed : 0f;
        Vec2 intercept = Predict.intercept(unit, target, bulletSpeed);

        player.mouseX = intercept.x;
        player.mouseY = intercept.y;

        boolean targetingAlly = (target instanceof Building b && b.team == player.team());
        boolean canHeal = unit.type.canHeal;

        if (targetingAlly) {
            player.shooting = canHeal;
        } else {
            player.shooting = true;
        }

        if (unit.type.faceTarget) unit.lookAt(intercept);

        unit.aim(intercept);
        unit.controlWeapons(true, player.shooting);

        for (WeaponMount mount : unit.mounts) {
            mount.aimX = intercept.x;
            mount.aimY = intercept.y;
        }
    }

    public static void drawTarget() {
        if (!enabled() || lastTargetPos == null) return;

        Draw.z(Layer.overlayUI);
        float x = lastTargetPos.getX(), y = lastTargetPos.getY();
        boolean isManual = (manualTarget != null && lastTargetPos == (Position)manualTarget);

        float pulse = Mathf.absin(Time.time, isManual ? 2f : 4f, 1f);

        Color color1, color2;
        if (isManual) {
            color1 = Color.red; color2 = Color.white;
        } else if (isHealingMode) {
            color1 = Color.green; color2 = Color.white;
        } else {
            color1 = Color.valueOf("ff00f6"); color2 = Color.valueOf("00038a");
        }

        Color pulsingColor = Tmp.c1.set(color1).lerp(color2, pulse);
        float size = (isManual ? 10f : 7f) + Mathf.absin(Time.time, 4f, 3f);

        Draw.color(pulsingColor);
        Lines.stroke(isManual ? 2.2f : 1.2f);
        Lines.square(x, y, size, Time.time * (isManual ? 1.5f : 2f));
        Drawf.target(x, y, size * 1.5f, pulsingColor);

        if (isManual) Lines.poly(x, y, 4, size * 1.3f, Time.time * -1.5f);

        Draw.reset();
    }

    private static float getPriority(Building b) {
        Block block = b.block;
        if (block == Blocks.powerSource) return 100f;
        if (block instanceof PowerNode) {
            if (b.power != null && b.power.graph.getPowerProduced() > 0.01f) return 90f;
            return 1f;
        }
        if (block instanceof MendProjector) {
            if (b.power != null && b.power.graph.getPowerProduced() > 0.01f) return 80f;
            return 1f;
        }
        if (block == Blocks.itemSource) return 70f;
        if (block instanceof Turret) {
            if (b instanceof Turret.TurretBuild tb && tb.hasAmmo()) return 40f;
            return 1f;
        }
        if (block instanceof PowerGenerator) return 30f;
        if (block instanceof GenericCrafter) return 3f;
        if (block instanceof Wall) return 2f;
        if (block == Blocks.powerVoid) return -100f;
        return 1f;
    }
    public static void drawUnitAim() {
        if (!viewUnitAim || player.unit() == null || player.dead()) return;

        Unit unit = player.unit();
        Draw.z(Layer.overlayUI);


        Lines.stroke(1f);
        Draw.color(Color.lightGray, 0.2f);
        Lines.line(unit.x, unit.y, unit.aimX(), unit.aimY());

        if (unit.mounts.length > 0) {
            for (WeaponMount mount : unit.mounts) {
                float rotation = unit.rotation - 90;

                float wx = unit.x + Angles.trnsx(rotation, mount.weapon.x, mount.weapon.y);
                float wy = unit.y + Angles.trnsy(rotation, mount.weapon.x, mount.weapon.y);

                float weaponAngle;
                if(mount.weapon.rotate){
                    weaponAngle = mount.rotation;

                    if(Math.abs(mount.rotation) < 0.001f && Math.abs(unit.rotation) > 0.001f){
                        weaponAngle = unit.rotation + mount.weapon.baseRotation;
                    }
                } else {
                    weaponAngle = unit.rotation + mount.weapon.baseRotation;
                }

                float range = mount.weapon.range();

                float tx = wx + Angles.trnsx(weaponAngle, range);
                float ty = wy + Angles.trnsy(weaponAngle, range);

                float angleToTarget = Angles.angle(wx, wy, unit.aimX(), unit.aimY());
                float dst = Math.abs(Angles.angleDist(weaponAngle, angleToTarget));

                if (dst < 4f) Draw.color(Color.green, 0.5f);
                else Draw.color(Color.white, 0.3f);

                Lines.stroke(1.2f);
                Lines.dashLine(wx, wy, tx, ty, (int)(range / 10));

                Fill.circle(tx, ty, 2f);

                if(mount.reload > 0){
                    Draw.color(Color.orange, 0.6f);
                    float rl = 1f - (mount.reload / mount.weapon.reload);
                    Lines.stroke(1.5f);
                    Lines.arc(wx, wy, 4f, rl, weaponAngle - 90);
                }
            }
        }

        Draw.color(Color.valueOf("00ff00"), 0.95f);
        Lines.stroke(1f);
        Lines.dashCircle(unit.x, unit.y, unit.range());

        float overrange = Core.settings.getFloat("overrange", 0f);
        if (overrange != 0f) {
            float multiplier = Math.max(0.1f, 1f + overrange / 100f);
            float autoRange = unit.range() * multiplier;

            Draw.color(Color.green.cpy().mul(0.6f), 0.8f);
            Lines.stroke(1.2f);

            Lines.dashCircle(unit.x, unit.y, autoRange);
        }

        Draw.reset();
    }
}