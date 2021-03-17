package com.softbankrobotics.peppermapping.actuation

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.util.FutureUtils
import com.softbankrobotics.pepperaruco.actuation.ArucoDictionary
import com.softbankrobotics.pepperaruco.actuation.ArucoMarkerFrameLocalizationPolicy
import com.softbankrobotics.pepperaruco.actuation.DetectArucoMarker


class ExploreArucoMarkerPathAndMapBuilder private constructor(val qiContext: QiContext) {

    private val config = ExploreArucoMarkerPathAndMap.Config()
    private val detectArucoMarkerConfig = DetectArucoMarker.Config().apply {
        localizationPolicy = ArucoMarkerFrameLocalizationPolicy.ATTACHED_TO_MAPFRAME
    }

    fun withMarkerLength(markerLength: Double): ExploreArucoMarkerPathAndMapBuilder {
        this.detectArucoMarkerConfig.markerLength = markerLength
        return this
    }

    fun withMarkerDictionary(dictionary: ArucoDictionary): ExploreArucoMarkerPathAndMapBuilder {
        this.detectArucoMarkerConfig.dictionary = dictionary
        return this
    }

    fun withMarkerInfoCallback(getArucoMarkerInfoCallback: (Int)-> ArucoMarkerInfo)
            : ExploreArucoMarkerPathAndMapBuilder {
        this.config.getArucoMarkerInfoCallback = getArucoMarkerInfoCallback
        return this
    }

    fun withEndpointMarkers(endpointMarkers: Set<Int>): ExploreArucoMarkerPathAndMapBuilder {
        this.config.endpointMarkers = endpointMarkers
        return this
    }

    companion object {
        fun with(qiContext: QiContext): ExploreArucoMarkerPathAndMapBuilder {
            return ExploreArucoMarkerPathAndMapBuilder(qiContext)
        }
    }

    fun build(): ExploreArucoMarkerPathAndMap {
        return FutureUtils.get(buildAsync())
    }

    fun buildAsync(): Future<ExploreArucoMarkerPathAndMap> {
        return Future.of(ExploreArucoMarkerPathAndMap(qiContext, config, detectArucoMarkerConfig))
    }
}
