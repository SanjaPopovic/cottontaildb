package ch.unibas.dmi.dbis.cottontail.execution.tasks.entity.projection

import ch.unibas.dmi.dbis.cottontail.database.entity.Entity
import ch.unibas.dmi.dbis.cottontail.database.general.query
import ch.unibas.dmi.dbis.cottontail.execution.cost.Costs
import ch.unibas.dmi.dbis.cottontail.execution.tasks.basics.ExecutionTask
import ch.unibas.dmi.dbis.cottontail.model.basics.ColumnDef
import ch.unibas.dmi.dbis.cottontail.model.recordset.Recordset
import ch.unibas.dmi.dbis.cottontail.model.values.DoubleValue
import com.github.dexecutor.core.task.Task

/**
 * A [Task] used during query execution. It takes a single [Entity] and determines the mean value of a specific [ColumnDef]. It thereby creates a 1x1 [Recordset].
 *
 * @author Ralph Gasser
 * @version 1.0
 */
internal class EntitySumProjectionTask(val entity: Entity, val column: ColumnDef<*>, val alias: String? = null): ExecutionTask("EntitySumProjectionTask[${entity.name}]") {

    /** The cost of this [EntityExistsProjectionTask] is constant */
    override val cost = this.entity.statistics.rows * Costs.DISK_ACCESS_READ

    /**
     * Executes this [EntityExistsProjectionTask]
     */
    override fun execute(): Recordset {
        assertNullaryInput()

        val column = ColumnDef.withAttributes(this.alias ?: "sum(${column.name})", "DOUBLE")

        return this.entity.Tx(true, columns = arrayOf(column)).query {
            var sum = 0.0
            val recordset = Recordset(arrayOf(column))
            it.forEach {
                when (val value = it[column]?.value) {
                    is Byte -> sum += value
                    is Short -> sum += value
                    is Int -> sum += value
                    is Long -> sum += value
                    is Float -> sum += value
                    is Double -> sum += value
                    else -> {}
                }
            }
            recordset.addRowUnsafe(arrayOf(DoubleValue(sum)))
            recordset
        }!!
    }
}