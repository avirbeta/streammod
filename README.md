# Stream Mod — Стриминг Minecraft 1.16.5 (Fabric)

Стримит весь экран Minecraft (интерфейс, инвентарь, HUD, чат) + звук из игры на 7 платформ и любой RTMP-сервер.

## Горячие клавиши

| Клавиша | Действие |
|---------|----------|
| **J** | Настройки стрима |
| **K** | Старт / стоп (или статистика если LIVE) |

## Поддерживаемые платформы

| Платформа | URL |
|-----------|-----|
| **Twitch** | rtmp://live.twitch.tv/app/ |
| **YouTube** | rtmp://a.rtmp.youtube.com/live2/ |
| **VK Play Live** | rtmp://live.vkplay.ru/app/ |
| **Rutube** | rtmp://rutube.ru/live/ |
| **Trovo** | rtmp://push.trovo.live/live/ |
| **GoodGame** | rtmp://badge.goodgame.ru/ |
| **Kick** | rtmp://fa723fc1b947.global-live.fastly.net/live/ |
| **Свой** | Любой RTMP URL |

## Где взять Stream Key

| Платформа | Где найти |
|-----------|-----------|
| **Twitch** | [dashboard.twitch.tv/settings/stream](https://dashboard.twitch.tv/settings/stream) → Primary Stream Key |
| **YouTube** | [studio.youtube.com](https://studio.youtube.com) → Go Live → Stream → Stream key |
| **VK Play Live** | [live.vkplay.ru](https://live.vkplay.ru) → Настройки канала → Ключ трансляции |
| **Rutube** | [rutube.ru/live](https://rutube.ru/live) → Создать трансляцию → ключ |
| **Trovo** | [trovo.live](https://trovo.live) → Dashboard → Stream Key |
| **GoodGame** | [goodgame.ru](https://goodgame.ru) → Панель стримера → Stream Key |
| **Kick** | [kick.com](https://kick.com) → Dashboard → Stream URL & Key |
| **Свой** | Введи RTMP URL в поле "Custom RTMP URL" |

## Установка

1. Установи [Fabric Loader](https://fabricmc.net/) для Minecraft 1.16.5
2. Установи FFmpeg:
   - **Linux**: `sudo apt install ffmpeg`
   - **Windows**: скачай с [ffmpeg.org](https://ffmpeg.org/download.html), добавь в PATH
   - **Mac**: `brew install ffmpeg`
3. Положи `streammod-fabric-1.16.5-1.0.0.jar` + [Fabric API](https://modrinth.com/mod/fabric-api) в `mods/`
4. Запусти Minecraft

## Настройка

1. Нажми **J** → настройки стрима
2. Выбери платформу (кнопка переключает)
3. Введи Stream Key
4. Настрой битрейт и разрешение
5. Нажми «Начать стрим» или закрой и нажми **K**

## Битрейт

| Интернет | Рекомендация |
|----------|-------------|
| Медленный (< 5 Мбит/с) | 1500-2000 кбит/с, 480p |
| Средний (5-20 Мбит/с) | 2500-4000 кбит/с, 720p |
| Быстрый (> 20 Мбит/с) | 4000-6000 кбит/с, оригинал |

## Разрешение

- **Оригинал** — текущее разрешение экрана
- **720p** — 1280x720
- **480p** — 854x480

## Звук

Автоматический захват звука из игры через PulseAudio (Linux).
Без PulseAudio — стрим без звука.

## Требования

- Minecraft 1.16.5
- Fabric Loader 0.11+
- Fabric API 0.42.0+1.16
- Java 8+
- FFmpeg (обязательно)

## Лицензия

VizorVerze
