package org.vitrivr.cottontail.database.catalogue

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import org.mapdb.*
import org.vitrivr.cottontail.config.Config
import org.vitrivr.cottontail.database.entity.DefaultEntity
import org.vitrivr.cottontail.database.general.AbstractTx
import org.vitrivr.cottontail.database.general.DBO
import org.vitrivr.cottontail.database.general.TxStatus
import org.vitrivr.cottontail.database.locking.LockMode
import org.vitrivr.cottontail.database.schema.DefaultSchema
import org.vitrivr.cottontail.database.schema.Schema
import org.vitrivr.cottontail.database.schema.SchemaHeader
import org.vitrivr.cottontail.execution.TransactionContext
import org.vitrivr.cottontail.model.basics.Name
import org.vitrivr.cottontail.model.exceptions.DatabaseException
import org.vitrivr.cottontail.utilities.extensions.read
import org.vitrivr.cottontail.utilities.extensions.write
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.locks.StampedLock
import java.util.stream.Collectors

/**
 * The default [Catalogue] implementation based on Map DB.
 *
 * @author Ralph Gasser
 * @version 2.0.0
 */
class DefaultCatalogue(override val config: Config) : Catalogue {
    /**
     * Companion object to [DefaultCatalogue]
     */
    companion object {
        /** ID of the schema header! */
        internal const val CATALOGUE_HEADER_FIELD: String = "cdb_catalogue_header"

        /** Filename for the [DefaultEntity] catalogue.  */
        internal const val FILE_CATALOGUE = "catalogue.db"
    }

    /** Root to Cottontail DB root folder. */
    override val path: Path = config.root

    /** Constant name of the [DefaultCatalogue] object. */
    override val name: Name.RootName = Name.RootName

    /** Constant parent [DBO], which is null in case of the [DefaultCatalogue]. */
    override val parent: DBO? = null

    /** A lock used to mediate access to this [DefaultCatalogue]. */
    private val closeLock = StampedLock()

    /** The [StoreWAL] that contains the Cottontail DB catalogue. */
    private val store: DB = this.config.mapdb.db(path.resolve(FILE_CATALOGUE))

    /** Reference to the [CatalogueHeader] of the [DefaultCatalogue]. Accessing it will read right from the underlying store. */
    private val headerField =
        this.store.atomicVar(CATALOGUE_HEADER_FIELD, CatalogueHeader.Serializer).createOrOpen()

    /** A in-memory registry of all the [Schema]s contained in this [DefaultCatalogue]. When a [DefaultCatalogue] is opened, all the [Schema]s will be loaded. */
    private val registry: MutableMap<Name.SchemaName, Schema> = Collections.synchronizedMap(Object2ObjectOpenHashMap())

    /** Size of this [DefaultCatalogue] in terms of [Schema]s it contains. */
    override val size: Int
        get() = this.closeLock.read { this.headerField.get().schemas.size }

    /** Status indicating whether this [DefaultCatalogue] is open or closed. */
    @Volatile
    override var closed: Boolean = false
        private set

    init {
        /* Initialize empty catalogue */
        if (this.headerField.get() == null) {
            this.headerField.set(CatalogueHeader())
            this.store.commit()
        }

        /* Initialize. */
        for (schemaRef in this.headerField.get().schemas) {
            if (!Files.exists(schemaRef.path)) {
                throw DatabaseException.DataCorruptionException("Broken catalogue entry for schema '${schemaRef.name}'. Path ${schemaRef.path} does not exist!")
            }
            this.registry[Name.SchemaName(schemaRef.name)] = DefaultSchema(schemaRef.path, this)
        }
    }

    /**
     * Creates and returns a new [DefaultCatalogue.Tx] for the given [TransactionContext].
     *
     * @param context The [TransactionContext] to create the [DefaultCatalogue.Tx] for.
     * @return New [DefaultCatalogue.Tx]
     */
    override fun newTx(context: TransactionContext): Tx = Tx(context)

    /**
     * Closes the [DefaultCatalogue] and all objects contained within.
     */
    override fun close() = this.closeLock.write {
        this.registry.forEach { (_, v) -> v.close() }
        this.store.close()
        this.closed = true
    }

