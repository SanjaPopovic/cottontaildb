package org.vitrivr.cottontail.database.schema

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.mapdb.*

import org.vitrivr.cottontail.database.catalogue.DefaultCatalogue
import org.vitrivr.cottontail.database.column.ColumnDef
import org.vitrivr.cottontail.database.column.ColumnDriver
import org.vitrivr.cottontail.database.column.mapdb.MapDBColumn
import org.vitrivr.cottontail.database.entity.DefaultEntity
import org.vitrivr.cottontail.database.entity.EntityHeader
import org.vitrivr.cottontail.database.general.AbstractTx
import org.vitrivr.cottontail.database.general.DBO
import org.vitrivr.cottontail.database.general.TxStatus
import org.vitrivr.cottontail.database.locking.LockMode
import org.vitrivr.cottontail.execution.TransactionContext
import org.vitrivr.cottontail.model.basics.Name
import org.vitrivr.cottontail.model.exceptions.DatabaseException
import org.vitrivr.cottontail.utilities.extensions.read

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.locks.StampedLock
import java.util.stream.Collectors

/**
 * Default [Schema] implementation in Cottontail DB based on Map DB.
 *
 * @see Schema
 *
 * @author Ralph Gasser
 * @version 2.0.0
 */
class DefaultSchema(override val path: Path, override val parent: DefaultCatalogue) : Schema {
    /** Companion object with different constants. */
    companion object {
        /** Filename for the [DefaultEntity] catalogue.  */
        const val SCHEMA_HEADER_FIELD = "cdb_entity_header"

        /** Filename for the [DefaultSchema] catalogue.  */
        const val FILE_CATALOGUE = "index.db"
    }

    /** Internal reference to the [Store] underpinning this [MapDBColumn]. */
    private val store: DB = try {
        this.parent.config.mapdb.db(this.path.resolve(FILE_CATALOGUE))
    } catch (e: DBException) {
        throw DatabaseException("Failed to open schema at '$path': ${e.message}'")
    }

    /** The [SchemaHeader] of this [DefaultSchema]. */
    private val headerField =
        this.store.atomicVar(SCHEMA_HEADER_FIELD, SchemaHeader.Serializer).createOrOpen()

    /** A lock used to mediate access the closed state of this [DefaultSchema]. */
    private val closeLock = StampedLock()

    /** A map of loaded [DefaultEntity] references. */
    private val registry: MutableMap<Name.EntityName, DefaultEntity> =
        Collections.synchronizedMap(Object2ObjectOpenHashMap())

    /** The [Name.SchemaName] of this [DefaultSchema]. */
    override val name: Name.SchemaName = Name.SchemaName(this.headerField.get().name)

    /** Flag indicating whether or not this [DefaultSchema] has been closed. */
    @Volatile
    override var closed: Boolean = false
        private set

    init {
        /* Initialize all entities. */
        this.headerField.get().entities.map {
            this.registry[this.name.entity(it.name)] = DefaultEntity(it.path, this)
        }
    }

    /**
     * Creates and returns a new [DefaultSchema.Tx] for the given [TransactionContext].
     *
     * @param context The [TransactionContext] to create the [DefaultSchema.Tx] for.
     * @return New [DefaultSchema.Tx]
     */
    override fun newTx(context: TransactionContext) = this.Tx(context)

    /**
     * Closes this [DefaultSchema] and all the [DefaultEntity] objects that are contained within.
     *
     * Since locks to [DBO] or [Transaction][org.vitrivr.cottontail.database.general.Tx]
     * objects may be held by other threads, it can take a
     * while for this method to complete.
     */
    override fun close() = this.closeLock.read {
        if (!this.closed) {
            this.registry.entries.removeIf {
                it.value.close()
                true
            }
            this.store.close()
            this.closed = true
        }
    }

