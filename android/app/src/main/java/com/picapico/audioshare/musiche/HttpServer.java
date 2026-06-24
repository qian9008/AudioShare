package com.picapico.audioshare.musiche;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.AsyncHttpResponse;
import com.koushikdutta.async.http.Headers;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;
import com.phicomm.speaker.player.light.PlayerVisualizer;
import com.picapico.audioshare.BuildConfig;
import com.picapico.audioshare.musiche.player.AudioPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.security.cert.X509Certificate;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.io.OutputStream;

public class HttpServer implements AudioPlayer.OnChangedListener {
    private static final String TAG = "AudioShareHttpServer";
    private String mVersionName = "";
    private String mDeviceName = "";
    int mServerPort;
    AsyncHttpServer mServer;
    AudioPlayer mAudioPlayer;
    List<WebSocket> mWebClients = new ArrayList<>();
    final Object mWebClientsLock = new Object();
    Map<String, RemoteClient> mRemoteClients = new HashMap<>();
    BroadcastReceiver mBroadcastReceiver;
    SharedPreferences mPreferences;
    AssetManager mAssetManager;
    Context mContext;
    public HttpServer(Context context, int port) {
        mServerPort = port;
        mContext = context;
        mAudioPlayer = new AudioPlayer(context);
        initDeviceName();
    }

    //region Initialization
    public void setAudioManager(AudioManager audioManager){
        mAudioPlayer.setAudioManager(audioManager);
        mAudioPlayer.setOnLoadSuccessListener(this);
    }

    private void setChannel(@AudioPlayer.ChannelType int channel){
        mAudioPlayer.setChannel(channel);
        mPreferences.edit().putInt("channel", channel).apply();
    }

    private void initBroadcastReceiver(){
        mBroadcastReceiver = new BroadcastReceiver();
        mBroadcastReceiver.setRemoteServerReceivedListener(this::onRemoteServerReceived);
        mBroadcastReceiver.start();
    }

    private void initDeviceName(){
        String marketingName = getMarketingName();
        if(marketingName == null || marketingName.isEmpty()){
            String manufacturer = Build.MANUFACTURER;
            String model = Build.MODEL;
            if(manufacturer == null || manufacturer.isEmpty() ||
                    "unknown".equalsIgnoreCase(manufacturer)){
                marketingName = model;
            }else {
                marketingName = manufacturer + " " + model;
            }
        }
        mDeviceName = marketingName;
    }
    //endregion

