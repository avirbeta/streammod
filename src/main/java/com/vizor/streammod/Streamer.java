package com.vizor.streammod;

import java.io.*;
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

    private static int streamWidth;
    private static int streamHeight;
    private static long startTimeMs;

    private static final BlockingQueue<byte[]> QUEUE = new ArrayBlockingQueue<>(60);
    public static final AtomicInteger droppedFrames = new AtomicInteger(0);
    public static final AtomicInteger sentFrames = new AtomicInteger(0);
    private static volatile int currentFps = 0;
    private static long fpsCounterTime = 0;
    private static int fpsFrameCount = 0;

    // оптимизация: throttle — не больше 1 кадра в 16мс (~60fps cap)
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
        return streaming ? System.currentTimeMillis() - startTimeMs : 0;
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
        if (!findFfmpeg()) return false;
        if (StreamSettings.streamKey.isEmpty()) return false;

        streamWidth = width;
        streamHeight = height;
        audioAvailable = false;
        droppedFrames.set(0);
        sentFrames.set(0);
        fpsCounterTime = System.currentTimeMillis();
        fpsFrameCount = 0;
        QUEUE.clear();

        int outW = width;
        int outH = height;
        if (!"original".equals(StreamSettings.resolution)) {
            if ("720p".equals(StreamSettings.resolution)) {
                outW = 1280;
                outH = 720;
            } else if ("480p".equals(StreamSettings.resolution)) {
                outW = 854;
                outH = 480;
            }
        }

        String audioDevice = findMonitorSourceCached();
        audioAvailable = audioDevice != null;

        try {
            String ffmpegPath = findFfmpegPath();
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

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ffmpeg = p;
            ffmpegIn = p.getOutputStream();
            ffmpegAlive = true;

            // поток для чтения ошибок FFmpeg
            monitorThread = new Thread(() -> {
                try {
                    InputStream err = p.getInputStream();
                    byte[] buf = new byte[2048];
                    int read;
                    while ((read = err.read(buf)) != -1) {
                        String msg = new String(buf, 0, read).trim();
                        if (!msg.isEmpty()) {
                            System.err.println("[streammod] FFmpeg: " + msg);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "ffmpeg-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();

            // pipe thread — пишет кадры в stdin FFmpeg
            pipeThread = new Thread(() -> {
                try {
                    while (streaming) {
                        byte[] frame = QUEUE.poll(500, TimeUnit.MILLISECONDS);
                        if (frame != null) {
                            try {
                                ffmpegIn.write(frame);
                                ffmpegIn.flush();
                                sentFrames.incrementAndGet();
                            } catch (IOException e) {
                                System.err.println("[streammod] Pipe write error: " + e.getMessage());
                                break;
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                }
                // закрываем FFmpeg
                try {
                    if (ffmpegIn != null) ffmpegIn.close();
                } catch (IOException ignored) {
                }
                try {
                    if (p != null) p.waitFor();
                } catch (InterruptedException ignored) {
                }
                ffmpegAlive = false;
            }, "ffmpeg-pipe");
            pipeThread.setDaemon(true);
            pipeThread.start();

            streaming = true;
            startTimeMs = System.currentTimeMillis();
            System.out.println("[streammod] Стрим начата: " + StreamSettings.platform
                    + " | Аудио: " + (audioAvailable ? "да" : "нет")
                    + " | Битрейт: " + StreamSettings.videoBitrate + "k");

            // автоматическая параллельная запись в файл
            Recorder.start(width, height, audioAvailable ? audioDevice : null);

            return true;

        } catch (Exception e) {
            System.err.println("[streammod] Ошибка запуска FFmpeg: " + e);
            stop();
            return false;
        }
    }

    public static synchronized void stop() {
        if (!streaming) return;
        streaming = false;
        QUEUE.clear();

        // останавливаем параллельную запись
        Recorder.stop();

        long ms = System.currentTimeMillis() - startTimeMs;
        int sec = (int) (ms / 1000);
        int min = sec / 60;
        sec = sec % 60;
        int sent = sentFrames.get();
        int dropped = droppedFrames.get();
        System.out.println("[streammod] Стрим остановлен. " + min + "м " + sec + "с | Кадров: " + sent + " | Потеряно: " + dropped);

        try {
            if (ffmpegIn != null) ffmpegIn.close();
        } catch (IOException ignored) {
        }
        try {
            if (ffmpeg != null) ffmpeg.destroy();
        } catch (Exception ignored) {
        }
        ffmpeg = null;
        ffmpegIn = null;
        ffmpegAlive = false;
    }

    /** Вызывается из render thread. Читает пиксели и кладёт в очередь. */
    public static void grabFrame(int width, int height, java.nio.ByteBuffer pixelBuffer) {
        if (!streaming) return;
        if (width != streamWidth || height != streamHeight) return;

        long now = System.currentTimeMillis();
        if (now - lastGrabTimeMs < MIN_FRAME_INTERVAL_MS) return;
        lastGrabTimeMs = now;

        int size = width * height * 4;
        byte[] frame = new byte[size];
        pixelBuffer.position(0);
        pixelBuffer.get(frame);

        // FPS counter
        fpsFrameCount++;
        if (now - fpsCounterTime >= 1000) {
            currentFps = fpsFrameCount;
            fpsFrameCount = 0;
            fpsCounterTime = now;
        }

        if (!QUEUE.offer(frame)) {
            droppedFrames.incrementAndGet();
        }

        // параллельная запись в файл
        Recorder.grabFrame(width, height, pixelBuffer);
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
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
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
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "ffmpeg");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String findFfmpegPath() {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "ffmpeg");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        } catch (Exception ignored) {
        }
        return "ffmpeg";
    }
}
