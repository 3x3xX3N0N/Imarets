// verdantbloom.bar landing page - multi-module Kotlin/JS (IR) build.
// Toolchain is pinned in gradle.properties / gradle/wrapper; see ../CONTRACTS.md.
plugins {
    kotlin("multiplatform") version "2.4.20" apply false
}

allprojects {
    group = "bar.verdantbloom"
    version = "0.1.0-scratch"
}

// kotlin-js-store/yarn.lock is committed. Several agents add npm dependencies in parallel, so a changed
// lock file must not fail the build: warn and rewrite it instead (run `gradlew kotlinUpgradeYarnLock` to be explicit).
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().apply {
        yarnLockMismatchReport.set(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport.WARNING)
        yarnLockAutoReplace.set(true)
    }
}
