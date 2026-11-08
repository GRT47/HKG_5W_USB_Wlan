package com.hkg.usbwifi;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HKG Standard Gen5W (Android 4.4.2 API 19) — USB Wi-Fi Hotspot Real-Time Status Dashboard Overlay.
 *
 * Launched via NEMESIS Dashboard ("Launch" -> bin/launch.sh) or background daemon.
 * Renders a live 2-column system overlay window on the head unit screen showing:
 *  - Platform / Board / Kernel info (tcc803x / tcc897x)
 *  - Background Daemon status (PID / running state)
 *  - USB Dongle (Realtek RTL8812BU/8822BU 0bda:b812 & RTL8831BU/8851BU 0bda:b831/b851) connection, USB bus path & link speed
 *  - Kernel Module (88x2bu*.ko / 8851bu*.ko) load state, memory size & insmod logs
 *  - USB Wi-Fi Network Interface (rtwlan0 / wlan1 / wlan0) UP/DOWN state, IPv4/prefix, MAC, RX/TX traffic
 *  - Built-in Wi-Fi (bcmdhd / wlan0) coexistence status
 *  - SoftAP (hostapd) & DHCP (dnsmasq) state, SSID, password, band/channel, connected clients
 *  - Live tail of daemon.log & hostapd.log
 *  - Interactive touch buttons: [GitHub OTA Update], [Restart AP], [Band 2.4G <-> 5G], [Subnet .0.1 <-> .43.1], [Export Logs]
 */
public class UsbWifiStatusMain {

    public static final String UI_VERSION = "2.0.8";
    public static final String GITHUB_RAW_HOST = "raw.githubusercontent.com";
    public static final String GITHUB_OTA_BASE_PATH = "/GRT47/HKG_5W_USB_Wlan/main/ota";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String TMP_DIR_PATH = "/data/local/tmp/usbwifi_hotspot";
    private static final String WIFI_WORK_DIR_PATH = "/data/misc/wifi/usbwifi_hotspot";

    public static String classifyUsbWifiDongle(String vid, String pid, String confVid, String confPid) {
        if (vid == null || pid == null) return null;
        String v = vid.trim().toLowerCase(Locale.US);
        String p = pid.trim().toLowerCase(Locale.US);
        String vp = v + ":" + p;
        // 1. Realtek RTL8831BU / RTL8851BU (AX900 Wi-Fi 6)
        if ("0bda:b831".equals(vp)) return "RTL8831BU Wi-Fi 6";
        if ("0bda:b851".equals(vp) || "0bda:b832".equals(vp)) return "RTL8851BU Wi-Fi 6";
        if ("3625:010b".equals(vp) || "2357:0142".equals(vp)) return "TP-Link RTL8831BU/8851BU";
        if ("7392:e611".equals(vp)) return "Edimax RTL8851BU";
        if ("2001:332a".equals(vp)) return "D-Link RTL8851BU";
        if ("2c4e:0128".equals(vp)) return "Mercusys RTL8851BU";
        if ("056e:4027".equals(vp) || "056e:4028".equals(vp)) return "Elecom RTL8851BU";
        if ("0411:03ed".equals(vp)) return "Buffalo RTL8851BU";
        // 2. Realtek RTL8812BU / RTL8822BU (802.11ac)
        if ("0bda:b812".equals(vp)) return "RTL8812BU";
        if ("0bda:b82c".equals(vp) || "0bda:b81a".equals(vp) || "0bda:b820".equals(vp) || "0bda:b82b".equals(vp)) {
            return "RTL8822BU";
        }
        if ("2357:012d".equals(vp) || "2357:0138".equals(vp) || "2357:0115".equals(vp)
                || "7392:b822".equals(vp) || "7392:c822".equals(vp)
                || "0b05:184c".equals(vp) || "0b05:1841".equals(vp)
                || "2001:331c".equals(vp) || "2001:331e".equals(vp)
                || "0846:9055".equals(vp) || "13b1:0043".equals(vp)
                || "0e66:0025".equals(vp) || "0411:029b".equals(vp) || "056e:4011".equals(vp)) {
            return "RTL88x2BU";
        }
        // 3. Realtek Virtual CD-ROM Auto-Install mode (auto-switching to Wi-Fi mode)
        if ("0bda:1a2b".equals(vp)) return "Realtek CD-ROM 자동전환 중";
        // 4. Custom configured VID:PID in hotspot.conf
        if (confVid != null && confPid != null
                && v.equals(confVid.trim().toLowerCase(Locale.US))
                && p.equals(confPid.trim().toLowerCase(Locale.US))) {
            return "USB Wi-Fi";
        }
        return null;
    }

    public static boolean is8851buDongle(String vid, String pid) {
        if (vid == null || pid == null) return false;
        String vp = vid.trim().toLowerCase(Locale.US) + ":" + pid.trim().toLowerCase(Locale.US);
        return "0bda:b831".equals(vp) || "0bda:b851".equals(vp) || "0bda:b832".equals(vp)
                || "3625:010b".equals(vp) || "2357:0142".equals(vp) || "7392:e611".equals(vp)
                || "2001:332a".equals(vp) || "2c4e:0128".equals(vp)
                || "056e:4027".equals(vp) || "056e:4028".equals(vp) || "0411:03ed".equals(vp)
                || "0bda:1a2b".equals(vp);
    }

    public static final class ScanEntry {
        public String ssid = "";
        public String band = "2.4G";
        public int rssi = -70;
        public boolean secured = true;
    }

    public static final class SystemSnapshot {
        public String platform = "";
        public String model = "";
        public String kernelRel = "";

        public boolean daemonAlive = false;
        public String daemonPid = "";

        public boolean usbDonglePresent = false;
        public String usbDongleDesc = "";
        public String otherUsbDevices = "";

        public boolean driverLoaded = false;
        public String driverModuleFile = "";
        public String driverMemInfo = "";
        public String lastInsmodError = "";

        public String usbIface = "";
        public boolean ifaceUp = false;
        public String ifaceIp = "";
        public String ifaceMac = "";
        public long rxPackets = 0L;
        public long rxBytes = 0L;
        public long txPackets = 0L;
        public long txBytes = 0L;

        public boolean hasBuiltinBcmdhd = false;
        public boolean builtinWlan0Up = false;
        public String builtinWlan0Ip = "";

        public String wifiMode = "ap";
        public boolean hostapdAlive = false;
        public String hostapdPid = "";
        public boolean dnsmasqAlive = false;
        public String dnsmasqPid = "";
        public boolean dnsmasqShared = false;

        public String apSsid = "HKG_USB_Hotspot";
        public String apPass = "12345678";
        public String apHwMode = "g";
        public String apChannel = "6";
        public String forceSubnet = "AUTO";
        public String enableLteNat = "1";
        public String autoUpdate = "1";

        public String staSsid = "";
        public String staPass = "";
        public boolean wpaSuppAlive = false;
        public String wpaSuppPid = "";
        public boolean staConnected = false;
        public String staConnectedSsid = "";
        public String staBand = "2.4GHz";
        public String staRssi = "-50 dBm";
        public List<ScanEntry> staScanList = new ArrayList<ScanEntry>();
        public List<String> savedStaList = new ArrayList<String>();

        public int cpuLoadPct = 0;
        public String ramSummary = "💾 RAM: --";

        public List<String> connectedClients = new ArrayList<String>();
        public List<String> recentLogs = new ArrayList<String>();
    }

