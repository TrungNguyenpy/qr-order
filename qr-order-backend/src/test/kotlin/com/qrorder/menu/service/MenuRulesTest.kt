package com.qrorder.menu.service

import com.qrorder.common.error.BusinessException
import com.qrorder.common.error.ErrorCode
import com.qrorder.menu.domain.SelectionType
import com.qrorder.menu.repository.MenuSpecifications
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** OptionGroupRules (BR-O03, trùng CHECK của option_group) và tiện ích tìm kiếm của module menu. */
class OptionGroupRulesTest {
    private fun fields(type: SelectionType, required: Boolean, min: Int?, max: Int?): Set<String?> =
        assertFailsWith<BusinessException> { OptionGroupRules.resolve(type, required, min, max) }
            .also { assertEquals(ErrorCode.VALIDATION_FAILED, it.errorCode) }
            .details.map { it.field }.toSet()

    @Test
    fun `defaults - SINGLE gets max 1, required gets min 1, MULTIPLE unbounded`() {
        assertEquals(OptionGroupSpec(SelectionType.SINGLE, true, 1, 1), OptionGroupRules.resolve(SelectionType.SINGLE, true, null, null))
        assertEquals(OptionGroupSpec(SelectionType.SINGLE, false, 0, 1), OptionGroupRules.resolve(SelectionType.SINGLE, false, null, null))
        assertEquals(OptionGroupSpec(SelectionType.MULTIPLE, false, 0, null), OptionGroupRules.resolve(SelectionType.MULTIPLE, false, null, null))
        assertEquals(OptionGroupSpec(SelectionType.MULTIPLE, true, 2, 4), OptionGroupRules.resolve(SelectionType.MULTIPLE, true, 2, 4))
    }

    @Test
    fun `violations mirror ck_option_group_single, required, max_select and min_select`() {
        assertEquals(setOf("maxSelect"), fields(SelectionType.SINGLE, false, 0, 2))
        assertEquals(setOf("minSelect"), fields(SelectionType.MULTIPLE, true, 0, null))
        assertEquals(setOf("minSelect"), fields(SelectionType.MULTIPLE, false, 1, null))
        assertEquals(setOf("maxSelect"), fields(SelectionType.MULTIPLE, true, 3, 2))
        assertEquals(setOf("maxSelect"), fields(SelectionType.MULTIPLE, false, 0, 0))
        assertTrue("minSelect" in fields(SelectionType.MULTIPLE, false, -1, null))
        // SINGLE bắt buộc chọn 2: maxSelect = 1 < minSelect = 2.
        assertEquals(setOf("maxSelect"), fields(SelectionType.SINGLE, true, 2, 1))
    }
}

class MenuSpecificationsTest {
    @Test
    fun `contains pattern is lower-cased with LIKE wildcards escaped, blank keyword means no filter`() {
        assertEquals("%phở%", MenuSpecifications.containsPattern("  Phở "))
        assertEquals("%50\\%\\_off\\\\%", MenuSpecifications.containsPattern("50%_off\\"))
        assertNull(MenuSpecifications.containsPattern("   "))
        assertNull(MenuSpecifications.containsPattern(null))
    }
}
