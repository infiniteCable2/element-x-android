/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
plugins {
    id("io.element.android-library")
}

android { namespace = "io.element.android.features.androidtoolbox.api" }

dependencies {
    api(libs.serialization.json)
}
