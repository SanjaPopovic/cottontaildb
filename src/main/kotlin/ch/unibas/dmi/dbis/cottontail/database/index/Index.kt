package ch.unibas.dmi.dbis.cottontail.database.index

import ch.unibas.dmi.dbis.cottontail.database.column.Column
import ch.unibas.dmi.dbis.cottontail.database.entity.Entity
import ch.unibas.dmi.dbis.cottontail.database.general.DBO
import ch.unibas.dmi.dbis.cottontail.database.general.Transaction
import ch.unibas.dmi.dbis.cottontail.database.general.TransactionStatus
import ch.unibas.dmi.dbis.cottontail.database.queries.Predicate
import ch.unibas.dmi.dbis.cottontail.database.schema.Schema
import ch.unibas.dmi.dbis.cottontail.model.basics.ColumnDef
import ch.unibas.dmi.dbis.cottontail.model.basics.Recordset
import ch.unibas.dmi.dbis.cottontail.model.exceptions.TransactionException
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write


/**
 * Represents an index in the Cottontail DB data model. An [Index] belongs to an [Entity] and can be used to index one to many
 * [Column]s. Usually, [Index]es allow for faster data access. They process [Predicate]s and return [Recordset]s.
 *
 * Calling the default constructor for an [Index] should open that [Index]. In order to initialize or update it, a call to
 * [Index.update] us necessary. For concurrency reason, that call can only be issued through an [IndexTransaction].
 *
 * @see Schema
 * @see Column
 * @see Entity.Tx
 *
 * @author Ralph Gasser
 * @version 1.0
 */
internal abstract class Index : DBO {
    /** A internal lock that is used to synchronize read/write access to this [Index]. */
    protected val txLock = ReentrantReadWriteLock()

    /** A internal lock that is used to synchronize closing of an [Index]. */
    protected val globalLock = ReentrantReadWriteLock()

    /** Reference to the [Entity], this index belongs to. */
    abstract override val parent: Entity

    /** The [ColumnDef] that are covered by this index. */
    abstract val columns: Array<ColumnDef<*>>

    /** Flag indicating whether or not this [Index] supports parallel execution. */
    val supportsParallelExecution
        get() = false

    /** The type of [Index]. */
    abstract val type: IndexType

    /**
     * Checks if this [Index] can process the provided [Predicate] and returns true if so and false otherwise.
     *
     * @param predicate [Predicate] to check.
     * @return True if [Predicate] can be processed, false otherwise.
     */
    abstract fun canProcess(predicate: Predicate): Boolean

    /** Handles finalization, in case the Garbage Collector reaps a cached [Index]. */
    @Synchronized
    protected fun finalize() {
        this.close()
    }

    /**
     * (Re-)builds the [Index] and can also be used to initialize it. Invoking this method should update the
     * [Index] immediately, without the need to commit (i.e. commit actions must take place inside).
     *
     * This is an internal method! External invocation is only possible through a [Index.Tx] object.
     *
     * @param columns List of columns to build the index. If null, the existing columns will be used.
     */
    protected abstract fun update(columns: Array<ColumnDef<*>>? = null)

    /**
     * Performs a lookup through this [Index] and returns [Recordset]. This is an internal method! External
     * invocation is only possible through a [Index.Tx] object.
     *
     * @param predicate The [Predicate] to perform the lookup.
     * @return The resulting [Recordset].
     *
     * @throws DatabaseException.PredicateNotSupportedBxIndexException If predicate is not supported by [Index].
     */
    protected abstract fun lookup(predicate: Predicate): Recordset

    /**
     * Performs a lookup through this [Index] and returns a [Recordset]. This is an internal method! External
     * invocation is only possible through a [Index.Tx] object.
     *
     * @param predicate The [Predicate] to perform the lookup.
     * @param parallelism The amount of parallelism to allow for.
     * @return The resulting [Recordset].
     *
     * @throws DatabaseException.PredicateNotSupportedBxIndexException If predicate is not supported by [Index].
     */
    protected fun lookupParallel(predicate: Predicate, parallelism: Short = 2): Recordset {
        throw UnsupportedOperationException()
    }

    /**
     * A [Transaction] that affects this [Index].
     */
    inner class Tx constructor(override val readonly: Boolean, override val tid: UUID = UUID.randomUUID()): IndexTransaction {

        /** Flag indicating whether or not this [Entity.Tx] was closed */
        @Volatile override var status: TransactionStatus = TransactionStatus.CLEAN
            private set

        /** Tries to acquire a global read-lock on the [MapDBColumn]. */
        init {
            if (this@Index.closed) {
                throw TransactionException.TransactionDBOClosedException(tid)
            }
            this@Index.globalLock.readLock().lock()
        }

        /**
         * (Re-)builds the underlying [Index] and can be used to initialize the [Index].
         *
         * @param columns List of columns to build the index. If null, the existing columns will be used.
         */
        override fun update(columns: Array<ColumnDef<*>>?) {
            this.acquireWriteLock()
            this@Index.update(columns)
        }

        /**
         * Performs a lookup through the underlying [Index] and returns a [Recordset].
         *
         * @param predicate The [Predicate] to perform the lookup.
         * @return The resulting [Recordset].
         *
         * @throws DatabaseException.PredicateNotSupportedBxIndexException If predicate is not supported by [Index].
         */
        override fun lookup(predicate: Predicate): Recordset = this@Index.txLock.read {
            return this@Index.lookup(predicate)
        }

        /**
         * Performs a lookup through the underlying [Index] and returns a [Recordset].
         *
         * @param predicate The [Predicate] to perform the lookup.
         * @param parallelism The amount of parallelism to allow for.
         * @return The resulting [Recordset].
         *
         * @throws DatabaseException.PredicateNotSupportedBxIndexException If predicate is not supported by [Index].
         */
        override fun lookupParallel(predicate: Predicate, parallelism: Short): Recordset = this@Index.txLock.read {
            return this@Index.lookupParallel(predicate, parallelism)
        }

        /**
         * Has no effect since updating an [Index] takes immediate effect.
         */
        override fun commit() {}

        /**
         * Has no effect since updating an [Index] takes immediate effect.
         */
        override fun rollback() {}

        /**
         * Closes this [Index.Tx] and releases the global lock. Closed [Entity.Tx] cannot be used anymore!
         */
        @Synchronized
        override fun close() {
            if (this.status != TransactionStatus.CLOSED) {
                if (this.status == TransactionStatus.DIRTY) {
                    this@Index.txLock.writeLock().unlock()
                }
                this.status = TransactionStatus.CLOSED
                this@Index.globalLock.readLock().unlock()
            }
        }

        /**
         * Tries to acquire a write-lock. If method fails, an exception will be thrown
         */
        @Synchronized
        private fun acquireWriteLock() {
            if (this.readonly) throw TransactionException.TransactionReadOnlyException(tid)
            if (this.status == TransactionStatus.CLOSED) throw TransactionException.TransactionClosedException(tid)
            if (this.status == TransactionStatus.ERROR) throw TransactionException.TransactionInErrorException(tid)
            if (this.status != TransactionStatus.DIRTY) {
                if (this@Index.txLock.writeLock().tryLock()) {
                    this.status = TransactionStatus.DIRTY
                } else {
                    throw TransactionException.TransactionWriteLockException(this.tid)
                }
            }
        }
    }
}