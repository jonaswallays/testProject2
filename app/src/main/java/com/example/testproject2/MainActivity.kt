package com.example.testproject2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import androidx.core.content.edit
import kotlin.collections.listOfNotNull

class WeatherApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        App.instance = this
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeatherApp() }
    }
}

data class LocationResult(
    val name: String,
    val admin1: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double
) {
    val displayName: String
        get() = listOfNotNull(name, admin1, country).distinct().joinToString(", ")
}

data class HourWeather(
    val time: String,
    val temperature: Double,
    val humidity: Int,
    val rainProbability: Int,
    val weatherCode: Int,
    val isDay: Boolean
)

data class WeatherData(
    val location: LocationResult,
    val timezone: String,
    val current: HourWeather,
    val hours: List<HourWeather>
)

sealed interface WeatherUiState {
    data object Idle : WeatherUiState
    data object Loading : WeatherUiState
    data class Success(val data: WeatherData) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}

class WeatherViewModel : ViewModel() {
    private val prefs = App.instance.getSharedPreferences("weather", 0)
    var uiState by mutableStateOf<WeatherUiState>(WeatherUiState.Idle)
        private set
    var locations by mutableStateOf<List<LocationResult>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set

    init {
        val name = prefs.getString("name", null)
        val admin = prefs.getString("admin", null)
        val country = prefs.getString("country", null)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("lon", null)?.toDoubleOrNull()
        if (name != null && lat != null && lon != null) {
            val saved = LocationResult(name, admin, country, lat, lon)
            loadWeather(saved)
        }
    }

    fun search(query: String) {
        if (query.trim().length < 2) {
            locations = emptyList()
            return
        }
        viewModelScope.launch {
            searchLoading = true
            try {
                locations =
                    withContext(Dispatchers.IO) { OpenMeteoApi.searchLocations(query.trim()) }
            } catch (e: Exception) {
                locations = emptyList()
            } finally {
                searchLoading = false
            }
        }
    }

    fun selectLocation(location: LocationResult) {
        prefs.edit {
            putString("name", location.name)
                .putString("admin", location.admin1)
                .putString("country", location.country)
                .putString("lat", location.latitude.toString())
                .putString("lon", location.longitude.toString())
        }
        locations = emptyList()
        loadWeather(location)
    }

    fun reload() {
        val current = (uiState as? WeatherUiState.Success)?.data?.location ?: run {
            val name = prefs.getString("name", null) ?: return
            val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return
            val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return
            LocationResult(
                name,
                prefs.getString("admin", null),
                prefs.getString("country", null),
                lat,
                lon
            )
        }
        loadWeather(current)
    }

    private fun loadWeather(location: LocationResult) {
        viewModelScope.launch {
            uiState = WeatherUiState.Loading
            try {
                uiState = WeatherUiState.Success(
                    withContext(Dispatchers.IO) { OpenMeteoApi.getWeather(location) }
                )
            } catch (e: Exception) {
                uiState = WeatherUiState.Error(e.message ?: "Kon het weer niet ophalen.")
            }
        }
    }
}

