package com.picapico.audioshare.musiche;

import android.util.Base64;
import android.util.Log;

import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.AsyncHttpGet;
import com.koushikdutta.async.http.AsyncHttpPost;
import com.koushikdutta.async.http.AsyncHttpRequest;
import com.koushikdutta.async.http.AsyncHttpResponse;
import com.koushikdutta.async.http.body.StringBody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MusicItem {
    public enum Quality {
        PQ, SQ, HQ, ZQ
    }
    private static final String TAG = "AudioShareMusicItem";
    private static final String EncSecKey = "&encSecKey=409afd10f2fa06173df57525287c4a1cdf6fa08bd542c6400da953704eb92dc1ad3c582e82f51a707ebfa0f6a25bcd185139fc1509d40dd97b180ed21641df55e90af4884a0b587bd25256141a9270b1b6f18908c6a626b74167e5a55a796c0f808a2eb12c33e63d34a7c4d358bab1dc661637dd1e888a1268b81a89f6136053";
    private static final String CloudMusicAPI = "https://music.163.com/weapi/song/enhance/player/url?csrf_token=";
    private static final String UserAgent = "Mozilla/5.0 (iPad; CPU OS 11_0 like Mac OS X) AppleWebKit/604.1.34 (KHTML, like Gecko) Version/11.0 Mobile/15A5341f Safari/604.1";
    private String id;
    private String name;
    private String image;
    private String singer;
    private String album;
    private String remark;
    private String cookie;
    private String musicU = "";
    private String uid = "";
    private String csrf = "";
    private String type;

    public Map<String, Object> toMap(){
        Map<String, Object> data = new HashMap<>();
        if(id != null) data.put("id", id);
        if(name != null) data.put("name", name);
        if(image != null) data.put("image", image);
        if(singer != null) data.put("singer", singer);
        if(album != null) data.put("album", album);
        if(remark != null) data.put("remark", remark);
        if(cookie != null) data.put("cookie", cookie);
        if(musicU != null) data.put("musicU", musicU);
        if(uid != null) data.put("uid", uid);
        if(csrf != null) data.put("csrf", csrf);
        if(type != null) data.put("type", type);
        return data;
    }

    public interface onUrlLoadedCallback {
        void onUrlLoaded(String url);
    }
    
    private interface HttpRequestCallback {
        void onCompleted(Exception e, String result);
    }

    private static void executeRequest(Context context, String urlStr, String method, String body, Map<String, String> headers, HttpRequestCallback callback) {
        new Thread(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                String httpProxy = "";
                if (context != null) {
                    SharedPreferences sp = context.getSharedPreferences("config", Context.MODE_PRIVATE);
                    httpProxy = sp.getString("musiche-http-proxy", "").trim();
                    if (httpProxy.startsWith("\"") && httpProxy.endsWith("\"")) {
                        httpProxy = httpProxy.substring(1, httpProxy.length() - 1);
                    }
                    httpProxy = httpProxy.trim();
                }

                URL url = new URL(urlStr);
                Log.i(TAG, "executeRequest API URL: " + urlStr + ", Proxy: [" + httpProxy + "]");
                if (!httpProxy.isEmpty()) {
                    String proxyHost = "";
                    int proxyPort = 80;
                    String temp = httpProxy;
                    if (temp.startsWith("http://")) {
                        temp = temp.substring(7);
                    } else if (temp.startsWith("https://")) {
                        temp = temp.substring(8);
                    }
                    temp = temp.split("/")[0].trim();
                    String[] parts = temp.split(":");
                    proxyHost = parts[0];
                    if (parts.length > 1) {
                        proxyPort = Integer.parseInt(parts[1].replaceAll("[^\\d]", ""));
                    }
                    Log.i(TAG, "Connecting API via proxy -> Host: " + proxyHost + ", Port: " + proxyPort);
                    java.net.Proxy javaProxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(proxyHost, proxyPort));
                    conn = (java.net.HttpURLConnection) url.openConnection(javaProxy);
                } else {
                    Log.i(TAG, "Connecting API directly without proxy");
                    conn = (java.net.HttpURLConnection) url.openConnection();
                }

                conn.setRequestMethod(method);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                    javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        }
                    };
                    javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                    sc.init(null, trustAllCerts, new java.security.SecureRandom());
                    ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                    ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
                }

                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                if (body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int code = conn.getResponseCode();
                java.io.InputStream is;
                if (code >= 400) {
                    is = conn.getErrorStream();
                } else {
                    is = conn.getInputStream();
                }

                String responseStr = "";
                if (is != null) {
                    String encoding = conn.getContentEncoding();
                    if (encoding != null && encoding.toLowerCase().contains("gzip")) {
                        is = new java.util.zip.GZIPInputStream(is);
                    }
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    responseStr = sb.toString();
                }

                final String finalResponse = responseStr;
                new Handler(Looper.getMainLooper()).post(() -> callback.onCompleted(null, finalResponse));

            } catch (Exception e) {
                Log.e(TAG, "executeRequest URL failed: " + urlStr, e);
                new Handler(Looper.getMainLooper()).post(() -> callback.onCompleted(e, null));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public void getMusicUrl(Context context, Quality quality, onUrlLoadedCallback callback){
        Log.i(TAG, "get play url: " + name + " " + type);
        switch (type){
            case "cloud": getCloudMusicUrl(context, quality, callback);break;
            case "qq": getQQMusicUrl(context, quality, callback, false);break;
            case "migu": getMiGuMusicUrl(context, quality, callback);break;
            default:
                callback.onUrlLoaded(null);break;
        }
    }

    private void getCloudMusicUrl(Context context, Quality quality, onUrlLoadedCallback callback){
        int br;
        switch (quality){
            case HQ: br = 320000; break;
            case SQ: br = 480000; break;
            case ZQ: br = 960000; break;
            default: br = 128000; break;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("ids", new String[]{id});
        data.put("br", br);
        data.put("csrf_token", csrf);
        String param = aesEncrypt(new JSONObject(data).toString(), "0CoJUm6Qyw8W8jud");
        param = aesEncrypt(param, "t9Y0m4pdsoMznMlL");
        try {
            param = URLEncoder.encode(param, "UTF-8");
        } catch (UnsupportedEncodingException ignore) {
        }
        String paramData = "params=" + param + EncSecKey;

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Referer", "https://music.163.com");
        headers.put("User-Agent", UserAgent);
        headers.put("Cookie", "os=ios;MUSIC_U=" + musicU);

        executeRequest(context, CloudMusicAPI, "POST", paramData, headers, (e, resultStr) -> {
            String url = null;
            if (resultStr != null && !resultStr.isEmpty()) {
                try {
                    JSONObject result = new JSONObject(resultStr);
                    if (result.has("data")) {
                        JSONObject obj = result.getJSONArray("data").getJSONObject(0);
                        if (obj.has("url")) {
                            url = obj.getString("url").replace("http://", "https://");
                        }
                    }
                } catch (JSONException ex) {
                    Log.e(TAG, "get cloud url error", ex);
                }
            }
            callback.onUrlLoaded(url);
        });
    }

    private static int now(){
        return (int) (System.currentTimeMillis()/1000);
    }

    private void getQQMusicUrl(Context context, Quality ignore, onUrlLoadedCallback callback, boolean audition){
        String fileName = "";
        if(audition && remark != null && !remark.isEmpty()){
            fileName = "\"" + remark + "\"";
        }
        String body = "{\"comm\":{\"cv\":4747474,\"ct\":24,\"format\":\"json\"," +
                "\"inCharset\":\"utf-8\",\"outCharset\":\"utf-8\",\"notice\":0," +
                "\"platform\":\"yqq.json\",\"needNewCode\":1}," +
                "\"req_0\":{\"module\":\"vkey.GetVkeyServer\"," +
                "\"method\":\"CgiGetVkey\"," +
                "\"param\":{" +
                "\"guid\":\"" + now() + "\"," +
                "\"songmid\":[\"" + id + "\"]," +
                "\"songtype\":[0],\"uin\":\"\",\"loginflag\":1,\"platform\":\"20\"," +
                "\"filename\":[" + fileName + "]}}}";

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://y.qq.com");
        headers.put("Content-Type", "application/json");
        headers.put("Cookie", cookie);

        executeRequest(context, "https://u.y.qq.com/cgi-bin/musicu.fcg", "POST", body, headers, (e, resultStr) -> {
            if (resultStr == null || resultStr.isEmpty()) {
                callback.onUrlLoaded(null);
                return;
            }
            String musicUrl = null;
            try {
                JSONObject result = new JSONObject(resultStr);
                JSONObject data = result.getJSONObject("req_0").getJSONObject("data");
                String pUrl = data.getJSONArray("midurlinfo").getJSONObject(0).getString("purl");
                JSONArray sip = data.getJSONArray("sip");
                String urlPrefix = null;
                for (int i = 0; i < sip.length(); i++) {
                    String host = sip.getString(i);
                    if (host != null && !host.isEmpty()) {
                        urlPrefix = host;
                        break;
                    }
                }
                if (urlPrefix == null) {
                    urlPrefix = "https://dl.stream.qqmusic.qq.com/";
                }
                if (!pUrl.isEmpty()) musicUrl = urlPrefix + pUrl;
            } catch (Exception ex) {
                Log.e(TAG, "get qq music url error", ex);
            }
            if (musicUrl == null && !audition) {
                getQQMusicUrl(context, ignore, callback, true);
            } else {
                callback.onUrlLoaded(musicUrl);
            }
        });
    }

    private void getMiGuMusicUrl(Context context, Quality quality, onUrlLoadedCallback callback){
        String toneFlag = "PQ";
        switch (quality){
            case HQ: toneFlag = "HQ"; break;
            case SQ: toneFlag = "SQ"; break;
            case ZQ: toneFlag = "ZQ"; break;
        }
        String requestUrl =
                "https://c.musicapp.migu.cn/MIGUM3.0/strategy/listen-url/v2.4?" +
                        "resourceType=2&netType=01&scene=" +
                        "&toneFlag=" + toneFlag +
                        "&contentId=" + remark +
                        "&copyrightId=" + id +
                        "&lowerQualityContentId=" + id;

        Map<String, String> headers = new HashMap<>();
        headers.put("channel", "014000D");
        headers.put("uid", uid);
        headers.put("Cookie", cookie);

        executeRequest(context, requestUrl, "GET", null, headers, (e, resultStr) -> {
            String url = null;
            if (resultStr != null && !resultStr.isEmpty()) {
                try {
                    JSONObject result = new JSONObject(resultStr);
                    if (result.has("data")) {
                        JSONObject obj = result.getJSONObject("data");
                        if (obj.has("url")) {
                            url = obj.getString("url").replace("http://", "https://");
                        }
                    }
                } catch (JSONException ex) {
                    Log.e(TAG, "get cloud url error", ex);
                }
            }
            callback.onUrlLoaded(url);
        });
    }
    private String aesEncrypt(String plain, String key){
        String iv = "0102030405060708";
        if (plain == null || key == null) return "";
        try {
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), ivParameterSpec);
            String base64Str = Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.DEFAULT);
            return base64Str.replaceAll("\n", "").replaceAll("\r", "");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    public static MusicItem of(JSONObject jsonObject){
        MusicItem musicItem = new MusicItem();
        try {
            if(jsonObject.has("id")){
                musicItem.setId(jsonObject.getString("id"));
            }
            if(jsonObject.has("name")){
                musicItem.setName(jsonObject.getString("name"));
            }
            String largeImage = jsonObject.has("largeImage") ? jsonObject.getString("largeImage") : null;
            String mediumImage = jsonObject.has("mediumImage") ? jsonObject.getString("mediumImage") : null;
            String smallImage = jsonObject.has("image") ? jsonObject.getString("image") : null;
            if(largeImage != null && !largeImage.isEmpty()){
                musicItem.setImage(largeImage);
            }else if(mediumImage != null && !mediumImage.isEmpty()){
                musicItem.setImage(mediumImage);
            }else if(smallImage != null && !smallImage.isEmpty()){
                musicItem.setImage(smallImage);
            }
            if(jsonObject.has("singer")){
                musicItem.setSinger(jsonObject.getString("singer"));
            }
            if(jsonObject.has("album")){
                musicItem.setAlbum(jsonObject.getString("album"));
            }
            if(jsonObject.has("remark")){
                musicItem.setRemark(jsonObject.getString("remark"));
            }
            if(jsonObject.has("type")){
                musicItem.setType(jsonObject.getString("type"));
            }
            if(jsonObject.has("cookie")){
                try{
                    if("cloud".equals(musicItem.getType())){
                        JSONObject typeObj = jsonObject.getJSONObject("cookie");
                        if(typeObj.has("__csrf")){
                            musicItem.setCsrf(typeObj.getString("__csrf"));
                        }
                        if(typeObj.has("MUSIC_U")){
                            musicItem.setMusicU(typeObj.getString("MUSIC_U"));
                        }
                    }else if("migu".equals(musicItem.getType())) {
                        JSONObject typeObj = jsonObject.getJSONObject("cookie");
                        if(typeObj.has("cookie")){
                            musicItem.setCookie(typeObj.getString("cookie"));
                        }
                        if(typeObj.has("uid")){
                            musicItem.setUid(typeObj.getString("uid"));
                        }
                    }else {
                        musicItem.setCookie(jsonObject.getString("cookie"));
                    }
                }catch (Exception ignore){
                    musicItem.setCookie(jsonObject.getString("cookie"));
                }
            }
            if(jsonObject.has("__csrf")){
                musicItem.setCsrf(jsonObject.getString("__csrf"));
            }
            if(jsonObject.has("MUSIC_U")){
                musicItem.setMusicU(jsonObject.getString("MUSIC_U"));
            }
            if(jsonObject.has("uid")){
                musicItem.setUid(jsonObject.getString("uid"));
            }
        } catch (Exception e) {
            Log.e(TAG, "parse music item error", e);
        }
        return musicItem;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getSinger() {
        return singer;
    }

    public void setSinger(String singer) {
        this.singer = singer;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setMusicU(String musicU) {
        this.musicU = musicU;
    }

    public void setCsrf(String csrf) {
        this.csrf = csrf;
    }
}
