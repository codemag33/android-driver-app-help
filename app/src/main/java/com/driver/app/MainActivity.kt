package com.driver.app

import android.Manifest
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.annotation.RequiresApi
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.driver.app.PipActionReceiver.Companion.ACTION_FINISH_ORDER
import com.driver.app.PipActionReceiver.Companion.EXTRA_FINISH_ORDER
import com.driver.app.databinding.ActivityMainBinding
import com.driver.app.ui.MapController
import com.driver.app.ui.RideViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * Главная Activity — рабочее место водителя.
 *
 * Мульти-пассажиры: пассажиры отображаются как точки на карте,
 * чат привязан к конкретному passengerId.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_SERVER = "yanpro_settings"
        private const val PREFS_KEY_URL = "server_url"
        private const val PREFS_KEY_ONLINE = "driver_online"
        private const val PREFS_KEY_NAME = "driver_name"
        private const val DEFAULT_SERVER_URL = "https://taxi.fbs3.ru"
    }

    // ─── ViewBinding ─────────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding

    // ─── ViewModel ───────────────────────────────────────────────────────────
    private val viewModel: RideViewModel by lazy {
        ViewModelProvider(this)[RideViewModel::class.java]
    }

    // ─── Map ─────────────────────────────────────────────────────────────────
    private var mapLibreMap: MapLibreMap? = null
    private val mapController by lazy { MapController(this) }

    // ─── Handlers ────────────────────────────────────────────────────────────
    private val suggestHandler = Handler(Looper.getMainLooper())
    private val cameraIdleHandler = Handler(Looper.getMainLooper())
    private var cameraIdlePending: Runnable? = null
    private val locationBroadcastHandler = Handler(Looper.getMainLooper())
    private var locationBroadcastRunnable: Runnable? = null

    // ─── Server & Socket ─────────────────────────────────────────────────────
    private var serverUrl = DEFAULT_SERVER_URL
    private var driverDisplayName: String = ""
    private lateinit var rideSocket: RideSocketManager

    // ─── Dialogs (safe references — nulled on dismiss) ───────────────────────
    private var rideRequestDialog: Dialog? = null
    private var countdownHandler: Handler? = null
    private var countdownRunnable: Runnable? = null
    private var chatDialog: Dialog? = null
    private var chatLogView: TextView? = null

    // ─── Passenger list ──────────────────────────────────────────────────────
    private var passengerAdapter: PassengerAdapter? = null

    // ─── Assistance list ─────────────────────────────────────────────────────
    private var assistanceAdapter: AssistanceAdapter? = null

    // ─── Fallback location ───────────────────────────────────────────────────
    private val fallbackLat = 55.751244
    private val fallbackLon = 37.618423

    // ─── Permission launcher ─────────────────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            enableLocationComponent()
        } else {
            showToast(R.string.toast_location_permission_denied)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
        initMap()
        setupButtons()
        setupRouteInputs()
        setupBackPressed()
        setupViewModelObservers()
        setupPassengerList()
        setupAssistanceList()

        serverUrl = loadServerUrl()
        driverDisplayName = loadDriverName()
        setupRideSocket()
        setupOnlineStatus()
        handleIntent(intent)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Back press (не deprecated)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = viewModel.tripState.value
                if (state.phase != RideViewModel.TripPhase.SELECTING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    enterPipMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ViewModel observers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupViewModelObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Trip phase → UI updates
                launch {
                    viewModel.tripState
                        .map { it.phase }
                        .distinctUntilChanged()
                        .collect { updateGoButtonState() }
                }

                // Toast events
                launch {
                    viewModel.toastEvent.collect { msg ->
                        if (msg != null) {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            viewModel.consumeToast()
                        }
                    }
                }

                // Online status → update chip UI
                launch {
                    viewModel.tripState
                        .map { it.isOnline }
                        .distinctUntilChanged()
                        .collect { isOnline ->
                            updateOnlineChipUi(isOnline)
                        }
                }

                // Waiting passengers list → update RecyclerView
                launch {
                    viewModel.waitingPassengers.collect { passengers ->
                        passengerAdapter?.submitList(passengers)
                        binding.rvPassengers.visibility = if (passengers.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }

                // Waiting assistance list → update RecyclerView
                launch {
                    viewModel.waitingAssistances.collect { assistances ->
                        assistanceAdapter?.submitList(assistances)
                        binding.rvAssistances.visibility = if (assistances.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    // ─── Online/Offline toggle ──────────────────────────────────────────────

    private fun setupOnlineStatus() {
        val savedOnline = getSharedPreferences(PREFS_SERVER, MODE_PRIVATE)
            .getBoolean(PREFS_KEY_ONLINE, false)
        viewModel.setOnline(savedOnline)

        binding.chipOnline.setOnClickListener {
            viewModel.toggleOnline()
            val newOnline = viewModel.tripState.value.isOnline
            getSharedPreferences(PREFS_SERVER, MODE_PRIVATE).edit()
                .putBoolean(PREFS_KEY_ONLINE, newOnline).apply()
            applyOnlineState(newOnline)
        }

        // Apply initial state
        applyOnlineState(savedOnline)
    }

    private fun applyOnlineState(isOnline: Boolean) {
        if (isOnline) {
            rideSocket.connect()
            startLocationBroadcastLoop()
            showToast(R.string.toast_went_online)
        } else {
            rideSocket.disconnect()
            locationBroadcastHandler.removeCallbacksAndMessages(null)
            showToast(R.string.toast_went_offline)
        }
    }

    private fun updateOnlineChipUi(isOnline: Boolean) {
        if (isOnline) {
            binding.onlineDot.setBackgroundResource(R.drawable.bg_online_dot)
            binding.tvOnlineStatus.setText(R.string.status_online)
        } else {
            binding.onlineDot.setBackgroundResource(R.drawable.bg_offline_dot)
            binding.tvOnlineStatus.setText(R.string.status_offline)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Map initialization
    // ═══════════════════════════════════════════════════════════════════════════

    private fun initMap() {
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            val styleUrl = "https://tiles.openfreemap.org/styles/liberty"

            map.addOnCameraIdleListener {
                val target = viewModel.tripState.value.pinTarget ?: return@addOnCameraIdleListener
                val center = map.cameraPosition.target ?: return@addOnCameraIdleListener
                cameraIdlePending?.let { cameraIdleHandler.removeCallbacks(it) }
                cameraIdlePending = Runnable { reverseGeocodeAndSetPoint(center, target) }
                cameraIdleHandler.postDelayed(cameraIdlePending!!, 800)
            }

            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                Log.d(TAG, "Map style loaded")
                mapController.setupLayers(style)

                if (hasLocationPermission()) {
                    enableLocationComponent()
                } else {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(fallbackLat, fallbackLon))
                        .zoom(12.0)
                        .build()
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Buttons
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupButtons() {
        binding.btnGo.setOnClickListener { onGoClicked() }
        binding.btnFinishOrder.setOnClickListener { finishOrder() }
        binding.fabMyLocation.setOnClickListener { recenterOnMyLocation() }
        binding.searchBar.setOnClickListener {
            val target = if (viewModel.tripState.value.pointA == null) 'A' else 'B'
            openAddressSearchDialog(target)
        }
        binding.btnSettings.setOnClickListener { openServerSettingsDialog() }
    }

    private fun setupRouteInputs() {
        binding.etPointA.setOnClickListener { openAddressSearchDialog('A') }
        binding.etPointB.setOnClickListener { openAddressSearchDialog('B') }
        binding.btnSearchA.setOnClickListener { openAddressSearchDialog('A') }
        binding.btnSearchB.setOnClickListener { openAddressSearchDialog('B') }
        binding.btnPinA.setOnClickListener { viewModel.enterPinMode('A'); enterPinSelectionMode('A') }
        binding.btnPinB.setOnClickListener { viewModel.enterPinMode('B'); enterPinSelectionMode('B') }
        binding.btnConfirmPin.setOnClickListener { confirmPinSelection() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Passenger list
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupPassengerList() {
        passengerAdapter = PassengerAdapter(
            onAccept = { passenger -> acceptPassenger(passenger) },
            onChat = { passenger -> openPassengerChatDialog(passenger) }
        )
        binding.rvPassengers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = passengerAdapter
        }
    }

    private fun setupAssistanceList() {
        assistanceAdapter = AssistanceAdapter(
            onAccept = { assistance -> acceptAssistanceRequest(assistance) },
            onChat = { assistance -> openPassengerChatDialog(RideViewModel.WaitingPassenger(
                assistance.passengerId, assistance.name, assistance.pickup,
                assistance.pickup, false
            )) }
        )
        binding.rvAssistances.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = assistanceAdapter
        }
    }

    private fun acceptPassenger(passenger: RideViewModel.WaitingPassenger) {
        if (!viewModel.isAcceptingAllowed()) {
            showToast(R.string.toast_active_ride_exists)
            return
        }
        rideSocket.acceptPassenger(passenger.passengerId)
        val accepted = viewModel.acceptPassenger(passenger.passengerId) ?: return
        mapController.setActivePassenger(mapLibreMap, passenger.passengerId)

        // Set route points
        val pickup = LatLng(accepted.pickup.lat, accepted.pickup.lon)
        val dest = LatLng(accepted.destination.lat, accepted.destination.lon)
        setPoint('A', pickup, getString(R.string.pickup_label_format, accepted.name))
        setPoint('B', dest, getString(R.string.dest_label_format, accepted.name))
        mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLngZoom(pickup, 17.0))

        showToast(R.string.toast_passenger_accepted, accepted.name)
    }

    private fun acceptAssistanceRequest(assistance: RideViewModel.WaitingAssistance) {
        if (!viewModel.isAcceptingAllowed()) {
            showToast(R.string.toast_active_ride_exists)
            return
        }
        rideSocket.acceptAssistance(assistance.assistId)
        val accepted = viewModel.acceptAssistance(assistance.assistId) ?: return
        mapController.setActivePassenger(mapLibreMap, assistance.passengerId)

        // Set route points (pickup only, no destination for assistance)
        val pickup = LatLng(accepted.pickup.lat, accepted.pickup.lon)
        setPoint('A', pickup, getString(R.string.pickup_label_format, accepted.name))
        mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLngZoom(pickup, 17.0))

        showToast(R.string.toast_passenger_accepted, accepted.name)
    }

    private fun openPassengerChatDialog(passenger: RideViewModel.WaitingPassenger) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.setContentView(R.layout.dialog_passenger_chat)

        val tvName = dialog.findViewById<TextView>(R.id.tvChatPassengerName)
        val scroll = dialog.findViewById<ScrollView>(R.id.scrollPassengerChat)
        val chatLog = dialog.findViewById<TextView>(R.id.tvPassengerChatLog)
        val input = dialog.findViewById<EditText>(R.id.etPassengerChatInput)
        val btnSend = dialog.findViewById<View>(R.id.btnSendPassengerChat)

        tvName.text = getString(R.string.chat_title_format, passenger.name)

        // Show existing messages
        val existing = viewModel.getPassengerChatMessages(passenger.passengerId)
        chatLog.text = existing.joinToString("\n") { "${it.from}: ${it.text}" }

        btnSend.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                rideSocket.sendPassengerChat(passenger.passengerId, text)
                viewModel.appendPassengerChat(passenger.passengerId, "driver", text)
                chatLog.text = viewModel.getPassengerChatMessages(passenger.passengerId)
                    .joinToString("\n") { "${it.from}: ${it.text}" }
                input.setText("")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { btnSend.performClick(); true } else false
        }

        // Observe incoming messages
        val chatJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.passengerChats.collect { chats ->
                    val messages = chats[passenger.passengerId]?.messages ?: return@collect
                    if (dialog.isShowing) {
                        chatLog.text = messages.joinToString("\n") { "${it.from}: ${it.text}" }
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }

        dialog.setOnDismissListener { chatJob.cancel() }
        dialog.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Location
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent() {
        val map = mapLibreMap ?: return
        val style = map.style ?: return

        val locationComponent = map.locationComponent
        val activationOptions = LocationComponentActivationOptions
            .builder(this, style)
            .useDefaultLocationEngine(true)
            .build()

        locationComponent.activateLocationComponent(activationOptions)
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.TRACKING
        locationComponent.renderMode = RenderMode.COMPASS

        locationComponent.lastKnownLocation?.let { loc ->
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 17.0))
        }
        startLocationBroadcastLoop()
    }

    @SuppressLint("MissingPermission")
    private fun recenterOnMyLocation() {
        if (!hasLocationPermission()) {
            showToast(R.string.toast_no_location_access)
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val loc = mapLibreMap?.locationComponent?.lastKnownLocation
        if (loc != null) {
            mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 17.0))
        } else {
            showToast(R.string.toast_location_not_determined)
        }
    }

    private fun startLocationBroadcastLoop() {
        locationBroadcastHandler.removeCallbacksAndMessages(null)
        locationBroadcastRunnable = object : Runnable {
            override fun run() {
                val loc = mapLibreMap?.locationComponent?.lastKnownLocation
                if (loc != null) {
                    // Отправляем локацию водителя всем активным пассажирам
                    rideSocket.activeRides.forEach { (passengerId, _) ->
                        rideSocket.sendDriverLocation(passengerId, loc.latitude, loc.longitude)
                    }
                    // Отправляем локацию водителя всем активным вызовам помощи
                    rideSocket.activeAssists.forEach { (assistId, _) ->
                        rideSocket.sendAssistanceLocation(assistId, loc.latitude, loc.longitude)
                    }
                    // Старый протокол
                    if (rideSocket.currentRideId != null) {
                        rideSocket.sendLocation(loc.latitude, loc.longitude)
                    }
                }
                locationBroadcastHandler.postDelayed(this, 5000)
            }
        }
        locationBroadcastHandler.post(locationBroadcastRunnable!!)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Address search (Uber-style fullscreen dialog)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun openAddressSearchDialog(target: Char) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_address_search)

        val input = dialog.findViewById<EditText>(R.id.etSearchInput)
        val listView = dialog.findViewById<ListView>(R.id.lvSearchResults)
        val closeBtn = dialog.findViewById<View>(R.id.btnCloseSearch)

        val adapter = SearchAdapter(this)
        listView.adapter = adapter

        val addressMap = mutableMapOf<String, android.location.Address>()

        // Default list: my location + recent
        lifecycleScope.launch {
            val recentItems = viewModel.searchHistoryRepo.load(target).map {
                RideViewModel.SearchItemUi(
                    RideViewModel.SearchItemType.RECENT, it.title, it.subtitle, it.lat, it.lon
                )
            }
            val items = mutableListOf<RideViewModel.SearchItemUi>()
            val loc = mapLibreMap?.locationComponent?.lastKnownLocation
            if (loc != null) {
                items.add(RideViewModel.SearchItemUi(
                    RideViewModel.SearchItemType.MY_LOCATION,
                    getString(R.string.my_location), "", loc.latitude, loc.longitude
                ))
            }
            items.addAll(recentItems)
            adapter.submitList(items)
        }

        closeBtn.setOnClickListener { dialog.dismiss() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            val latLng = LatLng(item.lat, item.lon)
            val label = if (item.subtitle.isNotEmpty()) "${item.title}, ${item.subtitle}" else item.title
            setPoint(target, latLng, label)
            mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17.0))
            dialog.dismiss()
        }

        // Text search with debounce via ViewModel
        var pendingSearch: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pendingSearch?.let { suggestHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim().orEmpty()
                val loc = mapLibreMap?.locationComponent?.lastKnownLocation
                viewModel.searchAddresses(query, target, loc?.latitude, loc?.longitude)
                pendingSearch = Runnable { adapter.submitList(viewModel.searchResults.value) }
                suggestHandler.postDelayed(pendingSearch!!, 50)
            }
        })

        // Observe search results
        val resultsJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchResults.collect { results ->
                    if (dialog.isShowing) adapter.submitList(results)
                }
            }
        }

        dialog.setOnDismissListener { resultsJob.cancel() }
        dialog.show()
        input.requestFocus()
        input.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun splitAddressParts(address: android.location.Address, fallback: String): Pair<String, String> {
        val lines = (0..address.maxAddressLineIndex).map { address.getAddressLine(it) }
        return when {
            lines.size >= 2 -> lines[0] to lines.drop(1).joinToString(", ")
            lines.isNotEmpty() -> lines[0] to ""
            else -> fallback to ""
        }
    }

    private fun splitLabelParts(label: String): Pair<String, String> {
        val lastComma = label.lastIndexOf(',')
        return if (lastComma > 0) {
            label.substring(0, lastComma).trim() to label.substring(lastComma + 1).trim()
        } else {
            label to ""
        }
    }

    // ─── SearchAdapter (inner) ───────────────────────────────────────────────

    private inner class SearchAdapter(context: android.content.Context) : android.widget.BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private val items = mutableListOf<RideViewModel.SearchItemUi>()

        fun submitList(newItems: List<RideViewModel.SearchItemUi>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RideViewModel.SearchItemUi = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_search_suggestion, parent, false)
            val item = items[position]

            val icon = view.findViewById<ImageView>(R.id.ivSearchIcon)
            val title = view.findViewById<TextView>(R.id.tvSearchTitle)
            val subtitle = view.findViewById<TextView>(R.id.tvSearchSubtitle)

            title.text = item.title
            subtitle.text = item.subtitle
            subtitle.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE

            val iconRes = when (item.type) {
                RideViewModel.SearchItemType.MY_LOCATION -> R.drawable.ic_locate_me
                RideViewModel.SearchItemType.RECENT -> R.drawable.ic_history
                RideViewModel.SearchItemType.GEOCODED -> R.drawable.ic_search_result
            }
            icon.setImageResource(iconRes)
            if (item.type == RideViewModel.SearchItemType.MY_LOCATION) {
                icon.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                icon.clearColorFilter()
            }

            return view
        }
    }

    // ─── PassengerAdapter ──────────────────────────────────────────────────

    private inner class PassengerAdapter(
        private val onAccept: (RideViewModel.WaitingPassenger) -> Unit,
        private val onChat: (RideViewModel.WaitingPassenger) -> Unit
    ) : RecyclerView.Adapter<PassengerAdapter.VH>() {

        private val items = mutableListOf<RideViewModel.WaitingPassenger>()

        fun submitList(newItems: List<RideViewModel.WaitingPassenger>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val dot: View = view.findViewById(R.id.passengerDot)
            val name: TextView = view.findViewById(R.id.tvPassengerName)
            val status: TextView = view.findViewById(R.id.tvPassengerStatus)
            val pickup: TextView = view.findViewById(R.id.tvPickupAddr)
            val dest: TextView = view.findViewById(R.id.tvDestAddr)
            val btnAccept: Button = view.findViewById(R.id.btnPassengerAccept)
            val btnChat: Button = view.findViewById(R.id.btnPassengerChat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_passenger, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.name.text = p.name
            holder.pickup.text = String.format(Locale.US, "%.5f, %.5f", p.pickup.lat, p.pickup.lon)
            holder.dest.text = String.format(Locale.US, "%.5f, %.5f", p.destination.lat, p.destination.lon)

            if (p.isActive) {
                holder.status.setText(R.string.passenger_active)
                holder.dot.setBackgroundResource(R.drawable.bg_online_dot)
                holder.btnAccept.visibility = View.GONE
            } else {
                holder.status.setText(R.string.passenger_waiting)
                holder.dot.setBackgroundResource(R.drawable.bg_offline_dot)
                holder.btnAccept.visibility = View.VISIBLE
                holder.btnAccept.setOnClickListener { onAccept(p) }
            }
            holder.btnChat.setOnClickListener { onChat(p) }
        }

        override fun getItemCount(): Int = items.size
    }

    // ─── AssistanceAdapter ─────────────────────────────────────────────────

    private inner class AssistanceAdapter(
        private val onAccept: (RideViewModel.WaitingAssistance) -> Unit,
        private val onChat: (RideViewModel.WaitingAssistance) -> Unit
    ) : RecyclerView.Adapter<AssistanceAdapter.VH>() {

        private val items = mutableListOf<RideViewModel.WaitingAssistance>()

        fun submitList(newItems: List<RideViewModel.WaitingAssistance>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val dot: View = view.findViewById(R.id.assistanceDot)
            val title: TextView = view.findViewById(R.id.tvAssistanceTitle)
            val status: TextView = view.findViewById(R.id.tvAssistanceStatus)
            val car: TextView = view.findViewById(R.id.tvAssistanceCar)
            val type: TextView = view.findViewById(R.id.tvAssistanceType)
            val pickup: TextView = view.findViewById(R.id.tvAssistancePickup)
            val btnAccept: Button = view.findViewById(R.id.btnAssistanceAccept)
            val btnChat: Button = view.findViewById(R.id.btnAssistanceChat)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_assistance, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.title.text = a.name
            holder.car.text = getString(R.string.assistance_car_label) + ": " + a.carMake
            val typeText = if (a.breakdownType == "electrical") getString(R.string.assistance_type_electrical) else getString(R.string.assistance_type_mechanical)
            holder.type.text = typeText
            holder.pickup.text = String.format(Locale.US, "%.5f, %.5f", a.pickup.lat, a.pickup.lon)

            if (a.isActive) {
                holder.status.setText(R.string.passenger_active)
                holder.dot.setBackgroundResource(R.drawable.bg_online_dot)
                holder.btnAccept.visibility = View.GONE
            } else {
                holder.status.setText(R.string.passenger_waiting)
                holder.dot.setBackgroundResource(R.drawable.bg_assistance_dot)
                holder.btnAccept.visibility = View.VISIBLE
                holder.btnAccept.setOnClickListener { onAccept(a) }
            }
            holder.btnChat.setOnClickListener { onChat(a) }
        }

        override fun getItemCount(): Int = items.size
    }
    // ═══════════════════════════════════════════════════════════════════════════

    private fun enterPinSelectionMode(target: Char) {
        binding.destPin.visibility = View.VISIBLE
        binding.btnConfirmPin.visibility = View.VISIBLE
        showToast(R.string.toast_move_map_confirm)
    }

    private fun confirmPinSelection() {
        val target = viewModel.tripState.value.pinTarget ?: return
        binding.destPin.visibility = View.GONE
        val camTarget = mapLibreMap?.cameraPosition?.target
        if (camTarget == null) {
            showToast(R.string.toast_map_not_ready)
            return
        }
        val label = formatCoords(camTarget)
        setPoint(target, camTarget, label)
        viewModel.exitPinMode()
        binding.btnConfirmPin.visibility = View.GONE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reverse geocode (when camera stops)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun reverseGeocodeAndSetPoint(latLng: LatLng, target: Char) {
        lifecycleScope.launch {
            val label = viewModel.geocodeRepo.reverseGeocode(
                latLng.latitude, latLng.longitude, formatCoords(latLng)
            )
            setPoint(target, latLng, label)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Go button
    // ═══════════════════════════════════════════════════════════════════════════

    private fun onGoClicked() {
        when (viewModel.tripState.value.phase) {
            RideViewModel.TripPhase.SELECTING -> {
                if (!viewModel.startTrip()) {
                    showToast(R.string.toast_select_both_points)
                    return
                }
                launchYandexNavigator(viewModel.tripState.value.pointA!!)
            }
            RideViewModel.TripPhase.TO_PICKUP -> {
                viewModel.passengerInCar()
                launchYandexNavigator(viewModel.tripState.value.pointB!!)
            }
            RideViewModel.TripPhase.WITH_PASSENGER -> {
                launchYandexNavigator(viewModel.tripState.value.pointB!!)
            }
        }
    }

    private fun updateGoButtonState() {
        binding.btnGo.text = viewModel.getGoButtonText()
        binding.btnGo.isEnabled = viewModel.isGoButtonEnabled()
        binding.tvOrderStatus.text = viewModel.getStatusText()
        binding.tvSearchPlaceholder.text = viewModel.getSearchPlaceholder()

        val state = viewModel.tripState.value
        val statusColor = when {
            state.phase != RideViewModel.TripPhase.SELECTING -> R.color.uber_green
            state.pointA != null && state.pointB != null -> R.color.uber_green
            else -> R.color.uber_text_secondary
        }
        binding.tvOrderStatus.setTextColor(ContextCompat.getColor(this, statusColor))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Yandex Navigator
    // ═══════════════════════════════════════════════════════════════════════════

    private fun launchYandexNavigator(destination: RideViewModel.LatLngData) {
        enterPipMode()

        val backUrl = android.net.Uri.encode("tuarip://trip_ended")
        val yandexUri = android.net.Uri.parse(
            "yandexnavi://build_route_on_map" +
            "?lat_to=${destination.lat}" +
            "&lon_to=${destination.lon}" +
            "&back_url=$backUrl"
        )

        val navIntent = Intent(Intent.ACTION_VIEW, yandexUri)
        if (navIntent.resolveActivity(packageManager) != null) {
            startActivity(navIntent)
        } else {
            Toast.makeText(this, R.string.nav_yandex_not_installed, Toast.LENGTH_LONG).show()
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=ru.yandex.yandexnavi")))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Picture-in-Picture
    // ═══════════════════════════════════════════════════════════════════════════

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        enterPictureInPictureMode(buildPipParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val actionIntent = Intent(ACTION_FINISH_ORDER).apply { setPackage(packageName) }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val finishAction = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.pip_action_finish),
            getString(R.string.pip_action_finish_desc),
            pendingIntent
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
            .setActions(listOf(finishAction))
            .build()
    }

    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        if (isInPipMode) {
            binding.mapView.visibility = View.INVISIBLE
            binding.searchBarContainer.visibility = View.INVISIBLE
            binding.statusRow.visibility = View.INVISIBLE
            binding.rvPassengers.visibility = View.GONE
            binding.rvAssistances.visibility = View.GONE
            binding.routeInputsContainer.visibility = View.GONE
            binding.btnConfirmPin.visibility = View.GONE
            binding.fabMyLocation.visibility = View.INVISIBLE
            binding.fullUiGroup.visibility = View.GONE
            binding.pipStatusText.visibility = View.VISIBLE
            binding.pipStatusText.text = viewModel.getPipStatusText()
        } else {
            binding.mapView.visibility = View.VISIBLE
            binding.searchBarContainer.visibility = View.VISIBLE
            binding.statusRow.visibility = View.VISIBLE
            if (viewModel.waitingPassengers.value.isNotEmpty()) binding.rvPassengers.visibility = View.VISIBLE
            if (viewModel.waitingAssistances.value.isNotEmpty()) binding.rvAssistances.visibility = View.VISIBLE
            val state = viewModel.tripState.value
            if (state.pointA != null || state.pointB != null) binding.routeInputsContainer.visibility = View.VISIBLE
            binding.fabMyLocation.visibility = View.VISIBLE
            binding.fullUiGroup.visibility = View.VISIBLE
            binding.pipStatusText.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Finish order
    // ═══════════════════════════════════════════════════════════════════════════

    private fun finishOrder() {
        // Завершаем все активные поездки (мульти-пассажиры)
        rideSocket.activeRides.keys.toList().forEach { passengerId ->
            rideSocket.finishPassengerRide(passengerId)
        }
        // Завершаем все активные вызовы помощи
        rideSocket.activeAssists.keys.toList().forEach { assistId ->
            rideSocket.finishAssistance(assistId)
        }
        rideSocket.finishRide()
        rideRequestDialog?.dismiss()
        countdownHandler?.removeCallbacksAndMessages(null)

        if (isInPictureInPictureMode) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        viewModel.finishOrder()
        mapController.clearAll(mapLibreMap)

        binding.etPointA.setText("")
        binding.etPointB.setText("")
        binding.tvDestAddress.setText(R.string.dest_address_placeholder)
        binding.btnConfirmPin.visibility = View.GONE
        binding.routeInputsContainer.visibility = View.GONE
        binding.rvPassengers.visibility = View.GONE
        binding.rvAssistances.visibility = View.GONE
        binding.destPin.visibility = View.GONE
        chatLogView = null

        updateGoButtonState()
        showToast(R.string.toast_order_finished)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Server & Socket
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_SERVER, MODE_PRIVATE)
        return prefs.getString(PREFS_KEY_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    private fun saveServerUrl(url: String) {
        getSharedPreferences(PREFS_SERVER, MODE_PRIVATE).edit().putString(PREFS_KEY_URL, url).apply()
    }

    private fun loadDriverName(): String {
        val prefs = getSharedPreferences(PREFS_SERVER, MODE_PRIVATE)
        return prefs.getString(PREFS_KEY_NAME, getString(R.string.driver_default_name)) ?: getString(R.string.driver_default_name)
    }

    private fun saveDriverName(name: String) {
        getSharedPreferences(PREFS_SERVER, MODE_PRIVATE).edit().putString(PREFS_KEY_NAME, name).apply()
    }

    private fun setupRideSocket() {
        rideSocket = RideSocketManager(serverUrl, driverDisplayName)

        rideSocket.onConnected = {
            runOnUiThread { showToast(R.string.toast_server_connected) }
        }
        rideSocket.onConnectError = { msg ->
            runOnUiThread { Toast.makeText(this, getString(R.string.toast_server_error, serverUrl, msg), Toast.LENGTH_LONG).show() }
        }

        // ─── Старый протокол (совместимость) ─────────────────────────────────
        rideSocket.onIncomingRide = { rideId, passengerName, pLat, pLon, dLat, dLon ->
            runOnUiThread { showIncomingRideDialog(rideId, passengerName, pLat, pLon, dLat, dLon) }
        }
        rideSocket.onLocationUpdate = { lat, lon ->
            runOnUiThread { mapController.updatePassengerLocation(mapLibreMap, "legacy", lat, lon) }
        }
        rideSocket.onChatMessage = { from, text, _ ->
            runOnUiThread {
                viewModel.appendChatMessage(from, text)
                chatLogView?.text = viewModel.chatMessages.value.joinToString("\n") { "${it.from}: ${it.text}" }
            }
        }
        rideSocket.onRideFinished = {
            runOnUiThread { showToast(R.string.toast_passenger_finished) }
        }
        rideSocket.onPeerDisconnected = {
            runOnUiThread { showToast(R.string.toast_passenger_disconnected) }
        }

        // ─── Новый протокол (мульти-пассажиры) ──────────────────────────────
        rideSocket.onPassengerWaiting = { passengerId, name, pLat, pLon, dLat, dLon ->
            runOnUiThread {
                viewModel.addWaitingPassenger(passengerId, name, pLat, pLon, dLat, dLon)
                mapController.addWaitingPassenger(mapLibreMap, passengerId, pLat, pLon, name)
                showToast(R.string.toast_passenger_waiting, name)
            }
        }
        rideSocket.onPassengerLocation = { passengerId, lat, lon ->
            runOnUiThread {
                viewModel.updatePassengerLocation(passengerId, lat, lon)
                mapController.updatePassengerLocation(mapLibreMap, passengerId, lat, lon)
            }
        }
        rideSocket.onPassengerLeft = { passengerId ->
            runOnUiThread {
                val name = viewModel.waitingPassengers.value.find { it.passengerId == passengerId }?.name ?: ""
                viewModel.removePassenger(passengerId)
                mapController.removePassenger(mapLibreMap, passengerId)
                if (name.isNotEmpty()) showToast(R.string.toast_passenger_left, name)
            }
        }
        rideSocket.onPassengerRideAccepted = { passengerId, rideId ->
            runOnUiThread {
                mapController.setActivePassenger(mapLibreMap, passengerId)
            }
        }
        rideSocket.onPassengerRideFinished = { passengerId ->
            runOnUiThread {
                viewModel.removePassenger(passengerId)
                mapController.removePassenger(mapLibreMap, passengerId)
                showToast(R.string.toast_order_finished)
            }
        }
        rideSocket.onPassengerCancelled = { passengerId ->
            runOnUiThread {
                val name = viewModel.waitingPassengers.value.find { it.passengerId == passengerId }?.name ?: ""
                viewModel.removePassenger(passengerId)
                mapController.removePassenger(mapLibreMap, passengerId)
                if (name.isNotEmpty()) showToast(R.string.toast_passenger_left, name)
            }
        }
        rideSocket.onPassengerChatMessage = { passengerId, from, text, _ ->
            runOnUiThread {
                viewModel.appendPassengerChat(passengerId, from, text)
            }
        }

        // ─── Помощь на дороге ──────────────────────────────────────────────
        rideSocket.onAssistanceWaiting = { assistId, passengerId, name, pLat, pLon, carMake, breakdownType ->
            runOnUiThread {
                viewModel.addWaitingAssistance(assistId, passengerId, name, pLat, pLon, carMake, breakdownType)
                mapController.addWaitingPassenger(mapLibreMap, passengerId, pLat, pLon, name)
                showToast(R.string.toast_assistance_waiting, name)
            }
        }
        rideSocket.onAssistanceAccepted = { assistId, passengerId ->
            runOnUiThread {
                mapController.setActivePassenger(mapLibreMap, passengerId)
            }
        }
        rideSocket.onAssistanceCancelled = { assistId ->
            runOnUiThread {
                val name = viewModel.waitingAssistances.value.find { it.assistId == assistId }?.name ?: ""
                viewModel.removeAssistance(assistId)
                if (name.isNotEmpty()) showToast(R.string.toast_assistance_cancelled, name)
            }
        }
        rideSocket.onAssistanceFinished = { assistId ->
            runOnUiThread {
                val name = viewModel.waitingAssistances.value.find { it.assistId == assistId }?.name ?: ""
                viewModel.removeAssistance(assistId)
                showToast(R.string.toast_assistance_finished)
            }
        }
        rideSocket.onAssistanceDriverDisconnected = { assistId ->
            runOnUiThread {
                viewModel.removeAssistance(assistId)
                showToast(R.string.toast_assistance_passenger_disconnected)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ride request dialog (старый протокол)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showIncomingRideDialog(
        rideId: String, passengerName: String,
        pickupLat: Double, pickupLon: Double,
        destLat: Double, destLon: Double
    ) {
        if (viewModel.tripState.value.phase != RideViewModel.TripPhase.SELECTING) return
        rideRequestDialog?.dismiss()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.setContentView(R.layout.dialog_ride_request)
        dialog.setCancelable(false)

        val tvPassengerName = dialog.findViewById<TextView>(R.id.tvPassengerName)
        val tvPickupAddress = dialog.findViewById<TextView>(R.id.tvPickupAddress)
        val tvDestAddress = dialog.findViewById<TextView>(R.id.tvDestAddress)
        val tvEarnings = dialog.findViewById<TextView>(R.id.tvEarnings)
        val tvCountdown = dialog.findViewById<TextView>(R.id.tvRideCountdown)
        val btnAccept = dialog.findViewById<Button>(R.id.btnAcceptRide)
        val btnDecline = dialog.findViewById<Button>(R.id.btnDeclineRide)

        tvPassengerName.text = passengerName

        lifecycleScope.launch {
            val pickupLabel = viewModel.geocodeRepo.reverseGeocode(pickupLat, pickupLon,
                String.format(Locale.US, "%.5f, %.5f", pickupLat, pickupLon))
            val destLabel = viewModel.geocodeRepo.reverseGeocode(destLat, destLon,
                String.format(Locale.US, "%.5f, %.5f", destLat, destLon))
            runOnUiThread {
                tvPickupAddress.text = pickupLabel
                tvDestAddress.text = destLabel
            }
        }

        val earnings = listOf("320 ₽", "450 ₽", "580 ₽", "410 ₽", "670 ₽").random()
        tvEarnings.text = earnings

        // Countdown
        var countdown = 15
        tvCountdown.text = "0:${String.format(Locale.US, "%02d", countdown)}"
        countdownHandler = Handler(Looper.getMainLooper())
        countdownRunnable = object : Runnable {
            override fun run() {
                countdown--
                if (countdown <= 0) { dialog.dismiss(); return }
                tvCountdown.text = "0:${String.format(Locale.US, "%02d", countdown)}"
                countdownHandler?.postDelayed(this, 1000)
            }
        }
        countdownHandler?.postDelayed(countdownRunnable!!, 1000)

        btnAccept.setOnClickListener {
            countdownHandler?.removeCallbacksAndMessages(null)
            rideSocket.acceptRide(rideId)
            val pickupLatLng = LatLng(pickupLat, pickupLon)
            setPoint('A', pickupLatLng, "$passengerName: посадка")
            setPoint('B', LatLng(destLat, destLon), "$passengerName: назначение")
            mapLibreMap?.easeCamera(CameraUpdateFactory.newLatLngZoom(pickupLatLng, 17.0))
            dialog.dismiss()
        }

        btnDecline.setOnClickListener {
            countdownHandler?.removeCallbacksAndMessages(null)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            countdownHandler?.removeCallbacksAndMessages(null)
            rideRequestDialog = null
        }

        dialog.show()
        rideRequestDialog = dialog
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat dialog (старый протокол)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun openChatDialog() {
        if (rideSocket.currentRideId == null) {
            showToast(R.string.toast_no_active_ride)
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.setContentView(R.layout.dialog_chat)

        val scroll = dialog.findViewById<ScrollView>(R.id.scrollChat)
        chatLogView = dialog.findViewById<TextView>(R.id.tvChatLog)
        val input = dialog.findViewById<EditText>(R.id.etChatInput)
        val btnSend = dialog.findViewById<View>(R.id.btnSendChat)

        chatLogView?.text = viewModel.chatMessages.value.joinToString("\n") { "${it.from}: ${it.text}" }

        btnSend.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                rideSocket.sendChatMessage(text)
                viewModel.appendChatMessage("driver", text)
                chatLogView?.text = viewModel.chatMessages.value.joinToString("\n") { "${it.from}: ${it.text}" }
                input.setText("")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { btnSend.performClick(); true } else false
        }

        dialog.setOnDismissListener { chatLogView = null }
        dialog.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Server settings dialog
    // ═══════════════════════════════════════════════════════════════════════════

    private fun openServerSettingsDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.setContentView(R.layout.dialog_settings)

        val input = dialog.findViewById<EditText>(R.id.etServerUrl)
        val nameInput = dialog.findViewById<EditText>(R.id.etDriverName)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveServer)

        input.setText(serverUrl)
        nameInput.setText(driverDisplayName)

        btnSave.setOnClickListener {
            val newUrl = input.text.toString().trim().trimEnd('/')
            val newName = nameInput.text.toString().trim()
            if (newUrl.isEmpty()) return@setOnClickListener

            serverUrl = newUrl
            viewModel.updateServerUrl(newUrl)
            saveServerUrl(newUrl)

            if (newName.isNotEmpty() && newName != driverDisplayName) {
                driverDisplayName = newName
                saveDriverName(newName)
            }

            rideSocket.disconnect()
            setupRideSocket()
            if (viewModel.tripState.value.isOnline) rideSocket.connect()

            Toast.makeText(this, getString(R.string.toast_server_saved, newUrl), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Intent handling (deep-link / PiP action)
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.data?.scheme == "tuarip" && intent.data?.host == "trip_ended" -> {
                showToast(R.string.toast_trip_completed)
            }
            intent.getBooleanExtra(EXTRA_FINISH_ORDER, false) -> {
                finishOrder()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Централизованная установка точки: ViewModel + UI + карта. */
    private fun setPoint(target: Char, latLng: LatLng, label: String) {
        viewModel.setPoint(target, latLng.latitude, latLng.longitude, label)
        when (target) {
            'A' -> binding.etPointA.setText(label)
            'B' -> {
                binding.etPointB.setText(label)
                binding.tvDestAddress.text = label
            }
        }
        binding.routeInputsContainer.visibility = View.VISIBLE
        mapController.updatePointMarker(mapLibreMap, target, latLng, label)
        updateGoButtonState()
    }

    private fun formatCoords(latLng: LatLng): String =
        String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)

    private fun showToast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(resId: Int, formatArg: String) {
        Toast.makeText(this, getString(resId, formatArg), Toast.LENGTH_SHORT).show()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MapView lifecycle delegation
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }

    override fun onDestroy() {
        super.onDestroy()
        locationBroadcastHandler.removeCallbacksAndMessages(null)
        countdownHandler?.removeCallbacksAndMessages(null)
        rideRequestDialog?.dismiss()
        chatDialog?.dismiss()
        if (::rideSocket.isInitialized) rideSocket.disconnect()
        mapController.destroy()
        if (::binding.isInitialized) binding.mapView.onDestroy()
    }
}