object OpenMeteoApi {
    fun searchLocations(query: String): List<LocationResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url =
            URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=nl&format=json")
        val json = get(url)
        val results = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                add(
                    LocationResult(
                        name = item.optString("name"),
                        admin1 = item.optString("admin1").takeIf { it.isNotBlank() },
                        country = item.optString("country").takeIf { it.isNotBlank() },
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude")
                    )
                )
            }
        }
    }

    fun getWeather(location: LocationResult): WeatherData {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${location.latitude}" +
                    "&longitude=${location.longitude}" +
                    "&current=temperature_2m,relative_humidity_2m,precipitation_probability,weather_code,is_day" +
                    "&hourly=temperature_2m,relative_humidity_2m,precipitation_probability,weather_code,is_day" +
                    "&forecast_days=7" +
                    "&timezone=auto"
        )
        val json = get(url)
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temperatures = hourly.getJSONArray("temperature_2m")
        val humidities = hourly.getJSONArray("relative_humidity_2m")
        val rain = hourly.getJSONArray("precipitation_probability")
        val codes = hourly.getJSONArray("weather_code")
        val day = hourly.getJSONArray("is_day")

        val list = buildList {
            for (i in 0 until times.length()) {
                add(
                    HourWeather(
                        time = times.getString(i),
                        temperature = temperatures.getDouble(i),
                        humidity = humidities.getInt(i),
                        rainProbability = rain.getInt(i),
                        weatherCode = codes.getInt(i),
                        isDay = day.getInt(i) == 1
                    )
                )
            }
        }

        val currentJson = json.getJSONObject("current")
        val current = HourWeather(
            time = currentJson.getString("time"),
            temperature = currentJson.getDouble("temperature_2m"),
            humidity = currentJson.getInt("relative_humidity_2m"),
            rainProbability = currentJson.getInt("precipitation_probability"),
            weatherCode = currentJson.getInt("weather_code"),
            isDay = currentJson.getInt("is_day") == 1
        )
        return WeatherData(location, json.optString("timezone", ""), current, list)
    }

    private fun get(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Server gaf foutcode $status")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherApp(vm: WeatherViewModel = viewModel()) {
    var query by remember { mutableStateOf("") }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Mijn Weer", fontWeight = FontWeight.Bold) },
                    actions = {
                        Button(onClick = { vm.reload() }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Vernieuwen")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        vm.search(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Locatie zoeken") },
                    placeholder = { Text("bv. Wortegem, Gent of 9000") },
                    singleLine = true
                )

                if (vm.searchLoading) {
                    LinearLoading()
                }

                if (vm.locations.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column {
                            vm.locations.forEach { location ->
                                Button(
                                    onClick = {
                                        query = ""
                                        vm.selectLocation(location)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(location.displayName, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                when (val state = vm.uiState) {
                    WeatherUiState.Idle -> EmptyState()
                    WeatherUiState.Loading -> LoadingState()
                    is WeatherUiState.Error -> ErrorState(state.message, vm::reload)
                    is WeatherUiState.Success -> WeatherContent(state.data)
                }
            }
        }
    }
}

@Composable
private fun LinearLoading() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .padding(top = 4.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterEnd),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Weer ophalen…")
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Zoek hierboven een locatie om het weer te bekijken.")
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Er ging iets mis", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(12.dp))
        Button(onClick = retry) { Text("Opnieuw proberen") }
    }
}

@Composable
private fun WeatherContent(data: WeatherData) {
    val current = data.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                data.location.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Tijdzone: ${data.timezone}", style = MaterialTheme.typography.bodySmall)
        }
        item { CurrentWeatherCard(current) }
        item {
            Text(
                "Per uur",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        items(data.hours.take(48)) { hour -> HourRow(hour) }
        item {
            Text(
                "Bron: Open-Meteo",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun CurrentWeatherCard(hour: HourWeather) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    weatherEmoji(hour.weatherCode, hour.isDay),
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Nu", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${formatTemp(hour.temperature)}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        weatherDescription(hour.weatherCode),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("💧", "Vochtigheid", "${hour.humidity}%")
                Metric("🌧️", "Regenkans", "${hour.rainProbability}%")
                Metric("🕐", "Tijd", formatHour(hour.time))
            }
        }
    }
}

@Composable
private fun Metric(icon: String, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon)
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HourRow(hour: HourWeather) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatHour(hour.time),
                modifier = Modifier.width(58.dp),
                fontWeight = FontWeight.Bold
            )
            Text(weatherEmoji(hour.weatherCode, hour.isDay), modifier = Modifier.width(42.dp))
            Text(
                formatTemp(hour.temperature),
                modifier = Modifier.width(62.dp),
                fontWeight = FontWeight.Bold
            )
            Text("💧 ${hour.humidity}%", modifier = Modifier.width(80.dp))
            Text("🌧 ${hour.rainProbability}%")
        }
    }
}

private fun formatHour(value: String): String = value.substringAfter("T", value).take(5)

private fun formatTemp(value: Double): String = String.format(Locale.getDefault(), "%.0f°C", value)

private fun weatherEmoji(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57 -> "🌦️"
    61, 63, 65, 66, 67 -> "🌧️"
    71, 73, 75, 77 -> "🌨️"
    80, 81, 82 -> "🌦️"
    85, 86 -> "🌨️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

private fun weatherDescription(code: Int): String = when (code) {
    0 -> "Helder"
    1 -> "Overwegend helder"
    2 -> "Gedeeltelijk bewolkt"
    3 -> "Bewolkt"
    45, 48 -> "Mist"
    51, 53, 55 -> "Motregen"
    56, 57 -> "IJzel / motregen"
    61, 63, 65 -> "Regen"
    66, 67 -> "IJzel"
    71, 73, 75, 77 -> "Sneeuw"
    80, 81, 82 -> "Regenbuien"
    85, 86 -> "Sneeuwbuien"
    95 -> "Onweer"
    96, 99 -> "Onweer met hagel"
    else -> "Onbekend"
}

object App {
    lateinit var instance: android.app.Application
}