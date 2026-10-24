package com.rx8.dash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rx8.dash.bt.BluetoothDevices
import com.rx8.dash.service.ServiceStarter
import com.rx8.dash.ui.Rx8DashApp

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as App).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Rx8DashApp(graph)
        }
    }

    override fun onStart() {
        super.onStart()
        graph.mediaController.start()
    }

    override fun onResume() {
        super.onResume()
        // Also covers the "tap to connect" notification: it opens this Activity, which is a foreground start.
        // Without Bluetooth permission tryStart runs the hub in-process (demo mode) and returns false.
        ServiceStarter.tryStart(this)
    }

    override fun onStop() {
        graph.mediaController.stop()
        super.onStop()
    }
}
