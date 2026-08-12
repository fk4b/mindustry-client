package mindustry.client.navigation

import arc.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.client.utils.*
import mindustry.game.*
import mindustry.gen.*
import mindustry.input.*
import mindustry.type.*
import mindustry.world.*

class MinePath @JvmOverloads constructor(
    val items: Seq<Item> = Seq<Item>(),
    var cap: Int = Core.settings.getInt("minepathcap"),
    val newGame: Boolean = false,
    args: String = Core.settings.getString("defaultminepathargs")
) : Path() {

    private var lastItem: Item? = null // Last item mined
    private var timer = Interval()
    private var coreIdle = false
    private var bestItem: Item? = null
    var tile: Tile? = null

    init {
        val split = args.lowercase().split("\\s".toRegex())
        if (items.isEmpty) {
            for (a in split) {
                if (a == "*" || a == "all" || a == "a") {
                    items.addAll(content.items().select { indexer.hasOre(it) || indexer.hasWallOre(it) })
                } else if (Strings.canParseInt(a)) {
                    cap = a.toInt().coerceAtLeast(0)
                } else {
                    val foundItem = content.items().find { a.equals(it.name, true) && (indexer.hasOre(it) || indexer.hasWallOre(it)) }
                    if (foundItem != null) {
                        items.add(foundItem)
                    } else if (Core.settings.getBool("afkmodespam")) {
                        player.sendMessage(Core.bundle.format("client.path.builder.invalid", a))
                    }
                }
            }
        }

        if (items.isEmpty) {
            if (player.unit() != null) items.addAll(player.unit().type.mineItems)
            if (split.none { Strings.parseInt(it) > 0 } && Core.settings.getBool("afkmodespam")) {
                player.sendMessage("client.path.miner.allinvalid".bundle())
            }
        } else if (cap >= 0) {
            if (Core.settings.getBool("afkmodespam")) {
                player.sendMessage(
                    Core.bundle.format(
                        "client.path.miner.tobuild",
                        items.joinToString(),
                        if (cap == 0) "∞" else cap
                    )
                )
            }
        } else {
            if (Core.settings.getBool("afkmodespam")) {
                player.sendMessage(
                    Core.bundle.format(
                        "client.path.miner.toidle",
                        items.joinToString(),
                        player.closestCore()?.storageCapacity ?: "-∞"
                    )
                )
            }
        }

        addListener { /*AutoTransfer.enabled = Core.settings.getBool("autotransfer") && !(state.rules.pvp && Server.io())*/ }
    }

    companion object {
        init {
            Events.on(EventType.WorldLoadEvent::class.java) {
                (Navigation.currentlyFollowing as? MinePath ?: return@on).apply {
                    lastItem = null
                }
            }
        }
    }

    override fun setShow(show: Boolean) = Unit
    override fun getShow() = false

    override fun follow() {
        if (player.unit() == null) return

        val core = player.closestCore() ?: return
        val maxCap = if (cap <= 0) core.storageCapacity else core.storageCapacity.coerceAtMost(cap)
        bestItem = items.min({ player.unit().canMine(it) && it.found() }) { core.items[it].toFloat() } ?: return

        if (lastItem != null && player.unit().canMine(lastItem) && lastItem!!.found() &&
            core.items[lastItem] - core.items[bestItem] < 100 && core.items[lastItem] < maxCap) {
            bestItem = lastItem
        }
        lastItem = bestItem

        if (!newGame && core.items[bestItem] >= maxCap && cap >= 0) {
            coreIdle = false
            if (Core.settings.getBool("afkmodespam")) {
                player.sendMessage(Core.bundle.format("client.path.miner.build", maxCap))
            }
            Navigation.follow(BuildPath(items, cap))
        }

        if (coreIdle) {
            if (!player.within(core, itemTransferRange - tilesize * 15)) {
                goTo(core, itemTransferRange - tilesize * 15)
            }

            if (player.unit().hasItem()) {
                player.unit().clearItem()
            }

            if (core.items[bestItem] < maxCap / 2) {
                if (Core.settings.getBool("afkmodespam")) {
                    player.sendMessage(Core.bundle.get("client.path.miner.resume"))
                }
                coreIdle = false
            }
            return
        }

        val unit = player.unit()

        val isInventoryFull = when {
            unit.stack.amount <= 0 -> false
            unit.stack.item == bestItem && unit.maxAccepted(bestItem) <= 1 -> true
            unit.stack.item != bestItem -> true
            else -> false
        }

        tile = indexer.findClosestMineableOre(player.unit(), bestItem)

        val canContinueMiningWithFullInventory = if (tile != null && isInventoryFull) {
            val isPlayerNearCore = player.within(core, tilesize * 27f)
            val isOreNearCore = core.within(tile, tilesize * 27f)
            isPlayerNearCore && isOreNearCore
        } else {
            false
        }

        if (isInventoryFull) {
            if (canContinueMiningWithFullInventory) {
                if (player.within(tile, player.unit().type.mineRange)) {
                    player.unit().mineTile = tile
                }
                player.boosting = player.unit().type.canBoost && !player.within(tile, player.unit().type.mineRange)
                goTo(tile, player.unit().type.mineRange - tilesize * 2)
            } else {
                if (player.within(core, itemTransferRange - tilesize * 10)) {
                    if (timer[30f]) {
                        player.unit().mineTile = null
                        Call.transferInventory(player, core)

                        if (core.items[bestItem] >= maxCap && cap < 0) {
                            if (Core.settings.getBool("afkmodespam")) {
                                player.sendMessage(Core.bundle.format("client.path.miner.idle", maxCap))
                            }
                            coreIdle = true
                        }
                    }
                } else {
                    player.unit().mineTile = null
                    if (player.unit().type.canBoost) {
                        player.boosting = true
                    }
                    goTo(core, itemTransferRange - tilesize * 15)
                }
            }
            return
        }

        if (tile == null) {
            if (player.unit().type.canBoost) {
                player.boosting = true
            }
            goTo(core, itemTransferRange - tilesize * 15)
            return
        }

        if (player.within(tile, player.unit().type.mineRange)) {
            player.unit().mineTile = tile
        }
        player.boosting = player.unit().type.canBoost && !player.within(tile, player.unit().type.mineRange)
        goTo(tile, player.unit().type.mineRange - tilesize * 2)
    }

    @Synchronized
    override fun draw() {
        if ((waypoints.waypoints.lastOrNull()?.dst(player) ?: 0F) > tilesize * 3) {
            waypoints.draw()
        }
    }

    override fun progress() = if (newGame && (control.input as? DesktopInput)?.moved == true) 1f else 0f

    override fun reset() = Unit

    override operator fun next(): Position? = null

    // FINISHME: Unjank core tp on mix tech maps
//    override fun allowCore(core: CoreBlock.CoreBuild): Boolean {
//        val type = (core.block() as CoreBlock).unitType
//
//        if (tile == null) return false
//        val item: Item? =
//            if ((type.mineFloor && tile!!.block() == Blocks.air)) tile!!.drop()
//            else if (type.mineWalls) tile!!.wallDrop()
//            else return false
//
//        return item != null && type.mineTier >= item.hardness
//    }

    private fun Item.found() = player.unit().type.mineFloor && indexer.hasOre(this) || player.unit().type.mineWalls && indexer.hasWallOre(this)
}