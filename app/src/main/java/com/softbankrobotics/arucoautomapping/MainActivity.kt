package com.softbankrobotics.arucoautomapping

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.`object`.actuation.Animate
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import com.aldebaran.qi.sdk.`object`.holder.AutonomousAbilitiesType
import com.aldebaran.qi.sdk.`object`.power.FlapSensor
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import com.aldebaran.qi.sdk.design.activity.conversationstatus.SpeechBarDisplayStrategy
import com.aldebaran.qi.sdk.localization.Localization.async
import com.softbankrobotics.dx.pepperextras.actuation.StubbornGoToBuilder
import com.softbankrobotics.dx.pepperextras.ui.ExplorationMapView
import com.softbankrobotics.dx.pepperextras.util.SingleThread
import com.softbankrobotics.dx.pepperextras.util.TAG
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.ArucoDictionary
import com.softbankrobotics.pepperaruco.actuation.ArucoMarker
import com.softbankrobotics.pepperaruco.util.OpenCVUtils
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerInfo
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerPath
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerPathBuilder
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerType
import com.softbankrobotics.peppermapping.actuation.EndpointsNotReachedException
import com.softbankrobotics.peppermapping.actuation.ExploreArucoMarkerPath
import com.softbankrobotics.peppermapping.actuation.ExploreArucoMarkerPathAndMap
import com.softbankrobotics.peppermapping.actuation.ExploreArucoMarkerPathAndMapBuilder
import com.softbankrobotics.peppermapping.actuation.FailedToGoToMarkerException
import com.softbankrobotics.peppermapping.actuation.LocalizeAndMapException
import com.softbankrobotics.peppermapping.actuation.LocalizeException
import com.softbankrobotics.peppermapping.util.readFromInternalStorage
import com.softbankrobotics.peppermapping.util.readStreamableBufferFromInternalStorage
import com.softbankrobotics.peppermapping.util.writeToInternalStorage
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.choose_map_or_patrol_screen.view.*
import kotlinx.android.synthetic.main.mapping_screen.view.*
import kotlinx.android.synthetic.main.patrol_screen.view.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Animation(val qiContext: QiContext, val resourceId: Int) {

    private lateinit var animate: Animate

    suspend fun load() {
        animate = AnimationBuilder.with(qiContext).withResources(resourceId).buildAsync().await().let {
            AnimateBuilder.with(qiContext).withAnimation(it).buildAsync().await()
        }
    }

    suspend fun run() {
        Log.i(TAG, "Running animation")
        animate.async().run().await()
    }

    suspend fun loop() {
        Log.i(TAG, "Running animation")
        while (true) animate.async().run().await()
    }
}

class MainActivity : RobotActivity(), RobotLifecycleCallbacks {

    private lateinit var qiContext: QiContext

