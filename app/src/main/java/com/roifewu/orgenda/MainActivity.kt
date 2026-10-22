package com.roifewu.orgenda

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import com.roifewu.orgenda.ui.OrgendaApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private lateinit var model: OrgendaViewModel
    private val launchIntent = MutableStateFlow<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[OrgendaViewModel::class.java]
        launchIntent.value = intent
        setContent { OrgendaApp(model, launchIntent) { launchIntent.value = null } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.app.ready.await()
                while (isActive) {
                    try { model.repository.sync(pull = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { model.repository.error(e) }
                    delay(30_000)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); launchIntent.value = intent }
    override fun onStop() { model.flush(); super.onStop() }
}
