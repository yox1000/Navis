package com.navis.pepscout;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.navis.pepscout.plugins.location.LocationPlugin;
import com.navis.pepscout.plugins.heading.HeadingPlugin;
import com.navis.pepscout.plugins.qr.QrPlugin;
import com.navis.pepscout.plugins.cv.CVPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register native plugins
        registerPlugin(LocationPlugin.class);
        registerPlugin(HeadingPlugin.class);
        registerPlugin(QrPlugin.class);
        registerPlugin(CVPlugin.class);
    }
}
