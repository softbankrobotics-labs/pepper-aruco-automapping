package com.softbankrobotics.peppermapping.actuation

class FailedToGoToMarkerException(public val markerId: Int): Exception()
class InternalErrorException(): Exception()

class LocalizeAndMapException(msg: String): Exception(msg)
class LocalizeException(msg: String): Exception(msg)
class EndpointsNotReachedException(markerFound: Set<Int>): Exception()
