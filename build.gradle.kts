plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass = "com.codeflow.CodeFlow"
}

repositories {
    mavenCentral()
}

dependencies {
    // OpenTelemetry API/SDK + OTLP HTTP exporter. The BOM keeps all OTel
    // artifacts on one compatible stable release.
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.65.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.postgresql:postgresql:42.7.13")

    // 终端 I/O（TUI 框架的底层驱动）
    implementation("org.jline:jline:3.28.0")

    // Markdown terminal rendering
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // LLM SDKs
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")

    // Web server (Remote mode)
    implementation("io.javalin:javalin:6.6.0")

    // Config & JSON
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "codeflow"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.distZip { dependsOn(tasks.shadowJar) }
tasks.distTar { dependsOn(tasks.shadowJar) }
tasks.startScripts { dependsOn(tasks.shadowJar) }
tasks.named("startShadowScripts") { dependsOn(tasks.jar) }
