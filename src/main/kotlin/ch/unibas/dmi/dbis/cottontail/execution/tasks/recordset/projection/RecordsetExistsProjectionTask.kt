package ch.unibas.dmi.dbis.cottontail.execution.tasks.recordset.projection

import ch.unibas.dmi.dbis.cottontail.execution.tasks.basics.ExecutionTask
import ch.unibas.dmi.dbis.cottontail.model.basics.ColumnDef
import ch.unibas.dmi.dbis.cottontail.model.recordset.Recordset
import ch.unibas.dmi.dbis.cottontail.model.values.BooleanValue
import com.github.dexecutor.core.task.Task
import com.github.dexecutor.core.task.TaskExecutionException

/**
 * A [Task] used during query execution. It takes a single [Recordset] as input, counts the number of of rows and returns it as [Recordset].
 *
 * @author Ralph Gasser
 * @version 1.0
 */
internal class RecordsetExistsProjectionTask (val alias: String? = null): ExecutionTask("RecordsetExistsProjectionTask") {

    /** The cost of this [RecordsetCountProjectionTask] is constant */
    override val cost = 0.1f


    /**
     * Executes this [RecordsetExistsProjectionTask]
     */
    override fun execute(): Recordset {
        assertUnaryInput()

        /* Get records from parent task. */
        val parent = this.first() ?: throw TaskExecutionException("Projection could not be executed because parent task has failed.")

        /* Create new Recordset with new columns. */
        val recordset = Recordset(arrayOf(ColumnDef.withAttributes(alias
                ?: "exists(*)", "BOOLEAN")))
        recordset.addRow(arrayOf(BooleanValue(parent.rowCount > 0)))
        return recordset
    }
}