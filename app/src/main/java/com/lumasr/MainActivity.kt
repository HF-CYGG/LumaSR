package com.lumasr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.lumasr.ui.LumaApp
import com.lumasr.ui.LumaViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: LumaViewModel by viewModels {
        LumaViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumaApp(viewModel = viewModel)
        }
    }
}
