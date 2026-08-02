package com.razstudio.pos.data.google

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.razstudio.pos.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 23 — Google sign-in via Credential Manager (Requirement 15.1, 15.19).
 *
 * The old `GoogleSignInClient` is deprecated and does not work on Android 14+ without the
 * compatibility path; Credential Manager is the supported route. It needs a **Web** OAuth client as
 * the server client ID, which is what [BuildConfig.GOOGLE_WEB_CLIENT_ID] holds. The two *Android*
 * clients registered in the Cloud console are matched at runtime by package name and signing
 * certificate and are deliberately never named in code — which is exactly why "no client ID needs
 * hardcoding" reads as true and is not (task 23.12b).
 *
 * ## Every failure is the same failure
 *
 * Sign-in here buys an owner one thing: not retyping their setup. Requirement 15.9 makes it optional
 * everywhere, so this class has no error state that a caller must handle differently — it returns
 * [Result.Unavailable] with a reason for the log, and the screen shows the same Skip it was already
 * showing. A café whose owner has no Google account, no Play Services, or no signal must reach its
 * till exactly as fast as one whose owner signs in.
 *
 * That is also why [isAvailable] exists: a build with no client ID configured skips the screen
 * entirely rather than showing a button that cannot work.
 */
@Singleton
open class GoogleSignInService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "GoogleSignInService"
    }

    sealed class Result {
        /** Signed in. [idToken] is unverified — see the class note on why that is acceptable today. */
        data class Success(
            val idToken: String,
            val email: String,
            val displayName: String,
        ) : Result()

        /** The owner dismissed the sheet. Not an error; no message is shown for it. */
        data object Cancelled : Result()

        /** Anything else. [reason] is for the log, never for the screen. */
        data class Unavailable(val reason: String) : Result()
    }

    /**
     * False when this build has no Web OAuth client configured, in which case the sign-in screen is
     * skipped rather than shown broken. A café that rebrands its `applicationId` and has not
     * registered its own OAuth clients lands here (task 23.11).
     */
    open fun isAvailable(): Boolean = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /**
     * Shows the Google sign-in sheet. Must be called with an **Activity** context — Credential
     * Manager renders UI, and an application context throws.
     *
     * The base request asks for `openid email profile` only. `drive.appdata` is requested later and
     * separately, at the moment the owner first saves or restores a bundle, so an owner who never
     * uses backup is never asked for Drive access at all (task 23.5b).
     */
    open suspend fun signIn(activityContext: Context): Result {
        if (!isAvailable()) return Result.Unavailable("no web client id in this build")

        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response = CredentialManager.create(context)
                .getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                Result.Success(
                    idToken = google.idToken,
                    email = google.id,
                    displayName = google.displayName ?: google.id,
                )
            } else {
                // A provider returned something that is not a Google ID token. Nothing to do with
                // it, and nothing the owner can act on.
                Result.Unavailable("unexpected credential type ${credential.type}")
            }
        } catch (e: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (e: NoCredentialException) {
            // No Google account on the device, or — far more likely during development — this
            // account is not on the OAuth consent screen's test-user list while the app is still in
            // Testing status (task 23.14).
            Log.d(TAG, "No credential available: ${e.message}")
            Result.Unavailable("no google account available")
        } catch (e: GetCredentialException) {
            Log.d(TAG, "Sign-in unavailable: ${e.javaClass.simpleName}: ${e.message}")
            Result.Unavailable(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            // Play Services missing or too old surfaces here rather than as a GetCredentialException.
            Log.d(TAG, "Sign-in threw unexpectedly", e)
            Result.Unavailable(e.message ?: "sign-in unavailable")
        }
    }
}
