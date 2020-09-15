package org.vitrivr.cottontail.server.grpc.helper

import org.vitrivr.cottontail.database.catalogue.Catalogue
import org.vitrivr.cottontail.database.column.*
import org.vitrivr.cottontail.database.entity.Entity
import org.vitrivr.cottontail.database.index.IndexType
import org.vitrivr.cottontail.database.queries.components.*
import org.vitrivr.cottontail.database.queries.planning.nodes.interfaces.NodeExpression
import org.vitrivr.cottontail.database.queries.planning.nodes.logical.*
import org.vitrivr.cottontail.database.queries.planning.nodes.physical.recordset.ProjectionPhysicalNodeExpression

import org.vitrivr.cottontail.grpc.CottontailGrpc
import org.vitrivr.cottontail.math.knn.metrics.Distances
import org.vitrivr.cottontail.model.basics.ColumnDef
import org.vitrivr.cottontail.model.basics.Name
import org.vitrivr.cottontail.model.exceptions.DatabaseException
import org.vitrivr.cottontail.model.exceptions.QueryException
import org.vitrivr.cottontail.model.values.*

/**
 * This helper class parses and binds queries issued through the gRPC endpoint. The process encompasses three steps:
 *
 * 1) The [CottontailGrpc.Query] is decomposed into its components.
 * 2) The gRPC query components are bound to Cottontail DB [DBO] objects and internal query objects are constructed. This step includes some basic validation.
 * 3) A [ExecutionPlan] is constructed from the internal query objects.
 *
 * @author Ralph Gasser
 * @version 1.2
 */
class GrpcQueryBinder(val catalogue: Catalogue) {
    /**
     * Binds the given [CottontailGrpc.Query] to the database objects and thereby creates an [ExecutionPlan].
     *
     * @param query The [CottontailGrpc.Query] that should be bound.
     * @return [ExecutionPlan]
     *
     * @throws QueryException.QuerySyntaxException If [CottontailGrpc.Query] is structurally incorrect.
     */
    fun parseAndBind(query: CottontailGrpc.Query): NodeExpression.LogicalNodeExpression {
        if (!query.hasFrom()) throw QueryException.QuerySyntaxException("Missing FROM-clause in query.")
        if (query.from.hasQuery()) {
            throw QueryException.QuerySyntaxException("Cottontail DB currently doesn't support sub-selects.")
        }
        return parseAndBindSimpleQuery(query)
    }

    /**
     * Parses and binds a simple [CottontailGrpc.Query] without any joins and subselects and thereby generates an [ExecutionPlan].
     *
     * @param query The simple [CottontailGrpc.Query] object.
     */
    private fun parseAndBindSimpleQuery(query: CottontailGrpc.Query): NodeExpression.LogicalNodeExpression {
        /* Create scan clause. */
        val scanClause = parseAndBindSimpleFrom(query.from)
        var root: NodeExpression.LogicalNodeExpression = scanClause

        /* Create WHERE-clause. */
        if (query.hasWhere()) {
            root = root.updateOutput(FilterLogicalNodeExpression(parseAndBindBooleanPredicate(scanClause.entity, query.where)))
        }

        /* Process kNN-clause (Important: mind precedence of WHERE-clause. */
        if (query.hasKnn()) {
            root = root.updateOutput(KnnLogicalNodeExpression(parseAndBindKnnPredicate(scanClause.entity, query.knn)))
        }

        /* Process projection clause. */
        root = if (query.hasProjection()) {
            root.updateOutput(parseAndBindProjection(scanClause.entity, query.projection))
        } else {
            root.updateOutput(parseAndBindProjection(scanClause.entity, CottontailGrpc.Projection.newBuilder().setOp(CottontailGrpc.Projection.Operation.SELECT).putAttributes("*", "").build()))
        }

        /* Process LIMIT and SKIP. */
        if (query.limit > 0L || query.skip > 0L) {
            root = root.updateOutput(LimitLogicalNodeExpression(query.limit, query.skip))
        }

        return root
    }

