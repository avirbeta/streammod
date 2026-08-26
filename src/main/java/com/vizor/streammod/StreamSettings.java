package com.vizor.streammod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StreamSettings {

    public static String platform = "twitch";
    public static String streamKey = "";
    public static int videoBitrate = 2500;
    public static String resolution = "original";
    public static String customRtmpUrl = "";
    public static String streamTitle = "Minecraft 1.16.5";
    public static String streamDescription = "";
    public static String audioDevice = "auto";

    private static File file;
    private static final Gson GSON = new Gson();

    public static final LinkedHashMap<String, String> PLATFORMS = new LinkedHashMap<String, String>();
    static {
        PLATFORMS.put("twitch", "Twitch");
        PLATFORMS.put("youtube", "YouTube");
        PLATFORMS.put("vkplay", "VK Play Live");
        PLATFORMS.put("rutube", "Rutube");
        PLATFORMS.put("trovo", "Trovo");
        PLATFORMS.put("goodgame", "GoodGame");
        PLATFORMS.put("kick", "Kick");
        PLATFORMS.put("custom", "Свой RTMP URL");
    }

    public static String getPlatformUrl(String key) {
        switch (key) {
            case "twitch":  return "rtmp://live.twitch.tv/app/";
            case "youtube": return "rtmp://a.rtmp.youtube.com/live2/";
            case "vkplay":  return "rtmp://live.vkplay.ru/app/";
            case "rutube":  return "rtmp://rutube.ru/live/";
            case "trovo":   return "rtmp://push.trovo.live/live/";
            case "goodgame":return "rtmp://badge.goodgame.ru/";
            case "kick":    return "rtmp://fa723fc1b947.global-live.fastly.net/live/";
            case "custom":  return customRtmpUrl;
            default:        return "rtmp://live.twitch.tv/app/";
        }
    }

    public static String getFullUrl() {
        String base = getPlatformUrl(platform);
        if (base == null || base.isEmpty()) return "";
        return base + streamKey;
    }

    public static void load() {
        file = FabricLoader.getInstance().getGameDir().resolve("streammod.json").toFile();
        if (!file.exists()) {
            save();
            return;
        }
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = new com.google.gson.JsonParser().parse(r).getAsJsonObject();
            platform = root.has("platform") ? root.get("platform").getAsString() : "twitch";
            streamKey = root.has("streamKey") ? root.get("streamKey").getAsString() : "";
            videoBitrate = root.has("videoBitrate") ? root.get("videoBitrate").getAsInt() : 2500;
            resolution = root.has("resolution") ? root.get("resolution").getAsString() : "original";
            customRtmpUrl = root.has("customRtmpUrl") ? root.get("customRtmpUrl").getAsString() : "";
            streamTitle = root.has("streamTitle") ? root.get("streamTitle").getAsString() : "Minecraft 1.16.5";
            streamDescription = root.has("streamDescription") ? root.get("streamDescription").getAsString() : "";
            audioDevice = root.has("audioDevice") ? root.get("audioDevice").getAsString() : "auto";
        } catch (Exception e) {
            System.err.println("[streammod] Ошибка чтения конфига: " + e);
        }
    }

    public static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("platform", platform);
            root.addProperty("streamKey", streamKey);
            root.addProperty("videoBitrate", videoBitrate);
            root.addProperty("resolution", resolution);
            root.addProperty("customRtmpUrl", customRtmpUrl);
            root.addProperty("streamTitle", streamTitle);
            root.addProperty("streamDescription", streamDescription);
            root.addProperty("audioDevice", audioDevice);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            System.err.println("[streammod] Ошибка сохранения: " + e);
        }
    }
}
