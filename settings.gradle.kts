pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Dtrad"
include(":app")
include(":aichat")        // llama.cpp (HY-MT)
include(":inference-core") // NCNN + détection (yakuyomi-engine)
include(":engine")         // OCR, effacement, mise en page (yakuyomi-engine)