    /**
     * A [Tx] that affects this [DefaultSchema].
     *
     * @author Ralph Gasser
     * @version 1.0.0
     */
    inner class Tx(context: TransactionContext) : AbstractTx(context), SchemaTx {

        /** Obtains a global (non-exclusive) read-lock on [DefaultSchema]. Prevents enclosing [DefaultSchema] from being closed. */
        private val closeStamp = this@DefaultSchema.closeLock.readLock()

        /** Reference to the surrounding [DefaultSchema]. */
        override val dbo: DBO
            get() = this@DefaultSchema

        /** Actions that should be executed after committing this [Tx]. */
        private val postCommitAction = mutableListOf<Runnable>()

        /** Actions that should be executed after rolling back this [Tx]. */
        private val postRollbackAction = mutableListOf<Runnable>()

        /**
         * Returns a list of [DefaultEntity] held by this [DefaultSchema].
         *
         * @return [List] of all [Name.EntityName].
         */
        override fun listEntities(): List<Name.EntityName> = this.withReadLock {
            return this@DefaultSchema.headerField.get().entities.map { this@DefaultSchema.name.entity(it.name) }
        }

        /**
         * Returns an instance of [DefaultEntity] if such an instance exists. If the [DefaultEntity] has been loaded before,
         * that [DefaultEntity] is re-used. Otherwise, the [DefaultEntity] will be loaded from disk.
         *
         * @param name Name of the [DefaultEntity] to access.
         * @return [DefaultEntity] or null.
         */
        override fun entityForName(name: Name.EntityName): DefaultEntity = this.withReadLock {
            return this@DefaultSchema.registry[name]
                ?: throw DatabaseException.EntityDoesNotExistException(name)
        }

        /**
         * Creates a new [DefaultEntity] in this [DefaultSchema].
         *
         * @param name The name of the [DefaultEntity] that should be created.
         * @param columns The [ColumnDef] of the columns the new [DefaultEntity] should have
         */
        override fun createEntity(name: Name.EntityName, vararg columns: ColumnDef<*>): DefaultEntity = this.withWriteLock {
            if (columns.map { it.name }.distinct().size != columns.size) throw DatabaseException.DuplicateColumnException(name, columns.map { it.name })
            if (this.listEntities().contains(name)) throw DatabaseException.EntityAlreadyExistsException(name)

            try {
                /* Create empty folder for entity. */
                val data = this@DefaultSchema.path.resolve("entity_${name.simple}")
                if (!Files.exists(data)) {
                    Files.createDirectories(data)
                } else {
                    throw DatabaseException("Failed to create entity '$name'. Data directory '$data' seems to be occupied.")
                }

                /* ON ROLLBACK: Delete unused entity folder. */
                this.postRollbackAction.add {
                    val pathsToDelete = Files.walk(data).sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList())
                    pathsToDelete.forEach { Files.delete(it) }
                }

                /* Generate the entity and initialize the new entities header. */
                val store = this@DefaultSchema.parent.config.mapdb.db(data.resolve(DefaultEntity.CATALOGUE_FILE))
                val columnsRefs = columns.map {
                    val path = data.resolve("col_${it.name.simple}.db")
                    MapDBColumn.initialize(path, it, this@DefaultSchema.parent.config.mapdb)
                    EntityHeader.ColumnRef(it.name.simple, ColumnDriver.MAPDB, path)
                }
                val entityHeader = EntityHeader(name = name.simple, columns = columnsRefs)
                store.atomicVar(DefaultEntity.ENTITY_HEADER_FIELD, EntityHeader.Serializer).create()
                    .set(entityHeader)
                store.commit()
                store.close()

                /* Update this schema's header. */
                val oldHeader = this@DefaultSchema.headerField.get()
                val newHeader = oldHeader.copy(modified = System.currentTimeMillis())
                newHeader.addEntityRef(SchemaHeader.EntityRef(name.simple, data))
                this@DefaultSchema.headerField.compareAndSet(oldHeader, newHeader)

                /* ON COMMIT: Make entity available. */
                val entity = DefaultEntity(data, this@DefaultSchema)
                this.postCommitAction.add {
                    this@DefaultSchema.registry[name] = entity
                }

                /* ON ROLLBACK: Close entity. */
                this.postRollbackAction.add(0) {
                    entity.close()
                }

                return entity
            } catch (e: DBException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Failed to create entity '$name' due to error in the underlying data store: {${e.message}")
            } catch (e: IOException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Failed to create entity '$name' due to an IO exception: {${e.message}")
            }
        }

        /**
         * Drops an [DefaultEntity] from this [DefaultSchema].
         *
         * @param name The name of the [DefaultEntity] that should be dropped.
         */
        override fun dropEntity(name: Name.EntityName) = this.withWriteLock {
            /* Get entity and try to obtain lock. */
            val entity =
                this@DefaultSchema.registry[name] ?: throw DatabaseException.EntityDoesNotExistException(
                    name
                )
            if (this.context.lockOn(entity) == LockMode.NO_LOCK) {
                this.context.requestLock(entity, LockMode.EXCLUSIVE)
            }

            /* Close entity and remove it from registry. */
            entity.close()
            this@DefaultSchema.registry.remove(name)

            try {
                /* Rename folder and of entity it for deletion. */
                val shadowEntity = entity.path.resolveSibling(entity.path.fileName.toString() + "~dropped")
                Files.move(entity.path, shadowEntity, StandardCopyOption.ATOMIC_MOVE)

                /* ON COMMIT: Remove schema from registry and delete files. */
                this.postCommitAction.add {
                    val pathsToDelete = Files.walk(shadowEntity).sorted(Comparator.reverseOrder()).collect(Collectors.toList())
                    pathsToDelete.forEach { Files.deleteIfExists(it) }
                    this.context.releaseLock(entity)
                }

                /* ON ROLLBACK: Re-map entity and move back files. */
                this.postRollbackAction.add {
                    Files.move(shadowEntity, entity.path, StandardCopyOption.ATOMIC_MOVE)
                    this@DefaultSchema.registry[name] = DefaultEntity(entity.path, this@DefaultSchema)
                    this.context.releaseLock(entity)
                }

                /* Update this schema's header. */
                val oldHeader = this@DefaultSchema.headerField.get()
                val newHeader = oldHeader.copy(modified = System.currentTimeMillis())
                newHeader.removeEntityRef(name.simple)
                this@DefaultSchema.headerField.compareAndSet(oldHeader, newHeader)
                Unit
            } catch (e: DBException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Entity '$name' could not be dropped, because of an error in the underlying data store: ${e.message}!")
            }
        }

        /**
         * Performs a COMMIT of all changes made through this [DefaultSchema.Tx].
         */
        override fun performCommit() {
            /* Perform commit. */
            this@DefaultSchema.store.commit()

            /* Execute post-commit actions. */
            this.postCommitAction.forEach { it.run() }
            this.postRollbackAction.clear()
            this.postCommitAction.clear()
        }

        /**
         * Performs a ROLLBACK of all changes made through this [DefaultSchema.Tx].
         */
        override fun performRollback() {
            /* Perform rollback. */
            this@DefaultSchema.store.rollback()

            /* Execute post-rollback actions. */
            this.postRollbackAction.forEach { it.run() }
            this.postRollbackAction.clear()
            this.postCommitAction.clear()
        }

        /**
         * Releases the [closeLock] on the [DefaultSchema].
         */
        override fun cleanup() {
            this@DefaultSchema.closeLock.unlockRead(this.closeStamp)
        }
    }
}



