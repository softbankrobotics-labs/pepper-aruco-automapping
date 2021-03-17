package com.softbankrobotics.peppermapping.actuation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.util.FutureUtils
import com.softbankrobotics.dx.pepperextras.actuation.distance
import com.softbankrobotics.dx.pepperextras.actuation.internal.RunnableAction
import com.softbankrobotics.dx.pepperextras.util.SingleThread
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.*
import com.softbankrobotics.peppermapping.R
import com.softbankrobotics.peppermapping.actuation.internal.GoForwardSearchingForNewMarker
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

// The robot must start looking at home marker (robot can be on another marker, its fine provided
// it looks at home)
class ExploreArucoMarkerPath internal constructor(
        private val qiContext: QiContext,
        private val config: Config,
        private val detectArucoMarkerConfig: DetectArucoMarker.Config)
    : RunnableAction<Result<ArucoMarkerPath>, ExploreArucoMarkerPath.Async>() {

    data class Config(
            var getArucoMarkerInfoCallback: (Int)-> ArucoMarkerInfo = { ArucoMarkerInfo(ArucoMarkerType.INVALID) },
            var markerPath: ArucoMarkerPath? = null
    )

    interface OnArucoMarkerExploredListener {
        fun onArucoMarkerExplored(marker: ArucoMarker)
    }

    fun addOnArucoMarkerExploredListener(listener: OnArucoMarkerExploredListener) {
        FutureUtils.get(async().addOnArucoMarkerExploredListener(listener))
    }

    fun removeOnArucoMarkerExploredListener(listener: OnArucoMarkerExploredListener) {
        FutureUtils.get(async().removeOnArucoMarkerExploredListener(listener))
    }

    fun removeAllOnArucoMarkerExploredListeners() {
        FutureUtils.get(async().removeAllOnArucoMarkerExploredListeners())
    }

    override val _asyncInstance = Async()

    inner class Async internal constructor()
        : RunnableAction<Result<ArucoMarkerPath>, ExploreArucoMarkerPath.Async>.Async() {

        private val listeners = mutableListOf<OnArucoMarkerExploredListener>()
        private val holderBA = HolderBuilder.with(qiContext)
                .withAutonomousAbilities(AutonomousAbilitiesType.BASIC_AWARENESS).build()

        private val arucoMarkerPath = ArucoMarkerPath()

        private val uTurnAnimation = AnimationBuilder.with(qiContext).withResources(R.raw.uturn).buildAsync()
        private val uTurnAnim = uTurnAnimation.andThenCompose {
            AnimateBuilder.with(qiContext).withAnimation(it).buildAsync()
        }

        override fun _run(scope: CoroutineScope): Future<Result<ArucoMarkerPath>> = scope.asyncFuture(CoroutineName("$ACTION_NAME|_run")) {
            Log.d(TAG, "Starting")
            holderBA.async().hold().await()

            // FIXME: check localization is running using mapframe

            try {
                explore(null, null)
                Result.success(arucoMarkerPath)
            } catch (e: InternalErrorException) {
                Log.e(TAG, "Got: $e")
                Result.failure<ArucoMarkerPath>(e)
            } catch (e: FailedToGoToMarkerException) {
                Log.e(TAG, "Got: $e")
                Result.failure<ArucoMarkerPath>(e)
            } finally {
                Log.d(TAG, "Stopping (in finally)")
                holderBA.async().release().await()
            }
        }

        private suspend fun fakeMarkerFrameFromRobotFrame(): Frame {
            val robotFrame = qiContext.actuation.async().robotFrame().await()
            val transformRotationY = TransformBuilder.create().fromRotation(Quaternion(0.0, 0.707, 0.0, 0.707))
            val freeFrame = qiContext.mapping.async().makeFreeFrame().await()
            freeFrame.async().update(robotFrame, transformRotationY, 0).await()
            return freeFrame.async().frame().await()
        }

        @Throws(FailedToGoToMarkerException::class, InternalErrorException::class)
        private suspend fun explore(
                nextMarker: ArucoMarker?,
                previousMarker: ArucoMarker?
        ): Unit = coroutineScope {
            Log.d(TAG, "Explore starting ${previousMarker?.id} -> ${nextMarker?.id}")

            Log.d(TAG, "Explore starting ${previousMarker?.id} -> ${nextMarker?.id}")
            var currentMarker = nextMarker
            val markerInfo = getMarkerInfo(currentMarker)

            // GoTo next marker
            if (currentMarker != null) {

                // Go to next marker and throws FailedToGoToMarkerException if marker unreachable
                val (positionReachedWithSuccess, detectedMarker) = GoToMarkerAndCheckItsPositionOnTheWayBuilder.with(qiContext)
                        .withMarker(currentMarker)
                        .withMarkerAlignmentEnabled(true, markerInfo.directionRotationAngle)
                        .withMaxSpeed(0.1f)
                        .withWalkingAnimationEnabled(false)
                        .buildAsync().await()
                        .async().run(this).await()
                if (detectedMarker != null)
                    currentMarker = detectedMarker
                if (!positionReachedWithSuccess)
                    throw FailedToGoToMarkerException(currentMarker.id)
                // Call the listeners
                currentMarker.let {
                    listeners.forEach { listener ->
                        listener.onArucoMarkerExplored(it)
                    }
                }
                // Save the marker in the marker path
                if (previousMarker != null)
                    arucoMarkerPath.addPath(previousMarker, currentMarker)
                else
                    arucoMarkerPath.addHome(currentMarker)
            }
            // Then search for markers around / in front
            val markers = when (markerInfo.type) {
                ArucoMarkerType.INTERSECTION -> {
                    // Find all new markers around the current marker (or robot if not on marker)
                    lookAroundForNewMarkers()
                }
                ArucoMarkerType.PATH -> {
                    // Find the closest marker in front of previous marker
                    val currentFrame = currentMarker?.let { applyRotationToZAxis(it.frame, markerInfo.directionRotationAngle) }
                            ?: fakeMarkerFrameFromRobotFrame()
                    lookFrontForNewMarkers(currentFrame)
                }
                ArucoMarkerType.ENDPOINT -> {
                    // We will be doing a U-turn
                    uTurnAnim.await().async().run().await()
                    setOf()
                }
                ArucoMarkerType.INVALID -> {
                    // This should never happen!!!
                    throw InternalErrorException()
                }
            }.toMutableSet()
            // Then for all markers found, go explore them, then return to current marker
            while (markers.isNotEmpty()) {
                getClosestMarkerFromRobot(markers.toList())?.let { closestMarker ->
                    if (!arucoMarkerPath.markerIds.contains(closestMarker.id)) {
                        // Explore the next marker
                        explore(closestMarker, currentMarker)
                        // Then go back to current marker
                        currentMarker?.let { m ->
                            Log.d(TAG, "Current marker not null, going on it")
                            GoToMarkerAndCheckItsPositionOnTheWayBuilder.with(qiContext)
                                    .withMarker(m)
                                    .withMarkerAlignmentEnabled(false)
                                    .withMaxSpeed(0.1f)
                                    .withWalkingAnimationEnabled(false)
                                    .buildAsync().await()
                                    .async().run(this).await()
                                    .let { (positionReachedWithSuccess, detectedMarker) ->
                                        if (!positionReachedWithSuccess)
                                            throw FailedToGoToMarkerException(m.id)
                                        if (detectedMarker != null)
                                            currentMarker = detectedMarker
                                    }
                        } ?: Log.d(TAG, "Current marker is null")
                    }
                    markers.remove(closestMarker)
                }
            }
            // Once here, robot should be on current marker.
        }

        private suspend fun lookFrontForNewMarkers(markerFrame: Frame): Set<ArucoMarker> = coroutineScope {
            GoForwardSearchingForNewMarker(qiContext, GoForwardSearchingForNewMarker.Config(
                    detectArucoMarkerConfig.markerLength,
                    config.getArucoMarkerInfoCallback,
                    arucoMarkerPath.markerIds,
                    markerFrame
            )).async().run(this).await().toList().let {
                getClosestMarkerFromRobot(it)
            }?.let { setOf(it) } ?: setOf()
        }

        private suspend fun lookAroundForNewMarkers(): Set<ArucoMarker> = coroutineScope {
            LookAroundAndDetectArucoMarkerBuilder.with(qiContext)
                    .withMarkerLength(detectArucoMarkerConfig.markerLength)
                    .withArucoMarkerFrameLocalizationPolicy(detectArucoMarkerConfig.localizationPolicy)
                    .withLookAtMovementPolicy(LookAtMovementPolicy.HEAD_AND_BASE)
                    .withLookAtSphericalCoordinates(*FLOOR_LOOK_AROUND_FULL_360_SPHERICAL_COORDINATES)
                    .withArucoMarkerValidationCallback(object: LookAroundAndDetectArucoMarker.ArucoMarkerValidationCallback {
                        override fun isMarkerValid(marker: ArucoMarker): Future<Boolean> {
                            if (config.getArucoMarkerInfoCallback(marker.id).type != ArucoMarkerType.INVALID
                                    && !arucoMarkerPath.markerIds.contains(marker.id)) {
                                return qiContext.actuation.async().isMarkerOnTheFloor(marker.frame)
                            }
                            return Future.of(false)
                        }
                    })
                    .buildAsync().await()
                    .async().run(this).await()
        }

        private suspend fun getClosestMarkerFromRobot(markers: List<ArucoMarker>): ArucoMarker? {
            val robotFrame = qiContext.actuation.async().robotFrame().await()
            return markers.minBy {
                robotFrame.async().distance(it.frame).await()
            }
        }

        private fun getMarkerInfo(marker: ArucoMarker?): ArucoMarkerInfo {
            return if (marker != null)
                config.getArucoMarkerInfoCallback(marker.id)
            else
                ArucoMarkerInfo(ArucoMarkerType.PATH)
        }

        fun addOnArucoMarkerExploredListener(listener: OnArucoMarkerExploredListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|addOnArucoMarkerExploredListener")) {
            listeners.add(listener)
            Unit
        }

        fun removeOnArucoMarkerExploredListener(listener: OnArucoMarkerExploredListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|removeOnArucoMarkerExploredListener"))  {
            listeners.remove(listener)
            Unit
        }

        fun removeAllOnArucoMarkerExploredListeners(): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|removeAllOnArucoMarkerExploredListeners"))  {
            listeners.clear()
        }
    }
}