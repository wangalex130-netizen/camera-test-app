package com.example.cameratest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.cameratest.ui.navigation.AppNav
import com.example.cameratest.ui.theme.CameraTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraTestTheme {
                AppNav()
            }
        }
    }
}
