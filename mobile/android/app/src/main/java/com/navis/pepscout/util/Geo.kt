package com.navis.pepscout.util

import kotlin.math.*

/**
 * Utility functions for geographic calculations, bearings, and distance
 */
object Geo {
    
    private const val EARTH_RADIUS_M = 6371000.0 // Earth radius in meters
    
    /**
     * Calculate distance between two points using Haversine formula
     * @return distance in meters
     */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                sin(dLon / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return EARTH_RADIUS_M * c
    }
    
    /**
     * Calculate bearing from point 1 to point 2
     * @return bearing in degrees (0-360, where 0 = North)
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360) % 360
    }
    
    /**
     * Calculate difference between two bearings
     * @return difference in degrees (-180 to +180)
     */
    fun bearingDifference(bearing1: Double, bearing2: Double): Double {
        var diff = bearing2 - bearing1
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return diff
    }
    
    /**
     * Decode polyline string to list of lat/lng coordinates
     * @param polyline encoded polyline string
     * @param precision precision factor (5 for standard, 6 for high precision)
     * @return list of (lat, lng) pairs
     */
    fun decodePolyline(polyline: String, precision: Int = 5): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        val len = polyline.length
        var lat = 0
        var lng = 0
        val factor = 10.0.pow(precision).toInt()

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            
            // Decode latitude
            do {
                b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            
            // Decode longitude
            do {
                b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            points.add(Pair(lat.toDouble() / factor, lng.toDouble() / factor))
        }

        return points
    }
    
    /**
     * Find closest point on a polyline to a given position
     * @return (closest point, distance to line, index of segment)
     */
    fun closestPointOnPolyline(
        lat: Double, 
        lon: Double, 
        polyline: List<Pair<Double, Double>>
    ): Triple<Pair<Double, Double>, Double, Int>? {
        if (polyline.size < 2) return null
        
        var minDistance = Double.MAX_VALUE
        var closestPoint: Pair<Double, Double>? = null
        var segmentIndex = -1
        
        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i + 1]
            
            val closest = closestPointOnSegment(lat, lon, p1.first, p1.second, p2.first, p2.second)
            val dist = distance(lat, lon, closest.first, closest.second)
            
            if (dist < minDistance) {
                minDistance = dist
                closestPoint = closest
                segmentIndex = i
            }
        }
        
        return closestPoint?.let { Triple(it, minDistance, segmentIndex) }
    }
    
    /**
     * Find closest point on a line segment to a given position
     */
    private fun closestPointOnSegment(
        lat: Double, lon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Pair<Double, Double> {
        // Convert to meters for calculation
        val x = lat
        val y = lon
        val x1 = lat1
        val y1 = lon1
        val x2 = lat2
        val y2 = lon2
        
        val dx = x2 - x1
        val dy = y2 - y1
        
        if (dx == 0.0 && dy == 0.0) {
            return Pair(x1, y1) // Start and end are the same
        }
        
        val t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        
        return Pair(x1 + clampedT * dx, y1 + clampedT * dy)
    }
    
    /**
     * Check if a point is within a certain distance of a polyline
     */
    fun isNearPolyline(
        lat: Double,
        lon: Double, 
        polyline: List<Pair<Double, Double>>,
        maxDistance: Double
    ): Boolean {
        val result = closestPointOnPolyline(lat, lon, polyline)
        return result?.second ?: Double.MAX_VALUE <= maxDistance
    }
    
    /**
     * Normalize angle to 0-360 range
     */
    fun normalizeAngle(angle: Double): Double {
        var normalized = angle % 360
        if (normalized < 0) normalized += 360
        return normalized
    }
    
    /**
     * Convert meters to approximate degrees at given latitude
     */
    fun metersToDegreesLat(meters: Double): Double = meters / 111320.0
    
    fun metersToDegreesLon(meters: Double, latitude: Double): Double {
        return meters / (111320.0 * cos(Math.toRadians(latitude)))
    }
}