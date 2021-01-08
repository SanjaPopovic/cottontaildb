package org.vitrivr.cottontail.database.index.va.bounds

import org.vitrivr.cottontail.database.index.va.signature.Signature

/**
 * Interface for bounds calculation in a [org.vitrivr.cottontail.database.index.va.VAFIndex]
 *
 * References:
 * [1] Weber, R. and Blott, S., 1997. An approximation based data structure for similarity search (No. 9141, p. 416). Technical Report 24, ESPRIT Project HERMES.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
interface Bounds {
    /** Lower bound of this [Bounds]. */
    val lb: Double

    /** Upper bound of this [Bounds]. */
    val ub: Double

    /**
     * Updates the lower and upper bounds of this [Bounds] using the given [Signature].
     *
     * @param signature The [Signature] to calculate the bounds for.
     * @return this
     */
    fun update(signature: Signature): Bounds

    /**
     * Checks if the given [Signature] is a VA-SSA candidate according to [1] by comparing the
     * lower bounds estimation to the given threshold and returns true if so and false otherwise.
     *
     * @param signature The [Signature] to check.
     * @param threshold The threshold for a [Signature] to be deemed a candidate. Can be used for early stopping.
     * @return True if [Signature] is a candidate, false otherwise.
     */
    fun isVASSACandidate(signature: Signature, threshold: Double): Boolean
}