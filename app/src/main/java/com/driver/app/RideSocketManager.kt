package com.driver.app

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URISyntaxException

/**
 * Обёртка над Socket.IO-клиентом для связи с сервером Yan.Pro.
 *
 * Все колбэки вызываются НЕ на главном потоке — вызывающая сторона
 * обязана переключаться на UI через runOnUiThread или coroutines.
 *
 * Поддерживает мульти-пассажиров: каждый пассажир — отдельный rideId,
 * чат и локация привязаны к конкретному passengerId.
 */
class RideSocketManager(
    private val serverUrl: String,
    private val driverName: String
) {
    companion object {
        private const val TAG = "RideSocketManager"
    }

    private var socket: Socket? = null

    /** Текущий активный rideId (для совместимости со старым протоколом) */
    var currentRideId: String? = null
        private set

    /** Маппинг passengerId → rideId для активных поездок */
    var activeRides: MutableMap<String, String> = mutableMapOf()
        private set

    /** Маппинг assistId → passengerId для активных вызовов помощи */
    var activeAssists: MutableMap<String, String> = mutableMapOf()
        private set

    // ─── Callbacks ───────────────────────────────────────────────────────────
    var onConnected: (() -> Unit)? = null
    var onConnectError: ((String) -> Unit)? = null

    // Старый протокол (один пассажир)
    var onIncomingRide: ((rideId: String, passengerName: String, pickupLat: Double, pickupLon: Double, destLat: Double, destLon: Double) -> Unit)? = null
    var onRideTaken: ((rideId: String) -> Unit)? = null
    var onLocationUpdate: ((lat: Double, lon: Double) -> Unit)? = null
    var onChatMessage: ((from: String, text: String, timestampMs: Long) -> Unit)? = null
    var onRideFinished: (() -> Unit)? = null
    var onPeerDisconnected: (() -> Unit)? = null

    // Новый протокол (мульти-пассажиры)
    var onPassengerWaiting: ((passengerId: String, name: String, pickupLat: Double, pickupLon: Double, destLat: Double, destLon: Double) -> Unit)? = null
    var onPassengerLocation: ((passengerId: String, lat: Double, lon: Double) -> Unit)? = null
    var onPassengerLeft: ((passengerId: String) -> Unit)? = null
    var onPassengerChatMessage: ((passengerId: String, from: String, text: String, timestampMs: Long) -> Unit)? = null
    var onPassengerRideAccepted: ((passengerId: String, rideId: String) -> Unit)? = null
    var onPassengerRideFinished: ((passengerId: String) -> Unit)? = null
    var onPassengerCancelled: ((passengerId: String) -> Unit)? = null

    // Помощь на дороге
    var onAssistanceWaiting: ((assistId: String, passengerId: String, passengerName: String, pickupLat: Double, pickupLon: Double, carMake: String, breakdownType: String) -> Unit)? = null
    var onAssistanceAccepted: ((assistId: String, passengerId: String) -> Unit)? = null
    var onAssistanceCancelled: ((assistId: String) -> Unit)? = null
    var onAssistanceFinished: ((assistId: String) -> Unit)? = null
    var onAssistanceDriverLocation: ((assistId: String, lat: Double, lon: Double) -> Unit)? = null
    var onAssistanceDriverDisconnected: ((assistId: String) -> Unit)? = null

    fun connect() {
        if (socket?.connected() == true) return
        socket?.disconnect()
        socket?.off()
        try {
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 2000
                extraHeaders = mutableMapOf()
            }
            val s = IO.socket(serverUrl, opts)
            socket = s

            s.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "connected")
                s.emit("driver:register", JSONObject().put("name", driverName))
                onConnected?.invoke()
            }

            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.firstOrNull()?.toString() ?: "unknown error"
                Log.e(TAG, "connect_error: $msg")
                onConnectError?.invoke(msg)
            }

            // ─── Старый протокол (совместимость) ─────────────────────────
            s.on("ride:incoming", onIncomingRideRaw)
            s.on("ride:taken") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                onRideTaken?.invoke(data.optString("rideId"))
            }
            s.on("ride:accepted") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                currentRideId = data.optString("rideId")
            }
            s.on("location:update") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                if (data.optString("from") == "passenger") {
                    onLocationUpdate?.invoke(data.optDouble("lat"), data.optDouble("lon"))
                }
            }
            s.on("chat:message") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                onChatMessage?.invoke(
                    data.optString("from"),
                    data.optString("text"),
                    data.optLong("ts")
                )
            }
            s.on("ride:finished") {
                currentRideId = null
                onRideFinished?.invoke()
            }
            s.on("ride:peer_disconnected") {
                onPeerDisconnected?.invoke()
            }

            // ─── Новый протокол (мульти-пассажиры) ──────────────────────
            s.on("passenger:waiting") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val pickup = data.optJSONObject("pickup") ?: return@on
                val dest = data.optJSONObject("destination") ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isEmpty()) return@on
                onPassengerWaiting?.invoke(
                    passengerId,
                    data.optString("passengerName", "Пассажир"),
                    pickup.optDouble("lat"),
                    pickup.optDouble("lon"),
                    dest.optDouble("lat"),
                    dest.optDouble("lon")
                )
            }
            s.on("passenger:location") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isEmpty()) return@on
                onPassengerLocation?.invoke(
                    passengerId,
                    data.optDouble("lat"),
                    data.optDouble("lon")
                )
            }
            s.on("passenger:left") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isEmpty()) return@on
                activeRides.remove(passengerId)
                onPassengerLeft?.invoke(passengerId)
            }
            s.on("passenger:ride_accepted") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                val rideId = data.optString("rideId")
                if (passengerId.isNotEmpty() && rideId.isNotEmpty()) {
                    activeRides[passengerId] = rideId
                }
                onPassengerRideAccepted?.invoke(passengerId, rideId)
            }
            s.on("passenger:ride_finished") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isNotEmpty()) {
                    activeRides.remove(passengerId)
                }
                onPassengerRideFinished?.invoke(passengerId)
            }
            s.on("passenger:cancelled") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isNotEmpty()) {
                    activeRides.remove(passengerId)
                }
                onPassengerCancelled?.invoke(passengerId)
            }
            s.on("passenger:chat") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val passengerId = data.optString("passengerId")
                if (passengerId.isEmpty()) return@on
                onPassengerChatMessage?.invoke(
                    passengerId,
                    data.optString("from"),
                    data.optString("text"),
                    data.optLong("ts")
                )
            }

            // ─── Помощь на дороге ──────────────────────────────────────────
            s.on("assistance:waiting") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val pickup = data.optJSONObject("pickup") ?: return@on
                val assistId = data.optString("assistId")
                val passengerId = data.optString("passengerId")
                if (assistId.isEmpty() || passengerId.isEmpty()) return@on
                onAssistanceWaiting?.invoke(
                    assistId,
                    passengerId,
                    data.optString("passengerName", "Пассажир"),
                    pickup.optDouble("lat"),
                    pickup.optDouble("lon"),
                    data.optString("carMake", ""),
                    data.optString("breakdownType", "unknown")
                )
            }
            s.on("assistance:ride_accepted") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val assistId = data.optString("assistId")
                val passengerId = data.optString("passengerId")
                if (assistId.isNotEmpty() && passengerId.isNotEmpty()) {
                    activeAssists[assistId] = passengerId
                }
                onAssistanceAccepted?.invoke(assistId, passengerId)
            }
            s.on("assistance:cancelled") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val assistId = data.optString("assistId")
                if (assistId.isNotEmpty()) {
                    activeAssists.remove(assistId)
                }
                onAssistanceCancelled?.invoke(assistId)
            }
            s.on("assistance:finished") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val assistId = data.optString("assistId")
                if (assistId.isNotEmpty()) {
                    activeAssists.remove(assistId)
                }
                onAssistanceFinished?.invoke(assistId)
            }
            s.on("assistance:driver_location") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val assistId = data.optString("assistId")
                if (assistId.isEmpty()) return@on
                onAssistanceDriverLocation?.invoke(
                    assistId,
                    data.optDouble("lat"),
                    data.optDouble("lon")
                )
            }
            s.on("assistance:driver_disconnected") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val assistId = data.optString("assistId")
                if (assistId.isNotEmpty()) {
                    activeAssists.remove(assistId)
                }
                onAssistanceDriverDisconnected?.invoke(assistId)
            }

            s.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL: $serverUrl", e)
            onConnectError?.invoke("Invalid server URL: $serverUrl")
        }
    }

    private val onIncomingRideRaw = Emitter.Listener { args ->
        val data = args.firstOrNull() as? JSONObject ?: return@Listener
        val pickup = data.optJSONObject("pickup") ?: return@Listener
        val dest = data.optJSONObject("destination") ?: return@Listener
        onIncomingRide?.invoke(
            data.optString("rideId"),
            data.optString("passengerName", "Passenger"),
            pickup.optDouble("lat"),
            pickup.optDouble("lon"),
            dest.optDouble("lat"),
            dest.optDouble("lon")
        )
    }

    // ─── Методы водителя ──────────────────────────────────────────────────

    fun acceptRide(rideId: String) {
        currentRideId = rideId
        socket?.emit("ride:accept", JSONObject().put("rideId", rideId))
    }

    fun acceptPassenger(passengerId: String) {
        socket?.emit("ride:accept", JSONObject().put("passengerId", passengerId))
    }

    fun sendLocation(lat: Double, lon: Double) {
        val rideId = currentRideId ?: return
        socket?.emit(
            "location:update",
            JSONObject().put("rideId", rideId).put("lat", lat).put("lon", lon)
        )
    }

    fun sendDriverLocation(passengerId: String, lat: Double, lon: Double) {
        socket?.emit(
            "ride:driver_location",
            JSONObject().put("passengerId", passengerId).put("lat", lat).put("lon", lon)
        )
    }

    fun sendChatMessage(text: String) {
        val rideId = currentRideId ?: return
        socket?.emit("chat:message", JSONObject().put("rideId", rideId).put("text", text))
    }

    fun sendPassengerChat(passengerId: String, text: String) {
        socket?.emit(
            "passenger:chat",
            JSONObject().put("passengerId", passengerId).put("text", text)
        )
    }

    fun finishRide() {
        val rideId = currentRideId ?: return
        socket?.emit("ride:finish", JSONObject().put("rideId", rideId))
        currentRideId = null
    }

    fun finishPassengerRide(passengerId: String) {
        socket?.emit(
            "ride:finish",
            JSONObject().put("passengerId", passengerId)
        )
        activeRides.remove(passengerId)
    }

    fun acceptAssistance(assistId: String) {
        socket?.emit("assistance:accept", JSONObject().put("assistId", assistId))
    }

    fun finishAssistance(assistId: String) {
        socket?.emit("assistance:finish", JSONObject().put("assistId", assistId))
        activeAssists.remove(assistId)
    }

    fun sendAssistanceLocation(assistId: String, lat: Double, lon: Double) {
        socket?.emit(
            "assistance:driver_location",
            JSONObject().put("assistId", assistId).put("lat", lat).put("lon", lon)
        )
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
