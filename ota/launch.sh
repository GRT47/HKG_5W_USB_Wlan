#!/system/bin/sh
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar

if [ -d "/data/nemesis/apps/usbwifi.hotspot" ]; then
    APP_DIR="/data/nemesis/apps/usbwifi.hotspot"
else
    APP_DIR="/data/nemesis/packages/usbwifi.hotspot"
fi
TMP_DIR="/data/local/tmp/usbwifi_hotspot"
mkdir -p "$TMP_DIR" /data/dalvik-cache 2>/dev/null
chmod 777 "$TMP_DIR" 2>/dev/null
setprop persist.sys.usbwifi.hotspot 1

# 1. Signal the Status Dashboard Overlay to appear immediately on screen
echo 1 > "$TMP_DIR/show_ui" 2>/dev/null
log -t USBWifiHotspot "Manual Launch triggered -> showing USB Wi-Fi Status Overlay"

# 2. Ensure background hotspot service daemon is running (pure mksh check)
svc_alive=0
if [ -f "$TMP_DIR/daemon.pid" ]; then
    read -r _dpid < "$TMP_DIR/daemon.pid" 2>/dev/null
    if [ -n "$_dpid" ] && [ -d "/proc/$_dpid" ] && kill -0 "$_dpid" 2>/dev/null; then
        svc_alive=1
    fi
fi
if [ "$svc_alive" = "0" ]; then
    /system/bin/sh "$APP_DIR/svc/usbwifi-hotspot-daemon.sh" </dev/null >>"$TMP_DIR/daemon_stdout.log" 2>&1 &
fi

# 3. Ensure Java Status UI overlay process is running (pure mksh check + FileLock in Java)
ui_alive=0
if [ -f "$TMP_DIR/ui_java.pid" ]; then
    read -r _upid < "$TMP_DIR/ui_java.pid" 2>/dev/null
    if [ -n "$_upid" ] && [ -d "/proc/$_upid" ] && kill -0 "$_upid" 2>/dev/null; then
        ui_alive=1
    fi
fi
if [ "$ui_alive" = "0" ]; then
    CLASSPATH="$APP_DIR/bin/usbwifistatus.jar" /system/bin/app_process /system/bin com.hkg.usbwifi.UsbWifiStatusMain daemon "$APP_DIR" --show </dev/null >>"$TMP_DIR/ui.log" 2>&1 &
    echo $! > "$TMP_DIR/ui_java.pid" 2>/dev/null
fi

exit 0
