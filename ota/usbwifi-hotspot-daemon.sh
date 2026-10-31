#!/system/bin/sh
# USB Wireless LAN Card Hotspot (AP) & Client (STA) Daemon for HKG Gen5W (v2.0.0)
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
CONF_FILE="$APP_DIR/config/hotspot.conf"
SAVED_STA_FILE="$APP_DIR/config/saved_sta.conf"
MOD_DIR="$APP_DIR/modules"
TMP_DIR="/data/local/tmp/usbwifi_hotspot"
WIFI_WORK_DIR="/data/misc/wifi/usbwifi_hotspot"
mkdir -p "$TMP_DIR" "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/ctrl" "$WIFI_WORK_DIR/wpa_ctrl" /data/dalvik-cache 2>/dev/null
chmod 777 /data/local /data/local/tmp "$TMP_DIR" "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/ctrl" "$WIFI_WORK_DIR/wpa_ctrl" 2>/dev/null
chown -R 1010:1010 "$WIFI_WORK_DIR" 2>/dev/null
touch /data/local/tmp/usb_sta.list 2>/dev/null
chown 1010:1010 /data/local/tmp/usb_sta.list 2>/dev/null
chmod 666 /data/local/tmp/usb_sta.list 2>/dev/null

# Ensure built-in /data/misc/wifi permissions are intact (never chown -R /data/misc/wifi)
if [ -d /data/misc/wifi ]; then
    chown 1010:1010 /data/misc/wifi 2>/dev/null
    chmod 775 /data/misc/wifi 2>/dev/null
    [ -f /data/misc/wifi/softap.conf ] && chown 1000:1000 /data/misc/wifi/softap.conf 2>/dev/null
    [ -f /data/misc/wifi/firmware_path ] && chown 0:0 /data/misc/wifi/firmware_path 2>/dev/null
    for _wf in all_sta.list entropy.bin hostapd.conf wpa_supplicant.conf wpa_supplicant_crc32.conf; do
        [ -f "/data/misc/wifi/$_wf" ] && chown 1000:1010 "/data/misc/wifi/$_wf" 2>/dev/null
    done
fi

# Single-instance guard (pure mksh: checks both daemon.pid and all /proc/[0-9]*/fd/1)
if [ -f "$TMP_DIR/daemon.pid" ]; then
    read -r _old_dpid < "$TMP_DIR/daemon.pid" 2>/dev/null
    if [ -n "$_old_dpid" ] && [ "$_old_dpid" != "$$" ] && [ -d "/proc/$_old_dpid" ] && kill -0 "$_old_dpid" 2>/dev/null; then
        _old_fd1="$(readlink "/proc/$_old_dpid/fd/1" 2>/dev/null)"
        case "$_old_fd1" in
            *usbwifi_hotspot/daemon_stdout.log*) exit 0 ;;
        esac
    fi
fi
for _p in /proc/[0-9]*; do
    _p="${_p#/proc/}"
    [ "$_p" = "$$" ] && continue
    [ "$_p" = "$PPID" ] && continue
    _p_fd1="$(readlink "/proc/$_p/fd/1" 2>/dev/null)"
    case "$_p_fd1" in
        *usbwifi_hotspot/daemon_stdout.log*)
            if kill -0 "$_p" 2>/dev/null; then
                exit 0
            fi
            ;;
    esac
done
# Force stdout/stderr to daemon_stdout.log even when NEMESIS launches service with /dev/null
exec >>"$TMP_DIR/daemon_stdout.log" 2>&1
echo "$$" > "$TMP_DIR/daemon.pid" 2>/dev/null

load_config() {
    USB_VID="0bda"
    USB_PID="b812"
    MODULE_NAME="88x2bu.ko"
    MODULE_ARGS="rtw_power_mgnt=0 rtw_ips_mode=0 rtw_enusbss=0 rtw_en_napi=0 rtw_en_gro=0 ifname=rtwlan%d if2name=rtwlan%d"
    WIFI_MODE="ap"
    AP_SSID="HKG_USB_Hotspot"
    AP_PASS="12345678"
    AP_HW_MODE="g"
    AP_CHANNEL="6"
    STA_SSID=""
    STA_PASS=""
    FORCE_SUBNET="AUTO"
    AP_IP="192.168.0.1"
    AP_PREFIX="24"
    DHCP_START="192.168.0.10"
    DHCP_END="192.168.0.100"
    ENABLE_LTE_NAT="1"
    [ -f "$CONF_FILE" ] && . "$CONF_FILE"
}
load_config

log_info() {
    echo "[usbwifi-hotspot] $*"
    log -t USBWifiHotspot "$*" 2>/dev/null
    echo "$(date) $*" >> "$TMP_DIR/daemon.log" 2>/dev/null
}

save_active_snapshot() {
    dmesg > "$TMP_DIR/dmesg_active.txt" 2>/dev/null
    ip addr show > "$TMP_DIR/ip_addr_active.txt" 2>/dev/null
    ip route show table all > "$TMP_DIR/ip_route_active.txt" 2>/dev/null
    ip rule show > "$TMP_DIR/ip_rule_active.txt" 2>/dev/null
    iptables -L -n -v > "$TMP_DIR/iptables_filter_active.txt" 2>/dev/null
    iptables -t nat -L -n -v > "$TMP_DIR/iptables_nat_active.txt" 2>/dev/null
    getprop | grep -iE "(dns|rmnet|net\.|ril|gsm|dhcp)" > "$TMP_DIR/net_props_active.txt" 2>/dev/null
    logcat -d -v time 2>/dev/null | grep -iE "(hostapd|dnsmasq|wpa_supplicant|USBWifi|rtwlan|wlan|Softap|Tether|Netd|Dhcp|88x2bu|8851bu)" > "$TMP_DIR/logcat_active.txt" 2>/dev/null
}

