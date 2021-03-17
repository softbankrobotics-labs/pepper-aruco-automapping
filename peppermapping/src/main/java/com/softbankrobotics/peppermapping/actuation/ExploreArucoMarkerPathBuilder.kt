package com.softbankrobotics.peppermapping.actuation

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.util.FutureUtils
import com.softbankrobotics.pepperaruco.actuation.ArucoDictionary
import com.softbankrobotics.pepperaruco.actuation.ArucoMarkerFrameLocalizationPolicy
import com.softbankrobotics.pepperaruco.actuation.DetectArucoMarker

class ExploreArucoMarkerPathBuilder private constructor(val qiContext: QiContext) {

    private val config = ExploreArucoMarkerPath.Config()
    private val detectArucoMarkerConfig = DetectArucoMarker.Config().apply {
        localizationPolicy = ArucoMarkerFrameLocalizationPolicy.ATTACHED_TO_MAPFRAME
    }

    fun withMarkerLength(markerLength: Double): ExploreArucoMarkerPathBuilder {
        this.detectArucoMarkerConfig.markerLength = markerLength
        return this
    }

    fun withMarkerDictionary(dictionary: ArucoDictionary): ExploreArucoMarkerPathBuilder {
        this.detectArucoMarkerConfig.dictionary = dictionary
        return this
    }

    fun withMarkerInfoCallback(getArucoMarkerInfoCallback: (Int)-> ArucoMarkerInfo)
            : ExploreArucoMarkerPathBuilder {
        this.config.getArucoMarkerInfoCallback = getArucoMarkerInfoCallback
        return this
    }

    companion object {
        fun with(qiContext: QiContext): ExploreArucoMarkerPathBuilder {
            return ExploreArucoMarkerPathBuilder(qiContext)
        }
    }

    fun build(): ExploreArucoMarkerPath {
        return FutureUtils.get(buildAsync())
    }

    fun buildAsync(): Future<ExploreArucoMarkerPath> {
        return Future.of(ExploreArucoMarkerPath(qiContext, config, detectArucoMarkerConfig))
    }
}
