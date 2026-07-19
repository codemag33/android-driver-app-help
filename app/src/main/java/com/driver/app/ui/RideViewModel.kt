package com.driver.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driver.app.data.GeocodeRepository
import com.driver.app.data.SearchHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием поездки и UI.
 *
 * Мульти-пассажиры: каждый пассажир — отдельная запись в списке ожидающих,
 * привязанный чат, свой rideId.
 */
class RideViewModel(application: Application) : AndroidViewModel(application) {

    // ─── Repositories ────────────────────────────────────────────────────────
    val geocodeRepo = GeocodeRepository(application)
    val searchHistoryRepo = SearchHistoryRepository(application)

    // ─── Trip state ──────────────────────────────────────────────────────────

    enum class TripPhase { SELECTING, TO_PICKUP, WITH_PASSENGER }

    data class TripState(
        val phase: TripPhase = TripPhase.SELECTING,
        val pointA: LatLngData? = null,
        val pointB: LatLngData? = null,
        val pinTarget: Char? = null,
        val isPinModeActive: Boolean = false,
        val serverUrl: String = "https://taxi.fbs3.ru",
        val driverName: String = "Водитель",
        val isOnline: Boolean = false
    )

    data class LatLngData(val lat: Double, val lon: Double)

    data class SearchItemUi(
        val type: SearchItemType,
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double
    )

    enum class SearchItemType { MY_LOCATION, RECENT, GEOCODED }

    data class ChatMessage(
        val from: String,
        val text: String,
        val isDriver: Boolean
    )

    // ─── Мульти-пассажиры ────────────────────────────────────────────────────

    data class WaitingPassenger(
        val passengerId: String,
        val name: String,
        val pickup: LatLngData,
        val destination: LatLngData,
        var isActive: Boolean = false,
        var currentLat: Double = pickup.lat,
        var currentLon: Double = pickup.lon
    )

    data class PassengerChat(
        val passengerId: String,
        val passengerName: String,
        val messages: List<ChatMessage> = emptyList()
    )

    data class WaitingAssistance(
        val assistId: String,
        val passengerId: String,
        val name: String,
        val pickup: LatLngData,
        val carMake: String,
        val breakdownType: String,
        val phone: String = "",
        val description: String = "",
        var isActive: Boolean = false
    )

    // ─── UI State Flows ──────────────────────────────────────────────────────

    private val _tripState = MutableStateFlow(TripState())
    val tripState: StateFlow<TripState> = _tripState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchItemUi>>(emptyList())
    val searchResults: StateFlow<List<SearchItemUi>> = _searchResults.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent: StateFlow<String?> = _toastEvent.asStateFlow()

    // Мульти-пассажиры
    private val _waitingPassengers = MutableStateFlow<List<WaitingPassenger>>(emptyList())
    val waitingPassengers: StateFlow<List<WaitingPassenger>> = _waitingPassengers.asStateFlow()

    private val _activePassengerId = MutableStateFlow<String?>(null)
    val activePassengerId: StateFlow<String?> = _activePassengerId.asStateFlow()

    private val _passengerChats = MutableStateFlow<Map<String, PassengerChat>>(emptyMap())
    val passengerChats: StateFlow<Map<String, PassengerChat>> = _passengerChats.asStateFlow()

    private val _waitingAssistances = MutableStateFlow<List<WaitingAssistance>>(emptyList())
    val waitingAssistances: StateFlow<List<WaitingAssistance>> = _waitingAssistances.asStateFlow()

    private val _activeAssistId = MutableStateFlow<String?>(null)
    val activeAssistId: StateFlow<String?> = _activeAssistId.asStateFlow()

    // ─── Search debounce ─────────────────────────────────────────────────────

    private var searchJob: Job? = null