sync_logs_to_usb() {
    if [ -d /storage/usb0 ] && [ -w /storage/usb0 ]; then
        local _ulog="/storage/usb0/usbwifi_hotspot_log"
        mkdir -p "$_ulog" 2>/dev/null
        cp "$TMP_DIR/"*.log "$_ulog/" 2>/dev/null
        cp "$TMP_DIR/"*.txt "$_ulog/" 2>/dev/null
        cp "$TMP_DIR/"*.conf "$_ulog/" 2>/dev/null
        cp "$TMP_DIR/"*.leases "$_ulog/" 2>/dev/null
        [ -f /data/local/tmp/usb_sta.list ] && cp /data/local/tmp/usb_sta.list "$_ulog/" 2>/dev/null
        ip addr show > "$_ulog/ip_addr.txt" 2>/dev/null
        ip route show table all > "$_ulog/ip_route.txt" 2>/dev/null
        ip rule show > "$_ulog/ip_rule.txt" 2>/dev/null
        cat /proc/modules > "$_ulog/proc_modules.txt" 2>/dev/null
        cat /proc/net/dev > "$_ulog/proc_net_dev.txt" 2>/dev/null
        ls -l /sys/class/net > "$_ulog/sys_class_net.txt" 2>/dev/null
        dmesg > "$_ulog/dmesg_latest.txt" 2>/dev/null
        sync
    fi
}

stop_sta_daemons() {
    local _wpid _cpid _w _p _fd1 _if
    if [ -f "$TMP_DIR/wpa_supplicant.pid" ]; then
        read -r _wpid < "$TMP_DIR/wpa_supplicant.pid" 2>/dev/null
        if [ -n "$_wpid" ] && [ -d "/proc/$_wpid" ]; then
            kill "$_wpid" 2>/dev/null
            for _w in 1 2 3; do
                [ -d "/proc/$_wpid" ] || break
                sleep 1
            done
        fi
        rm -f "$TMP_DIR/wpa_supplicant.pid" 2>/dev/null
    fi
    if [ -f "$TMP_DIR/dhcpcd.pid" ]; then
        read -r _cpid < "$TMP_DIR/dhcpcd.pid" 2>/dev/null
        [ -n "$_cpid" ] && kill "$_cpid" 2>/dev/null
        rm -f "$TMP_DIR/dhcpcd.pid" 2>/dev/null
    fi
    for _if in "$ACTIVE_IFACE" rtwlan0 rtwlan1 wlan1; do
        [ -n "$_if" ] || continue
        if [ -f "/data/misc/dhcp/dhcpcd-${_if}.pid" ]; then
            read -r _cpid < "/data/misc/dhcp/dhcpcd-${_if}.pid" 2>/dev/null
            [ -n "$_cpid" ] && kill "$_cpid" 2>/dev/null
            rm -f "/data/misc/dhcp/dhcpcd-${_if}.pid" 2>/dev/null
        fi
    done
    for _p in /proc/[0-9]*; do
        _p="${_p#/proc/}"
        [ "$_p" = "$$" ] && continue
        _fd1="$(readlink "/proc/$_p/fd/1" 2>/dev/null)"
        case "$_fd1" in
            *usbwifi_hotspot/wpa_supplicant.log*|*usbwifi_hotspot/dhcpcd.log*)
                kill "$_p" 2>/dev/null
                ;;
        esac
    done
    rm -f "$TMP_DIR/wpa_status.txt" "$TMP_DIR/wpa_signal.txt" 2>/dev/null
    ip rule del pref 9790 2>/dev/null
    ip rule del pref 9800 2>/dev/null
    ip route flush table 62 2>/dev/null
}

stop_ap_daemons() {
    local _hpid _dpid _spid _w _if _p _fd0 _fd1
    if [ -f "$TMP_DIR/hostapd.pid" ]; then
        read -r _hpid < "$TMP_DIR/hostapd.pid" 2>/dev/null
        if [ -n "$_hpid" ] && [ -d "/proc/$_hpid" ]; then
            kill "$_hpid" 2>/dev/null
            for _w in 1 2 3; do
                [ -d "/proc/$_hpid" ] || break
                sleep 1
            done
        fi
        rm -f "$TMP_DIR/hostapd.pid" 2>/dev/null
    fi
    if [ -f "$TMP_DIR/dnsmasq.pid" ]; then
        read -r _dpid < "$TMP_DIR/dnsmasq.pid" 2>/dev/null
        [ -n "$_dpid" ] && kill "$_dpid" 2>/dev/null
        rm -f "$TMP_DIR/dnsmasq.pid" 2>/dev/null
    fi
    if [ -f "$TMP_DIR/dnsmasq_stdin.pid" ]; then
        read -r _spid < "$TMP_DIR/dnsmasq_stdin.pid" 2>/dev/null
        [ -n "$_spid" ] && kill "$_spid" 2>/dev/null
        rm -f "$TMP_DIR/dnsmasq_stdin.pid" 2>/dev/null
    fi
    for _p in /proc/[0-9]*; do
        _p="${_p#/proc/}"
        [ "$_p" = "$$" ] && continue
        _fd0="$(readlink "/proc/$_p/fd/0" 2>/dev/null)"
        _fd1="$(readlink "/proc/$_p/fd/1" 2>/dev/null)"
        case "$_fd0 $_fd1" in
            *usbwifi_hotspot/hostapd.log*|*usbwifi_hotspot/dnsmasq.log*|*usbwifi_hotspot/dnsmasq.fifo*)
                kill "$_p" 2>/dev/null
                ;;
        esac
    done
    rm -f "$TMP_DIR/dnsmasq_shared.pid" 2>/dev/null
    for _if in "$ACTIVE_IFACE" rtwlan0 rtwlan1 wlan1; do
        [ -n "$_if" ] || continue
        iptables -D INPUT -i "$_if" -j ACCEPT 2>/dev/null
        iptables -D OUTPUT -o "$_if" -j ACCEPT 2>/dev/null
        iptables -D FORWARD -i "$_if" -j ACCEPT 2>/dev/null
        iptables -D FORWARD -o "$_if" -j ACCEPT 2>/dev/null
        iptables -t nat -D PREROUTING -i "$_if" -p udp --dport 53 -j REDIRECT --to-ports 1053 2>/dev/null
        iptables -t nat -D PREROUTING -i "$_if" -p tcp --dport 53 -j REDIRECT --to-ports 1053 2>/dev/null
        [ -n "$ACTIVE_IP" ] && [ -n "$ACTIVE_DNS" ] && iptables -t nat -D PREROUTING -i "$_if" -d "$ACTIVE_IP" -p udp --dport 53 -j DNAT --to-destination "$ACTIVE_DNS:53" 2>/dev/null
        [ -n "$ACTIVE_IP" ] && [ -n "$ACTIVE_DNS" ] && iptables -t nat -D PREROUTING -i "$_if" -d "$ACTIVE_IP" -p tcp --dport 53 -j DNAT --to-destination "$ACTIVE_DNS:53" 2>/dev/null
        iptables -t nat -D PREROUTING -i "$_if" -p udp --dport 67 -j REDIRECT --to-ports 1067 2>/dev/null
        [ -n "$ACTIVE_IP" ] && iptables -t nat -D POSTROUTING -o "$_if" -p udp --sport 1067 -j SNAT --to-source "$ACTIVE_IP:67" 2>/dev/null
    done
    ip rule del pref 9900 2>/dev/null
    ip rule del pref 9910 2>/dev/null
    ip rule del pref 17900 2>/dev/null
    ip rule del pref 17950 2>/dev/null
    ip rule del pref 18000 2>/dev/null
    ip rule del pref 18010 2>/dev/null
}

