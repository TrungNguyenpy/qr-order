package com.qrorder.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI là hợp đồng API duy nhất (SRS §11.6, §12): client TypeScript (Next.js) và Dart (Flutter)
 * đối chiếu theo /v3/api-docs. Mọi endpoint mặc định yêu cầu Bearer JWT, trừ endpoint public.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun qrOrderOpenApi(@Value("\${spring.application.name}") appName: String): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("QR Order API")
                    .version("v1")
                    .description(
                        "API hệ thống đặt món tại bàn qua QR ($appName). Phản hồi theo định dạng ApiResponse; " +
                            "lỗi trả error.code theo danh mục ErrorCode.",
                    ),
            )
            .components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Staff JWT (đăng nhập) hoặc Guest JWT (theo phiên bàn)"),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }
}