    /**
     * Parses and binds a [CottontailGrpc.From] clause.
     *
     * @param from The [CottontailGrpc.From] object.
     * @return The resulting [EntityEntityScanNodeExpression].
     */
    private fun parseAndBindSimpleFrom(from: CottontailGrpc.From): EntityScanLogicalNodeExpression = try {
        when (from.fromCase) {
            CottontailGrpc.From.FromCase.ENTITY -> {
                val entityName = from.entity.fqn()
                EntityScanLogicalNodeExpression.EntityFullScanLogicalNodeExpression(entity = this.catalogue.schemaForName(entityName.schema()).entityForName(entityName))
            }
            CottontailGrpc.From.FromCase.SAMPLE -> {
                val entityName = from.sample.entity.fqn()
                EntityScanLogicalNodeExpression.EntitySampleLogicalNodeExpression(entity = this.catalogue.schemaForName(entityName.schema()).entityForName(entityName), size = from.sample.size, seed = from.sample.seed)
            }
            else -> throw QueryException.QuerySyntaxException("Invalid FROM-clause in query.")
        }
    } catch (e: DatabaseException.SchemaDoesNotExistException) {
        throw QueryException.QueryBindException("Failed to bind '${from.entity.fqn()}'. Schema does not exist!")
    } catch (e: DatabaseException.EntityDoesNotExistException) {
        throw QueryException.QueryBindException("Failed to bind '${from.entity.fqn()}'. Entity does not exist!")
    } catch (e: DatabaseException) {
        throw QueryException.QueryBindException("Failed to bind ${from.entity.fqn()}. Database error!")
    }


    /**
     * Parses and binds a [CottontailGrpc.Where] clause.
     *
     * @param entity The [Entity] from which fetch columns.
     * @param where The [CottontailGrpc.Where] object.
     *
     * @return The resulting [AtomicBooleanPredicate].
     */
    private fun parseAndBindBooleanPredicate(entity: Entity, where: CottontailGrpc.Where): BooleanPredicate = when (where.predicateCase) {
        CottontailGrpc.Where.PredicateCase.ATOMIC -> parseAndBindAtomicBooleanPredicate(entity, where.atomic)
        CottontailGrpc.Where.PredicateCase.COMPOUND -> parseAndBindCompoundBooleanPredicate(entity, where.compound)
        CottontailGrpc.Where.PredicateCase.PREDICATE_NOT_SET -> throw QueryException.QuerySyntaxException("WHERE clause without a predicate is invalid!")
        null -> throw QueryException.QuerySyntaxException("WHERE clause without a predicate is invalid!")
    }

    /**
     * Parses and binds an atomic boolean predicate
     *
     * @param entity The [Entity] from which fetch columns.
     * @param projection The [CottontailGrpc.Knn] object.
     *
     * @return The resulting [AtomicBooleanPredicate].
     */
    private fun parseAndBindCompoundBooleanPredicate(entity: Entity, compound: CottontailGrpc.CompoundBooleanPredicate): CompoundBooleanPredicate {
        val left = when (compound.leftCase) {
            CottontailGrpc.CompoundBooleanPredicate.LeftCase.ALEFT -> parseAndBindAtomicBooleanPredicate(entity, compound.aleft)
            CottontailGrpc.CompoundBooleanPredicate.LeftCase.CLEFT -> parseAndBindCompoundBooleanPredicate(entity, compound.cleft)
            CottontailGrpc.CompoundBooleanPredicate.LeftCase.LEFT_NOT_SET -> throw QueryException.QuerySyntaxException("Unbalanced predicate! A compound boolean predicate must have a left and a right side.")
            null -> throw QueryException.QuerySyntaxException("Unbalanced predicate! A compound boolean predicate must have a left and a right side.")
        }

        val right = when (compound.rightCase) {
            CottontailGrpc.CompoundBooleanPredicate.RightCase.ARIGHT -> parseAndBindAtomicBooleanPredicate(entity, compound.aright)
            CottontailGrpc.CompoundBooleanPredicate.RightCase.CRIGHT -> parseAndBindCompoundBooleanPredicate(entity, compound.cright)
            CottontailGrpc.CompoundBooleanPredicate.RightCase.RIGHT_NOT_SET -> throw QueryException.QuerySyntaxException("Unbalanced predicate! A compound boolean predicate must have a left and a right side.")
            null -> throw QueryException.QuerySyntaxException("Unbalanced predicate! A compound boolean predicate must have a left and a right side.")
        }

        return try {
            CompoundBooleanPredicate(ConnectionOperator.valueOf(compound.op.name), left, right)
        } catch (e: IllegalArgumentException) {
            throw QueryException.QuerySyntaxException("'${compound.op.name}' is not a valid connection operator for a boolean predicate!")
        }
    }

