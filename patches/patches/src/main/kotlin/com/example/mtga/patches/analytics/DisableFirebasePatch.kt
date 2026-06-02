package com.example.mtga.patches.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.mutableClassByTypeOrNull

// Firebase SDKs ship ProGuard rules that preserve these FQNs across R8.
private val FIREBASE_TARGETS =
    listOf(
        "Lcom/google/firebase/crashlytics/FirebaseCrashlytics;" to
            setOf(
                "log",
                "recordException",
                "sendUnsentReports",
                "setUserId",
                "setCustomKey",
                "setCrashlyticsCollectionEnabled",
            ),
        "Lcom/google/firebase/analytics/FirebaseAnalytics;" to
            setOf(
                "logEvent",
                "setUserProperty",
                "setUserId",
                "setAnalyticsCollectionEnabled",
            ),
    )

@Suppress("unused")
val disableFirebasePatch =
    bytecodePatch(
        name = "Disable Firebase telemetry",
        description = "Neutralizes Firebase Analytics + Crashlytics logging methods.",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            for ((descriptor, names) in FIREBASE_TARGETS) {
                val clazz = mutableClassByTypeOrNull(descriptor) ?: continue
                clazz.methods
                    .filter { it.name in names && it.returnType == "V" }
                    .forEach { it.addInstructions(0, "return-void") }
            }
        }
    }
