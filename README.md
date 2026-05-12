# Universal Fernbedienung

Android-App zur lokalen Steuerung kompatibler Smart-TVs im Heimnetz ohne zusätzliche Bridge.

## Aktueller Stand

Die App unterstützt derzeit:

- Samsung Smart TVs mit lokalem Remote-WebSocket und Wake-on-LAN
- LG webOS TVs der letzten Jahre über lokale webOS-Verbindungen
- Nabo / Vestel TVs über SmartCenter-Erkennung und SmartCenter-Fernbedienungsbefehle

## Funktionen

- TV-Geräte im Heimnetz scannen
- erkannte Geräte lokal speichern
- Gerät über Dropdown auswählen und aktiv schalten
- Ein- und Ausschalten
- Navigation, Lautstärke, Kanal, Guide, Info, Source
- Ziffernblock und Teletext-Taste
- Quellenwechsel
- App-Starts, soweit das jeweilige TV-Modell lokale App-Kommandos akzeptiert
- Diagnose-Ausgabe

## Samsung

Samsung ist aktuell der am besten ausgebaute Modus.

- Wake-on-LAN für Einschalten
- WebSocket-Steuerung für Tasten
- App-Starts lokal
- Beim ersten Zugriff muss am TV eventuell eine Freigabe bestätigt werden

## LG webOS

LG wird lokal über webOS/SSAP angesprochen. Je nach Gerät kann beim ersten Zugriff ein Pairing-Dialog am TV erscheinen.

- Scanner erkennt LG über SSDP/UPnP und die typischen webOS-Ports 3000/3001
- nach dem Verbinden fragt die App Geräteinfos ab und speichert Modell, Firmware, webOS-SDK, Netzwerktyp und Wake-on-WiFi-Hinweise
- Einschalten ist nur möglich, wenn Wake-on-LAN beziehungsweise Wake-on-WiFi am TV erlaubt ist und eine MAC-Adresse bekannt ist

## Nabo / Vestel SmartCenter

Für Nabo/Vestel wurde die installierte Android-App `TV Smart Centre` nur für Interoperabilität untersucht. Die App nutzt bei passenden Geräten Vestel SmartCenter/SuperTVCommunicator statt reiner TiVo-Steuerung.

Die Universal-Fernbedienung sucht deshalb jetzt zusätzlich nach:

- DIAL/SSDP: `urn:dial-multiscreen-org:service:dial:1`
- UPnP/MediaRenderer-Antworten mit Vestel/Nabo/SmartCenter-Hinweisen
- Vestel-UDP-Discovery auf Port 4950 mit `vr_query_tv_version_782`
- Vestel-WebSocket auf Port 7681
- TiVo-IRCODE-Port 31339 nur noch als Fallback

Tasten werden bevorzugt als SmartCenter-XML an `Application-URL + SmartCenter` gesendet. Wenn das nicht klappt, probiert die App den Vestel-WebSocket auf Port 7681 und danach den TiVo-Fallback.

Wichtig: Nicht jedes Nabo/Vestel-Modell schaltet jede Funktion frei. App-Starts und Quellen können je nach Firmware andere Paketnamen oder Menüsequenzen benötigen.

## Scannen in anderen WLANs

Der Scanner ist nicht fest an `192.168.0.x` gebunden. Er ermittelt die lokalen IPv4-Netze des Smartphones und scannt diese Netze. Zusätzlich werden SSDP/UPnP- und Vestel-UDP-Antworten direkt übernommen, auch wenn sie außerhalb der geratenen Kandidatenliste liegen.

Wenn ein TV in einem Gäste-WLAN, VLAN oder anderen Layer-2-Netz hängt, werden Multicast und Broadcast oft vom Router blockiert. Dann kann kein lokaler Scanner zuverlässig Geräte finden.

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

## Deploy auf Galaxy S23 Ultra

Für die lokale Entwicklung gibt es ein Skript, das die Debug-APK baut, auf dem S23 Ultra installiert und danach eine Kopie in den Download-Ordner des Smartphones legt:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-s23.ps1
```

Die APK liegt danach auf dem Smartphone unter:

- `/sdcard/Download/Universal-Fernbedienung-debug.apk`

Für eine komplette Neuinstallation:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-s23.ps1 -Fresh
```

## Projektstruktur

- `app/src/main/java/de/craftplay/samsungtvbridge/MainActivity.kt`
  - Oberfläche
- `app/src/main/java/de/craftplay/samsungtvbridge/ui/MainViewModel.kt`
  - UI-Logik
- `app/src/main/java/de/craftplay/samsungtvbridge/data/DirectTvStore.kt`
  - lokale Gerätespeicherung und Mappings
- `app/src/main/java/de/craftplay/samsungtvbridge/data/SamsungDirectTvClient.kt`
  - direkte TV-Kommunikation für Samsung, LG und Nabo/Vestel

## Wichtig

- Die frühere Senderlisten-Funktion ist bewusst entfernt.
- Die App arbeitet lokal im Heimnetz.
- Für Samsung ist die Unterstützung derzeit am stabilsten.
- LG und Nabo/Vestel hängen stärker von Modell, Firmware, Wake-Einstellungen und lokalen Netzwerkfreigaben ab.
