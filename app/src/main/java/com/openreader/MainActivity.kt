package com.openreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenReaderApp() }
    }
}

private data class ShelfItem(val title: String, val subtitle: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenReaderApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Библиотека", "Избранное", "Папки", "Прочитано")
    val examples = listOf(
        ShelfItem("Добро пожаловать в OpenReader", "Здесь появятся ваши книги"),
        ShelfItem("Офлайн-чтение", "Без рекламы и подписок"),
        ShelfItem("Ваша библиотека", "Импортируйте EPUB, FB2, PDF и TXT")
    )

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MenuBook, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text("OpenReader")
                                    Text("Читай в своём ритме", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        },
                        actions = {
                            Button(onClick = { }) {
                                Icon(Icons.Default.Search, contentDescription = "Поиск")
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        val icons = listOf(Icons.Default.Book, Icons.Default.Favorite, Icons.Default.Folder, Icons.Default.MenuBook)
                        tabs.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Icon(icons[index], contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            when (selectedTab) {
                                1 -> "Избранное"
                                2 -> "Папки и коллекции"
                                3 -> "Прочитанные книги"
                                else -> "Моя библиотека"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Твоя библиотека — твои правила", style = MaterialTheme.typography.titleLarge)
                                Text("OpenReader создаётся как бесплатная офлайн-читалка. Сейчас это первый каркас приложения.")
                                Button(onClick = { }) { Text("Добавить книгу — скоро") }
                            }
                        }
                    }
                    items(examples) { book ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(38.dp))
                                Spacer(Modifier.size(14.dp))
                                Column {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                                    Text(book.subtitle, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