    //region Public Methods
    public HttpServer start(){
        stop();
        try {
            mServer = new AsyncHttpServer(){
                @Override
                public boolean onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                    return onClientRequest(request, response);
                }
            };
            mServer.listen(mServerPort);
            initHandler();
            Log.i(TAG, "start http server on: " + mServer);
        }catch (Exception e){
            Log.e(TAG, "start http server error: ", e);
        }
        initBroadcastReceiver();
        return this;
    }
    public void stop(){
        if(mServer == null) return;
        try {
            mServer.stop();
            mAudioPlayer.pause();
        }catch (Exception e){
            Log.e(TAG, "stop server error");
        }
        if(mBroadcastReceiver != null) {
            mBroadcastReceiver.stop();
            mBroadcastReceiver = null;
        }
        mServer = null;
    }
    public static String getMarketingName() {
        try {
            @SuppressLint("PrivateApi") Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Method getMethod = systemPropertiesClass.getMethod("get", String.class);
            return (String) getMethod.invoke(null, "ro.config.marketing_name");
        } catch (Exception ignore) { }
        return null;
    }

    public void setVersionName(String mVersionName){
        this.mVersionName = mVersionName;
    }

    public void setSharedPreferences(SharedPreferences preferences){
        this.mPreferences = preferences;
        mAudioPlayer.setChannel(mPreferences.getInt("channel", AudioPlayer.ChannelTypeStereo));
    }

    public void setAssetManager(AssetManager assetManager){
        this.mAssetManager = assetManager;
    }
    //endregion

    //region RemoteServerMessage
    private boolean isPositionSynchronized = false;
    private boolean isPositionSynchronizing = false;
    private void onRemoteServerMessage(String message, String address){
        if (BuildConfig.DEBUG) Log.d(TAG, "receive remote server message: " + message);
        RemoteMessage msg = RemoteMessage.of(message);
        if(msg == null) return;
        RemoteClient client = mRemoteClients.get(address);
        switch (msg.getType()){
            case RemoteMessage.MessageTypeGetPosition:
                mAudioPlayer.getPosition();
                break;
            case RemoteMessage.MessageTypePause:
                mAudioPlayer.pauseByRemote();
                break;
            case RemoteMessage.MessageTypeInfo:
                if(client != null){
                    client.setName(msg.getName());
                    client.setPort(msg.getPort());
                }
                break;
            case RemoteMessage.MessageTypeChannel:
                setChannel(msg.getChannel());
                break;
            case RemoteMessage.MessageTypeVolume:
                mAudioPlayer.setVolume(msg.getVolume());
                break;
            case RemoteMessage.MessageTypePlay:
                isPositionSynchronized = false;
                PlayerVisualizer.updateTimeMillis(-15);
                mAudioPlayer.setVolume(msg.getVolume());
                if(client != null && client.getChannel() == AudioPlayer.ChannelTypeNone){
                    client.setChannel(AudioPlayer.ChannelTypeStereo);
                }
                mAudioPlayer.play(msg.getUrl(), msg.getMusic());
                break;
            case RemoteMessage.MessageTypeSetPosition:
                isPositionSynchronized = true;
                int diff = (int) (System.currentTimeMillis() - getPositionTime) / 2;
                mAudioPlayer.setPosition(msg.getPosition() + diff);
                isPositionSynchronizing = false;
                break;
        }
    }

    private void onRemoteServerReceived(String hostname, String port){
        if(mRemoteClients.containsKey(hostname)) return;
        String url = String.format("http://%s:%s/sws", hostname, port);
        AsyncHttpClient.getDefaultInstance().websocket(url, (String) null, (e, webSocket) -> {
            if (e != null) {
                Log.e(TAG, "connect ws server error", e);
                return;
            }
            this.onRemoteServerSocket(webSocket);
        });
    }

    private void onRemoteServerSocket(WebSocket webSocket){
        try{
            AsyncNetworkSocket socket = (AsyncNetworkSocket) webSocket.getSocket();
            String address = socket.getRemoteAddress().getHostString();
            if(mRemoteClients.containsKey(address)) {
                webSocket.close();
                return;
            }
            mRemoteClients.put(address, RemoteClient.of(webSocket, address).setChannel(
                    mPreferences.getInt("channel-"+address, AudioPlayer.ChannelTypeStereo)));
//            mBroadcastReceiver.stop();
            webSocket.setClosedCallback(e -> mRemoteClients.remove(address));
            webSocket.setStringCallback(s -> onRemoteServerMessage(s, address));
            webSocket.send(RemoteMessage
                    .of(RemoteMessage.MessageTypeInfo)
                    .setName(mDeviceName)
                    .setPort(mServerPort)
                    .toJson());
            Log.d(TAG, "remote server connected: " + address);
        }catch (Exception ignore){}
    }
    //endregion

    //region AudioPlayer listener
    @Override
    public void onPositionChanged() {
        sendWSMessage(mAudioPlayer.getStatus().toString());
    }

    @Override
    public void onPositionChanged(long position) {
        sendServerWSMessage(RemoteMessage.of(RemoteMessage.MessageTypeSetPosition)
                .setPosition((int) position));
    }

    @Override
    public void onPaused() {
        sendServerWSMessage(RemoteMessage.of(RemoteMessage.MessageTypePause));
    }

    @Override
    public void onVolumeChanged(int volume) {
        sendServerWSMessage(RemoteMessage.of(RemoteMessage.MessageTypeVolume)
                .setVolume(volume));
    }

    private long getPositionTime = 0;
    @Override
    public void onPlaying(boolean remote, String url, MusicItem music, int volume) {
        if(isPositionSynchronized && url != null && remote) return;
        RemoteMessage message;
        if(remote){
            if(isPositionSynchronizing) return;
            isPositionSynchronizing = true;
            getPositionTime = System.currentTimeMillis();
            message = RemoteMessage.of(RemoteMessage.MessageTypeGetPosition);
        }else {
            message = RemoteMessage.of(RemoteMessage.MessageTypePlay)
                    .setUrl(url)
                    .setMusic(music)
                    .setVolume(volume);
            PlayerVisualizer.updateTimeMillis();
        }
        sendServerWSMessage(message);
    }

    public AudioPlayer getAudioPlayer() {
        return mAudioPlayer;
    }
    //endregion

    //region Handler
    private void initHandler(){
        mServer.websocket("/ws", websocketHandler);
        mServer.websocket("/sws", serverWebsocketHandler);
        mServer.get("/version", getVersion);
        mServer.post("/version", getVersion);
        mServer.get("/config", config);
        mServer.post("/config", config);
        mServer.get("/storages", getStorages);
        mServer.get("/storage", getStorage);
        mServer.addAction("DELETE", "/storage", deleteStorage);
        mServer.post("/storage", setStorage);
        mServer.post("/title", empty);
        mServer.post("/media", empty);
        mServer.post("/fadein", empty);
        mServer.post("/delayExit", delayExit);
        mServer.post("/gpu", empty);
        mServer.post("/play", play);
        mServer.post("/updatelist", updateList);
        mServer.post("/pause", pause);
        mServer.post("/progress", progress);
        mServer.post("/volume", volume);
        mServer.post("/status", status);
        mServer.post("/window", empty);
        mServer.post("/maximize", empty);
        mServer.post("/minimize", empty);
        mServer.post("/loop", loop);
        mServer.post("/quality", quality);
        mServer.post("/exit", pause);
        mServer.post("/hide", empty);
        mServer.post("/minimize", empty);
        mServer.post("/fonts", empty);
        mServer.post("/image", image);
        mServer.post("/theme", empty);
        mServer.post("/lyric", empty);
        mServer.post("/lyricline", empty);
        mServer.post("/hotkey", empty);
        mServer.post("/reboot", reboot);
        mServer.post("/file/select", selectFile);
        mServer.post("/file/exists", existsFile);
        mServer.post("/file/list/audio", getAllAudio);
        mServer.post("/file/directory/music", getMusicDirectory);
        mServer.post("/proxy", proxyPost);
        mServer.get("/proxy", proxyGet);
        mServer.post("/proxy/test", proxyTest);
        mServer.get("/proxy/test", proxyTest);
        mServer.get("/remote/clients", getRemoteClients);
        mServer.post("/remote/client", updateRemoteClient);
        mServer.get(".*", getStatic);
    }
    private boolean onClientRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        setCores(response);
        if(request.getMethod().equalsIgnoreCase("OPTIONS")){
            response.end();
            return true;
        }
        Log.d(TAG, "receive request [" + request.getMethod() + "] " + request.getPath());
        return false;
    }
    private final HttpServerRequestCallback empty = (request, response) -> response.send("");
    private final HttpServerRequestCallback getVersion = (request, response) -> response.send("v"+mVersionName);
    private final HttpServerRequestCallback getStatic = (request, response) -> {
        if(mAssetManager == null){
            response.code(404);
            response.end();
            return;
        }
        InputStream inputStream = null;
        try {
            mAssetManager.list("index.html");
            String urlPath = request.getPath().substring(1);
            inputStream = mAssetManager.open(urlPath);
        } catch (Exception ignore) {
        }
        if(inputStream == null){
            try {
                inputStream = mAssetManager.open("index.html");
            } catch (Exception ignore) {
            }
        }
        if(inputStream != null){
            try {
                byte[] buffer = new byte[inputStream.available()];
                int ignore = inputStream.read(buffer);
                response.send(getMimeType(request.getPath()), buffer);
                return;
            } catch (Exception ignore) {
            }
        }
        response.code(404);
        response.end();
    };
    public String getMimeType(String filePath)
    {
        String extension = "";
        if(filePath != null){
            String[] paths = filePath.split("\\.");
            if(paths.length > 0) extension = paths[paths.length - 1];
        }
        switch (extension)
        {
            case "html":
                return "text/html";
            case "js":
                return "application/javascript; charset=utf-8";
            case "css":
                return "text/css; charset=utf-8";
            case "woff2":
                return "font/woff2";
            case "woff":
                return "font/woff";
            case "png":
                return "image/png";
            case "jpg":
                return "image/jpg";
            case "svg":
                return "image/svg+xml";
            case "webp":
                return "image/webp";
            case "webm":
                return "video/webm";
        }
        return "text/html";
    }
    private final HttpServerRequestCallback config = (request, response) -> {
        Map<String, Boolean> data = new HashMap<>();
        data.put("remote", true);
        data.put("storage", true);
        data.put("file", false);
        data.put("list", true);
        data.put("client", true);
        data.put("lyric", true);
        data.put("shortcut", false);
        data.put("gpu", false);
        response.send(new JSONObject(data));
    };
    private final HttpServerRequestCallback getStorages = (request, response) -> {
        if(mPreferences == null){
            response.end();
            return;
        }
        Map<String, ?> values = mPreferences.getAll();
        Map<String, String> newValues = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey().startsWith("musiche-") && entry.getValue() instanceof String) {
                newValues.put(entry.getKey(), (String) entry.getValue());
            }
        }
        MusicItem music = mAudioPlayer.getCurrentMusic();
        if(music != null){
            Map<String, Object> musicMap = music.toMap();
            musicMap.remove("cookie");
            musicMap.remove("musicU");
            musicMap.remove("uid");
            musicMap.remove("csrf");
            newValues.put("musiche-current-music", new JSONObject(musicMap).toString());
        }
        response.send(new JSONObject(newValues));
    };
    private final HttpServerRequestCallback deleteStorage = (request, response) -> {
        String key = request.getQuery().getString("key");
        if(mPreferences != null && key != null && !key.isEmpty()){
            mPreferences.edit().remove(key).apply();
            response.send("application/json", "{\"data\":true}");
        }else {
            response.end();
        }
    };
    private final HttpServerRequestCallback getStorage = (request, response) -> {
        String key = request.getQuery().getString("key");
        if(mPreferences != null && key != null && !key.isEmpty()){
            String value = mPreferences.getString(key, "");
            response.send(value);
        }else {
            response.end();
        }
    };
    private final HttpServerRequestCallback setStorage = (request, response) -> {
        String key = request.getQuery().getString("key");
        String value = request.getBody().get().toString();
        if(mPreferences != null && key != null && !key.isEmpty()){
            mPreferences.edit().putString(key, value).apply();
        }
        response.end();
    };
    private Timer delayPauseTimer = null;
    private final HttpServerRequestCallback delayExit = (request, response) -> {
        String delayMinuteStr = String.valueOf(request.getBody().get());
        try{
            long delayMinute = Integer.parseInt(delayMinuteStr);
            if(delayPauseTimer != null){
                delayPauseTimer.cancel();
                delayPauseTimer = null;
            }
            if(delayMinute > 0){
                delayPauseTimer = new Timer();
                delayPauseTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        mAudioPlayer.pause();
                    }
                }, delayMinute * 60 *1000);
            }
        }catch (Exception ignore){
            delayPauseTimer = null;
        }
        response.send("");
    };
    private final HttpServerRequestCallback play = (request, response) -> {
        Object body = request.getBody().get();
        if(body == null){
            mAudioPlayer.play();
            response.send(mAudioPlayer.getStatus());
            return;
        }
        if(body instanceof JSONObject){
            mAudioPlayer.play(MusicPlayRequest.of((JSONObject) body));
        }else{
            String url = body.toString();
            if(url.startsWith("http")){
                mAudioPlayer.play(url);
            }else {
                File file = new File(url);
                if (file.exists()){
                    mAudioPlayer.play(url);
                }
            }
        }
        response.send(mAudioPlayer.getStatus());
    };
    private final HttpServerRequestCallback updateList = (request, response) -> {
        Object body = request.getBody().get();
        if(body instanceof JSONObject){
            mAudioPlayer.setMusicPlayRequest(MusicPlayRequest.of((JSONObject) body));
        }
        response.send("application/json", "{\"data\":true}");
    };
    private final HttpServerRequestCallback pause = (request, response) -> {
        mAudioPlayer.pause();
        response.send(mAudioPlayer.getStatus());
    };
    private final HttpServerRequestCallback progress = (request, response) -> {
        int progress = Integer.parseInt(request.getBody().get().toString());
        mAudioPlayer.setProgress(progress);
        response.send(mAudioPlayer.getStatus());
    };
    private final HttpServerRequestCallback volume = (request, response) -> {
        int volume = Integer.parseInt(request.getBody().get().toString());
        mAudioPlayer.setVolume(volume);
        response.send(mAudioPlayer.getStatus());
    };
    private final HttpServerRequestCallback status = (request, response) -> response.send(mAudioPlayer.getStatus());
    private final HttpServerRequestCallback loop = (request, response) -> {
        String loopType = request.getBody().get().toString().toLowerCase();
        switch (loopType)
        {
            case "single": mAudioPlayer.setLoopType(AudioPlayer.LoopType.Single); break;
            case "random": mAudioPlayer.setLoopType(AudioPlayer.LoopType.Random); break;
            case "order": mAudioPlayer.setLoopType(AudioPlayer.LoopType.Order); break;
            default: mAudioPlayer.setLoopType(AudioPlayer.LoopType.Loop); break;
        }
        response.send("application/json", "{\"data\":true}");
    };
    private final HttpServerRequestCallback quality = (request, response) -> {
        String qualityType = request.getBody().get().toString().toLowerCase();
        switch (qualityType)
        {
            case "sq": mAudioPlayer.setQuality(MusicItem.Quality.SQ); break;
            case "hq": mAudioPlayer.setQuality(MusicItem.Quality.HQ); break;
            case "zq": mAudioPlayer.setQuality(MusicItem.Quality.ZQ); break;
            default: mAudioPlayer.setQuality(MusicItem.Quality.PQ); break;
        }
        response.send("application/json", "{\"data\":true}");
    };
    private final HttpServerRequestCallback image = (request, response) -> response.send("");
    private final HttpServerRequestCallback reboot = (request, response) -> {
        Intent intent = mContext.getPackageManager()
                .getLaunchIntentForPackage(mContext.getPackageName());
        if(intent != null){
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(intent);
            response.send("application/json", "{\"data\":true}");
        }else {
            response.send("application/json", "{\"data\":false}");
        }
    };
    private final HttpServerRequestCallback getRemoteClients = (request, response) -> {
        List<Map<String, Object>> clients = new ArrayList<>();
        AsyncNetworkSocket socket = (AsyncNetworkSocket) request.getSocket();
        String localAddress = socket.getLocalAddress().getHostAddress();
        clients.add(RemoteClient.of(mDeviceName, localAddress, mServerPort, mAudioPlayer.getChannel()).toMap());
        for (String key: mRemoteClients.keySet()) {
            RemoteClient client = mRemoteClients.get(key);
            if(client != null) clients.add(client.toMap());
        }
        response.send(new JSONArray(clients));
    };
    private final HttpServerRequestCallback updateRemoteClient = (request, response) -> {
        try {
            JSONObject obj = (JSONObject) request.getBody().get();
            RemoteClient client = RemoteClient.of(obj);
            AsyncNetworkSocket socket = (AsyncNetworkSocket) request.getSocket();
            String localAddress = socket.getLocalAddress().getHostAddress();
            if(localAddress != null && localAddress.equals(client.getAddress())){
                setChannel(client.getChannel());
                response.send("application/json", "{\"data\":false}");
                return;
            }
            if(!mRemoteClients.containsKey(client.getAddress())) {
                response.send("application/json", "{\"data\":false}");
                return;
            }
            RemoteClient localClient = Objects.requireNonNull(mRemoteClients.get(client.getAddress()));
            localClient.setChannel(client.getChannel());
            if(client.getChannel() < 0) {
                localClient.send(RemoteMessage.of(RemoteMessage.MessageTypePause));
            }else {
                localClient.send(RemoteMessage.of(RemoteMessage.MessageTypeChannel)
                        .setChannel(client.getChannel()));
                if(mAudioPlayer.isPlaying()){
                    localClient.send(RemoteMessage.of(RemoteMessage.MessageTypePlay)
                            .setUrl(mAudioPlayer.getUrl())
                            .setMusic(mAudioPlayer.getCurrentMusic())
                            .setVolume(mAudioPlayer.getVolume()));
                    PlayerVisualizer.updateTimeMillis();
                }
            }
            mPreferences.edit().putInt("channel-"+localClient.getAddress(), localClient.getChannel()).apply();
            response.send("application/json", "{\"data\":true}");
        } catch (Exception ignore) {
            response.send("application/json", "{\"data\":false}");
        }
        response.end();
    };
    //selectFile getAllAudio getMusicDirectory

    private final HttpServerRequestCallback selectFile = (request, response) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("data", new String[]{});
        response.send(new JSONObject(result));
    };
    private final HttpServerRequestCallback existsFile = (request, response) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("data", false);
        response.send(new JSONObject(result));
    };
    private final HttpServerRequestCallback getAllAudio = (request, response) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("data", "");
        response.send(new JSONObject(result));
    };
    private final HttpServerRequestCallback getMusicDirectory = (request, response) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("data", "");
        response.send(new JSONObject(result));
    };

    //region proxy
    private final HttpServerRequestCallback proxyGet = (request, response) -> {
        String url = request.getQuery().getString("url");
        if(url == null || url.isEmpty()) {
            response.end();
            return;
        }
        ProxyRequestData proxyRequestData = new ProxyRequestData();
        proxyRequestData.setUrl(url);
        proxyRequestData.setMethod("GET");

        String httpProxy = mPreferences != null ? mPreferences.getString("musiche-http-proxy", "") : "";
        httpProxy = httpProxy.trim();
        if (httpProxy.startsWith("\"") && httpProxy.endsWith("\"")) {
            httpProxy = httpProxy.substring(1, httpProxy.length() - 1);
        }
        proxyRequestData.setHttpProxy(httpProxy.trim());

        new Thread(() -> {
            executeProxy(response, proxyRequestData);
        }).start();
    };

    private final HttpServerRequestCallback proxyPost = (request, response) -> {
        Object body = request.getBody().get();
        ProxyRequestData proxyRequestData = ProxyRequestData.of(body);
        String url = proxyRequestData.getUrl();
        if(url == null || url.isEmpty()) {
            response.send("");
            return;
        }

        if (proxyRequestData.getHttpProxy() == null || proxyRequestData.getHttpProxy().isEmpty()) {
            String httpProxy = mPreferences != null ? mPreferences.getString("musiche-http-proxy", "") : "";
            httpProxy = httpProxy.trim();
            if (httpProxy.startsWith("\"") && httpProxy.endsWith("\"")) {
                httpProxy = httpProxy.substring(1, httpProxy.length() - 1);
            }
            proxyRequestData.setHttpProxy(httpProxy.trim());
        }

        new Thread(() -> {
            executeProxy(response, proxyRequestData);
        }).start();
    };

    private void executeProxy(AsyncHttpServerResponse response, ProxyRequestData proxyRequestData) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            String urlStr = proxyRequestData.getUrl();
            URL url = new URL(urlStr);
            
            String httpProxy = proxyRequestData.getHttpProxy();
            if (httpProxy != null && !httpProxy.isEmpty()) {
                String proxyHost = "";
                int proxyPort = 80;
                String temp = httpProxy;
                if (temp.startsWith("http://")) {
                    temp = temp.substring(7);
                } else if (temp.startsWith("https://")) {
                    temp = temp.substring(8);
                }
                String[] parts = temp.split(":");
                proxyHost = parts[0];
                if (parts.length > 1) {
                    proxyPort = Integer.parseInt(parts[1]);
                }
                Proxy javaProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                conn = (HttpURLConnection) url.openConnection(javaProxy);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }

            conn.setRequestMethod(proxyRequestData.getMethod());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(proxyRequestData.isAllowAutoRedirect());

            if (conn instanceof HttpsURLConnection) {
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            }

            boolean userAgentSet = false;
            if (proxyRequestData.getHeaders() != null) {
                for (Map.Entry<String, String> entry : proxyRequestData.getHeaders().entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (key.equalsIgnoreCase("user-agent")) userAgentSet = true;
                    conn.setRequestProperty(key, val);
                }
            }
            if (!userAgentSet) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36");
            }

            if (proxyRequestData.hasBody()) {
                conn.setDoOutput(true);
                byte[] postData = proxyRequestData.getData().getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(postData);
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();

            if (!proxyRequestData.isAllowAutoRedirect() && code > 300 && code < 310) {
                response.code(200);
                response.setContentType("application/json");
                JSONObject headerJson = new JSONObject();
                Map<String, List<String>> resHeaders = conn.getHeaderFields();
                for (Map.Entry<String, List<String>> entry : resHeaders.entrySet()) {
                    if (entry.getKey() != null) {
                        headerJson.put(entry.getKey(), String.join(",", entry.getValue()));
                        if (entry.getKey().equalsIgnoreCase("set-cookie")) {
                            headerJson.put("Set-Cookie-Renamed", String.join(",", entry.getValue()));
                        }
                    }
                }
                response.send(headerJson);
                conn.disconnect();
                return;
            }

            response.code(code);

            Map<String, List<String>> resHeaders = conn.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : resHeaders.entrySet()) {
                String key = entry.getKey();
                if (key != null) {
                    String lowerKey = key.toLowerCase().replaceAll("-", "");
                    switch (lowerKey) {
                        case "cookies":
                        case "connection":
                        case "contentlength":
                        case "contentencoding":
                        case "transferencoding":
                        case "accesscontrolalloworigin":
                        case "accesscontrolallowheaders":
                        case "accesscontrolallowmethods":
                        case "accesscontrolexposeheaders":
                        case "accesscontrolallowcredentials":
                            break;
                        default:
                            response.getHeaders().set(key, String.join(",", entry.getValue()));
                            break;
                    }
                    if (key.equalsIgnoreCase("set-cookie")) {
                        response.getHeaders().set("Set-Cookie-Renamed", String.join(",", entry.getValue()));
                    }
                }
            }

            if (code >= 400) {
                is = conn.getErrorStream();
            } else {
                is = conn.getInputStream();
            }

            if (is != null) {
                final InputStream finalIs = is;
                final HttpURLConnection finalConn = conn;
                com.koushikdutta.async.Util.pump(is, response, new com.koushikdutta.async.callback.CompletedCallback() {
                    @Override
                    public void onCompleted(Exception ex) {
                        try {
                            finalIs.close();
                            finalConn.disconnect();
                        } catch (Exception ignored) {}
                        response.end();
                    }
                });
            } else {
                conn.disconnect();
                response.end();
            }

        } catch (Exception e) {
            Log.e(TAG, "Execute proxy request error", e);
            try {
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
            response.end();
        }
    }

    private final HttpServerRequestCallback proxyTest = (request, response) -> {
        new Thread(() -> {
            try {
                String httpProxy = mPreferences != null ? mPreferences.getString("musiche-http-proxy", "") : "";
                httpProxy = httpProxy.trim();
                if (httpProxy.startsWith("\"") && httpProxy.endsWith("\"")) {
                    httpProxy = httpProxy.substring(1, httpProxy.length() - 1);
                }
                httpProxy = httpProxy.trim();

                if (httpProxy.isEmpty()) {
                    JSONObject result = new JSONObject();
                    result.put("success", false);
                    result.put("message", "未在手机存储中检测到代理配置，请先保存代理设置");
                    result.put("proxy", "");
                    response.send(result);
                    return;
                }

                String proxyHost = "";
                int proxyPort = 80;
                String temp = httpProxy;
                if (temp.startsWith("http://")) {
                    temp = temp.substring(7);
                } else if (temp.startsWith("https://")) {
                    temp = temp.substring(8);
                }
                String[] parts = temp.split(":");
                proxyHost = parts[0];
                if (parts.length > 1) {
                    proxyPort = Integer.parseInt(parts[1]);
                }

                Proxy javaProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

                final boolean[] isUnblockNetease = {false};
                TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            if (certs != null && certs.length > 0) {
                                for (X509Certificate cert : certs) {
                                    String issuer = cert.getIssuerDN().getName().toLowerCase();
                                    String subject = cert.getSubjectDN().getName().toLowerCase();
                                    if (issuer.contains("unblock") || subject.contains("unblock")) {
                                        isUnblockNetease[0] = true;
                                    }
                                }
                            }
                        }
                    }
                };

                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());

                boolean test1Success = false;
                String test1Msg = "";
                try {
                    URL url = new URL("https://music.163.com/");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection(javaProxy);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    if (conn instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                        ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
                    }
                    int code = conn.getResponseCode();
                    test1Success = (code == 200);
                    test1Msg = "GET主页状态码: " + code;
                    conn.disconnect();
                } catch (Exception e) {
                    test1Success = false;
                    test1Msg = "GET主页出错: " + e.getMessage();
                }

                boolean test2Success = false;
                String test2Msg = "";
                try {
                    URL url = new URL("https://music.163.com/weapi/song/enhance/player/url");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection(javaProxy);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("Referer", "https://music.163.com");
                    if (conn instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                        ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
                    }

                    byte[] postData = "params=test&encSecKey=test".getBytes("UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(postData);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    test2Success = (code == 200);
                    test2Msg = "POST接口状态码: " + code;
                    conn.disconnect();
                } catch (Exception e) {
                    test2Success = false;
                    test2Msg = "POST接口出错: " + e.getMessage();
                }

                boolean success = test1Success && test2Success;
                String summary = "";
                if (success) {
                    summary = isUnblockNetease[0]
                        ? "测试成功！已成功通过 UnblockNeteaseMusic 代理 [" + httpProxy + "] 连通网易云并完成 API 解析测试。"
                        : "测试成功！已成功通过代理 [" + httpProxy + "] 连通网易云并完成 API 解析测试。";
                } else {
                    summary = "自检异常！\n1. " + test1Msg + "\n2. " + test2Msg + "\n请确认解锁代理配置。";
                }

                JSONObject result = new JSONObject();
                result.put("success", success);
                result.put("message", summary);
                result.put("proxy", httpProxy);
                response.send(result);

            } catch (Exception e) {
                try {
                    JSONObject errResult = new JSONObject();
                    errResult.put("success", false);
                    errResult.put("message", "测试执行发生内部异常: " + e.getMessage());
                    response.send(errResult);
                } catch (Exception ignored) {}
            }
        }).start();
    };

    private void responseProxyData(AsyncHttpServerResponse response, AsyncHttpResponse responseProxy, ByteBufferList buffer){
        if(responseProxy == null) {
            response.end();
            return;
        }
        Map<String, String> headers = parseHeader(responseProxy.headers());
        if(responseProxy.code() > 300 && responseProxy.code() < 310){
            response.code(200);
            response.setContentType("application/json");
            response.send(new JSONObject(headers));
            response.end();
            return;
        }
        response.code(responseProxy.code());
        setHeader(response, headers);
        try {
            if(buffer != null){
                String contentType = headers.get("content-type");
                if(contentType == null || contentType.isEmpty()){
                    contentType = "text/plain;charset=UTF-8";
                }
                response.send(contentType, buffer);
            }else {
                response.end();
            }
        }catch (Exception e){
            Log.e(TAG, "get proxy buffer error", e);
            response.end();
        }
    }
    //endregion

    //region header
    private Map<String, String> parseHeader(Headers headers){
        Map<String, String> result = new HashMap<>();
        for(String key: headers.getMultiMap().keySet()){
            result.put(key, String.join(",", headers.getAll(key)));
            if(key.equalsIgnoreCase("set-cookie")){
                result.put("Set-Cookie-Renamed", result.get(key));
            }
        }
        return result;
    }

    private void setCores(AsyncHttpServerResponse response){
        Headers headers = response.getHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "*");
        headers.set("Access-Control-Allow-Headers", "*");
        headers.set("Access-Control-Expose-Headers", "*");
        headers.set("Access-Control-Allow-Credentials", "true");
    }

    private void setHeader(AsyncHttpServerResponse response, Map<String, String> headers){
        for(String key: headers.keySet()){
            switch (key.toLowerCase().replaceAll("-", "")){
                case "cookies":
                case "connection":
                case "contentlength":
                case "contentencoding":
                case "transferencoding":
                case "accesscontrolalloworigin":
                case "accesscontrolallowheaders":
                case "accesscontrolallowmethods":
                case "accesscontrolexposeheaders":
                case "accesscontrolallowcredentials":
                    break;
                default:
                    response.getHeaders().set(key, headers.get(key));
                    break;
            }
        }
    }
    //endregion

    //region websocket
    private final AsyncHttpServer.WebSocketRequestCallback websocketHandler = (webSocket, request) -> {
        synchronized (mWebClientsLock){
            mWebClients.add(webSocket);
        }
        webSocket.send(mAudioPlayer.getStatus().toString());
        webSocket.setClosedCallback(e -> {
            try {
                if (e != null) Log.e(TAG, "An error occurred", e);
            } finally {
                synchronized (mWebClientsLock){
                    mWebClients.remove(webSocket);
                }
            }
        });
    };

    private final AsyncHttpServer.WebSocketRequestCallback serverWebsocketHandler = (webSocket, request) -> this.onRemoteServerSocket(webSocket);

    public void sendWSMessage(String message){
        synchronized (mWebClientsLock){
            for (WebSocket socket: mWebClients) {
                try {
                    socket.send(message);
                }catch (Exception e){
                    Log.e(TAG, "send websocket msg error", e);
                }
            }
        }
    }
    public void sendServerWSMessage(RemoteMessage message){
        String msg = message.toJson();
        if (BuildConfig.DEBUG) Log.d(TAG, "send remote server message: " + msg);
        for (String key: mRemoteClients.keySet()) {
            try {
                Objects.requireNonNull(mRemoteClients.get(key)).send(msg);
            }catch (Exception e){
                Log.e(TAG, "send websocket msg error", e);
            }
        }
    }
    //endregion
    //endregion
}
