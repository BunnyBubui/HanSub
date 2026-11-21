package th.hansub.live;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Optional user-configured HTTPS backend. API provider keys NEVER ship in the APK.
 * Dialogue is sent ONLY when a cloud mode is explicitly selected.
 */
public final class RemoteContextTranslator {
    public interface Callback {
        void completed(String thai, String error);
    }

    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    public void translate(String endpoint,String clientToken,String mode,String chinese,
                          List<String> history, Map<String,String> names, Callback callback) {
        if (endpoint==null || !endpoint.startsWith("https://") || clientToken==null ||
                clientToken.isBlank()) {
            main.post(()->callback.completed(null,"Cloud backend is not configured"));
            return;
        }
        pool.execute(()->{
            HttpsURLConnection connection=null;
            try {
                URL url=new URL(endpoint);
                if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo()!=null)
                    throw new IllegalArgumentException("Invalid HTTPS endpoint");
                connection=(HttpsURLConnection)url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(1800);
                connection.setReadTimeout(4200);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                connection.setRequestProperty("Accept","application/json");
                connection.setRequestProperty("Authorization","Bearer "+clientToken.trim());

                JSONObject body=new JSONObject();
                body.put("text",chinese);
                body.put("mode",mode);
                JSONArray previous=new JSONArray();
                for (String line:history) previous.put(line);
                body.put("context",previous);
                JSONObject glossary=new JSONObject();
                for (Map.Entry<String,String> entry:names.entrySet())
                    glossary.put(entry.getKey(),entry.getValue());
                body.put("glossary",glossary);
                byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try(OutputStream output=connection.getOutputStream()) {output.write(payload);}
                int status=connection.getResponseCode();
                if(status!=200) throw new IllegalStateException("Proxy HTTP "+status);
                StringBuilder json=new StringBuilder();
                try(BufferedReader input=new BufferedReader(new InputStreamReader(
                        connection.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while((line=input.readLine())!=null) {
                        json.append(line);
                        if (json.length()>8192) throw new IllegalStateException("Oversize response");
                    }
                }
                String thai=new JSONObject(json.toString()).optString("thai","").trim();
                if (thai.isEmpty()) throw new IllegalStateException("Empty translation");
                main.post(()->callback.completed(thai,null));
            } catch(Exception failure) {
                // Do not expose backend keys or transcript in UI/logs.
                String reason=failure.getClass().getSimpleName();
                main.post(()->callback.completed(null,reason));
            } finally {
                if (connection!=null) connection.disconnect();
            }
        });
    }

    public void close() {pool.shutdownNow();}
}
