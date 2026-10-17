package com.cmsoft.horizonstream.manual

import androidx.annotation.StringRes
import android.content.ActivityNotFoundException
import com.cmsoft.horizonstream.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object PsnAccountIdLogin {
    private const val clientId = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val clientSecret = "mvaiZkRsAsI1IBkY"
    private const val authorizeEndpoint = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
    private const val tokenEndpoint = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    private const val redirectUri = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val redirectHost = "remoteplay.dl.playstation.net"
    private const val redirectPath = "/remoteplay/redirect"
    private const val scope = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
    private const val questBrowserPackage = "com.oculus.browser"

    fun openInQuestBrowser(context: Context) {
        val loginUri = Uri.parse(authorizationUrl())
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, loginUri).setPackage(questBrowserPackage))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, loginUri), context.getString(R.string.psn_login_chooser_title)))
        }
    }

    suspend fun retrieveAccountId(redirectUrl: String): String = withContext(Dispatchers.IO) {
        val authorizationCode = validateRedirectUrl(redirectUrl)
        val authorization = "Basic " + Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )

        val token = requestJson(
            method = "POST",
            url = tokenEndpoint,
            authorization = authorization,
            contentType = "application/x-www-form-urlencoded",
            requestBody = formBody(
                "grant_type" to "authorization_code",
                "code" to authorizationCode,
                "scope" to scope,
                "redirect_uri" to redirectUri
            ),

        )
        val accessToken = token.optString("access_token")
        if (accessToken.isBlank()) {
            throw PsnLoginException(R.string.psn_login_no_access_token)
        }

        val account = requestJson(
            method = "GET",
            url = "$tokenEndpoint/${Uri.encode(accessToken)}",
            authorization = authorization,
            contentType = "application/json",
            requestBody = null,

        )
        val userId = account.optString("user_id")
        if (!userId.matches(Regex("\\d{1,20}"))) {
            throw PsnLoginException(R.string.psn_login_invalid_account_id)
        }
        encodeAccountId(userId)
    }

    internal fun validateRedirectUrl(value: String): String = parseRedirectUrl(value)

    private fun authorizationUrl(): String = Uri.parse(authorizeEndpoint).buildUpon()
        .appendQueryParameter("service_entity", "urn:service-entity:psn")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("scope", scope)
        .appendQueryParameter("request_locale", "en_US")
        .appendQueryParameter("ui", "pr")
        .appendQueryParameter("service_logo", "ps")
        .appendQueryParameter("layout_type", "popup")
        .appendQueryParameter("smcid", "remoteplay")
        .appendQueryParameter("prompt", "always")
        .appendQueryParameter("PlatformPrivacyWs1", "minimal")
        .build()
        .toString()

    private fun parseRedirectUrl(value: String): String {
        val uri = try {
            Uri.parse(value.trim())
        } catch (_: Exception) {
            throw PsnLoginException(R.string.psn_login_paste_full_url)
        }
        if (uri.scheme != "https" || uri.host != redirectHost || uri.path != redirectPath) {
            throw PsnLoginException(R.string.psn_login_not_redirect_url)
        }
        uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { return it }
        if (!uri.getQueryParameter("error").isNullOrBlank()) {
            throw PsnLoginException(R.string.psn_login_not_authorized)
        }
        throw PsnLoginException(R.string.psn_login_no_code_in_url)
    }

    private fun requestJson(
        method: String,
        url: String,
        authorization: String,
        contentType: String,
        requestBody: String?
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", authorization)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            if (requestBody != null) {
                doOutput = true
                outputStream.use { it.write(requestBody.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        return try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) throw PsnLoginException(describeFailure(status, body))
            JSONObject(body)
        } catch (error: PsnLoginException) {
            throw error
        } catch (_: Exception) {
            throw PsnLoginException(R.string.psn_login_network_error)
        } finally {
            connection.disconnect()
        }
    }

    @StringRes
    private fun describeFailure(status: Int, body: String): Int {
        val oauthError = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        return when {
            oauthError == "invalid_grant" -> R.string.psn_login_code_expired
            oauthError == "invalid_client" -> R.string.psn_login_client_rejected
            oauthError == "access_denied" -> R.string.psn_login_not_authorized
            status == 403 -> R.string.psn_login_refused_403
            else -> R.string.psn_login_server_error
        }
    }

    private fun formBody(vararg parameters: Pair<String, String>): String = parameters.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
    }

    private fun encodeAccountId(userId: String): String {
        var remaining = BigInteger(userId)
        val maxAccountId = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        if (remaining.signum() < 0 || remaining > maxAccountId) {
            throw PsnLoginException(R.string.psn_login_account_id_unusable)
        }
        val bytes = ByteArray(8)
        val byteMask = BigInteger.valueOf(0xff)
        for (index in bytes.indices) {
            bytes[index] = remaining.and(byteMask).toByte()
            remaining = remaining.shiftRight(8)
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

internal class PsnLoginException(@StringRes val resId: Int) : Exception()