    /**
     * Parses and binds an atomic boolean predicate
     *
     * @param entity The [Entity] from which fetch columns.
     * @param projection The [CottontailGrpc.Knn] object.
     *
     * @return The resulting [AtomicBooleanPredicate].
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseAndBindAtomicBooleanPredicate(entity: Entity, atomic: CottontailGrpc.AtomicLiteralBooleanPredicate): AtomicBooleanPredicate<*> {
        val columnName = entity.name.column(atomic.attribute)
        val column = entity.columnForName(columnName) ?: throw QueryException.QueryBindException("Failed to bind column '$columnName'. Column does not exist on entity'.")
        val operator = try {
            ComparisonOperator.valueOf(atomic.op.name)
        } catch (e: IllegalArgumentException) {
            throw QueryException.QuerySyntaxException("'${atomic.op.name}' is not a valid comparison operator for a boolean predicate!")
        }

        /* Perform some sanity checks. */
        when {
            operator == ComparisonOperator.LIKE && !entity.hasIndexForColumn(column, IndexType.LUCENE) -> throw QueryException.QueryBindException("Failed to bind query '${atomic.attribute} LIKE :1' on entity '${entity.name}'. The entity does not have a text-index on the specified column '${column.name}', which is required for LIKE comparisons.")
        }