    /**
     * A [Tx] that affects this [DefaultCatalogue].
     *
     * @author Ralph Gasser
     * @version 1.0.0
     */
    inner class Tx(context: TransactionContext) : AbstractTx(context), CatalogueTx {

        /** Reference to the [DefaultCatalogue] this [CatalogueTx] belongs to. */
        override val dbo: DefaultCatalogue
            get() = this@DefaultCatalogue

        /** Obtains a global (non-exclusive) read-lock on [DefaultCatalogue]. Prevents enclosing [Schema] from being closed. */
        private val closeStamp = this@DefaultCatalogue.closeLock.readLock()

        /** Actions that should be executed after committing this [Tx]. */
        private val postCommitAction = mutableListOf<Runnable>()

        /** Actions that should be executed after rolling back this [Tx]. */
        private val postRollbackAction = mutableListOf<Runnable>()

        /**
         * Returns a list of [Name.SchemaName] held by this [DefaultCatalogue].
         *
         * @return [List] of all [Name.SchemaName].
         */
        override fun listSchemas(): List<Name.SchemaName> = this.withReadLock {
            this@DefaultCatalogue.headerField.get().schemas.map { this.dbo.name.schema(it.name) }
        }

        /**
         * Returns the [Schema] for the given [Name.SchemaName].
         *
         * @param name [Name.SchemaName] to obtain the [Schema] for.
         */
        override fun schemaForName(name: Name.SchemaName): Schema = this.withReadLock {
            this@DefaultCatalogue.registry[name] ?: throw DatabaseException.SchemaDoesNotExistException(name)
        }

        /**
         * Creates a new, empty [Schema] with the given [Name.SchemaName] and [Path]
         *
         * @param name The [Name.SchemaName] of the new [Schema].
         */
        override fun createSchema(name: Name.SchemaName): Schema = this.withWriteLock {
            /* Check if schema with that name exists. */
            if (this.listSchemas().contains(name)) throw DatabaseException.SchemaAlreadyExistsException(name)

            try {
                /* Create empty folder for entity. */
                val data = this@DefaultCatalogue.path.resolve("schema_${name.simple}")
                if (!Files.exists(data)) {
                    Files.createDirectories(data)
                } else {
                    throw DatabaseException("Failed to create schema '$name'. Data directory '$data' seems to be occupied.")
                }

                /* Generate the store for the new schema. */
                val store = this@DefaultCatalogue.config.mapdb.db(data.resolve(DefaultSchema.FILE_CATALOGUE))
                val schemaHeader =
                    store.atomicVar(DefaultSchema.SCHEMA_HEADER_FIELD, SchemaHeader.Serializer).create()
                schemaHeader.set(SchemaHeader(name.simple))
                store.commit()
                store.close()

                /* Update this catalogue's header. */
                val oldHeader = this@DefaultCatalogue.headerField.get()
                val newHeader = oldHeader.copy(modified = System.currentTimeMillis())
                newHeader.addSchemaRef(CatalogueHeader.SchemaRef(name.simple, data))
                this@DefaultCatalogue.headerField.compareAndSet(oldHeader, newHeader)

                /* ON COMMIT: Make schema available. */
                val schema = DefaultSchema(data, this@DefaultCatalogue)
                this.postCommitAction.add {
                    this@DefaultCatalogue.registry[name] = schema
                }

                /* ON ROLLBACK: Remove schema folder. */
                this.postRollbackAction.add {
                    schema.close()
                    val pathsToDelete = Files.walk(data).sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList())
                    pathsToDelete.forEach { Files.delete(it) }
                }

                return schema
            } catch (e: DBException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Failed to create schema '$name' due to a storage exception: ${e.message}")
            } catch (e: IOException) {
                throw DatabaseException("Failed to create schema '$name' due to an IO exception: ${e.message}")
            }
        }

        /**
         * Drops an existing [Schema] with the given [Name.SchemaName].
         *
         * @param name The [Name.SchemaName] of the [Schema] to be dropped.
         */
        override fun dropSchema(name: Name.SchemaName) = this.withWriteLock {
            /* Obtain schema and acquire exclusive lock on it. */
            val schema = this.schemaForName(name)
            if (this.context.lockOn(schema) == LockMode.NO_LOCK) {
                this.context.requestLock(schema, LockMode.EXCLUSIVE)
            }

            /* Close schema and remove from registry. This is a reversible operation! */
            schema.close()
            this@DefaultCatalogue.registry.remove(name)

            /* Remove catalogue entry + update header. */
            try {
                /* Rename folder and mark it for deletion. */
                val shadowSchema = schema.path.resolveSibling(schema.path.fileName.toString() + "~dropped")
                Files.move(schema.path, shadowSchema, StandardCopyOption.ATOMIC_MOVE)

                /* ON ROLLBACK: Move back schema data and re-open it. */
                this.postRollbackAction.add {
                    Files.move(shadowSchema, schema.path)
                    this@DefaultCatalogue.registry[name] = DefaultSchema(schema.path, this@DefaultCatalogue)
                    this.context.releaseLock(schema)
                }

                /* ON COMMIT: Remove schema from registry and delete files. */
                this.postCommitAction.add {
                    val pathsToDelete = Files.walk(shadowSchema).sorted(Comparator.reverseOrder())
                        .collect(Collectors.toList())
                    pathsToDelete.forEach { Files.deleteIfExists(it) }
                    this.context.releaseLock(schema)
                }

                /* Update this catalogue's header. */
                val oldHeader = this@DefaultCatalogue.headerField.get()
                val newHeader = oldHeader.copy(modified = System.currentTimeMillis())
                newHeader.removeSchemaRef(name.simple)
                this@DefaultCatalogue.headerField.compareAndSet(oldHeader, newHeader)
                Unit
            } catch (e: DBException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Failed to drop schema '$name' due to a storage exception: ${e.message}")
            } catch (e: IOException) {
                this.status = TxStatus.ERROR
                throw DatabaseException("Failed to drop schema '$name' due to a IO exception: ${e.message}")
            }
        }

        /**
         * Performs a commit of all changes made through this [DefaultCatalogue.Tx].
         */
        override fun performCommit() {
            /* Perform commit. */
            this@DefaultCatalogue.store.commit()

            /* Execute post-commit actions. */
            this.postCommitAction.forEach { it.run() }
            this.postRollbackAction.clear()
            this.postCommitAction.clear()
        }

        /**
         * Performs a rollback of all changes made through this [DefaultCatalogue.Tx].
         */
        override fun performRollback() {
            /* Perform rollback. */
            this@DefaultCatalogue.store.rollback()

            /* Execute post-rollback actions. */
            this.postRollbackAction.forEach { it.run() }
            this.postRollbackAction.clear()
            this.postCommitAction.clear()
        }

        /**
         * Releases the [closeLock] on the [DefaultCatalogue].
         */
        override fun cleanup() {
            this@DefaultCatalogue.closeLock.unlockRead(this.closeStamp)
        }
    }
}