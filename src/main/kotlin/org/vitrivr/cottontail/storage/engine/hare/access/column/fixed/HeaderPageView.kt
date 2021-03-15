package org.vitrivr.cottontail.storage.engine.hare.access.column.fixed

import org.vitrivr.cottontail.database.column.ColumnDef
import org.vitrivr.cottontail.model.basics.TupleId
import org.vitrivr.cottontail.model.basics.Type
import org.vitrivr.cottontail.storage.engine.hare.DataCorruptionException
import org.vitrivr.cottontail.storage.engine.hare.access.column.directory.DirectoryPageView
import org.vitrivr.cottontail.storage.engine.hare.basics.Page
import org.vitrivr.cottontail.storage.engine.hare.basics.PageConstants
import org.vitrivr.cottontail.storage.engine.hare.disk.structures.HarePage
import org.vitrivr.cottontail.storage.engine.hare.views.PageView

/**
 * The [HeaderPageView] of this [FixedHareColumnFile]. The [HeaderPageView] is located on the first
 * [HarePage] in the [FixedHareColumnFile] file.
 *
 * @author Ralph Gasser
 * @version 1.1.0
 */
inline class HeaderPageView(override val page: Page) : PageView {

    companion object {
        /** The offset into the [HeaderPageView]'s header to obtain the type of column. */
        const val HEADER_OFFSET_TYPE = 4

        /** The offset into the [HeaderPageView]'s header to obtain the logical size of column. */
        const val HEADER_OFFSET_LSIZE = 8

        /** The offset into the [HeaderPageView]'s header to obtain the physical size of column. */
        const val HEADER_OFFSET_PSIZE = 12

        /** The offset into the [HeaderPageView]'s header to obtain the file flags. */
        const val HEADER_OFFSET_FLAGS = 16

        /** The offset into the [HeaderPageView]'s header to obtain the number of entries. */
        const val HEADER_OFFSET_COUNT = 24

        /** The offset into the [HeaderPageView]'s header to obtain the number of deleted entries. */
        const val HEADER_OFFSET_DELETED = 32

        /** The offset into the [HeaderPageView]'s header to obtain the maximum [TupleId]. */
        const val HEADER_OFFSET_MAXTID = 40

        /** Bit Masks (for flags) */

        /** Mask for 'NULLABLE' bit in this [HeaderPageView]. */
        const val HEADER_MASK_NULLABLE = 1L shl 0

        /**
         * Initializes and wraps a [Page] for usage as a [HeaderPageView].
         *
         * @param page [Page] that should be wrapped.
         * @param columnDef The [ColumnDef] of the [FixedHareColumnFile] this [HeaderPageView] belongs to.
         */
        fun initialize(page: Page, columnDef: ColumnDef<*>) {
            val type = page.getInt(0)
            require(type == PageConstants.PAGE_TYPE_UNINITIALIZED) { "Cannot initialize page of type $type as ${DirectoryPageView::class.java.simpleName} (type = ${PageConstants.PAGE_TYPE_DIRECTORY})." }
            page.putInt(0, PageConstants.PAGE_TYPE_HEADER_FIXED_COLUMN)
            page.putInt(HEADER_OFFSET_TYPE, columnDef.type.ordinal)                                            /* 4: Type of column. See ColumnDef.forOrdinal() */
            page.putInt(HEADER_OFFSET_LSIZE, columnDef.type.logicalSize)                                            /* 8: Logical size of column (for structured data types). */
            page.putInt(HEADER_OFFSET_PSIZE,  columnDef.type.physicalSize + FixedHareColumnFile.ENTRY_HEADER_SIZE)                                                   /* 12: Physical size of a column entry in bytes. */
            page.putLong(18, if (columnDef.nullable) {                                                   /* 16: Column flags; 64 bits, one bit reserved. */
                (0L or HEADER_MASK_NULLABLE)
            } else {
                0L
            })
            page.putLong(HEADER_OFFSET_COUNT, 0L)                                                        /* 24: Number of entries (count) in column. */
            page.putLong(HEADER_OFFSET_DELETED, 0L)                                                      /* 32: Number of deleted entries (count) in column. */
            page.putLong(HEADER_OFFSET_MAXTID, -1L)                                                       /* 40: Number of deleted entries (count) in column. */
        }
    }

    /** The logical size of the [ColumnDef] held by this [FixedHareColumnFile]. */
    val size: Int
        get() = this.page.getInt(HEADER_OFFSET_LSIZE)

    /** The [Type] held by this [FixedHareColumnFile]. */
    val type: Type<*>
        get() = Type.forOrdinal(this.page.getInt(HEADER_OFFSET_TYPE), this.size)

    /** The physical size of an entry in bytes. */
    val entrySize: Int
        get() = this.page.getInt(HEADER_OFFSET_PSIZE)

    /** Special flags set for this [FixedHareColumnFile], such as, nullability. */
    val flags: Long
        get() = this.page.getLong(HEADER_OFFSET_FLAGS)

    /** True if this [FixedHareColumnFile] supports null values. */
    val nullable: Boolean
        get() = ((this.flags and HEADER_MASK_NULLABLE) > 0L)

    /** The total number of entries in this [FixedHareColumnFile]. */
    var count: Long
        get() = this.page.getLong(HEADER_OFFSET_COUNT)
        set(v) {
            this.page.putLong(HEADER_OFFSET_COUNT, v)
        }

    /** The number of deleted entries in this [FixedHareColumnFile]. */
    var deleted: Long
        get() = this.page.getLong(HEADER_OFFSET_DELETED)
        set(v) {
            this.page.putLong(HEADER_OFFSET_DELETED, v)
        }

    /** The maximum [TupleId] for this [FixedHareColumnFile]. */
    var maxTupleId: TupleId
        get() = this.page.getLong(HEADER_OFFSET_MAXTID)
        set(v) {
            this.page.putLong(HEADER_OFFSET_MAXTID, v)
        }

    /**
     * Validates this [HeaderPageView] and returns it.
     *
     * @return this.
     */
    override fun validate(): HeaderPageView {
        require(this.page.getInt(0) == PageConstants.PAGE_TYPE_HEADER_FIXED_COLUMN) { IllegalStateException("Page identifier mismatch (expected = ${PageConstants.PAGE_TYPE_HEADER_FIXED_COLUMN}, actual = ${this.page.getInt(0)}).") }
        require(this.count >= 0) { DataCorruptionException("Negative number of entries in HARE variable length column file.") }
        require(this.deleted >= 0) { DataCorruptionException("Negative number of deleted entries in HARE variable length column file.") }
        return this
    }
}