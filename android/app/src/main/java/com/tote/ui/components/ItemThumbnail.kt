package com.tote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tote.data.remote.ItemDto
import com.tote.data.remote.PhotoUrls
import com.tote.ui.theme.ToteTheme

/**
 * The item's photograph, wherever an item is listed.
 *
 * A catalog of physical objects is recognised by sight long before it is read: someone scrolling
 * a bin's contents is matching pictures against a memory of the thing, and a column of identical
 * text rows makes them read every line. Two items called "Toddler Bed Comforter" are also
 * indistinguishable as text and obviously different as pictures — which is exactly the moment
 * someone notices they filed one twice.
 *
 * Driven by [ItemDto.photoCount] rather than by trying a URL and seeing what happens: an item
 * added by hand has no photograph, and firing a request per row to discover that would put a
 * 404 per item on the attic's Wi-Fi and render whatever a failure looks like. No photo gets the
 * placeholder, which keeps every row the same height — a list that jumps as images resolve is
 * harder to scan than one with a few empty frames.
 */
@Composable
fun ItemThumbnail(
    item: ItemDto,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val colors = ToteTheme.colors
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            // The cleaned photo is a transparent cutout, so it sits ON this surface rather than
            // carrying a white card of its own.
            .background(colors.panelHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (item.photoCount > 0) {
            AsyncImage(
                model = PhotoUrls.item(item.id, 0),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = colors.hairlineStrong,
            )
        }
    }
}
