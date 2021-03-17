package com.softbankrobotics.peppermapping.actuation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.util.FutureUtils
import com.softbankrobotics.dx.pepperextras.geometry.IDENTITY_TRANSFORM
import com.softbankrobotics.dx.pepperextras.actuation.internal.RunnableAction
import com.softbankrobotics.dx.pepperextras.geometry.toApacheRotation
import com.softbankrobotics.dx.pepperextras.util.SingleThread
import com.softbankrobotics.dx.pepperextras.util.TAG
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.dx.pepperextras.util.awaitOrNull
import com.softbankrobotics.pepperaruco.actuation.DetectArucoMarker
import com.softbankrobotics.peppermapping.R
import com.softbankrobotics.peppermapping.actuation.internal.alignPepperHead
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*

class ExploreArucoMarkerPathAndMap internal constructor(
        private val qiContext: QiContext,
        private val config: Config,
        private val detectArucoMarkerConfig: DetectArucoMarker.Config
) : RunnableAction<Result<Pair<ExplorationMap, ArucoMarkerPath>>, ExploreArucoMarkerPathAndMap.Async>() {

    internal data class Config(
            var getArucoMarkerInfoCallback: (Int) -> ArucoMarkerInfo = { ArucoMarkerInfo(ArucoMarkerType.INVALID)},
            var endpointMarkers: Set<Int> = setOf()
    )

    interface OnStatusChangedListener {
        fun onStatusChanged(status: Status)
    }

    enum class Status {
        NOT_STARTED,
        LOCALIZING_AND_MAPPING,
        EXPLORING_PATH,
        RETRIEVING_MAP,
        LOCALIZING,
        REVISITING_PATH
    }

    fun addOnStatusChangedListener(listener: OnStatusChangedListener) {
        FutureUtils.get(async().addOnStatusChangedListener(listener))
    }

    fun removeOnStatusChangedListener(listener: OnStatusChangedListener) {
        FutureUtils.get(async().removeOnStatusChangedListener(listener))
    }

    fun removeAllOnStatusChangedListeners() {
        FutureUtils.get(async().removeAllOnStatusChangedListeners())
    }

    fun addOnArucoMarkerExploredListener(listener: ExploreArucoMarkerPath.OnArucoMarkerExploredListener) {
        FutureUtils.get(async().addOnArucoMarkerExploredListener(listener))
    }

    fun removeOnArucoMarkerExploredListener(listener: ExploreArucoMarkerPath.OnArucoMarkerExploredListener) {
        FutureUtils.get(async().removeOnArucoMarkerExploredListener(listener))
    }

    fun removeAllOnArucoMarkerExploredListeners() {
        FutureUtils.get(async().removeAllOnArucoMarkerExploredListeners())
    }

    override val _asyncInstance = Async()

    inner class Async internal constructor()
        : RunnableAction<Result<Pair<ExplorationMap, ArucoMarkerPath>>, Async>.Async() {

        private val holderBABM = HolderBuilder.with(qiContext)
                .withAutonomousAbilities(AutonomousAbilitiesType.BASIC_AWARENESS,
                        AutonomousAbilitiesType.BACKGROUND_MOVEMENT)
                .build()

        private val exploreArucoMarkerPath = ExploreArucoMarkerPathBuilder.with(qiContext)
                .withMarkerLength(detectArucoMarkerConfig.markerLength)
                .withMarkerDictionary(detectArucoMarkerConfig.dictionary)
                .withMarkerInfoCallback(config.getArucoMarkerInfoCallback)
                .build()

        private val listeners = mutableListOf<OnStatusChangedListener>()
        var status: Status = Status.NOT_STARTED
            private set(value) {
                // Set the status of the lookat only if it has changed
                if (field != value) {
                    Log.d(TAG, "Status change to ${value}")
                    field = value
                    if (value != Status.NOT_STARTED) {
                        // Call all the onStatusChanged listeners
                        listeners.forEach {
                            try {
                                Log.d(TAG, "Calling callback")
                                it.onStatusChanged(field)
                                Log.d(TAG, "Callback called")
                            } catch (e: java.lang.Exception) {
                                Log.e(TAG, "Uncaught exception: $e")
                            }
                        }
                    }
                }
            }

        private suspend fun animatedLookAround() {
            try {
                val lookAroundAnimation = AnimationBuilder.with(qiContext).withResources(R.raw.look_around).buildAsync().await()
                val localize360Trajectory = AnimationBuilder.with(qiContext).withResources(R.raw.localize_360).buildAsync().await()
                val trajectoryLabel = "trajectory:localize_360"
                val lookAroundAnim = AnimateBuilder.with(qiContext)
                        .withAnimation(lookAroundAnimation)
                        .buildAsync().await()
                val localize360Anim = AnimateBuilder.with(qiContext)
                        .withAnimation(localize360Trajectory)
                        .buildAsync().await()
                var turnFuture : Future<Void>? = null
                lookAroundAnim.addOnLabelReachedListener { label, _ ->
                    when {
                        label != trajectoryLabel -> Log.w(TAG, "Unexpected label in look_around animation: $label")
                        turnFuture !== null -> Log.w(TAG, "Got $label in look_around animation, but the trajectory has already been started!")
                        else -> {
                            turnFuture = localize360Anim.async().run()
                        }
                    }
                }
                lookAroundAnim.async().run().await()
                if (turnFuture == null) {
                    Log.w(TAG, "Didn't encounter a '$trajectoryLabel' label in the look_around animation")
                }
                turnFuture?.await()
            }  catch (e: Throwable) {
                Log.e(TAG, "Animated 360° failed because ${e}, running a brute force one.")
                stubbornRotate360()
            }
        }

        override fun _run(scope: CoroutineScope): Future<Result<Pair<ExplorationMap, ArucoMarkerPath>>>
                = scope.asyncFuture(CoroutineName(ACTION_NAME)) {
            var localizeAndMapFuture: Future<Void>? = null
            var localizeFuture: Future<Void>? = null
            try {
                // Hold background movements
                holderBABM.hold()

                status = Status.LOCALIZING_AND_MAPPING
                alignPepperHead(qiContext)

                // Start a LocalizeAndMap action
                var onLocalizedPromised = Promise<Result<Unit>>()
                val localizeAndMap = LocalizeAndMapBuilder.with(qiContext).buildAsync().await().apply {
                    addOnStatusChangedListener { status ->
                        if (status == LocalizationStatus.LOCALIZED)
                            onLocalizedPromised.setValue(Result.success(Unit))
                    }
                }
                localizeAndMapFuture = localizeAndMap.async().run().thenConsume {
                    launch {
                        if (it.hasError() && !onLocalizedPromised.future.isDone)
                            onLocalizedPromised.setValue(Result.failure(LocalizeAndMapException(it.errorMessage)))
                    }
                }

                // Wait for robot to be localized
                onLocalizedPromised.future.await().getOrThrow()

                // Explore the Aruco marker Path
                status = Status.EXPLORING_PATH
                val arucoMarkerPath = exploreArucoMarkerPath
                        .async().run(this).await().getOrThrow()

                if (!arucoMarkerPath.markerIds.containsAll(config.endpointMarkers)) {
                    throw EndpointsNotReachedException(arucoMarkerPath.markerIds)
                }

                animatedLookAround()


                // Wait one second at the end of exploration
                delay(1000)

                // Stop localization and retrieve map
                localizeAndMapFuture.requestCancellation()
                localizeAndMapFuture.await()

                status = Status.RETRIEVING_MAP

                val explorationMap = localizeAndMap.async().dumpMap().await()

                status = Status.LOCALIZING
                // Start a LocalizeAndMap action
                onLocalizedPromised = Promise()
                val localize = LocalizeBuilder.with(qiContext).withMap(explorationMap).buildAsync().await().apply {
                    addOnStatusChangedListener { status ->
                        if (status == LocalizationStatus.LOCALIZED)
                            onLocalizedPromised.setValue(Result.success(Unit))
                    }
                }
                localizeFuture = localize.async().run().thenConsume {
                    if (it.hasError())
                        onLocalizedPromised.setValue(Result.failure(LocalizeException(it.errorMessage)))
                }
                // Wait for robot to be localized
                onLocalizedPromised.future.await().getOrThrow()

                status = Status.REVISITING_PATH

                TraverseArucoMarkerPathBuilder.with(qiContext)
                        .withArucoMarkerPath(arucoMarkerPath)
                        .buildAsync().await()
                        .async().run(this).await().getOrThrow()

                // Stop localization
                localizeFuture.requestCancellation()
                localizeFuture.await()

                Result.success(Pair(explorationMap, arucoMarkerPath))
            } catch (e: Throwable) {
                Result.failure<Pair<ExplorationMap, ArucoMarkerPath>>(e)
            } finally {
                holderBABM.release()
                localizeAndMapFuture?.run {
                    if (!isDone) {
                        requestCancellation()
                        awaitOrNull()
                    }
                }
                localizeFuture?.run {
                    if (!isDone) {
                        requestCancellation()
                        awaitOrNull()
                    }
                }
            }
        }

        private suspend fun stubbornRotate360() = coroutineScope {
            var count = 0
            val robotFrame = qiContext.actuationAsync.await().async().robotFrame().await()
            val robotFrameSnapshot = qiContext.mappingAsync.await().async().makeFreeFrame().await()
            val robotFrameInPast = robotFrameSnapshot.async().frame().await()
            var angle = 0.0
            var snaptime: Long = 0
            val monitoring = this.async {
                while (isActive) {
                    robotFrameSnapshot.async().update(robotFrame, IDENTITY_TRANSFORM, snaptime).await()
                    delay(200)
                    val transformTime = robotFrame.async().computeTransform(robotFrameInPast).await()
                    angle += transformTime.transform.rotation.toApacheRotation().angle
                    snaptime = transformTime.time
                }
            }
            while (angle < 2 * Math.PI && count < 20) {
                try {
                    Log.d(TAG, "Try rotation (angle: $angle, count: $count)")
                    val rotationAngle = Math.max(2 * Math.PI - angle, 0.0)
                    val time = rotationAngle * 6
                    val animationString = String.format(Locale.US, "[\"Holonomic\", [\"Line\", [0.0, 0.0]], %f, %f]", rotationAngle, time)
                    val animation = AnimationBuilder.with(qiContext).withTexts(animationString).buildAsync().await()
                    val animate = AnimateBuilder.with(qiContext).withAnimation(animation).buildAsync().await()
                    animate.async().run().await()
                    count += 1
                } catch (e: Throwable) {
                    count += 1
                }
            }
            monitoring.cancelAndJoin()
        }

        fun addOnStatusChangedListener(listener: OnStatusChangedListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName(ACTION_NAME)) {
            listeners.add(listener)
            Unit
        }

        fun removeOnStatusChangedListener(listener: OnStatusChangedListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName(ACTION_NAME))  {
            listeners.remove(listener)
            Unit
        }

        fun removeAllOnStatusChangedListeners(): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName(ACTION_NAME))  {
            listeners.clear()
        }


        fun addOnArucoMarkerExploredListener(listener: ExploreArucoMarkerPath.OnArucoMarkerExploredListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|addOnArucoMarkerExploredListener")) {
            exploreArucoMarkerPath.async().addOnArucoMarkerExploredListener(listener).await()
            Unit
        }

        fun removeOnArucoMarkerExploredListener(listener: ExploreArucoMarkerPath.OnArucoMarkerExploredListener): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|removeOnArucoMarkerExploredListener"))  {
            exploreArucoMarkerPath.async().removeOnArucoMarkerExploredListener(listener).await()
            Unit
        }

        fun removeAllOnArucoMarkerExploredListeners(): Future<Unit>
                = SingleThread.GlobalScope.asyncFuture(CoroutineName("$ACTION_NAME|removeAllOnArucoMarkerExploredListeners"))  {
            exploreArucoMarkerPath.async().removeAllOnArucoMarkerExploredListeners().await()
        }
    }
}
