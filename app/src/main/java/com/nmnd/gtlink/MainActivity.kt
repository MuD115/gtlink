package com.nmnd.gtlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nmnd.gtlink.service.WatchService
import com.nmnd.gtlink.theme.GTLinkTheme
import com.nmnd.gtlink.ui.AppViewModel
import com.nmnd.gtlink.ui.GtRoot
import com.nmnd.gtlink.ui.canConnectBluetooth

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchService.createChannels(this)

        if (savedInstanceState == null && vm.savedWatch.value != null && canConnectBluetooth(this)) {
            vm.connect()
        }

        setContent {
            GTLinkTheme {
                GtRoot(vm)
            }
        }
    }
}
