// bloom-api: interfaces + data classes shared by bloom-core, bloom-three, bloom-2d and site.
// commonMain = pure Kotlin (no DOM).  jsMain = the DOM-facing BloomRenderer contract.
// FROZEN after bringup - see scratch/CONTRACTS.md.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // no headless Chrome requirement; tests run on node
        }
        nodejs()
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