on_stop() {
    log_info "Stopping USB Wi-Fi service"
    stop_ap_daemons
    stop_sta_daemons
    rm -f "$TMP_DIR/daemon.pid" 2>/dev/null
    exit 0
}
trap 'on_stop' TERM INT

detect_usb_dongle_family() {
    local _d _v _p _found_cdrom=0
    for _d in /sys/bus/usb/devices/*; do
        if [ -f "$_d/idVendor" ] && [ -f "$_d/idProduct" ]; then
            read -r _v < "$_d/idVendor" 2>/dev/null
            read -r _p < "$_d/idProduct" 2>/dev/null
            case "${_v}:${_p}" in
                0bda:b831|0bda:b851|0bda:b832|3625:010b|2357:0142|7392:e611|2001:332a|2c4e:0128|056e:4027|056e:4028|0411:03ed)
                    echo "8851bu"
                    return 0
                    ;;
                0bda:b812|0bda:b82c|0bda:b81a|0bda:b820|0bda:b82b|2357:012d|2357:0138|2357:0115|0b05:184c|0b05:1841|0b05:19aa|7392:b822|7392:c822|2001:331c|2001:331e|0846:9055|13b1:0043|056e:4011|"${USB_VID}:${USB_PID}")
                    echo "88x2bu"
                    return 0
                    ;;
                0bda:1a2b)
                    _found_cdrom=1
                    ;;
            esac
        fi
    done
    if [ "$_found_cdrom" -eq 1 ]; then
        echo "cdrom_8851bu"
        return 0
    fi
    return 1
}

is_usb_dongle_present() {
    detect_usb_dongle_family >/dev/null 2>&1
}

trigger_usb_modeswitch() {
    local _d _v _p _if_dir _if_id
    for _d in /sys/bus/usb/devices/*; do
        if [ -f "$_d/idVendor" ] && [ -f "$_d/idProduct" ]; then
            read -r _v < "$_d/idVendor" 2>/dev/null
            read -r _p < "$_d/idProduct" 2>/dev/null
            if [ "$_v" = "0bda" ] && [ "$_p" = "1a2b" ]; then
                log_info "Detected Realtek 0bda:1a2b Virtual CD-ROM at ${_d##*/} -> triggering USB modeswitch"
                for _if_dir in "$_d/"*:*; do
                    [ -d "$_if_dir" ] || continue
                    _if_id="${_if_dir##*/}"
                    if [ -e "/sys/bus/usb/drivers/usb-storage/$_if_id" ]; then
                        echo "$_if_id" > /sys/bus/usb/drivers/usb-storage/unbind 2>/dev/null
                    fi
                    if [ -d /sys/bus/usb/drivers/rtl8851bu ]; then
                        echo "$_if_id" > /sys/bus/usb/drivers/rtl8851bu/bind 2>/dev/null
                    fi
                done
            fi
        fi
    done
}

find_usb_wifi_iface() {
    local _net _ifname _netlink _devlink _drvlink _best_if="" _best_idx=-1 _idx
    for _net in /sys/class/net/*; do
        [ -e "$_net" ] || continue
        _ifname="${_net##*/}"
        case "$_ifname" in
            rtwlan*)
                read -r _idx < "$_net/ifindex" 2>/dev/null
                case "$_idx" in
                    [0-9]*)
                        if [ "$_idx" -gt "$_best_idx" ]; then
                            _best_idx="$_idx"
                            _best_if="$_ifname"
                        fi
                        ;;
                    *)
                        [ -z "$_best_if" ] && _best_if="$_ifname"
                        ;;
                esac
                ;;
        esac
    done
    if [ -n "$_best_if" ]; then
        echo "$_best_if"
        return 0
    fi
    for _net in /sys/class/net/*; do
        [ -e "$_net" ] || continue
        _ifname="${_net##*/}"
        case "$_ifname" in
            lo|dummy*|sit*|ip6tnl*|rmnet*|eth0|p2p*) continue ;;
        esac
        if [ -d "$_net/wireless" ] || [ -L "$_net/phy80211" ]; then
            _netlink="$(readlink "$_net" 2>/dev/null)"
            _devlink="$(readlink "$_net/device" 2>/dev/null)"
            _drvlink="$(readlink "$_net/device/driver" 2>/dev/null)"
            case "$_netlink $_devlink $_drvlink" in
                *usb*|*dwc_otg*|*ehci*|*ohci*|*88x2bu*|*8851bu*)
                    echo "$_ifname"
                    return 0
                    ;;
            esac
        fi
    done
    return 1
}

INSMOD_FAILED=0
ACTIVE_IFACE=""
ACTIVE_IP=""
ACTIVE_DNS="8.8.8.8"

load_single_ko() {
    local _mbase="$1" _plat _target_ko _all_args
    if grep -q "^${_mbase} " /proc/modules 2>/dev/null; then
        return 0
    fi
    _plat="$(getprop ro.board.platform 2>/dev/null)"
    _target_ko="$MOD_DIR/${_mbase}.ko"
    if [ -n "$_plat" ] && [ -f "$MOD_DIR/${_mbase}_${_plat}.ko" ]; then
        _target_ko="$MOD_DIR/${_mbase}_${_plat}.ko"
    fi
    [ -f "$_target_ko" ] || return 1
    _all_args="$MODULE_ARGS"
    case "$_all_args" in
        *ifname=*) ;;
        *) _all_args="$_all_args ifname=rtwlan%d if2name=rtwlan%d" ;;
    esac
    log_info "Loading kernel module ${_target_ko##*/} ($_all_args) on $_plat"
    insmod "$_target_ko" $_all_args >>"$TMP_DIR/insmod.log" 2>&1
    if ! grep -q "^${_mbase} " /proc/modules 2>/dev/null; then
        log_info "Retrying insmod ${_target_ko##*/} with default params"
        insmod "$_target_ko" ifname=rtwlan%d if2name=rtwlan%d >>"$TMP_DIR/insmod.log" 2>&1
    fi
    grep -q "^${_mbase} " /proc/modules 2>/dev/null
}

