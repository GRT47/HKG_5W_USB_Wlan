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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HKG Standard Gen5W (Android 4.4.2 API 19) — USB Wi-Fi Hotspot Real-Time Status Dashboard Overlay.
 *
 * Launched via NEMESIS Dashboard ("Launch" -> bin/launch.sh) or background daemon.
 * Renders a live 2-column system overlay window on the head unit screen showing:
 *  - Platform / Board / Kernel info (tcc803x / tcc897x)
 *  - Background Daemon status (PID / running state)
 *  - USB Dongle (0bda:b812 Realtek RTL8812BU) connection, USB bus path & link speed
 *  - Kernel Module (88x2bu.ko / 88x2bu_tcc897x.ko) load state, memory size & insmod logs
 *  - USB Wi-Fi Network Interface (wlan1 / wlan0) UP/DOWN state, IPv4/prefix, MAC, RX/TX traffic
 *  - Built-in Wi-Fi (bcmdhd / wlan0) coexistence status
 *  - SoftAP (hostapd) & DHCP (dnsmasq) state, SSID, password, band/channel, connected clients
 *  - Live tail of daemon.log & hostapd.log
 *  - Interactive touch buttons: [Restart AP], [Band 2.4G <-> 5G], [Subnet .0.1 <-> .43.1], [Export Logs]
 */
public class UsbWifiStatusMain {

