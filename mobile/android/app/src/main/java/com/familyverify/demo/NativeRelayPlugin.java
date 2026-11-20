package com.familyverify.demo;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Bridge between the web page (www/app.js) and RelayKeepAliveService, which owns the relay socket.
 *   JS → native: connect({url, hello}), send({data}), disconnect()
 *   native → JS: "message" {data}, "state" {open, code, reason}
 * Events are retained until the page has a listener, so nothing is lost while the page is (re)loading.
 */
@CapacitorPlugin(name = "NativeRelay")
public class NativeRelayPlugin extends Plugin implements RelayKeepAliveService.Listener {

    @Override
    public void load() {
        RelayKeepAliveService.listener = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (RelayKeepAliveService.listener == this) RelayKeepAliveService.listener = null;
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String url = call.getString("url");
        String hello = call.getString("hello");
        if (url == null || hello == null) { call.reject("url and hello are required"); return; }
        RelayKeepAliveService.listener = this;
        RelayKeepAliveService.configure(getContext(), url, hello);
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        String data = call.getString("data");
        JSObject ret = new JSObject();
        ret.put("ok", data != null && RelayKeepAliveService.sendText(data));
        call.resolve(ret);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        RelayKeepAliveService.disconnect();
        call.resolve();
    }

    @Override
    public void onRelayMessage(String text) {
        JSObject d = new JSObject();
        d.put("data", text);
        notifyListeners("message", d, true);
    }

    @Override
    public void onRelayState(boolean open, int code, String reason) {
        JSObject d = new JSObject();
        d.put("open", open);
        d.put("code", code);
        d.put("reason", reason == null ? "" : reason);
        notifyListeners("state", d, true);
    }
}
