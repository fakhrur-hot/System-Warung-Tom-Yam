package com.razstudio.pos.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The setup guide behind the book button — how to bring each of the three modes up, and which steps
 * a human still has to do by hand.
 *
 * ### Why this is in the app and not a wiki
 *
 * The two manual prerequisites for a Cloud café are the kind that are only discovered at the moment
 * they fail: an owner types four correct values into a brand-new Supabase project, taps Save, and
 * gets a screen that cannot explain that the project has no tables and no functions in it. Putting
 * the sequence on the screen where the values are typed is the only place it arrives in time.
 *
 * ### Honesty about what is automated
 *
 * The provisioning wizard does the work, but two of its steps have never been run against a real
 * Supabase project — their own source says so. A guide that implied "it is all automatic" would send
 * someone at a paying café's infrastructure with unverified code, so the warning is stated here
 * rather than left in a comment nobody reads.
 *
 * English literals, matching the rest of this screen — see the note in [SetupScreen]'s signature.
 */
@Composable
fun SetupGuideDialog(
    strings: com.razstudio.pos.ui.i18n.UiStrings,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.setupGuideTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Section("Full QR Mode (cloud)")
                Body(
                    "Customers scan a table QR and order from the café's own website. Needs a " +
                        "Supabase project and a Cloudflare Pages site — both provisioned by the " +
                        "RAZStudio setup wizard, not by this app."
                )
                Step(1, "Run the provisioning wizard for the café — the Provision new café tab. It " +
                    "asks for the Wizard URL first, then applies the database schema, deploys the " +
                    "Edge Functions, creates the Pages site from GitHub main, and points the domain.")
                Step(2, "Copy the four values it shows at the end — website URL, Supabase URL, " +
                    "Supabase publishable key, café name — into this screen and tap Save.")
                Step(3, "Sign in with the owner key. Every other device joins by scanning a QR: the " +
                    "owner key for an admin, the invite QR for staff. Nothing else is typed twice.")
                Note(
                    "Joining a café that is ALREADY running is the Existing café tab, not this one. " +
                        "It takes the same values the café's Cloudflare Pages project holds — its " +
                        "site URL, VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY — and can " +
                        "fetch all of them from the site itself. Use it when the owner QR is lost."
                )

                Warn(
                    "One-time, by hand, before the first café: install the Cloudflare Pages GitHub " +
                        "App and authorize it for the repository. This is a person clicking through " +
                        "GitHub's authorize flow — it is not an API call, so no tool can do it. Once " +
                        "done it covers every future café."
                )
                Warn(
                    "Two provisioning steps — applying the schema and deploying the Edge Functions — " +
                        "have not yet been run against a real Supabase project. Verify them against a " +
                        "disposable project first: confirm all migrations report ok and that one " +
                        "deployed function actually responds. Do not point them at a paying café " +
                        "until that passes."
                )
                Note(
                    "An empty Supabase project will not work on its own. Saving these four values " +
                        "only stores them on this device — it creates nothing. If sign-in fails on a " +
                        "brand-new project, the schema or the functions are missing."
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Section("Wireless AP Mode (no cloud)")
                Body(
                    "One device runs the café: it holds the database and serves the other devices " +
                        "over Wi-Fi. No Supabase, no website, no internet needed."
                )
                Step(1, "On the device that will host, pick this mode, fill in the café name and tap " +
                    "Save, then choose Host this café on the home screen.")
                Step(2, "Put every device on the same Wi-Fi — either a router or the host's own " +
                    "hotspot. The host must stay awake and on that network.")
                Step(3, "On each staff phone, choose Join this café and scan the pairing QR from the " +
                    "host's Devices screen. Approve it there.")
                Note(
                    "The printer belongs to the host: it prints for the whole café, so staff devices " +
                        "need no printer of their own. If the host's address changes, staff devices " +
                        "relearn it automatically — no re-pairing."
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Section("Kiosk Mode (single device)")
                Body(
                    "One till, standing alone. No customer website, no staff devices, no server — " +
                        "everything is on this device."
                )
                Step(1, "Pick this mode, fill in the café name, tap Save.")
                Step(2, "Add the menu and tables, and pair the printer under Hardware.")
                Step(3, "Take backups from the Backup screen. Nothing is stored anywhere else, so a " +
                    "lost device is a lost café without them.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.setupGuideClose) } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun Step(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

/** A step no tool can perform, or one that is not yet safe to trust. Marked so it is not skimmed. */
@Composable
private fun Warn(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(text = "⚠", modifier = Modifier.width(22.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Note(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(text = "•", modifier = Modifier.width(22.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
