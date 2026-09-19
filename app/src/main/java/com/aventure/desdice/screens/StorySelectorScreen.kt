package com.aventure.desdice.screens

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aventure.desdice.model.Story
import com.aventure.desdice.viewmodel.GameViewModel

@Composable
fun StorySelectorScreen(
    viewModel: GameViewModel,
    onStorySelected: (String) -> Unit,
    onNewStoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stories by viewModel.stories.collectAsState()
    val currentStorySlug by viewModel.currentStorySlug.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Choisir une histoire",
            style = MaterialTheme.typography.headlineSmall
        )

        if (stories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(stories) { story ->
                    StoryItem(
                        story = story,
                        isSelected = story.slug == currentStorySlug,
                        onClick = {
                            viewModel.selectStory(story.slug)
                            onStorySelected(story.slug)
                        },
                        onDeleteClick = { viewModel.deleteStory(story.slug) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = onNewStoryClick, modifier = Modifier.weight(1f)) {
                    Text("Nouvelle histoire")
                }
            }
        }
    }
}

@Composable
private fun StoryItem(
    story: Story,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bgImage = Base64.decode(story.bgImageB64, Base64.DEFAULT)

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .aspectRatio(1f)
        ) {
            AsyncImage(
                model = bgImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = story.subtitle,
                style = MaterialTheme.typography.bodyMedium
            )
            if (isSelected) {
                Text(
                    text = "Sélectionnée",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (story.isCustom) {
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Supprimer"
                )
            }
        }
    }
}