ensure_driver_loaded() {
    local _fam _w _ok=0
    _fam="$(detect_usb_dongle_family 2>/dev/null)"
    if [ "$_fam" = "8851bu" ] || [ "$_fam" = "cdrom_8851bu" ]; then
        if grep -q "^8851bu " /proc/modules 2>/dev/null; then
            _ok=1
        fi
    elif [ "$_fam" = "88x2bu" ]; then
        if grep -q "^88x2bu " /proc/modules 2>/dev/null; then
            _ok=1
        fi
    else
        if grep -q "^88x2bu " /proc/modules 2>/dev/null && { [ ! -f "$MOD_DIR/8851bu.ko" ] || grep -q "^8851bu " /proc/modules 2>/dev/null; }; then
            _ok=1
        fi
    fi
    if [ "$_ok" -eq 1 ] && [ "$_fam" != "cdrom_8851bu" ]; then
        INSMOD_FAILED=0
        return 0
    fi
    if [ "$INSMOD_FAILED" -eq 1 ]; then
        return 1
    fi
    load_single_ko 88x2bu
    load_single_ko 8851bu
    dmesg > "$TMP_DIR/dmesg_after_insmod.txt" 2>/dev/null
    if [ "$_fam" = "cdrom_8851bu" ]; then
        trigger_usb_modeswitch
        for _w in 1 2 3 4 5; do
            sleep 1
            _fam="$(detect_usb_dongle_family 2>/dev/null)"
            [ "$_fam" = "8851bu" ] && break
        done
    fi
    if grep -qE "^(88x2bu|8851bu) " /proc/modules 2>/dev/null; then
        INSMOD_FAILED=0
        if is_usb_dongle_present; then
            for _w in 1 2 3 4 5; do
                [ -n "$(find_usb_wifi_iface)" ] && break
                sleep 1
            done
        fi
        ls -l /sys/class/net > "$TMP_DIR/sys_class_net.txt" 2>/dev/null
        return 0
    fi
    log_info "ERROR: Failed to load USB Wi-Fi kernel modules"
    INSMOD_FAILED=1
    sync_logs_to_usb
    return 1
}

ensure_hostapd_usb_binary() {
    if [ ! -x "$TMP_DIR/hostapd_usb" ]; then
        CLASSPATH="$APP_DIR/bin/usbwifistatus.jar" /system/bin/app_process /system/bin com.hkg.usbwifi.UsbWifiStatusMain patch-hostapd /system/bin/hostapd "$TMP_DIR/hostapd_usb" >/dev/null 2>&1
        if [ ! -f "$TMP_DIR/hostapd_usb" ]; then
            cp /system/bin/hostapd "$TMP_DIR/hostapd_usb" 2>/dev/null
        fi
        chmod 755 "$TMP_DIR/hostapd_usb" 2>/dev/null
    fi
}

