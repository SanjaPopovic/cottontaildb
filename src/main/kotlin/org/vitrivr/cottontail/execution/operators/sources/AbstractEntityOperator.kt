package org.vitrivr.cottontail.execution.operators.sources

import org.vitrivr.cottontail.database.entity.Entity
import org.vitrivr.cottontail.execution.ExecutionEngine
import org.vitrivr.cottontail.execution.operators.basics.SourceOperator
import org.vitrivr.cottontail.model.basics.ColumnDef

/**
 * An abstract [SourceOperator] that access an [Entity].
 *
 * @author Ralph Gasser
 * @version 1.0
 */
abstract class AbstractEntityOperator(context: ExecutionEngine.ExecutionContext, protected val entity: Entity, override val columns: Array<ColumnDef<*>>) : SourceOperator(context) {
    /** Transaction used by this [AbstractEntityOperator]. */
    protected var transaction: Entity.Tx? = null

    override fun prepareOpen() {
        this.transaction = this.entity.Tx(readonly = true)
    }

    override fun prepareClose() {
        this.transaction!!.close()
        this.transaction = null
    }
}