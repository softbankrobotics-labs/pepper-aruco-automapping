package com.softbankrobotics.peppermapping

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.softbankrobotics.dx.util.QiSDKTestActivity
import com.softbankrobotics.dx.util.SingleThread
import com.softbankrobotics.dx.util.TAG
import com.softbankrobotics.dx.util.await
import com.softbankrobotics.dx.util.awaitOrNull
import com.softbankrobotics.dx.util.withRobotFocus
import com.softbankrobotics.pepperaruco.actuation.ArucoMarkerBuilder
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerPath
import com.softbankrobotics.peppermapping.actuation.ArucoMarkerPathBuilder
import junit.framework.Assert
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class ArucoMarkerTest {

    @get:Rule
    val activityRule = ActivityTestRule(QiSDKTestActivity::class.java)

    suspend fun localizeAndRunTest(qiContext: QiContext, block: suspend () -> Unit) {
        var f: Future<Void> = Future.cancelled()
        f = LocalizeAndMapBuilder.with(qiContext).buildAsync().await()
                .apply {
                    addOnStatusChangedListener { status ->
                        SingleThread.GlobalScope.launch {
                            Log.i(TAG, "Status changed $status")
                            if (status ==  LocalizationStatus.LOCALIZED) {
                                Log.i(TAG, "Robot is localized")
                                block()
                                f.requestCancellation()
                            }
                        }
                    }
                }
                .async().run()
        f.awaitOrNull()
    }


    @Test
    fun testSerializeDeserialize() = withRobotFocus(activityRule.activity) { qiContext ->

        runBlocking {
            localizeAndRunTest(qiContext) {

                val marker1Str = "{\"detectionConfig\" : {\"cameraMatrix\" : [{ \"resolution\": { " +
                        "\"width\": 640, \"height\": 480 }, \"matrix\": [606.95912, 0.0, 328.703096," +
                        " 0.0, 606.95912, 244.2494995, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 1280, \"height\": 960 }, \"matrix\": [1213.91824, 0.0, " +
                        "657.406192, 0.0, 1213.91824, 488.498999, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 320, \"height\": 240 }, \"matrix\": [303.47956, 0.0, " +
                        "164.351548, 0.0, 303.47956, 122.12474975, 0.0, 0.0, 1.0] }], " +
                        "\"dictionary\" : \"DICT_4X4_100\", \"distortionCoefs\" : [-8.55109544, " +
                        "-133.329352, -0.00185792215, 0.00406427067, 1196.36779, -8.73892483, " +
                        "-129.521262, 1176.98249], \"localizationPolicy\" : \"ATTACHED_TO_MAPFRAME\", " +
                        "\"markerLength\" : 0.15 }, \"frame\" : { \"translation\": { \"x\": 42.0, " +
                        "\"y\": 2.0, \"z\": 3.0 }, \"rotation\": { \"w\": 1.0, \"x\": 1.0, " +
                        "\"y\": 0.0, \"z\": 0.0 } }, \"id\" : 1 }"
                val marker1 = ArucoMarkerBuilder.with(qiContext).withMarkerString(marker1Str).build()

                val marker2Str = "{\"detectionConfig\" : {\"cameraMatrix\" : [{ \"resolution\": { " +
                        "\"width\": 640, \"height\": 480 }, \"matrix\": [606.95912, 0.0, 328.703096," +
                        " 0.0, 606.95912, 244.2494995, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 1280, \"height\": 960 }, \"matrix\": [1213.91824, 0.0, " +
                        "657.406192, 0.0, 1213.91824, 488.498999, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 320, \"height\": 240 }, \"matrix\": [303.47956, 0.0, " +
                        "164.351548, 0.0, 303.47956, 122.12474975, 0.0, 0.0, 1.0] }], " +
                        "\"dictionary\" : \"DICT_4X4_100\", \"distortionCoefs\" : [-8.55109544, " +
                        "-133.329352, -0.00185792215, 0.00406427067, 1196.36779, -8.73892483, " +
                        "-129.521262, 1176.98249], \"localizationPolicy\" : \"ATTACHED_TO_MAPFRAME\", " +
                        "\"markerLength\" : 0.15 }, \"frame\" : { \"translation\": { \"x\": 42.0, " +
                        "\"y\": 2.0, \"z\": 3.0 }, \"rotation\": { \"w\": 1.0, \"x\": 1.0, " +
                        "\"y\": 0.0, \"z\": 0.0 } }, \"id\" : 2 }"
                val marker2 = ArucoMarkerBuilder.with(qiContext).withMarkerString(marker2Str).build()

                val marker3Str = "{\"detectionConfig\" : {\"cameraMatrix\" : [{ \"resolution\": { " +
                        "\"width\": 640, \"height\": 480 }, \"matrix\": [606.95912, 0.0, 328.703096," +
                        " 0.0, 606.95912, 244.2494995, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 1280, \"height\": 960 }, \"matrix\": [1213.91824, 0.0, " +
                        "657.406192, 0.0, 1213.91824, 488.498999, 0.0, 0.0, 1.0] }, { \"resolution\": " +
                        "{ \"width\": 320, \"height\": 240 }, \"matrix\": [303.47956, 0.0, " +
                        "164.351548, 0.0, 303.47956, 122.12474975, 0.0, 0.0, 1.0] }], " +
                        "\"dictionary\" : \"DICT_4X4_100\", \"distortionCoefs\" : [-8.55109544, " +
                        "-133.329352, -0.00185792215, 0.00406427067, 1196.36779, -8.73892483, " +
                        "-129.521262, 1176.98249], \"localizationPolicy\" : \"ATTACHED_TO_MAPFRAME\", " +
                        "\"markerLength\" : 0.15 }, \"frame\" : { \"translation\": { \"x\": 42.0, " +
                        "\"y\": 2.0, \"z\": 3.0 }, \"rotation\": { \"w\": 1.0, \"x\": 1.0, " +
                        "\"y\": 0.0, \"z\": 0.0 } }, \"id\" : 3 }"
                val marker3 = ArucoMarkerBuilder.with(qiContext).withMarkerString(marker3Str).build()

                val path = ArucoMarkerPath()
                path.addHome(marker1)
                path.addPath(marker1, marker2)
                path.addPath(marker2, marker3)


                val asString = path.serialize(qiContext)
                val reconstructed = ArucoMarkerPathBuilder.with(qiContext)
                        .withPathString(asString).buildAsync().await()
                Assert.assertEquals(asString.toSortedSet(), reconstructed.serialize(qiContext).toSortedSet())
            }
        }
    }
}