package mindustry.client.communication

import mindustry.client.utils.*
import java.nio.*
import kotlin.random.*

/**
 * Focus mark shared between client users: "shoot this".
 * [kind]: 0 = clear the sender's mark, 1 = unit (targetId = unit id), 2 = building (targetId = tile pos).
 */
class FocusMarkTransmission(val kind: Int, val targetId: Int, val x: Float, val y: Float) : Transmission {

    override var id = Random.nextLong()
    override val secureOnly: Boolean = false

    private constructor(buf: ByteBuffer) : this(buf.int, buf.int, Float.fromBits(buf.int), Float.fromBits(buf.int))

    constructor(input: ByteArray, id: Long, @Suppress("UNUSED_PARAMETER") senderID: Int) : this(input.buffer()) {
        this.id = id
    }

    override fun serialize() = kind.toBytes() + targetId.toBytes() + x.toBits().toBytes() + y.toBits().toBytes()
}
