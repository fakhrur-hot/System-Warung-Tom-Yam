package com.razstudio.pos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.razstudio.pos.ui.i18n.LanguageViewModel
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.viewmodels.AccountAvatarViewModel

/**
 * The signed-in Google account, beside the language and theme controls on every screen that has them.
 *
 * ## Why the menu differs by screen
 *
 * On the home screen the owner is between things and can afford a full account menu. Everywhere
 * else they are mid-service, and the two logouts are not equally recoverable:
 *
 *  - **Mode Logout** drops the café choice and keeps the Google session, so the owner lands on the
 *    home screen with their other cafés still listed and can switch without re-authenticating.
 *  - **Google Logout** clears the account. Every café folder disappears until somebody signs in
 *    again — which, on a counter phone in the middle of a lunch rush, is not a mistake anyone
 *    should be one tap away from.
 *
 * So off the home screen the menu offers Mode Logout only. That split is from the amendment in the
 * design sample, and it is the reason this composable takes [isHomeScreen] rather than reading the
 * route itself: the caller knows, and a route-sniffing component would silently do the wrong thing
 * on any screen added later.
 *
 * ## Nothing renders when nobody is signed in
 *
 * Google sign-in is optional everywhere (Property 10). A café that never signs in must not carry a
 * dead avatar around, so this collapses to nothing.
 */
@Composable
fun AccountAvatar(
    isHomeScreen: Boolean,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    onReloadDrive: () -> Unit = {},
    /** Nobody linked yet — take the owner to the Google flow. Home screen only. */
    onLink: () -> Unit = {},
    viewModel: AccountAvatarViewModel = hiltViewModel(),
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsState()
    val language by languageViewModel.language.collectAsState()
    val strings = uiStrings(language)
    var expanded by remember { mutableStateOf(false) }

    val current = account
    if (current == null) {
        // Home screen: offer to link, in the same slot the avatar will occupy afterwards, so the
        // control does not move once an account exists. This is what replaced the separate sign-in
        // page — the one unique thing that page offered, on the screen the owner already opens.
        //
        // Everywhere else: nothing. Sign-in is optional (Property 10), and a café that never links
        // must not be nagged from behind its own till.
        if (isHomeScreen) {
            IconButton(onClick = onLink, modifier = modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = strings.googleLinkButton,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // Off-cloud the photo can never load: NoInternetGuard refuses to resolve Google's image host in
    // LAN and Kiosk Mode, exactly as it should. Coil then draws its broken-image placeholder, which
    // on a café till looks like a bug rather than a working sign-in. Falling back to the initial
    // makes an unreachable photo indistinguishable from an account that never had one.
    var photoFailed by remember(current.photoUrl) { mutableStateOf(false) }

    Box {
        Box(
            modifier = modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            if (current.photoUrl.isNotBlank() && !photoFailed) {
                AsyncImage(
                    model = current.photoUrl,
                    contentDescription = current.email,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    onError = { photoFailed = true },
                )
            } else {
                // Not every Google account has a picture, and a blank circle looks broken. The
                // initial is the same fallback Google's own surfaces use.
                Text(
                    text = current.displayName.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The account is named at the top, unlabelled, because an owner with two Google
            // accounts on one phone needs to know which one this café is tied to before they act on
            // either logout.
            DropdownMenuItem(
                enabled = false,
                text = {
                    Text(
                        text = current.email,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {},
            )

            if (isHomeScreen) {
                DropdownMenuItem(
                    text = { Text(strings.avatarReloadDrive) },
                    onClick = {
                        expanded = false
                        onReloadDrive()
                    },
                )
            }

            DropdownMenuItem(
                text = { Text(strings.avatarModeLogout) },
                onClick = {
                    expanded = false
                    viewModel.modeLogout()
                    onSignedOut()
                },
            )

            if (isHomeScreen) {
                DropdownMenuItem(
                    text = { Text(strings.avatarGoogleLogout) },
                    onClick = {
                        expanded = false
                        viewModel.googleLogout()
                        onSignedOut()
                    },
                )
            }
        }
    }
}
