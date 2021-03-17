package org.vitrivr.cottontail.storage.engine.hare.access.column.variable

import org.vitrivr.cottontail.model.basics.TransactionId
import org.vitrivr.cottontail.model.basics.TupleId
import org.vitrivr.cottontail.model.values.types.Value
import org.vitrivr.cottontail.storage.engine.hare.access.column.directory.Directory
import org.vitrivr.cottontail.storage.engine.hare.access.interfaces.HareCursor
import org.vitrivr.cottontail.storage.engine.hare.buffer.BufferPool
import org.vitrivr.cottontail.storage.engine.hare.views.isDeleted

/**
 * A [HareCursor] implementation for [VariableHareColumnFile]s. This implementation is not thread safe!
 *
 * @author Ralph Gasser
 * @version 1.0.3
 */
class VariableHareColumnCursor<T : Value>(val file: VariableHareColumnFile<T>, private val directory: Directory, range: LongRange? = null) : HareCursor<T> {
    /** The [TransactionId] this [VariableHareColumnCursor] is associated with. */
    override val tid: TransactionId
        get() = this.bufferPool.tid

    /** The [TupleId] this [VariableHareColumnCursor] is currently pointing to. */
    override var tupleId: TupleId = HareCursor.CURSOR_BOF

    /** Minimum [TupleId] that can be accessed through this [VariableHareColumnCursor]. */
    override val start: TupleId

    /** Maximum [TupleId] that can be accessed through this [VariableHareColumnCursor]. */
    override val end: TupleId

    /** The [BufferPool] instance used by this [VariableHareColumnCursor] is always shared with the [Directory]. */
    private val bufferPool: BufferPool = this.directory.bufferPool

    /** [start] and [end] are initialized once! Hence [VariableHareColumnCursor] won't reflect changes to the file.*/
    init {
        require(this.file.isOpen) { "VariableHareColumnFile has been closed (file = ${this.file.path})." }
        require(this.file.disk == this.bufferPool.disk) { "VariableHareColumnFile and provided BufferPool do not share the same HareDiskManager." }

        val headerPage = this.bufferPool.get(VariableHareColumnFile.ROOT_PAGE_ID)
        val headerView = HeaderPageView(headerPage)
        if (range != null) {
            require(range.first >= 0L) { "Start tupleId must be greater or equal than zero." }
            require(range.last <= headerView.maxTupleId) { "End tupleId must be smaller or equal to to maximum tupleId for HARE file." }
            this.start = range.first
            this.end = range.last
        } else {
            this.start = 0L
            this.end = headerView.maxTupleId
        }
    }

    override fun hasNext(): Boolean {
        if (this.tupleId == HareCursor.CURSOR_BOF) {
            this.tupleId = this.start
        } else {
            this.tupleId += 1
        }
        while (true) {
            if (this.tupleId > this.end) return false
            if (isValid(this.tupleId)) return true
            this.tupleId += 1
        }
    }

    /**
     * Returns next [TupleId].
     */
    override fun next(): TupleId = this.tupleId

    /**
     * Returns a boolean indicating whether the entry for the given [TupleId] has been deleted.
     *
     * @return true if the entry at the current position of the [VariableHareColumnCursor] has been deleted and false otherwise.
     */
    private fun isValid(tupleId: TupleId): Boolean = !this.directory.flags(tupleId).isDeleted()
}