        /* Return the resulting AtomicBooleanPredicate. */
        return when (column.type) {
            is DoubleColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<DoubleValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toDoubleValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is FloatColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<FloatValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toFloatValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is LongColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<LongValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toLongValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is IntColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<IntValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toIntValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is ShortColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<ShortValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toShortValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is ByteColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<ByteValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toByteValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is BooleanColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<BooleanValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toBooleanValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is StringColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<StringValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toStringValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is Complex32ColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<Complex32Value>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toComplex32Value()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is Complex64ColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<Complex64Value>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toComplex64Value()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is FloatVectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<FloatVectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toFloatVectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is DoubleVectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<DoubleVectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toDoubleVectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is LongVectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<LongVectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toLongVectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is IntVectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<IntVectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toIntVectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is BooleanVectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<BooleanVectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toBooleanVectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is Complex32VectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<Complex32VectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toComplex32VectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
            is Complex64VectorColumnType -> AtomicBooleanPredicate(column = column as ColumnDef<Complex64VectorValue>, operator = operator, not = atomic.not, values = atomic.dataList.map {
                it.toComplex64VectorValue()
                        ?: throw QueryException.QuerySyntaxException("Cannot compare ${column.name} to NULL value with operator $operator.")
            })
        }
    }

    /**
     * Parses and binds the kNN-lookup part of a GRPC [CottontailGrpc.Query]
     *
     * @param entity The [Entity] from which fetch columns.
     * @param knn The [CottontailGrpc.Knn] object.
     *
     * @return The resulting [KnnPredicate].
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseAndBindKnnPredicate(entity: Entity, knn: CottontailGrpc.Knn): KnnPredicate<*> {
        val columnName = entity.name.column(knn.attribute)
        val column = entity.columnForName(columnName)
                ?: throw QueryException.QueryBindException("Failed to bind column '$columnName'. Column does not exist on entity!")
        val distance = Distances.valueOf(knn.distance.name).kernel
        val hint = knn.hint.toHint(entity)


        return when (column.type) {
            is DoubleVectorColumnType -> {
                val query = knn.queryList.map { q -> q.toDoubleVectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { w -> w.toDoubleVectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<DoubleVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<DoubleVectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<DoubleVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is FloatVectorColumnType -> {
                val query = knn.queryList.map { q -> q.toFloatVectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toFloatVectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<FloatVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<FloatVectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<FloatVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is LongVectorColumnType -> {
                val query = knn.queryList.map { q -> q.toLongVectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toLongVectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<LongVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<LongVectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<LongVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is IntVectorColumnType -> {
                val query = knn.queryList.map { q -> q.toIntVectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toIntVectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<IntVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<IntVectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<IntVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is BooleanVectorColumnType -> {
                val query = knn.queryList.map { q -> q.toBooleanVectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toBooleanVectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<BooleanVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<BooleanVectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<BooleanVectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is Complex32VectorColumnType -> {
                val query = knn.queryList.map { q -> q.toComplex32VectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toComplex32VectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<Complex32VectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<Complex32VectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else {
                    KnnPredicate(column = column as ColumnDef<Complex32VectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            is Complex64VectorColumnType -> {
                val query = knn.queryList.map { q -> q.toComplex64VectorValue() }
                if (knn.weightsCount > 0) {
                    val weights = knn.weightsList.map { it.toComplex64VectorValue() }
                    if (weights.all { it.allOnes() }) {
                        KnnPredicate(column = column as ColumnDef<Complex64VectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                    } else {
                        KnnPredicate(column = column as ColumnDef<Complex64VectorValue>, k = knn.k, query = query, weights = weights, distance = distance, hint = hint)
                    }
                } else{
                    KnnPredicate(column = column as ColumnDef<Complex64VectorValue>, k = knn.k, query = query, distance = distance, hint = hint)
                }
            }
            else -> throw QueryException.QuerySyntaxException("A kNN predicate does not contain a valid query vector!")
        }
    }

    /**
     * Parses and binds the projection part of a gRPC [CottontailGrpc.Query]
     *
     * @param entity The [Entity] involved in the projection.
     * @param projection The [CottontailGrpc.Projection] object.
     *
     * @return The resulting [ProjectionPhysicalNodeExpression].
     */
    private fun parseAndBindProjection(entity: Entity, projection: CottontailGrpc.Projection): ProjectionLogicalNodeExpression = try {
        val availableColumns = entity.allColumns()
        val fields = mutableListOf<Pair<ColumnDef<*>, Name.ColumnName?>>()

        /* Handle star projection. */
        if (projection.attributesMap.containsKey("*")) {
            availableColumns.forEach {
                fields.add(Pair(it, null))
            }
        }

        /* Handle other kinds of projections. */
        projection.attributesMap.forEach { (expr, alias) ->
            /* Fetch columns that match field and add them to list of requested columns */
            if (expr != "*") {
                val columnName = entity.name.column(expr)
                val column = availableColumns.first { it.name == columnName }

                /* Return field to alias mapping. */
                fields.add(Pair(column, if (alias.isBlank()) {
                    null
                } else {
                    Name.ColumnName(alias)
                }))
            }
        }

        ProjectionLogicalNodeExpression(type = Projection.valueOf(projection.op.name), fields = fields)
    } catch (e: java.lang.IllegalArgumentException) {
        throw QueryException.QuerySyntaxException("The query lacks a valid SELECT-clause (projection): ${projection.op} is not supported.")
    }
}