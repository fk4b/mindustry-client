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
import mindustry.world.blocks.power.ConsumeGenerator
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
            set(value) { field = value.coerceAtLeast(1) }
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
        /** Block fill order. The first block type is filled before the next one. */
        @JvmField var blockPriorityOrder: Array<String> = emptyArray()
        /** Per-block item priority, stored as "block-name=item1|item2|...". */
        @JvmField var blockItemOrder: Array<String> = emptyArray()
        /** Block names that are not filled even when their category is enabled. */
        @JvmField var disabledBlocks: Array<String> = emptyArray()
        private val fallbackCategories = arrayOf("defense", "generators", "factory", "units", "recon", "other")
        /** Fuel that a filter-based generator is filled with first. Everything else it accepts is a fallback. */
        private val fuelDefaults = mapOf(
            "combustion-generator" to listOf("coal"),
            "steam-generator" to listOf("coal"),
            "rtg-generator" to listOf("thorium", "phase-fabric")
        )
        /** Blocks with item filters that must never be autofilled (heaters, cultivators, centrifuges...). */
        private val excludedAutofillBlocks = setOf(
            "heat-source", "vent-condenser", "cultivator", "heat-reactor",
            "slag-heater", "electric-heater", "atmospheric-concentrator",
            "atmosphere-condenser", "electrolyzer", "coal-centrifuge",
            "slag-centrifuge", "phase-heater", "sublimate", "heat-redirector"
        )
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
            minCoreItems = Core.settings.getInt("autotransfer-mincoreitems", 10).coerceAtLeast(1)
            delay = Core.settings.getFloat("autotransfer-transferdelay", 60F)
            minTransferTotal = Core.settings.getInt("autotransfer-mintransfertotal", 10)
            minTransfer = Core.settings.getInt("autotransfer-mintransfer", 2)
            // Drain settings, undocumented for now as drain is still experimental
            drain = Core.settings.getBool("autotransfer-drain", false)
            drainToContainers = Core.settings.getBool("autotransfer-draintocontainers", false)
            reloadPriority()
            reloadCategories()
            reloadBlockPriority()
            reloadBlockItems()
            reloadBlockEnabled()
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
        fun reloadBlockItems() {
            val raw = Core.settings.getString("autotransfer-block-items", Core.settings.getString("autotransfer-generator-fuels", ""))
            blockItemOrder = if (raw.isBlank()) emptyArray()
            else raw.split(',').map { it.trim() }.filter { it.contains('=') }.toTypedArray()
        }

        @JvmStatic
        fun reloadBlockEnabled() {
            val raw = Core.settings.getString("autotransfer-disabled-blocks", "")
            disabledBlocks = if (raw.isBlank()) emptyArray()
            else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        }

        @JvmStatic
        fun blockEnabled(block: Block): Boolean = block.name !in disabledBlocks

        @JvmStatic
        fun setBlockEnabled(block: Block, on: Boolean) {
            val next = disabledBlocks.toMutableList()
            if (on) next.remove(block.name) else if (block.name !in next) next.add(block.name)
            disabledBlocks = next.toTypedArray()
            Core.settings.put("autotransfer-disabled-blocks", disabledBlocks.joinToString(","))
        }

        @JvmStatic
        fun resetBlockEnabled() {
            disabledBlocks = emptyArray()
            Core.settings.put("autotransfer-disabled-blocks", "")
        }

        private fun savedItemNames(block: Block): List<String> {
            val prefix = block.name + "="
            val entry = blockItemOrder.firstOrNull { it.startsWith(prefix) } ?: return emptyList()
            return entry.substring(prefix.length).split('|').map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun defaultItemOrder(block: Block, accepted: Seq<Item>): Seq<Item> {
            val out = Seq<Item>()
            fuelDefaults[block.name]?.forEach { name ->
                val item = content.item(name)
                if (item != null && accepted.contains(item) && !out.contains(item)) out.add(item)
            }
            accepted.each { if (!out.contains(it)) out.add(it) }
            return out
        }

        /** Accepted items in the user's priority order. Missing items keep their default order. */
        @JvmStatic
        fun orderedAcceptedItems(block: Block): Seq<Item> {
            val accepted = acceptedItems(block)
            val out = Seq<Item>()
            savedItemNames(block).forEach { name ->
                val item = content.item(name)
                if (item != null && accepted.contains(item) && !out.contains(item)) out.add(item)
            }
            defaultItemOrder(block, accepted).each { if (!out.contains(it)) out.add(it) }
            return out
        }

        /** First item in the priority order, or null for blocks with dynamic recipes. */
        @JvmStatic
        fun preferredItem(block: Block): Item? {
            val ordered = orderedAcceptedItems(block)
            return if (ordered.size == 0) null else ordered.first()
        }

        @JvmStatic
        fun setItemPriority(block: Block, items: Seq<Item>) {
            val accepted = acceptedItems(block)
            val names = Seq<String>()
            items.each { item -> if (accepted.contains(item) && !names.contains(item.name)) names.add(item.name) }
            val prefix = block.name + "="
            val next = blockItemOrder.filterNot { it.startsWith(prefix) }.toMutableList()
            if (names.size > 0) {
                val sb = StringBuilder()
                names.each { name -> if (sb.isNotEmpty()) sb.append('|'); sb.append(name) }
                next.add(prefix + sb.toString())
            }
            blockItemOrder = next.toTypedArray()
            Core.settings.put("autotransfer-block-items", blockItemOrder.joinToString(","))
        }

        @JvmStatic
        fun setPreferredItem(block: Block, item: Item?) {
            if (item == null) {
                resetBlockItem(block)
            } else {
                val items = Seq<Item>()
                items.add(item)
                setItemPriority(block, items)
            }
        }

        @JvmStatic
        fun resetBlockItem(block: Block) {
            val prefix = block.name + "="
            blockItemOrder = blockItemOrder.filterNot { it.startsWith(prefix) }.toTypedArray()
            Core.settings.put("autotransfer-block-items", blockItemOrder.joinToString(","))
        }

        @JvmStatic
        fun resetBlockItems() {
            blockItemOrder = emptyArray()
            Core.settings.put("autotransfer-block-items", "")
            Core.settings.put("autotransfer-generator-fuels", "")
        }

        /** Every item a block accepts. For turrets this is its ammo list. */
        @JvmStatic
        fun acceptedItems(block: Block): Seq<Item> {
            val out = Seq<Item>()
            if (block is ItemTurret) {
                block.ammoTypes.each { item, _ -> if (!out.contains(item)) out.add(item) }
                return out
            }
            val cons = fuelConsumer(block) ?: return out
            collectFuelItems(cons, null, out)
            return out
        }

        private fun acceptedItems(build: Building): Seq<Item> {
            val out = Seq<Item>()
            if (build.block is ItemTurret) return acceptedItems(build.block)
            val cons = fuelConsumer(build.block) ?: return out
            collectFuelItems(cons, build, out)
            return out
        }

        /** All blocks that can be autofilled, in the user's fill order. */
        @JvmStatic
        fun fillableBlocks(): Seq<Block> {
            val all = fillableBlocksUnsorted()
            val byName = ObjectMap<String, Block>()
            all.each { byName.put(it.name, it) }
            val out = Seq<Block>()
            blockPriorityOrder.forEach { name -> byName.get(name)?.let { out.add(it) } }
            all.each { if (!out.contains(it)) out.add(it) }
            return out
        }

        /** Fillable blocks of one category, in the current block priority order. */
        @JvmStatic
        fun blocksForCategory(category: String): Seq<Block> =
            fillableBlocks().select { categoryId(it) == category }

        @JvmStatic
        fun moveBlockInCategory(block: Block, dir: Int) {
            val category = categoryId(block)
            val list = blocksForCategory(category)
            val index = list.indexOf(block)
            val next = index + dir
            if (index < 0 || next < 0 || next >= list.size) return
            list.swap(index, next)
            val all = fillableBlocks()
            val out = Seq<String>()
            categoryOrder.forEach { id ->
                if (id == category) {
                    list.each { out.add(it.name) }
                } else {
                    all.each { b -> if (categoryId(b) == id) out.add(b.name) }
                }
            }
            all.each { b -> if (!out.contains(b.name)) out.add(b.name) }
            saveBlockPriority(Array(out.size) { out.get(it) })
        }

        @JvmStatic
        fun moveCategory(category: String, dir: Int) {
            val index = categoryOrder.indexOf(category)
            val next = index + dir
            if (index < 0 || next < 0 || next >= categoryOrder.size) return
            val order = categoryOrder.copyOf()
            val swap = order[index]
            order[index] = order[next]
            order[next] = swap
            saveCategories(order)
            rebuildBlockPriorityFromCategories()
        }

        @JvmStatic
        fun rebuildBlockPriorityFromCategories() {
            val all = fillableBlocks()
            val out = Seq<String>()
            categoryOrder.forEach { id ->
                all.each { b -> if (categoryId(b) == id && !out.contains(b.name)) out.add(b.name) }
            }
            all.each { b -> if (!out.contains(b.name)) out.add(b.name) }
            saveBlockPriority(Array(out.size) { out.get(it) })
        }

        @JvmStatic
        fun resetCategorySettings(category: String) {
            val names = Seq<String>()
            blocksForCategory(category).each { names.add(it.name) }
            disabledBlocks = disabledBlocks.filterNot { it in names }.toTypedArray()
            Core.settings.put("autotransfer-disabled-blocks", disabledBlocks.joinToString(","))
            val prefixes = Seq<String>()
            names.each { prefixes.add(it + "=") }
            blockItemOrder = blockItemOrder.filterNot { entry -> prefixes.any { entry.startsWith(it) } }.toTypedArray()
            Core.settings.put("autotransfer-block-items", blockItemOrder.joinToString(","))
        }

        @JvmStatic
        fun blockPrioritySnapshot(): Array<String> = blockPriorityOrder.copyOf()

        @JvmStatic
        fun saveBlockPriority(order: Array<String>) {
            blockPriorityOrder = order.copyOf()
            Core.settings.put("autotransfer-block-priority", order.joinToString(","))
        }

        @JvmStatic
        fun resetBlockPriority() {
            blockPriorityOrder = defaultBlockOrder()
            Core.settings.put("autotransfer-block-priority", blockPriorityOrder.joinToString(","))
        }

        /** Lower rank is filled first. */
        @JvmStatic
        fun blockRank(block: Block): Int {
            val index = blockPriorityOrder.indexOf(block.name)
            return if (index >= 0) index else 10_000 + catRank(block)
        }

        private fun fillableBlocksUnsorted(): Seq<Block> {
            val out = Seq<Block>()
            content.blocks().each { block ->
                if (block.name in excludedAutofillBlocks || block.name == "nuclear-reactor") return@each
                if (block is ItemTurret) {
                    if (block.ammoTypes.size > 0) out.add(block)
                    return@each
                }
                val cons = fuelConsumer(block) ?: return@each
                if (cons is ConsumeItemExplode) return@each
                if (cons is ConsumeItemDynamic) {
                    out.add(block)
                    return@each
                }
                if (acceptedItems(block).isEmpty()) return@each
                out.add(block)
            }
            return out
        }

        private fun defaultBlockOrder(): Array<String> {
            val blocks = fillableBlocksUnsorted()
            blocks.sort { a, b ->
                val ca = catRank(a)
                val cb = catRank(b)
                if (ca != cb) ca - cb else a.localizedName.compareTo(b.localizedName)
            }
            return Array(blocks.size) { blocks.get(it).name }
        }

        @JvmStatic
        fun reloadBlockPriority() {
            val raw = Core.settings.getString("autotransfer-block-priority", "")
            val saved = if (raw.isBlank()) emptyArray()
            else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

            val all = fillableBlocksUnsorted()
            val validNames = HashSet<String>()
            all.each { validNames.add(it.name) }

            val savedBlocks = Seq<Block>()
            saved.forEach { name ->
                if (name in validNames) content.block(name)?.let { if (!savedBlocks.contains(it)) savedBlocks.add(it) }
            }
            val defaultBlocks = Seq<Block>()
            defaultBlockOrder().forEach { name ->
                content.block(name)?.let { if (!defaultBlocks.contains(it)) defaultBlocks.add(it) }
            }

            val out = Seq<String>()
            categoryOrder.forEach { id ->
                savedBlocks.each { b -> if (categoryId(b) == id && !out.contains(b.name)) out.add(b.name) }
                defaultBlocks.each { b -> if (categoryId(b) == id && !out.contains(b.name)) out.add(b.name) }
            }
            savedBlocks.each { b -> if (!out.contains(b.name)) out.add(b.name) }
            defaultBlocks.each { b -> if (!out.contains(b.name)) out.add(b.name) }

            blockPriorityOrder = Array(out.size) { out.get(it) }
            Core.settings.put("autotransfer-block-priority", blockPriorityOrder.joinToString(","))
        }

        private fun fuelConsumer(block: Block): Consume? =
            block.findConsumer<Consume> { c -> (c is ConsumeItems || c is ConsumeItemFilter || c is ConsumeItemDynamic) && c !is ConsumeItemExplode }
                ?: block.findConsumer<Consume> { c -> c is ConsumeItems || c is ConsumeItemFilter || c is ConsumeItemDynamic }

        private fun collectFuelItems(cons: Consume, build: Building?, out: Seq<Item>) {
            if (cons is ConsumeItemExplode) return
            when (cons) {
                is ConsumeItems -> {
                    cons.items.forEach { stack -> if (stack != null && stack.item != null && !out.contains(stack.item)) out.add(stack.item) }
                }
                is ConsumeItemFilter -> {
                    content.items().each { item -> if (cons.filter.get(item) && !out.contains(item)) out.add(item) }
                }
                is ConsumeItemDynamic -> {
                    if (build != null) cons.items.get(build).forEach { stack -> if (stack != null && stack.item != null && !out.contains(stack.item)) out.add(stack.item) }
                }
            }
        }

        @JvmStatic
        fun resetSettings() {
            fromCores = true
            Core.settings.put("autotransfer-fromcores", true)
            fromContainers = true
            Core.settings.put("autotransfer-fromcontainers", true)
            minCoreItems = 10
            Core.settings.put("autotransfer-mincoreitems", 10)
            delay = 60F
            Core.settings.put("autotransfer-transferdelay", 60F)
            savePriority(fallbackPriority.copyOf())
            saveCategories(fallbackCategories.copyOf())
            disabledCategories = emptyArray()
            Core.settings.put("autotransfer-off", "")
            resetBlockPriority()
            resetBlockItems()
            resetBlockEnabled()
        }

        /** Kept for older callers. */
        @JvmStatic
        fun resetPriority() = resetSettings()

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
            is ConsumeGenerator -> "generators"
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
        // MinePath owns the unit while it has to travel to ore outside the core radius.
        // Any autofill action there steals the stack or pulls the unit back to the core.
        if (minePathBlocksAutofill()) return
        if (ratelimitRemaining <= 1) return // Leave one config for other stuff
        timer += Time.delta
        if (timer < delay) return
        timer = 0f
        counts.fill(0) // reset needed item counters
        ammoCounts.fill(0)
        dpsCounts.fill(0f)
        itemCat.fill(Int.MAX_VALUE)
        if (!justTransferred && drain && drain()) {
            justTransferred = false
            return
        }
        justTransferred = false
        transfer(false)
    }

    /** True while MinePath is heading to a vein farther from the core than the transfer radius. */
    private fun minePathBlocksAutofill(): Boolean {
        val path = Navigation.currentlyFollowing as? MinePath ?: return false
        val ore = path.tile ?: return false
        val core = player.closestCore() ?: return false
        return !ore.within(core, mineTransferRange - tilesize * 10f)
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
     * Port of the QoL-Control (TyTXexebe) autofill loop.
     * While a stack is held it is put into the best nearby consumer; with empty hands the
     * best needed item is requested from the core or a container. Turrets are topped up
     * only below 85% ammo, generators use their fuel defaults (coal / thorium), and the
     * whole loop pauses while MinePath is travelling to a far vein.
     * Returns true when a packet was sent.
     */
    private fun transfer(rush: Boolean): Boolean {
        val unit = player.unit() ?: return false
        if (unit.type.itemCapacity <= 0) return false

        val buildTree = player.team().data().buildingTree ?: return false
        val heldItem = unit.item()
        val held = unit.stack.amount

        buildTree.intersect(player.x - buildingRange, player.y - buildingRange, buildingRange * 2, buildingRange * 2, builds.clear())

        var source: Building? = null
        if (fromCores) {
            val closest = player.closestCore()
            if (closest != null && player.within(closest, buildingRange)) source = closest
        }
        if (source == null && fromContainers) {
            source = builds.select {
                it.block is StorageBlock && it.block !is CoreBlock && player.within(it, buildingRange) && it.items.total() > 0
            }.minByOrNull { it.dst2(unit) }
        }
        val nearSource = source != null && player.within(source, buildingRange)

        val targets = builds.select {
            it.team == player.team()
                && it !is NuclearReactorBuild
                && it.block.name !in excludedAutofillBlocks
                && player.within(it, buildingRange + it.block.size * 4f)
                && allows(it.block)
                && blockEnabled(it.block)
                && it.block.findConsumer<Consume?> { c -> c is ConsumeItems || c is ConsumeItemFilter || c is ConsumeItemDynamic } != null
        }.sort { a, b ->
            val ra = blockRank(a.block)
            val rb = blockRank(b.block)
            if (ra != rb) ra - rb else a.dst2(unit).compareTo(b.dst2(unit))
        }

        // Held stack: xexebe deposits it even when the unit is far from the core. This is
        // what lets a miner carry coal to a generator that is outside the core radius.
        if (held > 0 && heldItem != null) {
            val target = targets.firstOrNull { autofillItems(it).contains(heldItem) && needsAutofill(it, heldItem) }
            if (target != null && ratelimitRemaining > 1) {
                Call.transferInventory(player, target)
                justTransferred = true
                return true
            }
            // Nobody wants the stack: put it back into the source if one is in reach.
            if (source != null && nearSource && ratelimitRemaining > 1) {
                Call.transferInventory(player, source)
                justTransferred = true
                return true
            }
            return false
        }

        val src = source ?: return false
        if (!player.within(src, buildingRange)) return false

        var bestTarget: Building? = null
        var bestItem: Item? = null

        targetLoop@ for (target in targets) {
            val minHave = if (src is CoreBlock.CoreBuild) minCoreItems.coerceAtLeast(1) else 1
            for (want in autofillItems(target)) {
                if (!src.items.has(want, minHave)) continue
                if (!needsAutofill(target, want)) continue
                bestTarget = target
                bestItem = want
                break@targetLoop
            }
        }

        val target = bestTarget
        val want = bestItem
        if (target != null && want != null && ratelimitRemaining > 1) {
            val amount = min(unit.type.itemCapacity, src.items.get(want))
            if (amount > 0) {
                Call.requestItem(player, src, want, amount)
                justTransferred = true
                return true
            }
        }
        return false
    }

    /** Items a building can be filled with, in the order they should be tried. */
    private fun autofillItems(build: Building): Seq<Item> {
        val block = build.block
        val out = Seq<Item>()

        if (block is ItemTurret) {
            orderedAcceptedItems(block).each { if (!out.contains(it)) out.add(it) }
            return out
        }

        val accepted = acceptedItems(build)
        orderedAcceptedItems(block).each { if (accepted.contains(it) && !out.contains(it)) out.add(it) }
        accepted.each { if (!out.contains(it)) out.add(it) }
        return out
    }

    private fun needsAutofill(build: Building, item: Item): Boolean {
        if (!build.acceptItem(build, item)) return false
        if (build is ItemTurret.ItemTurretBuild) {
            val turret = build.block as ItemTurret
            return build.totalAmmo < turret.maxAmmo * 0.85f
        }
        val cap = build.block.itemCapacity
        return cap > 0 && build.items.get(item) < cap * 0.85f
    }

    /** Extended UI: a turret is filled only while its ammo is empty. Other blocks use the current recipe. Item rank picks which resource. */
    private fun neededItem(build: Building, source: Building, unit: mindustry.gen.Unit): Item? {
        val block = build.block
        val minHave = if (source is CoreBlock.CoreBuild) minCoreItems.coerceAtLeast(1) else 1
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
