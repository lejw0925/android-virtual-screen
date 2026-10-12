package io.github.lejw0925.virtualscreen.backend

import org.json.JSONArray
import org.json.JSONObject

/** A single pointer follows these vertices without lifting between segments. */
internal object SwipePath {
    const val MAX_POINTS = 64
    private val ENDPOINT_KEYS = listOf("x1", "y1", "x2", "y2")

    data class Point(val x: Int, val y: Int)

    fun parse(arguments: JSONObject): List<Point> {
        if (arguments.has("path")) {
            require(ENDPOINT_KEYS.none(arguments::has)) { "path 与 x1/y1/x2/y2 不能同时填写" }
            return parsePath(arguments.opt("path"))
        }
        return listOf(
            Point(integer(arguments, "x1"), integer(arguments, "y1")),
            Point(integer(arguments, "x2"), integer(arguments, "y2")),
        )
    }

    fun parsePath(value: Any?): List<Point> {
        require(value is JSONArray && value.length() in 2..MAX_POINTS) { "path 必须包含 2–$MAX_POINTS 个坐标点" }
        return List(value.length()) { index ->
            val point = value.opt(index)
            require(point is JSONObject && point.length() == 2 && point.has("x") && point.has("y")) {
                "path[$index] 必须是仅含 x、y 整数的对象"
            }
            Point(integer(point, "x"), integer(point, "y"))
        }
    }

    fun validate(points: List<Point>, width: Int, height: Int) {
        require(points.size in 2..MAX_POINTS) { "path 必须包含 2–$MAX_POINTS 个坐标点" }
        require(width > 0 && height > 0) { "INVALID_SCREEN_DIMENSIONS" }
        points.forEachIndexed { index, point ->
            require(point.x in 0 until width && point.y in 0 until height) {
                "path[$index] 超出屏幕范围：(${point.x},${point.y}) not in ${width}x$height"
            }
        }
    }

    fun toJson(points: List<Point>): JSONArray = JSONArray().apply {
        points.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) }
    }

    /** Bound a sampled viewer path by retaining the points furthest from each chord. */
    fun compact(points: List<Point>, maxPoints: Int = MAX_POINTS): List<Point> {
        require(maxPoints >= 2) { "maxPoints must be at least 2" }
        if (points.size <= maxPoints) return points.toList()
        data class Segment(val start: Int, val end: Int, val vertex: Int, val error: Double)
        fun segment(start: Int, end: Int): Segment {
            val first = points[start]
            val last = points[end]
            val dx = last.x.toDouble() - first.x
            val dy = last.y.toDouble() - first.y
            val lengthSquared = dx * dx + dy * dy
            var vertex = start
            var largest = 0.0
            for (index in start + 1 until end) {
                val px = points[index].x.toDouble() - first.x
                val py = points[index].y.toDouble() - first.y
                val fraction = if (lengthSquared == 0.0) 0.0
                    else ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val error = (px - fraction * dx) * (px - fraction * dx) +
                    (py - fraction * dy) * (py - fraction * dy)
                if (error > largest) {
                    largest = error
                    vertex = index
                }
            }
            return Segment(start, end, vertex, largest)
        }
        // A reversal can lie exactly on the start/end chord, so its error is
        // zero and the usual chord simplification would discard it. Keep
        // direction reversals as anchors before allocating the remaining
        // points by geometric error.
        val reversalAnchors = buildList {
            add(0)
            for (index in 1 until points.lastIndex) {
                val previous = points[index - 1]
                val current = points[index]
                val next = points[index + 1]
                val incomingX = current.x.toDouble() - previous.x
                val incomingY = current.y.toDouble() - previous.y
                val outgoingX = next.x.toDouble() - current.x
                val outgoingY = next.y.toDouble() - current.y
                if (incomingX * outgoingX + incomingY * outgoingY < 0) add(index)
            }
            add(points.lastIndex)
        }
        val anchors = if (reversalAnchors.size <= maxPoints) {
            reversalAnchors
        } else {
            // There is no way to retain every reversal when the input itself
            // exceeds the wire limit. Spread the retained anchors over the
            // path so both ends and the overall direction remain stable.
            buildList {
                for (slot in 0 until maxPoints) {
                    add(reversalAnchors[slot * (reversalAnchors.lastIndex) / (maxPoints - 1)])
                }
            }.distinct()
        }
        val selected = anchors.toMutableList()
        val segments = java.util.PriorityQueue<Segment>(compareByDescending { it.error })
        anchors.zipWithNext { start, end -> segments.add(segment(start, end)) }
        while (selected.size < maxPoints && segments.isNotEmpty()) {
            val next = segments.remove()
            if (next.error <= 0.25) break
            selected.add(next.vertex)
            segments.add(segment(next.start, next.vertex))
            segments.add(segment(next.vertex, next.end))
        }
        return selected.sorted().map(points::get)
    }

    fun integer(arguments: JSONObject, name: String): Int {
        val value = arguments.opt(name)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) {
            "$name 必须是整数"
        }
        return value.toInt()
    }
}
