package com.qrorder.billing

import com.qrorder.billing.service.BankTransferFactory
import com.qrorder.billing.service.VietQrLinks
import com.qrorder.billing.service.VietQrProperties
import com.qrorder.common.settings.RestaurantSettings
import com.qrorder.common.settings.RestaurantSettingsService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** VietQR: ghép URL ảnh và điều kiện hiển thị (thuần, không cần Spring/DB). */
class BankTransferQrTest {

    private val props = VietQrProperties()
    private val factory = BankTransferFactory(mock(RestaurantSettingsService::class.java), props)

    private fun settings(bin: String? = "970436", no: String? = "0011001234567", name: String? = "NGUYEN VAN A") =
        RestaurantSettings().apply { bankBin = bin; bankAccountNo = no; bankAccountName = name }

    @Test
    fun `image url carries bank, account, template, amount, note and encoded name`() {
        val url = VietQrLinks.imageUrl(props, "970436", "0011001234567", "NGUYEN VAN A", 159_000, "BILL-20261125-0001")
        assertEquals(
            "https://img.vietqr.io/image/970436-0011001234567-compact2.png" +
                "?amount=159000&addInfo=BILL-20261125-0001&accountName=NGUYEN%20VAN%20A",
            url,
        )
    }

    @Test
    fun `base url and template are configurable and a trailing slash is tolerated`() {
        val custom = VietQrProperties(imageBaseUrl = "https://qr.example.test/img/", template = "qr_only")
        assertEquals(
            "https://qr.example.test/img/970436-123456-qr_only.png?amount=1&addInfo=B&accountName=A",
            VietQrLinks.imageUrl(custom, "970436", "123456", "A", 1, "B"),
        )
    }

    @Test
    fun `unicode in account name is percent-encoded as UTF-8`() {
        val url = VietQrLinks.imageUrl(props, "970436", "123456", "NGUYỄN VĂN A", 1, "B")
        assertEquals(true, url.endsWith("accountName=NGUY%E1%BB%84N%20V%C4%82N%20A"), url)
    }

    @Test
    fun `factory returns info only when fully configured and money is due`() {
        val info = assertNotNull(factory.build(settings(), "BILL-1", 59_000))
        assertEquals(59_000, info.amount)
        assertEquals("BILL-1", info.transferNote)
        assertEquals("NGUYEN VAN A", info.accountName)

        assertNull(factory.build(settings(), "BILL-1", 0))
        assertNull(factory.build(settings(), "BILL-1", -5))
        assertNull(factory.build(settings(bin = null, no = null, name = null), "BILL-1", 1_000))
        assertNull(factory.build(settings(name = null), "BILL-1", 1_000))
    }
}
