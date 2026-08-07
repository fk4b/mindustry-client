package mindustry.client.navigation

import arc.*
import arc.math.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.client.*
import mindustry.client.ClientVars.*
import mindustry.client.communication.*
import mindustry.entities.units.*
import mindustry.game.EventType.*
import mindustry.gen.*
import mindustry.input.*
import mindustry.world.blocks.distribution.ItemBridge
import mindustry.world.blocks.liquid.LiquidBridge
import mindustry.world.blocks.power.PowerNode
import java.util.*
import kotlin.math.abs
import kotlin.math.max

/**
 * Follow / assist another player.
 * Circle assist: reverse speed (negative = clockwise) and routes:
 * circle / square / star / hold / line / figure8.
 */
class AssistPath(
    val assisting: Player?,
    val type: Type = Type.Regular,
    var circling: Boolean = false
) : Path() {
    private var show: Boolean = true
    private var plans = Seq<BuildPlan>()
    private var tolerance = 0f
    private var aStarTolerance = 0f
    private var buildPath: BuildPath? = if (type == Type.BuildPath) BuildPath.Self() else null
    private var theta = 0f
    private var orbitRadius = 0f
    private val transferTimer = Interval()
    private val orbitPos = Vec2()

    companion object {
        private var lastType: Type = Type.Regular
        private var lastAssisted: String? = null

        enum class OrbitShape {
            /** Classic circular orbit. */
            circle,
            /** Square orbit (chebyshev projection of circle). */
            square,
            /** 5-point star orbit. */
            star,
            /** Stay fixed behind the assisted unit (no orbit motion needed). */
            hold,
            /** Walk back and forth along the unit's facing line. */
            line,
            /** Figure-8 (lemniscate) around the assisted unit. */
            figure8;

            companion object {
                fun fromSetting(): OrbitShape {
                    val raw = Core.settings.getString("circleassistshape", "circle")
                        ?.lowercase(Locale.ROOT)
                        ?.replace("-", "")
                        ?.replace("_", "")
                        ?: "circle"
                    // aliases
                    val key = when (raw) {
                        "8", "eight", "fig8", "figureeight", "lemniscate" -> "figure8"
                        "holdpos", "stay" -> "hold"
                        "lines", "patrol" -> "line"
                        else -> raw
                    }
                    return entries.find { it.name.equals(key, ignoreCase = true) } ?: circle
                }
            }
        }

        /**
         * Offset on the assist route for the current shape.
         * @param facingDeg assisted unit rotation in degrees (used by hold/line)
         */
        fun orbitOffset(
            theta: Float,
            radius: Float,
            shape: OrbitShape = OrbitShape.fromSetting(),
            out: Vec2 = Vec2(),
            facingDeg: Float = 0f
        ): Vec2 {
            if (radius <= 0f) return out.setZero()
            val face = facingDeg * Mathf.degRad
            return when (shape) {
                OrbitShape.circle -> out.set(Mathf.cos(theta) * radius, Mathf.sin(theta) * radius)
                OrbitShape.square -> {
                    val cx = Mathf.cos(theta)
                    val cy = Mathf.sin(theta)
                    val m = max(abs(cx), abs(cy)).coerceAtLeast(1e-4f)
                    out.set(cx * radius / m, cy * radius / m)
                }
                OrbitShape.star -> {
                    val points = 10 // 5 tips * 2
                    val t = Mathf.mod(theta / Mathf.PI2, 1f) * points
                    val i0 = t.toInt() % points
                    val i1 = (i0 + 1) % points
                    val f = t - Mathf.floor(t)
                    val inner = radius * 0.38f
                    fun rad(i: Int) = if (i % 2 == 0) radius else inner
                    fun ang(i: Int) = -Mathf.PI / 2f + i * (Mathf.PI / 5f)
                    out.set(
                        Mathf.lerp(Mathf.cos(ang(i0)) * rad(i0), Mathf.cos(ang(i1)) * rad(i1), f),
                        Mathf.lerp(Mathf.sin(ang(i0)) * rad(i0), Mathf.sin(ang(i1)) * rad(i1), f)
                    )
                }
                // Fixed position behind the assisted unit
                OrbitShape.hold -> out.set(-Mathf.cos(face) * radius, -Mathf.sin(face) * radius)
                // Patrol along facing axis: -radius … +radius
                OrbitShape.line -> {
                    val along = Mathf.sin(theta) * radius
                    out.set(Mathf.cos(face) * along, Mathf.sin(face) * along)
                }
                // Lemniscate of Gerono (∞), rotated to unit facing
                OrbitShape.figure8 -> {
                    val s = Mathf.sin(theta)
                    val c = Mathf.cos(theta)
                    val lx = s * radius
                    val ly = s * c * radius
                    // rotate local (lx, ly) by facing
                    out.set(
                        lx * Mathf.cos(face) - ly * Mathf.sin(face),
                        lx * Mathf.sin(face) + ly * Mathf.cos(face)
                    )
                }
            }
        }

        init {
            Events.on(DepositEvent::class.java) {
                val assisting = (Navigation.currentlyFollowing as? AssistPath)?.assisting ?: return@on
                if (it.player != assisting || ratelimitRemaining <= 1) return@on
                Call.transferInventory(player, it.tile)
            }
            Events.on(WithdrawEvent::class.java) {
                val assisting = (Navigation.currentlyFollowing as? AssistPath)?.assisting ?: return@on
                if (it.player != assisting || ratelimitRemaining <= 1) return@on
                Call.requestItem(player, it.tile, it.item, it.amount)
            }
            // Re-follow after map load if we were assisting this player and freecam wasn't moved
            Events.on(UnitChangeEventClient::class.java) {
                if (it.player.hasLoadedMap || it.oldUnit != null) return@on
                if (it.player.name != lastAssisted) return@on
                if (Navigation.currentlyFollowing != null) return@on
                val input = control.input as? DesktopInput ?: return@on
                if (input.moved) return@on
                Navigation.follow(AssistPath(it.player, lastType, Core.settings.getBool("circleassist")))
            }
        }
    }

    init {
        lastType = type
        lastAssisted = null
    }

    override fun reset() {}

    override fun setShow(show: Boolean) {
        this.show = show
    }

    override fun getShow() = show

    override fun follow() {
        if (player?.dead() != false) return
        assisting?.unit() ?: return

        if (circling) {
            // hold does not need phase motion; other routes advance theta
            // Negative speed = clockwise / reverse patrol direction
            if (OrbitShape.fromSetting() != OrbitShape.hold) {
                theta += Time.delta / 60f * Core.settings.getFloat("circleassistspeed", 0f) * Mathf.PI2
                theta = Mathf.mod(theta, Mathf.PI2)
            }
        }

        aStarTolerance = assisting.unit().hitSize * Core.settings.getFloat("assistdistance", 5f) + tilesize * 5
        tolerance = if (circling) 0.1f else assisting.unit().hitSize * Core.settings.getFloat("assistdistance", 5f)
        orbitRadius = if (circling) {
            assisting.unit().hitSize / 2f + 8f * Core.settings.getFloat("assistdistance", 5f)
        } else 0f

        handleInput()

        if (player.unit() is Minerc && assisting.unit() is Minerc) {
            val mine = player.unit() as Minerc
            val com = assisting.unit() as Minerc
            if (com.mineTile() != null && mine.validMine(com.mineTile())) {
                mine.mineTile(com.mineTile())
                val core = player.unit().team.core()
                if (core != null && com.mineTile().drop() != null
                    && player.unit().within(core, player.unit().type.range)
                    && !player.unit().acceptsItem(com.mineTile().drop())
                    && core.acceptStack(player.unit().stack.item, player.unit().stack.amount, player.unit()) > 0
                    && transferTimer.get(60f)
                ) {
                    Call.transferInventory(player, core)
                }
            } else {
                mine.mineTile(null)
            }
        }

        if (assisting.isBuilder && player.isBuilder) {
            if (assisting.unit().updateBuilding && assisting.team() == player.team()) {
                plans.forEach { player.unit().removeBuild(it.x, it.y, it.breaking) }
                plans.clear()
                val skipBridges = Core.settings.getBool("assistfixfd", false)
                for (plan in assisting.unit().plans) {
                    if (BuildPlanCommunicationSystem.isNetworking(plan)) continue
                    if (skipBridges && (plan.block is PowerNode || plan.block is ItemBridge || plan.block is LiquidBridge)) continue
                    plans.add(plan)
                    player.unit().addBuild(plan, false)
                }
            }
        }
    }

    private fun handleInput() {
        if (player?.dead() != false) return
        assisting?.unit() ?: return

        val unit = player.unit()
        val shouldShoot =
            type != Type.BuildPath &&
            (assisting.unit().isShooting || player.shooting && Core.input.keyDown(Binding.select))
        val aimPos =
            if ((type == Type.Regular || type == Type.Cursor) && assisting.unit().isShooting)
                Tmp.v1.set(assisting.unit().aimX, assisting.unit().aimY)
            else if (unit.type.faceTarget) Core.input.mouseWorld()
            else Tmp.v1.trns(unit.rotation, Core.input.mouseWorld().dst(unit)).add(unit.x, player.unit().y)
        val lookPos =
            if (assisting.unit().isShooting && unit.type.faceTarget)
                player.angleTo(assisting.unit().aimX, assisting.unit().aimY)
            else if (unit.type.omniMovement && player.shooting && unit.type.hasWeapons() && unit.type.faceTarget && !(unit is Mechc && unit.isFlying()))
                Angles.mouseAngle(unit.x, unit.y)
            else player.unit().prefRotation()

        player.shooting(shouldShoot)
        unit.aim(aimPos)
        unit.lookAt(lookPos)

        if (circling && orbitRadius > 0f) {
            orbitOffset(
                theta,
                orbitRadius,
                out = orbitPos,
                facingDeg = assisting.unit().rotation
            )
        } else {
            orbitPos.setZero()
        }

        when (type) {
            Type.Regular -> goTo(
                assisting.x + orbitPos.x,
                assisting.y + orbitPos.y,
                tolerance,
                aStarTolerance + tilesize * 5
            )
            Type.FreeMove -> {
                val input = control.input
                if (input is DesktopInput) {
                    if (input.movement.epsilonEquals(0f, 0f)) {
                        if (Core.settings.getBool("zerodrift")) unit.vel.setZero()
                        else if (Core.settings.getBool("decreasedrift") && unit.vel().len() > 3.5) unit.vel.set(unit.vel().scl(0.95f))
                    } else player.unit().moveAt(input.movement)
                } else player.unit().moveAt((input as MobileInput).movement)
            }
            Type.Cursor -> goTo(
                assisting.mouseX + orbitPos.x,
                assisting.mouseY + orbitPos.y,
                tolerance,
                tolerance + tilesize * 5
            )
            Type.BuildPath -> {
                if (plans.isEmpty && (unit.plans == null || unit.plans.isEmpty)) {
                    goTo(
                        assisting.x + orbitPos.x,
                        assisting.y + orbitPos.y,
                        tolerance,
                        aStarTolerance + tilesize * 5
                    )
                } else {
                    buildPath?.follow()
                }
            }
        }
    }

    @Synchronized
    override fun draw() {
        assisting ?: return
        if (type != Type.FreeMove && player.dst(assisting) > aStarTolerance) waypoints.draw()
        // Draw build plans of the assisted unit when not already spectating them
        if (Spectate.pos != assisting) {
            try {
                @Suppress("UNCHECKED_CAST")
                val m = assisting.unit().javaClass.methods.find { it.name == "drawBuildPlans" && it.parameterCount == 0 }
                m?.invoke(assisting.unit())
            } catch (_: Throwable) {
                // optional UI hook; missing on some unit types
            }
        }
    }

    override fun progress(): Float {
        if (assisting != null && !assisting.isAdded) {
            (control.input as? DesktopInput)?.moved = false
            lastAssisted = assisting.name
        }
        return if (assisting == null || !assisting.isAdded) 1f else 0f
    }

    override fun next(): Position? = null

    enum class Type {
        Regular,
        FreeMove,
        Cursor,
        BuildPath,
    }
}
