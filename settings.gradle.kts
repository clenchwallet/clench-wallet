pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Build this reviewed native artifact explicitly before invoking Gradle. Never fall
// back to a remote artifact under the source-rebuild coordinate.
val localBdkVersion = "3.0.0-clench.1"
val localBdkRepository = rootDir.resolve("build/native-bdk/maven")
val localBdkArtifact = localBdkRepository.resolve(
    "org/bitcoindevkit/bdk-android/$localBdkVersion/bdk-android-$localBdkVersion"
)
check(listOf("aar", "pom", "module").all { extension ->
    file("$localBdkArtifact.$extension").isFile
}) {
    "Source-built BDK artifacts are missing. Run: python3 -B scripts/native/prepare-bdk.py"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "clenchSourceBuiltBdk"
                    url = uri(localBdkRepository)
                    metadataSources {
                        gradleMetadata()
                        mavenPom()
                    }
                }
            }
            filter {
                includeVersion("org.bitcoindevkit", "bdk-android", localBdkVersion)
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ClenchWallet"
include(":app")
