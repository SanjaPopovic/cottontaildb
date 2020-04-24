package ch.unibas.dmi.dbis.cottontail.database.serializers

import ch.unibas.dmi.dbis.cottontail.model.values.ByteValue
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.basics.Page
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.serializer.Serializer
import org.mapdb.DataInput2
import org.mapdb.DataOutput2

object ByteValueSerializer : Serializer<ByteValue> {
    override val physicalSize: Int = Byte.SIZE_BYTES
    override val logicalSize: Int = -1

    override fun deserialize(input: DataInput2, available: Int): ByteValue = ByteValue(input.readByte())
    override fun serialize(out: DataOutput2, value: ByteValue) {
        out.writeByte(value.value.toInt())
    }

    override fun serialize(page: Page, offset: Int, value: ByteValue) {
        page.putByte(offset, value.value)
    }

    override fun deserialize(page: Page, offset: Int): ByteValue = ByteValue(page.getByte(offset))
}