start_hotspot_on_iface() {
    local _iface="$1"
    load_config
    local _ip="$AP_IP"
    local _dstart="$DHCP_START"
    local _dend="$DHCP_END"

    if [ "$FORCE_SUBNET" = "192.168.43.1" ]; then
        _ip="192.168.43.1"
        _dstart="192.168.43.10"
        _dend="192.168.43.100"
    elif [ "$FORCE_SUBNET" = "192.168.0.1" ]; then
        _ip="192.168.0.1"
        _dstart="192.168.0.10"
        _dend="192.168.0.100"
    else
        # AUTO mode: avoid subnet conflict if built-in Wi-Fi (wlan0 or bcmdhd.ko) exists on vehicle
        if [ "$_iface" != "wlan0" ] && { [ -d /sys/class/net/wlan0 ] || [ -f /system/wifi/bcmdhd.ko ] || [ -f /lib/modules/bcmdhd.ko ] || [ -f /system/lib/modules/bcmdhd.ko ]; }; then
            _ip="192.168.43.1"
            _dstart="192.168.43.10"
            _dend="192.168.43.100"
        fi
    fi
    ACTIVE_IP="$_ip"

    if [ "$_ip" = "192.168.0.1" ]; then
        if ip addr show dev eth0 2>/dev/null | grep -q "192.168.0."; then
            ip addr flush dev eth0 >/dev/null 2>&1
            ip link set eth0 down >/dev/null 2>&1
        fi
    fi

    stop_sta_daemons
    stop_ap_daemons
    ensure_hostapd_usb_binary

    log_info "Configuring AP on $_iface with $_ip/$AP_PREFIX ($AP_SSID, mode=$AP_HW_MODE, ch=$AP_CHANNEL)"
    # Bring interface UP without toggling DOWN (prevents Realtek USB URB teardown race)
    if ! ip link set "$_iface" up >/dev/null 2>&1; then
        log_info "WARN: ip link set $_iface up failed, retrying in 2s"
        sleep 2
        ip link set "$_iface" up >/dev/null 2>&1 || return 1
    fi
    sleep 2
    ip -4 addr flush dev "$_iface" >/dev/null 2>&1
    ip addr add "$_ip/$AP_PREFIX" broadcast "${_ip%.*}.255" dev "$_iface" >/dev/null 2>&1

    # Prepare /data/misc/wifi/usbwifi_hotspot owned by wifi:wifi (1010:1010)
    mkdir -p "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/ctrl" 2>/dev/null
    if [ ! -f "$WIFI_WORK_DIR/entropy.bin" ]; then
        dd if=/dev/urandom of="$WIFI_WORK_DIR/entropy.bin" bs=256 count=1 >/dev/null 2>&1
    fi
    cat > "$WIFI_WORK_DIR/hostapd.conf" <<EOF
interface=$_iface
driver=nl80211
ctrl_interface=$WIFI_WORK_DIR/ctrl
ssid=$AP_SSID
channel=$AP_CHANNEL
ieee80211n=1
hw_mode=$AP_HW_MODE
ignore_broadcast_ssid=0
wpa=2
rsn_pairwise=CCMP
wpa_passphrase=$AP_PASS
EOF
    chown -R 1010:1010 "$WIFI_WORK_DIR" 2>/dev/null
    chmod 777 "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/ctrl" "$TMP_DIR" 2>/dev/null
    chmod 666 "$WIFI_WORK_DIR/hostapd.conf" "$WIFI_WORK_DIR/entropy.bin" /data/local/tmp/usb_sta.list 2>/dev/null
    cp "$WIFI_WORK_DIR/hostapd.conf" "$TMP_DIR/hostapd.conf" 2>/dev/null
    touch "$TMP_DIR/hostapd.log" 2>/dev/null
    chmod 666 "$TMP_DIR/hostapd.log" 2>/dev/null

    "$TMP_DIR/hostapd_usb" -e "$WIFI_WORK_DIR/entropy.bin" "$WIFI_WORK_DIR/hostapd.conf" >> "$TMP_DIR/hostapd.log" 2>&1 &
    echo $! > "$TMP_DIR/hostapd.pid"
    sleep 2

    # Re-ensure IP address in case hostapd reset interface state on startup
    if ! ip -4 addr show dev "$_iface" 2>/dev/null | grep -q "$_ip/"; then
        ip link set "$_iface" up >/dev/null 2>&1
        ip addr add "$_ip/$AP_PREFIX" broadcast "${_ip%.*}.255" dev "$_iface" >/dev/null 2>&1
    fi

    ip route replace "${_ip%.*}.0/$AP_PREFIX" dev "$_iface" src "$_ip" >/dev/null 2>&1
    ip route replace "${_ip%.*}.0/$AP_PREFIX" dev "$_iface" table 60 >/dev/null 2>&1
    ip route replace "${_ip%.*}.0/$AP_PREFIX" dev "$_iface" table 61 >/dev/null 2>&1

    # Gather active LTE carrier DNS servers (e.g. SKT net.rmnet1.dns1=172.25.227.37, dns2=172.19.140.37)
    local _dns_list="" _primary_dns="" _dprop _dval
    for _dprop in net.rmnet1.dns1 net.rmnet1.dns2 net.dns1 net.dns2 net.rmnet0.dns1 net.rmnet0.dns2; do
        _dval="$(getprop $_dprop 2>/dev/null)"
        case "$_dval" in
            [0-9]*.[0-9]*.[0-9]*.[0-9]*)
                if [ "$_dval" != "0.0.0.0" ] && [ "$_dval" != "127.0.0.1" ]; then
                    [ -z "$_primary_dns" ] && _primary_dns="$_dval"
                    case ",$_dns_list," in
                        *",$_dval,"*) ;;
                        *)
                            if [ -z "$_dns_list" ]; then
                                _dns_list="$_dval"
                            else
                                _dns_list="$_dns_list,$_dval"
                            fi
                            ;;
                    esac
                fi
                ;;
        esac
    done
    [ -z "$_primary_dns" ] && _primary_dns="8.8.8.8"
    ACTIVE_DNS="$_primary_dns"
    if [ -n "$_dns_list" ]; then
        _dns_list="$_dns_list,8.8.8.8,168.126.63.1"
    else
        _dns_list="8.8.8.8,168.126.63.1"
    fi

    # Isolated DHCP setup (NEVER call ndc tether): run dedicated dnsmasq with --port=0 & --dhcp-alternate-port=1067,68.
    # 88x2bu driver translates DHCP UDP port 67 <-> 1067 in-driver without altering broadcast daddr 255.255.255.255.
    iptables -D INPUT -i "$_iface" -j ACCEPT 2>/dev/null
    iptables -D OUTPUT -o "$_iface" -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -i "$_iface" -j ACCEPT 2>/dev/null
    iptables -I OUTPUT 1 -o "$_iface" -j ACCEPT 2>/dev/null
    iptables -t nat -D PREROUTING -i "$_iface" -d "$_ip" -p udp --dport 53 -j DNAT --to-destination "$_primary_dns:53" 2>/dev/null
    iptables -t nat -D PREROUTING -i "$_iface" -d "$_ip" -p tcp --dport 53 -j DNAT --to-destination "$_primary_dns:53" 2>/dev/null
    iptables -t nat -I PREROUTING 1 -i "$_iface" -d "$_ip" -p udp --dport 53 -j DNAT --to-destination "$_primary_dns:53" 2>/dev/null
    iptables -t nat -I PREROUTING 1 -i "$_iface" -d "$_ip" -p tcp --dport 53 -j DNAT --to-destination "$_primary_dns:53" 2>/dev/null
    iptables -t nat -D PREROUTING -i "$_iface" -p udp --dport 67 -j REDIRECT --to-ports 1067 2>/dev/null
    iptables -t nat -D POSTROUTING -o "$_iface" -p udp --sport 1067 -j SNAT --to-source "$_ip:67" 2>/dev/null

    if [ "$ENABLE_LTE_NAT" = "1" ]; then
        echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null
        iptables -D FORWARD -i "$_iface" -j ACCEPT 2>/dev/null
        iptables -D FORWARD -o "$_iface" -j ACCEPT 2>/dev/null
        iptables -I FORWARD 1 -i "$_iface" -j ACCEPT 2>/dev/null
        iptables -I FORWARD 1 -o "$_iface" -j ACCEPT 2>/dev/null
        iptables -t nat -D POSTROUTING -s "${_ip%.*}.0/$AP_PREFIX" -o rmnet+ -j MASQUERADE 2>/dev/null
        iptables -t nat -I POSTROUTING 1 -s "${_ip%.*}.0/$AP_PREFIX" -o rmnet+ -j MASQUERADE 2>/dev/null
        iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        iptables -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null
        ip rule del pref 9900 2>/dev/null
        ip rule del pref 9910 2>/dev/null
        ip rule del pref 17900 2>/dev/null
        ip rule del pref 17950 2>/dev/null
        ip rule del pref 18000 2>/dev/null
        ip rule del pref 18010 2>/dev/null
        local _up_tbl="60" _alt_tbl="61"
        if ip route show table 60 2>/dev/null | grep -q "^default .* dev rmnet1"; then
            _up_tbl="60"
            _alt_tbl="61"
        elif ip route show table 61 2>/dev/null | grep -q "^default .* dev rmnet1"; then
            _up_tbl="61"
            _alt_tbl="60"
        elif ping -c 1 -W 1 -I rmnet1 8.8.8.8 >/dev/null 2>&1; then
            _up_tbl="60"
            _alt_tbl="61"
        elif ping -c 1 -W 1 -I rmnet0 8.8.8.8 >/dev/null 2>&1; then
            _up_tbl="61"
            _alt_tbl="60"
        fi
        # Priority 9900 & 9910 run BEFORE Android netd rule 10000 (from all fwmark 0x0/0xffff lookup 60)
        ip rule del pref 9850 2>/dev/null
        ip rule del pref 9851 2>/dev/null
        ip rule del pref 9852 2>/dev/null
        ip rule add pref 9850 to 8.8.8.8/32 lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 9851 to 1.1.1.1/32 lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 9852 to 185.199.108.0/22 lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 9900 to "${_ip%.*}.0/$AP_PREFIX" lookup main 2>/dev/null
        ip rule add pref 9910 from "${_ip%.*}.0/$AP_PREFIX" lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 17900 from "${_ip%.*}.0/$AP_PREFIX" to "${_ip%.*}.0/$AP_PREFIX" lookup main 2>/dev/null
        ip rule add pref 17950 iif "$_iface" lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 18000 from "${_ip%.*}.0/$AP_PREFIX" lookup "$_up_tbl" 2>/dev/null
        ip rule add pref 18010 from "${_ip%.*}.0/$AP_PREFIX" lookup "$_alt_tbl" 2>/dev/null
        local _up_line _up_gw _up_dev
        _up_line="$(ip route show table $_up_tbl 2>/dev/null | grep '^default via ')"
        set -- $_up_line
        _up_gw="$3"
        _up_dev="$5"
        if [ -n "$_up_gw" ] && [ -n "$_up_dev" ]; then
            ip route replace default via "$_up_gw" dev "$_up_dev" metric 999 table default >/dev/null 2>&1
        fi
    fi

    rm -f "$TMP_DIR/dnsmasq.fifo" 2>/dev/null
    mknod "$TMP_DIR/dnsmasq.fifo" p 2>/dev/null || mkfifo "$TMP_DIR/dnsmasq.fifo" 2>/dev/null
    ( while true; do sleep 3600; done ) > "$TMP_DIR/dnsmasq.fifo" &
    echo $! > "$TMP_DIR/dnsmasq_stdin.pid"
    if [ "$ENABLE_LTE_NAT" = "1" ]; then
        /system/bin/dnsmasq --keep-in-foreground --no-resolv --no-poll --port=0 \
            --dhcp-alternate-port=1067,68 \
            --dhcp-broadcast --log-dhcp \
            --interface="$_iface" \
            --dhcp-range="$_dstart,$_dend,12h" \
            --dhcp-option=3,"$_ip" \
            --dhcp-option=6,"$_dns_list" \
            --dhcp-leasefile="$TMP_DIR/dnsmasq.leases" \
            --pid-file="$TMP_DIR/dnsmasq.pid" < "$TMP_DIR/dnsmasq.fifo" >>"$TMP_DIR/dnsmasq.log" 2>&1 &
    else
        /system/bin/dnsmasq --keep-in-foreground --no-resolv --no-poll --port=0 \
            --dhcp-alternate-port=1067,68 \
            --dhcp-broadcast --log-dhcp \
            --interface="$_iface" \
            --dhcp-range="$_dstart,$_dend,12h" \
            --dhcp-option=3 \
            --dhcp-option=6,"$_ip" \
            --dhcp-leasefile="$TMP_DIR/dnsmasq.leases" \
            --pid-file="$TMP_DIR/dnsmasq.pid" < "$TMP_DIR/dnsmasq.fifo" >>"$TMP_DIR/dnsmasq.log" 2>&1 &
    fi
    sleep 1
    echo -n "update_ifaces:$_iface" > "$TMP_DIR/dnsmasq.fifo" 2>/dev/null

    log_info "SoftAP active on $_iface ($_ip/$AP_PREFIX, SSID=$AP_SSID, DNS=$_dns_list)"
    save_active_snapshot
    sync_logs_to_usb
}

