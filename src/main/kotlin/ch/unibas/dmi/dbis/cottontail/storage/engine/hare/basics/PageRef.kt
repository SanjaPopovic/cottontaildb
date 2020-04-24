package ch.unibas.dmi.dbis.cottontail.storage.engine.hare.basics
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.PageId
import ch.unibas.dmi.dbis.cottontail.storage.engine.hare.buffer.Priority

/**
 * A [PageRef] is a wrapper for [Page] managed by the HARE storage engine. As opposed to normal
 * [Page]s, [PageRef]s are usually a finite resource managed by a [BufferPoool]. A given [PageRef]
 * can point to different [Page]s at different points in time.
 *
 * @author Ralph Gasser
 * @version 1.0
 */
interface PageRef : Page, ReferenceCounted {
    /** [PageId] this [PageRef] is currently pointing to. */
    val id: PageId

    /** [Priority] this [PageRef] currently has. Acts as a hint to the eviction policy. */
    val priority: Priority

    /** Number of accesses to this [PageRef]. Acts as a hint to the eviction policy. */
    val accessed: Long

    /** The last access to this [PageRef]. Acts as a hint to the eviction policy. */
    val lastAccess: Long

    /** Flag indicating whether or not this [PageRef] is dirty. */
    val dirty: Boolean
}