import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-library")
}

android { namespace = "io.element.android.features.androidtoolbox.impl" }
setupDependencyInjection()

dependencies {
    api(projects.features.androidtoolbox.api)
    implementation(projects.libraries.di)
    implementation(projects.libraries.core)
    implementation(libs.coroutines.core)
    testCommonDependencies(libs)
    testImplementation(projects.features.androidtoolbox.test)
}
