package com.example.mtga.patches.ad

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import com.example.mtga.patches.MTGA_COMPATIBLE_VERSIONS
import com.example.mtga.patches.MTGA_TARGET_PACKAGE
import com.example.mtga.patches.methodsNamed
import com.example.mtga.patches.mtgaTargets
import com.example.mtga.patches.mutableClassByType

// Skips the synchronous execute() variant: faking a non-null Response<T>
// is non-trivial. The blocked path returns without invoking
// Callback.onFailure; Truth Social's ad-fetch coroutine drops the orphan
// continuation silently, which matches the intent.

private const val BLOCKED_URL_SUBSTRING = "/truth/ads"

@Suppress("unused")
val blockOkHttpAdsPatch =
    bytecodePatch(
        name = "Block ad requests at the network layer",
        description = "Drops retrofit2 enqueue calls whose Request URL contains \"$BLOCKED_URL_SUBSTRING\".",
    ) {
        compatibleWith(MTGA_TARGET_PACKAGE(*MTGA_COMPATIBLE_VERSIONS))

        execute {
            val targets = mtgaTargets
            val okHttpCallDesc = targets.retrofitOkHttpCall.descriptor
            val enqueueName = targets.retrofitOkHttpCallEnqueueMethod
            val requestBuilderName = targets.retrofitOkHttpCallRequestMethod
            // okhttp3.Request is R8-renamed per build (we.B -> jg.D -> og.E ->
            // sg.D); read it from the TargetSet so the invoke-virtual return
            // descriptor matches the real method signature.
            val requestDesc = targets.okhttpRequest.descriptor

            mutableClassByType(okHttpCallDesc).methodsNamed(enqueueName).forEach { method ->
                method.addInstructionsWithLabels(
                    0,
                    """
                    invoke-virtual {p0}, $okHttpCallDesc->$requestBuilderName()$requestDesc
                    move-result-object v0
                    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;
                    move-result-object v0
                    const-string v1, "$BLOCKED_URL_SUBSTRING"
                    invoke-virtual {v0, v1}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                    move-result v0
                    if-eqz v0, :L_continue
                    return-void
                    :L_continue
                    nop
                    """,
                )
            }
        }
    }
