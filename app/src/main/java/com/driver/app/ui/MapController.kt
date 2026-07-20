package com.driver.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.animation.LinearInterpolator
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.atan2
import kotlin.math.pow

/**
 * Управление маркерами и визуальными эффектами на карте MapLibre.
 *
 * Мульти-пассажиры: один GeoJsonSource с FeatureCollection,
 * цвет точки определяется data-driven expression по свойству `status`.
 *
 * Статусы: "waiting" = синий, "active" = зелёный
 */
class MapController(private val context: Context) {

    // ─── Source/Layer IDs ────────────────────────────────────────────────────
    private val pointASourceId = "point-a-source"
    private val pointALayerId = "point-a-layer"
    private val pointBSourceId = "point-b-source"
    private val pointBLayerId = "point-b-layer"
    private val routeLineSourceId = "route-line-source"
    private val routeLineLayerId = "route-line-layer"
    private val routeArrowSourceId = "route-arrow-source"
    private val routeArrowLayerId = "route-arrow-layer"
    private val endpointASourceId = "endpoint-a-source"
    private val endpointALayerId = "endpoint-a-layer"
    private val endpointBSourceId = "endpoint-b-source"
    private val endpointBLayerId = "endpoint-b-layer"

    // Мульти-пассажиры
    private val passengersSourceId = "passengers-source"
    private val passengersLayerId = "passengers-layer"
    private val driverSourceId = "driver-source"
    private val driverLayerId = "driver-layer"

    private var routeArrowAnimator: ValueAnimator? = null
    private var layersReady = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val OSRM_URL = "https://router.project-osrm.org"

    var onRouteInfo: ((km: String, min: String) -> Unit)? = null

    // ─── Public state ────────────────────────────────────────────────────────

    var pointA: LatLng? = null
        private set
    var pointB: LatLng? = null
        private set

