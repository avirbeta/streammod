package com.vizor.streammod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;

import java.util.ArrayList;

public class StreamSettingsScreen extends Screen {

    private TextFieldWidget keyField;
    private TextFieldWidget bitrateField;
    private TextFieldWidget customUrlField;
    private TextFieldWidget titleField;
    private TextFieldWidget descField;

    public StreamSettingsScreen() {
        super(new TranslatableText("gui.streammod.title"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 24;

        // название стрима
        y += 10;
        titleField = new TextFieldWidget(this.textRenderer, cx - 130, y, 260, 18,
                new LiteralText("Title"));
        titleField.setText(StreamSettings.streamTitle);
        titleField.setMaxLength(150);
        this.addButton(titleField);
        y += 24;

        // описание стрима
        y += 10;
        descField = new TextFieldWidget(this.textRenderer, cx - 130, y, 260, 18,
                new LiteralText("Description"));
        descField.setText(StreamSettings.streamDescription);
        descField.setMaxLength(300);
        this.addButton(descField);
        y += 24;

        // платформа
        final ArrayList<String> platformKeys = new ArrayList<>(StreamSettings.PLATFORMS.keySet());
        final ArrayList<String> platformNames = new ArrayList<>(StreamSettings.PLATFORMS.values());

        int platIdx = 0;
        for (int i = 0; i < platformKeys.size(); i++) {
            if (platformKeys.get(i).equals(StreamSettings.platform)) platIdx = i;
        }
        final int[] idx = {platIdx};
        this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                new LiteralText("Платформа: " + platformNames.get(idx[0])), b -> {
            idx[0] = (idx[0] + 1) % platformKeys.size();
            StreamSettings.platform = platformKeys.get(idx[0]);
            b.setMessage(new LiteralText("Платформа: " + platformNames.get(idx[0])));
        }));
        y += 26;

        // stream key
        keyField = new TextFieldWidget(this.textRenderer, cx - 130, y, 260, 18,
                new LiteralText("Stream Key"));
        keyField.setText(StreamSettings.streamKey);
        keyField.setMaxLength(200);
        this.addButton(keyField);
        y += 24;

        // кастомный RTMP URL
        customUrlField = new TextFieldWidget(this.textRenderer, cx - 130, y, 260, 18,
                new LiteralText("Custom RTMP URL"));
        customUrlField.setText(StreamSettings.customRtmpUrl);
        customUrlField.setMaxLength(300);
        this.addButton(customUrlField);
        y += 24;

        // битрейт
        bitrateField = new TextFieldWidget(this.textRenderer, cx - 130, y, 100, 18,
                new LiteralText("Bitrate"));
        bitrateField.setText(String.valueOf(StreamSettings.videoBitrate));
        bitrateField.setMaxLength(6);
        this.addButton(bitrateField);
        this.addButton(new ButtonWidget(cx - 24, y, 154, 18,
                new LiteralText("кбит/с"), b -> {}));
        y += 24;

        // разрешение
        final String[] resos = {"original", "720p", "480p"};
        final String[] resoNames = {"Оригинал", "720p", "480p"};
        int resIdx = 0;
        for (int i = 0; i < resos.length; i++) {
            if (resos[i].equals(StreamSettings.resolution)) resIdx = i;
        }
        final int[] ri = {resIdx};
        this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                new LiteralText("Разрешение: " + resoNames[ri[0]]), b -> {
            ri[0] = (ri[0] + 1) % resos.length;
            StreamSettings.resolution = resos[ri[0]];
            b.setMessage(new LiteralText("Разрешение: " + resoNames[ri[0]]));
        }));
        y += 26;

        // аудио
        boolean hasMonitor = Streamer.findMonitorSourceCached() != null;
        this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                new LiteralText(hasMonitor ? "Звук: PulseAudio" : "Звук: не обнаружен"), b -> {}));
        y += 26;

        // стрим
        boolean live = Streamer.isStreaming();
        if (live) {
            long dur = Streamer.getDurationMs() / 1000;
            int fps = Streamer.getCurrentFps();
            int sent = Streamer.getSentFrames();
            int dropped = Streamer.getDroppedFrames();
            this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                    new LiteralText("LIVE " + (dur / 60) + "м " + (dur % 60) + "с | "
                            + fps + " FPS | " + sent + " кадров | " + dropped + " потеряно"), b -> {}));
            y += 26;
            this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                    new LiteralText("Остановить стрим"), b -> {
                Streamer.stop();
                this.init();
            }));
        } else {
            this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                    new LiteralText("Офлайн"), b -> {}));
            y += 26;
            this.addButton(new ButtonWidget(cx - 130, y, 260, 20,
                    new LiteralText("Начать стрим"), b -> {
                saveSettings();
                StreamSettings.save();
                startStreamAsync();
            }));
        }
        y += 26;

        // готово
        this.addButton(new ButtonWidget(cx - 40, Math.max(y + 10, this.height - 30), 80, 20,
                new TranslatableText("gui.streammod.done"), b -> {
            saveSettings();
            StreamSettings.save();
            this.onClose();
        }));
    }

    private void startStreamAsync() {
        MinecraftClient client = MinecraftClient.getInstance();
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
                                new LiteralText("§c[Стрим] Ошибка запуска"), true);
                    }
                }
                if (client.currentScreen instanceof StreamSettingsScreen) {
                    ((StreamSettingsScreen) client.currentScreen).init();
                }
            });
        }, "stream-start");
        t.setDaemon(true);
        t.start();
    }

    private void saveSettings() {
        StreamSettings.streamKey = keyField.getText().trim();
        StreamSettings.customRtmpUrl = customUrlField.getText().trim();
        StreamSettings.streamTitle = titleField.getText().trim();
        StreamSettings.streamDescription = descField.getText().trim();
        try {
            StreamSettings.videoBitrate = Integer.parseInt(bitrateField.getText().trim());
        } catch (NumberFormatException e) {
            StreamSettings.videoBitrate = 2500;
        }
        if (StreamSettings.videoBitrate < 500) StreamSettings.videoBitrate = 500;
        if (StreamSettings.videoBitrate > 10000) StreamSettings.videoBitrate = 10000;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title.getString(), this.width / 2, 8, 0xFFFFFF);

        int cx = this.width / 2;
        this.textRenderer.drawWithShadow(matrices, "Название стрима:", cx - 130, 24, 0xAAAAAA);
        this.textRenderer.drawWithShadow(matrices, "Описание:", cx - 130, 54, 0xAAAAAA);

        super.render(matrices, mouseX, mouseY, delta);
    }
}
