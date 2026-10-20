package com.vowi.vowidroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A round letter tile: the first letter of a name, or a person icon for bare numbers. */
@Composable
fun Avatar(name: String?, key: String, size: Dp = 40.dp, modifier: Modifier = Modifier, photo: ImageBitmap? = null) {
    if (photo != null) {
        // The contact's own photo, cropped to a circle like the stock apps.
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    val color = Color(LetterTile.color(key))
    val letter = LetterTile.letter(name)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter.toString(),
                color = Color.White,
                fontSize = (size.value * 0.45f).sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
        }
    }
}
