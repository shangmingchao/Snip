plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.publish)
}

android {
    namespace = "cn.frank.snip"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates("io.github.shangmingchao", "snip", "1.0.0")

    pom {
        name.set("Snip")
        description.set("A Android library for cropping images")
        inceptionYear.set("2026")
        url.set("https://github.com/shangmingchao/Snip/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("shangmingchao")
                name.set("shangmingchao")
                url.set("https://github.com/shangmingchao/")
            }
        }
        scm {
            url.set("https://github.com/shangmingchao/Snip")
            connection.set("scm:git:git://github.com/shangmingchao/Snip.git")
            developerConnection.set("scm:git:ssh://git@github.com/shangmingchao/Snip.git")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
}
