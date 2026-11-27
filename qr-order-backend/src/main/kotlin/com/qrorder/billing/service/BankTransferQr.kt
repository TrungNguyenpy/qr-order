package com.qrorder.billing.service

import com.qrorder.common.settings.RestaurantSettings
import com.qrorder.common.settings.RestaurantSettingsService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.nio.charset.StandardCharsets

/**
 * Cấu hình dịch vụ dựng ảnh VietQR (không hardcode, biến môi trường VIETQR_*). Hệ thống chỉ ghép URL ảnh,
 * không gọi dịch vụ ngoài và không đối soát tự động: thu ngân xác nhận nhận tiền bằng tay.
 */
@ConfigurationProperties(prefix = "app.vietqr")
data class VietQrProperties(
    val imageBaseUrl: String = "https://img.vietqr.io/image",
    /** Mẫu ảnh của img.vietqr.io: compact, compact2, qr_only, print. */
    val template: String = "compact2",
)

/**
 * Thông tin chuyển khoản kèm ảnh VietQR cho một Bill chưa thu đủ. [qrImageUrl] đã gắn sẵn số tiền còn phải thu
 * ([amount]) và nội dung chuyển khoản [transferNote] (= số hóa đơn).
 */
data class BankTransferInfo(
    val bankBin: String,
    val accountNo: String,
    val accountName: String,
    val amount: Long,
    val transferNote: String,
    val qrImageUrl: String,
)

/** Ghép URL ảnh VietQR (thuần, dễ unit test). */
object VietQrLinks {
    fun imageUrl(
        properties: VietQrProperties,
        bankBin: String,
        accountNo: String,
        accountName: String,
        amount: Long,
        note: String,
    ): String =
        UriComponentsBuilder
            .fromUriString("${properties.imageBaseUrl.trimEnd('/')}/$bankBin-$accountNo-${properties.template}.png")
            .queryParam("amount", amount)
            .queryParam("addInfo", note)
            .queryParam("accountName", accountName)
            .encode(StandardCharsets.UTF_8)
            .build()
            .toUriString()
}

/** Dựng [BankTransferInfo] từ cấu hình ngân hàng của nhà hàng; trả null khi chưa cấu hình hoặc không còn tiền phải thu. */
@Component
class BankTransferFactory(
    private val settingsService: RestaurantSettingsService,
    private val properties: VietQrProperties,
) {
    fun forOutstanding(billNumber: String, outstanding: Long): BankTransferInfo? =
        build(settingsService.current(), billNumber, outstanding)

    internal fun build(settings: RestaurantSettings, billNumber: String, outstanding: Long): BankTransferInfo? {
        val bin = settings.bankBin
        val accountNo = settings.bankAccountNo
        val accountName = settings.bankAccountName
        if (bin == null || accountNo == null || accountName == null || outstanding <= 0) return null
        return BankTransferInfo(
            bankBin = bin,
            accountNo = accountNo,
            accountName = accountName,
            amount = outstanding,
            transferNote = billNumber,
            qrImageUrl = VietQrLinks.imageUrl(properties, bin, accountNo, accountName, outstanding, billNumber),
        )
    }
}
