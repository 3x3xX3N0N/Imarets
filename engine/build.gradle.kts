// engine: SpaceGraph ZUI port (com.example.spacegraphkt). engine/attic/ is NOT compiled.
plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        useEsModules()
        browser {
            testTask { enabled = false } // tests run on node: gradlew :engine:jsNodeTest
        }
        nodejs()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalJsExport")
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
    sourceSets {
        jsMain.dependencies {
            api(project(":three-externals"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
