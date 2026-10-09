package com.sal7one.transiber.home

import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.ui.rememberIsTv
import com.sal7one.transiber.ui.tvSafeArea

/**
 * Live captions is the only service, so Home is a single card rather than the
 * old swipe carousel: paging arrows and page dots cannot be reached with a
 * D-pad and had nothing left to page between.
 */
@Composable
internal fun HomeScreen(onCaptions: () -> Unit, entryRevision: Int = 0, onSetup: () -> Unit) {
    val context = LocalContext.current
    val isTv = rememberIsTv()
    // A remote has no pointer, so something must hold focus before the first
    // key arrives or the D-pad appears dead.
    val openCaptions = remember { FocusRequester() }
    LaunchedEffect(entryRevision) { runCatching { openCaptions.requestFocus() } }

    Column(
        Modifier.fillMaxSize().tvSafeArea(isTv),
        verticalArrangement = if (isTv) Arrangement.Center else Arrangement.Top,
    ) {
        HomeEasySetupEntry(onSetup = onSetup)
        HomeServiceCard(
            service = HomeService.CAPTIONS,
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = if (isTv) 720.dp else 560.dp)
                .then(if (isTv) Modifier else Modifier.weight(1f)),
            actionFocusRequester = openCaptions,
            compactArtwork = isTv,
        ) {
            HomeServiceStore.remember(context, HomeService.CAPTIONS)
            onCaptions()
        }
    }
}
