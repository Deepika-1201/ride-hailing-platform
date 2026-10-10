plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ridehailing"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val springModulithVersion = "2.1.1"
val archunitVersion = "1.5.1"
val h3Version = "4.5.0"
val jsonSchemaValidatorVersion = "3.0.8"
val jqwikVersion = "1.10.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:$springModulithVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation("com.uber:h3:$h3Version")
    implementation("io.lettuce:lettuce-core")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("net.jqwik:jqwik:$jqwikVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

// Only the executable jar is shipped.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    // LLD §17.2: -Ptags=race runs the race suite alone.
    val tags = providers.gradleProperty("tags").orNull
    if (tags != null) {
        useJUnitPlatform { includeTags(*tags.split(",").toTypedArray()) }
    }
    systemProperty("user.timezone", "UTC")
    // LLD §17.2: each race repeats this often; -PraceRepetitions=20 for quick runs.
    systemProperty("ride.races.repetitions", providers.gradleProperty("raceRepetitions").getOrElse("200"))
    // LLD §17.1: the contract coverage check needs the whole suite, which --tests and -Ptags leave out.
    systemProperty("ride.contract-coverage", tags == null
            && gradle.startParameter.taskRequests.none { request -> request.args.any { it.startsWith("--tests") } })
    jvmArgs("-XX:+EnableDynamicAgentLoading", "--enable-native-access=ALL-UNNAMED")
    // Testcontainers' cleanup container mounts the socket from inside the Docker VM (Colima) or host (Linux CI).
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    // H3 loads its native library (ADR-012).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootTestRun") {
    mainClass = "com.ridehailing.TestRideHailingApplication"
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
}
