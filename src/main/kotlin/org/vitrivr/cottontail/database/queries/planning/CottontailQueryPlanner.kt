package org.vitrivr.cottontail.database.queries.planning

import org.vitrivr.cottontail.database.queries.OperatorNode
import org.vitrivr.cottontail.database.queries.QueryContext
import org.vitrivr.cottontail.database.queries.planning.nodes.logical.LogicalOperatorNode
import org.vitrivr.cottontail.database.queries.planning.nodes.physical.PhysicalOperatorNode
import org.vitrivr.cottontail.database.queries.planning.rules.RewriteRule
import org.vitrivr.cottontail.model.exceptions.QueryException

/**
 * This is a rather simple query planner that optimizes a [OperatorNode] by recursively applying
 * a set of [RewriteRule]s to get more sophisticated yet equivalent [OperatorNode]s.
 *
 * Query optimization takes place in two stages:
 *
 * During the first stage, the logical tree is rewritten by means of other [LogicalOperatorNode]s,
 * to generate several, equivalent representations of the query.
 *
 * During the second stage, the logical tree is rewritten by replacing [LogicalOperatorNode]s by
 * [PhysicalOperatorNode] to arrive at an executable query plan. Optimization during the second
 * stage is done based on estimated costs.
 *
 * @author Ralph Gasser
 * @version 1.2.0
 */
class CottontailQueryPlanner(logicalRewriteRules: Collection<RewriteRule>, physicalRewriteRules: Collection<RewriteRule>, val planCacheSize: Int = 100) {

    /** Internal cache used to store query plans for known queries. */
    private val planCache = LinkedHashMap<Long, PhysicalOperatorNode>()

    /** The [RuleGroup] for the logical rewrite phase. */
    private val logicalRules = RuleGroup(logicalRewriteRules)

    /** The [RuleGroup] for the physical rewrite phase. */
    private val physicalRules = RuleGroup(physicalRewriteRules)

    /**
     * Generates a [PhysicalOperatorNode]s for the given [LogicalOperatorNode] by recursively
     * applying [RewriteRule] to the seed [LogicalOperatorNode] and selecting the best candidate
     * in terms of cost.
     *
     * @param context The [QueryContext] to plan for.
     * @param bypassCache If the plan cache should be bypassed (forces new planning).
     *
     * @throws QueryException.QueryPlannerException If planner fails to generate a valid execution plan.
     */
    fun planAndSelect(context: QueryContext, bypassCache: Boolean = false, cache: Boolean = false) {
        /* Try to obtain PhysicalNodeExpression from plan cache, unless bypassCache has been set to true. */
        val logicalNodeExpression = context.logical
        require(logicalNodeExpression != null) { "Cannot plan for a QueryContext that doesn't have a valid logical query represntation." }
        val digest = logicalNodeExpression.deepDigest()
        if (!bypassCache) {
            if (this.planCache.containsKey(digest)) {
                context.physical = this.planCache[digest]
                return
            }
        }

        /* Execute actual query planning and select candidate with lowest cost. */
        val candidates = this.plan(logicalNodeExpression)
        context.physical = candidates.minByOrNull { it.totalCost } ?: throw QueryException.QueryPlannerException("Failed to generate a physical execution plan for expression: $logicalNodeExpression.")

        /* Update plan cache. */
        if (!cache) {
            if (this.planCache.size >= planCacheSize) {
                this.planCache.remove(this.planCache.keys.first())
            }
            this.planCache[digest] = context.physical!!
        }
    }

    /**
     * Generates a list of equivalent [PhysicalOperatorNode]s by recursively applying [RewriteRule]s
     * on the seed [LogicalOperatorNode] and derived [OperatorNode]s.
     *
     * @param expression The [LogicalOperatorNode] to plan.
     * @return List of [PhysicalOperatorNode] that execute the [LogicalOperatorNode]
     */
    fun plan(expression: LogicalOperatorNode): Collection<PhysicalOperatorNode> {
        /** Generate stage 1 candidates by logical optimization. */
        val stage1 = (this.optimize(expression, this.logicalRules) + expression)

        /** Generate stage 2 candidates by physical optimization. */
        val stage2 = stage1.flatMap {
            this.optimize(it, this.physicalRules)
        }.filterIsInstance<PhysicalOperatorNode>()
            .filter {
                it.root.executable
            }
        return stage2
    }

    /**
     * Clears the plan cache of this [CottontailQueryPlanner]
     */
    fun clearCache() = this.planCache.clear()

    /**
     * Performs optimization of a [LogicalOperatorNode] tree, by applying plan rewrite rules that
     * manipulate that tree and return equivalent [LogicalOperatorNode] trees.
     *
     * @param expression The [LogicalOperatorNode] that should be optimized.
     */
    private fun optimize(expression: OperatorNode, group: RuleGroup): Collection<OperatorNode> {
        val candidates = mutableListOf<OperatorNode>()
        val explore = mutableListOf<OperatorNode>()
        var pointer: OperatorNode? = expression
        while (pointer != null) {

            /* Apply rules to node and add results to list for exploration. */
            val results = group.apply(pointer)
            if (results.isEmpty()) {
                if (pointer.inputs.size > 0) {
                    explore.addAll(pointer.inputs)
                } else {
                    candidates.add(pointer.root)
                }
            }
            for (r in results) {
                if (r.inputs.size > 0) {
                    explore.addAll(r.inputs)
                } else {
                    candidates.add(r.root)
                }
            }

            /* Move pointer up in the tree. */
            pointer = explore.removeLastOrNull()
        }
        return candidates
    }
}