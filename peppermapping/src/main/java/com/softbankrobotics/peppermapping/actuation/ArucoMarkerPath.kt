package com.softbankrobotics.peppermapping.actuation

import android.util.Log
import android.util.SparseArray
import androidx.core.util.containsKey
import androidx.core.util.set
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.beust.klaxon.Converter
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.softbankrobotics.dx.pepperextras.actuation.distance
import com.softbankrobotics.dx.pepperextras.util.SingleThread
import com.softbankrobotics.dx.pepperextras.util.TAG
import com.softbankrobotics.dx.pepperextras.util.asyncFuture
import com.softbankrobotics.dx.pepperextras.util.await
import com.softbankrobotics.pepperaruco.actuation.ArucoDictionary
import com.softbankrobotics.pepperaruco.actuation.ArucoMarker
import com.softbankrobotics.pepperaruco.actuation.ArucoMarkerBuilder
import com.softbankrobotics.pepperaruco.actuation.ArucoMarkerFrameLocalizationPolicy
import java.util.*
import kotlin.collections.ArrayList

public class ArucoMarkerPath internal constructor(
        private val markersMap: MutableMap<Int, ArucoMarker> = mutableMapOf(),
        private val adjacencyMap: MutableMap<Int, MutableSet<Int>> = mutableMapOf(),
        private var homeId: Int = -1
) {
    init {
        if ((homeId != -1) && !markersMap.containsKey(homeId))
            throw RuntimeException("Invalid homeId $homeId is not in markerMap")
        adjacencyMap.forEach {
            if (!markersMap.containsKey(it.key))
                throw RuntimeException("Invalid id ${it.key} is in adjacencyMap and not in markerMap")
            it.value.forEach {
                if (!markersMap.containsKey(it))
                    throw RuntimeException("Invalid id ${it} is in adjacencyMap and not in markerMap")
            }
        }
    }

    public val markers: Set<ArucoMarker>
        get() {
            return markersMap.values.toSet()
        }

    public val markerIds: Set<Int>
        get() {
            return markersMap.keys.toSet()
        }

    public val home: ArucoMarker?
        get() {
            return markersMap.get(homeId)
        }

    public fun getPath(from: Frame, toArucoMarkerId: Int): Future<List<ArucoMarker>> = SingleThread.GlobalScope.asyncFuture {
        getClosestMarker(from)?.let {
            getPath(it.id, toArucoMarkerId).await()
        } ?: markersMap.get(toArucoMarkerId)?.let { listOf(it) } ?: listOf()
    }

    public fun getPath(fromArucoMarkerId: Int, toArucoMarkerId: Int): Future<List<ArucoMarker>> = SingleThread.GlobalScope.asyncFuture {
        // Breadth-first graph traversal
        if (fromArucoMarkerId == toArucoMarkerId)
            return@asyncFuture markersMap.get(toArucoMarkerId)?.let { listOf(it) } ?: listOf()

        val pathTo = SparseArray<ArrayList<ArucoMarker>>()
        val edge = arrayListOf(fromArucoMarkerId)
        pathTo[fromArucoMarkerId] = ArrayList()
        while (edge.isNotEmpty()) {
            val newEdge = ArrayList<Int>()
            edge.forEach { edgeId ->
                adjacencyMap[edgeId]?.forEach { id ->
                    if (!pathTo.containsKey(id)) {
                        // Ah, a point we haven't seen yet!
                        pathTo[id] = ArrayList(pathTo[edgeId])
                        markersMap[id]?.let { pathTo[id].add(it) }
                        if (id == toArucoMarkerId) {
                            return@asyncFuture pathTo[id]
                        } else {
                            // It's not the chosen one, visit it next time
                            newEdge.add(id)
                        }
                    }
                }
            }
            edge.clear()
            edge.addAll(newEdge)
        }
        markersMap.get(toArucoMarkerId)?.let { listOf(it) } ?: listOf()
    }

    public fun iterator(): ListIterator<ArucoMarker> {
        return depthFirstTraversal().listIterator()
    }

    public fun serialize(qiContext: QiContext): String {
        markers.forEach {
            if (it.detectionConfig.localizationPolicy !=
                    ArucoMarkerFrameLocalizationPolicy.ATTACHED_TO_MAPFRAME)
                throw RuntimeException("Marker serialization with localizationPolicy " +
                        "${it.detectionConfig.localizationPolicy} is not supported.")
        }
        val serializer = Klaxon().converter(KlaxonConverter(qiContext))
        return serializer.toJsonString(this)
    }

    internal fun addPath(marker1: ArucoMarker, marker2: ArucoMarker) {
        // Always refresh the Aruco Marker using the latest observation.
        registerOrUpdateMarkerData(marker1)
        registerOrUpdateMarkerData(marker2)
        // Then store paths
        adjacencyMap.getOrPut(marker1.id, { mutableSetOf() }).add(marker2.id)
        adjacencyMap.getOrPut(marker2.id, { mutableSetOf() }).add(marker1.id)
    }

    internal fun registerOrUpdateMarkerData(marker: ArucoMarker) {
        markersMap.put(marker.id, marker)
    }

    internal fun addHome(marker: ArucoMarker) {
        registerOrUpdateMarkerData(marker)
        homeId = marker.id
    }

    private suspend fun getClosestMarker(frame: Frame): ArucoMarker? {
        return markers.minBy {
            frame.async().distance(it.frame).await()
        }
    }

    private fun depthFirstTraversal(): List<ArucoMarker> {
        if (adjacencyMap.isEmpty()) return listOf()
        // Mark all the vertices / nodes as not visited.
        val visitedMap = mutableMapOf<Int, Boolean>().apply {
            adjacencyMap.keys.forEach { id -> put(id, false) }
        }
        // Create a stack for DFS. Both ArrayDeque and LinkedList implement Deque.
        val stack: Deque<Int> = LinkedList()
        // Initial step -> add the startNode to the stack.
        stack.push(homeId)
        // Store the sequence in which nodes are visited, for return value.
        val traversalList = mutableListOf<ArucoMarker>()
        // Traverse the graph.
        while (stack.isNotEmpty()) {
            // Pop the node off the top of the stack.
            val currentNode = stack.pop()
            if (!visitedMap[currentNode]!!) {
                // Store this for the result.
                traversalList.add(markersMap.get(currentNode)!!)
                // Mark the current node visited and add to the traversal list.
                visitedMap[currentNode] = true
                // Add nodes in the adjacency map.
                adjacencyMap[currentNode]?.forEach { node ->
                    stack.push(node)
                }
            }
        }
        return traversalList
    }

    internal class KlaxonConverter(val qiContext: QiContext) : Converter {

        override fun canConvert(cls: Class<*>): Boolean {
            return cls == ArucoMarkerPath::class.java
        }

        override fun toJson(value: Any): String {
            return when (value) {
                is ArucoMarkerPath -> {
                    "{\"homeId\" : ${value.homeId}, " +
                            "\"markers\" : [${value.markers.map { it.serialize(qiContext)}.joinToString()}], " +
                            "\"adjacencyMap\" : { ${value.adjacencyMap.map { 
                                "\"${it.key}\": [ ${it.value.joinToString()} ]" }.joinToString() } }" +
                            "}"
                }
                else -> ""
            }
        }

        override fun fromJson(jv: JsonValue): Any {
            return when (jv.propertyClass) {
                ArucoMarkerPath::class.java -> {
                    val homeId = jv.objInt("homeId")
                    val markersMap: MutableMap<Int, ArucoMarker> = jv.obj?.array<JsonObject>("markers")?.map  {
                        val m = ArucoMarkerBuilder.with(qiContext)
                                .withMarkerString(it.toJsonString())
                                .build()
                        m.id to m
                    }?.toMap()?.toMutableMap() ?: error("markers")
                    val adjacencyMap: MutableMap<Int, MutableSet<Int>> = (jv.obj?.get("adjacencyMap") as JsonObject)
                            .map.map {
                                it.key.toInt() to (it.value as JsonArray<*>).map { it as Int }.toMutableSet()
                            }.toMap().toMutableMap()
                    ArucoMarkerPath(markersMap, adjacencyMap, homeId)
                }
                else -> Any()
            }
        }

        private inline fun error(field: String): Nothing {
            throw RuntimeException("Deserialization failed, '$field' field is missing")
        }
    }
}