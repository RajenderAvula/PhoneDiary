package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

/**
 * Tap anywhere on a real Google Map to drop a pin, set a label and radius.
 * Hosted in its own Dialog window because the caller sits inside a scrolling
 * column, which would otherwise hand a full-screen Scaffold infinite height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLocationPickerDialog(
    initial: LocationReminderItem?,
    defaultCenter: LatLng = LatLng(20.5937, 78.9629),
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Pick a location") },
                        navigationIcon = {
                            TextButton(onClick = onDismiss) { Text("✕ Cancel") }
                        },
                        actions = {
                            TextButton(
                                enabled = pickedLatLng != null && label.isNotBlank(),
                                onClick = {
                                    val point = pickedLatLng ?: return@TextButton
                                    val radius = radiusInput.toFloatOrNull()?.coerceIn(0f, 200_000f) ?: 100f
                                    // Keep repeat + note content when re-picking the pin of an existing item.
                                    val result = initial?.copy(
                                        label = label.trim(),
                                        latitude = point.latitude,
                                        longitude = point.longitude,
                                        radiusMeters = radius
                                    ) ?: LocationReminderItem(
                                        id = LocationReminderListUtil.newId(),
                                        label = label.trim(),
                                        latitude = point.latitude,
                                        longitude = point.longitude,
                                        radiusMeters = radius,
                                        enabled = true
                                    )
                                    onConfirm(result)
                                }
                            ) { Text("Use this location") }
                        }
                    )
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
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
                            pickedLatLng?.let { point ->
                                Marker(state = MarkerState(position = point))
                                Circle(
                                    center = point,
                                    radius = (radiusInput.toFloatOrNull() ?: 100f).toDouble().coerceIn(0.0, 200_000.0),
                                    fillColor = Color(0x337C6FE0),
                                    strokeColor = Color(0xFF7C6FE0)
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
    }
}
