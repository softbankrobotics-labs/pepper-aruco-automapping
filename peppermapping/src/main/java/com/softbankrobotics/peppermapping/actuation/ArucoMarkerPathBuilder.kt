package com.softbankrobotics.peppermapping.actuation

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.util.FutureUtils
import com.beust.klaxon.Klaxon


class ArucoMarkerPathBuilder private constructor(val qiContext: QiContext) {

    private lateinit var pathString: String

    fun withPathString(str: String): ArucoMarkerPathBuilder {
        this.pathString = str
        return this
    }

    companion object {
        fun with(qiContext: QiContext): ArucoMarkerPathBuilder {
            return ArucoMarkerPathBuilder(qiContext)
        }
    }

    fun build(): ArucoMarkerPath {
        return FutureUtils.get(buildAsync())
    }

    fun buildAsync(): Future<ArucoMarkerPath> {
        check(::pathString.isInitialized) { "markerString is required." }
        val serializer = Klaxon().converter(ArucoMarkerPath.KlaxonConverter(qiContext))
        return Future.of(serializer.parse<ArucoMarkerPath>(pathString))
    }
}
