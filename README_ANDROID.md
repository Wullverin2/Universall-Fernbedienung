# Universal Fernbedienung Android App

Diese Android-App steuert kompatible Smart-TVs direkt im lokalen Netzwerk. Eine separate Node-Bridge ist fuer diese App nicht notwendig.

## Funktionen

- Samsung-, LG-webOS- und Nabo/Vestel-TVs im aktuellen WLAN scannen
- erkannte TVs lokal speichern
- TV per Dropdown auswaehlen und aktiv schalten
- Ein-/Ausschalten, Navigation, Lautstaerke, Kanal, Guide, Info, Source
- Ziffernblock und Teletext
- Quellenwechsel und App-Starts, soweit das TV-Modell lokale Kommandos akzeptiert
- Diagnoseanzeige mit Plattform, Pairing-Status, letztem Request und letztem Fehler
- persistentes Diagnose-Log mit Zeitstempel
- herstellerabhaengiges Keymapping statt fest verdrahteter Samsung-Tastencodes
- lokale Lerndatenbank mit JSON-Export fuer spaetere Modell-Auswertung

## Keymapping und App-Starts

Die App verwendet intern neutrale Tasten wie `HOME`, `OK`, `VOLUME_UP`, `TELETEXT` und `DIGIT_1`. Erst beim Senden wird daraus der passende Hersteller-Code:

- Samsung: Samsung-Remote-Keys wie `KEY_HOME`; App-Start mit per `sdb vd_applist` ausgelesenen IDs, HTTP-Endpunkt und danach WebSocket `ed.apps.launch` als zweiter Startweg
- LG webOS: SSAP-Kommandos und Pointer-Input-Socket
- Nabo/Vestel: SmartCenter-Buttons und TiVo-IRCODE-Fallback

Samsung-App-Buttons nutzen die App-ID-Reihenfolge aus dem MU-TV: numerische Samsung-ID, Tizen-App-ID und Paket-ID. Ein HTTP-OK wird nicht mehr als alleiniger Beweis gewertet; die WebSocket-Launch-Variante wird danach ebenfalls gesendet.

## LG-Pairing

Ein gespeicherter LG-webOS-Client-Key wird nicht mehr automatisch geloescht, wenn ein Befehl fehlschlaegt oder LG `401/Unauthorized` meldet. Das Pairing bleibt bestehen. Geloescht wird der Key nur, wenn das LG-Geraet aus der Geraeteliste entfernt wird.

## Lerndatenbank

Jede Aktion wird lokal protokolliert:

- Geraetetyp, Plattform, Modell und IP
- Aktion und normalisierte Eingabe
- Erfolg oder Fehler
- Methode, Port, App-ID und Fehlertext

Die Karte `Lerndatenbank` zeigt eine Zusammenfassung nach Modell und Eingabe. Mit `JSON exportieren` koennen die Daten spaeter fuer eine zentrale Kompatibilitaetsdatenbank genutzt werden. Tokens und Client-Keys werden nicht exportiert.

## Diagnose-Log

Die App schreibt jede Aktion und jeden Fehler in:

- `files/universal-remote-diagnostic.log`
- `/sdcard/Android/data/de.craftplay.universalremote/files/universal-remote-diagnostic.log`

Auslesen per ADB:

```powershell
$adb = "C:\Users\speed_pctca6b\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s R5CW82ZH8SB shell run-as de.craftplay.universalremote cat files/universal-remote-diagnostic.log
```

Oder als Datei kopieren:

```powershell
$adb = "C:\Users\speed_pctca6b\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s R5CW82ZH8SB pull /sdcard/Android/data/de.craftplay.universalremote/files/universal-remote-diagnostic.log .
```

Token und LG-Client-Keys werden vor dem Schreiben maskiert.

## Voraussetzungen

- Android Studio
- Android SDK API 35
- echtes Android-Geraet im selben WLAN wie der TV
- USB-Debugging fuer ADB-Installation und Logauswertung

## Build und Installation

```powershell
.\gradlew.bat assembleDebug
powershell -ExecutionPolicy Bypass -File scripts\deploy-s23.ps1
```

Die APK wird auf dem S23 installiert und nach `/sdcard/Download/Universal-Fernbedienung-debug.apk` kopiert.

## Hinweise

- LG webOS benoetigt beim ersten Zugriff oft eine Pairing-Bestaetigung am TV.
- Nabo/Vestel-Steuerung haengt stark von Firmware und Netzwerkfreigaben ab.
- Wenn ein TV im Handy-Hotspot steuerbar ist, im Router-WLAN aber nicht, blockiert der Router oft Multicast, Broadcast oder lokale Client-Kommunikation.
