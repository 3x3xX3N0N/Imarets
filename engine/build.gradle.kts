// engine: SpaceGraph ZUI port (com.example.spacegraphkt). engine/attic/ is NOT compiled.
plugins {
    kotlin("multiplatform")
}

// Opt-in browser harness for the engine alone: `gradlew :engine:jsBrowserProductionWebpack -PengineHarness`,
// then serve engine/harness (see engine/harness/README.md). Without the property this module is a plain
// library, exactly as before: no executable, no webpack, no harness sources.
val engineHarness = providers.gradleProperty("engineHarness").isPresent

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // tests run on node: gradlew :engine:jsNodeTest
        }
        nodejs()
        if (engineHarness) binaries.executable()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalJsExport")
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
    sourceSets {
        if (engineHarness) jsMain { kotlin.srcDir("src/harness/kotlin") }
        jsMain.dependencies {
            api(project(":three-externals"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
