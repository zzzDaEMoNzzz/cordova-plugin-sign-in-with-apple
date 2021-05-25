package org.yorc.plugin

import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaWebView
import org.json.JSONArray
import org.json.JSONObject

import java.util.UUID
import android.util.Log
import android.annotation.TargetApi
import android.view.WindowManager.LayoutParams
import android.view.WindowManager
import android.view.Window
import android.content.Context
import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.FormBody;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Call;

class SignInWithApple : CordovaPlugin() {
    val http: OkHttpClient = OkHttpClient()

    val AUTHURL = "https://appleid.apple.com/auth/authorize"
    val TOKENURL = "https://appleid.apple.com/auth/token"

    override fun execute(action: String?, args: JSONArray, callbackContext: CallbackContext): Boolean {
        when {
            action == "signin" -> {
                var clientId: String? = null
                var redirectUri: String? = null
                var scope: String? = null

                try {
                    val options: JSONObject = args.getJSONObject(0)
                    clientId = options.getString("clientId")
                    redirectUri = options.getString("redirectUri")
                    scope = parseScope(options.getJSONArray("requestedScopes"))
                } catch (e: Exception) {
                    callbackContext.error(TAG + ": " + e.message)
                    return true
                }

                getToken(clientId!!, redirectUri!!, scope, callbackContext!!)
                return true
            }
        }
        return false
    }

    private fun parseScope(requestedScopes: JSONArray): String {
        val result = mutableListOf<String>()
        for (i in 0..(requestedScopes.length() - 1)) {
            val item: Int = requestedScopes.getInt(i)
            if (item == 0) result.add("name")
            if (item == 1) result.add("email")
        }
        return result.joinToString(separator = "%20")
    }

    private fun getToken(clientId: String, redirectUri: String, scope: String, callbackContext: CallbackContext) {
        val state = UUID.randomUUID().toString()
        val url = (AUTHURL
                + "?client_id="
                + clientId
                + "&redirect_uri="
                + redirectUri
                + "&response_type=code&scope="
                + scope
                + "&response_mode=form_post&state="
                + state)

        val openAppleDialog: Runnable = object : Runnable {
            lateinit var appleDialog: Dialog

            var clientSecret: String = ""
            var authCode: String = ""
            var firstName: String = ""
            var middleName: String = ""
            var lastName: String = ""
            var email: String = ""

            override fun run() {
                appleDialog = Dialog(webView.getContext())
                val appleWebView = WebView(cordova.getActivity())

                appleWebView.isVerticalScrollBarEnabled = false
                appleWebView.isHorizontalScrollBarEnabled = false
                appleWebView.webViewClient = AppleWebViewClient()

                appleWebView.settings.javaScriptEnabled = true
                appleWebView.loadUrl(url)

                val window: Window = appleDialog.getWindow()!!
                val lp: LayoutParams = LayoutParams()
                lp.copyFrom(window.getAttributes())
                lp.width = LayoutParams.MATCH_PARENT
                lp.height = LayoutParams.MATCH_PARENT

                appleDialog.setContentView(appleWebView)
                appleDialog.show()
                window.setAttributes(lp)

                appleDialog.setOnDismissListener {
                    appleWebView.destroy()
                }
            }

            inner class AppleWebViewClient: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (url.startsWith(redirectUri)) {
                        handleRedirect(url)
                        return true
                    }
                    return false
                }

                private fun handleRedirect(url: String) {
                    if (url.contains("success=")) {
                        appleDialog.dismiss()
                    }

                    val uri = Uri.parse(url)
                    val success = uri.getQueryParameter("success")

                    if (success == "true") {
                        authCode = uri.getQueryParameter("code") ?: ""
                        clientSecret = uri.getQueryParameter("client_secret") ?: ""

                        if (authCode == "" || clientSecret == "") {
                            return callbackContext.error(TAG + ": Couldn't get the Auth Code")
                        }

                        if (url.contains("email")) {
                            firstName = uri.getQueryParameter("first_name") ?: ""
                            middleName = uri.getQueryParameter("middle_name") ?: ""
                            lastName = uri.getQueryParameter("last_name") ?: ""
                            email = uri.getQueryParameter("email") ?: ""
                        }

                        getIdToken(authCode, clientSecret)
                    } else if (success == "false") {
                        callbackContext.error(TAG + ": Couldn't get the Auth Code")
                    }
                }

                private fun getIdToken(authCode: String, clientSecret: String) {
                    val grantType = "authorization_code"
                    val queryParams = ("?grant_type=" + grantType
                                    + "&code=" + authCode
                                    + "&redirect_uri=" + redirectUri
                                    + "&client_id=" + clientId
                                    + "&client_secret=" + clientSecret)

                    val authUrl: String = TOKENURL + queryParams

                    cordova.getThreadPool().execute(object : Runnable {
                        override fun run() {
                            val formBody: RequestBody = FormBody.Builder().build()
                            val request: Request = Request.Builder().url(authUrl).post(formBody).build()

                            http.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    try {
                                        val result: JSONObject = JSONObject(response.body!!.string())
                                        if (email != "") {
                                            val userObj: JSONObject = JSONObject()
                                            userObj.put("email", email)
                                            userObj.put("firstName", firstName)
                                            userObj.put("middleName", middleName)
                                            userObj.put("lastName", lastName)
                                        }
                                        callbackContext.success(result)
                                    } catch (e: Exception) {
                                        callbackContext.error(TAG + ": Couldn't get the Auth Code")
                                    }
                                } else {
                                    callbackContext.error(TAG + ": Couldn't get the Auth Code")
                                }
                            }
                        }
                    })
                }
            }
        }

        cordova.getActivity().runOnUiThread(openAppleDialog)
    }

    companion object {
        private const val TAG = "SignInWithApple"
    }
}