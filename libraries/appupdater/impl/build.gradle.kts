import extension.buildConfigFieldStr
import extension.readLocalProperty
import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.libraries.appupdater.impl"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val updateManifestUrl = providers.environmentVariable("ELEMENT_X_UPDATE_MANIFEST_URL").orNull
            ?: readLocalProperty("updateManifestUrl")
            ?: "https://raw.githubusercontent.com/infiniteCable2/element-x-android/develop/release/update.json"
        buildConfigFieldStr(
            name = "UPDATE_MANIFEST_URL",
            value = updateManifestUrl,
        )
    }
}

setupDependencyInjection()

dependencies {
    api(projects.libraries.appupdater.api)

    implementation(projects.libraries.core)
    implementation(projects.libraries.di)
    implementation(projects.libraries.network)
    implementation(libs.androidx.corektx)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(platform(libs.network.okhttp.bom))
    implementation(libs.network.okhttp)

    testCommonDependencies(libs)
}
