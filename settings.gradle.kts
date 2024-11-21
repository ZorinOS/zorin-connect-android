pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        /* Needed for org.apache.sshd debugging
        maven {
            url = uri("https://jitpack.io")
        }
        */
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm-util:9.6")
    }
}

rootProject.name = "zorin-connect-android"
