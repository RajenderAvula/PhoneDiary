package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

/**
 * Lets the user tap anywhere on a real Google Map to drop a pin, set a
 * label and radius, and confirm. Requires a valid Maps API key in the
 * manifest to render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLocationPickerDialog(
    initial: LocationReminderItem?,
    defaultCenter: LatLng = LatLng(20.5937, 78.9629), // fallback center if no initial point
    onConfirm: (LocationReminderItem) -> Unit,
    onDismiss: () -> Unit
) {
    var pickedLatLng by remember {
        mutableStateOf(initial?.let { LatLng(it.latitude, it.longitude) })
    }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var radiusInput by remember { mutableStateOf((initial?.radiusMeters ?: 100f).toInt().toString()) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(pickedLatLng ?: defaultCenter, if (pickedLatLng != null) 15f else 4f)
    }

    Dialog_FullScreenScaffold(
        title = "Pick a location",
        onDismiss = onDismiss,
        confirmLabel = "Use this location",
        confirmEnabled = pickedLatLng != null && label.isNotBlank(),
        onConfirm = {
            val point = pickedLatLng ?: return@Dialog_FullScreenScaffold
            val radius = radiusInput.toFloatOrNull()?.coerceIn(0f, 200_000f) ?: 100f
            onConfirm(
                LocationReminderItem(
                    id = initial?.id ?: LocationReminderListUtil.newId(),
                    label = label.trim(),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    radiusMeters = radius,
                    enabled = initial?.enabled ?: true
                )
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Tap anywhere on the map to drop a pin",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    onMapClick = { latLng -> pickedLatLng = latLng }
                ) {
                    /*pickedLatLng?.let { point ->
                        Marker(state = MarkerState(position = point))
                        Circle(
                            center = point,
                            radius = (radiusInput.toFloatOrNull() ?: 100f).toDouble().coerceIn(0.0, 200_000.0),
                            fillColor = androidx.compose.ui.graphics.Color(0x337C6FE0).let {
                                android.graphics.Color.argb(50, 124, 111, 224)
                            },
                            strokeColor = android.graphics.Color.rgb(124, 111, 224)
                        )
                    }*/
                    pickedLatLng?.let { point ->
                        Marker(state = MarkerState(position = point))
                        Circle(
                            center = point,
                            radius = (radiusInput.toFloatOrNull() ?: 100f).toDouble().coerceIn(0.0, 200_000.0),
                            fillColor = androidx.compose.ui.graphics.Color(0x337C6FE0),
                            strokeColor = androidx.compose.ui.graphics.Color(0xFF7C6FE0)
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Label (e.g. Home, Office)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = radiusInput,
                        onValueChange = { radiusInput = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(120.dp),
                        label = { Text("Radius (m)") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "0–200,000m (≈200km max, treated as practically unlimited)",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/** Small internal full-screen dialog scaffold shared by this picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dialog_FullScreenScaffold(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("✕ Cancel") }
                },
                actions = {
                    TextButton(enabled = confirmEnabled, onClick = onConfirm) { Text(confirmLabel) }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            content()
        }
    }
}
