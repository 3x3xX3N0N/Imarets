// bloom-three: three.js (WebGPURenderer / forceWebGL) implementation of BloomRenderer.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // tests run on node: gradlew :bloom-three:jsNodeTest
        }
        nodejs()
    }
    sourceSets {
        jsMain.dependencies {
            api(project(":bloom-api"))
            api(project(":three-externals"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