start_sta_on_iface() {
    local _iface="$1" _prio=100 _s_ssid _s_pass
    load_config
    stop_ap_daemons
    stop_sta_daemons

    # Avoid 192.168.0.0/24 routing conflict with unused internal eth0 (192.168.0.123 on Ioniq 5 / K8 HEV)
    if ip addr show dev eth0 2>/dev/null | grep -q "192.168.0.123"; then
        ip addr flush dev eth0 >/dev/null 2>&1
        ip link set eth0 down >/dev/null 2>&1
    fi

    log_info "Configuring Client (STA) mode on $_iface (target SSID=$STA_SSID)"
    if ! ip link set "$_iface" up >/dev/null 2>&1; then
        sleep 2
        ip link set "$_iface" up >/dev/null 2>&1 || return 1
    fi
    sleep 1
    ip -4 addr flush dev "$_iface" >/dev/null 2>&1

    mkdir -p "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/wpa_ctrl" 2>/dev/null
    cat > "$WIFI_WORK_DIR/wpa_supplicant.conf" <<EOF
ctrl_interface=$WIFI_WORK_DIR/wpa_ctrl
update_config=1
ap_scan=1
EOF
    if [ -n "$STA_SSID" ]; then
        if [ -n "$STA_PASS" ]; then
            cat >> "$WIFI_WORK_DIR/wpa_supplicant.conf" <<EOF
network={
    ssid="$STA_SSID"
    scan_ssid=1
    key_mgmt=WPA-PSK
    psk="$STA_PASS"
    priority=100
}
EOF
        else
            cat >> "$WIFI_WORK_DIR/wpa_supplicant.conf" <<EOF
network={
    ssid="$STA_SSID"
    scan_ssid=1
    key_mgmt=NONE
    priority=100
}
EOF
        fi
    fi
    if [ -f "$SAVED_STA_FILE" ]; then
        while IFS='=' read -r _s_ssid _s_pass; do
            [ -n "$_s_ssid" ] || continue
            [ "$_s_ssid" = "$STA_SSID" ] && continue
            _prio=$((_prio - 10))
            [ "$_prio" -lt 10 ] && _prio=10
            if [ -n "$_s_pass" ]; then
                cat >> "$WIFI_WORK_DIR/wpa_supplicant.conf" <<EOF
network={
    ssid="$_s_ssid"
    scan_ssid=1
    key_mgmt=WPA-PSK
    psk="$_s_pass"
    priority=$_prio
}
EOF
            fi
        done < "$SAVED_STA_FILE"
    fi
    chown -R 1010:1010 "$WIFI_WORK_DIR" 2>/dev/null
    chmod 777 "$WIFI_WORK_DIR" "$WIFI_WORK_DIR/wpa_ctrl" 2>/dev/null
    chmod 666 "$WIFI_WORK_DIR/wpa_supplicant.conf" 2>/dev/null
    cp "$WIFI_WORK_DIR/wpa_supplicant.conf" "$TMP_DIR/wpa_supplicant.conf" 2>/dev/null
    iptables -D INPUT -i "$_iface" -j ACCEPT 2>/dev/null
    iptables -D OUTPUT -o "$_iface" -j ACCEPT 2>/dev/null
    iptables -I INPUT 1 -i "$_iface" -j ACCEPT 2>/dev/null
    iptables -I OUTPUT 1 -o "$_iface" -j ACCEPT 2>/dev/null

    /system/bin/wpa_supplicant -Dnl80211 -i"$_iface" -c"$WIFI_WORK_DIR/wpa_supplicant.conf" >>"$TMP_DIR/wpa_supplicant.log" 2>&1 &
    echo $! > "$TMP_DIR/wpa_supplicant.pid"
    sleep 2
    for _rproc in "/proc/net/rtl88x2bu/$_iface" "/proc/net/rtl8851bu/$_iface"; do
        [ -w "$_rproc/survey_info" ] && echo 1 > "$_rproc/survey_info" 2>/dev/null
    done
    [ -x /system/bin/wpa_cli ] && /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" scan >/dev/null 2>&1
    sleep 2
    for _rproc in "/proc/net/rtl88x2bu/$_iface" "/proc/net/rtl8851bu/$_iface"; do
        [ -f "$_rproc/survey_info" ] && cat "$_rproc/survey_info" > "$TMP_DIR/sta_survey.txt" 2>/dev/null
        [ -f "$_rproc/ap_info" ] && cat "$_rproc/ap_info" > "$TMP_DIR/sta_ap_info.txt" 2>/dev/null
        [ -f "$_rproc/rx_signal" ] && cat "$_rproc/rx_signal" > "$TMP_DIR/sta_rx_signal.txt" 2>/dev/null
    done
    if [ -x /system/bin/wpa_cli ]; then
        /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" scan_results > "$TMP_DIR/sta_scan.txt" 2>/dev/null
        /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" status > "$TMP_DIR/wpa_status.txt" 2>/dev/null
    fi
    log_info "Client (STA) wpa_supplicant started on $_iface"
    save_active_snapshot
}