    ///////////////////////////////////
    // Android lifecycle callbacks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.start_splash)
        showingMainActivity = false
        OpenCVUtils.loadOpenCV(this)
        QiSDK.register(this, this)
        setSpeechBarDisplayStrategy(SpeechBarDisplayStrategy.OVERLAY)
    }

    override fun onDestroy() {
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    private val holderBA by lazy { HolderBuilder.with(qiContext)
            .withAutonomousAbilities(AutonomousAbilitiesType.BASIC_AWARENESS).build()}


    // Robot lifecycle callbacks

    private val appScope = SingleThread.newCoroutineScope()

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.i(TAG, "Robot Focus gained")
        this.qiContext = qiContext
        holderBA.hold()
        appScope.asyncFuture {
            flapSensor = qiContext.powerAsync.await().chargingFlap
            runOnUiThread {
                setContentView(R.layout.activity_main)
            }
            showFlapOrMainScreen()
        }
    }

    override fun onRobotFocusLost() {
        holderBA.release()
        Log.i(TAG, "Robot Focus lost")
    }

    override fun onRobotFocusRefused(reason: String) {
        Log.i(TAG, "Robot Focus refused because $reason")
    }

    // GUI code

    private suspend fun showFlapOrMainScreen() {
        flapSensor?.let {
            val flapOpen = it.async().state.await().open
            if (!showingMainActivity) {
                runOnUiThread {
                    setContentView(R.layout.activity_main)
                    showingMainActivity = true
                }
            }
            if (flapOpen) {
                showFlapOpenScreen()
            } else {
                showMainChoiceScreen()
            }
            it.async().addOnStateChangedListener { flapState ->
                if (flapState.open)
                    showFlapOpenScreen()
                else
                    showMainChoiceScreen()
            }
        }
    }

    private suspend fun stopShowFlapOrMainScreen() {
        flapSensor?.async()?.removeAllOnStateChangedListeners()?.await()
    }

    private fun hideAllLayout() {
        patrolLayout.visibility = View.GONE
        mappingLayout.visibility = View.GONE
        loadingMapLayout.visibility = View.GONE
        chooseMapOrPatrolLayout.visibility = View.GONE
        overwriteLayout.visibility = View.GONE
        explanationLayout.visibility = View.GONE
        flapOpenLayout.visibility = View.GONE
    }

    private fun showMainChoiceScreen() {
        val shouldBeEnabled = isMapAvailable()
        runOnUiThread {
            chooseMapOrPatrolLayout.localizeButton.isEnabled = shouldBeEnabled
            chooseMapOrPatrolLayout.localizeButton.alpha = if(shouldBeEnabled) 1.0f else 0.2f
        }
        runOnUiThread {
            hideAllLayout()
            chooseMapOrPatrolLayout.visibility = View.VISIBLE
        }
    }

    private fun showOverwriteScreen() {
        runOnUiThread {
            hideAllLayout()
            overwriteLayout.visibility = View.VISIBLE
        }
    }

    private fun showExplanationScreen() {
        runOnUiThread {
            hideAllLayout()
            explanationLayout.visibility = View.VISIBLE
        }
    }

    private fun showMappingScreen() {
        runOnUiThread {
            hideAllLayout()
            mappingLayout.status.text = ""
            mappingLayout.visibility = View.VISIBLE
        }
    }

    private fun setMappingStatus(status: String) {
        runOnUiThread {
            mappingLayout.status.text = status
        }
    }

    private fun showLoadingMapScreen() {
        runOnUiThread {
            hideAllLayout()
            loadingMapLayout.visibility = View.VISIBLE
        }
    }

    private var showingMainActivity = false

    private fun showFlapOpenScreen() {
        runOnUiThread {
            hideAllLayout()
            flapOpenLayout.visibility = View.VISIBLE
        }
    }

    private suspend fun showPatrolScreen(map: ExplorationMap) {
        runOnUiThread {
            hideAllLayout()
            patrolLayout.visibility = View.VISIBLE
        }
        (patrolLayout.map as ExplorationMapView).setExplorationMap(map.async().topGraphicalRepresentation.await())
    }

    private fun showError(title: String, msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> appScope.asyncFuture { showFlapOrMainScreen() } }
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show()
        }
    }

    // Button callbacks

    fun onNewMapButtonClicked(view: View) {
        if (isMapAvailable()) {
            // Warn the user that we will overwrite his map
            showOverwriteScreen()
        } else {
            // No warning, jump straight to explanation.
            showExplanationScreen()
        }
    }
    fun onLocalizeButtonClicked(view: View) {
        appScope.asyncFuture { onLocalizeButtonClicked() }
    }
    fun onExploreButtonClicked(view: View) {
        appScope.asyncFuture { onExploreButtonClicked() }
    }
    fun onExplanationCancelButtonClicked(view: View) {
        appScope.asyncFuture { onExplanationCancelClicked() }
    }

    fun onConfirmOverwriteButtonClicked(view: View) {
        showExplanationScreen()
    }

    fun onCancelOverwriteButtonClicked(view: View) {
        appScope.asyncFuture {
            showFlapOrMainScreen()
        }
    }

    // Main logic code

    private var flapSensor: FlapSensor? = null

    private val loadingMapAnimation by lazy { Animation(qiContext, R.raw.loading) }
    private var explorationMap: ExplorationMap? = null
    private var arucoMarkerPath: ArucoMarkerPath? = null

    private suspend fun onExplanationCancelClicked() {
        showMainChoiceScreen()
    }

    private suspend fun onExploreButtonClicked() {
        stopShowFlapOrMainScreen()
        showMappingScreen()
        try {

            val (explorationMap, markerPath) = ExploreArucoMarkerPathAndMapBuilder.with(qiContext)
                    .withMarkerDictionary(ArucoDictionary.DICT_4X4_100)
                    .withMarkerLength(arucoMarkerSize)
                    .withMarkerInfoCallback(::getMarkerInfo)
                    .withEndpointMarkers(endpointMarkers)
                    .buildAsync().await()
                    .apply { async().addOnStatusChangedListener(OnExploreAndMapStatusChanged()) }
                    .apply { async().addOnArucoMarkerExploredListener(OnArucoMarkerExplored()) }
                    .async().run().await().getOrThrow()

            setMappingStatus("Saving map to internal storage")
            writeToInternalStorage(explorationMap.async().serializeAsStreamableBuffer().await(), explorationMapFilename)
            writeToInternalStorage(markerPath.serialize(qiContext), arucoMarkerPathFilename)
            this.explorationMap = explorationMap
            this.arucoMarkerPath = markerPath
            try {
                patrol(explorationMap, markerPath)
            } catch (e: LocalizeException) {
                showError("Localize & Patrol error", "Failed to Localize")
                Animation(qiContext, R.raw.localize_fail3).apply { load(); run() }
            }
        } catch (e: FailedToGoToMarkerException) {
            showError("Mapping error","Failed to go to marker ${e.markerId}")
        } catch (e: LocalizeAndMapException) {
            showError("Mapping error","Failed to LocalizeAndMap")
            Animation(qiContext, R.raw.localize_fail3).apply { load(); run() }
        } catch (e: LocalizeException) {
            showError("Mapping error","Failed to Localize")
            Animation(qiContext, R.raw.localize_fail3).apply { load(); run() }
        } catch (e: EndpointsNotReachedException) {
            showError("Mapping error","Failed to reach endpoint markers")
        } catch (e: Throwable) {
            showError("Mapping error", e.toString())
        }
    }

    private suspend fun onLocalizeButtonClicked() {
        stopShowFlapOrMainScreen()
        try {
            val (map, path) = explorationMap?.let { m -> arucoMarkerPath?.let { p -> Pair(m, p) } } ?: loadMap()
            explorationMap = map
            arucoMarkerPath = path
            patrol(map, path)
        } catch (e: Throwable) {
            showError("Localize & Patrol error", e.toString())
        }
    }

    private suspend fun loadMap(): Pair<ExplorationMap, ArucoMarkerPath> = coroutineScope {
        showLoadingMapScreen()

        loadingMapAnimation.load()
        val loadingAnimation = GlobalScope.async { loadingMapAnimation.loop() }
        val explorationMap: ExplorationMap
        val markerPath: ArucoMarkerPath
        try {
            explorationMap = try {
                ExplorationMapBuilder.with(qiContext)
                        .withStreamableBuffer(readStreamableBufferFromInternalStorage(explorationMapFilename))
                        .buildAsync().await()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load exploration map: $e.")
                throw RuntimeException("Failed to load exploration map.")
            }
            markerPath = try {
                ArucoMarkerPathBuilder.with(qiContext)
                        .withPathString(readFromInternalStorage(arucoMarkerPathFilename))
                        .buildAsync().await()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load aruco marker path: $e.")
                throw RuntimeException("Failed to load aruco marker path.")
            }
        } finally {
            loadingAnimation.cancelAndJoin()
        }
        Pair(explorationMap, markerPath)
    }

    private suspend fun monitorRobotPositionInMap() = coroutineScope {
        val robotFrame = qiContext.actuation.async().robotFrame().await()
        val mapFrame = qiContext.mapping.async().mapFrame().await()
        while (isActive) {
            // Compute the position of the robot
            val robotPos = robotFrame.async().computeTransform(mapFrame).await().transform
            (patrolLayout.map as ExplorationMapView).setRobotPosition(robotPos)
            delay(500)
        }
    }

    private suspend fun patrol(explorationMap: ExplorationMap, markerPath: ArucoMarkerPath) = coroutineScope {
        showPatrolScreen(explorationMap)

        val markerIterator = markerPath.iterator()
        // Localize
        val onLocalizedPromised = Promise<Result<Unit>>()
        val localize = LocalizeBuilder.with(qiContext).withMap(explorationMap).buildAsync().await().apply {
            addOnStatusChangedListener { status ->
                if (status == LocalizationStatus.LOCALIZED)
                    onLocalizedPromised.setValue(Result.success(Unit))
            }
        }
        val localizeFuture = localize.async().run().thenConsume {
            if (it.hasError())
                onLocalizedPromised.setValue(Result.failure(LocalizeException(it.errorMessage)))
        }
        // Wait for robot to be localized
        onLocalizedPromised.future.await().getOrThrow()

        // Patrol
        val robotPosMonitoring = launch { monitorRobotPositionInMap() }

        // Localize succes animation
        Animation(qiContext, R.raw.localize_success).apply { load(); run() }

        // Patrol loop
        try {
            val wayPointAnimation = Animation(qiContext, R.raw.waypoint).apply { load() }
            while (true) {
                while (markerIterator.hasNext()) {
                    markerIterator.next().let {
                        Log.i(TAG, "In patrol going though markers ${it.id}")
                        val success = StubbornGoToBuilder.with(qiContext)
                                .withFrame(it.frame)
                                .withWalkingAnimationEnabled(true)
                                .withMaxSpeed(1f)
                                .buildAsync().await().async().run().await()
                        if (success) {
                            wayPointAnimation.run()
                        } else {
                            Log.e(TAG, "I could not reach aruco ${it.id}")
                        }
                    }
                }
                while (markerIterator.hasPrevious()) {
                    markerIterator.previous().let {
                        Log.i(TAG, "In patrol going though markers ${it.id}")
                        val success = StubbornGoToBuilder.with(qiContext)
                                .withFrame(it.frame)
                                .withWalkingAnimationEnabled(true)
                                .withMaxSpeed(1f)
                                .buildAsync().await().async().run().await()
                        if (success) {
                            wayPointAnimation.run()
                        } else {
                            Log.e(TAG, "I could not reach aruco ${it.id}")
                        }
                    }
                }
            }
        } finally {
            robotPosMonitoring.cancelAndJoin()
            localizeFuture.requestCancellation()
            localizeFuture.await()
        }
    }

    inner class OnExploreAndMapStatusChanged: ExploreArucoMarkerPathAndMap.OnStatusChangedListener {
        override fun onStatusChanged(status: ExploreArucoMarkerPathAndMap.Status) {
            Log.i(TAG, "Status changed to $status")
            when (status) {
                ExploreArucoMarkerPathAndMap.Status.NOT_STARTED ->
                    setMappingStatus("Not started")
                ExploreArucoMarkerPathAndMap.Status.LOCALIZING_AND_MAPPING ->
                    setMappingStatus("Running LocalizeAndMap")
                ExploreArucoMarkerPathAndMap.Status.EXPLORING_PATH ->
                    setMappingStatus("Exploring Aruco markers path")
                ExploreArucoMarkerPathAndMap.Status.RETRIEVING_MAP ->
                    setMappingStatus("Retrieving created map")
                ExploreArucoMarkerPathAndMap.Status.LOCALIZING ->
                    setMappingStatus("Running Localize")
                ExploreArucoMarkerPathAndMap.Status.REVISITING_PATH ->
                    setMappingStatus("Revisiting all Aruco markers")
            }
        }
    }

    inner class OnArucoMarkerExplored: ExploreArucoMarkerPath.OnArucoMarkerExploredListener {
        override fun onArucoMarkerExplored(marker: ArucoMarker) {
            setMappingStatus("Exploring Aruco markers path. Explored marker ${marker.id}")
        }
    }

    private val arucoMarkerSize = 0.15
    private val explorationMapFilename = "exploration.map"
    private val arucoMarkerPathFilename = "markers.map"
    private val endpointMarkers = setOf<Int>()

    private fun getMarkerInfo(markerId: Int): ArucoMarkerInfo {
        val angle = 0.0
        return when (markerId) {
            1 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 45.0)
            5 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 45.0)
            19 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 45.0)
            24 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 45.0)
            38 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 135.0)
            25 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 45.0)
            35 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 45.0)
            39 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 45.0)
            48 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 45.0)
            0 -> ArucoMarkerInfo(ArucoMarkerType.PATH, -45.0)
            2 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 225.0)
            11 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 225.0)
            20 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 135.0)
            32 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 135.0)
            42 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 135.0)
            44 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 135.0)
            46 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 225.0)
            3 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 270.0)
            10 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 90.0)
            13 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 270.0)
            34 -> ArucoMarkerInfo(ArucoMarkerType.ENDPOINT, 270.0)
            37 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            7 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            15 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 270.0)
            18 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 90.0)
            21 -> ArucoMarkerInfo(ArucoMarkerType.INTERSECTION, 0.0)
            16 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            22 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 270.0)
            26 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 270.0)
            27 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 270.0)
            28 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            30 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            41 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 90.0)
            43 -> ArucoMarkerInfo(ArucoMarkerType.PATH, 270.0)
            else -> ArucoMarkerInfo(ArucoMarkerType.INVALID, angle)
        }
    }

    private fun isMapAvailable(): Boolean {
        return getFileStreamPath(explorationMapFilename).exists()
                && getFileStreamPath(arucoMarkerPathFilename).exists()
    }
}
