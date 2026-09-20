// site: the executable. Kotlin/JS IR -> webpack production bundle -> scratch/dist.
// bringup owns this file until the integrator takes over.
plugins {
    kotlin("multiplatform")
}

val fontsource = providers.gradleProperty("npm.fontsource").get()

kotlin {
    js {
        useEsModules()
        browser {
            commonWebpackConfig {
                outputFileName = "site.js"
                // "extract" = real .css file via MiniCssExtractPlugin. NOT "inline": style-loader injects
                // <style> tags at runtime, which a strict CSP (style-src 'self') blocks.
                cssSupport {
                    enabled.set(true)
                    mode.set("extract")
                }
            }
            testTask { enabled = false }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":bloom-api"))
            implementation(project(":bloom-core"))
            implementation(project(":bloom-three"))
            implementation(project(":bloom-2d"))
            implementation(project(":engine"))
            implementation(project(":three-externals"))
            // fonts are bundled from npm (no CDN, no remote fonts); entry points are added in webpack.config.d/fonts.js
            implementation(npm("@fontsource/archivo", fontsource))
            implementation(npm("@fontsource/archivo-black", fontsource))
            implementation(npm("@fontsource/jetbrains-mono", fontsource))
            // KGP 2.4.20 writes the css rule into webpack.config.js but did not install its loaders here; pin them.
            implementation(devNpm("css-loader", "7.1.5"))
            implementation(devNpm("mini-css-extract-plugin", "2.10.2"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val productionDir = layout.buildDirectory.dir("dist/js/productionExecutable")
val scratchDist = rootProject.layout.projectDirectory.dir("../dist")

/** Fails the build if webpack bundled a second copy of three (SPEC 4). Reads the production source map. */
val verifySingleThree by tasks.registering {
    group = "verification"
    description = "Checks that the bundle contains three.webgpu.js and NOT three.module.js / three.cjs"
    dependsOn("jsBrowserDistribution")
    val mapFile = productionDir.map { it.file("site.js.map") }
    inputs.file(mapFile)
    doLast {
        val text = mapFile.get().asFile.readText()
        val builds = Regex("three/build/(three[a-z.]*\\.c?js)").findAll(text).map { it.groupValues[1] }.toSortedSet()
        logger.lifecycle("three builds in bundle: $builds")
        check("three.webgpu.js" in builds) { "three.webgpu.js is missing from the bundle: $builds" }
        check("three.module.js" !in builds && "three.cjs" !in builds) {
            "TWO COPIES OF THREE: $builds - something imports bare 'three' past the webpack alias"
        }
    }
}

/** Copies the production bundle to scratch/dist (keeps the integrator's own docs/scripts there). */
val copyDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Sync the production bundle into scratch/dist"
    dependsOn("jsBrowserDistribution", verifySingleThree)
    from(productionDir)
    into(scratchDist)
    preserve {
        include("*.md", "*.txt", "*.sh", "*.cmd", "*.ps1", "*.py")
    }
}

tasks.named("assemble") { dependsOn(copyDist) }
