package com.softbankrobotics.peppermapping.actuation.internal

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.softbankrobotics.dx.pepperextras.actuation.StubbornGoToBuilder
import com.softbankrobotics.dx.pepperextras.actuation.internal.RunnableAction
import com.softbankrobotics.dx.pepperextras.actuation.makeDetachedFrame
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.*
import com.softbankrobotics.peppermapping.R
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerInfo
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

internal class GoForwardSearchingForNewMarker internal constructor(
        private val qiContext: QiContext,
        private val config: Config)
    : RunnableAction<Set<ArucoMarker>, GoForwardSearchingForNewMarker.Async>() {

    data class Config(
            val markerLength: Double,
            val getMarkerInfoCallback: (Int) -> ArucoMarkerInfo,
            val alreadyKnownMarkers: Collection<Int>,
            val currentMarkerFrame: Frame
    )

    override val _asyncInstance = Async()
    inner class Async internal constructor()
        : RunnableAction<Set<ArucoMarker>, GoForwardSearchingForNewMarker.Async>.Async() {

        private val dintFindMarkerAnimation = AnimationBuilder.with(qiContext).withResources(R.raw.didnt_find_marker).buildAsync()
        private val didntFindMarkerAnim = dintFindMarkerAnimation.andThenCompose {
            AnimateBuilder.with(qiContext).withAnimation(it).buildAsync()
        }
        private val missingMarkerAnimation = AnimationBuilder.with(qiContext).withResources(R.raw.missing_marker).buildAsync()
        private val missingMarkerAnim = missingMarkerAnimation.andThenCompose {
            AnimateBuilder.with(qiContext).withAnimation(it).buildAsync()
        }

        override fun _run(scope: CoroutineScope): Future<Set<ArucoMarker>>
                = scope.asyncFuture(CoroutineName("$ACTION_NAME|_run")) {
            val positions = getlistOfFrameInFrontOfMarker(config.currentMarkerFrame)
            for ((index, position) in positions.withIndex()) {

                Log.d(TAG, "Going to position in front of marker (${index + 1}/${positions.size})")
                val success = goToMarkerFrame(position)
                Log.d(TAG, "Position reached: $success")

                Log.d(TAG, "Searching for markers mostly in front")
                val markers = lookFrontForNewMarkers()
                if (markers.isNotEmpty()) {
                    Log.d(TAG, "Stopping. ${markers.size} marker(s) detected.")
                    return@asyncFuture markers
                } else {
                    Log.d(TAG, "No marker found")
                    didntFindMarkerAnim.await().async().run().await()
                }
            }
            Log.d(TAG, "Stopping. No markers were found.")
            missingMarkerAnim.await().async().run().await()
            setOf<ArucoMarker>()
        }

        private suspend fun lookFrontForNewMarkers(): Set<ArucoMarker> = coroutineScope {
            Log.d(TAG, "Look around and detect aruco marker")
            LookAroundAndDetectArucoMarkerBuilder.with(qiContext)
                    .withMarkerLength(config.markerLength)
                    .withArucoMarkerFrameLocalizationPolicy(ArucoMarkerFrameLocalizationPolicy.ATTACHED_TO_MAPFRAME)
                    .withLookAtMovementPolicy(LookAtMovementPolicy.HEAD_ONLY)
                    .withLookAtSphericalCoordinates(*FLOOR_LOOK_AROUND_FRONT_ONLY_SPHERICAL_COORDINATES)
                    .withArucoMarkerValidationCallback(object: LookAroundAndDetectArucoMarker.ArucoMarkerValidationCallback {
                        override fun isMarkerValid(marker: ArucoMarker): Future<Boolean> {
                            // To be accepted, markers:
                            // - must be on the floor
                            // - must not be known
                            // - must be valid markers number
                            if (config.getMarkerInfoCallback(marker.id).type != ArucoMarkerType.INVALID
                                    && !config.alreadyKnownMarkers.contains(marker.id)) {
                                return qiContext.actuation.async().isMarkerOnTheFloor(marker.frame)
                            }
                            return Future.of(false)
                        }
                    })
                    .withTerminationCallback(LookAroundAndDetectArucoMarker
                            .TerminateWhenSomeMarkersDetected())
                    .buildAsync().await()
                    .async().run(this).await()
        }

        private suspend fun goToMarkerFrame(markerFrame: Frame): Boolean = coroutineScope {
            alignPepperHead(qiContext)
            // Rotate marker so that X comes to Z axis, so that robot align correctly
            val transformRotationY = TransformBuilder.create().fromRotation(Quaternion(0.0, -0.707, 0.0, 0.707))
            val freeFrame = qiContext.mapping.async().makeFreeFrame().await()
            freeFrame.async().update(markerFrame, transformRotationY, 0).await()
            val frame = freeFrame.async().frame().await()
            StubbornGoToBuilder.with(qiContext)
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .withFrame(frame)
                    .withWalkingAnimationEnabled(false)
                    .withMaxSpeed(0.1f)
                    .buildAsync().await()
                    .async().run(this).await()
        }

        private suspend fun getlistOfFrameInFrontOfMarker(markerFrame: Frame,
                                                          maxDistCm: Int = 300,
                                                          incrementDistCm: Int = 100): List<Frame> {
            return (0..maxDistCm step incrementDistCm).map { distance ->
                val vector = Vector3(0.0, 0.0, distance / 100.0)
                val transform = TransformBuilder.create().fromTranslation(vector)
                val res = qiContext.mapping.async().makeDetachedFrame(markerFrame, transform).await()
                res
            }
        }
    }
}