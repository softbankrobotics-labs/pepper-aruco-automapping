package com.softbankrobotics.peppermapping.actuation

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.util.FutureUtils

class TraverseArucoMarkerPathBuilder private constructor(val qiContext: QiContext) {

    private lateinit var markerPath: ArucoMarkerPath

    fun withArucoMarkerPath(markerPath: ArucoMarkerPath): TraverseArucoMarkerPathBuilder {
        this.markerPath = markerPath
        return this
    }

    companion object {
        fun with(qiContext: QiContext): TraverseArucoMarkerPathBuilder {
            return TraverseArucoMarkerPathBuilder(qiContext)
        }
    }

    fun build(): TraverseArucoMarkerPath {
        return FutureUtils.get(buildAsync())
    }

    fun buildAsync(): Future<TraverseArucoMarkerPath> {
        check(::markerPath.isInitialized) { "ArucoMarkerPath required." }
        return Future.of(TraverseArucoMarkerPath(qiContext, markerPath))
    }
}
