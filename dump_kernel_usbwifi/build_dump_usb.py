#!/usr/bin/env python3
"""Build USB dump script (type=execute) to extract Gen5W kernel modules (.ko),
kallsyms, boot partition image, USB device descriptors, and SoftAP binaries
needed to cross-compile and configure Realtek RTL8812BU (0bda:b812) hotspot.
"""
from __future__ import annotations

import zipfile
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
OUT_ZIP = BASE_DIR.parent / "Dump_USBWifi_Kernel.zip"

NEMESIS_DATA = (
    "[nemesis]\n"
    "version=1.0\n"
    "type=execute\n"
    "path=NEMESIS.sh\n"
)

NEMESIS_SH = r"""#!/system/bin/sh
# Read-only kernel, module (.ko), symbol, and SoftAP binary dump for USB Wi-Fi Hotspot
# Compatible with Android 4.4.2 (KitKat) toolbox + mksh + busybox on Gen5W head units.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

USB_DIR="$(pwd)"
if [ ! -w "$USB_DIR" ]; then
    for _d in /storage/usb0 /mnt/media_rw/usb0 /storage/usb1 /mnt/media_rw/usb1 /storage/usb2 /mnt/media_rw/usb2; do
        if [ -d "$_d" ] && [ -w "$_d" ]; then
            USB_DIR="$_d"
            break
        fi
    done
fi

OUT="$USB_DIR/usbwifi_kernel_dump"
rm -r "$OUT" 2>/dev/null
mkdir -p "$OUT/modules/system_lib_modules" \
         "$OUT/modules/extra_ko" \
         "$OUT/kernel" \
         "$OUT/binaries" \
         "$OUT/usb" \
         "$OUT/network" \
         "$OUT/logs" || exit 1

log_step() {
    echo "$(date) $1" >> "$OUT/dump_progress.log" 2>/dev/null
}

copy_file() {
    [ -f "$1" ] && cp "$1" "$2" 2>/dev/null
    true
}

copy_flat_dir() {
    local _src="$1"
    local _dst="$2"
    local _f
    mkdir -p "$_dst" 2>/dev/null
    for _f in "$_src"/*; do
        [ -f "$_f" ] && cp "$_f" "$_dst/${_f##*/}" 2>/dev/null
    done
}

log_step "1. Copying kernel modules (.ko) from /system/lib/modules and system partitions"
ls -la /system/lib/modules > "$OUT/modules/ls_system_lib_modules.txt" 2>&1
copy_flat_dir /system/lib/modules "$OUT/modules/system_lib_modules"

if [ -d /vendor/lib/modules ]; then
    ls -la /vendor/lib/modules > "$OUT/modules/ls_vendor_lib_modules.txt" 2>&1
    copy_flat_dir /vendor/lib/modules "$OUT/modules/extra_ko"
fi

if [ -d /lib/modules ]; then
    ls -la /lib/modules > "$OUT/modules/ls_root_lib_modules.txt" 2>&1
    copy_flat_dir /lib/modules "$OUT/modules/extra_ko"
fi

if [ -x /system/bin/busybox ]; then
    /system/bin/busybox find /system /oem_data -name "*.ko" > "$OUT/modules/find_all_ko.txt" 2>/dev/null
    while read -r _ko; do
        if [ -n "$_ko" ] && [ -f "$_ko" ]; then
            cp "$_ko" "$OUT/modules/extra_ko/${_ko##*/}" 2>/dev/null
        fi
    done < "$OUT/modules/find_all_ko.txt"
fi

log_step "2. Capturing /proc/kallsyms (with kptr_restrict=0), /proc/modules, and kernel info"
_old_kptr="$(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)"
echo 0 > /proc/sys/kernel/kptr_restrict 2>/dev/null

cat /proc/version > "$OUT/kernel/proc_version.txt" 2>&1
cat /proc/cmdline > "$OUT/kernel/proc_cmdline.txt" 2>&1
cat /proc/cpuinfo > "$OUT/kernel/proc_cpuinfo.txt" 2>&1
cat /proc/modules > "$OUT/kernel/proc_modules.txt" 2>&1
cat /proc/kallsyms > "$OUT/kernel/kallsyms.txt" 2>&1
cat /proc/devices > "$OUT/kernel/proc_devices.txt" 2>&1
cat /proc/iomem > "$OUT/kernel/proc_iomem.txt" 2>&1
cat /proc/interrupts > "$OUT/kernel/proc_interrupts.txt" 2>&1
cat /proc/net/dev > "$OUT/kernel/proc_net_dev.txt" 2>&1
cat /proc/net/wireless > "$OUT/kernel/proc_net_wireless.txt" 2>&1

if [ -n "$_old_kptr" ]; then
    echo "$_old_kptr" > /proc/sys/kernel/kptr_restrict 2>/dev/null
fi

if [ -f /proc/config.gz ]; then
    cat /proc/config.gz > "$OUT/kernel/config.gz" 2>/dev/null
fi

log_step "3. Dumping boot partition image for exact kernel struct/symbol verification"
ls -la /dev/block/platform/bdm/by-name > "$OUT/kernel/by_name_partitions.txt" 2>&1
for _bpart in /dev/block/platform/bdm/by-name/boot /dev/block/platform/bdm/by-name/_boot; do
    if [ -e "$_bpart" ]; then
        dd if="$_bpart" of="$OUT/kernel/${_bpart##*/}.img" bs=1048576 count=24 2>>"$OUT/dump_progress.log"
        break
    fi
done

log_step "4. Copying SoftAP userspace binaries and libraries (hostapd, dnsmasq, netd, etc.)"
for _bin in /system/bin/hostapd \
            /system/bin/dnsmasq \
            /system/bin/wpa_supplicant \
            /system/bin/ndc \
            /system/bin/netd \
            /system/bin/ip \
            /system/bin/iptables \
            /system/bin/netcfg \
            /system/bin/wl; do
    copy_file "$_bin" "$OUT/binaries/${_bin##*/}"
done

for _lib in /system/lib/libhardware_legacy.so \
            /system/lib/libnetutils.so \
            /system/lib/libnl.so \
            /system/lib/libwpa_client.so \
            /system/lib/libcutils.so; do
    copy_file "$_lib" "$OUT/binaries/${_lib##*/}"
done

if [ -x /system/bin/busybox ]; then
    /system/bin/busybox --list > "$OUT/binaries/busybox_applets.txt" 2>&1
fi
/system/bin/hostapd -v > "$OUT/binaries/hostapd_version.txt" 2>&1
/system/bin/dnsmasq -v > "$OUT/binaries/dnsmasq_version.txt" 2>&1
for _p in /proc/[0-9]*; do
    _comm="$(cat "$_p/comm" 2>/dev/null)"
    case "$_comm" in
        *hostapd*|*dnsmasq*|*netd*|*wpa*)
            echo "=== $_p ($_comm) ===" >> "$OUT/binaries/running_daemons_cmdline.txt"
            tr '\000' ' ' < "$_p/cmdline" >> "$OUT/binaries/running_daemons_cmdline.txt" 2>/dev/null
            echo "" >> "$OUT/binaries/running_daemons_cmdline.txt"
            ;;
    esac
done

log_step "5. Capturing USB bus devices and network/wireless sysfs state"
: > "$OUT/usb/usb_devices.txt"
for _udev in /sys/bus/usb/devices/*; do
    if [ -d "$_udev" ]; then
        echo "=== $_udev ===" >> "$OUT/usb/usb_devices.txt"
        for _attr in idVendor idProduct bcdDevice bDeviceClass bDeviceSubClass bDeviceProtocol manufacturer product serial speed version maxchild bNumConfigurations bConfigurationValue modalias uevent; do
            if [ -f "$_udev/$_attr" ]; then
                echo "  $_attr: $(cat "$_udev/$_attr" 2>/dev/null)" >> "$OUT/usb/usb_devices.txt"
            fi
        done
    fi
done

ls -la /sys/class/net > "$OUT/network/sys_class_net.txt" 2>&1
ls -la /sys/class/ieee80211 >> "$OUT/network/sys_class_net.txt" 2>&1
getprop > "$OUT/network/getprop.txt" 2>&1
ip addr > "$OUT/network/ip_addr.txt" 2>&1
ip link > "$OUT/network/ip_link.txt" 2>&1
ip route show table all > "$OUT/network/ip_route_all.txt" 2>&1
netcfg > "$OUT/network/netcfg.txt" 2>&1
ls -la /system/wifi /system/etc/wifi /data/misc/wifi > "$OUT/network/wifi_dirs.txt" 2>&1
copy_file /system/build.prop "$OUT/network/build.prop"
copy_file /init.rc "$OUT/kernel/init.rc"
copy_file /init.tcc897x.rc "$OUT/kernel/init.tcc897x.rc"
copy_file /init.tcc897x.wifi.rc "$OUT/kernel/init.tcc897x.wifi.rc"
copy_file /init.daudio.rc "$OUT/kernel/init.daudio.rc"

log_step "6. Capturing dmesg"
dmesg > "$OUT/logs/dmesg.txt" 2>&1

log_step "7. Syncing filesystem and finishing"
sync
exit 0
"""


def main() -> None:
    data_path = BASE_DIR / "NEMESIS.data"
    sh_path = BASE_DIR / "NEMESIS.sh"

    data_path.write_bytes(NEMESIS_DATA.replace("\r\n", "\n").encode("utf-8"))
    sh_path.write_bytes(NEMESIS_SH.replace("\r\n", "\n").encode("utf-8"))

    with zipfile.ZipFile(OUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(data_path, "NEMESIS.data")
        zf.write(sh_path, "NEMESIS.sh")

    print(f"Wrote {data_path} ({data_path.stat().st_size} bytes)")
    print(f"Wrote {sh_path} ({sh_path.stat().st_size} bytes)")
    print(f"Wrote {OUT_ZIP} ({OUT_ZIP.stat().st_size} bytes)")


if __name__ == "__main__":
    main()

