package com.familyverify.demo;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Demo phones: keep the screen on so alerts are seen immediately
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Stay connected to the family relay while another app (WhatsApp) is in front
        RelayKeepAliveService.start(this);
    }

    @Override
    public void onDestroy() {
        // App closed for good (swiped away / back out): drop the ongoing notification
        if (isFinishing()) RelayKeepAliveService.stop(this);
        super.onDestroy();
    }
}
