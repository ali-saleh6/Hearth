package com.sal7one.transiber.home

import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.ui.tvFocusHighlight

@Composable
internal fun HomeServiceCard(
    service: HomeService,
    modifier: Modifier = Modifier,
    actionFocusRequester: FocusRequester? = null,
    // Ten feet away the artwork matters less than a readable, reachable action.
    compactArtwork: Boolean = false,
    onOpen: () -> Unit,
) {
    val uiText = rememberUiText()

    val artwork = when (service) {
        HomeService.CAPTIONS -> R.drawable.home_captions
    }
    ElevatedCard(shape = RoundedCornerShape(32.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 9.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Image(painterResource(artwork), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(if (compactArtwork) 2.6f else 1.08f))
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText.title(service), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(uiText.description(service), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Button(onClick = onOpen, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .then(actionFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .tvFocusHighlight(RoundedCornerShape(18.dp))) {
                    Text(uiText.action(service), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
