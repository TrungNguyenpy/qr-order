package com.qrorder.common.api

import org.springframework.data.domain.Page

/**
 * Trang dữ liệu trả về cho client (hợp đồng API dùng chung). `page` tính từ 0.
 * Không trả nguyên đối tượng Page của Spring Data để hợp đồng JSON ổn định khi nâng cấp thư viện.
 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <E, T> of(page: Page<E>, mapper: (E) -> T): PageResponse<T> =
            PageResponse(
                items = page.content.map(mapper),
                page = page.number,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages,
            )
    }
}
