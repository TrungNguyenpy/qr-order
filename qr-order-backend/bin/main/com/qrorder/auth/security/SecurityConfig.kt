package com.qrorder.auth.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security: stateless JWT, mặc định từ chối (BR-A01), CORS whitelist (NFR-04).
 *
 * Phân quyền 2 tầng (SRS §10):
 * - Role: ROLE_ADMIN, ROLE_MANAGER, ROLE_STAFF, ROLE_KITCHEN, ROLE_GUEST.
 *   Phân cấp: MANAGER gồm quyền STAFF và KITCHEN; ADMIN đứng riêng, không kế thừa (O3, BR-A06).
 * - Permission nghiệp vụ động: `@PreAuthorize("@perm.has('PAYMENT_PROCESS')")` - xem [PermissionGuard].
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        principalResolver: AuthPrincipalResolver,
        errorWriter: SecurityErrorWriter,
        corsConfigurationSource: CorsConfigurationSource,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() } // Không dùng cookie/session: JWT gửi qua header Authorization.
            .cors { it.configurationSource(corsConfigurationSource) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(RestAuthenticationEntryPoint(errorWriter))
                    .accessDeniedHandler(RestAccessDeniedHandler(errorWriter))
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(*PUBLIC_PATHS).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(JwtAuthenticationFilter(principalResolver), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /** Mặc định bcrypt, lưu kèm tiền tố {bcrypt} để có thể chuyển sang thuật toán khác sau này (NFR-04). */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun corsConfigurationSource(corsProperties: CorsProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = corsProperties.allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "X-Correlation-Id")
            exposedHeaders = listOf("X-Correlation-Id")
            allowCredentials = false
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    companion object {
        /**
         * Endpoint không cần đăng nhập. Đường dẫn /ws chỉ là bước bắt tay HTTP; xác thực STOMP thực hiện ở
         * lệnh CONNECT (StompAuthChannelInterceptor).
         */
        val PUBLIC_PATHS = arrayOf(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            // Đăng xuất chỉ cần refresh token (access token có thể đã hết hạn); idempotent.
            "/api/v1/auth/logout",
            "/api/v1/public/**",
            "/ws/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/error",
        )

        const val ROLE_HIERARCHY = "ROLE_MANAGER > ROLE_STAFF\nROLE_MANAGER > ROLE_KITCHEN"

        /** Phân cấp role dùng cho cả luật URL lẫn method security. */
        @Bean
        @JvmStatic
        fun roleHierarchy(): RoleHierarchy = RoleHierarchyImpl.fromHierarchy(ROLE_HIERARCHY)

        @Bean
        @JvmStatic
        fun methodSecurityExpressionHandler(roleHierarchy: RoleHierarchy): MethodSecurityExpressionHandler =
            DefaultMethodSecurityExpressionHandler().apply { setRoleHierarchy(roleHierarchy) }
    }
}