    public static SystemSnapshot collectSnapshot(String installDir) {
        SystemSnapshot snap = new SystemSnapshot();
        snap.platform = getSystemProperty("ro.board.platform", "unknown");
        snap.model = getSystemProperty("ro.product.model", getSystemProperty("ro.product.device", "Gen5W"));
        snap.kernelRel = readFirstLineQuick(new File("/proc/sys/kernel/osrelease"));
        if (snap.kernelRel.isEmpty()) {
            snap.kernelRel = System.getProperty("os.version", "3.18.24-tcc");
        }

        File tmpDir = new File(TMP_DIR_PATH);
        File confFile = new File(installDir, "config/hotspot.conf");
        Map<String, String> conf = parseKeyValueFile(confFile);
        String targetVid = normalizeLower(conf.get("USB_VID"), "0bda");
        String targetPid = normalizeLower(conf.get("USB_PID"), "b812");
        snap.wifiMode = normalizeLower(conf.get("WIFI_MODE"), "ap");
        if (!"sta".equals(snap.wifiMode)) {
            snap.wifiMode = "ap";
        }
        snap.apSsid = stripQuotes( getOrDefault(conf, "AP_SSID", "HKG_USB_Hotspot") );
        snap.apPass = stripQuotes( getOrDefault(conf, "AP_PASS", "12345678") );
        snap.apHwMode = stripQuotes( getOrDefault(conf, "AP_HW_MODE", "g") );
        snap.apChannel = stripQuotes( getOrDefault(conf, "AP_CHANNEL", "6") );
        snap.staSsid = stripQuotes( getOrDefault(conf, "STA_SSID", "") );
        snap.staPass = stripQuotes( getOrDefault(conf, "STA_PASS", "") );
        snap.forceSubnet = stripQuotes( getOrDefault(conf, "FORCE_SUBNET", "AUTO") );
        snap.enableLteNat = stripQuotes( getOrDefault(conf, "ENABLE_LTE_NAT", "1") );
        snap.autoUpdate = stripQuotes( getOrDefault(conf, "AUTO_UPDATE", "1") );

        // Override with live hostapd.conf if present
        File liveHostapdConf = new File(WIFI_WORK_DIR_PATH, "hostapd.conf");
        if (!liveHostapdConf.isFile()) {
            liveHostapdConf = new File(tmpDir, "hostapd.conf");
        }
        if (liveHostapdConf.isFile()) {
            Map<String, String> hconf = parseKeyValueFile(liveHostapdConf);
            if (hconf.containsKey("ssid")) snap.apSsid = hconf.get("ssid");
            if (hconf.containsKey("wpa_passphrase")) snap.apPass = hconf.get("wpa_passphrase");
            if (hconf.containsKey("hw_mode")) snap.apHwMode = hconf.get("hw_mode");
            if (hconf.containsKey("channel")) snap.apChannel = hconf.get("channel");
        }

        // 1. Daemon PID check
        File daemonPidFile = new File(tmpDir, "daemon.pid");
        if (daemonPidFile.isFile()) {
            String pid = readFirstLineQuick(daemonPidFile);
            if (isProcessAlive(pid)) {
                snap.daemonAlive = true;
                snap.daemonPid = pid;
            }
        }
        if (!snap.daemonAlive) {
            String foundPid = findPidByCmdline("usbwifi-hotspot-daemon.sh");
            if (!foundPid.isEmpty()) {
                snap.daemonAlive = true;
                snap.daemonPid = foundPid;
            }
        }

        // 2. USB Dongle check (/sys/bus/usb/devices/*)
        File usbBusDir = new File("/sys/bus/usb/devices");
        File[] usbDevs = usbBusDir.listFiles();
        List<String> otherList = new ArrayList<String>();
        boolean activeDongleIs8851bu = false;
        if (usbDevs != null) {
            for (File d : usbDevs) {
                File vidF = new File(d, "idVendor");
                File pidF = new File(d, "idProduct");
                if (!vidF.isFile() || !pidF.isFile()) continue;
                String v = readFirstLineQuick(vidF).toLowerCase(Locale.US);
                String p = readFirstLineQuick(pidF).toLowerCase(Locale.US);
                if (v.isEmpty() || p.isEmpty()) continue;
                // Ignore Linux root hubs (1d6b:0002 / 1d6b:0003) and internal hubs (0424:2514 etc. unless needed)
                if ("1d6b".equals(v)) continue;
                String prod = readFirstLineQuick(new File(d, "product"));
                String speed = readFirstLineQuick(new File(d, "speed"));
                String busName = d.getName();
                String chipModel = classifyUsbWifiDongle(v, p, targetVid, targetPid);
                if (chipModel != null) {
                    snap.usbDonglePresent = true;
                    if (is8851buDongle(v, p)) {
                        activeDongleIs8851bu = true;
                    }
                    String label = prod.isEmpty() ? (chipModel + " (" + v + ":" + p + ")") : (prod + " [" + chipModel + ", " + v + ":" + p + "]");
                    snap.usbDongleDesc = label + " [포트: " + busName + (speed.isEmpty() ? "" : ", " + speed + "Mbps") + "]";
                } else {
                    String oLabel = prod.isEmpty() ? (v + ":" + p) : (prod + "(" + v + ":" + p + ")");
                    if (!otherList.contains(oLabel)) {
                        otherList.add(oLabel);
                    }
                }
            }
        }
        if (!otherList.isEmpty()) {
            StringBuilder osb = new StringBuilder();
            for (int i = 0; i < otherList.size(); i++) {
                if (i > 0) osb.append(", ");
                osb.append(otherList.get(i));
            }
            snap.otherUsbDevices = osb.toString();
        }

        // 3. Kernel module (88x2bu / 8851bu) check in /proc/modules
        File modDir = new File(installDir, "modules");
        String baseDrv = activeDongleIs8851bu ? "8851bu" : "88x2bu";
        File platKo = new File(modDir, baseDrv + "_" + snap.platform + ".ko");
        snap.driverModuleFile = platKo.isFile() ? platKo.getName() : (baseDrv + ".ko");

        List<String> modLines = readLinesQuick(new File("/proc/modules"), 200);
        boolean loaded88x2 = false;
        boolean loaded8851 = false;
        String mem88x2 = "";
        String mem8851 = "";
        for (String line : modLines) {
            if (line.startsWith("88x2bu ")) {
                loaded88x2 = true;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    long bytes = parseLongSafe(parts[1], 0L);
                    String mbStr = String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0));
                    mem88x2 = mbStr + ", ref=" + parts[2];
                }
            } else if (line.startsWith("8851bu ")) {
                loaded8851 = true;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    long bytes = parseLongSafe(parts[1], 0L);
                    String mbStr = String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0));
                    mem8851 = mbStr + ", ref=" + parts[2];
                }
            } else if (line.startsWith("bcmdhd ")) {
                snap.hasBuiltinBcmdhd = true;
            }
        }
        if (activeDongleIs8851bu && loaded8851) {
            snap.driverLoaded = true;
            File ko51 = new File(modDir, "8851bu_" + snap.platform + ".ko");
            snap.driverModuleFile = ko51.isFile() ? ko51.getName() : "8851bu.ko";
            snap.driverMemInfo = mem8851;
        } else if (!activeDongleIs8851bu && loaded88x2) {
            snap.driverLoaded = true;
            File ko82 = new File(modDir, "88x2bu_" + snap.platform + ".ko");
            snap.driverModuleFile = ko82.isFile() ? ko82.getName() : "88x2bu.ko";
            snap.driverMemInfo = mem88x2;
        } else if (loaded8851) {
            snap.driverLoaded = true;
            File ko51 = new File(modDir, "8851bu_" + snap.platform + ".ko");
            snap.driverModuleFile = ko51.isFile() ? ko51.getName() : "8851bu.ko";
            snap.driverMemInfo = mem8851;
        } else if (loaded88x2) {
            snap.driverLoaded = true;
            File ko82 = new File(modDir, "88x2bu_" + snap.platform + ".ko");
            snap.driverModuleFile = ko82.isFile() ? ko82.getName() : "88x2bu.ko";
            snap.driverMemInfo = mem88x2;
        }
        if (loaded88x2 && loaded8851) {
            snap.driverModuleFile = snap.driverModuleFile + " (+듀얼모듈)";
        }
        if (!snap.hasBuiltinBcmdhd) {
            if (new File("/system/wifi/bcmdhd.ko").isFile()
                    || new File("/lib/modules/bcmdhd.ko").isFile()
                    || new File("/system/lib/modules/bcmdhd.ko").isFile()) {
                snap.hasBuiltinBcmdhd = true;
            }
        }
        if (!snap.driverLoaded) {
            List<String> insLines = readTailLines(new File(tmpDir, "insmod.log"), 3);
            if (!insLines.isEmpty()) {
                snap.lastInsmodError = insLines.get(insLines.size() - 1);
            }
        }

        // 4. Network Interface Detection
        snap.usbIface = findUsbWifiInterface();
        if (!snap.usbIface.isEmpty()) {
            File netDir = new File("/sys/class/net/" + snap.usbIface);
            snap.ifaceMac = readFirstLineQuick(new File(netDir, "address"));
            String flagsHex = readFirstLineQuick(new File(netDir, "flags"));
            long flagsVal = parseHexSafe(flagsHex, 0L);
            String oper = readFirstLineQuick(new File(netDir, "operstate"));
            snap.ifaceUp = ((flagsVal & 0x1L) != 0L) || "up".equalsIgnoreCase(oper);
            snap.ifaceIp = queryInterfaceIpv4(snap.usbIface);
            long[] stats = readNetDevStats(snap.usbIface);
            snap.rxBytes = stats[0];
            snap.rxPackets = stats[1];
            snap.txBytes = stats[2];
            snap.txPackets = stats[3];
        }

        if (!"wlan0".equals(snap.usbIface) && new File("/sys/class/net/wlan0").exists()) {
            String w0Flags = readFirstLineQuick(new File("/sys/class/net/wlan0/flags"));
            snap.builtinWlan0Up = (parseHexSafe(w0Flags, 0L) & 0x1L) != 0L;
            snap.builtinWlan0Ip = queryInterfaceIpv4("wlan0");
        }

        // 5. SoftAP (hostapd) & DHCP (dnsmasq) status
        File hpidFile = new File(tmpDir, "hostapd.pid");
        if (hpidFile.isFile()) {
            String hpid = readFirstLineQuick(hpidFile);
            if (isProcessAlive(hpid)) {
                snap.hostapdAlive = true;
                snap.hostapdPid = hpid;
            }
        }
        if (!snap.hostapdAlive) {
            String hpid = findPidByCmdline("usbwifi_hotspot/hostapd.conf");
            if (!hpid.isEmpty()) {
                snap.hostapdAlive = true;
                snap.hostapdPid = hpid;
            }
        }

        File dpidFile = new File(tmpDir, "dnsmasq.pid");
        if (dpidFile.isFile()) {
            String dpid = readFirstLineQuick(dpidFile);
            if (isProcessAlive(dpid)) {
                snap.dnsmasqAlive = true;
                snap.dnsmasqPid = dpid;
                snap.dnsmasqShared = false;
            }
        }
        if (!snap.dnsmasqAlive) {
            File spidFile = new File(tmpDir, "dnsmasq_shared.pid");
            if (spidFile.isFile()) {
                String spid = readFirstLineQuick(spidFile);
                if (isProcessAlive(spid)) {
                    snap.dnsmasqAlive = true;
                    snap.dnsmasqPid = spid;
                    snap.dnsmasqShared = true;
                }
            }
        }
        if (!snap.dnsmasqAlive) {
            String dpid = findPidByComm("dnsmasq");
            if (!dpid.isEmpty()) {
                snap.dnsmasqAlive = true;
                snap.dnsmasqPid = dpid;
                snap.dnsmasqShared = true;
            }
        }

        // 6. Connected Clients (from dnsmasq.leases + /proc/net/arp on usbIface)
        String subnetPrefix = "";
        if (!snap.ifaceIp.isEmpty()) {
            int lastDot = snap.ifaceIp.lastIndexOf('.');
            if (lastDot > 0) {
                subnetPrefix = snap.ifaceIp.substring(0, lastDot + 1);
            }
        }
        Map<String, String> clientMap = new LinkedHashMap<String, String>();
        File[] leaseFiles = new File[]{
                new File(tmpDir, "dnsmasq.leases"),
                new File("/data/misc/dhcp/dnsmasq.leases")
        };
        for (File lf : leaseFiles) {
            if (!lf.isFile()) continue;
            List<String> leases = readLinesQuick(lf, 60);
            for (String l : leases) {
                String[] parts = l.trim().split("\\s+");
                if (parts.length >= 4) {
                    String mac = parts[1];
                    String ip = parts[2];
                    if (!subnetPrefix.isEmpty() && !ip.startsWith(subnetPrefix)) {
                        continue;
                    }
                    String host = "*".equals(parts[3]) ? "기기" : parts[3];
                    clientMap.put(ip, ip + " (" + host + " / " + mac + ")");
                }
            }
        }
        if (!snap.usbIface.isEmpty()) {
            List<String> arpLines = readLinesQuick(new File("/proc/net/arp"), 100);
            for (String l : arpLines) {
                if (l.startsWith("IP address")) continue;
                String[] parts = l.trim().split("\\s+");
                if (parts.length >= 6) {
                    String ip = parts[0];
                    String flags = parts[2];
                    String mac = parts[3];
                    String dev = parts[5];
                    if (snap.usbIface.equals(dev) && !"0x0".equals(flags) && !"00:00:00:00:00:00".equals(mac)) {
                        if (!clientMap.containsKey(ip)) {
                            clientMap.put(ip, ip + " (ARP / " + mac + ")");
                        }
                    }
                }
            }
        }
        File usbStaFile = new File("/data/local/tmp/usb_sta.list");
        if (usbStaFile.isFile()) {
            List<String> staLines = readLinesQuick(usbStaFile, 50);
            for (String l : staLines) {
                String t = l.trim();
                if (t.length() >= 17 && t.indexOf(':') > 0) {
                    String mac = t.split("\\s+")[0];
                    boolean already = false;
                    for (String val : clientMap.values()) {
                        if (val.toLowerCase(Locale.US).contains(mac.toLowerCase(Locale.US))) {
                            already = true;
                            break;
                        }
                    }
                    if (!already) {
                        clientMap.put(mac, mac + " (Wi-Fi 연결됨)");
                    }
                }
            }
        }
        snap.connectedClients.addAll(clientMap.values());

        // 6b. Client (STA) Mode status, scan results & saved Wi-Fi networks
        File wpidFile = new File(tmpDir, "wpa_supplicant.pid");
        if (wpidFile.isFile()) {
            String wpid = readFirstLineQuick(wpidFile);
            if (isProcessAlive(wpid)) {
                snap.wpaSuppAlive = true;
                snap.wpaSuppPid = wpid;
            }
        }
        File wpaStatusFile = new File(tmpDir, "wpa_status.txt");
        if (wpaStatusFile.isFile()) {
            Map<String, String> wstat = parseKeyValueFile(wpaStatusFile);
            String wstate = getOrDefault(wstat, "wpa_state", "");
            if ("COMPLETED".equalsIgnoreCase(wstate)) {
                snap.staConnected = true;
                snap.staConnectedSsid = decodeWpaSsid(getOrDefault(wstat, "ssid", snap.staSsid));
                int freq = (int) parseLongSafe(wstat.get("freq"), 2412L);
                snap.staBand = freq >= 4900 ? "5GHz" : "2.4GHz";
            }
        }
        // The driver's ap_info keeps showing our own AP ("SSID=HKG_USB_Hotspot") after AP -> STA, so only
        // trust it once wpa_supplicant has moved the link to operstate "up" (4-way handshake completed).
        boolean staLinkUp = !snap.usbIface.isEmpty()
                && "up".equals(readFirstLineQuick(new File("/sys/class/net/" + snap.usbIface + "/operstate")));
        if (!snap.staConnected && staLinkUp) {
            File[] apInfoFiles = new File[]{
                    !snap.usbIface.isEmpty() ? new File("/proc/net/rtl88x2bu/" + snap.usbIface + "/ap_info") : new File(tmpDir, "sta_ap_info.txt"),
                    !snap.usbIface.isEmpty() ? new File("/proc/net/rtl8851bu/" + snap.usbIface + "/ap_info") : new File(tmpDir, "sta_ap_info.txt"),
                    new File(tmpDir, "sta_ap_info.txt")
            };
            for (File aif : apInfoFiles) {
                if (!aif.isFile()) continue;
                List<String> alines = readLinesQuick(aif, 20);
                for (String al : alines) {
                    String t = al.trim();
                    if (t.startsWith("SSID=")) {
                        String val = t.substring(5).trim();
                        if (!val.isEmpty() && !"(null)".equalsIgnoreCase(val)) {
                            snap.staConnected = true;
                            snap.staConnectedSsid = val;
                        }
                    } else if (t.startsWith("cur_channel=")) {
                        int comma = t.indexOf(',');
                        String chStr = (comma > 12 ? t.substring(12, comma) : t.substring(12)).trim();
                        int ch = (int) parseLongSafe(chStr, 1L);
                        snap.staBand = ch >= 36 ? "5GHz" : "2.4GHz";
                    }
                }
                if (snap.staConnected) break;
            }
        }
        File wpaSigFile = new File(tmpDir, "wpa_signal.txt");
        if (wpaSigFile.isFile()) {
            Map<String, String> wsig = parseKeyValueFile(wpaSigFile);
            String rssiVal = wsig.get("RSSI");
            if (rssiVal != null && !rssiVal.isEmpty()) {
                snap.staRssi = rssiVal + " dBm";
            }
        }
        if ("-- dBm".equals(snap.staRssi)) {
            File[] rxSigFiles = new File[]{
                    !snap.usbIface.isEmpty() ? new File("/proc/net/rtl88x2bu/" + snap.usbIface + "/rx_signal") : new File(tmpDir, "sta_rx_signal.txt"),
                    !snap.usbIface.isEmpty() ? new File("/proc/net/rtl8851bu/" + snap.usbIface + "/rx_signal") : new File(tmpDir, "sta_rx_signal.txt"),
                    new File(tmpDir, "sta_rx_signal.txt")
            };
            for (File rxf : rxSigFiles) {
                if (!rxf.isFile()) continue;
                List<String> rlines = readLinesQuick(rxf, 10);
                for (String rl : rlines) {
                    String t = rl.trim();
                    if (t.startsWith("rssi:")) {
                        long rv = parseLongSafe(t.substring(5).trim(), 0L);
                        if (rv < 0L) {
                            snap.staRssi = rv + " dBm";
                            break;
                        } else if (rv > 0L) {
                            snap.staRssi = (rv - 100L) + " dBm";
                            break;
                        }
                    }
                }
                if (!"-- dBm".equals(snap.staRssi)) break;
            }
        }
        Map<String, ScanEntry> dedup = new LinkedHashMap<String, ScanEntry>();
        File scanFile = new File(tmpDir, "sta_scan.txt");
        if (scanFile.isFile()) {
            List<String> scanLines = readLinesQuick(scanFile, 80);
            for (String sl : scanLines) {
                String t = sl.trim();
                if (t.isEmpty() || t.startsWith("Selected interface") || t.startsWith("bssid")) continue;
                String[] cols = t.split("\\t+");
                if (cols.length < 5) {
                    cols = t.split("\\s+", 5);
                }
                if (cols.length >= 5) {
                    int freq = (int) parseLongSafe(cols[1], 2412L);
                    int rssi = (int) parseLongSafe(cols[2], -75L);
                    String flags = cols[3];
                    String ssid = cols[4].trim();
                    if (ssid.isEmpty() || ssid.startsWith("\\x00")) continue;
                    ssid = decodeWpaSsid(ssid);
                    ScanEntry existing = dedup.get(ssid);
                    if (existing == null || rssi > existing.rssi) {
                        ScanEntry se = new ScanEntry();
                        se.ssid = ssid;
                        se.band = freq >= 4900 ? "5G" : "2.4G";
                        se.rssi = rssi;
                        se.secured = flags.contains("WPA") || flags.contains("WEP") || flags.contains("RSN");
                        dedup.put(ssid, se);
                    }
                }
            }
        }
        File[] surveyFiles = new File[]{
                !snap.usbIface.isEmpty() ? new File("/proc/net/rtl88x2bu/" + snap.usbIface + "/survey_info") : new File(tmpDir, "sta_survey.txt"),
                !snap.usbIface.isEmpty() ? new File("/proc/net/rtl8851bu/" + snap.usbIface + "/survey_info") : new File(tmpDir, "sta_survey.txt"),
                new File(tmpDir, "sta_survey.txt")
        };
        for (File svf : surveyFiles) {
            if (!svf.isFile()) continue;
            List<String> svLines = readLinesQuick(svf, 80);
            int chCol = 2;
            int dbmCol = 4;
            for (String sl : svLines) {
                String t = sl.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("index")) {
                    int[] hc = parseSurveyHeader(t);
                    chCol = hc[0];
                    dbmCol = hc[1];
                    continue;
                }
                SurveyRow row = parseSurveyRow(t, chCol, dbmCol);
                if (row == null) continue;
                String ssid = row.ssid;
                if (ssid.isEmpty() || ssid.startsWith("\\x00")) continue;
                ScanEntry existing = dedup.get(ssid);
                if (existing == null || row.dbm > existing.rssi) {
                    ScanEntry se = new ScanEntry();
                    se.ssid = ssid;
                    se.band = row.ch >= 36 ? "5G" : "2.4G";
                    se.rssi = row.dbm;
                    se.secured = row.flags.contains("WPA") || row.flags.contains("WEP") || row.flags.contains("RSN");
                    dedup.put(ssid, se);
                }
            }
            if (!dedup.isEmpty()) break;
        }
        snap.staScanList.addAll(dedup.values());
        File savedStaFile = new File(installDir, "config/saved_sta.conf");
        if (savedStaFile.isFile()) {
            List<String> slines = readLinesQuick(savedStaFile, 20);
            for (String sl : slines) {
                int eq = sl.indexOf('=');
                String sName = (eq > 0 ? sl.substring(0, eq) : sl).trim();
                if (!sName.isEmpty() && !snap.savedStaList.contains(sName)) {
                    snap.savedStaList.add(sName);
                }
            }
        }
        if (!snap.staSsid.isEmpty() && !snap.savedStaList.contains(snap.staSsid)) {
            snap.savedStaList.add(0, snap.staSsid);
        }

        // 7. CPU & RAM usage
        snap.cpuLoadPct = StatusDashboardOverlay.sampleCpuUsagePercent();
        snap.ramSummary = StatusDashboardOverlay.readRamUsageSummary();

        // 8. Recent logs from daemon.log & hostapd.log / wpa_supplicant.log
        List<String> dLogs = readTailLines(new File(tmpDir, "daemon.log"), 4);
        for (String dl : dLogs) {
            snap.recentLogs.add("[데몬] " + compactLogLine(dl));
        }
        if ("sta".equals(snap.wifiMode)) {
            List<String> wLogs = readTailLines(new File(tmpDir, "wpa_supplicant.log"), 3);
            for (String wl : wLogs) {
                snap.recentLogs.add("[STA] " + compactLogLine(wl));
            }
        } else {
            List<String> hLogs = readTailLines(new File(tmpDir, "hostapd.log"), 3);
            for (String hl : hLogs) {
                snap.recentLogs.add("[AP] " + compactLogLine(hl));
            }
        }
        if (snap.recentLogs.isEmpty()) {
            snap.recentLogs.add("(아직 기록된 로그가 없습니다)");
        }

        return snap;
    }

    public static String compactLogLine(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Strip verbose date prefix like "Sat Oct 31 14:13:50 +09:00 2026 " -> "14:13:50 "
        int plusIdx = s.indexOf("+09:00");
        if (plusIdx > 8) {
            String before = s.substring(0, plusIdx).trim();
            int lastSpace = before.lastIndexOf(' ');
            String timePart = lastSpace >= 0 ? before.substring(lastSpace + 1) : before;
            int afterYear = s.indexOf(' ', plusIdx + 7);
            if (afterYear > 0 && afterYear + 1 < s.length()) {
                s = timePart + " " + s.substring(afterYear + 1).trim();
            }
        }
        if (s.length() > 68) {
            s = s.substring(0, 65) + "...";
        }
        return s;
    }

    public static String findUsbWifiInterface() {
        File netRoot = new File("/sys/class/net");
        File[] nets = netRoot.listFiles();
        if (nets == null) return "";
        for (File net : nets) {
            String ifname = net.getName();
            if (ifname.startsWith("lo") || ifname.startsWith("dummy") || ifname.startsWith("sit")
                    || ifname.startsWith("ip6tnl") || ifname.startsWith("rmnet")
                    || ifname.equals("eth0") || ifname.startsWith("p2p") || ifname.equals("rtwlan1")) {
                continue;
            }
            boolean isWireless = new File(net, "wireless").isDirectory() || new File(net, "phy80211").exists();
            if (!isWireless) continue;
            try {
                String canonNet = net.getCanonicalPath().toLowerCase(Locale.US);
                File devLink = new File(net, "device");
                String canonDev = devLink.exists() ? devLink.getCanonicalPath().toLowerCase(Locale.US) : "";
                File drvLink = new File(net, "device/driver");
                String canonDrv = drvLink.exists() ? drvLink.getCanonicalPath().toLowerCase(Locale.US) : "";
                String combined = canonNet + " " + canonDev + " " + canonDrv;
                if (combined.contains("usb") || combined.contains("dwc_otg")
                        || combined.contains("ehci") || combined.contains("ohci")
                        || combined.contains("88x2bu") || combined.contains("8851bu")) {
                    return ifname;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String queryInterfaceIpv4(String iface) {
        if (iface == null || iface.isEmpty()) return "";
        try {
            NetworkInterface nif = NetworkInterface.getByName(iface);
            if (nif != null) {
                List<InterfaceAddress> ifAddrs = nif.getInterfaceAddresses();
                if (ifAddrs != null) {
                    for (InterfaceAddress ia : ifAddrs) {
                        InetAddress addr = ia.getAddress();
                        if (addr instanceof Inet4Address) {
                            short prefix = ia.getNetworkPrefixLength();
                            String hostAddr = addr.getHostAddress();
                            return prefix > 0 ? (hostAddr + "/" + prefix) : hostAddr;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String out = execCommandCapture(new String[]{"/system/bin/ip", "-4", "addr", "show", "dev", iface}, 50);
        if (out.isEmpty()) return "";
        String[] lines = out.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("inet ")) {
                String[] parts = t.split("\\s+");
                if (parts.length >= 2) {
                    return parts[1];
                }
            }
        }
        return "";
    }

    private static long[] readNetDevStats(String iface) {
        long[] res = new long[]{0L, 0L, 0L, 0L};
        List<String> lines = readLinesQuick(new File("/proc/net/dev"), 60);
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            if (iface.equals(name)) {
                String rest = line.substring(colon + 1).trim();
                String[] parts = rest.split("\\s+");
                if (parts.length >= 10) {
                    res[0] = parseLongSafe(parts[0], 0L); // rx_bytes
                    res[1] = parseLongSafe(parts[1], 0L); // rx_packets
                    res[2] = parseLongSafe(parts[8], 0L); // tx_bytes
                    res[3] = parseLongSafe(parts[9], 0L); // tx_packets
                }
                break;
            }
        }
        return res;
    }

    private static boolean isProcessAlive(String pid) {
        if (pid == null) return false;
        String t = pid.trim();
        if (t.isEmpty() || !t.matches("\\d+")) return false;
        return new File("/proc/" + t).isDirectory();
    }

    private static boolean isProcessMatching(String pid, String keyword) {
        if (!isProcessAlive(pid)) return false;
        File cmdFile = new File("/proc/" + pid.trim() + "/cmdline");
        if (!cmdFile.isFile()) return false;
        try {
            byte[] raw = readAllBytes(new FileInputStream(cmdFile));
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) raw[i] = (byte) ' ';
            }
            String cmd = new String(raw, UTF8);
            return cmd.contains(keyword);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String findPidByCmdline(String keyword) {
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) return "";
        for (File e : entries) {
            String name = e.getName();
            if (!name.matches("\\d+")) continue;
            if (isProcessMatching(name, keyword)) {
                return name;
            }
        }
        return "";
    }

    private static String findPidByComm(String targetComm) {
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) return "";
        for (File e : entries) {
            String name = e.getName();
            if (!name.matches("\\d+")) continue;
            String comm = readFirstLineQuick(new File(e, "comm"));
            if (targetComm.equals(comm)) {
                return name;
            }
        }
        return "";
    }

    // =========================================================================
    // SYSTEM WINDOWMANAGER OVERLAY UI (Gen5W Android 4.4.2)
    // =========================================================================

    public static final class StatusDashboardOverlay {
        private static volatile String installDir = "/data/nemesis/apps/usbwifi.hotspot";
        private static volatile Object uiHandler = null;
        private static volatile Object windowManager = null;
        private static volatile Object sysContext = null;
        private static volatile Object rootOverlayView = null;

        private static volatile Object mainScreenBox = null;
        private static volatile Object editModalBox = null;

        private static volatile Object tabModeApBtn = null;
        private static volatile Object tabModeStaBtn = null;
        private static volatile Object statusPillTv = null;
        private static volatile Object expertToggleBtn = null;

        private static volatile Object apViewBox = null;
        private static volatile Object staViewBox = null;
        private static volatile Object expertViewBox = null;

        // AP Mode Views
        private static volatile Object apSsidTv = null;
        private static volatile Object apSsidHintTv = null;
        private static volatile Object apBandDescTv = null;
        private static volatile Object band24Btn = null;
        private static volatile Object band5Btn = null;
        private static volatile Object pwMaskToggleBtn = null;
        private static volatile Object apPassTv = null;
        private static volatile Object apClientCountBadgeTv = null;
        private static volatile Object apClientListTv = null;
        private static volatile Object apTrafficTv = null;

        // STA (Client) Mode Views
        private static volatile Object staConnBadgeTv = null;
        private static volatile Object staConnectedSsidTv = null;
        private static volatile Object staConnectedDetailTv = null;
        private static final Object[] staScanRowViews = new Object[4];
        private static final Object[] staScanRowTitleTvs = new Object[4];
        private static final Object[] staScanRowSubTvs = new Object[4];
        private static final Object[] staScanRowBandTvs = new Object[4];
        private static final String[] staScanRowSsids = new String[]{"", "", "", ""};
        private static volatile Object staSavedListTv = null;
        private static volatile Object staTrafficTv = null;

        // Expert Mode & OTA Update Views
        private static volatile Object otaUpdateBtn = null;
        private static volatile Object autoUpdateToggleBtn = null;
        private static volatile Object headerSysInfoTextView = null;
        private static volatile Object leftColTextView = null;
        private static volatile Object rightColTextView = null;
        private static volatile Object subnetCycleBtn = null;

        // OTA State
        private static volatile boolean otaCheckingOrUpdating = false;
        private static volatile boolean otaUpdateAvailable = false;
        private static volatile String otaRemoteVersion = UI_VERSION;
        private static volatile String otaStatusSummary = "자동 확인 대기 중";
        private static volatile long lastManualOtaTapMs = 0L;

        // Footer Views
        private static volatile Object footerStatusTextView = null;
        private static volatile Object footerTimerTextView = null;

        // Touch Keyboard Modal Views & State
        private static volatile boolean isEditModalOpen = false;
        private static volatile String modalContext = "ap"; // "ap" or "sta"
        private static volatile String activeEditField = "ssid"; // "ssid" or "pass"
        private static volatile String editingSsid = "HKG_USB_Hotspot";
        private static volatile String editingPass = "12345678";
        private static volatile boolean kbUpper = true;
        private static volatile Object modalTitleTv = null;
        private static volatile Object modalValidationTv = null;
        private static volatile Object modalSaveBtn = null;
        private static volatile Object modalSsidFieldBox = null;
        private static volatile Object modalPassFieldBox = null;
        private static volatile Object modalSsidLenTv = null;
        private static volatile Object modalPassLenTv = null;
        private static volatile Object modalSsidValueTv = null;
        private static volatile Object modalPassValueTv = null;
        private static volatile Object kbShiftBtn = null;
        private static final List<Object> kbLetterBtns = new ArrayList<Object>();
        private static final List<String> kbLetterBaseChars = new ArrayList<String>();

        // UI State
        private static volatile boolean isVisible = false;
        private static volatile boolean isExpertView = false;
        private static volatile boolean isPwMasked = false;
        private static volatile long shownAtMs = 0L;
        private static volatile String actionBannerMsg = "💡 상단 좌측 스위치로 [📡 핫스팟 모드]와 [📶 와이파이 수신 모드]를 언제든 전환할 수 있습니다.";
        private static volatile long lastCpuTotalTicks = 0L;
        private static volatile long lastCpuIdleTicks = 0L;
        private static volatile int lastCpuLoadPct = 0;
        private static volatile SystemSnapshot latestSnapshot = null;

        private static final Runnable REFRESH_TASK = new Runnable() {
            @Override
            public void run() {
                refreshOnUiThread();
            }
        };

        public static void init(final String dir) {
            installDir = dir;
            Thread samplerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            if (isVisible) {
                                latestSnapshot = collectSnapshot(installDir);
                                postToUi(REFRESH_TASK);
                            }
                            Thread.sleep(1000L);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "UsbWifiSnapshotWorker");
            samplerThread.setDaemon(true);
            samplerThread.start();

            Thread otaWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Wait 45s after boot/daemon start so LTE (rmnet1) or STA Wi-Fi (rtwlan0) is ready
                        Thread.sleep(45000L);
                    } catch (Throwable ignored) {
                    }
                    while (true) {
                        try {
                            if (!isEditModalOpen && !otaCheckingOrUpdating) {
                                Map<String, String> conf = parseKeyValueFile(new File(installDir, "config/hotspot.conf"));
                                boolean autoOn = !"0".equals(stripQuotes(getOrDefault(conf, "AUTO_UPDATE", "1")));
                                checkAndApplyGitHubOta(installDir, autoOn, false, true);
                            }
                        } catch (Throwable ignored) {
                        }
                        try {
                            // Re-check GitHub every 3 hours in background
                            Thread.sleep(3L * 3600L * 1000L);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "UsbWifiOtaAutoWorker");
            otaWorker.setDaemon(true);
            otaWorker.start();

            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> looperCls = Class.forName("android.os.Looper");
                        try {
                            looperCls.getMethod("prepareMainLooper").invoke(null);
                        } catch (Throwable ignored) {
                            looperCls.getMethod("prepare").invoke(null);
                        }
                        Object myLooper = looperCls.getMethod("myLooper").invoke(null);
                        Class<?> handlerCls = Class.forName("android.os.Handler");
                        uiHandler = handlerCls.getConstructor(looperCls).newInstance(myLooper);

                        System.out.println("[USBWifiUI] StatusDashboardOverlay UI Looper initialized.");
                        Method loopMethod = looperCls.getMethod("loop");
                        while (true) {
                            try {
                                loopMethod.invoke(null);
                                break;
                            } catch (Throwable loopErr) {
                                Throwable cause = (loopErr.getCause() != null) ? loopErr.getCause() : loopErr;
                                System.out.println("[USBWifiUI] UI Looper caught exception (resuming loop): " + cause);
                            }
                        }
                    } catch (Throwable t) {
                        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
                        System.out.println("[USBWifiUI] StatusDashboardOverlay init failed: " + cause);
                    }
                }
            }, "UsbWifiStatusUiThread");
            uiThread.setDaemon(true);
            uiThread.start();

            MapButtonLongPressMonitor.start(dir);
        }

        public static void showFromMapLongPress(final String detail) {
            try {
                if (latestSnapshot == null) {
                    latestSnapshot = collectSnapshot(installDir);
                }
            } catch (Throwable ignored) {
            }
            actionBannerMsg = "🗺️ 헤드유닛 [MAP] 버튼 3초 롱프레스 감지 — 대시보드가 실행되었습니다. (" + detail + ")";
            show();
        }

        public static void ensureTopMostAfterKeyUp() {
            postDelayedToUi(new Runnable() {
                @Override
                public void run() {
                    if (isVisible) {
                        showOnUiThread();
                    }
                }
            }, 250L);
        }

        public static void hideIfOpenForMoreThan(final long minOpenMs) {
            if (!isVisible) return;
            long now = System.currentTimeMillis();
            if (shownAtMs > 0L && (now - shownAtMs) >= minOpenMs) {
                hide();
            }
        }

        private static boolean ensureUiContextOnUiThread() {
            if (sysContext != null && windowManager != null) {
                return true;
            }
            try {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                Method getSvc = smCls.getMethod("getService", String.class);
                if (getSvc.invoke(null, "window") == null
                        || getSvc.invoke(null, "display") == null
                        || getSvc.invoke(null, "activity") == null) {
                    return false;
                }

                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = null;
                try {
                    at = atCls.getMethod("currentActivityThread").invoke(null);
                } catch (Throwable ignored) {
                }
                if (at == null) {
                    at = atCls.getMethod("systemMain").invoke(null);
                }
                if (at != null && sysContext == null) {
                    sysContext = atCls.getMethod("getSystemContext").invoke(at);
                }
                if (sysContext != null && windowManager == null) {
                    Method getSysSvc = sysContext.getClass().getMethod("getSystemService", String.class);
                    windowManager = getSysSvc.invoke(sysContext, "window");
                }
            } catch (Throwable t) {
                sysContext = null;
                windowManager = null;
                try {
                    Class<?> atCls = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Field fSysCtx = atCls.getDeclaredField("mSystemContext");
                    fSysCtx.setAccessible(true);
                    fSysCtx.set(null, null);
                    java.lang.reflect.Field fCurAt = atCls.getDeclaredField("sCurrentActivityThread");
                    fCurAt.setAccessible(true);
                    fCurAt.set(null, null);
                } catch (Throwable ignored) {
                }
            }
            return sysContext != null && windowManager != null;
        }

        public static void show() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    showOnUiThread();
                }
            });
        }

        public static void hide() {
            postToUi(new Runnable() {
                @Override
                public void run() {
                    hideOnUiThread();
                }
            });
        }

        private static void postToUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("post", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static void postDelayedToUi(Runnable r, long delayMs) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("postDelayed", Runnable.class, long.class).invoke(h, r, delayMs);
            } catch (Throwable ignored) {
            }
        }

        private static void removeCallbacksFromUi(Runnable r) {
            Object h = uiHandler;
            if (h == null) return;
            try {
                h.getClass().getMethod("removeCallbacks", Runnable.class).invoke(h, r);
            } catch (Throwable ignored) {
            }
        }

        private static Object buildDashboardViewTree() throws Exception {
            Class<?> ctxCls = Class.forName("android.content.Context");
            Class<?> viewCls = Class.forName("android.view.View");
            Class<?> vgCls = Class.forName("android.view.ViewGroup");
            Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
            Class<?> llCls = Class.forName("android.widget.LinearLayout");
            Class<?> llLpCls = Class.forName("android.widget.LinearLayout$LayoutParams");
            Class<?> tvCls = Class.forName("android.widget.TextView");

            Constructor<?> llCtor = llCls.getConstructor(ctxCls);
            Constructor<?> tvCtor = tvCls.getConstructor(ctxCls);
            Constructor<?> llLpCtor2 = llLpCls.getConstructor(int.class, int.class);
            Constructor<?> llLpCtor3 = llLpCls.getConstructor(int.class, int.class, float.class);
            Method tvSetGravity = tvCls.getMethod("setGravity", int.class);
            Method setVis = viewCls.getMethod("setVisibility", int.class);
            Method setPad = viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class);
            Method addView = vgCls.getMethod("addView", viewCls, vgLpCls);
            Method setOrient = llCls.getMethod("setOrientation", int.class);
            Method llSetGrav = llCls.getMethod("setGravity", int.class);

            final int MATCH_PARENT = -1;
            final int WRAP_CONTENT = -2;

            kbLetterBtns.clear();
            kbLetterBaseChars.clear();

            // Full-Screen Root Container (8:3 wide aspect ratio navigation screen)
            Object root = llCtor.newInstance(sysContext);
            setOrient.invoke(root, 1); // VERTICAL
            setPad.invoke(root, 24, 14, 24, 14);
            setRoundedBg(root, 0xFF0B1120, 0xFF1E293B, 1, 0f);
            disableViewEffects(root, true);

            // =====================================================================
            // MAIN SCREEN CONTAINER (Header + 3-Col Body + Footer)
            // =====================================================================
            mainScreenBox = llCtor.newInstance(sysContext);
            setOrient.invoke(mainScreenBox, 1); // VERTICAL

            // 1. Top Unified Header Row
            Object headerRow = llCtor.newInstance(sysContext);
            setOrient.invoke(headerRow, 0); // HORIZONTAL
            llSetGrav.invoke(headerRow, 16); // CENTER_VERTICAL

            // Mode Switcher Pill Box ([핫스팟 모드 (AP)] <-> [와이파이 수신 (Client)])
            Object modeSwitchBox = llCtor.newInstance(sysContext);
            setOrient.invoke(modeSwitchBox, 0);
            setPad.invoke(modeSwitchBox, 4, 4, 4, 4);
            setRoundedBg(modeSwitchBox, 0xFF020617, 0xFF334155, 1, 12f);

            tabModeApBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(tabModeApBtn, "📡 핫스팟 모드 (AP)", 13.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(tabModeApBtn, 17);
            setPad.invoke(tabModeApBtn, 16, 8, 16, 8);
            setRoundedBg(tabModeApBtn, 0xFF0284C7, 0xFF38BDF8, 1, 9f);
            attachClickListener(tabModeApBtn, new Runnable() {
                @Override
                public void run() {
                    switchWifiModeFromUi("ap");
                }
            });
            addView.invoke(modeSwitchBox, tabModeApBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

            tabModeStaBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(tabModeStaBtn, "📶 와이파이 수신 (Client)", 13.5f, 0xFF94A3B8, true);
            tvSetGravity.invoke(tabModeStaBtn, 17);
            setPad.invoke(tabModeStaBtn, 16, 8, 16, 8);
            setRoundedBg(tabModeStaBtn, 0x00000000, 0x00000000, 0, 9f);
            attachClickListener(tabModeStaBtn, new Runnable() {
                @Override
                public void run() {
                    switchWifiModeFromUi("sta");
                }
            });
            Object staTabLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(staTabLp, 4, 0, 0, 0);
            addView.invoke(modeSwitchBox, tabModeStaBtn, staTabLp);
            addView.invoke(headerRow, modeSwitchBox, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

            // Status Pill Badge (no LTE/hotspot sharing sub-phrase per user request)
            statusPillTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(statusPillTv, "● 핫스팟 신호 송출 중", 13.5f, 0xFF6EE7B7, true);
            tvSetGravity.invoke(statusPillTv, 17);
            setPad.invoke(statusPillTv, 16, 7, 16, 7);
            setRoundedBg(statusPillTv, 0x3310B981, 0x6634D399, 1, 20f);
            Object pillLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(pillLp, 12, 0, 12, 0);
            addView.invoke(headerRow, statusPillTv, pillLp);

            // Header Flexible Spacer
            Object hdrSpacer = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            addView.invoke(headerRow, hdrSpacer, llLpCtor3.newInstance(0, 1, 1.0f));

            // Header Action Buttons: [☁️ 업데이트] [🔄 재시작] [💾 로그 저장] [🔧 상세 정보] [✖ 닫기]
            otaUpdateBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(otaUpdateBtn, "☁️ 업데이트", 13.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(otaUpdateBtn, 17);
            setPad.invoke(otaUpdateBtn, 16, 9, 16, 9);
            setRoundedBg(otaUpdateBtn, 0xFF4F46E5, 0xFF818CF8, 1, 11f);
            attachClickListener(otaUpdateBtn, new Runnable() {
                @Override
                public void run() {
                    triggerGitHubOtaFromUi();
                }
            });
            Object otaBtnLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(otaBtnLp, 0, 0, 8, 0);
            addView.invoke(headerRow, otaUpdateBtn, otaBtnLp);

            Object restartBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(restartBtn, "🔄 재시작", 13.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(restartBtn, 17);
            setPad.invoke(restartBtn, 16, 9, 16, 9);
            setRoundedBg(restartBtn, 0xFF0284C7, 0xFF38BDF8, 1, 11f);
            attachClickListener(restartBtn, new Runnable() {
                @Override
                public void run() {
                    triggerRestartHotspotFromUi();
                }
            });
            Object rBtnLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(rBtnLp, 0, 0, 8, 0);
            addView.invoke(headerRow, restartBtn, rBtnLp);

            Object dumpBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(dumpBtn, "💾 로그 저장", 13.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(dumpBtn, 17);
            setPad.invoke(dumpBtn, 16, 9, 16, 9);
            setRoundedBg(dumpBtn, 0xFF059669, 0xFF34D399, 1, 11f);
            attachClickListener(dumpBtn, new Runnable() {
                @Override
                public void run() {
                    triggerExportLogsFromUi();
                }
            });
            Object dBtnLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(dBtnLp, 0, 0, 8, 0);
            addView.invoke(headerRow, dumpBtn, dBtnLp);

            expertToggleBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(expertToggleBtn, "🔧 상세 정보", 13.5f, 0xFFE2E8F0, true);
            tvSetGravity.invoke(expertToggleBtn, 17);
            setPad.invoke(expertToggleBtn, 16, 9, 16, 9);
            setRoundedBg(expertToggleBtn, 0xFF1E293B, 0xFF475569, 1, 11f);
            attachClickListener(expertToggleBtn, new Runnable() {
                @Override
                public void run() {
                    isExpertView = !isExpertView;
                    postToUi(REFRESH_TASK);
                }
            });
            Object eBtnLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(eBtnLp, 0, 0, 8, 0);
            addView.invoke(headerRow, expertToggleBtn, eBtnLp);

            Object closeBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(closeBtn, "✖ 닫기", 14.0f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(closeBtn, 17);
            setPad.invoke(closeBtn, 20, 9, 20, 9);
            setRoundedBg(closeBtn, 0xFFE11D48, 0xFFFDA4AF, 1, 11f);
            attachClickListener(closeBtn, new Runnable() {
                @Override
                public void run() {
                    hide();
                }
            });
            addView.invoke(headerRow, closeBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(mainScreenBox, headerRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // Header Divider
            Object div1 = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            viewCls.getMethod("setBackgroundColor", int.class).invoke(div1, 0xFF1E293B);
            Object divLp1 = llLpCtor2.newInstance(MATCH_PARENT, 2);
            setMargins(divLp1, 0, 10, 0, 10);
            addView.invoke(mainScreenBox, div1, divLp1);

            // =====================================================================
            // 2-A. AP MODE VIEW (Horizontal 3-Column 4:4:4 Wide Layout)
            // =====================================================================
            apViewBox = llCtor.newInstance(sysContext);
            setOrient.invoke(apViewBox, 0); // HORIZONTAL

            // [AP Col 1: SSID & Band]
            Object apCol1 = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol1, 1);
            setPad.invoke(apCol1, 16, 14, 16, 14);
            setRoundedBg(apCol1, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object apCol1Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol1Hdr, 0);
            llSetGrav.invoke(apCol1Hdr, 16);
            Object apCol1Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(apCol1Title, "1. 핫스팟 이름 (SSID)", 13.0f, 0xFF38BDF8, true);
            addView.invoke(apCol1Hdr, apCol1Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object editSsidBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(editSsidBtn, "✏️ 이름 변경", 12.5f, 0xFFFFFFFF, true);
            setPad.invoke(editSsidBtn, 12, 6, 12, 6);
            setRoundedBg(editSsidBtn, 0xFF0284C7, 0xFF38BDF8, 1, 8f);
            attachClickListener(editSsidBtn, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("ap_ssid");
                }
            });
            addView.invoke(apCol1Hdr, editSsidBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol1, apCol1Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object ssidBox = llCtor.newInstance(sysContext);
            setOrient.invoke(ssidBox, 1);
            setPad.invoke(ssidBox, 14, 12, 14, 12);
            setRoundedBg(ssidBox, 0xFF0F172A, 0xFF334155, 1, 12f);
            attachClickListener(ssidBox, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("ap_ssid");
                }
            });
            apSsidTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apSsidTv, "HKG_USB_Hotspot", 20.0f, 0xFFFFFFFF, true);
            addView.invoke(ssidBox, apSsidTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
            apSsidHintTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apSsidHintTv, "✔ 휴대폰 Wi-Fi 목록에서 이 이름을 선택하세요", 12.0f, 0xFF34D399, true);
            Object hintLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(hintLp, 0, 6, 0, 0);
            addView.invoke(ssidBox, apSsidHintTv, hintLp);
            Object ssidBoxLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(ssidBoxLp, 0, 10, 0, 0);
            addView.invoke(apCol1, ssidBox, ssidBoxLp);

            addView.invoke(apCol1, viewCls.getConstructor(ctxCls).newInstance(sysContext), llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            Object bandHdrRow = llCtor.newInstance(sysContext);
            setOrient.invoke(bandHdrRow, 0);
            Object bandLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(bandLbl, "송출 주파수 대역", 12.5f, 0xFFE2E8F0, true);
            addView.invoke(bandHdrRow, bandLbl, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            apBandDescTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apBandDescTv, "2.4GHz (넓은 범위)", 12.0f, 0xFF7DD3FC, true);
            addView.invoke(bandHdrRow, apBandDescTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol1, bandHdrRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object bandSwitchRow = llCtor.newInstance(sysContext);
            setOrient.invoke(bandSwitchRow, 0);
            setPad.invoke(bandSwitchRow, 4, 4, 4, 4);
            setRoundedBg(bandSwitchRow, 0xFF020617, 0xFF334155, 1, 11f);
            band24Btn = tvCtor.newInstance(sysContext);
            setTextViewStyle(band24Btn, "2.4GHz 일반", 13.0f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(band24Btn, 17);
            setPad.invoke(band24Btn, 8, 9, 8, 9);
            setRoundedBg(band24Btn, 0xFF0284C7, 0xFF38BDF8, 1, 8f);
            attachClickListener(band24Btn, new Runnable() {
                @Override
                public void run() {
                    selectBandFromUi("g");
                }
            });
            addView.invoke(bandSwitchRow, band24Btn, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

            band5Btn = tvCtor.newInstance(sysContext);
            setTextViewStyle(band5Btn, "5GHz 고속", 13.0f, 0xFF94A3B8, true);
            tvSetGravity.invoke(band5Btn, 17);
            setPad.invoke(band5Btn, 8, 9, 8, 9);
            setRoundedBg(band5Btn, 0x00000000, 0x00000000, 0, 8f);
            attachClickListener(band5Btn, new Runnable() {
                @Override
                public void run() {
                    selectBandFromUi("a");
                }
            });
            addView.invoke(bandSwitchRow, band5Btn, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object bswLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(bswLp, 0, 6, 0, 0);
            addView.invoke(apCol1, bandSwitchRow, bswLp);

            Object apCol1Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 4.0f);
            setMargins(apCol1Lp, 0, 0, 8, 0);
            addView.invoke(apViewBox, apCol1, apCol1Lp);

            // [AP Col 2: Password & Combined Editor]
            Object apCol2 = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol2, 1);
            setPad.invoke(apCol2, 16, 14, 16, 14);
            setRoundedBg(apCol2, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object apCol2Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol2Hdr, 0);
            llSetGrav.invoke(apCol2Hdr, 16);
            Object apCol2Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(apCol2Title, "2. 핫스팟 비밀번호", 13.0f, 0xFFFBBF24, true);
            addView.invoke(apCol2Hdr, apCol2Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

            pwMaskToggleBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(pwMaskToggleBtn, "숨김", 12.0f, 0xFFE2E8F0, true);
            setPad.invoke(pwMaskToggleBtn, 10, 6, 10, 6);
            setRoundedBg(pwMaskToggleBtn, 0xFF0F172A, 0xFF475569, 1, 8f);
            attachClickListener(pwMaskToggleBtn, new Runnable() {
                @Override
                public void run() {
                    isPwMasked = !isPwMasked;
                    postToUi(REFRESH_TASK);
                }
            });
            Object pwMaskLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(pwMaskLp, 0, 0, 6, 0);
            addView.invoke(apCol2Hdr, pwMaskToggleBtn, pwMaskLp);

            Object editPassBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(editPassBtn, "✏️ 비밀번호 변경", 12.5f, 0xFFFFFFFF, true);
            setPad.invoke(editPassBtn, 12, 6, 12, 6);
            setRoundedBg(editPassBtn, 0xFFD97706, 0xFFFBBF24, 1, 8f);
            attachClickListener(editPassBtn, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("ap_pass");
                }
            });
            addView.invoke(apCol2Hdr, editPassBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol2, apCol2Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object passBox = llCtor.newInstance(sysContext);
            setOrient.invoke(passBox, 1);
            setPad.invoke(passBox, 14, 12, 14, 12);
            setRoundedBg(passBox, 0xFF0F172A, 0xFF334155, 1, 12f);
            attachClickListener(passBox, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("ap_pass");
                }
            });
            apPassTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apPassTv, "12345678", 20.0f, 0xFFFCD34D, true);
            addView.invoke(passBox, apPassTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
            Object passSubTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(passSubTv, "🔒 WPA2 보안 (8자리 이상)", 12.0f, 0xFF94A3B8, false);
            Object passSubLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(passSubLp, 0, 6, 0, 0);
            addView.invoke(passBox, passSubTv, passSubLp);
            Object passBoxLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(passBoxLp, 0, 10, 0, 0);
            addView.invoke(apCol2, passBox, passBoxLp);

            addView.invoke(apCol2, viewCls.getConstructor(ctxCls).newInstance(sysContext), llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            Object easyHdrRow = llCtor.newInstance(sysContext);
            setOrient.invoke(easyHdrRow, 0);
            Object easyLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(easyLbl, "간편 설정", 12.5f, 0xFFE2E8F0, true);
            addView.invoke(easyHdrRow, easyLbl, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object easySub = tvCtor.newInstance(sysContext);
            setTextViewStyle(easySub, "차량용 화면 키보드 내장", 12.0f, 0xFF94A3B8, false);
            addView.invoke(easyHdrRow, easySub, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol2, easyHdrRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object editBothBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(editBothBtn, "✏️ 핫스팟 이름 · 비밀번호 한번에 수정하기", 13.0f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(editBothBtn, 17);
            setPad.invoke(editBothBtn, 12, 11, 12, 11);
            setRoundedBg(editBothBtn, 0xFF4F46E5, 0xFF818CF8, 1, 11f);
            attachClickListener(editBothBtn, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("ap_both");
                }
            });
            Object ebLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(ebLp, 0, 6, 0, 0);
            addView.invoke(apCol2, editBothBtn, ebLp);

            Object apCol2Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 4.0f);
            setMargins(apCol2Lp, 8, 0, 8, 0);
            addView.invoke(apViewBox, apCol2, apCol2Lp);

            // [AP Col 3: Connected Devices & Data Traffic]
            Object apCol3 = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol3, 1);
            setPad.invoke(apCol3, 16, 14, 16, 14);
            setRoundedBg(apCol3, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object apCol3Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(apCol3Hdr, 0);
            llSetGrav.invoke(apCol3Hdr, 16);
            Object apCol3Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(apCol3Title, "3. 내 차에 연결된 기기", 13.0f, 0xFF34D399, true);
            addView.invoke(apCol3Hdr, apCol3Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            apClientCountBadgeTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apClientCountBadgeTv, "0대 연결됨", 12.0f, 0xFF6EE7B7, true);
            setPad.invoke(apClientCountBadgeTv, 10, 4, 10, 4);
            setRoundedBg(apClientCountBadgeTv, 0x3310B981, 0x6634D399, 1, 12f);
            addView.invoke(apCol3Hdr, apClientCountBadgeTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol3, apCol3Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            apClientListTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apClientListTv, "현재 연결된 기기가 없습니다.", 13.5f, 0xFFE2E8F0, false);
            setPad.invoke(apClientListTv, 12, 10, 12, 10);
            setRoundedBg(apClientListTv, 0xFF0F172A, 0xFF334155, 1, 12f);
            Object clLp = llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f);
            setMargins(clLp, 0, 10, 0, 10);
            addView.invoke(apCol3, apClientListTv, clLp);

            Object apTrafRow = llCtor.newInstance(sysContext);
            setOrient.invoke(apTrafRow, 0);
            Object apTrafLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(apTrafLbl, "공유 데이터량", 12.5f, 0xFF94A3B8, true);
            addView.invoke(apTrafRow, apTrafLbl, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            apTrafficTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(apTrafficTv, "⬇ 0 B  /  ⬆ 0 B", 12.5f, 0xFF7DD3FC, true);
            addView.invoke(apTrafRow, apTrafficTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(apCol3, apTrafRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object apCol3Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 4.0f);
            setMargins(apCol3Lp, 8, 0, 0, 0);
            addView.invoke(apViewBox, apCol3, apCol3Lp);

            addView.invoke(mainScreenBox, apViewBox, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            // =====================================================================
            // 2-B. CLIENT (STA) MODE VIEW (Horizontal 3-Column 4:5:3 Wide Layout)
            // =====================================================================
            staViewBox = llCtor.newInstance(sysContext);
            setOrient.invoke(staViewBox, 0); // HORIZONTAL
            setVis.invoke(staViewBox, 8); // GONE initially

            // [STA Col 1: Connected Wi-Fi Info & Manual/Disconnect]
            Object staCol1 = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol1, 1);
            setPad.invoke(staCol1, 16, 14, 16, 14);
            setRoundedBg(staCol1, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object staCol1Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol1Hdr, 0);
            llSetGrav.invoke(staCol1Hdr, 16);
            Object staCol1Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(staCol1Title, "1. 현재 연결된 와이파이", 13.0f, 0xFF38BDF8, true);
            addView.invoke(staCol1Hdr, staCol1Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            staConnBadgeTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(staConnBadgeTv, "⚪ 대기 중", 12.0f, 0xFFFCD34D, true);
            setPad.invoke(staConnBadgeTv, 10, 4, 10, 4);
            setRoundedBg(staConnBadgeTv, 0x33F59E0B, 0x66FBBF24, 1, 12f);
            addView.invoke(staCol1Hdr, staConnBadgeTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(staCol1, staCol1Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object staConnCard = llCtor.newInstance(sysContext);
            setOrient.invoke(staConnCard, 1);
            setPad.invoke(staConnCard, 14, 12, 14, 12);
            setRoundedBg(staConnCard, 0xFF0F172A, 0xFF10B981, 1, 12f);
            staConnectedSsidTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(staConnectedSsidTv, "연결된 Wi-Fi 없음", 18.0f, 0xFFFFFFFF, true);
            addView.invoke(staConnCard, staConnectedSsidTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
            staConnectedDetailTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(staConnectedDetailTv, "• 우측 목록에서 Wi-Fi를 터치해 연결하세요", 12.5f, 0xFFCBD5E1, false);
            Object scdLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(scdLp, 0, 8, 0, 0);
            addView.invoke(staConnCard, staConnectedDetailTv, scdLp);
            Object sccLp = llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f);
            setMargins(sccLp, 0, 10, 0, 10);
            addView.invoke(staCol1, staConnCard, sccLp);

            Object staActRow = llCtor.newInstance(sysContext);
            setOrient.invoke(staActRow, 0);
            Object staManualBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(staManualBtn, "➕ 직접 SSID 입력", 12.5f, 0xFF7DD3FC, true);
            tvSetGravity.invoke(staManualBtn, 17);
            setPad.invoke(staManualBtn, 8, 10, 8, 10);
            setRoundedBg(staManualBtn, 0xFF0F172A, 0xFF475569, 1, 10f);
            attachClickListener(staManualBtn, new Runnable() {
                @Override
                public void run() {
                    openEditModalOnUi("sta_manual");
                }
            });
            Object smBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(smBtnLp, 0, 0, 6, 0);
            addView.invoke(staActRow, staManualBtn, smBtnLp);

            Object staDiscBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(staDiscBtn, "🔌 연결 해제", 12.5f, 0xFFFECDD3, true);
            tvSetGravity.invoke(staDiscBtn, 17);
            setPad.invoke(staDiscBtn, 8, 10, 8, 10);
            setRoundedBg(staDiscBtn, 0x44E11D48, 0x88F43F5E, 1, 10f);
            attachClickListener(staDiscBtn, new Runnable() {
                @Override
                public void run() {
                    disconnectStaFromUi();
                }
            });
            Object sdBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(sdBtnLp, 6, 0, 0, 0);
            addView.invoke(staActRow, staDiscBtn, sdBtnLp);
            addView.invoke(staCol1, staActRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object staCol1Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 4.0f);
            setMargins(staCol1Lp, 0, 0, 8, 0);
            addView.invoke(staViewBox, staCol1, staCol1Lp);

            // [STA Col 2: Nearby Wi-Fi Scan List (4 tappable rows)]
            Object staCol2 = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol2, 1);
            setPad.invoke(staCol2, 16, 14, 16, 14);
            setRoundedBg(staCol2, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object staCol2Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol2Hdr, 0);
            llSetGrav.invoke(staCol2Hdr, 16);
            Object staCol2Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(staCol2Title, "2. 주변 Wi-Fi 네트워크 선택 (터치하여 연결)", 13.0f, 0xFFFBBF24, true);
            addView.invoke(staCol2Hdr, staCol2Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object rescanBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(rescanBtn, "🔄 다시 검색", 12.0f, 0xFFFFFFFF, true);
            setPad.invoke(rescanBtn, 12, 6, 12, 6);
            setRoundedBg(rescanBtn, 0xFFD97706, 0xFFFBBF24, 1, 8f);
            attachClickListener(rescanBtn, new Runnable() {
                @Override
                public void run() {
                    triggerStaScanFromUi();
                }
            });
            addView.invoke(staCol2Hdr, rescanBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(staCol2, staCol2Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            for (int i = 0; i < 4; i++) {
                final int rowIdx = i;
                Object sRow = llCtor.newInstance(sysContext);
                setOrient.invoke(sRow, 0);
                llSetGrav.invoke(sRow, 16);
                setPad.invoke(sRow, 12, 6, 12, 6);
                setRoundedBg(sRow, 0xFF0F172A, 0xFF334155, 1, 10f);
                attachClickListener(sRow, new Runnable() {
                    @Override
                    public void run() {
                        String targetSsid = staScanRowSsids[rowIdx];
                        if (targetSsid != null && !targetSsid.isEmpty()) {
                            openStaConnectModalOnUi(targetSsid);
                        } else {
                            openEditModalOnUi("sta_manual");
                        }
                    }
                });
                Object sRowTextCol = llCtor.newInstance(sysContext);
                setOrient.invoke(sRowTextCol, 1);
                Object sTitleTv = tvCtor.newInstance(sysContext);
                setTextViewStyle(sTitleTv, "📶 검색 중...", 13.5f, 0xFFFFFFFF, true);
                addView.invoke(sRowTextCol, sTitleTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
                Object sSubTv = tvCtor.newInstance(sysContext);
                setTextViewStyle(sSubTv, "터치하여 연결 설정", 11.5f, 0xFF94A3B8, false);
                addView.invoke(sRowTextCol, sSubTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
                addView.invoke(sRow, sRowTextCol, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

                Object sBandTv = tvCtor.newInstance(sysContext);
                setTextViewStyle(sBandTv, "🔒", 12.0f, 0xFF7DD3FC, true);
                addView.invoke(sRow, sBandTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

                staScanRowViews[i] = sRow;
                staScanRowTitleTvs[i] = sTitleTv;
                staScanRowSubTvs[i] = sSubTv;
                staScanRowBandTvs[i] = sBandTv;

                Object sRowLp = llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f);
                setMargins(sRowLp, 0, 6, 0, 0);
                addView.invoke(staCol2, sRow, sRowLp);
            }

            Object staCol2Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 5.0f);
            setMargins(staCol2Lp, 8, 0, 8, 0);
            addView.invoke(staViewBox, staCol2, staCol2Lp);

            // [STA Col 3: Saved Wi-Fi & Data Traffic]
            Object staCol3 = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol3, 1);
            setPad.invoke(staCol3, 16, 14, 16, 14);
            setRoundedBg(staCol3, 0xFF1E293B, 0xFF334155, 1, 16f);

            Object staCol3Hdr = llCtor.newInstance(sysContext);
            setOrient.invoke(staCol3Hdr, 0);
            Object staCol3Title = tvCtor.newInstance(sysContext);
            setTextViewStyle(staCol3Title, "3. 저장된 Wi-Fi", 13.0f, 0xFF34D399, true);
            addView.invoke(staCol3Hdr, staCol3Title, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object staAutoLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(staAutoLbl, "자동 연결", 12.0f, 0xFF94A3B8, false);
            addView.invoke(staCol3Hdr, staAutoLbl, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(staCol3, staCol3Hdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            staSavedListTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(staSavedListTv, "저장된 네트워크 없음", 13.0f, 0xFFE2E8F0, false);
            setPad.invoke(staSavedListTv, 12, 10, 12, 10);
            setRoundedBg(staSavedListTv, 0xFF0F172A, 0xFF334155, 1, 12f);
            Object sslLp = llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f);
            setMargins(sslLp, 0, 10, 0, 10);
            addView.invoke(staCol3, staSavedListTv, sslLp);

            staTrafficTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(staTrafficTv, "⬇ 수신 데이터 : 0 B\n⬆ 송신 데이터 : 0 B", 12.5f, 0xFF7DD3FC, true);
            addView.invoke(staCol3, staTrafficTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object staCol3Lp = llLpCtor3.newInstance(0, MATCH_PARENT, 3.0f);
            setMargins(staCol3Lp, 8, 0, 0, 0);
            addView.invoke(staViewBox, staCol3, staCol3Lp);

            addView.invoke(mainScreenBox, staViewBox, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            // =====================================================================
            // 2-C. EXPERT DIAGNOSTICS VIEW (Horizontal 2-Column 6:6 Wide Layout)
            // =====================================================================
            expertViewBox = llCtor.newInstance(sysContext);
            setOrient.invoke(expertViewBox, 0);
            setVis.invoke(expertViewBox, 8); // GONE initially

            Object leftCard = llCtor.newInstance(sysContext);
            setOrient.invoke(leftCard, 1);
            setPad.invoke(leftCard, 16, 12, 16, 12);
            setRoundedBg(leftCard, 0xFF0F172A, 0xFF334155, 1, 14f);

            Object leftHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftHdr, "[ 하드웨어 · 커널 드라이버 · 라우팅 상태 ]", 14.0f, 0xFF34D399, true);
            addView.invoke(leftCard, leftHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            leftColTextView = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftColTextView, "", 13.0f, 0xFFE2E8F0, false);
            setPad.invoke(leftColTextView, 0, 6, 0, 6);
            addView.invoke(leftCard, leftColTextView, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            Object expLeftBot = llCtor.newInstance(sysContext);
            setOrient.invoke(expLeftBot, 0);
            llSetGrav.invoke(expLeftBot, 16);
            headerSysInfoTextView = tvCtor.newInstance(sysContext);
            setTextViewStyle(headerSysInfoTextView, "CPU --% | RAM --", 12.5f, 0xFF94A3B8, true);
            addView.invoke(expLeftBot, headerSysInfoTextView, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

            autoUpdateToggleBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(autoUpdateToggleBtn, "☁️ 자동업데이트: 켜짐", 12.5f, 0xFFC7D2FE, true);
            setPad.invoke(autoUpdateToggleBtn, 12, 6, 12, 6);
            setRoundedBg(autoUpdateToggleBtn, 0xFF312E81, 0xFF6366F1, 1, 8f);
            attachClickListener(autoUpdateToggleBtn, new Runnable() {
                @Override
                public void run() {
                    toggleAutoUpdateFromUi();
                }
            });
            Object auBtnLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(auBtnLp, 0, 0, 8, 0);
            addView.invoke(expLeftBot, autoUpdateToggleBtn, auBtnLp);

            subnetCycleBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(subnetCycleBtn, "🌐 AP 서브넷: AUTO", 12.5f, 0xFF93C5FD, true);
            setPad.invoke(subnetCycleBtn, 12, 6, 12, 6);
            setRoundedBg(subnetCycleBtn, 0xFF1E3A8A, 0xFF3B82F6, 1, 8f);
            attachClickListener(subnetCycleBtn, new Runnable() {
                @Override
                public void run() {
                    toggleSubnetFromUi();
                }
            });
            addView.invoke(expLeftBot, subnetCycleBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(leftCard, expLeftBot, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object leftCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.0f);
            setMargins(leftCardLp, 0, 0, 8, 0);
            addView.invoke(expertViewBox, leftCard, leftCardLp);

            Object rightCard = llCtor.newInstance(sysContext);
            setOrient.invoke(rightCard, 1);
            setPad.invoke(rightCard, 16, 12, 16, 12);
            setRoundedBg(rightCard, 0xFF0F172A, 0xFF334155, 1, 14f);

            Object rightHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightHdr, "[ 실시간 데몬 로그 (/data/local/tmp/usbwifi_hotspot) ]", 14.0f, 0xFFFBBF24, true);
            addView.invoke(rightCard, rightHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            rightColTextView = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightColTextView, "", 13.0f, 0xFFE2E8F0, false);
            setPad.invoke(rightColTextView, 0, 6, 0, 0);
            addView.invoke(rightCard, rightColTextView, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            Object rightCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.0f);
            setMargins(rightCardLp, 8, 0, 0, 0);
            addView.invoke(expertViewBox, rightCard, rightCardLp);

            addView.invoke(mainScreenBox, expertViewBox, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            // 3. Compact 1-Line Bottom Footer
            Object div2 = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            viewCls.getMethod("setBackgroundColor", int.class).invoke(div2, 0xFF1E293B);
            Object divLp2 = llLpCtor2.newInstance(MATCH_PARENT, 2);
            setMargins(divLp2, 0, 10, 0, 8);
            addView.invoke(mainScreenBox, div2, divLp2);

            Object footerRow = llCtor.newInstance(sysContext);
            setOrient.invoke(footerRow, 0);
            llSetGrav.invoke(footerRow, 16);
            footerStatusTextView = tvCtor.newInstance(sysContext);
            setTextViewStyle(footerStatusTextView, actionBannerMsg, 13.0f, 0xFF7DD3FC, true);
            addView.invoke(footerRow, footerStatusTextView, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

            footerTimerTextView = tvCtor.newInstance(sysContext);
            setTextViewStyle(footerTimerTextView, "자동 닫힘: 90초 후  |  v" + UI_VERSION, 12.5f, 0xFF94A3B8, true);
            addView.invoke(footerRow, footerTimerTextView, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(mainScreenBox, footerRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            addView.invoke(root, mainScreenBox, llLpCtor2.newInstance(MATCH_PARENT, MATCH_PARENT));

            // =====================================================================
            // 4. WIDE LEFT-RIGHT SPLIT (4:8) IN-OVERLAY TOUCH KEYBOARD MODAL
            // =====================================================================
            editModalBox = llCtor.newInstance(sysContext);
            setOrient.invoke(editModalBox, 1); // VERTICAL
            setVis.invoke(editModalBox, 8); // GONE initially

            // Modal Top Header Bar
            Object mHdr = llCtor.newInstance(sysContext);
            setOrient.invoke(mHdr, 0);
            llSetGrav.invoke(mHdr, 16);

            modalTitleTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalTitleTv, "✏️ 내 차량 핫스팟 이름(SSID) · 비밀번호 변경", 16.0f, 0xFFFFFFFF, true);
            addView.invoke(mHdr, modalTitleTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));

            modalValidationTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalValidationTv, "  ✔ 우측 키보드로 입력 후 [저장 및 적용]을 누르세요", 13.0f, 0xFF34D399, true);
            addView.invoke(mHdr, modalValidationTv, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));

            Object mResetBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(mResetBtn, "↺ 초기값 복원", 13.0f, 0xFFE2E8F0, true);
            setPad.invoke(mResetBtn, 14, 8, 14, 8);
            setRoundedBg(mResetBtn, 0xFF1E293B, 0xFF475569, 1, 10f);
            attachClickListener(mResetBtn, new Runnable() {
                @Override
                public void run() {
                    editingSsid = "HKG_USB_Hotspot";
                    editingPass = "12345678";
                    refreshModalInputsOnUi();
                }
            });
            Object mrLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(mrLp, 0, 0, 8, 0);
            addView.invoke(mHdr, mResetBtn, mrLp);

            Object mCancelBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(mCancelBtn, "✖ 취소", 13.5f, 0xFFFFFFFF, true);
            setPad.invoke(mCancelBtn, 18, 8, 18, 8);
            setRoundedBg(mCancelBtn, 0xFF334155, 0xFF64748B, 1, 10f);
            attachClickListener(mCancelBtn, new Runnable() {
                @Override
                public void run() {
                    isEditModalOpen = false;
                    postToUi(REFRESH_TASK);
                }
            });
            Object mcLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(mcLp, 0, 0, 8, 0);
            addView.invoke(mHdr, mCancelBtn, mcLp);

            modalSaveBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalSaveBtn, "✔ 저장 및 적용", 13.5f, 0xFFFFFFFF, true);
            setPad.invoke(modalSaveBtn, 22, 8, 22, 8);
            setRoundedBg(modalSaveBtn, 0xFF059669, 0xFF34D399, 1, 10f);
            attachClickListener(modalSaveBtn, new Runnable() {
                @Override
                public void run() {
                    saveModalAndApplyOnUi();
                }
            });
            addView.invoke(mHdr, modalSaveBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(editModalBox, mHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object mDiv = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            viewCls.getMethod("setBackgroundColor", int.class).invoke(mDiv, 0xFF334155);
            Object mDivLp = llLpCtor2.newInstance(MATCH_PARENT, 2);
            setMargins(mDivLp, 0, 8, 0, 10);
            addView.invoke(editModalBox, mDiv, mDivLp);

            // Modal Body: Left 4 (Input Boxes + Quick Presets) / Right 8 (Full-Height 4-Row Keyboard)
            Object mBody = llCtor.newInstance(sysContext);
            setOrient.invoke(mBody, 0); // HORIZONTAL

            Object mLeftCol = llCtor.newInstance(sysContext);
            setOrient.invoke(mLeftCol, 1);

            // Input Box 1: SSID
            modalSsidFieldBox = llCtor.newInstance(sysContext);
            setOrient.invoke(modalSsidFieldBox, 1);
            setPad.invoke(modalSsidFieldBox, 14, 10, 14, 10);
            setRoundedBg(modalSsidFieldBox, 0xFF0F172A, 0xFF38BDF8, 2, 12f);
            attachClickListener(modalSsidFieldBox, new Runnable() {
                @Override
                public void run() {
                    activeEditField = "ssid";
                    refreshModalInputsOnUi();
                }
            });
            Object mSsidHdr = llCtor.newInstance(sysContext);
            setOrient.invoke(mSsidHdr, 0);
            Object mSsidLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(mSsidLbl, "1. 와이파이 이름 (SSID)", 12.5f, 0xFF38BDF8, true);
            addView.invoke(mSsidHdr, mSsidLbl, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            modalSsidLenTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalSsidLenTv, "15 / 32자", 12.0f, 0xFFCBD5E1, true);
            addView.invoke(mSsidHdr, modalSsidLenTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(modalSsidFieldBox, mSsidHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object mSsidValRow = llCtor.newInstance(sysContext);
            setOrient.invoke(mSsidValRow, 0);
            llSetGrav.invoke(mSsidValRow, 16);
            modalSsidValueTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalSsidValueTv, "HKG_USB_Hotspot", 17.5f, 0xFFFFFFFF, true);
            addView.invoke(mSsidValRow, modalSsidValueTv, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object clrSsidBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(clrSsidBtn, "지우기", 12.0f, 0xFFCBD5E1, true);
            setPad.invoke(clrSsidBtn, 10, 5, 10, 5);
            setRoundedBg(clrSsidBtn, 0xFF1E293B, 0xFF475569, 1, 8f);
            attachClickListener(clrSsidBtn, new Runnable() {
                @Override
                public void run() {
                    activeEditField = "ssid";
                    editingSsid = "";
                    refreshModalInputsOnUi();
                }
            });
            addView.invoke(mSsidValRow, clrSsidBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            Object msvrLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(msvrLp, 0, 6, 0, 0);
            addView.invoke(modalSsidFieldBox, mSsidValRow, msvrLp);
            addView.invoke(mLeftCol, modalSsidFieldBox, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // Input Box 2: Password
            modalPassFieldBox = llCtor.newInstance(sysContext);
            setOrient.invoke(modalPassFieldBox, 1);
            setPad.invoke(modalPassFieldBox, 14, 10, 14, 10);
            setRoundedBg(modalPassFieldBox, 0xFF0F172A, 0xFF334155, 2, 12f);
            attachClickListener(modalPassFieldBox, new Runnable() {
                @Override
                public void run() {
                    activeEditField = "pass";
                    refreshModalInputsOnUi();
                }
            });
            Object mPassHdr = llCtor.newInstance(sysContext);
            setOrient.invoke(mPassHdr, 0);
            Object mPassLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(mPassLbl, "2. 비밀번호 (8자리 이상)", 12.5f, 0xFFFBBF24, true);
            addView.invoke(mPassHdr, mPassLbl, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            modalPassLenTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalPassLenTv, "✔ 8자 가능", 12.0f, 0xFF34D399, true);
            addView.invoke(mPassHdr, modalPassLenTv, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            addView.invoke(modalPassFieldBox, mPassHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object mPassValRow = llCtor.newInstance(sysContext);
            setOrient.invoke(mPassValRow, 0);
            llSetGrav.invoke(mPassValRow, 16);
            modalPassValueTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(modalPassValueTv, "12345678", 17.5f, 0xFFFCD34D, true);
            addView.invoke(mPassValRow, modalPassValueTv, llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f));
            Object clrPassBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(clrPassBtn, "지우기", 12.0f, 0xFFCBD5E1, true);
            setPad.invoke(clrPassBtn, 10, 5, 10, 5);
            setRoundedBg(clrPassBtn, 0xFF1E293B, 0xFF475569, 1, 8f);
            attachClickListener(clrPassBtn, new Runnable() {
                @Override
                public void run() {
                    activeEditField = "pass";
                    editingPass = "";
                    refreshModalInputsOnUi();
                }
            });
            addView.invoke(mPassValRow, clrPassBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            Object mpvrLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(mpvrLp, 0, 6, 0, 0);
            addView.invoke(modalPassFieldBox, mPassValRow, mpvrLp);
            Object mpfbLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(mpfbLp, 0, 10, 0, 10);
            addView.invoke(mLeftCol, modalPassFieldBox, mpfbLp);

            addView.invoke(mLeftCol, viewCls.getConstructor(ctxCls).newInstance(sysContext), llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));

            // Quick SSID Presets Box
            Object presetBox = llCtor.newInstance(sysContext);
            setOrient.invoke(presetBox, 1);
            setPad.invoke(presetBox, 12, 10, 12, 10);
            setRoundedBg(presetBox, 0xFF0F172A, 0xFF1E293B, 1, 12f);
            Object presetLbl = tvCtor.newInstance(sysContext);
            setTextViewStyle(presetLbl, "빠른 이름 원터치 선택:", 12.0f, 0xFF94A3B8, true);
            addView.invoke(presetBox, presetLbl, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            final String[][] presets = new String[][]{
                    {"HKG_USB_Hotspot", "NiroEV_WiFi"},
                    {"EV6_Hotspot", "My_Phone_Hotspot"}
            };
            for (int r = 0; r < presets.length; r++) {
                Object pRow = llCtor.newInstance(sysContext);
                setOrient.invoke(pRow, 0);
                for (int c = 0; c < presets[r].length; c++) {
                    final String pVal = presets[r][c];
                    Object pBtn = tvCtor.newInstance(sysContext);
                    setTextViewStyle(pBtn, pVal, 12.0f, 0xFF7DD3FC, true);
                    tvSetGravity.invoke(pBtn, 17);
                    setPad.invoke(pBtn, 8, 8, 8, 8);
                    setRoundedBg(pBtn, 0xFF1E293B, 0xFF334155, 1, 8f);
                    attachClickListener(pBtn, new Runnable() {
                        @Override
                        public void run() {
                            activeEditField = "ssid";
                            editingSsid = pVal;
                            refreshModalInputsOnUi();
                        }
                    });
                    Object pBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
                    setMargins(pBtnLp, c == 0 ? 0 : 4, 6, c == 0 ? 4 : 0, 0);
                    addView.invoke(pRow, pBtn, pBtnLp);
                }
                addView.invoke(presetBox, pRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));
            }
            addView.invoke(mLeftCol, presetBox, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object mLeftLp = llLpCtor3.newInstance(0, MATCH_PARENT, 4.0f);
            setMargins(mLeftLp, 0, 0, 10, 0);
            addView.invoke(mBody, mLeftCol, mLeftLp);

            // Right Column (weight 8.0f): Full-Height 4-Row Touch Keyboard (flex-1 rows, h-full buttons)
            Object kbBox = llCtor.newInstance(sysContext);
            setOrient.invoke(kbBox, 1);
            setPad.invoke(kbBox, 10, 10, 10, 10);
            setRoundedBg(kbBox, 0xFF020617, 0xFF1E293B, 1, 16f);

            String[][] kbRows = new String[][]{
                    {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "_"},
                    {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
                    {"A", "S", "D", "F", "G", "H", "J", "K", "L", "@"},
                    {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", "!", "BS"}
            };
            for (int r = 0; r < kbRows.length; r++) {
                Object kRow = llCtor.newInstance(sysContext);
                setOrient.invoke(kRow, 0);
                for (int c = 0; c < kbRows[r].length; c++) {
                    final String token = kbRows[r][c];
                    Object kBtn = tvCtor.newInstance(sysContext);
                    tvSetGravity.invoke(kBtn, 17); // CENTER
                    float weight = 1.0f;
                    if ("SHIFT".equals(token)) {
                        weight = 1.35f;
                        kbShiftBtn = kBtn;
                        setTextViewStyle(kBtn, "⬆ 대문자", 13.0f, 0xFFFFFFFF, true);
                        setRoundedBg(kBtn, 0xFF0284C7, 0xFF38BDF8, 1, 12f);
                        attachClickListener(kBtn, new Runnable() {
                            @Override
                            public void run() {
                                kbUpper = !kbUpper;
                                refreshKeyboardCaseOnUi();
                            }
                        });
                    } else if ("BS".equals(token)) {
                        weight = 1.35f;
                        setTextViewStyle(kBtn, "⌫ 지움", 13.0f, 0xFFFFFFFF, true);
                        setRoundedBg(kBtn, 0xFFBE123C, 0xFFF43F5E, 1, 12f);
                        attachClickListener(kBtn, new Runnable() {
                            @Override
                            public void run() {
                                pressBackspaceOnUi();
                            }
                        });
                    } else {
                        setTextViewStyle(kBtn, token, 17.0f, 0xFFFFFFFF, true);
                        setRoundedBg(kBtn, 0xFF1E293B, 0xFF334155, 1, 12f);
                        final int letterIdx;
                        if (token.length() == 1 && token.charAt(0) >= 'A' && token.charAt(0) <= 'Z') {
                            letterIdx = kbLetterBtns.size();
                            kbLetterBtns.add(kBtn);
                            kbLetterBaseChars.add(token);
                        } else {
                            letterIdx = -1;
                        }
                        attachClickListener(kBtn, new Runnable() {
                            @Override
                            public void run() {
                                String ch = token;
                                if (letterIdx >= 0) {
                                    ch = kbUpper ? token : token.toLowerCase(Locale.US);
                                }
                                pressKeyOnUi(ch);
                            }
                        });
                    }
                    Object kBtnLp = llLpCtor3.newInstance(0, MATCH_PARENT, weight);
                    setMargins(kBtnLp, 3, 3, 3, 3);
                    addView.invoke(kRow, kBtn, kBtnLp);
                }
                Object kRowLp = llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f);
                addView.invoke(kbBox, kRow, kRowLp);
            }

            Object mRightLp = llLpCtor3.newInstance(0, MATCH_PARENT, 8.0f);
            addView.invoke(mBody, kbBox, mRightLp);

            addView.invoke(editModalBox, mBody, llLpCtor3.newInstance(MATCH_PARENT, 0, 1.0f));
            addView.invoke(root, editModalBox, llLpCtor2.newInstance(MATCH_PARENT, MATCH_PARENT));

            return root;
        }

        private static void showOnUiThread() {
            try {
                if (!ensureUiContextOnUiThread()) {
                    postDelayedToUi(new Runnable() {
                        @Override
                        public void run() {
                            showOnUiThread();
                        }
                    }, 500L);
                    return;
                }
                if (isVisible && rootOverlayView != null && isEditModalOpen) {
                    shownAtMs = System.currentTimeMillis();
                    refreshOnUiThread();
                    return;
                }
                if (rootOverlayView != null) {
                    try {
                        Class<?> viewCls0 = Class.forName("android.view.View");
                        windowManager.getClass().getMethod("removeViewImmediate", viewCls0).invoke(windowManager, rootOverlayView);
                    } catch (Throwable ignored) {
                    }
                    rootOverlayView = null;
                    isVisible = false;
                }

                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> vgLpCls = Class.forName("android.view.ViewGroup$LayoutParams");
                Class<?> wmLpCls = Class.forName("android.view.WindowManager$LayoutParams");
                Class<?> vmCls = Class.forName("android.view.ViewManager");

                int[] candidateTypes = new int[]{2020, 2008, 2009, 2005};
                int flags = 0x00000008 | 0x00000020 | 0x00000080;
                Constructor<?> wmLpCtor = wmLpCls.getConstructor(int.class, int.class, int.class, int.class, int.class);
                Method addViewMethod = vmCls.getMethod("addView", viewCls, vgLpCls);

                for (int wtype : candidateTypes) {
                    Object candidateRoot = null;
                    try {
                        candidateRoot = buildDashboardViewTree();
                        // Full-screen edge-to-edge overlay (-1 = MATCH_PARENT, -1 = MATCH_PARENT)
                        Object wmLp = wmLpCtor.newInstance(-1, -1, wtype, flags, -3);
                        wmLpCls.getField("gravity").setInt(wmLp, 17);
                        wmLpCls.getMethod("setTitle", CharSequence.class).invoke(wmLp, "UsbWifiHotspotOverlay");
                        addViewMethod.invoke(windowManager, candidateRoot, wmLp);

                        try {
                            Object vri = viewCls.getMethod("getViewRootImpl").invoke(candidateRoot);
                            if (vri != null) {
                                java.lang.reflect.Field fMedia = vri.getClass().getDeclaredField("mMediaDisabled");
                                fMedia.setAccessible(true);
                                fMedia.setBoolean(vri, true);
                            }
                        } catch (Throwable ignored) {
                        }

                        rootOverlayView = candidateRoot;
                        isVisible = true;
                        isEditModalOpen = false;
                        shownAtMs = System.currentTimeMillis();
                        System.out.println("[USBWifiUI] StatusDashboardOverlay full-screen displayed (windowType=" + wtype + ")");
                        refreshOnUiThread();
                        break;
                    } catch (Throwable t) {
                        if (candidateRoot != null) {
                            try {
                                windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, candidateRoot);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("[USBWifiUI] StatusDashboardOverlay show error: " + t.getMessage());
            }
        }

        private static void hideOnUiThread() {
            Object oldRoot = rootOverlayView;
            rootOverlayView = null;
            isVisible = false;
            isEditModalOpen = false;
            try {
                removeCallbacksFromUi(REFRESH_TASK);
                if (oldRoot != null && windowManager != null) {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Class<?> vmCls = Class.forName("android.view.ViewManager");
                    try {
                        viewCls.getMethod("setVisibility", int.class).invoke(oldRoot, 8);
                    } catch (Throwable ignored) {
                    }
                    try {
                        vmCls.getMethod("removeView", viewCls).invoke(windowManager, oldRoot);
                    } catch (Throwable t) {
                        windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, oldRoot);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private static void refreshOnUiThread() {
            if (!isVisible || rootOverlayView == null) return;
            long now = System.currentTimeMillis();
            if (isEditModalOpen) {
                shownAtMs = now;
            }
            long elapsedSec = (now - shownAtMs) / 1000L;
            if (elapsedSec >= 90L) {
                hideOnUiThread();
                return;
            }

            try {
                Class<?> viewCls = Class.forName("android.view.View");
                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setVis = viewCls.getMethod("setVisibility", int.class);
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                Method setColor = tvCls.getMethod("setTextColor", int.class);

                if (isEditModalOpen) {
                    setVis.invoke(mainScreenBox, 8);
                    setVis.invoke(editModalBox, 0);
                    refreshModalInputsOnUi();
                    return;
                } else {
                    setVis.invoke(editModalBox, 8);
                    setVis.invoke(mainScreenBox, 0);
                }

                SystemSnapshot snap = latestSnapshot;
                if (snap == null) {
                    return;
                }

                boolean isStaMode = "sta".equals(snap.wifiMode);

                // 1. Update Top Header Mode Switcher & Status Pill
                if (isStaMode) {
                    setRoundedBg(tabModeApBtn, 0x00000000, 0x00000000, 0, 9f);
                    setColor.invoke(tabModeApBtn, 0xFF94A3B8);
                    setRoundedBg(tabModeStaBtn, 0xFF059669, 0xFF34D399, 1, 9f);
                    setColor.invoke(tabModeStaBtn, 0xFFFFFFFF);
                } else {
                    setRoundedBg(tabModeApBtn, 0xFF0284C7, 0xFF38BDF8, 1, 9f);
                    setColor.invoke(tabModeApBtn, 0xFFFFFFFF);
                    setRoundedBg(tabModeStaBtn, 0x00000000, 0x00000000, 0, 9f);
                    setColor.invoke(tabModeStaBtn, 0xFF94A3B8);
                }

                if (!snap.usbDonglePresent) {
                    setText.invoke(statusPillTv, "● USB 무선랜카드 미연결");
                    setColor.invoke(statusPillTv, 0xFFFDA4AF);
                    setRoundedBg(statusPillTv, 0x33E11D48, 0x66F43F5E, 1, 20f);
                } else if (isStaMode) {
                    if (snap.staConnected) {
                        setText.invoke(statusPillTv, "● [" + snap.staConnectedSsid + "] 수신 연결됨");
                        setColor.invoke(statusPillTv, 0xFF6EE7B7);
                        setRoundedBg(statusPillTv, 0x3310B981, 0x6634D399, 1, 20f);
                    } else if (snap.wpaSuppAlive) {
                        String tgt = snap.staSsid.isEmpty() ? "Wi-Fi 선택 대기 중" : ("[" + snap.staSsid + "] 연결 중...");
                        setText.invoke(statusPillTv, "● " + tgt);
                        setColor.invoke(statusPillTv, 0xFFFCD34D);
                        setRoundedBg(statusPillTv, 0x33F59E0B, 0x66FBBF24, 1, 20f);
                    } else {
                        setText.invoke(statusPillTv, "● 수신 모드 준비 중...");
                        setColor.invoke(statusPillTv, 0xFFFCD34D);
                        setRoundedBg(statusPillTv, 0x33F59E0B, 0x66FBBF24, 1, 20f);
                    }
                } else {
                    if (snap.hostapdAlive && snap.dnsmasqAlive && snap.ifaceUp) {
                        setText.invoke(statusPillTv, "● 핫스팟 신호 송출 중");
                        setColor.invoke(statusPillTv, 0xFF6EE7B7);
                        setRoundedBg(statusPillTv, 0x3310B981, 0x6634D399, 1, 20f);
                    } else {
                        setText.invoke(statusPillTv, "● 핫스팟 신호 준비 중...");
                        setColor.invoke(statusPillTv, 0xFFFCD34D);
                        setRoundedBg(statusPillTv, 0x33F59E0B, 0x66FBBF24, 1, 20f);
                    }
                }

                setText.invoke(expertToggleBtn, isExpertView ? "✨ 간편 화면" : "🔧 상세 정보");

                // 2. Toggle Active Body Panel (AP / STA / Expert)
                if (isExpertView) {
                    setVis.invoke(apViewBox, 8);
                    setVis.invoke(staViewBox, 8);
                    setVis.invoke(expertViewBox, 0);
                } else if (isStaMode) {
                    setVis.invoke(apViewBox, 8);
                    setVis.invoke(expertViewBox, 8);
                    setVis.invoke(staViewBox, 0);
                } else {
                    setVis.invoke(staViewBox, 8);
                    setVis.invoke(expertViewBox, 8);
                    setVis.invoke(apViewBox, 0);
                }

                // 3. Populate AP Mode Views
                setText.invoke(apSsidTv, snap.apSsid);
                if (!snap.usbDonglePresent) {
                    setText.invoke(apSsidHintTv, "⚠ USB 포트에 무선랜카드를 꽂아주세요");
                    setColor.invoke(apSsidHintTv, 0xFFFDA4AF);
                } else if (snap.hostapdAlive) {
                    setText.invoke(apSsidHintTv, "✔ 휴대폰 Wi-Fi 목록에서 이 이름을 선택하세요");
                    setColor.invoke(apSsidHintTv, 0xFF34D399);
                } else {
                    setText.invoke(apSsidHintTv, "⏳ 무선 신호를 준비하고 있습니다...");
                    setColor.invoke(apSsidHintTv, 0xFFFCD34D);
                }

                boolean is5G = "a".equalsIgnoreCase(snap.apHwMode);
                if (is5G) {
                    setText.invoke(apBandDescTv, "5GHz (빠른 속도 · Ch " + snap.apChannel + ")");
                    setRoundedBg(band5Btn, 0xFF7C3AED, 0xFFA78BFA, 1, 8f);
                    setColor.invoke(band5Btn, 0xFFFFFFFF);
                    setRoundedBg(band24Btn, 0x00000000, 0x00000000, 0, 8f);
                    setColor.invoke(band24Btn, 0xFF94A3B8);
                } else {
                    setText.invoke(apBandDescTv, "2.4GHz (넓은 범위 · Ch " + snap.apChannel + ")");
                    setRoundedBg(band24Btn, 0xFF0284C7, 0xFF38BDF8, 1, 8f);
                    setColor.invoke(band24Btn, 0xFFFFFFFF);
                    setRoundedBg(band5Btn, 0x00000000, 0x00000000, 0, 8f);
                    setColor.invoke(band5Btn, 0xFF94A3B8);
                }

                setText.invoke(pwMaskToggleBtn, isPwMasked ? "표시" : "숨김");
                setText.invoke(apPassTv, isPwMasked ? "••••••••" : snap.apPass);

                int cCount = snap.connectedClients.size();
                setText.invoke(apClientCountBadgeTv, cCount + "대 연결됨");
                if (cCount == 0) {
                    setText.invoke(apClientListTv, "현재 연결된 기기가 없습니다.\n\n휴대폰에서 [" + snap.apSsid + "]를 선택해 연결하세요.");
                } else {
                    StringBuilder csb = new StringBuilder();
                    int maxShow = Math.min(4, cCount);
                    for (int i = 0; i < maxShow; i++) {
                        if (i > 0) csb.append("\n");
                        csb.append("📱 ").append(snap.connectedClients.get(i));
                    }
                    setText.invoke(apClientListTv, csb.toString());
                }
                setText.invoke(apTrafficTv, "⬇ " + formatBytes(snap.rxBytes) + "  /  ⬆ " + formatBytes(snap.txBytes));

                // 4. Populate STA (Client) Mode Views
                if (snap.staConnected) {
                    setText.invoke(staConnBadgeTv, "● 연결됨 (" + snap.staBand + ")");
                    setColor.invoke(staConnBadgeTv, 0xFF6EE7B7);
                    setRoundedBg(staConnBadgeTv, 0x3310B981, 0x6634D399, 1, 12f);
                    setText.invoke(staConnectedSsidTv, snap.staConnectedSsid);
                    String ipDisp = snap.ifaceIp.isEmpty() ? "DHCP IP 할당 중..." : snap.ifaceIp;
                    setText.invoke(staConnectedDetailTv,
                            "• 신호 세기 : " + snap.staRssi + "\n"
                                    + "• 할당 IP : " + ipDisp + "\n"
                                    + "• 상태 : ✔ 외부 와이파이 수신 연결됨");
                } else {
                    setText.invoke(staConnBadgeTv, snap.staSsid.isEmpty() ? "⚪ 미연결" : "⏳ 연결 시도 중");
                    setColor.invoke(staConnBadgeTv, 0xFFFCD34D);
                    setRoundedBg(staConnBadgeTv, 0x33F59E0B, 0x66FBBF24, 1, 12f);
                    setText.invoke(staConnectedSsidTv, snap.staSsid.isEmpty() ? "연결된 Wi-Fi 없음" : snap.staSsid);
                    setText.invoke(staConnectedDetailTv,
                            "• 상태 : " + (snap.staSsid.isEmpty() ? "우측 목록에서 Wi-Fi를 터치해 연결하세요" : "비밀번호 확인 또는 신호 검색 중...") + "\n"
                                    + "• 인터페이스 : " + (snap.usbIface.isEmpty() ? "rtwlan0" : snap.usbIface));
                }

                for (int i = 0; i < 4; i++) {
                    if (i < snap.staScanList.size()) {
                        ScanEntry se = snap.staScanList.get(i);
                        staScanRowSsids[i] = se.ssid;
                        boolean isCur = snap.staConnected && se.ssid.equals(snap.staConnectedSsid);
                        setText.invoke(staScanRowTitleTvs[i], "📶 " + se.ssid);
                        setText.invoke(staScanRowSubTvs[i], isCur
                                ? "✔ 현재 연결됨 · 터치하여 비밀번호 수정"
                                : ("신호 (" + se.rssi + " dBm) · 터치하여 연결"));
                        setColor.invoke(staScanRowSubTvs[i], isCur ? 0xFF34D399 : 0xFF94A3B8);
                        setText.invoke(staScanRowBandTvs[i], se.band + (se.secured ? " 🔒" : ""));
                        setRoundedBg(staScanRowViews[i], isCur ? 0xFF064E3B : 0xFF0F172A, isCur ? 0xFF10B981 : 0xFF334155, 1, 10f);
                    } else {
                        staScanRowSsids[i] = "";
                        setText.invoke(staScanRowTitleTvs[i], i == 0 ? "📶 주변 Wi-Fi 검색 결과 대기 중..." : "➕ 직접 Wi-Fi 이름(SSID) 입력하기");
                        setText.invoke(staScanRowSubTvs[i], i == 0 ? "상단 [🔄 다시 검색]을 누르거나 터치하여 직접 입력" : "터치하여 화면 키보드로 연결");
                        setColor.invoke(staScanRowSubTvs[i], 0xFF64748B);
                        setText.invoke(staScanRowBandTvs[i], "");
                        setRoundedBg(staScanRowViews[i], 0xFF0F172A, 0xFF1E293B, 1, 10f);
                    }
                }

                if (snap.savedStaList.isEmpty()) {
                    setText.invoke(staSavedListTv, "저장된 Wi-Fi가 없습니다.\n\n한번 연결한 네트워크는 자동으로 기록됩니다.");
                } else {
                    StringBuilder ssb = new StringBuilder();
                    int maxS = Math.min(4, snap.savedStaList.size());
                    for (int i = 0; i < maxS; i++) {
                        if (i > 0) ssb.append("\n\n");
                        ssb.append(i + 1).append("순위: ").append(snap.savedStaList.get(i));
                    }
                    setText.invoke(staSavedListTv, ssb.toString());
                }
                setText.invoke(staTrafficTv, "⬇ 수신 데이터 : " + formatBytes(snap.rxBytes) + "\n⬆ 송신 데이터 : " + formatBytes(snap.txBytes));

                // 5. Populate Expert View
                String usbStr = snap.usbDonglePresent ? ("🟢 " + snap.usbDongleDesc) : "🔴 미연결";
                String drvStr = snap.driverLoaded ? ("🟢 " + snap.driverModuleFile + " (" + snap.driverMemInfo + ")") : "🔴 미로드";
                String ifaceStr = snap.usbIface.isEmpty() ? "⚪ 미생성" : ((snap.ifaceUp ? "🟢 " : "🟠 ") + snap.usbIface + " | IP: " + (snap.ifaceIp.isEmpty() ? "없음" : snap.ifaceIp));
                String modeDesc = isStaMode ? "Client / STA (와이파이 수신 — wpa_supplicant)" : "AP (핫스팟 송출 — hostapd_usb + dnsmasq)";

                StringBuilder leftSb = new StringBuilder(512);
                leftSb.append("• 플랫폼/커널 : ").append(snap.platform).append(" (").append(snap.model).append(") | Linux ").append(snap.kernelRel).append("\n");
                leftSb.append("• 동작 모드 : ").append(modeDesc).append("\n");
                leftSb.append("• USB 랜카드 : ").append(usbStr).append("\n");
                leftSb.append("• 드라이버 모듈 : ").append(drvStr).append("\n");
                leftSb.append("• 인터페이스 : ").append(ifaceStr).append(" | 순정 wlan0 완벽 분리\n");
                leftSb.append("• 백그라운드 데몬 : ").append(snap.daemonAlive ? ("🟢 PID " + snap.daemonPid) : "🔴 중지됨")
                        .append("  |  GitHub OTA: v").append(UI_VERSION).append(" (").append(otaStatusSummary).append(")");

                StringBuilder rightSb = new StringBuilder(512);
                int maxLogs = Math.min(6, snap.recentLogs.size());
                int startIdx = Math.max(0, snap.recentLogs.size() - maxLogs);
                for (int i = startIdx; i < snap.recentLogs.size(); i++) {
                    rightSb.append(snap.recentLogs.get(i));
                    if (i + 1 < snap.recentLogs.size()) rightSb.append("\n");
                }

                if (otaUpdateBtn != null) {
                    if (otaCheckingOrUpdating) {
                        setText.invoke(otaUpdateBtn, "⏳ 확인 중...");
                        setRoundedBg(otaUpdateBtn, 0xFF334155, 0xFF94A3B8, 1, 11f);
                    } else if (otaUpdateAvailable) {
                        setText.invoke(otaUpdateBtn, "☁️ 업데이트 (NEW)");
                        setRoundedBg(otaUpdateBtn, 0xFFD97706, 0xFFFBBF24, 1, 11f);
                    } else {
                        setText.invoke(otaUpdateBtn, "☁️ 업데이트");
                        setRoundedBg(otaUpdateBtn, 0xFF4F46E5, 0xFF818CF8, 1, 11f);
                    }
                }
                if (autoUpdateToggleBtn != null) {
                    boolean autoOn = !"0".equals(snap.autoUpdate);
                    setText.invoke(autoUpdateToggleBtn, autoOn ? "☁️ 자동업데이트: 켜짐" : "☁️ 자동업데이트: 꺼짐");
                    setRoundedBg(autoUpdateToggleBtn, autoOn ? 0xFF312E81 : 0xFF1E293B, autoOn ? 0xFF6366F1 : 0xFF475569, 1, 8f);
                }

                setText.invoke(headerSysInfoTextView, "CPU " + snap.cpuLoadPct + "%  |  " + snap.ramSummary);
                setText.invoke(subnetCycleBtn, "🌐 AP 서브넷: " + snap.forceSubnet);
                setText.invoke(leftColTextView, leftSb.toString());
                setText.invoke(rightColTextView, rightSb.toString());

                // 6. Footer Status & Timer
                long remainClose = Math.max(1L, 90L - elapsedSec);
                setText.invoke(footerStatusTextView, actionBannerMsg);
                setText.invoke(footerTimerTextView, "자동 닫힘: " + remainClose + "초 후  |  v" + UI_VERSION);
            } catch (Throwable ignored) {
            }
        }

        private static void openEditModalOnUi(String target) {
            SystemSnapshot snap = latestSnapshot;
            if ("sta_manual".equals(target)) {
                modalContext = "sta";
                editingSsid = (snap != null && !snap.staSsid.isEmpty()) ? snap.staSsid : "";
                editingPass = (snap != null && !snap.staPass.isEmpty()) ? snap.staPass : "";
                activeEditField = "ssid";
            } else {
                modalContext = "ap";
                editingSsid = (snap != null && !snap.apSsid.isEmpty()) ? snap.apSsid : "HKG_USB_Hotspot";
                editingPass = (snap != null && !snap.apPass.isEmpty()) ? snap.apPass : "12345678";
                activeEditField = "ap_pass".equals(target) ? "pass" : "ssid";
            }
            isEditModalOpen = true;
            shownAtMs = System.currentTimeMillis();
            postToUi(REFRESH_TASK);
        }

        private static void openStaConnectModalOnUi(String targetSsid) {
            SystemSnapshot snap = latestSnapshot;
            modalContext = "sta";
            editingSsid = targetSsid;
            editingPass = (snap != null && targetSsid.equals(snap.staSsid) && !snap.staPass.isEmpty())
                    ? snap.staPass
                    : lookupSavedStaPassword(targetSsid);
            activeEditField = "pass";
            isEditModalOpen = true;
            shownAtMs = System.currentTimeMillis();
            postToUi(REFRESH_TASK);
        }

        private static String lookupSavedStaPassword(String ssid) {
            if (ssid == null || ssid.isEmpty()) return "";
            File savedFile = new File(installDir, "config/saved_sta.conf");
            if (!savedFile.isFile()) return "";
            List<String> lines = readLinesQuick(savedFile, 30);
            for (String l : lines) {
                int eq = l.indexOf('=');
                if (eq > 0) {
                    String k = l.substring(0, eq).trim();
                    String v = l.substring(eq + 1).trim();
                    if (ssid.equals(k)) return v;
                }
            }
            return "";
        }

        private static void saveStaNetworkToHistory(String ssid, String pass) {
            try {
                File savedFile = new File(installDir, "config/saved_sta.conf");
                List<String> existing = savedFile.isFile() ? readLinesQuick(savedFile, 30) : new ArrayList<String>();
                List<String> updated = new ArrayList<String>();
                updated.add(ssid + "=" + pass);
                for (String l : existing) {
                    int eq = l.indexOf('=');
                    String k = (eq > 0 ? l.substring(0, eq) : l).trim();
                    if (!k.isEmpty() && !k.equals(ssid) && updated.size() < 8) {
                        updated.add(l.trim());
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String u : updated) {
                    sb.append(u).append('\n');
                }
                writeBytesAtomic(savedFile, sb.toString().getBytes(UTF8));
            } catch (Throwable ignored) {
            }
        }

        private static void refreshKeyboardCaseOnUi() {
            try {
                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                if (kbShiftBtn != null) {
                    setText.invoke(kbShiftBtn, kbUpper ? "⬆ 대문자" : "⬇ 소문자");
                    setRoundedBg(kbShiftBtn, kbUpper ? 0xFF0284C7 : 0xFF334155, kbUpper ? 0xFF38BDF8 : 0xFF64748B, 1, 12f);
                }
                for (int i = 0; i < kbLetterBtns.size(); i++) {
                    String base = kbLetterBaseChars.get(i);
                    setText.invoke(kbLetterBtns.get(i), kbUpper ? base : base.toLowerCase(Locale.US));
                }
            } catch (Throwable ignored) {
            }
        }

        private static void pressKeyOnUi(String ch) {
            if ("ssid".equals(activeEditField)) {
                if (editingSsid.length() < 32) {
                    editingSsid = editingSsid + ch;
                }
            } else {
                if (editingPass.length() < 63) {
                    editingPass = editingPass + ch;
                }
            }
            refreshModalInputsOnUi();
        }

        private static void pressBackspaceOnUi() {
            if ("ssid".equals(activeEditField)) {
                if (editingSsid.length() > 0) {
                    editingSsid = editingSsid.substring(0, editingSsid.length() - 1);
                }
            } else {
                if (editingPass.length() > 0) {
                    editingPass = editingPass.substring(0, editingPass.length() - 1);
                }
            }
            refreshModalInputsOnUi();
        }

        private static void refreshModalInputsOnUi() {
            try {
                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                Method setColor = tvCls.getMethod("setTextColor", int.class);

                boolean isSta = "sta".equals(modalContext);
                setText.invoke(modalTitleTv, isSta
                        ? "✏️ 외부 Wi-Fi 네트워크 연결 설정"
                        : "✏️ 내 차량 핫스팟 이름(SSID) · 비밀번호 변경");
                setText.invoke(modalSaveBtn, isSta ? "✔ 이 Wi-Fi에 연결하기" : "✔ 저장 및 적용");

                if ("ssid".equals(activeEditField)) {
                    setRoundedBg(modalSsidFieldBox, 0xFF0F172A, 0xFF38BDF8, 2, 12f);
                    setRoundedBg(modalPassFieldBox, 0xFF0F172A, 0xFF334155, 2, 12f);
                } else {
                    setRoundedBg(modalSsidFieldBox, 0xFF0F172A, 0xFF334155, 2, 12f);
                    setRoundedBg(modalPassFieldBox, 0xFF0F172A, 0xFFFBBF24, 2, 12f);
                }

                String sTrim = editingSsid.trim();
                boolean ssidOk = sTrim.length() >= 1 && sTrim.length() <= 32;
                boolean passOk = editingPass.length() >= 8 && editingPass.length() <= 63;

                setText.invoke(modalSsidValueTv, editingSsid.isEmpty() ? "(이름을 입력하세요)" : editingSsid);
                setColor.invoke(modalSsidValueTv, editingSsid.isEmpty() ? 0xFF64748B : 0xFFFFFFFF);
                setText.invoke(modalSsidLenTv, sTrim.length() + " / 32자");

                setText.invoke(modalPassValueTv, editingPass.isEmpty() ? "(비밀번호 8자 이상)" : editingPass);
                setColor.invoke(modalPassValueTv, editingPass.isEmpty() ? 0xFF64748B : 0xFFFCD34D);
                if (passOk) {
                    setText.invoke(modalPassLenTv, "✔ " + editingPass.length() + "자 가능");
                    setColor.invoke(modalPassLenTv, 0xFF34D399);
                } else {
                    setText.invoke(modalPassLenTv, "⚠ " + editingPass.length() + "자 (8자 이상)");
                    setColor.invoke(modalPassLenTv, 0xFFFDA4AF);
                }

                if (ssidOk && passOk) {
                    setText.invoke(modalValidationTv, isSta
                            ? "  ✔ [연결하기]를 누르면 해당 Wi-Fi에 접속합니다"
                            : "  ✔ 우측 키보드로 입력 후 [저장 및 적용]을 누르세요");
                    setColor.invoke(modalValidationTv, 0xFF34D399);
                    setRoundedBg(modalSaveBtn, 0xFF059669, 0xFF34D399, 1, 10f);
                } else {
                    setText.invoke(modalValidationTv, !ssidOk
                            ? "  ⚠ 와이파이 이름(SSID)을 1자 이상 입력해 주세요"
                            : "  ⚠ 비밀번호는 8자리 이상 입력해야 합니다");
                    setColor.invoke(modalValidationTv, 0xFFFDA4AF);
                    setRoundedBg(modalSaveBtn, 0xFF334155, 0xFF475569, 1, 10f);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void saveModalAndApplyOnUi() {
            final String newSsid = editingSsid.trim();
            final String newPass = editingPass;
            if (newSsid.length() < 1 || newSsid.length() > 32 || newPass.length() < 8 || newPass.length() > 63) {
                return;
            }
            if (!isSafeConfigValue(newSsid) || !isSafeConfigValue(newPass)) {
                actionBannerMsg = "⚠️ 이 Wi-Fi 이름에는 저장할 수 없는 제어 문자가 포함되어 있습니다.";
                postToUi(REFRESH_TASK);
                return;
            }
            final boolean isSta = "sta".equals(modalContext);
            isEditModalOpen = false;
            actionBannerMsg = isSta
                    ? ("📶 외부 Wi-Fi [" + newSsid + "]에 연결을 요청하고 있습니다...")
                    : ("✅ 새 핫스팟 설정([" + newSsid + "])을 저장하고 신호를 재시작합니다...");
            postToUi(REFRESH_TASK);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        if (isSta) {
                            updateConfigKeys(confFile, new String[][]{
                                    {"WIFI_MODE", "sta"},
                                    {"STA_SSID", newSsid},
                                    {"STA_PASS", newPass}
                            });
                            saveStaNetworkToHistory(newSsid, newPass);
                        } else {
                            updateConfigKeys(confFile, new String[][]{
                                    {"WIFI_MODE", "ap"},
                                    {"AP_SSID", newSsid},
                                    {"AP_PASS", newPass}
                            });
                        }
                        writeBytesAtomic(new File(TMP_DIR_PATH, "force_restart"), "1\n".getBytes(UTF8));
                        latestSnapshot = collectSnapshot(installDir);
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 설정 저장 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "SaveModalConfigThread").start();
        }

        private static void switchWifiModeFromUi(final String targetMode) {
            isExpertView = false;
            actionBannerMsg = "sta".equals(targetMode)
                    ? "📶 [와이파이 수신 모드(Client)]로 전환 중입니다..."
                    : "📡 [핫스팟 모드(AP)]로 전환 중입니다...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        updateConfigKeys(confFile, new String[][]{
                                {"WIFI_MODE", targetMode}
                        });
                        writeBytesAtomic(new File(TMP_DIR_PATH, "force_restart"), "1\n".getBytes(UTF8));
                        latestSnapshot = collectSnapshot(installDir);
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 모드 전환 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "SwitchWifiModeThread").start();
        }

        private static void selectBandFromUi(final String targetHwMode) {
            final String nextChan = "a".equalsIgnoreCase(targetHwMode) ? "149" : "6";
            String label = "a".equalsIgnoreCase(targetHwMode) ? "5GHz 고속 (Ch 149)" : "2.4GHz 일반 (Ch 6)";
            actionBannerMsg = "📻 송출 주파수를 [" + label + "]로 변경하고 재시작합니다...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        updateConfigKeys(confFile, new String[][]{
                                {"AP_HW_MODE", targetHwMode},
                                {"AP_CHANNEL", nextChan}
                        });
                        writeBytesAtomic(new File(TMP_DIR_PATH, "force_restart"), "1\n".getBytes(UTF8));
                        latestSnapshot = collectSnapshot(installDir);
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 주파수 변경 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "SelectBandThread").start();
        }

        private static void triggerStaScanFromUi() {
            actionBannerMsg = "🔄 주변 Wi-Fi 네트워크를 다시 검색하고 있습니다...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        writeBytesAtomic(new File(TMP_DIR_PATH, "trigger_scan"), "1\n".getBytes(UTF8));
                        Thread.sleep(4500L);
                        latestSnapshot = collectSnapshot(installDir);
                        actionBannerMsg = "✅ 주변 Wi-Fi 검색 완료! 연결할 네트워크를 터치하세요.";
                    } catch (Throwable ignored) {
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "StaScanTrigger").start();
        }

        private static void disconnectStaFromUi() {
            actionBannerMsg = "🔌 현재 외부 Wi-Fi 연결을 해제합니다...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        updateConfigKeys(confFile, new String[][]{
                                {"STA_SSID", ""},
                                {"STA_PASS", ""}
                        });
                        writeBytesAtomic(new File(TMP_DIR_PATH, "disconnect_sta"), "1\n".getBytes(UTF8));
                        Thread.sleep(1000L);
                        latestSnapshot = collectSnapshot(installDir);
                        actionBannerMsg = "🔌 외부 Wi-Fi 연결이 해제되었습니다.";
                    } catch (Throwable ignored) {
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "DisconnectStaThread").start();
        }

        private static void triggerRestartHotspotFromUi() {
            actionBannerMsg = "⏳ 무선랜 서비스를 재시작하고 있습니다...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File tmpDir = new File(TMP_DIR_PATH);
                        if (!tmpDir.exists()) tmpDir.mkdirs();
                        writeBytesAtomic(new File(tmpDir, "force_restart"), "1\n".getBytes(UTF8));
                        String dpid = readFirstLineQuick(new File(tmpDir, "daemon.pid"));
                        if (!isProcessAlive(dpid)) {
                            File daemonSh = new File(installDir, "svc/usbwifi-hotspot-daemon.sh");
                            if (daemonSh.isFile()) {
                                new ProcessBuilder("/system/bin/sh", daemonSh.getAbsolutePath()).start();
                            }
                        }
                        Thread.sleep(1500L);
                        actionBannerMsg = "✅ 재시작 명령 전달 완료! (잠시 후 상태가 갱신됩니다)";
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 재시작 요청 오류: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "RestartHotspotTrigger").start();
        }

        private static void toggleAutoUpdateFromUi() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        Map<String, String> conf = parseKeyValueFile(confFile);
                        boolean curOn = !"0".equals(stripQuotes(getOrDefault(conf, "AUTO_UPDATE", "1")));
                        String nextVal = curOn ? "0" : "1";
                        updateConfigKeys(confFile, new String[][]{
                                {"AUTO_UPDATE", nextVal}
                        });
                        latestSnapshot = collectSnapshot(installDir);
                        actionBannerMsg = "1".equals(nextVal)
                                ? "☁️ GitHub 백그라운드 자동 업데이트가 [켜짐]으로 설정되었습니다."
                                : "☁️ GitHub 백그라운드 자동 업데이트가 [꺼짐]으로 설정되었습니다.";
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 자동 업데이트 설정 변경 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ToggleAutoUpdateThread").start();
        }

        private static void triggerGitHubOtaFromUi() {
            if (otaCheckingOrUpdating) {
                actionBannerMsg = "⏳ 이미 GitHub 업데이트 확인/다운로드가 진행 중입니다...";
                postToUi(REFRESH_TASK);
                return;
            }
            long now = System.currentTimeMillis();
            final boolean forceReinstall = !otaUpdateAvailable && (now - lastManualOtaTapMs < 8000L);
            lastManualOtaTapMs = now;

            actionBannerMsg = forceReinstall
                    ? "⬇️ GitHub(GRT47/HKG_5W_USB_Wlan)에서 최신 파일 강제 재설치 중..."
                    : "⏳ GitHub(GRT47/HKG_5W_USB_Wlan)에서 최신 버전을 확인하고 있습니다...";
            postToUi(REFRESH_TASK);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    checkAndApplyGitHubOta(installDir, true, forceReinstall, false);
                }
            }, "ManualGitHubOtaThread").start();
        }

        public static void checkAndApplyGitHubOta(final String dir, boolean applyIfNew, boolean forceApply, boolean silentIfUpToDate) {
            if (otaCheckingOrUpdating) return;
            otaCheckingOrUpdating = true;
            postToUi(REFRESH_TASK);
            try {
                ensureGitHubRoutingRule();
                byte[] verBytes = downloadGitHubOtaFile("version.json", 8000);
                Map<String, String> vmap = parseJsonFlatMap(new String(verBytes, UTF8));
                String remoteVer = getOrDefault(vmap, "version", "");
                String expJarSha = getOrDefault(vmap, "sha256_jar", "").toLowerCase(Locale.US);
                String expDaemonSha = getOrDefault(vmap, "sha256_daemon", "").toLowerCase(Locale.US);
                String expLaunchSha = getOrDefault(vmap, "sha256_launch", "").toLowerCase(Locale.US);
                if (remoteVer.isEmpty() || expJarSha.length() != 64 || expDaemonSha.length() != 64) {
                    throw new IOException("version.json 매니페스트 형식 오류");
                }
                otaRemoteVersion = remoteVer;

                File localJar = new File(dir, "bin/usbwifistatus.jar");
                File localDaemon = new File(dir, "svc/usbwifi-hotspot-daemon.sh");
                File localLaunch = new File(dir, "bin/launch.sh");

                String curJarSha = sha256File(localJar);
                String curDaemonSha = sha256File(localDaemon);
                String curLaunchSha = sha256File(localLaunch);

                boolean jarChanged = !expJarSha.equalsIgnoreCase(curJarSha);
                boolean daemonChanged = !expDaemonSha.equalsIgnoreCase(curDaemonSha);
                boolean launchChanged = !expLaunchSha.isEmpty() && !expLaunchSha.equalsIgnoreCase(curLaunchSha);
                boolean hasUpdate = jarChanged || daemonChanged || launchChanged;

                String nowStr = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
                if (!hasUpdate && !forceApply) {
                    otaUpdateAvailable = false;
                    otaStatusSummary = "최신 v" + remoteVer + " 확인됨 " + nowStr;
                    if (!silentIfUpToDate) {
                        actionBannerMsg = "✅ 현재 GitHub 최신 버전(v" + remoteVer + ")입니다! (8초 내 한 번 더 누르면 강제 재설치)";
                    }
                    otaCheckingOrUpdating = false;
                    postToUi(REFRESH_TASK);
                    return;
                }

                otaUpdateAvailable = hasUpdate;
                if (!applyIfNew && !forceApply) {
                    otaStatusSummary = "새 버전 v" + remoteVer + " 대기 중";
                    actionBannerMsg = "☁️ GitHub에 새 업데이트(v" + remoteVer + ")가 있습니다! 상단 [☁️ 업데이트]를 누르세요.";
                    otaCheckingOrUpdating = false;
                    postToUi(REFRESH_TASK);
                    return;
                }

                actionBannerMsg = "⬇️ GitHub 새 버전(v" + remoteVer + ") 다운로드 및 SHA-256 무결성 검증 중...";
                postToUi(REFRESH_TASK);

                // 1. Download & verify usbwifistatus.b64 -> decoded JAR bytes
                byte[] b64Bytes = downloadGitHubOtaFile("usbwifistatus.b64", 15000);
                byte[] newJarBytes = decodeBase64(new String(b64Bytes, UTF8));
                String dlJarSha = sha256Hex(newJarBytes);
                if (!expJarSha.equalsIgnoreCase(dlJarSha)) {
                    throw new IOException("usbwifistatus.jar SHA-256 불일치 (" + dlJarSha.substring(0, 8) + ")");
                }

                // 2. Download & verify usbwifi-hotspot-daemon.sh
                byte[] newDaemonBytes = downloadGitHubOtaFile("usbwifi-hotspot-daemon.sh", 10000);
                String dlDaemonSha = sha256Hex(newDaemonBytes);
                if (!expDaemonSha.equalsIgnoreCase(dlDaemonSha)) {
                    throw new IOException("daemon.sh SHA-256 불일치 (" + dlDaemonSha.substring(0, 8) + ")");
                }

                // 3. Download & verify launch.sh
                byte[] newLaunchBytes = null;
                if (!expLaunchSha.isEmpty()) {
                    newLaunchBytes = downloadGitHubOtaFile("launch.sh", 8000);
                    String dlLaunchSha = sha256Hex(newLaunchBytes);
                    if (!expLaunchSha.equalsIgnoreCase(dlLaunchSha)) {
                        throw new IOException("launch.sh SHA-256 불일치 (" + dlLaunchSha.substring(0, 8) + ")");
                    }
                }

                // 4. Backup & Atomic Replace (User config/hotspot.conf & saved_sta.conf are 100% untouched!)
                if (localJar.isFile()) {
                    writeBytesAtomic(new File(dir, "bin/usbwifistatus.jar.bak"), readAllBytes(new FileInputStream(localJar)));
                }
                if (localDaemon.isFile()) {
                    writeBytesAtomic(new File(dir, "svc/usbwifi-hotspot-daemon.sh.bak"), readAllBytes(new FileInputStream(localDaemon)));
                }
                writeBytesAtomic(localJar, newJarBytes);
                localJar.setReadable(true, false);

                writeBytesAtomic(localDaemon, newDaemonBytes);
                localDaemon.setReadable(true, false);
                localDaemon.setExecutable(true, false);

                if (newLaunchBytes != null) {
                    writeBytesAtomic(localLaunch, newLaunchBytes);
                    localLaunch.setReadable(true, false);
                    localLaunch.setExecutable(true, false);
                }
                writeBytesAtomic(new File(dir, "config/version.json"), verBytes);
                execCommandCapture(new String[]{"/system/bin/sh", "-c",
                        "chmod 755 \"" + localDaemon.getAbsolutePath() + "\" \"" + localLaunch.getAbsolutePath() + "\" 2>/dev/null; "
                                + "chmod 644 \"" + localJar.getAbsolutePath() + "\" 2>/dev/null; "
                                + "echo \"$(date) [OTA] Updated to v" + remoteVer + " (jar=" + expJarSha.substring(0, 8) + ")\" >> /data/local/tmp/usbwifi_hotspot/daemon.log 2>/dev/null"
                }, 5);

                otaUpdateAvailable = false;
                otaStatusSummary = "v" + remoteVer + " 업데이트 완료!";
                actionBannerMsg = "✅ GitHub v" + remoteVer + " 업데이트 완료! 2초 후 새 버전으로 자동 재시작합니다...";
                postToUi(REFRESH_TASK);

                if (daemonChanged || forceApply) {
                    writeBytesAtomic(new File(TMP_DIR_PATH, "ota_reload_daemon"), "1\n".getBytes(UTF8));
                }
                if (jarChanged || forceApply) {
                    final boolean wasVisible = isVisible;
                    Thread.sleep(1500L);
                    if (wasVisible) {
                        writeBytesAtomic(new File(TMP_DIR_PATH, "show_ui"), "1\n".getBytes(UTF8));
                    }
                    String relaunchCmd = "sleep 1; rm -f /data/local/tmp/usbwifi_hotspot/ui_java.lock /data/local/tmp/usbwifi_hotspot/ui_java.pid 2>/dev/null; "
                            + "/system/bin/sh \"" + localLaunch.getAbsolutePath() + "\" >/dev/null 2>&1 &";
                    new ProcessBuilder("/system/bin/sh", "-c", relaunchCmd).start();
                    try {
                        if (sInstanceLock != null) sInstanceLock.release();
                        if (sLockFos != null) sLockFos.close();
                    } catch (Throwable ignored) {
                    }
                    System.exit(0);
                }
            } catch (Throwable t) {
                otaStatusSummary = "연결 확인 필요";
                if (!silentIfUpToDate) {
                    actionBannerMsg = "⚠️ GitHub 업데이트 실패: " + t.getMessage() + " (LTE 또는 Wi-Fi 인터넷 연결 확인)";
                }
            } finally {
                otaCheckingOrUpdating = false;
                postToUi(REFRESH_TASK);
            }
        }

        private static void toggleSubnetFromUi() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        Map<String, String> conf = parseKeyValueFile(confFile);
                        String curSub = stripQuotes(getOrDefault(conf, "FORCE_SUBNET", "AUTO"));
                        String nextSub;
                        if ("AUTO".equalsIgnoreCase(curSub)) {
                            nextSub = "192.168.0.1";
                        } else if ("192.168.0.1".equals(curSub)) {
                            nextSub = "192.168.43.1";
                        } else {
                            nextSub = "AUTO";
                        }
                        updateConfigKeys(confFile, new String[][]{
                                {"FORCE_SUBNET", nextSub}
                        });
                        writeBytesAtomic(new File(TMP_DIR_PATH, "force_restart"), "1\n".getBytes(UTF8));
                        actionBannerMsg = "✅ 핫스팟 IP 대역 모드를 [" + nextSub + "]로 변경하고 재시작합니다.";
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ IP 대역 변경 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ToggleSubnetThread").start();
        }

        private static void triggerExportLogsFromUi() {
            actionBannerMsg = "⏳ USB 메모리(/storage/usb0/usbwifi_hotspot_log)로 진단 로그 저장 중...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        exportLogsNow();
                        String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                        File usbLogDir = new File("/storage/usb0/usbwifi_hotspot_log");
                        if (usbLogDir.isDirectory()) {
                            actionBannerMsg = "✅ USB(/storage/usb0/usbwifi_hotspot_log)에 로그 저장 완료! (" + nowStr + ")";
                        } else {
                            actionBannerMsg = "✅ 로컬(/data/local/tmp/usbwifi_hotspot)에 저장 완료! (USB 연결 시 자동 복사)";
                        }
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 로그 저장 중 오류: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ExportLogsThread").start();
        }

        private static void disableViewEffects(Object view, boolean hideAccessibility) {
            try {
                Class<?> viewCls = Class.forName("android.view.View");
                viewCls.getMethod("setSoundEffectsEnabled", boolean.class).invoke(view, false);
                viewCls.getMethod("setHapticFeedbackEnabled", boolean.class).invoke(view, false);
                if (hideAccessibility) {
                    viewCls.getMethod("setImportantForAccessibility", int.class).invoke(view, 4);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void setTextViewStyle(Object tv, String text, float sizeSp, int color, boolean bold) throws Exception {
            Class<?> tvCls = Class.forName("android.widget.TextView");
            tvCls.getMethod("setText", CharSequence.class).invoke(tv, text);
            tvCls.getMethod("setTextSize", int.class, float.class).invoke(tv, 2, sizeSp);
            tvCls.getMethod("setTextColor", int.class).invoke(tv, color);
            if (bold) {
                Class<?> tfCls = Class.forName("android.graphics.Typeface");
                Object defaultBold = tfCls.getField("DEFAULT_BOLD").get(null);
                tvCls.getMethod("setTypeface", tfCls, int.class).invoke(tv, defaultBold, 1);
            }
        }

        private static void setRoundedBg(Object view, int fillColor, int strokeColor, int strokeWidthPx, float radiusPx) throws Exception {
            Class<?> gdCls = Class.forName("android.graphics.drawable.GradientDrawable");
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable");
            Class<?> viewCls = Class.forName("android.view.View");
            Object gd = gdCls.getConstructor().newInstance();
            gdCls.getMethod("setColor", int.class).invoke(gd, fillColor);
            gdCls.getMethod("setCornerRadius", float.class).invoke(gd, radiusPx);
            gdCls.getMethod("setStroke", int.class, int.class).invoke(gd, strokeWidthPx, strokeColor);
            viewCls.getMethod("setBackgroundDrawable", drawableCls).invoke(view, gd);
        }

        private static void setMargins(Object marginLp, int l, int t, int r, int b) throws Exception {
            Class<?> mlpCls = Class.forName("android.view.ViewGroup$MarginLayoutParams");
            mlpCls.getMethod("setMargins", int.class, int.class, int.class, int.class).invoke(marginLp, l, t, r, b);
        }

        private static void attachClickListener(Object view, final Runnable action) throws Exception {
            disableViewEffects(view, false);
            Class<?> touchListenerCls = Class.forName("android.view.View$OnTouchListener");
            Class<?> viewCls = Class.forName("android.view.View");
            viewCls.getMethod("setClickable", boolean.class).invoke(view, true);
            Object proxy = Proxy.newProxyInstance(
                    touchListenerCls.getClassLoader(),
                    new Class<?>[]{touchListenerCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) {
                            String mName = method.getName();
                            if ("equals".equals(mName)) {
                                return Boolean.valueOf(args != null && args.length == 1 && proxyObj == args[0]);
                            }
                            if ("hashCode".equals(mName)) {
                                return Integer.valueOf(System.identityHashCode(proxyObj));
                            }
                            if ("toString".equals(mName)) {
                                return "UsbWifiTouchListener";
                            }
                            if ("onTouch".equals(mName) && args != null && args.length >= 2 && args[1] != null) {
                                try {
                                    Object motionEvent = args[1];
                                    int act = ((Integer) motionEvent.getClass().getMethod("getAction").invoke(motionEvent)).intValue() & 0xFF;
                                    if (act == 1) { // ACTION_UP
                                        shownAtMs = System.currentTimeMillis();
                                        action.run();
                                    }
                                } catch (Throwable ignored) {
                                }
                                return Boolean.TRUE;
                            }
                            return Boolean.TRUE;
                        }
                    }
            );
            viewCls.getMethod("setOnTouchListener", touchListenerCls).invoke(view, proxy);
        }

        public static int sampleCpuUsagePercent() {
            try {
                String line = readFirstLineQuick(new File("/proc/stat"));
                if (line != null && line.startsWith("cpu")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 5) {
                        long user = Long.parseLong(parts[1]);
                        long nice = Long.parseLong(parts[2]);
                        long sys = Long.parseLong(parts[3]);
                        long idle = Long.parseLong(parts[4]);
                        long iowait = parts.length >= 6 ? Long.parseLong(parts[5]) : 0L;
                        long irq = parts.length >= 7 ? Long.parseLong(parts[6]) : 0L;
                        long softirq = parts.length >= 8 ? Long.parseLong(parts[7]) : 0L;
                        long idleAll = idle + iowait;
                        long total = user + nice + sys + idleAll + irq + softirq;
                        long dTotal = total - lastCpuTotalTicks;
                        long dIdle = idleAll - lastCpuIdleTicks;
                        lastCpuTotalTicks = total;
                        lastCpuIdleTicks = idleAll;
                        if (dTotal > 0L) {
                            int pct = (int) Math.round(((dTotal - dIdle) * 100.0) / dTotal);
                            lastCpuLoadPct = Math.max(0, Math.min(100, pct));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return lastCpuLoadPct;
        }

        public static String readRamUsageSummary() {
            BufferedReader br = null;
            try {
                File memFile = new File("/proc/meminfo");
                if (!memFile.exists()) return "💾 RAM: --";
                br = new BufferedReader(new InputStreamReader(new FileInputStream(memFile), UTF8));
                long totalKb = 0L;
                long freeKb = 0L;
                long buffersKb = 0L;
                long cachedKb = 0L;
                String line;
                int found = 0;
                while ((line = br.readLine()) != null && found < 4) {
                    if (line.startsWith("MemTotal:")) {
                        totalKb = parseLeadingLong(line.substring(9), 0L);
                        found++;
                    } else if (line.startsWith("MemFree:")) {
                        freeKb = parseLeadingLong(line.substring(8), 0L);
                        found++;
                    } else if (line.startsWith("Buffers:")) {
                        buffersKb = parseLeadingLong(line.substring(8), 0L);
                        found++;
                    } else if (line.startsWith("Cached:")) {
                        cachedKb = parseLeadingLong(line.substring(7), 0L);
                        found++;
                    }
                }
                if (totalKb > 0L) {
                    long usedKb = Math.max(0L, totalKb - freeKb - buffersKb - cachedKb);
                    return "💾 RAM: " + (usedKb / 1024L) + "/" + (totalKb / 1024L) + "MB";
                }
            } catch (Throwable ignored) {
            } finally {
                if (br != null) try { br.close(); } catch (Exception ignored) {}
            }
            return "💾 RAM: --";
        }
    }

    // =========================================================================
    // HELPERS & DAEMON ENTRYPOINT
    // =========================================================================

    public static void exportLogsNow() {
        String cmd = "TMP_DIR=/data/local/tmp/usbwifi_hotspot; "
                + "mkdir -p \"$TMP_DIR\" 2>/dev/null; "
                + "ip addr show > \"$TMP_DIR/ip_addr.txt\" 2>/dev/null; "
                + "ip route show table all > \"$TMP_DIR/ip_route.txt\" 2>/dev/null; "
                + "cat /proc/modules > \"$TMP_DIR/proc_modules.txt\" 2>/dev/null; "
                + "cat /proc/net/dev > \"$TMP_DIR/proc_net_dev.txt\" 2>/dev/null; "
                + "ls -l /sys/class/net > \"$TMP_DIR/sys_class_net.txt\" 2>/dev/null; "
                + "dmesg > \"$TMP_DIR/dmesg_latest.txt\" 2>/dev/null; "
                + "if [ -d /storage/usb0 ] && [ -w /storage/usb0 ]; then "
                + "  ULOG=/storage/usb0/usbwifi_hotspot_log; "
                + "  mkdir -p \"$ULOG\" 2>/dev/null; "
                + "  cp \"$TMP_DIR/\"*.log \"$ULOG/\" 2>/dev/null; "
                + "  cp \"$TMP_DIR/\"*.txt \"$ULOG/\" 2>/dev/null; "
                // hostapd.conf / wpa_supplicant.conf: copy with passphrases masked
                + "  for _cf in \"$TMP_DIR/\"*.conf; do [ -f \"$_cf\" ] || continue; "
                + "    while IFS= read -r _l || [ -n \"$_l\" ]; do case \"$_l\" in "
                + "      *wpa_passphrase=*) echo \"${_l%%wpa_passphrase=*}wpa_passphrase=********\" ;; "
                + "      *psk=*) echo \"${_l%%psk=*}psk=********\" ;; "
                + "      *) echo \"$_l\" ;; esac; done < \"$_cf\" > \"$ULOG/${_cf##*/}\" 2>/dev/null; "
                + "  done; "
                + "  cp \"$TMP_DIR/\"*.leases \"$ULOG/\" 2>/dev/null; "
                + "  [ -f /data/local/tmp/usb_sta.list ] && cp /data/local/tmp/usb_sta.list \"$ULOG/\" 2>/dev/null; "
                + "  sync; "
                + "fi";
        execCommandCapture(new String[]{"/system/bin/sh", "-c", cmd}, 150);
    }

    public static void updateConfigKeys(File confFile, String[][] updates) throws IOException {
        List<String> lines = confFile.isFile() ? readLinesQuick(confFile, 500) : new ArrayList<String>();
        for (String[] kv : updates) {
            String key = kv[0];
            String val = kv[1];
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith(key + "=")) {
                    lines.set(i, key + "=" + val);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(key + "=" + val);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        writeBytesAtomic(confFile, sb.toString().getBytes(UTF8));
    }

    public static Map<String, String> parseKeyValueFile(File f) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (f == null || !f.isFile()) return map;
        List<String> lines = readLinesQuick(f, 200);
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq > 0) {
                String k = t.substring(0, eq).trim();
                String v = stripQuotes(t.substring(eq + 1).trim());
                map.put(k, v);
            }
        }
        return map;
    }

    private static String getOrDefault(Map<String, String> m, String k, String def) {
        String v = m.get(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String normalizeLower(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : stripQuotes(s.trim()).toLowerCase(Locale.US);
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> spCls = Class.forName("android.os.SystemProperties");
            String val = (String) spCls.getMethod("get", String.class, String.class).invoke(null, key, def);
            return (val == null || val.isEmpty()) ? def : val;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String readFirstLineQuick(File f) {
        BufferedReader br = null;
        try {
            if (!f.isFile()) return "";
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String s = br.readLine();
            return s != null ? s.trim() : "";
        } catch (Throwable e) {
            return "";
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
    }

    private static List<String> readLinesQuick(File f, int maxLines) {
        List<String> out = new ArrayList<String>();
        BufferedReader br = null;
        try {
            if (!f.isFile()) return out;
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line;
            while ((line = br.readLine()) != null && out.size() < maxLines) {
                out.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static List<String> readTailLines(File f, int tailCount) {
        List<String> all = new ArrayList<String>();
        BufferedReader br = null;
        try {
            if (!f.isFile()) return all;
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    all.add(t);
                    if (all.size() > tailCount * 4 && all.size() > 40) {
                        all.remove(0);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        if (all.size() <= tailCount) return all;
        return new ArrayList<String>(all.subList(all.size() - tailCount, all.size()));
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    public static void writeBytesAtomic(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(content);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(target)) {
            // Some filesystems refuse rename-over-existing: delete then retry once
            if (target.exists() && !target.delete()) {
                tmp.delete();
                throw new IOException("Cannot replace " + target.getAbsolutePath());
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("Cannot write " + target.getAbsolutePath());
            }
        }
    }

    /**
     * Decodes wpa_cli's printf-style SSID escaping (\\, \", \e, \n, \r, \t, \xNN) back to the real
     * SSID so that UTF-8 (e.g. Korean) names and names with backslashes/quotes match the actual AP.
     * Returns the input unchanged if it would decode to control characters or invalid UTF-8.
     */
    public static String decodeWpaSsid(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        byte[] raw = s.getBytes(UTF8);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b != '\\' || i + 1 >= raw.length) {
                bos.write(b);
                continue;
            }
            byte n = raw[++i];
            if (n == '\\' || n == '"') {
                bos.write(n);
            } else if (n == 'x' && i + 2 < raw.length) {
                int hi = Character.digit((char) raw[i + 1], 16);
                int lo = Character.digit((char) raw[i + 2], 16);
                if (hi < 0 || lo < 0) return s;
                int v = (hi << 4) | lo;
                if (v < 0x20 || v == 0x7F) return s;
                bos.write(v);
                i += 2;
            } else {
                return s; // \e \n \r \t or unknown: control chars are not allowed in our config files
            }
        }
        byte[] out = bos.toByteArray();
        try {
            java.nio.charset.CharsetDecoder dec = UTF8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(out)).toString();
        } catch (Throwable e) {
            return s;
        }
    }

    /** One parsed row of the Realtek /proc/net/rtl*bu/<iface>/survey_info table. */
    public static final class SurveyRow {
        public int ch = 1;
        public int dbm = -75;
        public String flags = "";
        public String ssid = "";
    }

    /**
     * Column layout differs per driver, so read it from the header:
     *   88x2bu: "index bssid ch RSSI SdBm Noise age flag ssid"
     *   8851bu: "index bssid band ch RSSI SdBm Noise alpha2 age flag ssid"
     * Returns {chColumn, dBmColumn}.
     */
    public static int[] parseSurveyHeader(String header) {
        String[] h = header.trim().split("\\s+");
        int ch = 2;
        int dbm = 4;
        for (int i = 0; i < h.length; i++) {
            if ("ch".equalsIgnoreCase(h[i])) ch = i;
            else if ("SdBm".equalsIgnoreCase(h[i])) dbm = i;
        }
        return new int[]{ch, dbm};
    }

    /**
     * Parses a survey_info data row. The flag column is the first "[..][..]" token after the BSSID
     * and everything after it is the SSID (which may contain spaces or start with '['). This does
     * not depend on the number of columns, which varies between drivers (and data rows of 8851bu
     * even carry one more token than its header names).
     */
    public static SurveyRow parseSurveyRow(String line, int chCol, int dbmCol) {
        String t = line.trim();
        List<int[]> spans = new ArrayList<int[]>();
        int i = 0;
        int n = t.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(t.charAt(i))) i++;
            if (i >= n) break;
            int s = i;
            while (i < n && !Character.isWhitespace(t.charAt(i))) i++;
            spans.add(new int[]{s, i});
        }
        if (spans.size() < 3) return null;
        String bssid = t.substring(spans.get(1)[0], spans.get(1)[1]);
        if (bssid.indexOf(':') <= 0) return null;
        int flagIdx = -1;
        for (int k = 2; k < spans.size(); k++) {
            String tok = t.substring(spans.get(k)[0], spans.get(k)[1]);
            if (tok.matches("(\\[[^\\[\\]\\s]*\\])+")) {
                flagIdx = k;
                break;
            }
        }
        if (flagIdx < 0) return null;
        SurveyRow r = new SurveyRow();
        r.flags = t.substring(spans.get(flagIdx)[0], spans.get(flagIdx)[1]);
        r.ssid = (flagIdx + 1 < spans.size()) ? t.substring(spans.get(flagIdx + 1)[0]).trim() : "";
        if (chCol > 1 && chCol < flagIdx) {
            r.ch = (int) parseLongSafe(t.substring(spans.get(chCol)[0], spans.get(chCol)[1]), 1L);
        }
        if (dbmCol > 1 && dbmCol < flagIdx) {
            r.dbm = (int) parseLongSafe(t.substring(spans.get(dbmCol)[0], spans.get(dbmCol)[1]), -75L);
        }
        return r;
    }

    /** True if the value can be stored on a single KEY=VALUE line and in wpa_supplicant/hostapd configs. */
    public static boolean isSafeConfigValue(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    private static String execCommandCapture(String[] cmd, int maxLines) {
        Process proc = null;
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();
        try {
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < maxLines) {
                sb.append(line).append('\n');
                count++;
            }
            proc.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
            if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static long parseHexSafe(String s, long def) {
        if (s == null) return def;
        try {
            String t = s.trim().toLowerCase(Locale.US);
            if (t.startsWith("0x")) t = t.substring(2);
            return Long.parseLong(t, 16);
        } catch (Throwable e) {
            return def;
        }
    }

    private static long parseLeadingLong(String s, long def) {
        if (s == null) return def;
        String t = s.trim();
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        return num.length() > 0 ? parseLongSafe(num.toString(), def) : def;
    }

    private static FileOutputStream sLockFos = null;
    private static java.nio.channels.FileLock sInstanceLock = null;

    public static void runDaemon(String installDir, boolean showNow) throws Exception {
        File tmpDir = new File(TMP_DIR_PATH);
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File showUiFile = new File(tmpDir, "show_ui");

        if (showNow) {
            writeBytesAtomic(showUiFile, "1".getBytes(UTF8));
        }

        // Single-instance kernel file lock so two UI processes can NEVER coexist
        try {
            File lockFile = new File(tmpDir, "ui_java.lock");
            sLockFos = new FileOutputStream(lockFile);
            sInstanceLock = sLockFos.getChannel().tryLock();
            if (sInstanceLock == null) {
                System.out.println("[USBWifiUI] Another UsbWifiStatusMain instance holds ui_java.lock; exiting cleanly.");
                System.exit(0);
                return;
            }
        } catch (Throwable lockErr) {
            System.out.println("[USBWifiUI] Lock check warning: " + lockErr);
        }

        try {
            String myPid = new File("/proc/self").getCanonicalFile().getName();
            if (myPid.matches("\\d+")) {
                writeBytesAtomic(new File(tmpDir, "ui_java.pid"), (myPid + "\n").getBytes(UTF8));
            }
        } catch (Throwable ignored) {
        }

        System.out.println("[USBWifiUI] Starting UsbWifiStatusMain v" + UI_VERSION + " (installDir=" + installDir + ")");
        try {
            StatusDashboardOverlay.latestSnapshot = collectSnapshot(installDir);
        } catch (Throwable ignored) {
        }
        StatusDashboardOverlay.init(installDir);

        while (true) {
            if (showUiFile.exists()) {
                showUiFile.delete();
                try {
                    if (StatusDashboardOverlay.latestSnapshot == null) {
                        StatusDashboardOverlay.latestSnapshot = collectSnapshot(installDir);
                    }
                } catch (Throwable ignored) {
                }
                StatusDashboardOverlay.show();
            }
            Thread.sleep(200L);
        }
    }

    public static byte[] patchHostapdBytes(byte[] raw) {
        byte[] needle = "/data/misc/wifi/all_sta.list".getBytes(UTF8);
        byte[] repl = "/data/local/tmp/usb_sta.list".getBytes(UTF8);
        if (needle.length != repl.length) {
            throw new IllegalStateException("Replacement length mismatch");
        }
        byte[] out = new byte[raw.length];
        System.arraycopy(raw, 0, out, 0, raw.length);
        for (int i = 0; i <= out.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (out[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(repl, 0, out, i, repl.length);
                i += needle.length - 1;
            }
        }
        return out;
    }

    public static void patchHostapdBinary(File src, File dst) throws IOException {
        byte[] raw = readAllBytes(new FileInputStream(src));
        byte[] patched = patchHostapdBytes(raw);
        writeBytesAtomic(dst, patched);
        dst.setExecutable(true, false);
        dst.setReadable(true, false);
    }

    public static void ensureGitHubRoutingRule() {
        try {
            if (!new File("/system/bin/ip").exists()) return;
            String cmd = "if ! ip rule show 2>/dev/null | grep -q '9800:.*lookup 62'; then "
                    + "  _tbl=61; "
                    + "  if ip route show table 60 2>/dev/null | grep -q '^default .* dev rmnet1'; then _tbl=60; "
                    + "  elif ip route show table 61 2>/dev/null | grep -q '^default .* dev rmnet1'; then _tbl=61; fi; "
                    + "  ip rule del pref 9850 2>/dev/null; "
                    + "  ip rule del pref 9851 2>/dev/null; "
                    + "  ip rule del pref 9852 2>/dev/null; "
                    + "  ip rule add pref 9850 to 8.8.8.8/32 lookup $_tbl 2>/dev/null; "
                    + "  ip rule add pref 9851 to 1.1.1.1/32 lookup $_tbl 2>/dev/null; "
                    + "  ip rule add pref 9852 to 185.199.108.0/22 lookup $_tbl 2>/dev/null; "
                    + "fi";
            execCommandCapture(new String[]{"/system/bin/sh", "-c", cmd}, 5);
        } catch (Throwable ignored) {
        }
    }

    private static final byte[][] GITHUB_ANYCAST_IPS = new byte[][]{
            new byte[]{(byte) 185, (byte) 199, (byte) 108, (byte) 133},
            new byte[]{(byte) 185, (byte) 199, (byte) 109, (byte) 133},
            new byte[]{(byte) 185, (byte) 199, (byte) 110, (byte) 133},
            new byte[]{(byte) 185, (byte) 199, (byte) 111, (byte) 133}
    };

    public static byte[] resolveHostViaPublicDns(String host, String dnsServerIp, int timeoutMs) {
        DatagramSocket ds = null;
        try {
            ByteArrayOutputStream q = new ByteArrayOutputStream(64);
            q.write(0x12);
            q.write(0x34); // ID
            q.write(0x01);
            q.write(0x00); // Flags: standard query, recursion desired
            q.write(0x00);
            q.write(0x01); // QDCOUNT = 1
            q.write(0x00);
            q.write(0x00); // ANCOUNT = 0
            q.write(0x00);
            q.write(0x00); // NSCOUNT = 0
            q.write(0x00);
            q.write(0x00); // ARCOUNT = 0
            String[] labels = host.split("\\.");
            for (String lb : labels) {
                byte[] b = lb.getBytes(UTF8);
                q.write(b.length);
                q.write(b);
            }
            q.write(0x00); // root label
            q.write(0x00);
            q.write(0x01); // QTYPE = A (1)
            q.write(0x00);
            q.write(0x01); // QCLASS = IN (1)

            byte[] req = q.toByteArray();
            String[] dparts = dnsServerIp.split("\\.");
            byte[] dnsIp = new byte[]{
                    (byte) Integer.parseInt(dparts[0]),
                    (byte) Integer.parseInt(dparts[1]),
                    (byte) Integer.parseInt(dparts[2]),
                    (byte) Integer.parseInt(dparts[3])
            };
            ds = new DatagramSocket();
            ds.setSoTimeout(timeoutMs);
            ds.send(new DatagramPacket(req, req.length, InetAddress.getByAddress(dnsIp), 53));
            byte[] resp = new byte[512];
            DatagramPacket dp = new DatagramPacket(resp, resp.length);
            ds.receive(dp);
            int len = dp.getLength();
            if (len < 12) return null;
            int anCount = ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF);
            if (anCount <= 0) return null;
            int idx = 12;
            while (idx < len && resp[idx] != 0) {
                if ((resp[idx] & 0xC0) == 0xC0) {
                    idx += 2;
                    break;
                }
                idx += 1 + (resp[idx] & 0xFF);
            }
            if (idx < len && resp[idx] == 0) idx++;
            idx += 4; // skip QTYPE + QCLASS
            for (int a = 0; a < anCount && idx + 10 <= len; a++) {
                if ((resp[idx] & 0xC0) == 0xC0) {
                    idx += 2;
                } else {
                    while (idx < len && resp[idx] != 0) {
                        idx += 1 + (resp[idx] & 0xFF);
                    }
                    idx++;
                }
                if (idx + 10 > len) break;
                int rtype = ((resp[idx] & 0xFF) << 8) | (resp[idx + 1] & 0xFF);
                int rdlen = ((resp[idx + 8] & 0xFF) << 8) | (resp[idx + 9] & 0xFF);
                idx += 10;
                if (rtype == 1 && rdlen == 4 && idx + 4 <= len) {
                    return new byte[]{resp[idx], resp[idx + 1], resp[idx + 2], resp[idx + 3]};
                }
                idx += rdlen;
            }
        } catch (Throwable ignored) {
        } finally {
            if (ds != null) try { ds.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    public static byte[] downloadGitHubOtaFile(String fileName, int timeoutMs) throws Exception {
        List<byte[]> candidateIps = new ArrayList<byte[]>();
        byte[] dns1 = resolveHostViaPublicDns(GITHUB_RAW_HOST, "8.8.8.8", 2000);
        if (dns1 != null) candidateIps.add(dns1);
        byte[] dns2 = resolveHostViaPublicDns(GITHUB_RAW_HOST, "1.1.1.1", 2000);
        if (dns2 != null) candidateIps.add(dns2);
        for (byte[] anycast : GITHUB_ANYCAST_IPS) {
            candidateIps.add(anycast);
        }

        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext sc;
        try {
            sc = SSLContext.getInstance("TLSv1.2");
        } catch (Throwable ignored) {
            sc = SSLContext.getInstance("TLS");
        }
        sc.init(null, trustAll, new SecureRandom());

        Exception lastErr = null;
        for (byte[] ipBytes : candidateIps) {
            Socket tcpSock = null;
            SSLSocket sslSock = null;
            try {
                InetAddress addr = InetAddress.getByAddress(GITHUB_RAW_HOST, ipBytes);
                tcpSock = new Socket();
                tcpSock.connect(new InetSocketAddress(addr, 443), timeoutMs);
                tcpSock.setSoTimeout(timeoutMs);

                sslSock = (SSLSocket) sc.getSocketFactory().createSocket(tcpSock, GITHUB_RAW_HOST, 443, true);
                try {
                    String[] sup = sslSock.getSupportedProtocols();
                    List<String> en = new ArrayList<String>();
                    for (String p : sup) {
                        if ("TLSv1.2".equals(p) || "TLSv1.1".equals(p) || "TLSv1".equals(p)) {
                            en.add(p);
                        }
                    }
                    if (!en.isEmpty()) {
                        sslSock.setEnabledProtocols(en.toArray(new String[en.size()]));
                    }
                } catch (Throwable ignored) {
                }
                try {
                    sslSock.getClass().getMethod("setHostname", String.class).invoke(sslSock, GITHUB_RAW_HOST);
                } catch (Throwable ignored) {
                }
                sslSock.startHandshake();

                String reqPath = GITHUB_OTA_BASE_PATH + "/" + fileName + "?t=" + System.currentTimeMillis();
                String httpReq = "GET " + reqPath + " HTTP/1.1\r\n"
                        + "Host: " + GITHUB_RAW_HOST + "\r\n"
                        + "User-Agent: HKG-Gen5W-USBWifi-OTA/" + UI_VERSION + "\r\n"
                        + "Accept: */*\r\n"
                        + "Cache-Control: no-cache, no-store\r\n"
                        + "Connection: close\r\n\r\n";
                sslSock.getOutputStream().write(httpReq.getBytes(UTF8));
                sslSock.getOutputStream().flush();

                InputStream in = sslSock.getInputStream();
                String statusLine = readHttpLine(in);
                if (statusLine == null || !statusLine.contains(" 200")) {
                    throw new IOException("HTTP 상태 오류: " + statusLine);
                }
                int contentLen = -1;
                boolean chunked = false;
                while (true) {
                    String hdr = readHttpLine(in);
                    if (hdr == null || hdr.isEmpty()) break;
                    String low = hdr.toLowerCase(Locale.US);
                    if (low.startsWith("content-length:")) {
                        contentLen = (int) parseLongSafe(hdr.substring(15).trim(), -1L);
                    } else if (low.startsWith("transfer-encoding:") && low.contains("chunked")) {
                        chunked = true;
                    }
                }
                return readHttpBody(in, contentLen, chunked);
            } catch (Exception e) {
                lastErr = e;
            } finally {
                if (sslSock != null) try { sslSock.close(); } catch (Exception ignored) {}
                if (tcpSock != null) try { tcpSock.close(); } catch (Exception ignored) {}
            }
        }
        throw (lastErr != null) ? lastErr : new IOException("GitHub 서버 연결 실패");
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        boolean gotAny = false;
        while ((b = in.read()) != -1) {
            gotAny = true;
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (!gotAny && bos.size() == 0) return null;
        return new String(bos.toByteArray(), UTF8);
    }

    private static byte[] readHttpBody(InputStream in, int contentLen, boolean chunked) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(contentLen > 0 ? contentLen : 16384);
        byte[] buf = new byte[4096];
        if (chunked) {
            while (true) {
                String line = readHttpLine(in);
                if (line == null) break;
                int semi = line.indexOf(';');
                String hex = (semi > 0 ? line.substring(0, semi) : line).trim();
                if (hex.isEmpty()) continue;
                int chunkSize = (int) parseHexSafe(hex, 0L);
                if (chunkSize <= 0) break;
                int rem = chunkSize;
                while (rem > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, rem));
                    if (n == -1) throw new IOException("Incomplete HTTP chunk");
                    bos.write(buf, 0, n);
                    rem -= n;
                }
                readHttpLine(in); // trailing CRLF
            }
        } else if (contentLen >= 0) {
            int rem = contentLen;
            while (rem > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, rem));
                if (n == -1) throw new IOException("Incomplete HTTP body");
                bos.write(buf, 0, n);
                rem -= n;
            }
        } else {
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : dig) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String sha256File(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            return sha256Hex(readAllBytes(new FileInputStream(f)));
        } catch (Throwable e) {
            return "";
        }
    }

    private static final String B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    public static String encodeBase64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 4) / 3 + 16);
        int i = 0;
        int lineLen = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xFF;
            int b1 = (i < data.length) ? (data[i++] & 0xFF) : -1;
            int b2 = (i < data.length) ? (data[i++] & 0xFF) : -1;
            sb.append(B64_ALPHABET.charAt(b0 >>> 2));
            sb.append(B64_ALPHABET.charAt(((b0 & 0x03) << 4) | (b1 >= 0 ? (b1 >>> 4) : 0)));
            sb.append(b1 >= 0 ? B64_ALPHABET.charAt(((b1 & 0x0F) << 2) | (b2 >= 0 ? (b2 >>> 6) : 0)) : '=');
            sb.append(b2 >= 0 ? B64_ALPHABET.charAt(b2 & 0x3F) : '=');
            lineLen += 4;
            if (lineLen >= 76 && i < data.length) {
                sb.append('\n');
                lineLen = 0;
            }
        }
        return sb.toString();
    }

    public static byte[] decodeBase64(String b64) {
        int[] rev = new int[128];
        for (int i = 0; i < rev.length; i++) rev[i] = -1;
        for (int i = 0; i < B64_ALPHABET.length(); i++) {
            rev[B64_ALPHABET.charAt(i)] = i;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream((b64.length() * 3) / 4);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == '=') break;
            if (c >= 128 || rev[c] < 0) continue;
            buf = (buf << 6) | rev[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((buf >>> bits) & 0xFF);
            }
        }
        return bos.toByteArray();
    }

    public static Map<String, String> parseJsonFlatMap(String json) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (json == null) return map;
        int i = 0;
        int n = json.length();
        while (i < n) {
            int q1 = json.indexOf('"', i);
            if (q1 < 0) break;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String key = json.substring(q1 + 1, q2);
            int colon = json.indexOf(':', q2 + 1);
            if (colon < 0) break;
            int v1 = json.indexOf('"', colon + 1);
            if (v1 < 0) break;
            int v2 = json.indexOf('"', v1 + 1);
            if (v2 < 0) break;
            String val = json.substring(v1 + 1, v2);
            map.put(key, val);
            i = v2 + 1;
        }
        return map;
    }

    /**
     * Physical [MAP] Button 3-Second Long-Press Monitor for Gen5W Head Units.
     *
     * Monitors:
     *  1. Linux /dev/input/event* physical key devices (specifically daudio_wp_daudioplus_<model>_kr on event1,
     *     telechips keypad / gpio-keys-polled on event0, and daudio_wp_daudioplus_<model>_kr_can on event2,
     *     while excluding touchscreens and rotary knobs).
     *  2. Android KeyLayout (.kl) files in /system/usr/keylayout/ and /vendor/usr/keylayout/ to map scanCodes
     *     to MAP / HKMC_MAP / KEY_MAP vs non-MAP keys (VOLUME, POWER, SEEK, TRACK, RADIO, MEDIA, SETUP, STAR, CALL).
     *  3. Logcat ModeManagerService / PhoneWindowManager / WindowManager / MicomD key events as a secondary source.
     *
     * Supports all 3 hardware hold behaviors for >= 3,000 ms (3.0 seconds):
     *  - Mode A: Continuous EV_KEY hold (value=1 held for >= 3,000 ms before value=0).
     *  - Mode B: Micom short-to-long keycode handoff (C_short held -> C_long keycode emitted while holding).
     *  - Mode C: Rapid repeat pulses (value=1 -> value=0 repeated every <550 ms for >= 3,000 ms).
     */
    public static final class MapButtonLongPressMonitor {
        public static final long HOLD_DURATION_MS = 3000L;
        private static final Object STATE_LOCK = new Object();
        private static volatile boolean started = false;
        private static String sInstallDir = "/data/nemesis/apps/usbwifi.hotspot";

        // Keycode classification tables populated from .kl files & hotspot.conf
        private static final Map<Integer, String> codeToKlName = new LinkedHashMap<Integer, String>();
        private static final List<Integer> knownMapCodes = new ArrayList<Integer>();
        private static final List<Integer> knownMapLongCodes = new ArrayList<Integer>();
        private static final List<Integer> knownNonMapCodes = new ArrayList<Integer>();
        private static final List<Integer> rawNaviCodes = new ArrayList<Integer>();
        private static final List<String> activeReaderPaths = new ArrayList<String>();

        // Hold state machine variables (guarded by STATE_LOCK)
        private static String activeDevName = "";
        private static int activeCode = -1;
        private static long holdStartMs = 0L;
        private static long lastEventMs = 0L;
        private static long lastReleaseMs = 0L;
        private static int lastReleasedCode = -1;
        private static int pulseCount = 0;
        private static boolean holdTriggered = false;
        private static long lastPopupTriggerMs = 0L;
        public static volatile int selfTestTriggerCount = 0;

        static {
            initDefaultNonMapCodes();
        }

        private static void initDefaultNonMapCodes() {
            // Standard Linux <linux/input.h> non-MAP keys (Back/Esc, Enter, Home, D-Pad, Vol, Mute, Power, Media, Call, Touch)
            int[] stdNonMap = new int[]{
                    1, 28, 102, 103, 104, 105, 106, 107, 108,
                    113, 114, 115, 116, 139, 142, 158,
                    163, 164, 165, 166, 167, 168,
                    200, 201, 207, 208, 231, 330
            };
            for (int c : stdNonMap) {
                if (!knownNonMapCodes.contains(c)) {
                    knownNonMapCodes.add(c);
                }
            }
        }

        public static void resetForSelfTest() {
            synchronized (STATE_LOCK) {
                codeToKlName.clear();
                knownMapCodes.clear();
                knownMapLongCodes.clear();
                knownNonMapCodes.clear();
                rawNaviCodes.clear();
                initDefaultNonMapCodes();
                activeDevName = "";
                activeCode = -1;
                holdStartMs = 0L;
                lastEventMs = 0L;
                lastReleaseMs = 0L;
                lastReleasedCode = -1;
                pulseCount = 0;
                holdTriggered = false;
                lastPopupTriggerMs = 0L;
                selfTestTriggerCount = 0;
            }
        }

        public static void parseKeyLayoutLines(String fileName, List<String> lines, boolean isDeviceSpecific) {
            if (lines == null) return;
            synchronized (STATE_LOCK) {
                for (String raw : lines) {
                    if (raw == null) continue;
                    String line = raw.trim();
                    int hash = line.indexOf('#');
                    if (hash >= 0) {
                        line = line.substring(0, hash).trim();
                    }
                    if (!line.startsWith("key ")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 3) continue;
                    int code = (int) parseLongSafe(parts[1], -1L);
                    if (code <= 0) continue;
                    String keyName = parts[2].trim().toUpperCase(Locale.US);

                    if (isDeviceSpecific || !codeToKlName.containsKey(code)) {
                        codeToKlName.put(code, keyName + (isDeviceSpecific ? ("@" + fileName) : ""));
                    }

                    if (keyName.contains("MAP")) {
                        if (!knownMapCodes.contains(code)) {
                            knownMapCodes.add(code);
                        }
                        if (keyName.contains("LONG") && !knownMapLongCodes.contains(code)) {
                            knownMapLongCodes.add(code);
                        }
                        knownNonMapCodes.remove(Integer.valueOf(code));
                    } else if (isExplicitNonMapKeyName(keyName)) {
                        if (!knownMapCodes.contains(code)) {
                            if (!knownNonMapCodes.contains(code)) {
                                knownNonMapCodes.add(code);
                            }
                        }
                    } else if (keyName.contains("NAVI") && isDeviceSpecific) {
                        if (!rawNaviCodes.contains(code)) {
                            rawNaviCodes.add(code);
                        }
                    }
                }
                // If a device-specific .kl had an explicit *MAP* key, treat separate NAVI key as non-MAP
                if (!knownMapCodes.isEmpty()) {
                    for (Integer nc : rawNaviCodes) {
                        if (!knownMapCodes.contains(nc) && !knownNonMapCodes.contains(nc)) {
                            knownNonMapCodes.add(nc);
                        }
                    }
                } else {
                    for (Integer nc : rawNaviCodes) {
                        if (!knownMapCodes.contains(nc)) {
                            knownMapCodes.add(nc);
                        }
                    }
                }
            }
        }

        private static boolean isExplicitNonMapKeyName(String upper) {
            String[] nonMapTokens = new String[]{
                    "VOLUME", "VOL_", "MUTE", "POWER", "SLEEP", "WAKE",
                    "MEDIA", "MUSIC", "AUDIO", "VIDEO", "RADIO", "DMB", "TUNER", "FM", "AM",
                    "SEEK", "TRACK", "NEXT", "PREV", "FASTFORWARD", "REWIND", "PLAY", "PAUSE", "STOP",
                    "SETUP", "SETTING", "CONFIG", "STAR", "CUSTOM", "FAVORITE", "UVO", "BLUELINK",
                    "CALL", "ENDCALL", "PHONE", "HEADSET", "HOOK", "VOICE", "VR", "PTT",
                    "HOME", "BACK", "ESCAPE", "MENU", "DISP", "CAMERA", "HAZARD", "EJECT", "TUNE", "KNOB",
                    "DPAD", "ENTER", "SPACE", "DEL", "TAB", "SHIFT", "CTRL", "ALT"
            };
            for (String tok : nonMapTokens) {
                if (upper.contains(tok)) return true;
            }
            return false;
        }

        private static String detectVehicleFasciaPrefix() {
            File inputDir = new File("/sys/class/input");
            File[] files = inputDir.listFiles();
            if (files == null) return "";
            for (File f : files) {
                if (!f.getName().startsWith("event")) continue;
                String devName = readFirstLineQuick(new File(f, "device/name")).toLowerCase(Locale.US).trim();
                if (devName.startsWith("daudio_wp_") && !devName.endsWith("_can") && !devName.endsWith("_knob")) {
                    String prefix = devName;
                    String[] suffixes = new String[]{"_kr", "_eu", "_us", "_cn", "_in", "_au", "_me", "_ru", "_br", "_gen", "_base"};
                    for (String sfx : suffixes) {
                        if (prefix.endsWith(sfx) && prefix.length() > sfx.length()) {
                            prefix = prefix.substring(0, prefix.length() - sfx.length());
                            break;
                        }
                    }
                    return prefix;
                }
            }
            return "";
        }

        private static void loadSystemKeyLayouts(String installDir) {
            try {
                File confFile = new File(installDir, "config/hotspot.conf");
                Map<String, String> conf = parseKeyValueFile(confFile);
                String customCodeStr = stripQuotes(getOrDefault(conf, "MAP_KEY_CODE", "AUTO"));
                int customCode = (int) parseLongSafe(customCodeStr, -1L);
                if (customCode > 0) {
                    synchronized (STATE_LOCK) {
                        if (!knownMapCodes.contains(customCode)) {
                            knownMapCodes.add(customCode);
                        }
                        knownNonMapCodes.remove(Integer.valueOf(customCode));
                    }
                }
            } catch (Throwable ignored) {
            }

            String vehiclePrefix = detectVehicleFasciaPrefix();
            String[] klDirs = new String[]{"/system/usr/keylayout", "/vendor/usr/keylayout"};
            List<File> matchedFiles = new ArrayList<File>();
            List<File> fallbackFiles = new ArrayList<File>();
            List<File> genericFiles = new ArrayList<File>();
            for (String dirPath : klDirs) {
                File dir = new File(dirPath);
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile() || !f.getName().endsWith(".kl")) continue;
                    String low = f.getName().toLowerCase(Locale.US);
                    if ("generic.kl".equals(low)) {
                        genericFiles.add(f);
                    } else if (!vehiclePrefix.isEmpty() && low.startsWith(vehiclePrefix + "_")) {
                        matchedFiles.add(f);
                    } else if (low.contains("daudio") || low.contains("tcc") || low.contains("gpio")
                            || low.contains("keypad") || low.contains("mobis") || low.contains("hkmc")) {
                        fallbackFiles.add(f);
                    }
                }
            }

            // Load Generic.kl first, then vehicle-matched .kl (or fallback .kl files if no exact match)
            for (File gf : genericFiles) {
                parseKeyLayoutLines(gf.getName(), readLinesQuick(gf, 600), false);
            }
            List<File> activeSpecific = !matchedFiles.isEmpty() ? matchedFiles : fallbackFiles;
            for (File sf : activeSpecific) {
                List<String> lines = readLinesQuick(sf, 400);
                parseKeyLayoutLines(sf.getName(), lines, true);
                if (!matchedFiles.isEmpty()) {
                    appendKeyLog("[KL-Matched] " + sf.getName() + " (prefix=" + vehiclePrefix + ", " + lines.size() + " lines)");
                    for (String ln : lines) {
                        String t = ln.trim();
                        if (t.startsWith("key ")) {
                            appendKeyLog("  " + sf.getName() + ": " + t);
                        }
                    }
                }
            }
            appendKeyLog("[KL-Summary] prefix='" + vehiclePrefix + "' matched=" + matchedFiles.size()
                    + " knownMapCodes=" + knownMapCodes + ", knownMapLongCodes=" + knownMapLongCodes);
        }

        public static boolean isEligibleMapKey(String devName, boolean isFasciaDevice, int code) {
            synchronized (STATE_LOCK) {
                if (knownMapCodes.contains(code) || knownMapLongCodes.contains(code)) {
                    return true;
                }
                if (knownNonMapCodes.contains(code)) {
                    return false;
                }
                if (!isFasciaDevice) {
                    // On CAN / steering wheel devices, only trigger on explicitly mapped MAP keycodes
                    return false;
                }
                // On the front fascia keypad (daudio_wp_daudioplus_<model>_kr / event1):
                // All non-MAP buttons (RADIO, MEDIA, SEEK, TRACK, SETUP, STAR, POWER, VOL) are in knownNonMapCodes.
                // Even if .kl did not use the literal substring "MAP" for the MAP button (or uses a separate scanCode),
                // any remaining fascia button on event1/event0 is eligible as the MAP button!
                return true;
            }
        }

        /**
         * Processes an EV_KEY event (type == 1) and returns true if the 3.0s hold trigger just fired synchronously.
         */
        public static boolean onKeyEvent(String devName, boolean isFasciaDevice, int code, int value, long nowMs, boolean isSelfTest) {
            if (code <= 0) return false;
            boolean triggerNow = false;
            boolean postReleaseEnsureTop = false;
            boolean shortPressDismiss = false;
            String triggerDetail = "";

            synchronized (STATE_LOCK) {
                boolean eligible = isEligibleMapKey(devName, isFasciaDevice, code);
                boolean isHandoffCandidate = isFasciaDevice
                        && !knownNonMapCodes.contains(code)
                        && (activeCode != -1 || (holdStartMs > 0L && (nowMs - lastReleaseMs) <= 600L));

                if (!eligible && !isHandoffCandidate) {
                    // A non-MAP key was pressed on the fascia; cancel any unconfirmed pulse chain
                    if (value == 1 && isFasciaDevice) {
                        activeCode = -1;
                        holdStartMs = 0L;
                        pulseCount = 0;
                        shortPressDismiss = true;
                    }
                    if (shortPressDismiss && !isSelfTest) {
                        StatusDashboardOverlay.hideIfOpenForMoreThan(1500L);
                    }
                    return false;
                }

                if (value == 1) { // KEY_DOWN
                    if (activeCode == code) {
                        // Repeated value=1 while already held down; keep original holdStartMs
                        lastEventMs = nowMs;
                        if (!holdTriggered && holdStartMs > 0L && (nowMs - holdStartMs) >= HOLD_DURATION_MS) {
                            holdTriggered = true;
                            triggerNow = true;
                            triggerDetail = "EV_KEY repeat-down code=" + code + " (" + ((nowMs - holdStartMs) / 1000.0) + "s)";
                        }
                    } else if (activeCode != -1 && activeCode != code) {
                        // Handoff from short keycode to long keycode while still held down
                        activeCode = code;
                        activeDevName = devName;
                        lastEventMs = nowMs;
                        if (!holdTriggered && holdStartMs > 0L && (nowMs - holdStartMs) >= HOLD_DURATION_MS) {
                            holdTriggered = true;
                            triggerNow = true;
                            triggerDetail = "EV_KEY handoff code=" + code + " (" + ((nowMs - holdStartMs) / 1000.0) + "s)";
                        }
                    } else {
                        // activeCode == -1: check if this is a rapid repeat pulse or short->long keycode handoff
                        long sinceRelease = nowMs - lastReleaseMs;
                        long prevHoldDur = lastReleaseMs - holdStartMs;
                        boolean isRapidRepeat = (holdStartMs > 0L && code == lastReleasedCode && sinceRelease >= 0L && sinceRelease <= 550L);
                        boolean isShortToLongHandoff = (holdStartMs > 0L && code != lastReleasedCode
                                && ((prevHoldDur >= 700L && sinceRelease <= 600L)
                                || (knownMapLongCodes.contains(code) && (nowMs - holdStartMs) >= 1200L && (nowMs - holdStartMs) <= 4000L)));

                        if (isRapidRepeat || isShortToLongHandoff) {
                            activeCode = code;
                            activeDevName = devName;
                            lastEventMs = nowMs;
                            pulseCount++;
                            if (!holdTriggered && (nowMs - holdStartMs) >= HOLD_DURATION_MS) {
                                holdTriggered = true;
                                triggerNow = true;
                                triggerDetail = "EV_KEY hold-chain code=" + code + " (" + ((nowMs - holdStartMs) / 1000.0) + "s)";
                            }
                        } else {
                            // Fresh MAP button press
                            activeDevName = devName;
                            activeCode = code;
                            holdStartMs = nowMs;
                            lastEventMs = nowMs;
                            pulseCount = 1;
                            holdTriggered = false;
                        }
                    }
                } else if (value == 2) { // KEY_REPEAT
                    if (activeCode == code && holdStartMs > 0L) {
                        lastEventMs = nowMs;
                        if (!holdTriggered && (nowMs - holdStartMs) >= HOLD_DURATION_MS) {
                            holdTriggered = true;
                            triggerNow = true;
                            triggerDetail = "EV_KEY repeat code=" + code + " (" + ((nowMs - holdStartMs) / 1000.0) + "s)";
                        }
                    }
                } else if (value == 0) { // KEY_UP
                    if (activeCode == code) {
                        long heldMs = holdStartMs > 0L ? (nowMs - holdStartMs) : 0L;
                        if (!holdTriggered && holdStartMs > 0L && heldMs >= HOLD_DURATION_MS) {
                            holdTriggered = true;
                            triggerNow = true;
                            triggerDetail = "EV_KEY release after " + (heldMs / 1000.0) + "s (code=" + code + ")";
                        } else if (holdTriggered) {
                            postReleaseEnsureTop = true;
                        } else if (heldMs > 0L && heldMs < 900L && pulseCount <= 1) {
                            shortPressDismiss = true;
                        }
                        lastReleaseMs = nowMs;
                        lastReleasedCode = code;
                        activeCode = -1;
                    }
                }
            }

            if (triggerNow) {
                fireDashboardTrigger(triggerDetail, nowMs, isSelfTest);
            } else if (postReleaseEnsureTop && !isSelfTest) {
                StatusDashboardOverlay.ensureTopMostAfterKeyUp();
            } else if (shortPressDismiss && !isSelfTest) {
                StatusDashboardOverlay.hideIfOpenForMoreThan(1500L);
            }
            return triggerNow;
        }

        /**
         * Periodic 100ms timer check so that holding the MAP button immediately pops up the Dashboard
         * right as the hold reaches 3.0 seconds, without waiting for the user to release the button.
         */
        public static boolean checkPeriodicHoldTimer(long nowMs, boolean isSelfTest) {
            boolean triggerNow = false;
            String triggerDetail = "";
            synchronized (STATE_LOCK) {
                if (!holdTriggered && holdStartMs > 0L) {
                    long elapsed = nowMs - holdStartMs;
                    if (activeCode != -1 && elapsed >= HOLD_DURATION_MS) {
                        holdTriggered = true;
                        triggerNow = true;
                        triggerDetail = String.format(Locale.US, "MAP code=%d %.1f초 유지", activeCode, elapsed / 1000.0);
                    } else if (activeCode == -1 && pulseCount >= 4 && (nowMs - lastReleaseMs) <= 450L && elapsed >= HOLD_DURATION_MS) {
                        holdTriggered = true;
                        triggerNow = true;
                        triggerDetail = String.format(Locale.US, "MAP pulse code=%d %.1f초 유지", lastReleasedCode, elapsed / 1000.0);
                    }
                }
            }
            if (triggerNow) {
                fireDashboardTrigger(triggerDetail, nowMs, isSelfTest);
            }
            return triggerNow;
        }

        private static void fireDashboardTrigger(String detail, long nowMs, boolean isSelfTest) {
            if (isSelfTest) {
                selfTestTriggerCount++;
                return;
            }
            synchronized (STATE_LOCK) {
                if (nowMs - lastPopupTriggerMs < 2000L) {
                    return; // Debounce duplicate triggers within 2.0s
                }
                lastPopupTriggerMs = nowMs;
            }
            String logMsg = "[MapKeyMonitor] TRIGGERED Dashboard Popup! (" + detail + ")";
            System.out.println(logMsg);
            appendKeyLog(logMsg);
            try {
                File tmpDir = new File(TMP_DIR_PATH);
                if (!tmpDir.exists()) tmpDir.mkdirs();
                StatusDashboardOverlay.showFromMapLongPress(detail);
            } catch (Throwable t) {
                appendKeyLog("[MapKeyMonitor] showFromMapLongPress error: " + t);
            }
        }

        public static void start(final String installDir) {
            if (started) return;
            started = true;
            sInstallDir = installDir;

            loadSystemKeyLayouts(installDir);

            // 1. Periodic 100ms hold-timer & input device hot-discovery thread
            Thread timerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    long lastScanMs = 0L;
                    while (true) {
                        try {
                            long now = System.currentTimeMillis();
                            if (now - lastScanMs >= 5000L) {
                                lastScanMs = now;
                                scanAndAttachInputDevices();
                            }
                            checkPeriodicHoldTimer(now, false);
                            Thread.sleep(100L);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "UsbWifiMapHoldTimer");
            timerThread.setDaemon(true);
            timerThread.start();

            // 2. Companion logcat key monitor for ModeManagerService / PhoneWindowManager / WindowManager
            Thread logcatThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLogcatKeyMonitor();
                }
            }, "UsbWifiMapLogcatMonitor");
            logcatThread.setDaemon(true);
            logcatThread.start();
        }

        private static void scanAndAttachInputDevices() {
            File inputDir = new File("/dev/input");
            File[] files = inputDir.listFiles();
            if (files == null) return;
            for (File devFile : files) {
                String fname = devFile.getName();
                if (!fname.startsWith("event")) continue;
                final String absPath = devFile.getAbsolutePath();
                synchronized (STATE_LOCK) {
                    if (activeReaderPaths.contains(absPath)) continue;
                }
                String sysNamePath = "/sys/class/input/" + fname + "/device/name";
                String devName = readFirstLineQuick(new File(sysNamePath));
                String lowName = devName.toLowerCase(Locale.US);

                // Skip touchscreens and rotary knobs
                if (lowName.contains("touch") || lowName.contains("fts_ts") || lowName.contains("siw_")
                        || lowName.contains("melfas") || lowName.contains("mip4") || lowName.contains("atmel")
                        || lowName.contains("mxt") || lowName.contains("knob")) {
                    synchronized (STATE_LOCK) {
                        activeReaderPaths.add(absPath);
                    }
                    appendKeyLog("[InputSkip] " + fname + " (" + devName + ") -> skipped (touch/knob)");
                    continue;
                }

                final boolean isFascia = (lowName.contains("daudio") && !lowName.contains("_can") && !lowName.contains("_knob"))
                        || lowName.contains("keypad")
                        || lowName.contains("gpio-keys")
                        || "event0".equals(fname)
                        || "event1".equals(fname);
                final String label = devName.isEmpty() ? fname : (fname + ":" + devName);

                synchronized (STATE_LOCK) {
                    activeReaderPaths.add(absPath);
                }
                appendKeyLog("[InputAttach] Monitoring " + absPath + " name='" + devName + "' isFascia=" + isFascia);

                Thread reader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        readInputEventLoop(devFile, label, isFascia);
                    }
                }, "InputReader-" + fname);
                reader.setDaemon(true);
                reader.start();
            }
        }

        private static void readInputEventLoop(File devFile, String label, boolean isFascia) {
            FileInputStream fis = null;
            byte[] buf = new byte[16];
            try {
                fis = new FileInputStream(devFile);
                while (true) {
                    int off = 0;
                    while (off < 16) {
                        int n = fis.read(buf, off, 16 - off);
                        if (n < 0) {
                            throw new IOException("EOF on " + devFile.getAbsolutePath());
                        }
                        off += n;
                    }
                    int type = (buf[8] & 0xFF) | ((buf[9] & 0xFF) << 8);
                    int code = (buf[10] & 0xFF) | ((buf[11] & 0xFF) << 8);
                    int value = (buf[12] & 0xFF)
                            | ((buf[13] & 0xFF) << 8)
                            | ((buf[14] & 0xFF) << 16)
                            | ((buf[15] & 0xFF) << 24);

                    if (type == 1) { // EV_KEY
                        long now = System.currentTimeMillis();
                        String klName;
                        synchronized (STATE_LOCK) {
                            klName = codeToKlName.containsKey(code) ? codeToKlName.get(code) : "UNKNOWN";
                        }
                        String navifg = getSystemProperty("persist.sys.hkmc.navifg", "");
                        appendKeyLog("EV_KEY dev=" + label + " code=" + code + "(0x" + Integer.toHexString(code)
                                + ") val=" + value + " kl=" + klName + " navifg=" + navifg);
                        onKeyEvent(label, isFascia, code, value, now, false);
                    }
                }
            } catch (Throwable t) {
                appendKeyLog("[InputReaderExit] " + label + ": " + t);
            } finally {
                if (fis != null) try { fis.close(); } catch (Exception ignored) {}
                synchronized (STATE_LOCK) {
                    activeReaderPaths.remove(devFile.getAbsolutePath());
                }
            }
        }

        private static void runLogcatKeyMonitor() {
            while (true) {
                Process proc = null;
                BufferedReader br = null;
                try {
                    long startMs = System.currentTimeMillis();
                    proc = new ProcessBuilder(
                            "/system/bin/logcat", "-v", "time", "-b", "main",
                            "ModeManagerService:V", "PhoneWindowManager:D", "WindowManager:D", "InputReader:D", "*:S"
                    ).redirectErrorStream(true).start();
                    br = new BufferedReader(new InputStreamReader(proc.getInputStream(), UTF8));
                    String line;
                    long logMapDownMs = 0L;
                    boolean logMapTriggered = false;
                    while ((line = br.readLine()) != null) {
                        long now = System.currentTimeMillis();
                        // Skip historical logcat buffer during the first 2.0 seconds after attaching
                        if (now - startMs < 2000L) continue;
                        String up = line.toUpperCase(Locale.US);
                        if (!up.contains("MAP") && !up.contains("KEYCODE") && !up.contains("HARDKEY")) {
                            continue;
                        }
                        if (up.contains("NAVI_GPS_CHANGED") || up.contains("MAPMATCHING")) {
                            continue;
                        }
                        appendKeyLog("[LogcatKey] " + line.trim());

                        if (up.contains("MAP")) {
                            boolean isUp = up.contains("ACTION_UP") || up.contains("KEY_UP") || up.contains("UP=TRUE") || up.contains("RELEASE");
                            boolean isDown = up.contains("ACTION_DOWN") || up.contains("KEY_DOWN") || up.contains("DOWN=TRUE") || up.contains("PRESS");
                            boolean isLong = up.contains("LONG");

                            if (isUp) {
                                if (logMapDownMs > 0L && !logMapTriggered && (now - logMapDownMs) >= HOLD_DURATION_MS) {
                                    logMapTriggered = true;
                                    fireDashboardTrigger("Logcat MAP hold " + ((now - logMapDownMs) / 1000.0) + "s", now, false);
                                }
                                logMapDownMs = 0L;
                                logMapTriggered = false;
                            } else if (isLong) {
                                if (!logMapTriggered && (logMapDownMs == 0L || (now - logMapDownMs) >= 2400L)) {
                                    logMapTriggered = true;
                                    fireDashboardTrigger("Logcat MAP_LONG", now, false);
                                }
                            } else if (isDown) {
                                if (logMapDownMs == 0L) {
                                    logMapDownMs = now;
                                    logMapTriggered = false;
                                } else if (!logMapTriggered && (now - logMapDownMs) >= HOLD_DURATION_MS) {
                                    logMapTriggered = true;
                                    fireDashboardTrigger("Logcat MAP repeat " + ((now - logMapDownMs) / 1000.0) + "s", now, false);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (br != null) try { br.close(); } catch (Exception ignored) {}
                    if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
                }
                try {
                    Thread.sleep(5000L);
                } catch (Throwable ignored) {
                }
            }
        }

        private static void appendKeyLog(String msg) {
            FileOutputStream fos = null;
            try {
                File tmpDir = new File(TMP_DIR_PATH);
                if (!tmpDir.exists()) return;
                File logFile = new File(tmpDir, "key_events.log");
                if (logFile.isFile() && logFile.length() > 65536L) {
                    File bak = new File(tmpDir, "key_events.old.log");
                    if (bak.exists()) bak.delete();
                    logFile.renameTo(bak);
                }
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                byte[] lineBytes = (ts + " " + msg + "\n").getBytes(UTF8);
                fos = new FileOutputStream(logFile, true);
                fos.write(lineBytes);
            } catch (Throwable ignored) {
            } finally {
                if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static int runSelfTest() throws Exception {
        String compact = compactLogLine("Sat Oct 31 14:13:50 +09:00 2026 Service started (pid=3563), monitoring USB 0bda:b812");
        if (!compact.startsWith("14:13:50 Service started")) {
            throw new AssertionError("compactLogLine failed: " + compact);
        }
        String fb = formatBytes(1536L);
        if (!"1.5 KB".equals(fb)) {
            throw new AssertionError("formatBytes failed: " + fb);
        }
        byte[] sampleBin = "prefix\0/data/misc/wifi/all_sta.list\0suffix".getBytes(UTF8);
        String patchedStr = new String(patchHostapdBytes(sampleBin), UTF8);
        if (!patchedStr.contains("/data/local/tmp/usb_sta.list") || patchedStr.contains("/data/misc/wifi/all_sta.list")) {
            throw new AssertionError("patchHostapdBytes failed: " + patchedStr);
        }
        byte[] testPayload = "HKG_Gen5W_OTA_Test_Payload_1234567890".getBytes(UTF8);
        String b64 = encodeBase64(testPayload);
        byte[] decoded = decodeBase64(b64);
        if (!sha256Hex(testPayload).equals(sha256Hex(decoded))) {
            throw new AssertionError("Base64/SHA-256 self-test failed");
        }
        Map<String, String> jmap = parseJsonFlatMap("{\"version\": \"1.9.0\", \"sha256_jar\": \"abcd\"}");
        if (!"1.9.0".equals(jmap.get("version")) || !"abcd".equals(jmap.get("sha256_jar"))) {
            throw new AssertionError("parseJsonFlatMap failed");
        }
        // wpa_cli printf-escaped SSIDs -> real SSIDs
        String korSsid = decodeWpaSsid("\\xed\\x99\\x8d\\xea\\xb8\\xb8\\xeb\\x8f\\x99\\xec\\x9d\\x98 iPhone");
        if (!"홍길동의 iPhone".equals(korSsid)) {
            throw new AssertionError("decodeWpaSsid UTF-8 failed: " + korSsid);
        }
        if (!"a\\b\"c".equals(decodeWpaSsid("a\\\\b\\\"c"))) {
            throw new AssertionError("decodeWpaSsid quote/backslash failed");
        }
        if (!"bad\\nssid".equals(decodeWpaSsid("bad\\nssid")) || !"x\\x0ay".equals(decodeWpaSsid("x\\x0ay"))) {
            throw new AssertionError("decodeWpaSsid must not produce control characters");
        }
        if (!"Galaxy S23 1A2B".equals(decodeWpaSsid("Galaxy S23 1A2B"))) {
            throw new AssertionError("decodeWpaSsid plain passthrough failed");
        }
        if (isSafeConfigValue("a\nb") || !isSafeConfigValue("Galaxy $(x) `y`; z")) {
            throw new AssertionError("isSafeConfigValue failed");
        }
        // survey_info parsing: 8851bu layout (real rows from a Gen5W log, 2026-11-08)
        int[] hc51 = parseSurveyHeader("index bssid             band ch  RSSI SdBm Noise alpha2   age   flag                             ssid");
        SurveyRow r1 = parseSurveyRow("    1 00:e1:40:19:14:f7 2.4G   6  -86  -87     0     -- 0 42880 [WPA2][WEP][WPS][ESS][P]         janus_bb_gw200_1914F7", hc51[0], hc51[1]);
        if (r1 == null || !"janus_bb_gw200_1914F7".equals(r1.ssid) || r1.ch != 6 || r1.dbm != -87 || !r1.flags.contains("WPA2")) {
            throw new AssertionError("parseSurveyRow 8851bu failed: " + (r1 == null ? "null" : r1.ssid + "/" + r1.ch + "/" + r1.dbm));
        }
        SurveyRow r2 = parseSurveyRow("   12 b4:b6:86:b0:72:78 2.4G   5  -80  -80     0     -- 0 30290 [WPA2][WEP][WPS][ESS][P]         DIRECT-77-HP OfficeJet Pro 8710", hc51[0], hc51[1]);
        if (r2 == null || !"DIRECT-77-HP OfficeJet Pro 8710".equals(r2.ssid)) {
            throw new AssertionError("parseSurveyRow SSID with spaces failed");
        }
        SurveyRow r3 = parseSurveyRow("   21 ca:dd:6a:53:51:c7 2.4G  11  -85  -85     0     -- 0 29850 [WPA2][WEP][ESS][P]              [LG_CeilingCassette A/C]51c7", hc51[0], hc51[1]);
        if (r3 == null || !"[LG_CeilingCassette A/C]51c7".equals(r3.ssid) || !"[WPA2][WEP][ESS][P]".equals(r3.flags)) {
            throw new AssertionError("parseSurveyRow SSID starting with '[' failed");
        }
        SurveyRow r4 = parseSurveyRow("   16 2e:4e:e9:ae:6c:b5 2.4G  10  -72  -73     0     -- 0 29940 [WPA2][WEP][ESS][B]              ", hc51[0], hc51[1]);
        if (r4 == null || !r4.ssid.isEmpty()) {
            throw new AssertionError("parseSurveyRow hidden SSID failed");
        }
        // 88x2bu layout
        int[] hc2b = parseSurveyHeader("index bssid              ch  RSSI SdBm Noise   age   flag          ssid");
        SurveyRow r5 = parseSurveyRow("    1 66:7a:71:f2:09:db  149  -44  -45     0 29750 [WPA2][ESS]   Camellia 5G", hc2b[0], hc2b[1]);
        if (r5 == null || !"Camellia 5G".equals(r5.ssid) || r5.ch != 149 || r5.dbm != -45) {
            throw new AssertionError("parseSurveyRow 88x2bu failed");
        }

        // Self-test MapButtonLongPressMonitor (3.0-second MAP button hold detector)
        MapButtonLongPressMonitor.resetForSelfTest();
        List<String> mockKl = new ArrayList<String>();
        mockKl.add("key 214 HKMC_MAP");
        mockKl.add("key 215 HKMC_MAP_LONG");
        mockKl.add("key 216 HKMC_NAVI");
        mockKl.add("key 115 VOLUME_UP");
        mockKl.add("key 163 MEDIA_NEXT");
        MapButtonLongPressMonitor.parseKeyLayoutLines("daudio_wp_daudioplus_cvev_kr.kl", mockKl, true);

        // 1) Short press of MAP (250ms) -> must NOT trigger
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 1, 1000L, true);
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(1200L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 0, 1250L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 0) {
            throw new AssertionError("Short MAP press must not trigger dashboard");
        }

        // 2) Long press of non-MAP key (VOLUME_UP 115 or NAVI 216 held for 3500ms) -> must NOT trigger
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 115, 1, 2000L, true);
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(5500L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 115, 0, 5600L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 216, 1, 6000L, true);
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(9500L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 216, 0, 9600L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 0) {
            throw new AssertionError("Non-MAP key hold must not trigger dashboard");
        }

        // 3) Continuous 3.0-second hold of MAP (code 214 held for 3000ms) -> MUST trigger right at 3000ms
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 1, 10000L, true);
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(12900L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 0) {
            throw new AssertionError("MAP hold at 2.9s must not trigger yet");
        }
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(13000L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 1) {
            throw new AssertionError("MAP hold at 3.0s must trigger dashboard");
        }
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 0, 13500L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 1) {
            throw new AssertionError("MAP release after trigger must not double-trigger");
        }

        // 4) Short-to-long keycode handoff (214 held 1.5s -> 215 held 1.5s = 3.0s total) -> MUST trigger
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 1, 20000L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 214, 0, 21500L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 215, 1, 21550L, true);
        MapButtonLongPressMonitor.checkPeriodicHoldTimer(23000L, true);
        MapButtonLongPressMonitor.onKeyEvent("event1", true, 215, 0, 23100L, true);
        if (MapButtonLongPressMonitor.selfTestTriggerCount != 2) {
            throw new AssertionError("MAP short-to-long handoff at 3.0s must trigger dashboard");
        }

        // 5) RTL8831BU / RTL8851BU / RTL8812BU / 0bda:1a2b USB dongle classification tests
        if (!"RTL8831BU Wi-Fi 6".equals(classifyUsbWifiDongle("0bda", "b831", "0bda", "b812")) || !is8851buDongle("0bda", "b831")) {
            throw new AssertionError("RTL8831BU (0bda:b831) classification failed");
        }
        if (!"RTL8851BU Wi-Fi 6".equals(classifyUsbWifiDongle("0bda", "b851", "0bda", "b812")) || !is8851buDongle("0bda", "b851")) {
            throw new AssertionError("RTL8851BU (0bda:b851) classification failed");
        }
        if (!"TP-Link RTL8831BU/8851BU".equals(classifyUsbWifiDongle("3625", "010b", "0bda", "b812")) || !is8851buDongle("3625", "010b")) {
            throw new AssertionError("TP-Link RTL8851BU (3625:010b) classification failed");
        }
        if (!"Realtek CD-ROM 자동전환 중".equals(classifyUsbWifiDongle("0bda", "1a2b", "0bda", "b812")) || !is8851buDongle("0bda", "1a2b")) {
            throw new AssertionError("Realtek CD-ROM (0bda:1a2b) classification failed");
        }
        if (!"RTL8812BU".equals(classifyUsbWifiDongle("0bda", "b812", "0bda", "b812")) || is8851buDongle("0bda", "b812")) {
            throw new AssertionError("RTL8812BU (0bda:b812) classification failed");
        }

        System.out.println("USBWIFI STATUS UI SELF-TEST PASSED! (" + compact + ", mapHoldTests=OK, rtl8831buTests=OK)");
        return 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "daemon".equals(args[0])) {
            String installDir = args.length >= 2 ? args[1] : "/data/nemesis/apps/usbwifi.hotspot";
            boolean showNow = args.length >= 3 && "--show".equals(args[2]);
            runDaemon(installDir, showNow);
        } else if ("show-ui".equals(args[0])) {
            File tmpDir = new File(TMP_DIR_PATH);
            if (!tmpDir.exists()) tmpDir.mkdirs();
            writeBytesAtomic(new File(tmpDir, "show_ui"), "1".getBytes(UTF8));
        } else if ("patch-hostapd".equals(args[0]) && args.length >= 3) {
            patchHostapdBinary(new File(args[1]), new File(args[2]));
        } else if ("test-self".equals(args[0])) {
            System.exit(runSelfTest());
        } else {
            System.err.println("Usage: UsbWifiStatusMain [daemon|show-ui|patch-hostapd|test-self]");
            System.exit(2);
        }
    }
}

