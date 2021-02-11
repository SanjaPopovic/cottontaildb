package org.vitrivr.cottontail.database.events

import org.vitrivr.cottontail.database.column.ColumnDef
import org.vitrivr.cottontail.database.entity.DefaultEntity
import org.vitrivr.cottontail.model.basics.Name
import org.vitrivr.cottontail.model.basics.TupleId
import org.vitrivr.cottontail.model.values.types.Value

/**
 * An internal [DataChangeEvent] to signal changes made to an [DefaultEntity].
 *
 * @version 1.0.1
 * @author Ralph Gasser
 */
sealed class DataChangeEvent(
    val name: Name.EntityName,
    val tupleId: TupleId
) {

    /**
     * A [DataChangeEvent] that signals a INSERT into an [DefaultEntity]
     */
    class InsertDataChangeEvent(
        name: Name.EntityName,
        tupleId: TupleId,
        val inserts: Map<ColumnDef<*>, Value?>
    ) : DataChangeEvent(name, tupleId)

    /**
     * A [DataChangeEvent] that signals n UPDATE in an [DefaultEntity]
     */
    class UpdateDataChangeEvent(
        name: Name.EntityName,
        tupleId: TupleId,
        val updates: Map<ColumnDef<*>, Pair<Value?, Value?>>,
    ) : DataChangeEvent(name, tupleId)

    /**
     * A [DataChangeEvent] that signals a DELETE from an [DefaultEntity]
     */
    class DeleteDataChangeEvent(
        name: Name.EntityName,
        tupleId: TupleId,
        val deleted: Map<ColumnDef<*>, Value?>
    ) : DataChangeEvent(name, tupleId)
}