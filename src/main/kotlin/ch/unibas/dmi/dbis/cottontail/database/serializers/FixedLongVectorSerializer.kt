package ch.unibas.dmi.dbis.cottontail.database.serializers

import ch.unibas.dmi.dbis.cottontail.model.values.LongVectorValue
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.basics.Page
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.serializer.Serializer
import org.mapdb.DataInput2
import org.mapdb.DataOutput2

/**
 * A [Serializer] for [LongVectorValue]s that are fixed in length.
 *
 * @author Ralph Gasser
 * @version 1.0
 */
class FixedLongVectorSerializer(override val logicalSize: Int): Serializer<LongVectorValue> {
    override val physicalSize: Int = (this.logicalSize shl 3)

    override fun serialize(out: DataOutput2, value: LongVectorValue) {
        for (i in 0 until this.logicalSize) {
            out.writeLong(value[i].value)
        }
    }
    override fun deserialize(input: DataInput2, available: Int): LongVectorValue {
        val vector = LongArray(this.logicalSize)
        for (i in 0 until this.logicalSize) {
            vector[i] = input.readLong()
        }
        return LongVectorValue(vector)
    }

    override fun serialize(page: Page, offset: Int, value: LongVectorValue) {
        page.putLongs(offset, value.data)
    }

    override fun deserialize(page: Page, offset: Int): LongVectorValue = LongVectorValue(page.getLongs(offset, LongArray(this.logicalSize)))
}