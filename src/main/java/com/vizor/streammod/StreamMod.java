package com.vizor.streammod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

public class StreamMod implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        StreamSettings.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streammod.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.streammod"
        ));

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streammod.settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.streammod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                toggle(client);
            }
            while (settingsKey.wasPressed()) {
                if (!Streamer.isStreaming()) {
                    client.openScreen(new StreamSettingsScreen());
                } else {
                    if (client.player != null) {
                        long dur = Streamer.getDurationMs() / 1000;
                        int fps = Streamer.getCurrentFps();
                        int dropped = Streamer.getDroppedFrames();
                        client.player.sendMessage(
                                new LiteralText("§e[Стрим] §fLIVE " + (dur / 60) + "м " + (dur % 60) + "с | "
                                        + fps + " FPS | Потеряно: " + dropped), true);
                    }
                }
            }
        });

        System.out.println("[streammod] Загружен. J = настройки, K = старт/стоп стрима.");
    }

    private static void toggle(MinecraftClient client) {
        if (Streamer.isStreaming()) {
            Streamer.stop();
            if (client.player != null) {
                client.player.sendMessage(
                        new LiteralText("§c[Стрим] Стрим остановлен"), true);
            }
        } else {
            if (!Streamer.findFfmpeg()) {
                if (client.player != null) {
                    client.player.sendMessage(
                            new LiteralText("§c[Стрим] FFmpeg не найден! Установи: sudo apt install ffmpeg"), true);
                }
                return;
            }
            if (StreamSettings.streamKey.isEmpty()) {
                if (client.player != null) {
                    client.player.sendMessage(
                            new LiteralText("§c[Стрим] Введи stream key (нажми J)"), true);
                }
                return;
            }

            // запуск в отдельном потоке чтобы не фризить клиент
            Thread t = new Thread(() -> {
                int w = client.getWindow().getFramebufferWidth();
                int h = client.getWindow().getFramebufferHeight();
                boolean ok = Streamer.start(w, h);
                final String audio = Streamer.hasAudio() ? " + звук" : "";
                client.execute(() -> {
                    if (client.player != null) {
                        if (ok) {
                            client.player.sendMessage(
                                    new LiteralText("§a[Стрим] §fСтрим начата" + audio + " (K для стопа)"), true);
                        } else {
                            client.player.sendMessage(
                                    new LiteralText("§c[Стрим] Ошибка запуска. Проверь лог."), true);
                        }
                    }
                });
            }, "stream-start");
            t.setDaemon(true);
            t.start();
        }
    }
}
