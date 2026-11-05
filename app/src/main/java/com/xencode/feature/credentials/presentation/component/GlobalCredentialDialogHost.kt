package com.xencode.feature.credentials.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xencode.feature.credentials.data.CredentialRequestBridge

/**
 * Global credential dialog host: subscribes to [CredentialRequestBridge.request] and renders [CredentialPromptDialog] whenever it is non-null.
 *
 * Attached at the app root Composable (covering all pages), so when command-line git lacks credentials (AI Bash / interactive terminal) and a dialog fires,
 * it pops wherever the user currently is — chat page, terminal page, or Git page — because credential detection is handled uniformly by the in-container custom credential helper,
 * independent of the user's current page. Confirm → [CredentialRequestBridge.respond], cancel → [CredentialRequestBridge.cancel].
 */
@Composable
fun GlobalCredentialDialogHost(bridge: CredentialRequestBridge) {
    val request by bridge.request.collectAsStateWithLifecycle()
    request?.let { req ->
        CredentialPromptDialog(
            host = req.host,
            onConfirm = { username, token ->
                bridge.respond(req.requestId, req.host, username, token)
            },
            onDismiss = { bridge.cancel(req.requestId) }
        )
    }
}
