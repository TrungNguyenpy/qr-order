// Phiên bản được khóa tại một chỗ để dễ nâng cấp (PROJECT_PROGRESS.md mục E).
// Spring Boot 3.5.16 là bản vá OSS cuối của dòng 3.x (hết hỗ trợ OSS 30/06/2026) – xem Issue BE-01.
plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    // Kotlin do Spring Boot 3.5 quản lý (kotlin.version = 1.9.25), tránh lệch phiên bản stdlib/reflect.
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
}

group = "com.qrorder"
version = "0.1.0-SNAPSHOT"
description = "QR Order - Backend API (Kotlin + Spring Boot)"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springdocVersion = "2.8.14"
val jjwtVersion = "0.12.6"
// Giai đoạn 3 (người dùng chốt): ZXing sinh ảnh QR PNG, OpenPDF xuất PDF in QR. OpenPDF 3.x dùng package org.openpdf.
val zxingVersion = "3.5.4"
val openPdfVersion = "3.0.5"

dependencies {
    // Web, validation, security, JPA, WebSocket (STOMP), Actuator
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database: PostgreSQL 16 + Flyway (Flyway 10+ cần module riêng cho PostgreSQL)
    // Driver ở scope implementation vì GlobalExceptionHandler đọc PSQLException (SQLSTATE, constraint).
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // JWT (HS256)
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    // QR bàn (Giai đoạn 3, FR-QR-01): ảnh PNG (ZXing core + javase/MatrixToImageWriter) và PDF in (OpenPDF).
    implementation("com.google.zxing:core:$zxingVersion")
    implementation("com.google.zxing:javase:$zxingVersion")
    implementation("com.github.librepdf:openpdf:$openPdfVersion")

    // OpenAPI (hợp đồng API, SRS §11.6)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // Test: JUnit 5 + Testcontainers (postgres:16-alpine)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // jvmTarget lấy theo toolchain Java 21.
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// Entity JPA cần mở (open) để Hibernate tạo proxy lazy. @Embeddable không cần proxy nên không mở
// (để dùng được data class cho khóa chính kép).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Integration test dùng Testcontainers: cần Docker đang chạy.
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootJar {
    archiveFileName.set("qr-order-backend.jar")
}

tasks.jar {
    enabled = false
}
