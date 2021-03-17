package com.softbankrobotics.peppermapping.actuation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.softbankrobotics.dx.pepperextras.actuation.internal.RunnableAction
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.ArucoMarker
import com.softbankrobotics.pepperaruco.actuation.GoToMarkerAndCheckItsPositionOnTheWayBuilder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope

class TraverseArucoMarkerPath  internal constructor(
        private val qiContext: QiContext,
        private val markerPath: ArucoMarkerPath)
    : RunnableAction<Result<Unit>, TraverseArucoMarkerPath.Async>() {

    override val _asyncInstance = Async()

    inner class Async internal constructor()
        : RunnableAction<Result<Unit>, TraverseArucoMarkerPath.Async>.Async() {

        @Throws(FailedToGoToMarkerException::class)
        override fun _run(scope: CoroutineScope): Future<Result<Unit>> = scope.asyncFuture(CoroutineName("$ACTION_NAME|_run")) {
            try {
                goToNextMarkersAndComeBack(scope, markerPath.iterator())
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure<Unit>(e)
            }
        }

        suspend fun goToNextMarkersAndComeBack(scope: CoroutineScope, iterator: Iterator<ArucoMarker>) {
            Log.d(TAG, "goToNextMarkersAndComeBack")
            if (iterator.hasNext()) {
                var marker = iterator.next()
                Log.d(TAG, "goToNextMarkersAndComeBack -> ${marker.id}")

                GoToMarkerAndCheckItsPositionOnTheWayBuilder.with(qiContext)
                        .withMarker(marker)
                        .withMarkerAlignmentEnabled(false)
                        .withMaxSpeed(0.8f)
                        .withWalkingAnimationEnabled(true)
                        .buildAsync().await()
                        .async().run(scope).await()
                        .let { (positionReachedWithSuccess, detectedMarker) ->
                            if (!positionReachedWithSuccess)
                                throw FailedToGoToMarkerException(marker.id)
                            if (detectedMarker != null)
                                marker = detectedMarker
                        }
                markerPath.registerOrUpdateMarkerData(marker)
                goToNextMarkersAndComeBack(scope, iterator)
                GoToMarkerAndCheckItsPositionOnTheWayBuilder.with(qiContext)
                        .withMarker(marker)
                        .withMarkerAlignmentEnabled(false)
                        .withMaxSpeed(0.8f)
                        .withWalkingAnimationEnabled(true)
                        .buildAsync().await()
                        .async().run(scope).await()
                        .let { (positionReachedWithSuccess, detectedMarker) ->
                            if (!positionReachedWithSuccess)
                                throw FailedToGoToMarkerException(marker.id)
                            if (detectedMarker != null)
                                marker = detectedMarker
                        }
                markerPath.registerOrUpdateMarkerData(marker)
                Log.d(TAG, "goToNextMarkersAndComeBack <- ${marker.id}")
            }
        }
    }
}
