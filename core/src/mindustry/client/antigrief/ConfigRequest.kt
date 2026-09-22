package mindustry.client.antigrief

import mindustry.*
import mindustry.gen.*

open class ConfigRequest @JvmOverloads constructor(@JvmField val x: Int, @JvmField val y: Int, @JvmField var value: Any?, @JvmField var isRotate: Boolean = false) : Runnable {
    @JvmOverloads constructor(build: Building, value: Any?, isRotate: Boolean = false): this(build.tileX(), build.tileY(), value, isRotate)

    override fun run() {
        val tile = Vars.world?.tile(x, y) ?: return
        val build = tile.build ?: return
        if (isRotate) Call.rotateBlock(Vars.player, build, value as Boolean)
        else Call.tileConfig(Vars.player, build, value)
    }
}
