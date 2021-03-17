package com.softbankrobotics.peppermapping.actuation

import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import com.softbankrobotics.dx.pepperextras.geometry.toQiQuaternion
import com.softbankrobotics.dx.pepperextras.util.await
import org.apache.commons.math3.geometry.euclidean.threed.Rotation
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D


data class ArucoMarkerInfo(
        val type: ArucoMarkerType,

        // By default, the Z axis of the markers are used to indicate the aruco direction:
        //   - when Pepper goes on top of a marker, it align with the Z-axis
        //   - when Pepper search for next markers after a PATH marker, it goes in the direction
        //     of the current marker Z-axis
        // To change this behavior, change the 'directionRotationAngle' angle (in degree), try values
        // like 45.0, -45.0, 90.0, ...
        val directionRotationAngle: Double = 0.0
)

internal suspend fun applyRotationToZAxis(markerFrame: Frame, rotation: Double): Frame {
    val r = Rotation(Vector3D(1.0, 0.0, 0.0), rotation, RotationConvention.FRAME_TRANSFORM)
    val t = Transform(r.toQiQuaternion(), Vector3(0.0, 0.0, 0.0))
    return markerFrame.makeAttachedFrame(t).async().frame().await()
}