is_sta_healthy() {
    local _iface="$1" _wpid _cpid _sta_ip _gw _dns1 _assoc=0 _rproc
    [ -n "$_iface" ] || return 1
    [ -d "/sys/class/net/$_iface" ] || return 1
    if [ -f "$TMP_DIR/wpa_supplicant.pid" ]; then
        read -r _wpid < "$TMP_DIR/wpa_supplicant.pid" 2>/dev/null
        [ -n "$_wpid" ] && [ -d "/proc/$_wpid" ] || return 1
    else
        return 1
    fi
    for _rproc in "/proc/net/rtl88x2bu/$_iface" "/proc/net/rtl8851bu/$_iface"; do
        [ -f "$_rproc/survey_info" ] && cat "$_rproc/survey_info" > "$TMP_DIR/sta_survey.txt" 2>/dev/null
        [ -f "$_rproc/ap_info" ] && cat "$_rproc/ap_info" > "$TMP_DIR/sta_ap_info.txt" 2>/dev/null
        [ -f "$_rproc/rx_signal" ] && cat "$_rproc/rx_signal" > "$TMP_DIR/sta_rx_signal.txt" 2>/dev/null
    done
    if [ -x /system/bin/wpa_cli ]; then
        /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" status > "$TMP_DIR/wpa_status.txt" 2>/dev/null
        /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" signal_poll > "$TMP_DIR/wpa_signal.txt" 2>/dev/null
        /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$_iface" scan_results > "$TMP_DIR/sta_scan.txt" 2>/dev/null
    fi
    if grep -q "^wpa_state=COMPLETED" "$TMP_DIR/wpa_status.txt" 2>/dev/null; then
        _assoc=1
    elif grep -q "^SSID=..*" "$TMP_DIR/sta_ap_info.txt" 2>/dev/null; then
        _assoc=1
    fi
    if [ "$_assoc" = "1" ]; then
        if ! ip -4 addr show dev "$_iface" 2>/dev/null | grep -q "inet "; then
            local _dhcp_running=0
            if [ -f "$TMP_DIR/dhcpcd.pid" ]; then
                read -r _cpid < "$TMP_DIR/dhcpcd.pid" 2>/dev/null
                [ -n "$_cpid" ] && [ -d "/proc/$_cpid" ] && _dhcp_running=1
            fi
            if [ "$_dhcp_running" = "0" ]; then
                log_info "STA connected on $_iface -> starting dhcpcd"
                rm -f "/data/misc/dhcp/dhcpcd-${_iface}.pid" 2>/dev/null
                /system/bin/dhcpcd -ABKL "$_iface" >>"$TMP_DIR/dhcpcd.log" 2>&1 &
                echo $! > "$TMP_DIR/dhcpcd.pid"
                sleep 3
            fi
        fi
        _sta_ip="$(ip -4 addr show dev "$_iface" 2>/dev/null | grep 'inet ' | while read -r _a _b _rest; do echo "${_b%%/*}"; break; done)"
        if [ -n "$_sta_ip" ]; then
            _gw="$(getprop dhcp.${_iface}.gateway 2>/dev/null)"
            [ -z "$_gw" ] && _gw="${_sta_ip%.*}.1"
            _dns1="$(getprop dhcp.${_iface}.dns1 2>/dev/null)"
            [ -z "$_dns1" ] && _dns1="8.8.8.8"
            ip route replace "${_sta_ip%.*}.0/24" dev "$_iface" src "$_sta_ip" table 62 >/dev/null 2>&1
            ip route replace default via "$_gw" dev "$_iface" table 62 >/dev/null 2>&1
            ip rule del pref 9790 2>/dev/null
            ip rule del pref 9800 2>/dev/null
            ip rule add pref 9790 to "${_sta_ip%.*}.0/24" lookup main 2>/dev/null
            ip rule add pref 9800 from all lookup 62 2>/dev/null
            ndc resolver setifdns "$_iface" "" "$_dns1" "8.8.8.8" >/dev/null 2>&1
            ndc resolver setdefaultif "$_iface" >/dev/null 2>&1
        fi
    fi
    return 0
}

is_ap_healthy() {
    local _iface="$1" _hpid _dpid
    [ -n "$_iface" ] || return 1
    [ -d "/sys/class/net/$_iface" ] || return 1
    if [ -f "$TMP_DIR/hostapd.pid" ]; then
        read -r _hpid < "$TMP_DIR/hostapd.pid" 2>/dev/null
        [ -n "$_hpid" ] && [ -d "/proc/$_hpid" ] || return 1
    else
        return 1
    fi
    if [ -f "$TMP_DIR/dnsmasq.pid" ]; then
        read -r _dpid < "$TMP_DIR/dnsmasq.pid" 2>/dev/null
        [ -n "$_dpid" ] && [ -d "/proc/$_dpid" ] || return 1
    else
        return 1
    fi
    # Ensure IP address stays assigned without restarting hostapd
    if [ -n "$ACTIVE_IP" ] && ! ip -4 addr show dev "$_iface" 2>/dev/null | grep -q "$ACTIVE_IP/"; then
        ip link set "$_iface" up >/dev/null 2>&1
        ip addr add "$ACTIVE_IP/$AP_PREFIX" broadcast "${ACTIVE_IP%.*}.255" dev "$_iface" >/dev/null 2>&1
    fi
    return 0
}

log_info "Service started (pid=$$), monitoring USB Wi-Fi (88x2bu + 8851bu/8831bu)"
# Pre-load 88x2bu and 8851bu drivers immediately at boot (before Android Auto / system_server starts)
# so usb_driver is registered and blocks usbfs AOA Request 51/52/53 on any hotplug
ensure_driver_loaded

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2 &
    wait $!
done

ensure_hostapd_usb_binary

LAST_USB_SYNC=0
SNAP_TICK=0
while true; do
    if [ -f "$TMP_DIR/ota_reload_daemon" ]; then
        rm -f "$TMP_DIR/ota_reload_daemon" "$TMP_DIR/daemon.pid" 2>/dev/null
        log_info "OTA update applied -> re-executing usbwifi-hotspot-daemon.sh"
        exec /system/bin/sh "$APP_DIR/svc/usbwifi-hotspot-daemon.sh"
    fi
    if [ -f "$TMP_DIR/force_restart" ]; then
        rm -f "$TMP_DIR/force_restart" 2>/dev/null
        log_info "Mode switch / restart requested via UI dashboard"
        load_config
        INSMOD_FAILED=0
        stop_ap_daemons
        stop_sta_daemons
        ACTIVE_IFACE=""
    fi
    if [ -f "$TMP_DIR/trigger_scan" ]; then
        rm -f "$TMP_DIR/trigger_scan" 2>/dev/null
        if [ -n "$ACTIVE_IFACE" ] && [ "$WIFI_MODE" = "sta" ]; then
            for _rproc in "/proc/net/rtl88x2bu/$ACTIVE_IFACE" "/proc/net/rtl8851bu/$ACTIVE_IFACE"; do
                [ -w "$_rproc/survey_info" ] && echo 1 > "$_rproc/survey_info" 2>/dev/null
            done
            [ -x /system/bin/wpa_cli ] && /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$ACTIVE_IFACE" scan >/dev/null 2>&1
            sleep 2
            for _rproc in "/proc/net/rtl88x2bu/$ACTIVE_IFACE" "/proc/net/rtl8851bu/$ACTIVE_IFACE"; do
                [ -f "$_rproc/survey_info" ] && cat "$_rproc/survey_info" > "$TMP_DIR/sta_survey.txt" 2>/dev/null
            done
            [ -x /system/bin/wpa_cli ] && /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$ACTIVE_IFACE" scan_results > "$TMP_DIR/sta_scan.txt" 2>/dev/null
        fi
    fi
    if [ -f "$TMP_DIR/disconnect_sta" ]; then
        rm -f "$TMP_DIR/disconnect_sta" 2>/dev/null
        if [ -n "$ACTIVE_IFACE" ]; then
            if [ -x /system/bin/wpa_cli ]; then
                /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$ACTIVE_IFACE" disconnect >/dev/null 2>&1
                /system/bin/wpa_cli -p "$WIFI_WORK_DIR/wpa_ctrl" -i "$ACTIVE_IFACE" status > "$TMP_DIR/wpa_status.txt" 2>/dev/null
            else
                kill_pid_file "$TMP_DIR/wpa_supplicant.pid"
                rm -f "$TMP_DIR/sta_ap_info.txt" "$TMP_DIR/wpa_status.txt" 2>/dev/null
            fi
            kill_pid_file "$TMP_DIR/dhcpcd.pid"
            ip -4 addr flush dev "$ACTIVE_IFACE" >/dev/null 2>&1
            ip rule del pref 9790 2>/dev/null
            ip rule del pref 9800 2>/dev/null
            ip route flush table 62 2>/dev/null
        fi
    fi
    if is_usb_dongle_present; then
        if ensure_driver_loaded; then
            _iface="$(find_usb_wifi_iface)"
            if [ -n "$_iface" ]; then
                if [ "$WIFI_MODE" = "sta" ]; then
                    if ! is_sta_healthy "$_iface"; then
                        ACTIVE_IFACE="$_iface"
                        start_sta_on_iface "$_iface"
                    else
                        SNAP_TICK=$((SNAP_TICK + 1))
                        if [ "$SNAP_TICK" -ge 2 ]; then
                            SNAP_TICK=0
                            save_active_snapshot
                        fi
                    fi
                else
                    if ! is_ap_healthy "$_iface"; then
                        ACTIVE_IFACE="$_iface"
                        start_hotspot_on_iface "$_iface"
                    else
                        SNAP_TICK=$((SNAP_TICK + 1))
                        if [ "$SNAP_TICK" -ge 2 ]; then
                            SNAP_TICK=0
                            save_active_snapshot
                        fi
                    fi
                fi
            fi
        fi
    else
        INSMOD_FAILED=0
        if [ -n "$ACTIVE_IFACE" ]; then
            log_info "USB dongle unplugged -> stopping daemons"
            stop_ap_daemons
            stop_sta_daemons
            ACTIVE_IFACE=""
            save_active_snapshot
        fi
    fi
    if [ -d /storage/usb0 ]; then
        if [ "$LAST_USB_SYNC" -eq 0 ]; then
            sync_logs_to_usb
            LAST_USB_SYNC=1
        fi
    else
        LAST_USB_SYNC=0
    fi
    for _tick in 1 2 3 4 5; do
        [ -f "$TMP_DIR/ota_reload_daemon" ] && break
        [ -f "$TMP_DIR/force_restart" ] && break
        [ -f "$TMP_DIR/trigger_scan" ] && break
        [ -f "$TMP_DIR/disconnect_sta" ] && break
        sleep 1 &
        wait $!
    done
done
