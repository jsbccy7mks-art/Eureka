package com.zapp.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.zapp.tv.ui.AppViewModel
import com.zapp.tv.ui.ZappRoot
import com.zapp.tv.ui.ZappTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZappTheme {
                ZappRoot(viewModel)
            }
        }
    }
}
