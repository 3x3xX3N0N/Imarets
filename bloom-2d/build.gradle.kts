// bloom-2d: Canvas2D implementation of BloomRenderer + 2D ZUI. No three.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // tests run on node: gradlew :bloom-2d:jsNodeTest
        }
        nodejs()
    }
    sourceSets {
        jsMain.dependencies {
            api(project(":bloom-api"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
