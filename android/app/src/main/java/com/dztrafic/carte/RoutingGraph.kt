package com.dztrafic.carte

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ville, commune ou village de la liste de recherche (kind : 0 ville, 1 ville moyenne, 2 village). */
class RoutePlace(
    val name: String,
    val nameAr: String,
    val nameFr: String,
    val kind: Int,
    val population: Int,
    val latitude: Double,
    val longitude: Double,
) {
    val searchText: String = normalizeForSearch("$name $nameAr $nameFr")

    fun label(arabic: Boolean): String =
        if (arabic) nameAr.ifEmpty { name.ifEmpty { nameFr } } else nameFr.ifEmpty { name.ifEmpty { nameAr } }
}

class RouteResult(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val distanceMeters: Double,
    val seconds: Double,
)

/** Cherche dans les noms (arabe ou français, sans accents) ; les lieux sont déjà triés par importance. */
fun searchPlaces(places: List<RoutePlace>, query: String, limit: Int): List<RoutePlace> {
    val wanted = normalizeForSearch(query).trim()
    if (wanted.isEmpty()) return places.take(limit)
    val starting = ArrayList<RoutePlace>()
    val containing = ArrayList<RoutePlace>()
    for (place in places) {
        val index = place.searchText.indexOf(wanted)
        if (index < 0) continue
        if (index == 0 || place.searchText[index - 1] == ' ') starting.add(place) else containing.add(place)
        if (starting.size >= limit) break
    }
    return (starting + containing).take(limit)
}

private class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    operator fun get(index: Int) = data[index]

    fun clear() {
        size = 0
    }
}

private class LongHeap {
    private var data = LongArray(1 shl 16)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun push(value: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        var index = size++
        while (index > 0) {
            val parent = (index - 1) shr 1
            if (data[parent] <= value) break
            data[index] = data[parent]
            index = parent
        }
        data[index] = value
    }

    fun pop(): Long {
        val top = data[0]
        val last = data[--size]
        if (size > 0) {
            var index = 0
            while (true) {
                var child = 2 * index + 1
                if (child >= size) break
                if (child + 1 < size && data[child + 1] < data[child]) child++
                if (data[child] >= last) break
                data[index] = data[child]
                index = child
            }
            data[index] = last
        }
        return top
    }
}

/**
 * Réseau routier de l'Algérie lu directement dans le fichier (mappé en mémoire, rien n'est copié dans le tas).
 * Format : voir tools/build_routing_graph.py.
 */
class RoutingGraph(file: File) : AutoCloseable {

    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel

    val nodeCount: Int
    private val placesOffset: Int
    private val placesSize: Int
    private val lat: IntBuffer
    private val lon: IntBuffer
    private val flags: MappedByteBuffer
    private val arcStart: IntBuffer
    private val arcs: IntBuffer
    private val edgeFrom: IntBuffer
    private val edgeTo: IntBuffer
    private val edgeLength: IntBuffer
    private val edgeAttr: ShortBuffer
    private val edgeGeometry: IntBuffer
    private val geometry: MappedByteBuffer

    private var gScore = FloatArray(0)
    private var parentCode = IntArray(0)
    private var gStamp = IntArray(0)
    private var closedStamp = IntArray(0)
    private var stamp = 0
    private val heap = LongHeap()
    private var cursor = 0

    init {
        val header = map(0, HEADER_SIZE)
        val magic = byteArrayOf(header.get(0), header.get(1), header.get(2), header.get(3))
        if (!magic.contentEquals("TRK1".toByteArray(Charsets.US_ASCII)) || header.getInt(4) != 1) {
            channel.close()
            raf.close()
            throw IOException("fichier d'itinéraires invalide")
        }
        nodeCount = header.getInt(8)
        val edgeCount = header.getInt(12)
        val arcCount = header.getInt(16)
        placesOffset = header.getInt(24)
        placesSize = header.getInt(28)
        lat = map(header.getInt(32), nodeCount * 4).asIntBuffer()
        lon = map(header.getInt(36), nodeCount * 4).asIntBuffer()
        flags = map(header.getInt(40), nodeCount)
        arcStart = map(header.getInt(44), (nodeCount + 1) * 4).asIntBuffer()
        arcs = map(header.getInt(48), arcCount * 4).asIntBuffer()
        edgeFrom = map(header.getInt(52), edgeCount * 4).asIntBuffer()
        edgeTo = map(header.getInt(56), edgeCount * 4).asIntBuffer()
        edgeLength = map(header.getInt(60), edgeCount * 4).asIntBuffer()
        edgeAttr = map(header.getInt(64), edgeCount * 2).asShortBuffer()
        edgeGeometry = map(header.getInt(68), edgeCount * 4).asIntBuffer()
        geometry = map(header.getInt(72), header.getInt(76))
    }

