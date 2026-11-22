package com.picapico.audioshare.musiche;

import android.util.Log;

import com.picapico.audioshare.NetworkUtils;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

/**
 * 多机互联的局域网自发现（UDP 广播 + 监听）。
 *
 * 协议：任何设备在空闲时每 10 秒向 58261~58270 广播 "picapico-audio-share@tcpPort@httpPort"，
 * 收到广播的一方用第 3 段（httpPort）去连 ws://ip:httpPort/sws 建立同步通道。
 *
 * 这里的容错要点（原实现是“异常即线程死亡，永久失去发现能力”）：
 * 1. receiveLoop 外层套循环：UDP 出错 / 端口被占 / WiFi 切换 都会自动重建 socket 继续监听；
 * 2. 只有 stop() 才真正退出循环，避免重复 start 出现多个监听线程；
 * 3. 本机 IP 快照在每个接收周期重新采样，防止 DHCP 换 IP 后把自己的广播当成别人 → 自己连自己。
 */
public class BroadcastReceiver {
    private static final String TAG = "AudioShareBCReceiver";
    private static final String HEAD = "picapico-audio-share";
    private static final int PORT_START = 58261;
    private static final int PORT_COUNT = 10;
    private static final long REBIND_DELAY_MS = 5000;   // 全部端口都绑不上（被占用）时的重试间隔
    private static final long RETRY_DELAY_MS = 2000;    // 接收异常后的重试间隔

    public interface OnRemoteServerReceivedListener {
        void OnRemoteServerReceived(String hostname, String port);
    }
    private OnRemoteServerReceivedListener remoteServerReceivedListener = null;

    public void setRemoteServerReceivedListener(OnRemoteServerReceivedListener listener){
        remoteServerReceivedListener = listener;
    }

    private volatile DatagramSocket socket = null;
    private volatile boolean running = false;
    private volatile boolean ignoreError = false;
    // 本机所有 IPv4 地址的快照（形如 "-192.168.1.5-"），用于过滤自己发出的广播
    private volatile String localAddresses = "";

    public void start(){
        if(running) return;
        running = true;
        ignoreError = false;
        new Thread(this::receiveLoop, "AudioShareUdpDiscovery").start();
    }

    public void stop(){
        running = false;
        ignoreError = true;
        closeSocket();
    }

    /** 监听主循环：绑定端口 → 收包 → 出错后重建，直到 stop() */
    private void receiveLoop(){
        while (running) {
            if(!openSocket()){
                Log.w(TAG, "cannot listen udp, retry after " + REBIND_DELAY_MS + "ms");
                sleep(REBIND_DELAY_MS);
                continue;
            }
            try {
                localAddresses = getIPV4();
                receive();
            } catch (Exception e) {
                if(!ignoreError) Log.e(TAG, "cannot receive udp: " + e.getMessage());
            } finally {
                closeSocket();
            }
            if(running) sleep(RETRY_DELAY_MS);
        }
    }

    /** 依次尝试绑定端口段（发送端会往 10 个端口都发，占一个就够） */
    private boolean openSocket(){
        for (int port = PORT_START; port < PORT_START + PORT_COUNT; port++) {
            try {
                DatagramSocket s = new DatagramSocket(port);
                s.setBroadcast(true);
                socket = s;
                return true;
            } catch (Exception ignore) {
            }
        }
        return false;
    }

    private void receive() throws Exception {
        byte[] buffer = new byte[128];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running && socket != null) {
            socket.receive(packet);
            if(remoteServerReceivedListener == null) continue;
            InetAddress packageAddress = packet.getAddress();
            if(packageAddress == null || packageAddress.getHostAddress() == null
                    || localAddresses.contains(packageAddress.getHostAddress())){
                continue;   // 自己发的广播，忽略
            }
            String[] messages = new String(packet.getData(), 0, packet.getLength()).split("@");
            if(messages.length < 3 || !messages[0].startsWith(HEAD)) continue;
            remoteServerReceivedListener.OnRemoteServerReceived(packet.getAddress().getHostAddress(), messages[2]);
        }
    }

    private void closeSocket(){
        DatagramSocket s = socket;
        socket = null;
        if(s != null){
            try {
                s.close();
            } catch (Exception ignore) { }
        }
    }

    private void sleep(long millis){
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }

    private String getIPV4(){
        StringBuilder addressesSB = new StringBuilder();
        List<InetAddress> allAddresses = NetworkUtils.getAllInetAddress();
        for (int i = 0; i < allAddresses.size();i++) {
            if(allAddresses.get(i) instanceof Inet4Address){
                addressesSB.append("-").append(allAddresses.get(i).getHostAddress()).append("-");
            }
        }
        return addressesSB.toString();
    }
}
