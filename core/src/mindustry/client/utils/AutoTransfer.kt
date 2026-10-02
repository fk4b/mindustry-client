package mindustry.client.utils

import arc.*
import arc.math.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.client.ClientVars.*
import mindustry.client.navigation.*
import mindustry.content.*
import mindustry.entities.bullet.*
import mindustry.game.*
import mindustry.gen.*
import mindustry.graphics.*
import mindustry.type.*
import mindustry.world.*
import mindustry.world.blocks.defense.*
import mindustry.world.blocks.defense.turrets.*
import mindustry.world.blocks.power.NuclearReactor.*
import mindustry.world.blocks.production.*
import mindustry.world.blocks.production.Drill.*
import mindustry.world.blocks.production.GenericCrafter.*
import mindustry.world.blocks.storage.*
import mindustry.world.blocks.storage.Unloader.*
import mindustry.world.blocks.units.*
import mindustry.world.consumers.*
import kotlin.math.*

/** An auto transfer setup based on Ferlern/extended-ui */
class AutoTransfer {
    companion object Settings {
        // All of these settings (aside from debug) are overwritten on init()
        @JvmField var enabled = false
        var fromCores = false
        var fromContainers = false
        var minCoreItems = -1
        var delay = -1F
        var debug = false
        var minTransferTotal = -1
        var minTransfer = -1
        var drain = false
        var drainToContainers = false
        private val fallbackPriority = arrayOf(
            "silicon", "surge-alloy", "phase-fabric", "plastanium", "thorium",
            "pyratite", "blast-compound", "spore-pod", "titanium", "graphite",
            "metaglass", "coal", "beryllium", "tungsten", "oxide", "carbide",
            "copper", "lead", "sand", "scrap"
        )
        @JvmField var priorityOrder: Array<String> = fallbackPriority.copyOf()
        private val fallbackCategories = arrayOf("defense", "factory", "units", "recon", "other")
        @JvmField var categoryOrder: Array<String> = fallbackCategories.copyOf()
        /** Category ids that must not be filled. Empty means every type is on. */
        @JvmField var disabledCategories: Array<String> = emptyArray()
        /** Freshly placed defense turrets are filled until this Time.time, without the usual pause. */
        @JvmField var defenseRushUntil = 0f
        private var defenseListen = false

        fun init() {
            // Main settings
            enabled = Core.settings.getBool("autotransfer", false)
            fromCores = Core.settings.getBool("autotransfer-fromcores", true)
            fromContainers = Core.settings.getBool("autotransfer-fromcontainers", true)
            minCoreItems = Core.settings.getInt("autotransfer-mincoreitems", 10)
            delay = Core.settings.getFloat("autotransfer-transferdelay", 60F)
            minTransferTotal = Core.settings.getInt("autotransfer-mintransfertotal", 10)
            minTransfer = Core.settings.getInt("autotransfer-mintransfer", 2)
            // Drain settings, undocumented for now as drain is still experimental
            drain = Core.settings.getBool("autotransfer-drain", false)
            drainToContainers = Core.settings.getBool("autotransfer-draintocontainers", false)
            reloadPriority()
            reloadCategories()
            reloadFilters()
            listenDefense()
        }

        /** True only when the first enabled destination type is defense. */
        @JvmStatic
        fun defenseFirst(): Boolean {
            for (id in categoryOrder) {
                if (id !in disabledCategories) return id == "defense"
            }
            return false
        }

        private fun listenDefense() {
            if (defenseListen) return
            defenseListen = true
            Events.on(EventType.BlockBuildEndEvent::class.java) { e ->
                if (!enabled || e.breaking) return@on
                val p = player ?: return@on
                val u = p.unit() ?: return@on
                if (p.dead() || e.team != p.team()) return@on
                val tile = e.tile ?: return@on
                if (!u.within(tile, itemTransferRange)) return@on
                val block = tile.block()
                if (block !is ItemTurret || categoryId(block) != "defense" || !allows(block) || !defenseFirst()) return@on
                defenseRushUntil = Time.time + 240f
            }
        }

        @JvmStatic
        fun reloadPriority() {
            val raw = Core.settings.getString("autotransfer-priority", "")
            priorityOrder = if (raw.isBlank()) fallbackPriority.copyOf()
            else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        }

        @JvmStatic
        fun prioritySnapshot(): Array<String> = priorityOrder.copyOf()

        @JvmStatic
        fun savePriority(order: Array<String>) {
            priorityOrder = order.copyOf()
            Core.settings.put("autotransfer-priority", order.joinToString(","))
        }

        @JvmStatic
        fun resetPriority() {
            savePriority(fallbackPriority.copyOf())
            saveCategories(fallbackCategories.copyOf())
            disabledCategories = emptyArray()
            Core.settings.put("autotransfer-off", "")
        }

        @JvmStatic
        fun categorySnapshot(): Array<String> = categoryOrder.copyOf()

        @JvmStatic
        fun saveCategories(order: Array<String>) {
            categoryOrder = order.copyOf()
            Core.settings.put("autotransfer-cats", order.joinToString(","))
        }

        @JvmStatic
        fun reloadCategories() {
            val known = fallbackCategories
            val raw = Core.settings.getString("autotransfer-cats", "")
            if (raw.isNullOrBlank()) {
                categoryOrder = known.copyOf()
                return
            }
            val parsed = raw.split(',').map { it.trim() }.filter { it in known }.distinct().toMutableList()
            for (id in known) if (id !in parsed) {
                var insert = parsed.size
                val at = known.indexOf(id)
                for (i in at - 1 downTo 0) {
                    val prev = parsed.indexOf(known[i])
                    if (prev >= 0) {
                        insert = prev + 1
                        break
                    }
                }
                parsed.add(insert, id)
            }
            categoryOrder = parsed.toTypedArray()
        }

        /** Old trash checkboxes meant “none checked = fill everything”. A saved off-list replaces them. */
        @JvmStatic
        fun reloadFilters() {
            if (Core.settings.has("autotransfer-off")) {
                val raw = Core.settings.getString("autotransfer-off", "")
                disabledCategories = raw.split(',').map { it.trim() }.filter { it in fallbackCategories }.distinct().toTypedArray()
                return
            }
            val old = listOf(
                "autotransfer-t-turrets" to "defense",
                "autotransfer-t-prod" to "factory",
                "autotransfer-t-units" to "units",
                "autotransfer-t-recons" to "recon"
            )
            val anyOn = old.any { Core.settings.has(it.first) && Core.settings.getBool(it.first) }
            if (!anyOn) {
                disabledCategories = emptyArray()
                return
            }
            val off = old.filter { !Core.settings.getBool(it.first, false) }.map { it.second }.toMutableList()
            off.add("other")
            disabledCategories = off.toTypedArray()
        }

        @JvmStatic
        fun categoryEnabled(id: String): Boolean = id !in disabledCategories

        @JvmStatic
        fun setCategoryEnabled(id: String, on: Boolean) {
            if (id !in fallbackCategories) return
            val next = disabledCategories.toMutableList()
            if (on) next.remove(id) else if (id !in next) next.add(id)
            disabledCategories = next.toTypedArray()
            Core.settings.put("autotransfer-off", disabledCategories.joinToString(","))
        }

        @JvmStatic
        fun allows(block: Block): Boolean = categoryId(block) !in disabledCategories

        /** Lower rank is filled first. Defense, factories and unit factories are separate from the item list. */
        @JvmStatic
        fun catRank(block: Block): Int {
            val index = categoryOrder.indexOf(categoryId(block))
            return if (index < 0) 100 else index
        }

        private fun categoryId(block: Block): String = when (block) {
            is BaseTurret, is MendProjector, is RegenProjector, is ForceProjector,
            is OverdriveProjector, is ShockwaveTower, is RepairTurret, is Wall -> "defense"
            is Reconstructor -> "recon"
            is UnitBlock, is UnitAssembler -> "units"
            is GenericCrafter, is Separator -> "factory"
            else -> "other"
        }

        private fun rank(item: Item): Int {
            val index = priorityOrder.indexOf(item.name)
            return if (index < 0) 10_000 else index
        }
    }