    fun searchAddresses(query: String, target: Char, currentLat: Double?, currentLon: Double?) {
        searchJob?.cancel()
        if (query.length < 2) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce
            val results = mutableListOf<SearchItemUi>()

            if (currentLat != null && currentLon != null) {
                results.add(
                    SearchItemUi(
                        SearchItemType.MY_LOCATION,
                        getApplication<Application>().getString(com.driver.app.R.string.my_location),
                        "",
                        currentLat,
                        currentLon
                    )
                )
            }

            val recent = searchHistoryRepo.load(target)
            results.addAll(recent.map {
                SearchItemUi(SearchItemType.RECENT, it.title, it.subtitle, it.lat, it.lon)
            })

            val geocoded = geocodeRepo.search(query)
            results.addAll(geocoded.map { result ->
                val parts = result.label.split(",", limit = 2)
                val title = parts.getOrElse(0) { result.label }.trim()
                val subtitle = parts.getOrElse(1) { "" }.trim()
                SearchItemUi(SearchItemType.GEOCODED, title, subtitle, result.latitude, result.longitude)
            })

            _searchResults.value = results
        }
    }

    // ─── Point management ────────────────────────────────────────────────────

    fun setPoint(target: Char, lat: Double, lon: Double, label: String) {
        _tripState.update { state ->
            when (target) {
                'A' -> state.copy(pointA = LatLngData(lat, lon))
                'B' -> state.copy(pointB = LatLngData(lat, lon))
                else -> state
            }
        }
        viewModelScope.launch {
            searchHistoryRepo.save(target, label, "", lat, lon)
        }
    }

    fun clearPoints() {
        _tripState.update { it.copy(pointA = null, pointB = null, pinTarget = null, isPinModeActive = false) }
    }

    // ─── Pin selection ───────────────────────────────────────────────────────

    fun enterPinMode(target: Char) {
        _tripState.update { it.copy(pinTarget = target, isPinModeActive = true) }
    }

    fun exitPinMode() {
        _tripState.update { it.copy(pinTarget = null, isPinModeActive = false) }
    }

    // ─── Online/Offline ─────────────────────────────────────────────────────

    fun toggleOnline() {
        _tripState.update { it.copy(isOnline = !it.isOnline) }
    }

    fun setOnline(online: Boolean) {
        _tripState.update { it.copy(isOnline = online) }
    }

    // ─── Trip phase transitions ──────────────────────────────────────────────

    fun startTrip(): Boolean {
        val state = _tripState.value
        if (state.phase != TripPhase.SELECTING || state.pointA == null || state.pointB == null) return false
        _tripState.update { it.copy(phase = TripPhase.TO_PICKUP) }
        return true
    }

    fun passengerInCar() {
        _tripState.update { it.copy(phase = TripPhase.WITH_PASSENGER) }
    }

    fun finishOrder() {
        val keepOnline = _tripState.value.isOnline
        _tripState.value = TripState(serverUrl = _tripState.value.serverUrl, driverName = _tripState.value.driverName, isOnline = keepOnline)
        _chatMessages.value = emptyList()
        _waitingPassengers.value = emptyList()
        _activePassengerId.value = null
        _passengerChats.value = emptyMap()
        _waitingAssistances.value = emptyList()
        _activeAssistId.value = null
    }

    // ─── Chat (старый протокол) ──────────────────────────────────────────────

    fun appendChatMessage(from: String, text: String) {
        val isDriver = from != "passenger"
        val label = if (isDriver) "Вы" else "Пассажир"
        _chatMessages.value = _chatMessages.value + ChatMessage(label, text, isDriver)
    }

    // ─── Мульти-пассажиры ────────────────────────────────────────────────────

    fun addWaitingPassenger(passengerId: String, name: String, pickupLat: Double, pickupLon: Double, destLat: Double, destLon: Double) {
        val existing = _waitingPassengers.value.find { it.passengerId == passengerId }
        if (existing != null) return // уже на карте

        val passenger = WaitingPassenger(
            passengerId = passengerId,
            name = name,
            pickup = LatLngData(pickupLat, pickupLon),
            destination = LatLngData(destLat, destLon)
        )
        _waitingPassengers.update { it + passenger }

        // Инициализируем чат
        _passengerChats.update { chats ->
            chats + (passengerId to PassengerChat(passengerId, name))
        }
    }

    fun updatePassengerLocation(passengerId: String, lat: Double, lon: Double) {
        _waitingPassengers.update { list ->
            list.map { p ->
                if (p.passengerId == passengerId) {
                    p.copy(currentLat = lat, currentLon = lon)
                } else p
            }
        }
    }

    fun acceptPassenger(passengerId: String): WaitingPassenger? {
        val passenger = _waitingPassengers.value.find { it.passengerId == passengerId } ?: return null
        _waitingPassengers.update { list ->
            list.map { it.copy(isActive = it.passengerId == passengerId) }
        }
        _activePassengerId.value = passengerId
        return passenger
    }

    fun removePassenger(passengerId: String) {
        _waitingPassengers.update { list -> list.filter { it.passengerId != passengerId } }
        if (_activePassengerId.value == passengerId) {
            _activePassengerId.value = null
        }
        _passengerChats.update { chats -> chats - passengerId }
    }

    fun getActivePassenger(): WaitingPassenger? {
        val id = _activePassengerId.value ?: return null
        return _waitingPassengers.value.find { it.passengerId == id }
    }

    fun isAcceptingAllowed(): Boolean {
        return _activePassengerId.value == null && _activeAssistId.value == null && _tripState.value.phase == TripPhase.SELECTING
    }

    // ─── Помощь на дороге ────────────────────────────────────────────────────

    fun addWaitingAssistance(assistId: String, passengerId: String, name: String, pickupLat: Double, pickupLon: Double, carMake: String, breakdownType: String, phone: String = "", description: String = "") {
        val existing = _waitingAssistances.value.find { it.assistId == assistId }
        if (existing != null) return

        val assistance = WaitingAssistance(
            assistId = assistId,
            passengerId = passengerId,
            name = name,
            pickup = LatLngData(pickupLat, pickupLon),
            carMake = carMake,
            breakdownType = breakdownType,
            phone = phone,
            description = description
        )
        _waitingAssistances.update { it + assistance }
    }

    fun acceptAssistance(assistId: String): WaitingAssistance? {
        val assistance = _waitingAssistances.value.find { it.assistId == assistId } ?: return null
        _waitingAssistances.update { list ->
            list.map { it.copy(isActive = it.assistId == assistId) }
        }
        _activeAssistId.value = assistId
        return assistance
    }

    fun removeAssistance(assistId: String) {
        _waitingAssistances.update { list -> list.filter { it.assistId != assistId } }
        if (_activeAssistId.value == assistId) {
            _activeAssistId.value = null
        }
    }

    // ─── Чат с конкретным пассажиром ─────────────────────────────────────────

    fun appendPassengerChat(passengerId: String, from: String, text: String) {
        val isDriver = from != "passenger"
        val label = if (isDriver) "Вы" else _passengerChats.value[passengerId]?.passengerName ?: "Пассажир"
        val msg = ChatMessage(label, text, isDriver)

        _passengerChats.update { chats ->
            val chat = chats[passengerId] ?: return@update chats
            chats + (passengerId to chat.copy(messages = chat.messages + msg))
        }
    }

    fun getPassengerChatMessages(passengerId: String): List<ChatMessage> {
        return _passengerChats.value[passengerId]?.messages ?: emptyList()
    }

    // ─── Server URL ──────────────────────────────────────────────────────────

    fun updateServerUrl(url: String) {
        _tripState.update { it.copy(serverUrl = url) }
    }

    // ─── Toast events ────────────────────────────────────────────────────────

    fun showToast(message: String) {
        _toastEvent.value = message
    }

    fun consumeToast() {
        _toastEvent.value = null
    }

    // ─── Derived state helpers for Activity ───────────────────────────────────

    fun getGoButtonText(): String {
        return when (_tripState.value.phase) {
            TripPhase.SELECTING -> "Поехали"
            TripPhase.TO_PICKUP -> "Клиент в машине"
            TripPhase.WITH_PASSENGER -> "Обновить маршрут"
        }
    }

    fun getStatusText(): String {
        val state = _tripState.value
        return when (state.phase) {
            TripPhase.SELECTING -> if (state.pointA != null && state.pointB != null) "Готов" else "Выберите точки"
            TripPhase.TO_PICKUP -> "Едем за клиентом"
            TripPhase.WITH_PASSENGER -> "Везём клиента"
        }
    }

    fun getSearchPlaceholder(): String {
        val state = _tripState.value
        return when {
            state.phase == TripPhase.TO_PICKUP -> "Едем за клиентом"
            state.phase == TripPhase.WITH_PASSENGER -> "Везём клиента"
            state.pointA != null && state.pointB != null -> "Маршрут задан"
            state.pointA != null -> "Куда везти?"
            else -> "Куда ехать?"
        }
    }

    fun getPipStatusText(): String {
        return when (_tripState.value.phase) {
            TripPhase.TO_PICKUP -> "Едем за клиентом…"
            TripPhase.WITH_PASSENGER -> "Везём клиента…"
            TripPhase.SELECTING -> "Заказ выполняется…"
        }
    }

    fun isGoButtonEnabled(): Boolean {
        val state = _tripState.value
        return when (state.phase) {
            TripPhase.SELECTING -> state.pointA != null && state.pointB != null
            TripPhase.TO_PICKUP, TripPhase.WITH_PASSENGER -> true
        }
    }
}
