package org.vitrivr.cottontail.database.queries.planning

import org.vitrivr.cottontail.database.queries.planning.nodes.interfaces.NodeExpression
import org.vitrivr.cottontail.database.queries.planning.nodes.interfaces.RewriteRule
import org.vitrivr.cottontail.database.queries.planning.nodes.logical.LogicalNodeExpression
import org.vitrivr.cottontail.database.queries.planning.nodes.physical.PhysicalNodeExpression
import org.vitrivr.cottontail.model.exceptions.QueryException
import java.util.*

/**
 * This is a rather simple query planner that optimizes a [NodeExpression] by recursively applying
 * a set of [RewriteRule]s to get more sophisticated yet equivalent [NodeExpression]s.
 *
 * Query optimization takes place in two stages:
 *
 * During the first stage, the logical tree is rewritten by means of other [LogicalNodeExpression]s,
 * to generate several, equivalent representations of the query.
 *
 * During the second stage, the logical tree is rewritten by replacing [LogicalNodeExpression]s by
 * [PhysicalNodeExpression] to arrive at an executable query plan. Optimization during the second
 * stage is done based on estimated costs.
 *
 * @author Ralph Gasser
 * @version 1.1
 */
class CottontailQueryPlanner(logicalRewriteRules: Collection<RewriteRule>, physicalRewriteRules: Collection<RewriteRule>) {

    /** The [RuleShuttle] for the logical rewrite phase. */
    private val logicalShuttle = RuleShuttle(logicalRewriteRules)

    /** The [RuleShuttle] for the physical rewrite phase. */
    private val physicalShuttle = RuleShuttle(physicalRewriteRules)

    /**
     * Generates a list of equivalent [NodeExpression]s by recursively applying [RewriteRule]s
     * on the seed [NodeExpression] and derived [NodeExpression]. The level of recursion and the number
     * of candidates to consider per level can be configured.
     *
     * @param expression The [NodeExpression] to optimize.
     * @param recursion The depth of recursion before final candidate is selected.
     * @param candidatesPerLevel The number of candidates to generate per recursion level.
     *
     * @throws QueryException.QueryPlannerException If planner fails to generate a valid execution plan.
     */
    fun plan(expression: LogicalNodeExpression, recursion: Int, candidatesPerLevel: Int): Collection<PhysicalNodeExpression> {
        /** Generate stage 1 candidates by logical optimization. */
        val stage1 = this.optimize(expression, this.logicalShuttle, recursion, candidatesPerLevel)

        /** Generate stage 2 candidates by physical optimization. */
        val stage2 = stage1.flatMap {
            this.optimize(it, this.physicalShuttle, recursion, candidatesPerLevel)
        }.filter {
            it.root.executable
        }.filterIsInstance<PhysicalNodeExpression>()
        if (stage2.isEmpty()) {
            throw QueryException.QueryPlannerException("Failed to generate a physical execution plan for expression: $expression.")
        } else {
            return stage2
        }
    }

    /**
     * Performs optimization of a [LogicalNodeExpression] tree, by applying plan rewrite rules that
     * manipulate that tree and return equivalent [LogicalNodeExpression] trees.
     *
     * @param expression The [LogicalNodeExpression] that should be optimized.
     */
    fun optimize(expression: NodeExpression, shuttle: RuleShuttle, recursion: Int, candidatesPerLevel: Int): Collection<NodeExpression> {
        val candidates = mutableListOf<NodeExpression>()
        if (recursion > 0) {
            val generated = this.generateCandidates(expression, shuttle)
            for (e in generated) {
                candidates.addAll(this.optimize(e, shuttle, recursion - 1, candidatesPerLevel))
            }
            candidates.add(expression)
        }
        return candidates
    }

    /**
     * Generates candidate [NodeExpression]s by applying the [RuleShuttle] to the given [NodeExpression].
     * Each re-write generated by a [RewriteRule] results in new candidate, which will in turn be processed.
     *
     * @param expression The [NodeExpression] to explore candidates for.
     * @param shuttle The [RuleShuttle] to use for optimization.
     * @param stopAfter The maximum number of iterations after which optimization will be aborted.
     */
    private fun generateCandidates(expression: NodeExpression, shuttle: RuleShuttle, stopAfter: Int = Int.MAX_VALUE): Collection<NodeExpression> {

        /** Initialize the list of LogicalNodeExpression candidates with the initial expression. */
        val candidates = LinkedList<NodeExpression>()
        val next = LinkedList<NodeExpression>()
        next.add(expression)

        /** Start optimization. */
        var iterations = 0
        while (next.size > 0 && iterations <= stopAfter) {
            val current = next.removeAt(0)
            val localCandidates = mutableListOf<NodeExpression>()
            current.apply(shuttle, localCandidates)

            if (localCandidates.size > 0) {
                next.addAll(localCandidates)
                candidates.addAll(localCandidates)
            }
            iterations += 1
        }

        /** Return candidates. */
        return candidates
    }
}