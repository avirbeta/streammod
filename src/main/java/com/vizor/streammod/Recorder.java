package com.vizor.streammod;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class Recorder {

    private static Process ffmpeg;
    private static OutputStream ffmpegIn;
    private static Thread pipeThread;
    private static volatile boolean recording = false;
    private static String lastFilePath = "";

    private static final BlockingQueue<byte[]> QUEUE = new ArrayBlockingQueue<>(120);

    private Recorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    public static String getLastFilePath() {
        return lastFilePath;
    }

    public static synchronized boolean start(int width, int height, String audioDevice) {
        if (recording) return false;

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        java.io.File outDir = new java.io.File(
                net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "recordings");
        outDir.mkdirs();
        java.io.File outFile = new java.io.File(outDir, "recording_" + ts + ".mp4");
        lastFilePath = outFile.getAbsolutePath();

        try {
            String ffmpegPath = Streamer.findFfmpegPath();
            String[] cmd;
            if (audioDevice != null) {
                cmd = new String[]{
                    ffmpegPath, "-y", "-loglevel", "warning",
                    "-f", "rawvideo", "-pix_fmt", "bgr0",
                    "-s", width + "x" + height, "-r", "60", "-i", "-",
                    "-f", "pulse", "-thread_queue_size", "512", "-i", audioDevice,
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-crf", "23",
                    "-vf", "vflip",
                    "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
                    "-map", "0:v:0", "-map", "1:a:0",
                    "-shortest",
                    outFile.getAbsolutePath()
                };
            } else {
                cmd = new String[]{
                    ffmpegPath, "-y", "-loglevel", "warning",
                    "-f", "rawvideo", "-pix_fmt", "bgr0",
                    "-s", width + "x" + height, "-r", "60", "-i", "-",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-crf", "23",
                    "-vf", "vflip",
                    outFile.getAbsolutePath()
                };
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ffmpeg = p;
            ffmpegIn = p.getOutputStream();

            Thread errThread = new Thread(() -> {
                try {
                    InputStream err = p.getInputStream();
                    byte[] buf = new byte[1024];
                    while (err.read(buf) != -1) {}
                } catch (IOException ignored) {}
            }, "rec-stderr");
            errThread.setDaemon(true);
            errThread.start();

            pipeThread = new Thread(() -> {
                try {
                    while (recording) {
                        byte[] frame = QUEUE.poll(500, TimeUnit.MILLISECONDS);
                        if (frame != null) {
                            ffmpegIn.write(frame);
                            ffmpegIn.flush();
                        }
                    }
                } catch (IOException | InterruptedException ignored) {}
                try { ffmpegIn.close(); } catch (IOException ignored) {}
                try { p.waitFor(); } catch (InterruptedException ignored) {}
            }, "rec-pipe");
            pipeThread.setDaemon(true);
            pipeThread.start();

            recording = true;
            System.out.println("[streammod] Запись начата: " + outFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            System.err.println("[streammod] Ошибка записи: " + e);
            stop();
            return false;
        }
    }

    public static synchronized void stop() {
        if (!recording) return;
        recording = false;
        QUEUE.clear();
        try { if (ffmpegIn != null) ffmpegIn.close(); } catch (IOException ignored) {}
        try { if (ffmpeg != null) ffmpeg.destroy(); } catch (Exception ignored) {}
        ffmpeg = null;
        ffmpegIn = null;
        System.out.println("[streammod] Запись остановлена: " + lastFilePath);
    }

    public static void grabFrame(int width, int height, java.nio.ByteBuffer pixelBuffer) {
        if (!recording) return;
        int size = width * height * 4;
        byte[] frame = new byte[size];
        pixelBuffer.position(0);
        pixelBuffer.get(frame);
        QUEUE.offer(frame);
    }
}
