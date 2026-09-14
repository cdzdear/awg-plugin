# AWG-Telegram Fork
## Telegram-форк со встроенным AmneziaWG туннелем (без системного VPN)

[![Build AWG Library](https://github.com/YOUR_USERNAME/awg-telegram-fork/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/awg-telegram-fork/actions/workflows/build.yml)

---

## 🎯 Что это

Модифицированный **AyuGram4A** (форк Telegram) со встроенным туннелем **AmneziaWG (AWG3)**.

### Ключевые особенности:
- ✅ **Нет системного VPN** — нет иконки замка в статус-баре Android  
- ✅ **Нет конфликтов** с другими VPN-приложениями  
- ✅ **Нет разрешения BIND_VPN_SERVICE** в манифесте  
- ✅ **Обфускация AWG3** — защита от DPI блокировок  
- ✅ **Автостарт** при запуске Telegram  
- ✅ **Нороутованные устройства** — не нужен root  

---

## 🏗️ Архитектура

```
Telegram App (Java/Kotlin)
    │
    │ SOCKS5 (127.0.0.1:RANDOM_PORT)
    │ ← автоматически настраивается
    ▼
AWG Module (libawg.so — Go userspace)
    │
    │ amneziawg-go (форк WireGuard)
    │ + gVisor netstack (userspace TCP/IP)
    │ + встроенный SOCKS5 сервер
    │
    ▼ зашифрованные UDP пакеты (AWG3 обфускация)
    
Internet → AmneziaWG Server → Telegram DC серверы
```

**Почему без VpnService?**  
Вместо захвата всего трафика через TUN (требует VpnService), мы используем userspace TCP/IP стек (`gVisor netstack`) из `amneziawg-go`. Он создаёт виртуальную сеть в памяти приложения и выставляет SOCKS5 прокси. Telegram умеет работать через SOCKS5 нативно — это официальная встроенная функция.

---

## 📁 Структура проекта

```
awg-telegram-fork/
├── .github/workflows/build.yml   # GitHub Actions CI
├── awg-go-lib/                   # Go AWG библиотека
│   ├── main.go                   # CGo экспорты + SOCKS5 сервер
│   ├── config.go                 # Парсер WG/AWG конфигов
│   ├── go.mod                    # Go зависимости
│   ├── build_android.sh          # Сборка для Linux/WSL
│   └── build_android_windows.bat # Сборка для Windows
├── android-patch/                # Патчи для AyuGram4A
│   ├── java/org/telegram/
│   │   ├── awg/
│   │   │   ├── AWGLib.java       # JNI мост к libawg.so
│   │   │   └── AWGManager.java   # Менеджер туннеля
│   │   └── ui/awg/
│   │       └── AWGSettingsActivity.java  # UI настроек
│   └── patches/
│       ├── 01_ApplicationLoader.patch
│       └── 02_SettingsActivity.patch
└── setup.bat                     # Автоматическая установка
```

---

## 🚀 Быстрый старт

### Вариант A: GitHub Actions (рекомендуется для Windows)

1. **Fork этого репозитория** на GitHub
2. **Push** — CI автоматически соберёт `libawg.so`
3. Скачайте артефакт `libawg-android-libs` из вкладки Actions
4. Положите `.so` файлы в `AyuGram4A/TMessagesProj/src/main/jniLibs/`
5. Запустите полную сборку APK через Actions (включите `build_apk = true`)

### Вариант B: Ручная сборка (Linux/WSL/macOS)

#### 1. Установка зависимостей
```bash
# Go 1.21+
wget https://go.dev/dl/go1.21.0.linux-amd64.tar.gz
sudo tar -C /usr/local -xzf go1.21.0.linux-amd64.tar.gz
export PATH=$PATH:/usr/local/go/bin

# Android NDK r25c
wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
unzip android-ndk-r25c-linux.zip
export ANDROID_NDK_ROOT=$PWD/android-ndk-r25c
```

#### 2. Сборка AWG библиотеки
```bash
cd awg-go-lib
go mod download
./build_android.sh ../android-patch/jniLibs
```

#### 3. Клонирование и патчинг AyuGram
```bash
git clone --depth=1 --recurse-submodules \
    https://github.com/AyuGram/AyuGram4A.git

# Копирование AWG файлов
SRC="AyuGram4A/TMessagesProj/src/main/java/org/telegram"
mkdir -p "$SRC/awg" "$SRC/ui/awg"
cp android-patch/java/org/telegram/awg/*.java "$SRC/awg/"
cp android-patch/java/org/telegram/ui/awg/*.java "$SRC/ui/awg/"

# Копирование нативных библиотек
for ABI in arm64-v8a armeabi-v7a x86_64; do
    mkdir -p "AyuGram4A/TMessagesProj/src/main/jniLibs/$ABI"
    cp "android-patch/jniLibs/$ABI/libawg.so" \
       "AyuGram4A/TMessagesProj/src/main/jniLibs/$ABI/"
done
```

#### 4. Ручной патч ApplicationLoader.java
Откройте `AyuGram4A/TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`

Добавьте импорты:
```java
import org.telegram.awg.AWGLib;
import org.telegram.awg.AWGManager;
```

В метод `onCreate()` добавьте:
```java
// Initialize built-in AmneziaWG tunnel (no VpnService)
try {
    AWGLib.load(this);
    AWGManager.getInstance().init();
} catch (Exception e) {
    Log.e("AWGTelegram", "AWG init failed: " + e.getMessage());
}
```

#### 5. Добавление пункта в настройки (SettingsActivity.java)
Смотрите патч: `android-patch/patches/02_SettingsActivity.patch`

Найдите в `SettingsActivity.java` место где добавляются пункты меню и добавьте:
```java
// В методе rowsIds/updateRows:
awgRow = rowCount++;

// В onItemClick:
if (position == awgRow) {
    presentFragment(new AWGSettingsActivity());
    return;
}

// В адаптере:
if (position == awgRow) {
    TextCell textCell = (TextCell) view;
    AWGManager mgr = AWGManager.getInstance();
    textCell.setTextAndValue("Встроенный AWG VPN",
        mgr.isRunning() ? "🟢 Активен" : "⚫ Выключен", false);
}
```

#### 6. Сборка APK
```bash
cd AyuGram4A
./gradlew assembleAfatDebug
```

APK: `TMessagesProj/build/outputs/apk/afat/debug/*.apk`

---

## ⚙️ Конфигурация AWG

### Формат конфига (AWG3)
```ini
[Interface]
PrivateKey = <ваш приватный ключ>
Address = 10.0.0.2/32
DNS = 1.1.1.1

# Параметры обфускации AWG3 (опционально)
Jc = 120          # Количество junk-пакетов
Jmin = 23         # Мин. размер junk
Jmax = 911        # Макс. размер junk
S1 = 0            # Junk после Initiation
S2 = 0            # Junk после Response  
H1 = 1392786989   # Custom magic header 1
H2 = 397410492    # Custom magic header 2
H3 = 3264174472   # Custom magic header 3
H4 = 2015952236   # Custom magic header 4

[Peer]
PublicKey = <публичный ключ сервера>
Endpoint = YOUR-SERVER:51820
PersistentKeepalive = 25

# РЕКОМЕНДУЕТСЯ: только IP Telegram DC
AllowedIPs = 149.154.160.0/20, 91.108.4.0/22, 91.108.8.0/22, 91.108.56.0/22, 91.108.16.0/22, 91.105.192.0/23, 185.76.151.0/24

# Или весь трафик:
# AllowedIPs = 0.0.0.0/0, ::/0
```

### Настройка сервера (AmneziaVPN/AmneziaWG)
1. Установите [AmneziaVPN](https://github.com/amnezia-vpn/amnezia-client) на VPS
2. Создайте конфигурацию AWG3
3. Экспортируйте `.conf` файл
4. В Telegram: Настройки → Встроенный AWG → вставьте конфиг

---

## 🔧 Как работает SOCKS5 без VpnService

```
1. Go: amneziawg-go создаёт userspace netstack (gVisor)
       Нет /dev/tun, нет VpnService, нет root

2. Go: поверх netstack запускается SOCKS5 сервер
       Слушает на 127.0.0.1:RANDOM_PORT
       Принимает TCP соединения от Telegram

3. Java: AWGManager настраивает встроенный прокси Telegram
         SharedPreferences: proxy_enabled=true, proxy_ip=127.0.0.1
         SharedConfig.reloadConfig() применяет на лету

4. Telegram: автоматически использует SOCKS5 прокси
             Все MTProto соединения идут через 127.0.0.1:PORT

5. Go: каждый TCP пакет от Telegram шифруется AWG3
       Отправляется как зашифрованный UDP на ваш сервер
```

**Результат**: Telegram работает через AWG туннель, VPN-иконки нет, root не нужен.

---

## 📱 Требования

- Android 6.0+ (API 23+)
- Работает без root
- Не нужны системные разрешения VPN
- Совместимо с другими VPN-приложениями

---

## 📊 Известные ограничения

1. **Только трафик Telegram** туннелируется (не весь трафик устройства)
2. При `AllowedIPs = 0.0.0.0/0` — весь трафик через SOCKS5, но маршрутизирует только то, что Telegram направит через прокси (не другие приложения)
3. UDP-трафик Telegram (голосовые/видеозвонки) может идти напрямую — нужна дополнительная настройка

---

## 🤝 Contributing

PR приветствуются! Особенно:
- Поддержка UDP через SOCKS5 (для звонков)
- QR-код импорт конфига
- Kill-switch (блокировка при отключении туннеля)
- Статистика трафика в реальном времени
