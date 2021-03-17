package com.softbankrobotics.peppermapping.actuation.internal

import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.softbankrobotics.dx.pepperextras.actuation.ExtraLookAt
import com.softbankrobotics.dx.pepperextras.actuation.ExtraLookAtBuilder
import com.softbankrobotics.dx.pepperextras.actuation.makeDetachedFrame
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.PepperRobotData

internal suspend fun alignPepperHead(qiContext: QiContext) {
    val transform = TransformBuilder.create().fromTranslation(
            Vector3(1.0, 0.0, PepperRobotData.GAZE_FRAME_Z))
    val robotFrame = qiContext.actuation.async().robotFrame().await()
    val lookAtFrame = qiContext.mapping.async().makeDetachedFrame(robotFrame, transform).await()
    ExtraLookAtBuilder.with(qiContext).withFrame(lookAtFrame)
            .withTerminationPolicy(ExtraLookAt.TerminationPolicy.TERMINATE_WHEN_LOOKING_AT_OR_NOT_MOVING_ANYMORE)
            .buildAsync().await()
            .apply {
                policy = LookAtMovementPolicy.HEAD_ONLY
            }.async().run().await()
}