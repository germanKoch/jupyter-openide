plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.openide.jupyter"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        pluginVerifier()
    }

    implementation("org.zeromq:jeromq:0.6.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Copy>("copyDist") {
    from(tasks.named("buildPlugin").map { layout.buildDirectory.dir("distributions") })
    into(layout.projectDirectory.dir("dist"))
}

tasks.named("buildPlugin") {
    finalizedBy("copyDist")
}
