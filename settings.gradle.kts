pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "imarets"

include(
    ":three-externals",
    ":bloom-api",
    ":engine",
    ":bloom-core",
    ":bloom-three",
    ":bloom-2d",
    ":site",
)
