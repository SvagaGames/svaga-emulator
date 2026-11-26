package com.svaga.emulator

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val BASE_URL = "https://45-151-106-142.sslip.io:8443"

class ApiError(val code: String) : Exception(code)

data class Player(val id: String, val name: String, val seconds: Long, val rating: Int?)
data class Community(val totalSeconds: Long, val players: Int, val avgRating: Double?, val ratingCount: Int, val top: List<Player>)
data class Person(val id: String, val name: String, val level: Int, val library: Int, val completed: Int)
data class FoundGame(val id: Int, val name: String, val year: Int?, val platforms: String, val cover: String? = null)

/** Svaga ID session (same account as Svaga Archive). The e-mail/password login is used; tokens stay in app-private prefs. */
class Session(context: Context) {
    private val p = context.getSharedPreferences("svaga_session", Context.MODE_PRIVATE)
    var access: String? get() = p.getString("access", null); set(v) = p.edit().putString("access", v).apply()
    var refresh: String? get() = p.getString("refresh", null); set(v) = p.edit().putString("refresh", v).apply()
    var email: String? get() = p.getString("email", null); set(v) = p.edit().putString("email", v).apply()
    val signedIn: Boolean get() = refresh != null
    fun clear() = p.edit().clear().apply()
}

class Net(private val session: Session) {
    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject {
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        if (auth) session.access?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiError(runCatching { JSONObject(text).optString("error", "http_$code") }.getOrDefault("http_$code"))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun save(o: JSONObject) {
        session.access = o.getString("accessToken")
        session.refresh = o.getString("refreshToken")
        session.email = o.optJSONObject("user")?.optString("email") ?: session.email
    }

    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        save(request("POST", "/v1/auth/login", JSONObject().put("email", email).put("password", password), false))
    }

    private fun refreshTokens() {
        val r = session.refresh ?: throw ApiError("unauthorized")
        save(request("POST", "/v1/auth/refresh", JSONObject().put("refreshToken", r), false))
    }

    private fun authed(method: String, path: String, body: JSONObject? = null): JSONObject = try {
        request(method, path, body, true)
    } catch (e: ApiError) {
        if (e.code == "unauthorized" || e.code == "http_401") { refreshTokens(); request(method, path, body, true) } else throw e
    }

    suspend fun search(query: String): List<FoundGame> = withContext(Dispatchers.IO) {
        val o = authed("GET", "/v1/catalog/search?q=" + URLEncoder.encode(query, "UTF-8"))
        val arr = o.optJSONArray("items") ?: return@withContext emptyList()
        (0 until arr.length()).map {
            val g = arr.getJSONObject(it)
            val plats = g.optJSONArray("platforms")
            FoundGame(
                g.getInt("id"), g.optString("name"),
                g.optString("releaseDate", "").take(4).toIntOrNull(),
                (0 until (plats?.length() ?: 0)).joinToString(", ") { i -> plats!!.getString(i) },
                g.optString("cover").ifEmpty { null }?.takeIf { it != "null" },
            )
        }
    }

    /** Cover of a Svaga catalog game (for ROMs linked from Svaga Archive). */
    suspend fun gameCover(gameId: Int): String? = withContext(Dispatchers.IO) {
        authed("GET", "/v1/catalog/games/$gameId").optString("cover").ifEmpty { null }?.takeIf { it != "null" }
    }

    suspend fun community(gameId: Int): Community = withContext(Dispatchers.IO) {
        val o = authed("GET", "/v1/games/$gameId/players?limit=10")
        val arr = o.optJSONArray("top")
        Community(
            o.optLong("totalSeconds"), o.optInt("players"),
            if (o.isNull("avgRating")) null else o.optDouble("avgRating"), o.optInt("ratingCount"),
            (0 until (arr?.length() ?: 0)).map {
                val p = arr!!.getJSONObject(it)
                Player(p.getString("id"), p.optString("name"), p.optLong("seconds"), if (p.isNull("rating")) null else p.optInt("rating"))
            },
        )
    }

    suspend fun users(q: String, offset: Int = 0): List<Person> = withContext(Dispatchers.IO) {
        val o = authed("GET", "/v1/users?offset=$offset&q=" + URLEncoder.encode(q, "UTF-8"))
        val arr = o.optJSONArray("items") ?: return@withContext emptyList()
        (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            Person(p.getString("id"), p.optString("name"), p.optInt("level", 1), p.optInt("library"), p.optInt("completed"))
        }
    }

    suspend fun rate(gameId: Int, rating: Int) = withContext(Dispatchers.IO) {
        authed("PUT", "/v1/library/$gameId", JSONObject().put("rating", rating))
        Unit
    }

    suspend fun reportPlaytime(gameId: Int, seconds: Long) = withContext(Dispatchers.IO) {
        authed("PUT", "/v1/me/playtime/$gameId", JSONObject().put("seconds", seconds))
        Unit
    }
}

/** Play time waits here until it is sent to the server, so offline sessions are not lost. */
class PlaytimeQueue(context: Context) {
    private val p = context.getSharedPreferences("svaga_playtime_queue", Context.MODE_PRIVATE)

    @Synchronized
    fun add(gameId: Int, seconds: Long) {
        if (seconds < 10) return
        p.edit().putLong(gameId.toString(), p.getLong(gameId.toString(), 0) + seconds).apply()
    }

    suspend fun flush(net: Net) {
        for ((k, _) in p.all.entries.toList()) {
            val id = k.toIntOrNull() ?: continue
            var left = p.getLong(k, 0)
            while (left > 0) {
                val chunk = minOf(left, 6 * 3600L)
                try { net.reportPlaytime(id, chunk) } catch (e: Exception) { return }
                left -= chunk
                synchronized(this) { if (left > 0) p.edit().putLong(k, left).apply() else p.edit().remove(k).apply() }
            }
        }
    }
}