    public static final String UI_VERSION = "1.6.0";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String TMP_DIR_PATH = "/data/local/tmp/usbwifi_hotspot";
    private static final String WIFI_WORK_DIR_PATH = "/data/misc/wifi/usbwifi_hotspot";

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
        snap.apSsid = stripQuotes( getOrDefault(conf, "AP_SSID", "HKG_USB_Hotspot") );
        snap.apPass = stripQuotes( getOrDefault(conf, "AP_PASS", "12345678") );
        snap.apHwMode = stripQuotes( getOrDefault(conf, "AP_HW_MODE", "g") );
        snap.apChannel = stripQuotes( getOrDefault(conf, "AP_CHANNEL", "6") );
        snap.forceSubnet = stripQuotes( getOrDefault(conf, "FORCE_SUBNET", "AUTO") );
        snap.enableLteNat = stripQuotes( getOrDefault(conf, "ENABLE_LTE_NAT", "1") );

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
                if (v.equals(targetVid) && p.equals(targetPid)) {
                    snap.usbDonglePresent = true;
                    String label = prod.isEmpty() ? ("RTL88x2BU (" + v + ":" + p + ")") : prod;
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

        // 3. Kernel module (88x2bu) check in /proc/modules
        File modDir = new File(installDir, "modules");
        File platKo = new File(modDir, "88x2bu_" + snap.platform + ".ko");
        snap.driverModuleFile = platKo.isFile() ? platKo.getName() : "88x2bu.ko";

        List<String> modLines = readLinesQuick(new File("/proc/modules"), 200);
        for (String line : modLines) {
            if (line.startsWith("88x2bu ")) {
                snap.driverLoaded = true;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    long bytes = parseLongSafe(parts[1], 0L);
                    String mbStr = String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0));
                    snap.driverMemInfo = mbStr + ", ref=" + parts[2];
                }
            } else if (line.startsWith("bcmdhd ")) {
                snap.hasBuiltinBcmdhd = true;
            }
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

        // 7. CPU & RAM usage
        snap.cpuLoadPct = StatusDashboardOverlay.sampleCpuUsagePercent();
        snap.ramSummary = StatusDashboardOverlay.readRamUsageSummary();

        // 8. Recent logs from daemon.log & hostapd.log
        List<String> dLogs = readTailLines(new File(tmpDir, "daemon.log"), 4);
        for (String dl : dLogs) {
            snap.recentLogs.add("[데몬] " + compactLogLine(dl));
        }
        List<String> hLogs = readTailLines(new File(tmpDir, "hostapd.log"), 3);
        for (String hl : hLogs) {
            snap.recentLogs.add("[AP] " + compactLogLine(hl));
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
                        || combined.contains("88x2bu")) {
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
        private static volatile Object headerSysInfoTextView = null;
        private static volatile Object leftColTextView = null;
        private static volatile Object rightColTextView = null;
        private static volatile Object footerStatusTextView = null;
        private static volatile boolean isVisible = false;
        private static volatile long shownAtMs = 0L;
        private static volatile String actionBannerMsg = "실시간 1초 주기 자동 갱신 중 | 90초 후 자동 닫힘";
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

        private static Object[] buildDashboardViewTree() throws Exception {
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

            final int MATCH_PARENT = -1;
            final int WRAP_CONTENT = -2;

            // Root container
            Object root = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(root, 1); // VERTICAL
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(root, 26, 20, 26, 20);
            setRoundedBg(root, 0xF20F172A, 0xFF38BDF8, 3, 18f);
            disableViewEffects(root, true);

            // 1. Header Row
            Object headerRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(headerRow, 0); // HORIZONTAL
            llCls.getMethod("setGravity", int.class).invoke(headerRow, 16);    // CENTER_VERTICAL

            Object titleTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(titleTv, "📡 HKG 5W USB Wi-Fi 핫스팟 — 동작 현황 대시보드 (v" + UI_VERSION + ")", 20.0f, 0xFF38BDF8, true);
            Object titleLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, titleTv, titleLp);

            Object sysInfoTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(sysInfoTv, "  🖥 로딩 중...  ", 14.5f, 0xFFA7F3D0, true);
            tvSetGravity.invoke(sysInfoTv, 17);
            setRoundedBg(sysInfoTv, 0xFF1E293B, 0xFF475569, 1, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(sysInfoTv, 14, 8, 14, 8);
            Object sysInfoLp = llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT);
            setMargins(sysInfoLp, 8, 0, 12, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, sysInfoTv, sysInfoLp);

            Object closeBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(closeBtn, "  ✖ 닫기  ", 15.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(closeBtn, 17);
            setRoundedBg(closeBtn, 0xFFDC2626, 0xFFF87171, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(closeBtn, 20, 9, 20, 9);
            attachClickListener(closeBtn, new Runnable() {
                @Override
                public void run() {
                    hide();
                }
            });
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(headerRow, closeBtn, llLpCtor2.newInstance(WRAP_CONTENT, WRAP_CONTENT));
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, headerRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // Divider
            Object div1 = viewCls.getConstructor(ctxCls).newInstance(sysContext);
            viewCls.getMethod("setBackgroundColor", int.class).invoke(div1, 0xFF334155);
            Object divLp1 = llLpCtor2.newInstance(MATCH_PARENT, 2);
            setMargins(divLp1, 0, 10, 0, 12);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, div1, divLp1);

            // 2. Two-Column Body Row
            Object bodyRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(bodyRow, 0); // HORIZONTAL

            // Left Column Card
            Object leftCard = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(leftCard, 1);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(leftCard, 16, 12, 16, 12);
            setRoundedBg(leftCard, 0xFF1E293B, 0xFF475569, 1, 12f);

            Object leftHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftHdr, "[ 1. 하드웨어 · 커널 드라이버 · 네트워크 인터페이스 ]", 16.5f, 0xFF34D399, true);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(leftCard, leftHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object leftTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(leftTv, "", 14.5f, 0xFFE2E8F0, false);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(leftTv, 0, 8, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(leftCard, leftTv, llLpCtor3.newInstance(MATCH_PARENT, WRAP_CONTENT, 1.0f));

            Object leftCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.0f);
            setMargins(leftCardLp, 0, 0, 8, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(bodyRow, leftCard, leftCardLp);

            // Right Column Card
            Object rightCard = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(rightCard, 1);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(rightCard, 16, 12, 16, 12);
            setRoundedBg(rightCard, 0xFF1E293B, 0xFF475569, 1, 12f);

            Object rightHdr = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightHdr, "[ 2. SoftAP 핫스팟 · 접속 클라이언트 · 실시간 로그 ]", 16.5f, 0xFFFBBF24, true);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(rightCard, rightHdr, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            Object rightTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(rightTv, "", 14.5f, 0xFFE2E8F0, false);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(rightTv, 0, 8, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(rightCard, rightTv, llLpCtor3.newInstance(MATCH_PARENT, WRAP_CONTENT, 1.0f));

            Object rightCardLp = llLpCtor3.newInstance(0, MATCH_PARENT, 1.12f);
            setMargins(rightCardLp, 8, 0, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(bodyRow, rightCard, rightCardLp);

            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, bodyRow, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            // 3. Action Buttons Row
            Object btnRow = llCtor.newInstance(sysContext);
            llCls.getMethod("setOrientation", int.class).invoke(btnRow, 0); // HORIZONTAL
            llCls.getMethod("setGravity", int.class).invoke(btnRow, 17);    // CENTER
            Object btnRowLp = llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT);
            setMargins(btnRowLp, 0, 12, 0, 6);

            // Button 1: Force Restart Hotspot
            Object restartBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(restartBtn, "🔄 핫스팟 즉시 재시작", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(restartBtn, 17);
            setRoundedBg(restartBtn, 0xFF0284C7, 0xFF38BDF8, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(restartBtn, 10, 10, 10, 10);
            attachClickListener(restartBtn, new Runnable() {
                @Override
                public void run() {
                    triggerRestartHotspotFromUi();
                }
            });
            Object restartBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(restartBtnLp, 0, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, restartBtn, restartBtnLp);

            // Button 2: Toggle 2.4GHz <-> 5GHz
            Object bandBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(bandBtn, "📻 대역 변경 (2.4G ↔ 5G)", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(bandBtn, 17);
            setRoundedBg(bandBtn, 0xFF7C3AED, 0xFFA78BFA, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(bandBtn, 10, 10, 10, 10);
            attachClickListener(bandBtn, new Runnable() {
                @Override
                public void run() {
                    toggleBandFromUi();
                }
            });
            Object bandBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(bandBtnLp, 6, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, bandBtn, bandBtnLp);

            // Button 3: Toggle Subnet (AUTO / 192.168.0.1 / 192.168.43.1)
            Object subnetBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(subnetBtn, "🌐 IP 대역 (.0.1 ↔ .43.1)", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(subnetBtn, 17);
            setRoundedBg(subnetBtn, 0xFF2563EB, 0xFF60A5FA, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(subnetBtn, 10, 10, 10, 10);
            attachClickListener(subnetBtn, new Runnable() {
                @Override
                public void run() {
                    toggleSubnetFromUi();
                }
            });
            Object subnetBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(subnetBtnLp, 6, 0, 6, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, subnetBtn, subnetBtnLp);

            // Button 4: Export Diagnostic Logs to USB
            Object dumpBtn = tvCtor.newInstance(sysContext);
            setTextViewStyle(dumpBtn, "💾 USB로 진단 로그 저장", 14.5f, 0xFFFFFFFF, true);
            tvSetGravity.invoke(dumpBtn, 17);
            setRoundedBg(dumpBtn, 0xFF059669, 0xFF34D399, 2, 10f);
            viewCls.getMethod("setPadding", int.class, int.class, int.class, int.class).invoke(dumpBtn, 10, 10, 10, 10);
            attachClickListener(dumpBtn, new Runnable() {
                @Override
                public void run() {
                    triggerExportLogsFromUi();
                }
            });
            Object dumpBtnLp = llLpCtor3.newInstance(0, WRAP_CONTENT, 1.0f);
            setMargins(dumpBtnLp, 6, 0, 0, 0);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(btnRow, dumpBtn, dumpBtnLp);

            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, btnRow, btnRowLp);

            // 4. Footer Status Line
            Object footerTv = tvCtor.newInstance(sysContext);
            setTextViewStyle(footerTv, actionBannerMsg, 14.5f, 0xFF93C5FD, false);
            tvCls.getMethod("setGravity", int.class).invoke(footerTv, 17);
            vgCls.getMethod("addView", viewCls, vgLpCls).invoke(root, footerTv, llLpCtor2.newInstance(MATCH_PARENT, WRAP_CONTENT));

            return new Object[]{root, leftTv, rightTv, footerTv, sysInfoTv};
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
                if (isVisible && rootOverlayView != null) {
                    shownAtMs = System.currentTimeMillis();
                    refreshOnUiThread();
                    return;
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
                    Object[] tree = null;
                    try {
                        tree = buildDashboardViewTree();
                        Object candidateRoot = tree[0];
                        Object wmLp = wmLpCtor.newInstance(1220, -2, wtype, flags, -3);
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
                        leftColTextView = tree[1];
                        rightColTextView = tree[2];
                        footerStatusTextView = tree[3];
                        headerSysInfoTextView = tree[4];
                        isVisible = true;
                        shownAtMs = System.currentTimeMillis();
                        System.out.println("[USBWifiUI] StatusDashboardOverlay displayed (windowType=" + wtype + ")");
                        refreshOnUiThread();
                        break;
                    } catch (Throwable t) {
                        if (tree != null && tree[0] != null) {
                            try {
                                windowManager.getClass().getMethod("removeViewImmediate", viewCls).invoke(windowManager, tree[0]);
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
            headerSysInfoTextView = null;
            leftColTextView = null;
            rightColTextView = null;
            footerStatusTextView = null;
            isVisible = false;
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
            long elapsedSec = (now - shownAtMs) / 1000L;
            if (elapsedSec >= 90L) {
                hideOnUiThread();
                return;
            }

            try {
                SystemSnapshot snap = latestSnapshot;
                if (snap == null) {
                    return;
                }

                String daemonStr = snap.daemonAlive
                        ? ("🟢 정상 가동 중 (PID: " + snap.daemonPid + ")")
                        : "🔴 중지됨 (하단 [핫스팟 즉시 재시작] 터치)";

                String usbStr;
                if (snap.usbDonglePresent) {
                    usbStr = "🟢 연결됨 — " + snap.usbDongleDesc;
                } else if (!snap.otherUsbDevices.isEmpty()) {
                    usbStr = "🟠 미감지 (현재 연결된 USB: " + snap.otherUsbDevices + ")";
                } else {
                    usbStr = "🔴 미연결 (USB 포트에 무선랜카드를 꽂아주세요)";
                }

                String drvStr;
                if (snap.driverLoaded) {
                    drvStr = "🟢 로드됨 (" + snap.driverModuleFile + " | " + snap.driverMemInfo + ")";
                } else if (!snap.lastInsmodError.isEmpty()) {
                    drvStr = "🔴 로드 실패: " + snap.lastInsmodError;
                } else {
                    drvStr = snap.usbDonglePresent ? "⏳ 모듈 로드 준비 중 (" + snap.driverModuleFile + ")" : "⚪ 대기 중 (" + snap.driverModuleFile + ")";
                }

                String ifaceStr;
                String trafficStr;
                if (!snap.usbIface.isEmpty()) {
                    String st = snap.ifaceUp ? "UP" : "DOWN";
                    String ip = snap.ifaceIp.isEmpty() ? "(IP 미할당)" : snap.ifaceIp;
                    ifaceStr = (snap.ifaceUp ? "🟢 " : "🟠 ") + snap.usbIface + " [" + st + "] | IP: " + ip + " | MAC: " + snap.ifaceMac;
                    trafficStr = String.format(Locale.US, "RX %d 패킷 (%s)  /  TX %d 패킷 (%s)",
                            snap.rxPackets, formatBytes(snap.rxBytes), snap.txPackets, formatBytes(snap.txBytes));
                } else {
                    ifaceStr = "⚪ 미생성 (USB 무선랜카드 연결 시 자동 생성)";
                    trafficStr = "RX 0 패킷 (0 B)  /  TX 0 패킷 (0 B)";
                }

                String coexistStr;
                String usbIfLabel = snap.usbIface.isEmpty() ? "rtwlan0" : snap.usbIface;
                if (snap.hasBuiltinBcmdhd) {
                    String w0Info = snap.builtinWlan0Up
                            ? ("활성" + (snap.builtinWlan0Ip.isEmpty() ? "" : " " + snap.builtinWlan0Ip))
                            : "비활성/대기";
                    coexistStr = "내장 Wi-Fi(bcmdhd) 보유 차량 [순정 wlan0: " + w0Info + " / USB: " + usbIfLabel + " 완벽 분리]";
                } else {
                    coexistStr = "내장 Wi-Fi 없음 [USB 무선랜카드가 단독 " + usbIfLabel + " 사용]";
                }

                StringBuilder leftSb = new StringBuilder(640);
                leftSb.append("• 차량 플랫폼 : ").append(snap.platform).append(" (").append(snap.model).append(") | Linux ").append(snap.kernelRel).append("\n");
                leftSb.append("• 백그라운드 데몬 : ").append(daemonStr).append("\n");
                leftSb.append("• USB 무선랜카드 : ").append(usbStr).append("\n");
                leftSb.append("• 커널 드라이버 : ").append(drvStr).append("\n");
                leftSb.append("• 무선 인터페이스 : ").append(ifaceStr).append("\n");
                leftSb.append("• 실시간 트래픽 : ").append(trafficStr).append("\n");
                leftSb.append("• 내장 Wi-Fi 공존 : ").append(coexistStr);

                String hostapdStr = snap.hostapdAlive
                        ? ("🟢 신호 송출 중 (PID: " + snap.hostapdPid + ")")
                        : "🔴 중지됨";
                String dnsmasqStr;
                if (snap.dnsmasqAlive) {
                    String modeTag = snap.dnsmasqShared ? "시스템 공유 PID: " : "PID: ";
                    dnsmasqStr = "🟢 DHCP 할당 가동 중 (" + modeTag + snap.dnsmasqPid + ")";
                } else {
                    dnsmasqStr = "🔴 중지됨";
                }
                String bandDesc = "a".equalsIgnoreCase(snap.apHwMode)
                        ? ("5GHz (Ch " + snap.apChannel + ")")
                        : ("2.4GHz (Ch " + snap.apChannel + ")");

                StringBuilder rightSb = new StringBuilder(640);
                rightSb.append("• SoftAP (hostapd) : ").append(hostapdStr).append("\n");
                rightSb.append("• DHCP (dnsmasq) : ").append(dnsmasqStr).append("\n");
                rightSb.append("• 핫스팟 무선 설정 : SSID [ ").append(snap.apSsid).append(" ] | PW [ ").append(snap.apPass)
                        .append(" ] | ").append(bandDesc).append("\n");
                if (snap.connectedClients.isEmpty()) {
                    rightSb.append("• 접속 클라이언트 : 0대 (현재 연결된 기기 없음)\n");
                } else {
                    rightSb.append("• 접속 클라이언트 (").append(snap.connectedClients.size()).append("대) : ")
                            .append(snap.connectedClients.get(0)).append("\n");
                }
                rightSb.append("• 최근 동작 로그 (/data/local/tmp/usbwifi_hotspot) :\n");
                int maxLogs = Math.min(4, snap.recentLogs.size());
                int startIdx = Math.max(0, snap.recentLogs.size() - maxLogs);
                for (int i = startIdx; i < snap.recentLogs.size(); i++) {
                    rightSb.append("   ").append(snap.recentLogs.get(i));
                    if (i + 1 < snap.recentLogs.size()) rightSb.append("\n");
                }

                Class<?> tvCls = Class.forName("android.widget.TextView");
                Method setText = tvCls.getMethod("setText", CharSequence.class);
                if (headerSysInfoTextView != null) {
                    setText.invoke(headerSysInfoTextView, "  🖥 " + snap.platform + "  |  🌡 CPU: " + snap.cpuLoadPct + "%  |  " + snap.ramSummary + "  ");
                }
                setText.invoke(leftColTextView, leftSb.toString());
                setText.invoke(rightColTextView, rightSb.toString());
                long remainClose = Math.max(1L, 90L - elapsedSec);
                setText.invoke(footerStatusTextView, actionBannerMsg + "  (" + remainClose + "초 후 자동 닫힘)");
            } catch (Throwable ignored) {
            }
        }

        private static void triggerRestartHotspotFromUi() {
            actionBannerMsg = "⏳ USB Wi-Fi 핫스팟(hostapd / dnsmasq) 재시작 요청 중...";
            postToUi(REFRESH_TASK);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File tmpDir = new File(TMP_DIR_PATH);
                        if (!tmpDir.exists()) tmpDir.mkdirs();
                        writeBytesAtomic(new File(tmpDir, "force_restart"), "1\n".getBytes(UTF8));
                        // Also ensure daemon is running
                        String dpid = readFirstLineQuick(new File(tmpDir, "daemon.pid"));
                        if (!isProcessAlive(dpid)) {
                            File daemonSh = new File(installDir, "svc/usbwifi-hotspot-daemon.sh");
                            if (daemonSh.isFile()) {
                                new ProcessBuilder("/system/bin/sh", daemonSh.getAbsolutePath()).start();
                            }
                        }
                        Thread.sleep(1500L);
                        actionBannerMsg = "✅ 핫스팟 재시작 명령 전달 완료! (잠시 후 상태가 갱신됩니다)";
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 재시작 요청 오류: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "RestartHotspotTrigger").start();
        }

        private static void toggleBandFromUi() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File confFile = new File(installDir, "config/hotspot.conf");
                        Map<String, String> conf = parseKeyValueFile(confFile);
                        String curMode = stripQuotes(getOrDefault(conf, "AP_HW_MODE", "g"));
                        String nextMode = "a".equalsIgnoreCase(curMode) ? "g" : "a";
                        String nextChan = "a".equalsIgnoreCase(nextMode) ? "149" : "6";
                        updateConfigKeys(confFile, new String[][]{
                                {"AP_HW_MODE", nextMode},
                                {"AP_CHANNEL", nextChan}
                        });
                        writeBytesAtomic(new File(TMP_DIR_PATH, "force_restart"), "1\n".getBytes(UTF8));
                        String label = "a".equals(nextMode) ? "5GHz (Ch 149)" : "2.4GHz (Ch 6)";
                        actionBannerMsg = "✅ 무선 주파수를 [" + label + "]로 변경하고 AP를 재시작합니다.";
                    } catch (Throwable t) {
                        actionBannerMsg = "⚠️ 대역 변경 실패: " + t.getMessage();
                    }
                    postToUi(REFRESH_TASK);
                }
            }, "ToggleBandThread").start();
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
                + "  cp \"$TMP_DIR/\"*.conf \"$ULOG/\" 2>/dev/null; "
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
        if (target.exists() && !tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        } else {
            tmp.renameTo(target);
        }
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
        System.out.println("USBWIFI STATUS UI SELF-TEST PASSED! (" + compact + ")");
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

