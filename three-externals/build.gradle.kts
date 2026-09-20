// three-externals: Kotlin externals for npm `three`. FROZEN after bringup - see scratch/CONTRACTS.md.
// The npm dependency is `api`-like: every module depending on this one gets `three` in the shared
// build/js/node_modules, and :site bundles it with webpack (no CDN).
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }
    sourceSets {
        jsMain.dependencies {
            api(npm("three", providers.gradleProperty("npm.three").get()))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
