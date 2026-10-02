// Throwaway spike S-3: memory and CPU per WebSocket connection, Tomcat (servlet) vs Netty (reactive).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}

tasks.bootJar {
    archiveFileName = "ws-spike.jar"
}