    val builds = Seq<Building>(false) // Not ordered as we sort *after* mutation is finished.
    val containers = Seq<Building>()
    var item: Item? = null
    var timer = 0F
    val counts = IntArray(content.items().size)
    val ammoCounts = IntArray(content.items().size)
    /** Best destination category that still needs each item. Lower is earlier in the category list. */
    val itemCat = IntArray(content.items().size)
    val dpsCounts = FloatArray(content.items().size)
    var core: Building? = null
    var justTransferred = false
    /** 0 idle, 1 dumping a wrong stack, 2 waiting until a pickup grows the stack, 3 waiting until a deposit changes the stack. */
    private var rushPhase = 0
    private var rushPhaseUntil = 0f
    private var rushPhaseItem: Item? = null
    private var rushStackAmount = 0

    fun draw() {
        if (!debug || player.unit().item() == null) return
        builds.forEach {
            val accepted = it.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit())
            Drawf.select(it.x, it.y, it.block.size * tilesize / 2f + 2f, if (accepted >= Mathf.clamp(player.unit().stack.amount, 1, 5)) Pal.place else Pal.noplace)
        }
    }

    fun update() {
        if (!enabled) {
            defenseRushUntil = 0f
            rushPhase = 0
            return
        }
        if (state.rules.onlyDepositCore) return
        if (player == null || player.dead() || player.unit() == null) return
        if (!defenseFirst()) {
            defenseRushUntil = 0f
            rushPhase = 0
        } else if (rushPhase != 0) {
            val amountNow = player.unit().stack.amount
            val timedOut = Time.time > rushPhaseUntil
            // item() stays set after the stack hits 0, so an empty unit is amount == 0.
            val done = when (rushPhase) {
                1 -> amountNow <= 0 || timedOut
                2 -> amountNow > rushStackAmount || timedOut
                else -> amountNow < rushStackAmount || amountNow <= 0 || timedOut
            }
            if (!done) return
            rushPhase = 0
            rushPhaseItem = null
        }
        if (ratelimitRemaining <= 1) return // Leave one config for other stuff
        val rush = defenseRushUntil > 0f && Time.time <= defenseRushUntil
        if (!rush) {
            if (defenseRushUntil > 0f) defenseRushUntil = 0f
            timer += Time.delta
            if (timer < delay) return
        }
        counts.fill(0) // reset needed item counters
        ammoCounts.fill(0)
        dpsCounts.fill(0f)
        itemCat.fill(Int.MAX_VALUE)
        if (!rush && !justTransferred && drain && drain()) {
            timer = 0f
            return
        }
        justTransferred = false
        if (transfer(rush)) timer = 0f
    }

    /** One in-flight ammo action, so a rush does not send another request every frame. */
    private fun armRush(phase: Int, want: Item?, stackBefore: Int) {
        rushPhase = phase
        rushPhaseItem = want
        rushPhaseUntil = Time.time + 50f
        rushStackAmount = stackBefore
        defenseRushUntil = Time.time + 240f
    }

    /**
     * One Extended UI action per pause: deposit the held stack, put a wrong stack back, or take one item.
     * A remainder below 5 of the item buildings still want is topped up from the source. Hands must be
     * empty before a different item can be taken, because a unit holds only one stack.
     * Category order picks the building. The item list picks the resource inside that building.
     * Returns true when a packet was sent.
     */
    private fun transfer(rush: Boolean): Boolean {
        core = if (fromCores) player.closestCore() else null
        if (Navigation.currentlyFollowing is MinePath) { // Only allow autotransfer + minepath when within mineTransferRange
            if (core != null && (Navigation.currentlyFollowing as MinePath).tile?.within(core, mineTransferRange - tilesize * 10) != true) return false
        } // Ngl this looks spaghetti

        val buildTree = player.team().data().buildingTree ?: return false
        val unit = player.unit() ?: return false
        val heldItem = unit.item()
        val held = unit.stack.amount

        buildTree.intersect(player.x - buildingRange, player.y - buildingRange, buildingRange * 2, buildingRange * 2, builds.clear())

        if (fromContainers && (core == null || !player.within(core, buildingRange))) core = containers.selectFrom(builds) { it.block is StorageBlock && (item == null || it.items.has(item)) }.min { it -> it.dst(player) }

        builds.retainAll {
            it.block.findConsumer<Consume?> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic } != null
                && it !is NuclearReactorBuild
                && player.within(it, buildingRange)
                && allows(it.block)
        }
        if (rush) {
            builds.retainAll { it.block is ItemTurret && categoryId(it.block) == "defense" }
            if (builds.size == 0) {
                defenseRushUntil = 0f
                timer = 0f
                return false
            }
        }

        val source = core
        var wantItem: Item? = null
        var wantBuild: Building? = null
        var wantCat = Int.MAX_VALUE
        var wantRank = Int.MAX_VALUE
        var wantDist = Float.MAX_VALUE
        if (source != null) {
            builds.each { build ->
                val need = neededItem(build, source, unit) ?: return@each
                val cat = catRank(build.block)
                val itemRank = rank(need)
                val dist = build.dst(unit)
                val better = cat < wantCat || (cat == wantCat && (itemRank < wantRank || (itemRank == wantRank && dist < wantDist)))
                if (!better) return@each
                wantCat = cat
                wantRank = itemRank
                wantDist = dist
                wantItem = need
                wantBuild = build
            }
        }

        if (held > 0 && heldItem != null) {
            val needBuild = wantBuild
            val sameWant = needBuild != null && wantItem == heldItem
            val accepted = if (sameWant) needBuild.acceptStack(heldItem, held, unit) else 0
            // A full enough stack goes into the building. A unit that cannot hold 5 still delivers what it has.
            val dest = when {
                sameWant && (accepted >= 5 || (accepted > 0 && unit.maxAccepted(heldItem) <= 0)) -> needBuild
                wantItem == null -> leftoverTarget(unit, heldItem, held)
                else -> null
            }
            if (dest != null && ratelimitRemaining > 1) {
                Call.transferInventory(player, dest)
                justTransferred = true
                if (rush) armRush(3, heldItem, held)
                return true
            }
            if (wantItem != null && wantItem != heldItem && source != null && player.within(source, buildingRange) && ratelimitRemaining > 1) {
                Call.transferInventory(player, source)
                if (net.server() && (player.unit()?.stack?.amount ?: 0) > 0) Call.dropItem(0f)
                justTransferred = true
                if (rush) armRush(1, wantItem, held)
                return true
            }
            // Fewer than 5 of the wanted item cannot be deposited, and that leftover blocked a new pickup.
            if (sameWant && held < 5 && unit.maxAccepted(heldItem) > 0 && source != null && player.within(source, buildingRange) && ratelimitRemaining > 1) {
                Call.requestItem(player, source, heldItem, 999)
                item = null
                justTransferred = true
                if (rush) armRush(2, heldItem, held)
                return true
            }
            if (rush && wantItem == null) {
                defenseRushUntil = 0f
                timer = 0f
            }
            return false
        }

        if (wantItem != null && source != null && player.within(source, buildingRange) && ratelimitRemaining > 1) {
            Call.requestItem(player, source, wantItem, 999)
            item = null
            justTransferred = true
            if (rush) armRush(2, wantItem, held)
            return true
        }
        if (rush) {
            defenseRushUntil = 0f
            timer = 0f
        }
        return false
    }

    /** Extended UI: a turret is filled only while its ammo is empty. Other blocks use the current recipe. Item rank picks which resource. */
    private fun neededItem(build: Building, source: Building, unit: mindustry.gen.Unit): Item? {
        val block = build.block
        val minHave = if (source is CoreBlock.CoreBuild) minCoreItems else 1
        var best: Item? = null
        var bestRank = Int.MAX_VALUE
        fun consider(candidate: Item?) {
            if (candidate == null || candidate == Items.blastCompound) return
            if (!source.items.has(candidate, minHave)) return
            if (build.acceptStack(candidate, 20, unit) < 5) return
            val itemRank = rank(candidate)
            if (itemRank < bestRank) {
                bestRank = itemRank
                best = candidate
            }
        }
        if (block is ItemTurret) {
            if (build !is ItemTurret.ItemTurretBuild || !build.ammo.isEmpty) return null
            block.ammoTypes.each { ammoItem, _ -> consider(ammoItem) }
            return best
        }
        if (block is UnitFactory && build is UnitFactory.UnitFactoryBuild) {
            if (build.currentPlan < 0 || build.currentPlan >= block.plans.size) return null
            for (stack in block.plans.get(build.currentPlan).requirements) consider(stack.item)
            return best
        }
        when (val cons = block.findConsumer<Consume> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic }) {
            is ConsumeItems -> {
                if (cons.booster) return null
                for (stack in cons.items) consider(stack.item)
            }
            is ConsumeItemFilter -> content.items().each { candidate -> if (cons.filter.get(candidate)) consider(candidate) }
            is ConsumeItemDynamic -> for (stack in cons.items.get(build)) consider(stack.item)
            else -> return null
        }
        return best
    }

    /** A held stack nobody is waiting on still goes into the earliest category that can take at least 5. */
    private fun leftoverTarget(unit: mindustry.gen.Unit, heldItem: Item, held: Int): Building? {
        var best: Building? = null
        var bestCat = Int.MAX_VALUE
        var bestDist = Float.MAX_VALUE
        builds.each { build ->
            if (build.acceptStack(heldItem, held, unit) < 5) return@each
            val cat = catRank(build.block)
            val dist = build.dst(unit)
            if (best == null || cat < bestCat || (cat == bestCat && dist < bestDist)) {
                best = build
                bestCat = cat
                bestDist = dist
            }
        }
        return best
    }

    /** Transfers outputs from blocks into core/containers */
    private fun drain(): Boolean { // FINISHME: Until this class is refactored to have a more generic input output system I'm just gonna copy a lot of code into this function
        core = player.closestCore() ?: return false
        val nearCore = player.within(core, itemTransferRange)
        if (!nearCore) core = null

        val buildTree = player.team().data().buildingTree ?: return false
        buildTree.intersect(player.x - itemTransferRange, player.y - itemTransferRange, itemTransferRange * 2, itemTransferRange * 2, builds.clear()) // grab all buildings in range

        val bestContainers = if (!nearCore && drainToContainers) findDrainDestinations() else emptyArray() // This uses the nearby builds so we do this after the intersect

        val nonContainerBuilds = builds.select { it.block.findConsumer<Consume?> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic } != null && it !is NuclearReactorBuild && player.within(it, itemTransferRange) }
            .sort { b -> -b.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit()).toFloat() }

        nonContainerBuilds.each { processTransferTarget(it, 0) }

        val nonContainerDrainCounts = counts.copyOf() // Direct to factory drain targets. This is scuffed but oh well
        for ((index, i) in ammoCounts.withIndex()) nonContainerDrainCounts[index] += i // Include turret ammo counts as they're separate FINISHME: hack

        // Find the drainable items
        counts.fill(0)
        processDrainSources()

        var maxID = -1
        var maxCount = 0
        if (nearCore) { // Draining to core: Drain as much as possible always (without overfilling cores)
            val playerCap = player.unit().itemCapacity()
            val reasonableCoreLimit = (core!! as CoreBlock.CoreBuild).storageCapacity - 300 - playerCap * 3 // Why is this reasonable? Because it feels right. There is no other reason.
            for (i in counts.indices) {
                val count = counts[i]
                if (count > maxCount && core!!.items.get(i) < reasonableCoreLimit) {
                    maxID = i
                    maxCount = count
                }
            }
        } else { // Draining to multiple inventories: Drain the thing that is most needed FINISHME: We should make the whole autotransfer/drain system smart enough to select the highest average items per transfer instead of just moving the most items. Moving 10 items to 3 inventories is less worth it than moving 9 items to 2 inventories as it will exhaust more ratelimit.
            for (i in nonContainerDrainCounts.indices) {
                val count = nonContainerDrainCounts[i]
                if (count > maxCount && counts[i] > minTransferTotal) {
                    maxID = i
                    maxCount = count
                }
            }
            // Nothing to drain to factories: Drain to a single container with a configured unloader
            if (maxID == -1 && drainToContainers) {
                for (i in counts.indices) {
                    val count = counts[i]
                    if (count > maxCount && bestContainers[i] != null) {
                        maxID = i
                        maxCount = count
                    }
                }
                if (maxID != -1) core = bestContainers[maxID]
            }
        }
        if (maxID == -1) return false // No core/container/factory was found, perform a normal transfer round instead

        item = if (counts[maxID] >= minTransferTotal) content.item(maxID) else null
        if (item == null) return false

        maxCount = maxCount.coerceAtMost(player.unit().maxAccepted(item))
        builds.sort { b -> b.items[maxID].toFloat() - b.getMaximumAccepted(item) }.forEach {
            if (ratelimitRemaining <= 1 || it.items[maxID] < minTransfer || maxCount < minTransfer) return@forEach // No ratelimit left or this building doesn't have enough of the item or the player unit is full

            Call.requestItem(player, it, item, maxCount)
            maxCount -= it.items[maxID]
        }

        Time.run(delay/2F) {
            if (player.unit() == null) return@run // FINISHME: Should we reset the delay?
            if (core != null) { // Standard single target drain
                if (ratelimitRemaining > 1 && (maxCount != player.unit().maxAccepted(item) || maxCount == 0)) { // If theres ratelimit remaining and the player has grabbed anything or if the player is holding something else
                    if (maxCount == 0) { // We're holding something else and we need to dispose of it somehow
                        justTransferred = true // Force an autotransfer next time, draining probably won't fix much
                        timer = delay // Immediately pick up items on the next frame
                    }
                    if (core!!.getMaximumAccepted(item) > 0) Call.transferInventory(player, core) // Drain to the block
                }
            } else { // Drain into (possibly) multiple buildings
                var held = counts[maxID] // FINISHME: This number will probably be wrong, we should somehow fix this to save ratelimit
                nonContainerBuilds.forEach {
                    if (ratelimitRemaining <= 1) return@forEach
                    held = depositIntoBuilding(it, held, player.unit()?.item())
                }
            }
        }

        return true
    }

    /** Gathers the counts for all drainable buildings. */
    private fun processDrainSources() {
        for (i in builds.size - 1 downTo 0) {
            when (val build = builds[i]) {
                is GenericCrafterBuild -> { // Crafters that are near full
                    if (!build.block.outputsItems()) builds.remove(i)
                    else if ((build.block as GenericCrafter).outputItems.any { (build.items[it.item] + it.amount) >= build.block.itemCapacity }) (build.block as GenericCrafter).outputItems.forEach { counts[it.item.id.toInt()] += build.items[it.item.id.toInt()] } // FINISHME: Use the item cap instead of shouldConsume as shouldConsume is false for disabled blocks which will cause transfer attempts not to mention that shouldConsume does more work than needed.

                }
                is DrillBuild -> { // Drills that are full
                    if (build.dominantItem == null || build.items.total() < build.block.itemCapacity) builds.remove(i)
                    else counts[build.dominantItem.id.toInt()] += build.items.total() // FINISHME: This can likely be wrong but it shouldn't matter, right?
                }
                else -> builds.remove(i)
            }
        }
    }

    /** Finds the best StorageBlock to drain to for each building. */
    private fun findDrainDestinations(): Array<Building?> {
        val has = BooleanArray(content.items().size) // We don't really care about these allocations, honestly
        val loadables = arrayOfNulls<Building>(content.items().size) // Array of the most empty container for each item type
        builds.each {
            if (it.block !is StorageBlock || it.block is CoreBlock || !player.within(it, itemTransferRange)) return@each
            has.fill(false)
            for (i in 0 ..< it.proximity.size) { // Returning from a Seq loop creates garbage, using a for i loop solves this
                val prox = it.proximity[i]
                if (prox !is UnloaderBuild) continue
                if (prox.sortItem == null) return@each // Nulloaders will cause issues, ignore containers with them FINISHME: Instead of fully ignoring them, we should just insert items that are already in the container
                has[prox.sortItem.id.toInt()] = true
            }

            val cap = it.block.itemCapacity
            for (i in has.indices) { // Iterate all containers for this item
                if (has[i]) {
                    if (loadables[i] == null) { // First container for this item, set it up
                        loadables[i] = it
                        continue
                    }
                    val container = loadables[i]!!
                    if (cap - it.items[i] > container.block.itemCapacity - container.items[i]) loadables[i] = it
                }
            }
        }
        return loadables
    }

    /** Attempts to make a deposit. Returns the remaining [held] value. */
    private fun depositIntoBuilding(build: Building, held: Int, heldItem: Item?): Int {
        if (held <= 0 || heldItem == null
        || heldItem == Items.blastCompound && build.block.findConsumer<ConsumeItems> { it is ConsumeItemExplode } != null // Don't explode things
        || build.block.findConsumer<ConsumeItems> { it.booster && it is ConsumeItems && it.items.any { it.item == heldItem } } != null // Don't provide boosters
        ) return held
        val accepted = build.acceptStack(heldItem, player.unit().stack.amount, player.unit())

        if (accepted <= 0) return held // FINISHME: Shouldn't we be enforcing minTransfer here too?
        Call.transferInventory(player, build)
        return held - accepted
    }

    /** Adds the possible deposits for the [build] to [counts], [ammoCounts], and [dpsCounts] as needed. */
    private fun processTransferTarget(build: Building, minItems: Int) {
        fun hasMinItems(item: Item, min: Int = minItems) = minItems == 0 || core!!.items.has(item, min)
        val rank = catRank(build.block)
        fun mark(item: Item) {
            val id = item.id.toInt()
            if (rank < itemCat[id]) itemCat[id] = rank
        }

        when (val cons = build.block.findConsumer<Consume> { (it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic) && it !is ConsumeItemExplode } ?: build.block.findConsumer { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic }) { // Cursed af
            is ConsumeItems -> {
                cons.items.forEach { i ->
                    if (cons.booster) return@forEach // Don't boost menders, projectors or overdrives
                    val acceptedC = build.acceptStack(i.item, build.getMaximumAccepted(i.item), player.unit())
                    if (acceptedC >= minTransfer && hasMinItems(i.item, max(i.amount, minItems))) {
                        counts[i.item.id.toInt()] += acceptedC
                        mark(i.item)
                    }
                }
            }
            is ConsumeItemFilter -> {
                content.items().each { i ->
                    val acceptedC = if (i == Items.blastCompound && build.block.findConsumer<Consume> { it is ConsumeItemExplode } != null) 0 else build.acceptStack(i, Int.MAX_VALUE, player.unit())
                    if (acceptedC >= minTransfer && build.block.consumesItem(i) && hasMinItems(i)) {
                        if (build.block is ItemTurret) { // Turrets have varying ammo, add an offset to prioritize some than others
                            ammoCounts[i.id.toInt()] += acceptedC
                            dpsCounts[i.id.toInt()] += acceptedC * getAmmoScore((build.block as? ItemTurret)?.ammoTypes?.get(i))
                        } else {
                            counts[i.id.toInt()] += acceptedC
                        }
                        mark(i)
                    }
                }
            }
            is ConsumeItemDynamic -> {
                cons.items.get(build).forEach { i -> // Get the current requirements
                    val acceptedC = build.getMaximumAccepted(i.item) - build.items.get(i.item)
                    if (acceptedC >= minTransfer && hasMinItems(i.item, max(i.amount, minItems))) {
                        counts[i.item.id.toInt()] += acceptedC
                        mark(i.item)
                    }
                }
            }
            else -> throw IllegalStateException("This should never happen. Report this.")
        }
    }

    private fun getAmmoScore(ammo: BulletType?): Float {
        return ammo?.estimateDPS() ?: 0f
        /* Commented out for future reference in case I do need my own dps estimation function
//        return (((ammo.damage * if (ammo.pierceBuilding || ammo.pierce) ammo.pierceCap else 1) +
//                    ammo.splashDamage +
//                    ammo.fragBullets * getAmmoScore(ammo.fragBullet)
//                ) * ammo.ammoMultiplier * ammo.reloadMultiplier).toInt()
         */
    }
}
