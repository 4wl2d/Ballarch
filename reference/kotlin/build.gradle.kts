import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

subprojects {
    group = "dev.ballarch"
    version = "2.0.0-SNAPSHOT"
}

// Common setup for example modules (library modules configure themselves).
configure(subprojects.filter { it.path.startsWith(":examples:") }) {
    // Container projects such as :examples:shop have no sources.
    if (file("src").exists()) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
        dependencies {
            "testImplementation"(kotlin("test"))
            "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}

/**
 * Module-boundary fitness function (Core 2 rule R10, build level): in the shop
 * example, an `-api` module depends only on the kernel, and an implementation
 * module depends only on the kernel and on `-api` modules (never on another
 * feature's implementation). Test-only dependencies are not checked.
 */
val checkModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Fails if a shop module depends on another feature's implementation module."
    doLast {
        val violations = mutableListOf<String>()
        subprojects.filter { it.path.startsWith(":examples:shop:") }.forEach { p ->
            val deps = listOf("api", "implementation", "compileOnly")
                .flatMap { c -> p.configurations.findByName(c)?.dependencies.orEmpty() }
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
            for (d in deps) {
                val allowed = when {
                    p.name == "app" -> true // the composition root may depend on everything
                    p.name.endsWith("-http") -> true // adapters depend on the runtime and on their feature
                    p.name.endsWith("-api") -> d == ":ballarch-kernel"
                    else -> d == ":ballarch-kernel" || d.endsWith("-api")
                }
                if (!allowed) violations += "${p.path} -> $d"
            }
        }
        if (violations.isNotEmpty()) throw GradleException("module boundary violations:\n  " + violations.joinToString("\n  "))
        println("checkModuleBoundaries: OK")
    }
}
