package com.vizor.streammod;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Streamer {

    private static Process ffmpeg;
    private static OutputStream ffmpegIn;
    private static Thread pipeThread;
    private static Thread monitorThread;
    private static volatile boolean streaming = false;
    private static volatile boolean ffmpegAlive = false;
    private static boolean audioAvailable = false;
    private static String cachedMonitorSource = null;
    private static long monitorCacheTime = 0;
    private static volatile String ffmpegPathCache = null;

    private static int streamWidth;
    private static int streamHeight;
    private static long startTimeMs;
    private static volatile long lastDurationMs = 0L;

    private static final BlockingQueue<byte[]> QUEUE = new ArrayBlockingQueue<>(60);
    private static final BlockingQueue<byte[]> FREE = new ArrayBlockingQueue<>(60);
    public static final AtomicInteger droppedFrames = new AtomicInteger(0);
    public static final AtomicInteger sentFrames = new AtomicInteger(0);
    private static volatile int currentFps = 0;
    private static long fpsCounterTime = 0;
    private static int fpsFrameCount = 0;

    // throttle — не больше 1 кадра в 16мс (~60fps cap)
    private static volatile long lastGrabTimeMs = 0;
    private static final long MIN_FRAME_INTERVAL_MS = 16;

    private Streamer() {
    }

    public static boolean isStreaming() {
        return streaming;
    }

    public static boolean isAlive() {
        return ffmpegAlive;
    }

    public static boolean hasAudio() {
        return audioAvailable;
    }

    public static long getDurationMs() {
        return streaming ? System.currentTimeMillis() - startTimeMs : lastDurationMs;
    }

    public static long getLastDurationMs() {
        return lastDurationMs;
    }

    public static int getCurrentFps() {
        return currentFps;
    }

    public static int getQueueSize() {
        return QUEUE.size();
    }

    public static int getDroppedFrames() {
        return droppedFrames.get();
    }

    public static int getSentFrames() {
        return sentFrames.get();
    }

    public static synchronized boolean start(int width, int height) {
        if (streaming) return false;
        if (width <= 0 || height <= 0) return false;
        if (StreamSettings.streamKey == null || StreamSettings.streamKey.isEmpty()) return false;

        String ffmpegPath = findFfmpegPath();
        if (ffmpegPath == null) return false;

        streamWidth = width;
        streamHeight = height;
        droppedFrames.set(0);
        sentFrames.set(0);
        lastDurationMs = 0L;
        lastGrabTimeMs = 0L;
        fpsCounterTime = System.currentTimeMillis();
        fpsFrameCount = 0;
        QUEUE.clear();
        FREE.clear();

        int outW = width;
        int outH = height;
        if ("720p".equals(StreamSettings.resolution)) {
            outW = 1280;
            outH = 720;
        } else if ("480p".equals(StreamSettings.resolution)) {
            outW = 854;
            outH = 480;
        }

        String configuredAudio = StreamSettings.audioDevice;
        String audioDevice;
        if (configuredAudio == null || configuredAudio.isEmpty() || "auto".equalsIgnoreCase(configuredAudio)) {
            audioDevice = findMonitorSourceCached();
        } else {
            audioDevice = configuredAudio;
        }
        audioAvailable = audioDevice != null && !audioDevice.trim().isEmpty();

        try {
            String[] cmd;
            if (audioAvailable) {
                cmd = new String[]{
                        ffmpegPath, "-y", "-loglevel", "warning",
                        "-f", "rawvideo", "-pix_fmt", "bgr0",
                        "-s", width + "x" + height, "-r", "60", "-i", "-",
                        "-f", "pulse", "-thread_queue_size", "512", "-i", audioDevice,
                        "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                        "-vf", "vflip,scale=" + outW + ":" + outH,
                        "-b:v", StreamSettings.videoBitrate + "k",
                        "-maxrate", StreamSettings.videoBitrate + "k",
                        "-bufsize", (StreamSettings.videoBitrate * 2) + "k",
                        "-pix_fmt", "yuv420p", "-g", "60",
                        "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                        "-map", "0:v:0", "-map", "1:a:0",
                        "-shortest",
                        "-f", "flv",
                        StreamSettings.getFullUrl()
                };
            } else {
                cmd = new String[]{
                        ffmpegPath, "-y", "-loglevel", "warning",
                        "-f", "rawvideo", "-pix_fmt", "bgr0",
                        "-s", width + "x" + height, "-r", "60", "-i", "-",
                        "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                        "-vf", "vflip,scale=" + outW + ":" + outH,
                        "-b:v", StreamSettings.videoBitrate + "k",
                        "-maxrate", StreamSettings.videoBitrate + "k",
                        "-bufsize", (StreamSettings.videoBitrate * 2) + "k",
                        "-pix_fmt", "yuv420p", "-g", "60",
                        "-f", "flv",
                        StreamSettings.getFullUrl()
                };
            }

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            ffmpeg = p;
            ffmpegIn = p.getOutputStream();
            ffmpegAlive = true;

            // streaming=true ДО старта pipe-потока, иначе while (streaming) сразу завершится.
            streaming = true;
            startTimeMs = System.currentTimeMillis();

            monitorThread = new Thread(() -> {
                try {
                    InputStream out = p.getInputStream();
                    byte[] buf = new byte[2048];
                    int read;
                    while ((read = out.read(buf)) != -1) {
                        String msg = new String(buf, 0, read).trim();
                        if (!msg.isEmpty()) {
                            System.err.println("[streammod] FFmpeg: " + msg);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "streammod-ffmpeg-log");
            monitorThread.setDaemon(true);
            monitorThread.start();

            pipeThread = new Thread(() -> {
                try {
                    while (true) {
                        byte[] frame = QUEUE.poll(200, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            if (!streaming) break;
                            continue;
                        }
                        try {
                            ffmpegIn.write(frame);
                            ffmpegIn.flush();
                            sentFrames.incrementAndGet();
                        } catch (IOException e) {
                            System.err.println("[streammod] Pipe write error: " + e.getMessage());
                            break;
                        } finally {
                            FREE.offer(frame);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
                try {
                    if (ffmpegIn != null) ffmpegIn.close();
                } catch (IOException ignored) {
                }
                try {
                    p.waitFor();
                } catch (InterruptedException ignored) {
                }
                ffmpegAlive = false;
                streaming = false;
            }, "streammod-ffmpeg-pipe");
            pipeThread.setDaemon(true);
            pipeThread.start();

            System.out.println("[streammod] Стрим начат: " + StreamSettings.platform
                    + " | Аудио: " + (audioAvailable ? "да" : "нет")
                    + " | Битрейт: " + StreamSettings.videoBitrate + "k");
            return true;

        } catch (Exception e) {
            System.err.println("[streammod] Ошибка запуска FFmpeg: " + e);
            streaming = false;
            cleanup();
            return false;
        }
    }

    public static synchronized void stop() {
        if (!streaming) {
            cleanup();
            return;
        }
        lastDurationMs = System.currentTimeMillis() - startTimeMs;
        streaming = false;
        QUEUE.clear();

        try {
            if (pipeThread != null) {
                pipeThread.join(3000);
            }
        } catch (InterruptedException ignored) {
        }

        cleanup();

        long sec = lastDurationMs / 1000L;
        System.out.println("[streammod] Стрим остановлен. " + (sec / 60) + "м " + (sec % 60) + "с | Кадров: "
                + sentFrames.get() + " | Потеряно: " + droppedFrames.get());
    }

    private static void cleanup() {
        try {
            if (ffmpegIn != null) {
                ffmpegIn.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (ffmpeg != null && !ffmpeg.waitFor(5, TimeUnit.SECONDS)) {
                ffmpeg.destroy();
                if (!ffmpeg.waitFor(2, TimeUnit.SECONDS)) {
                    ffmpeg.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        }
        ffmpeg = null;
        ffmpegIn = null;
        pipeThread = null;
        monitorThread = null;
        ffmpegAlive = false;
        QUEUE.clear();
        FREE.clear();
    }

    /** Вызывается из render thread. Читает пиксели и кладёт в очередь. */
    public static void grabFrame(int width, int height, java.nio.ByteBuffer pixelBuffer) {
        if (!streaming) return;
        if (width != streamWidth || height != streamHeight) return;

        long now = System.currentTimeMillis();
        if (now - lastGrabTimeMs < MIN_FRAME_INTERVAL_MS) return;
        lastGrabTimeMs = now;

        int size = width * height * 4;
        byte[] frame = FREE.poll();
        if (frame == null || frame.length != size) {
            frame = new byte[size];
        }
        pixelBuffer.position(0);
        pixelBuffer.get(frame);

        fpsFrameCount++;
        if (now - fpsCounterTime >= 1000) {
            currentFps = fpsFrameCount;
            fpsFrameCount = 0;
            fpsCounterTime = now;
        }

        if (!QUEUE.offer(frame)) {
            droppedFrames.incrementAndGet();
            FREE.offer(frame);
        }
    }

    public static String findMonitorSourceCached() {
        long now = System.currentTimeMillis();
        if (cachedMonitorSource != null && (now - monitorCacheTime) < 30_000) {
            return cachedMonitorSource;
        }
        cachedMonitorSource = findMonitorSource();
        monitorCacheTime = now;
        return cachedMonitorSource;
    }

    private static String findMonitorSource() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pactl", "list", "short", "sources");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains(".monitor")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        p.destroy();
                        return parts[1];
                    }
                }
            }
            p.destroy();
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean findFfmpeg() {
        return findFfmpegPath() != null;
    }

    private static String findFfmpegPath() {
        String cached = ffmpegPathCache;
        if (cached != null) return cached;

        String env = System.getenv("FFMPEG_PATH");
        if (env != null && new File(env).canExecute()) {
            ffmpegPathCache = env;
            return env;
        }

        String[] candidates = {
                "ffmpeg",
                "/usr/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "C:\\ffmpeg\\bin\\ffmpeg.exe"
        };
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-version").start();
                if (p.waitFor(1000, TimeUnit.MILLISECONDS) && p.exitValue() == 0) {
                    ffmpegPathCache = candidate;
                    return candidate;
                }
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
