package com.postureguard.ui.friends

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postureguard.cloud.Friendship
import com.postureguard.ui.components.Gap
import com.postureguard.ui.components.InitialAvatar
import com.postureguard.ui.components.Panel
import com.postureguard.ui.components.PrimaryButton
import com.postureguard.ui.components.SecondaryButton
import com.postureguard.ui.components.SectionLabel
import com.postureguard.ui.components.SmallButton
import com.postureguard.ui.components.TextLink
import com.postureguard.ui.theme.StatusColors

/**
 * Posture buddies: add friends by email, accept requests, star favorites (they are told when you slouch for
 * 10 minutes) and challenge a friend to hold good posture for 5 seconds.
 * Layout: left = who I am + add a friend, right = the lists.
 */
@Composable
fun FriendsScreen(viewModel: FriendsViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.clearMessages()
        viewModel.refresh()
    }

    Row(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        // --- Left column ------------------------------------------------------------------------------
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            TextLink("←  Back", onClick = onClose, modifier = Modifier.offset(x = (-8).dp))
            Gap(8.dp)
            Text("Friends", style = MaterialTheme.typography.headlineLarge)
            Gap(4.dp)
            Text(
                "Favorites get a message when you've slouched for 10 minutes. Challenge a friend to sit up straight.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(20.dp)
            val account = state.account
            when {
                !state.accountLoaded -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                account == null -> Unit // the sign-in form is in the right column
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialAvatar(account.displayName.ifBlank { account.email }, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName.ifBlank { "You" }, style = MaterialTheme.typography.titleSmall)
                            Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextLink("Sign out", onClick = viewModel::signOut)
                    }
                }
            }
            state.error?.let {
                Gap(12.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Bad)
            }
            state.info?.let {
                Gap(12.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Good)
            }
        }
        Spacer(Modifier.width(32.dp))

        // --- Right column: forms on top (buttons always visible), then the lists ------------------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (!state.accountLoaded) {
                // still reading the saved account (a fraction of a second)
            } else if (state.account == null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Sign in to add friends", style = MaterialTheme.typography.titleLarge)
                    Gap(4.dp)
                    Text(
                        "Your camera image is never shared, only that a 10-minute alert happened and your challenge results.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(16.dp)
                    SignInForm(state.signingIn, viewModel::signIn)
                }
            } else {
                AddFriendForm(state.addingFriend, state.info, viewModel::addFriend)
                Gap(16.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Your people", Modifier.weight(1f))
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextLink("Refresh", onClick = viewModel::refresh)
                    }
                }
                FriendLists(state, viewModel)
            }
        }
    }
}

@Composable
private fun FriendLists(state: FriendsUiState, viewModel: FriendsViewModel) {
    if (state.friendships.isEmpty() && !state.loading) {
        Gap(24.dp)
        Text("No friends yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add someone by the email they signed in with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.incoming.isNotEmpty()) {
            item { ListHeader("Requests") }
            items(state.incoming, key = { it.id }) { f ->
                FriendRow(f, subtitle = "Wants to be your posture buddy") {
                    SmallButton("Accept", onClick = { viewModel.accept(f) }, primary = true, enabled = f.id !in state.busy)
                    SmallButton("Decline", onClick = { viewModel.remove(f) }, enabled = f.id !in state.busy)
                }
            }
        }
        if (state.friends.isNotEmpty()) {
            item { ListHeader("Friends") }
            items(state.friends, key = { it.id }) { f ->
                FriendRow(f, subtitle = if (f.favorite) "Favorite · gets your alerts" else f.friend.email.orEmpty()) {
                    FavoriteStar(f.favorite, enabled = f.id !in state.busy) { viewModel.toggleFavorite(f) }
                    SmallButton("Challenge", onClick = { viewModel.challenge(f) }, primary = true, enabled = f.id !in state.busy)
                }
            }
        }
        if (state.outgoing.isNotEmpty()) {
            item { ListHeader("Sent") }
            items(state.outgoing, key = { it.id }) { f ->
                FriendRow(f, subtitle = "Waiting for them to accept") {
                    SmallButton("Cancel", onClick = { viewModel.remove(f) }, enabled = f.id !in state.busy)
                }
            }
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun FriendRow(f: Friendship, subtitle: String, actions: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(f.friend.shownName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.friend.shownName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** ★ = favorite (gets my alerts), ☆ = not. */
@Composable
private fun FavoriteStar(favorite: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Text(
        if (favorite) "★" else "☆",
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = "Favorite", onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.headlineSmall,
        color = if (favorite) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Test sign-in for the DEV_AUTH server: name + email, no password.
 * Replace with Firebase email/password sign-in once Nuno's Firebase project exists.
 */
@Composable
private fun SignInForm(signingIn: Boolean, onSignIn: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    // Landscape: everything on one row, so the button can't end up below the fold
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, modifier = Modifier.weight(1f),
            label = { Text("Your name") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it }, modifier = Modifier.weight(1.3f),
            label = { Text("Email") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSignIn(name, email) }),
        )
        PrimaryButton(
            if (signingIn) "Signing in…" else "Sign in",
            onClick = { focus.clearFocus(); onSignIn(name, email) },
            modifier = Modifier.padding(top = 8.dp), // line up with the text boxes (their label sits above)
            enabled = !signingIn && email.isNotBlank(),
        )
    }
    Gap(6.dp)
    Text(
        "Test sign-in (server in dev mode). Real accounts come with Firebase.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AddFriendForm(adding: Boolean, info: String?, onAdd: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Clear the field after a successful request
    LaunchedEffect(info) { if (info?.startsWith("Request sent") == true) email = "" }

    val focus = LocalFocusManager.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = email, onValueChange = { email = it }, modifier = Modifier.weight(1f),
            label = { Text("Add a friend by email") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { focus.clearFocus(); onAdd(email) }),
        )
        PrimaryButton(
            if (adding) "Sending…" else "Send request",
            onClick = { focus.clearFocus(); onAdd(email) },
            modifier = Modifier.padding(top = 8.dp),
            enabled = !adding && email.isNotBlank(),
        )
    }
}
