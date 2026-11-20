package com.familyverify.demo;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeRelayPlugin.class);
        super.onCreate(savedInstanceState);
        // Demo phones: keep the screen on so alerts are seen immediately
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Owns the connection to the family relay, so it keeps working while another app (WhatsApp) is in front
        RelayKeepAliveService.start(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        RelayKeepAliveService.appVisible = true;
        RelayKeepAliveService.clearPopups(this);   // the in-app card takes over
    }

    @Override
    public void onPause() {
        RelayKeepAliveService.appVisible = false;
        super.onPause();
    }

    @Override
    public void onDestroy() {
        // App closed for good (swiped away / back out): drop the ongoing notification and the connection
        if (isFinishing()) RelayKeepAliveService.stop(this);
        super.onDestroy();
    }
}