    private fun map(offset: Int, size: Int): MappedByteBuffer {
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, offset.toLong(), size.toLong())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return buffer
    }

    override fun close() {
        channel.close()
        raf.close()
    }

    fun loadPlaces(): List<RoutePlace> {
        val buffer = map(placesOffset, placesSize)
        val result = ArrayList<RoutePlace>()
        var position = 0
        while (position < placesSize) {
            val latitude = buffer.getInt(position)
            val longitude = buffer.getInt(position + 4)
            val population = buffer.getInt(position + 8)
            val kind = buffer.get(position + 12).toInt() and 0xFF
            position += 13
            val texts = Array(3) { "" }
            for (i in 0 until 3) {
                val length = buffer.get(position).toInt() and 0xFF
                val bytes = ByteArray(length) { buffer.get(position + 1 + it) }
                texts[i] = String(bytes, Charsets.UTF_8)
                position += 1 + length
            }
            result.add(RoutePlace(texts[0], texts[1], texts[2], kind, population, latitude / 1e6, longitude / 1e6))
        }
        return result
    }

    private fun metres(latE6: Int, lonE6: Int, refLatE6: Int, refLonE6: Int, cosRef: Double): Double {
        val dy = (latE6 - refLatE6) * 0.110574
        val dx = (lonE6 - refLonE6) * 0.111320 * cosRef
        return sqrt(dx * dx + dy * dy)
    }

    /** Nœud le plus proche appartenant au grand réseau connecté, ou -1 si aucun dans le rayon. */
    private fun nearestNode(latitude: Double, longitude: Double, maxMetres: Double): Int {
        val latE6 = (latitude * 1e6).roundToInt()
        val lonE6 = (longitude * 1e6).roundToInt()
        val cosRef = cos(Math.toRadians(latitude))
        val maxLat = (maxMetres / 0.110574).toInt()
        val maxLon = (maxMetres / (0.111320 * cosRef)).toInt()
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for (node in 0 until nodeCount) {
            val nodeLat = lat.get(node)
            if (abs(nodeLat - latE6) > maxLat) continue
            val nodeLon = lon.get(node)
            if (abs(nodeLon - lonE6) > maxLon) continue
            if (flags.get(node).toInt() == 0) continue
            val distance = metres(nodeLat, nodeLon, latE6, lonE6, cosRef)
            if (distance < bestDistance) {
                best = node
                bestDistance = distance
            }
        }
        return if (bestDistance <= maxMetres) best else -1
    }

    private fun ensureWorkArrays() {
        if (gScore.size == nodeCount) return
        gScore = FloatArray(nodeCount)
        parentCode = IntArray(nodeCount)
        gStamp = IntArray(nodeCount)
        closedStamp = IntArray(nodeCount)
        stamp = 0
    }

    /**
     * Itinéraire le plus rapide entre deux positions. Les petites rues ne sont utilisées que près du départ
     * et de l'arrivée ; si rien n'est trouvé, le rayon est élargi puis supprimé.
     * Appel bloquant : à lancer hors du thread principal.
     */
    @Synchronized
    fun route(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        isCancelled: () -> Boolean,
    ): RouteResult? {
        val start = nearestNode(startLat, startLon, START_SNAP_METRES)
        val goal = nearestNode(endLat, endLon, END_SNAP_METRES)
        if (start < 0 || goal < 0 || start == goal) return null
        ensureWorkArrays()
        for (factor in RADIUS_FACTORS) {
            if (search(start, goal, factor, isCancelled)) return buildResult(start, goal)
            if (isCancelled()) return null
        }
        return null
    }

    private fun search(start: Int, goal: Int, radiusFactor: Float, isCancelled: () -> Boolean): Boolean {
        stamp++
        val startLat = lat.get(start)
        val startLon = lon.get(start)
        val goalLat = lat.get(goal)
        val goalLon = lon.get(goal)
        val cosStart = cos(Math.toRadians(startLat / 1e6))
        val cosGoal = cos(Math.toRadians(goalLat / 1e6))

        fun priority(cost: Float, node: Int): Long {
            val remaining = metres(lat.get(node), lon.get(node), goalLat, goalLon, cosGoal) / MAX_SPEED_MPS
            val total = cost + remaining.toFloat()
            return (java.lang.Float.floatToRawIntBits(total).toLong() shl 32) or node.toLong()
        }

        heap.clear()
        gScore[start] = 0f
        gStamp[start] = stamp
        parentCode[start] = -1
        heap.push(priority(0f, start))
        var expansions = 0
        while (heap.size > 0) {
            val node = (heap.pop() and 0xFFFFFFFFL).toInt()
            if (closedStamp[node] == stamp) continue
            closedStamp[node] = stamp
            if (node == goal) return true
            expansions++
            if ((expansions and 0x3FFF) == 0 && isCancelled()) return false
            val cost = gScore[node]
            for (a in arcStart.get(node) until arcStart.get(node + 1)) {
                val code = arcs.get(a)
                val edge = code ushr 1
                val target = if ((code and 1) == 0) edgeTo.get(edge) else edgeFrom.get(edge)
                if (closedStamp[target] == stamp) continue
                val attr = edgeAttr.get(edge).toInt() and 0xFFFF
                val roadClass = attr and 7
                if (roadClass > 2) {
                    val limit = CLASS_RADIUS_METRES[roadClass] * radiusFactor
                    if (limit < Float.MAX_VALUE) {
                        val targetLat = lat.get(target)
                        val targetLon = lon.get(target)
                        if (metres(targetLat, targetLon, startLat, startLon, cosStart) > limit &&
                            metres(targetLat, targetLon, goalLat, goalLon, cosGoal) > limit
                        ) continue
                    }
                }
                val speed = (attr ushr 3).coerceAtLeast(5)
                val newCost = cost + edgeLength.get(edge) / 10f / (speed / 3.6f)
                if (gStamp[target] != stamp || newCost < gScore[target]) {
                    gStamp[target] = stamp
                    gScore[target] = newCost
                    parentCode[target] = code
                    heap.push(priority(newCost, target))
                }
            }
        }
        return false
    }

    private fun readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = geometry.get(cursor++).toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            if (byte < 0x80) return result
            shift += 7
        }
    }

    /** Points d'un tronçon dans le sens de sa définition : départ, points intermédiaires, arrivée. */
    private fun decodeEdge(edge: Int, outLat: IntList, outLon: IntList) {
        outLat.clear()
        outLon.clear()
        cursor = edgeGeometry.get(edge)
        val count = readVarint()
        var currentLat = lat.get(edgeFrom.get(edge))
        var currentLon = lon.get(edgeFrom.get(edge))
        outLat.add(currentLat)
        outLon.add(currentLon)
        repeat(count) {
            val dLat = readVarint()
            val dLon = readVarint()
            currentLat += (dLat ushr 1) xor -(dLat and 1)
            currentLon += (dLon ushr 1) xor -(dLon and 1)
            outLat.add(currentLat)
            outLon.add(currentLon)
        }
        outLat.add(lat.get(edgeTo.get(edge)))
        outLon.add(lon.get(edgeTo.get(edge)))
    }

    private fun buildResult(start: Int, goal: Int): RouteResult {
        val codes = IntList()
        var node = goal
        while (node != start) {
            val code = parentCode[node]
            codes.add(code)
            val edge = code ushr 1
            node = if ((code and 1) == 0) edgeFrom.get(edge) else edgeTo.get(edge)
        }
        val pathLat = IntList()
        val pathLon = IntList()
        pathLat.add(lat.get(start))
        pathLon.add(lon.get(start))
        val edgeLat = IntList()
        val edgeLon = IntList()
        var decimetres = 0L
        for (i in codes.size - 1 downTo 0) {
            val code = codes[i]
            val edge = code ushr 1
            decodeEdge(edge, edgeLat, edgeLon)
            if ((code and 1) == 0) {
                for (p in 1 until edgeLat.size) {
                    pathLat.add(edgeLat[p])
                    pathLon.add(edgeLon[p])
                }
            } else {
                for (p in edgeLat.size - 2 downTo 0) {
                    pathLat.add(edgeLat[p])
                    pathLon.add(edgeLon[p])
                }
            }
            decimetres += edgeLength.get(edge)
        }
        // On allège le tracé : un point tous les quelques mètres suffit à l'écran.
        val keptLat = IntList()
        val keptLon = IntList()
        val cosMiddle = cos(Math.toRadians(pathLat[pathLat.size / 2] / 1e6))
        for (p in 0 until pathLat.size) {
            val last = p == pathLat.size - 1
            if (keptLat.size == 0 || last ||
                metres(pathLat[p], pathLon[p], keptLat[keptLat.size - 1], keptLon[keptLon.size - 1], cosMiddle) >= MIN_POINT_SPACING_METRES
            ) {
                keptLat.add(pathLat[p])
                keptLon.add(pathLon[p])
            }
        }
        return RouteResult(
            DoubleArray(keptLat.size) { keptLat[it] / 1e6 },
            DoubleArray(keptLon.size) { keptLon[it] / 1e6 },
            decimetres / 10.0,
            gScore[goal].toDouble(),
        )
    }

    private companion object {
        const val HEADER_SIZE = 128
        const val START_SNAP_METRES = 5_000.0
        const val END_SNAP_METRES = 8_000.0
        const val MAX_SPEED_MPS = 33.4
        const val MIN_POINT_SPACING_METRES = 4.0
        val RADIUS_FACTORS = floatArrayOf(1f, 4f, 1000f)

        // Rayon autour du départ ou de l'arrivée dans lequel chaque type de route est utilisable
        // (0 autoroute, 1 nationale, 2 principale, 3 secondaire, 4 tertiaire, 5 non classée, 6 résidentielle).
        val CLASS_RADIUS_METRES = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 150_000f, 40_000f, 20_000f, 15_000f,
        )
    }
}
