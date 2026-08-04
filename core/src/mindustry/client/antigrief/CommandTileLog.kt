package mindustry.client.antigrief

import arc.Core
import arc.math.Mathf
import arc.math.geom.Vec2
import mindustry.client.utils.*
import mindustry.world.Block
import mindustry.world.Tile
import kotlin.math.min

class CommandTileLog(
    tile: Tile,
    cause: Interactor,
    val block: Block,
    val poscom: Vec2,
) : TileLog(cause) {
    override fun apply(previous: TileState) {
        previous.rotation = 0
    }

    override fun toString(): String {
        return cause.name.stripColors() + " " + Core.bundle.get("client.command") + " " + block.localizedName +
            "  to  " + Mathf.ceil(poscom.x / 8f) + "  ,  " + Mathf.ceil(poscom.y / 8f)
    }

    override fun toShortString(): String {
        val sn = cause.shortName.stripColors()
        val short = sn.subSequence(0, min(16, sn.length)).toString() + if (sn.length > 16) "..." else ""
        return "$short ${Core.bundle.get("client.command")} ${block.localizedName}"
    }
}
