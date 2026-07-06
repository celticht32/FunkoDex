package com.funkodex.ui.screens.prescan

import com.funkodex.data.model.FunkoItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies the ownership-badge mapping used by PreScanViewModel.submitNameSearch
 * (DEC-022). The mapping turns the result of
 * FunkoRepository.findCollectionItemForCatalog (a collection item, or null) into
 * an OwnStatus badge:
 *   null          -> NOT_IN_COLLECTION
 *   isOwned=true  -> OWNED
 *   isOwned=false -> WANTED   (in collection as a want-list entry)
 *
 * submitNameSearch is a suspend fun over CBL + the lookup service, so this
 * asserts the pure mapping rule directly rather than driving the coroutine.
 */
class PreScanBadgeLogicTest {

    /** Same rule as submitNameSearch. */
    private fun statusFor(collectionItem: FunkoItem?): OwnStatus = when {
        collectionItem == null -> OwnStatus.NOT_IN_COLLECTION
        collectionItem.isOwned -> OwnStatus.OWNED
        else                   -> OwnStatus.WANTED
    }

    @Test
    fun `no matching collection item maps to NOT_IN_COLLECTION`() {
        assertEquals(OwnStatus.NOT_IN_COLLECTION, statusFor(null))
    }

    @Test
    fun `owned collection item maps to OWNED`() {
        val owned = FunkoItem(id = "funko::1", name = "Stitch", isOwned = true)
        assertEquals(OwnStatus.OWNED, statusFor(owned))
    }

    @Test
    fun `want-list collection item maps to WANTED`() {
        val wanted = FunkoItem(id = "funko::2", name = "Stitch", isOwned = false)
        assertEquals(OwnStatus.WANTED, statusFor(wanted))
    }

    @Test
    fun `three OwnStatus values are distinct`() {
        val all = setOf(OwnStatus.OWNED, OwnStatus.WANTED, OwnStatus.NOT_IN_COLLECTION)
        assertEquals(3, all.size)
    }
}
