// bloom-core: pure Kotlin math (Lorenz clock, Hopf fibers, NewCal). commonMain ONLY: no DOM, no three.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // tests run on node: gradlew :bloom-core:jsNodeTest
        }
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":bloom-api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
