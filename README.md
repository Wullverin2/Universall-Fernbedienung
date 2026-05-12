# Universal Fernbedienung

Android-App zur lokalen Steuerung kompatibler Smart-TVs im Heimnetz ohne zusätzliche Bridge.

## Aktueller Stand

Die App unterstützt derzeit:

- Samsung Smart TVs mit lokalem Remote-WebSocket und Wake-on-LAN
- LG webOS TVs der letzten Jahre über lokale webOS-Verbindungen
- Nabo / Vestel TVs mit TiVo-Basis als experimentellen Modus

## Funktionen

- TV-Geräte im Heimnetz scannen
- erkannte Geräte lokal speichern
- Gerät über Dropdown auswählen und aktiv schalten
- Ein- und Ausschalten
- Navigation, Lautstärke, Kanal, Guide, Info, Source
- Ziffernblock und Teletext-Taste
- Quellenwechsel
- App-Starts
- Diagnose-Ausgabe

## Hinweise zur Unterstützung

### Samsung

Samsung ist aktuell der am besten ausgebaute Modus.

- Wake-on-LAN für Einschalten
- WebSocket-Steuerung für Tasten
- App-Starts lokal

### LG webOS

LG wird lokal angesprochen. Je nach Gerät kann beim ersten Zugriff ein Pairing-Dialog am TV erscheinen.

### Nabo / Vestel mit TiVo

Dieser Modus ist aktuell experimentell.

- Grundlegende Tasten haben die besten Chancen
- Quellen, Apps und Spezialfunktionen können je nach Gerät abweichen oder nicht reagieren

## Build

Voraussetzungen:

- Android Studio
- Android SDK passend zum Projekt
- aktiviertes USB-Debugging für Installationen per ADB

Build:

```bat
gradlew.bat assembleDebug
```

APK:

- `app/build/outputs/apk/debug/app-debug.apk`

## Installation per ADB

```bat
adb uninstall de.craftplay.universalremote
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Projektstruktur

- `app/src/main/java/de/craftplay/samsungtvbridge/MainActivity.kt`
  - Oberfläche
- `app/src/main/java/de/craftplay/samsungtvbridge/ui/MainViewModel.kt`
  - UI-Logik
- `app/src/main/java/de/craftplay/samsungtvbridge/data/DirectTvStore.kt`
  - lokale Gerätespeicherung und Mappings
- `app/src/main/java/de/craftplay/samsungtvbridge/data/SamsungDirectTvClient.kt`
  - direkte TV-Kommunikation für Samsung, LG und TiVo/Vestel

## Wichtig

- Die frühere Senderlisten-Funktion ist bewusst entfernt.
- Die App arbeitet lokal im Heimnetz.
- Für Samsung ist die Unterstützung derzeit am stabilsten.