    /** Подогнать камеру так, чтобы были видны все переданные точки */
    fun fitBounds(map: MapLibreMap?, points: List<LatLng>, paddingPx: Int = 120) {
        if (points.isEmpty()) return
        val mapInstance = map ?: return
        if (points.size == 1) {
            mapInstance.easeCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.5))
            return
        }
        val builder = org.maplibre.android.camera.CameraPosition.Builder()
        val latitudes = points.map { it.latitude }
        val longitudes = points.map { it.longitude }
        val minLat = latitudes.min(); val maxLat = latitudes.max()
        val minLon = longitudes.min(); val maxLon = longitudes.max()
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(minLat, minLon))
            .include(LatLng(maxLat, maxLon))
            .build()
        mapInstance.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    /** Текущее состояние пассажиров на карте */
    private val passengerFeatures = mutableMapOf<String, Feature>()

    /** Настраивает слои маркеров на карте. Вызывать после загрузки стиля. */
    fun setupLayers(style: Style) {
        style.addImage("point-a-marker", vectorToBitmap(com.driver.app.R.drawable.ic_point_a))
        style.addImage("point-b-marker", vectorToBitmap(com.driver.app.R.drawable.ic_dest_pin))
        style.addImage("route-arrow", vectorToBitmap(com.driver.app.R.drawable.ic_route_arrow))

        // Point A
        style.addSource(GeoJsonSource(pointASourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayer(
            SymbolLayer(pointALayerId, pointASourceId).withProperties(
                PropertyFactory.iconImage("point-a-marker"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#1A000000"),
                PropertyFactory.textHaloWidth(6f),
                PropertyFactory.textHaloBlur(4f),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textMaxWidth(14f),
                PropertyFactory.textLetterSpacing(0.02f)
            )
        )

        // Point B
        style.addSource(GeoJsonSource(pointBSourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayer(
            SymbolLayer(pointBLayerId, pointBSourceId).withProperties(
                PropertyFactory.iconImage("point-b-marker"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#1A000000"),
                PropertyFactory.textHaloWidth(6f),
                PropertyFactory.textHaloBlur(4f),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOffset(arrayOf(0f, 0.4f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textMaxWidth(14f),
                PropertyFactory.textLetterSpacing(0.02f)
            )
        )

        // Route line (decorative arc)
        style.addSource(GeoJsonSource(routeLineSourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayerBelow(
            LineLayer(routeLineLayerId, routeLineSourceId).withProperties(
                PropertyFactory.lineColor("#2979FF"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            ),
            pointALayerId
        )

        // Route arrow
        style.addSource(GeoJsonSource(routeArrowSourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayer(
            SymbolLayer(routeArrowLayerId, routeArrowSourceId).withProperties(
                PropertyFactory.iconImage("route-arrow"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(1.0f)
            )
        )

        // Endpoint dots
        style.addSource(GeoJsonSource(endpointASourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayerBelow(
            SymbolLayer(endpointALayerId, endpointASourceId).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor("#2979FF"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.85f)
            ),
            pointALayerId
        )

        style.addSource(GeoJsonSource(endpointBSourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayerBelow(
            SymbolLayer(endpointBLayerId, endpointBSourceId).withProperties(
                PropertyFactory.circleRadius(5f),
                PropertyFactory.circleColor("#2979FF"),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.85f)
            ),
            pointBLayerId
        )

        // ─── Мульти-пассажиры ──────────────────────────────────────────────
        // Создаём маркер-изображения ДО слоёв
        createCircleMarkerImage(style, "circle-waiting", "#2979FF")
        createCircleMarkerImage(style, "circle-active", "#00C853")
        createCircleMarkerImage(style, "circle-driver", "#4CAF50")

        // Source с FeatureCollection — каждый Feature = пассажир
        // Свойства: passengerId, name, status ("waiting"|"active")
        style.addSource(GeoJsonSource(passengersSourceId, FeatureCollection.fromFeatures(emptyArray())))

        // Слой точек пассажиров — иконка по data-driven expression (status → image)
        style.addLayerBelow(
            SymbolLayer(passengersLayerId, passengersSourceId).withProperties(
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.get("status"),
                        Expression.literal("waiting"),
                        Expression.literal("circle-waiting"),
                        Expression.literal("active"),
                        Expression.literal("circle-active"),
                        Expression.literal("circle-waiting")
                    )
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#66000000"),
                PropertyFactory.textHaloWidth(3f),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true)
            ),
            pointALayerId
        )

        // Driver marker (green dot for PWA passengers)
        style.addSource(GeoJsonSource(driverSourceId, FeatureCollection.fromFeatures(emptyArray())))
        style.addLayerBelow(
            SymbolLayer(driverLayerId, driverSourceId).withProperties(
                PropertyFactory.iconImage("circle-driver"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(1.0f)
            ),
            pointALayerId
        )

        layersReady = true
    }

    private fun createCircleMarkerImage(style: Style, name: String, color: String) {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            this.color = android.graphics.Color.parseColor(color)
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
        style.addImage(name, bitmap)
    }

    // ─── Static point markers ────────────────────────────────────────────────

    fun updatePointMarker(map: MapLibreMap?, target: Char, latLng: LatLng, label: String) {
        if (!layersReady) return
        val style = map?.style ?: return
        val sourceId = if (target == 'A') pointASourceId else pointBSourceId
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return

        val cleanLabel = label.split(",").firstOrNull()?.trim()?.take(30) ?: label.take(30)
        val feature = Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude))
        feature.addStringProperty("label", cleanLabel)
        source.setGeoJson(feature)

        when (target) {
            'A' -> pointA = latLng
            'B' -> pointB = latLng
        }
        updateRouteArc(map)
    }

    // ─── Мульти-пассажиры ────────────────────────────────────────────────────

    /** Добавить пассажира на карту (синяя точка) */
    fun addWaitingPassenger(map: MapLibreMap?, passengerId: String, lat: Double, lon: Double, name: String) {
        if (!layersReady) return
        val style = map?.style ?: return

        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            addStringProperty("passengerId", passengerId)
            addStringProperty("name", name)
            addStringProperty("status", "waiting")
        }
        passengerFeatures[passengerId] = feature
        refreshPassengersSource(style)
    }

    /** Обновить позицию пассажира на карте */
    fun updatePassengerLocation(map: MapLibreMap?, passengerId: String, lat: Double, lon: Double) {
        if (!layersReady) return
        val style = map?.style ?: return

        val existing = passengerFeatures[passengerId]
        if (existing != null) {
            val updated = Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("passengerId", passengerId)
                addStringProperty("name", existing.getStringProperty("name") ?: "")
                addStringProperty("status", existing.getStringProperty("status") ?: "waiting")
            }
            passengerFeatures[passengerId] = updated
            refreshPassengersSource(style)
        }
    }

    /** Пассажир принят — делаем зелёным */
    fun setActivePassenger(map: MapLibreMap?, passengerId: String) {
        if (!layersReady) return
        val style = map?.style ?: return

        passengerFeatures[passengerId]?.let { feature ->
            val updated = Feature.fromGeometry(feature.geometry() as Point).apply {
                addStringProperty("passengerId", passengerId)
                addStringProperty("name", feature.getStringProperty("name") ?: "")
                addStringProperty("status", "active")
            }
            passengerFeatures[passengerId] = updated
            refreshPassengersSource(style)
        }
    }

    /** Убрать пассажира с карты */
    fun removePassenger(map: MapLibreMap?, passengerId: String) {
        if (!layersReady) return
        val style = map?.style ?: return

        passengerFeatures.remove(passengerId)
        refreshPassengersSource(style)
    }

    /** Убрать всех пассажиров с карты */
    fun clearPassengers(map: MapLibreMap?) {
        passengerFeatures.clear()
        if (!layersReady) return
        val style = map?.style ?: return
        refreshPassengersSource(style)
    }

    private fun refreshPassengersSource(style: Style) {
        val source = style.getSourceAs<GeoJsonSource>(passengersSourceId) ?: return
        val features = passengerFeatures.values.toList()
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // ─── Driver marker (for PWA) ─────────────────────────────────────────────

    fun updateDriverMarker(map: MapLibreMap?, lat: Double, lon: Double) {
        if (!layersReady) return
        val style = map?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(driverSourceId) ?: return
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
    }

    fun clearDriverMarker(map: MapLibreMap?) {
        if (!layersReady) return
        val style = map?.style ?: return
        style.getSourceAs<GeoJsonSource>(driverSourceId)
            ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // ─── Route arc ───────────────────────────────────────────────────────────

    fun updateRouteArc(map: MapLibreMap?) {
        if (!layersReady) return
        val style = map?.style ?: return
        val a = pointA
        val b = pointB

        routeArrowAnimator?.cancel()

        if (a == null || b == null) {
            style.getSourceAs<GeoJsonSource>(routeLineSourceId)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            style.getSourceAs<GeoJsonSource>(routeArrowSourceId)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            style.getSourceAs<GeoJsonSource>(endpointASourceId)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            style.getSourceAs<GeoJsonSource>(endpointBSourceId)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            onRouteInfo?.invoke("", "")
            return
        }

        fetchOSRMRoute(map, a, b)
    }

    private fun fetchOSRMRoute(map: MapLibreMap?, a: LatLng, b: LatLng) {
        val url = "$OSRM_URL/route/v1/driving/${a.longitude},${a.latitude};${b.longitude},${b.latitude}?overview=full&geometries=geojson&steps=false"
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { JSONObject(URL(url).readText()) }
                val routes = json.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val route = routes.getJSONObject(0)
                    val distance = route.getDouble("distance")
                    val duration = route.getDouble("duration")
                    val km = String.format("%.1f км", distance / 1000)
                    val min = "${Math.round(duration / 60)} мин"
                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    val points = (0 until coords.length()).map { i ->
                        val c = coords.getJSONArray(i)
                        Point.fromLngLat(c.getDouble(0), c.getDouble(1))
                    }
                    withContext(Dispatchers.Main) {
                        drawRouteLine(map, points)
                        onRouteInfo?.invoke(km, min)
                    }
                    return@launch
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                val curve = buildCurvedRoute(a, b)
                drawRouteLine(map, curve)
                val dist = a.distanceTo(b) / 1000.0
                onRouteInfo?.invoke(String.format("~%.1f км", dist), "~${Math.round(dist / 40 * 60)} мин")
            }
        }
    }

    private fun drawRouteLine(map: MapLibreMap?, points: List<Point>) {
        val style = map?.style ?: return
        style.getSourceAs<GeoJsonSource>(routeLineSourceId)
            ?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        style.getSourceAs<GeoJsonSource>(endpointASourceId)
            ?.setGeoJson(Feature.fromGeometry(points.first()))
        style.getSourceAs<GeoJsonSource>(endpointBSourceId)
            ?.setGeoJson(Feature.fromGeometry(points.last()))
        startArrowAnimation(map, points)
    }

    // ─── Clear all ───────────────────────────────────────────────────────────

    fun clearAll(map: MapLibreMap?) {
        routeArrowAnimator?.cancel()
        pointA = null
        pointB = null
        passengerFeatures.clear()
        val style = map?.style ?: return
        listOf(
            pointASourceId, pointBSourceId, routeLineSourceId, routeArrowSourceId,
            endpointASourceId, endpointBSourceId, passengersSourceId, driverSourceId
        ).forEach { id ->
            style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    fun destroy() {
        routeArrowAnimator?.cancel()
        routeArrowAnimator = null
    }

    // ─── Private: arrow animation ────────────────────────────────────────────

    private fun startArrowAnimation(map: MapLibreMap?, curve: List<Point>) {
        routeArrowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                moveArrowAlongCurve(map, curve, t)
            }
            start()
        }
    }

    private fun moveArrowAlongCurve(map: MapLibreMap?, curve: List<Point>, t: Float) {
        val style = map?.style ?: return
        val idx = (t * (curve.size - 1)).toInt().coerceIn(1, curve.size - 1)
        val bearing = calcBearing(curve[idx - 1], curve[idx])

        style.getSourceAs<GeoJsonSource>(routeArrowSourceId)?.setGeoJson(Feature.fromGeometry(curve[idx]))
        style.getLayerAs<SymbolLayer>(routeArrowLayerId)
            ?.setProperties(PropertyFactory.iconRotate(bearing.toFloat()))
    }

    // ─── Private: geometry helpers ───────────────────────────────────────────

    private fun buildCurvedRoute(a: LatLng, b: LatLng, segments: Int = 32): List<Point> {
        val ax = a.longitude; val ay = a.latitude
        val bx = b.longitude; val by = b.latitude
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        val dx = bx - ax
        val dy = by - ay
        val curveFactor = 0.15
        val cx = mx - dy * curveFactor
        val cy = my + dx * curveFactor

        return (0..segments).map { i ->
            val t = i.toDouble() / segments
            val x = (1 - t).pow(2) * ax + 2 * (1 - t) * t * cx + t.pow(2) * bx
            val y = (1 - t).pow(2) * ay + 2 * (1 - t) * t * cy + t.pow(2) * by
            Point.fromLngLat(x, y)
        }
    }

    private fun calcBearing(from: Point, to: Point): Double {
        val dLon = to.longitude() - from.longitude()
        val dLat = to.latitude() - from.latitude()
        var degrees = Math.toDegrees(atan2(dLon, dLat))
        if (degrees < 0) degrees += 360
        return degrees
    }

    private fun vectorToBitmap(@DrawableRes resId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)!!
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
