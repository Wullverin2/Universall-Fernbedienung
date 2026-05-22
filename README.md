# Universal Fernbedienung

Android-App zur lokalen Steuerung kompatibler Smart-TVs im Heimnetz ohne zusaetzliche Bridge.

## Aktueller Stand

Die App unterstuetzt derzeit:

- Samsung Smart TVs mit lokalem Remote-WebSocket und Wake-on-LAN
- LG webOS TVs der letzten Jahre ueber lokale webOS-Verbindungen
- Nabo / Vestel TVs ueber SmartCenter-Erkennung und SmartCenter-Fernbedienungsbefehle

## Funktionen

- TV-Geraete im Heimnetz scannen
- erkannte Geraete lokal speichern
- Geraet ueber Dropdown auswaehlen und aktiv schalten
- Ein- und Ausschalten
- Navigation, Lautstaerke, Kanal, Guide, Info, Source
- Ziffernblock und Teletext-Taste
- Quellenwechsel
- App-Starts, soweit das jeweilige TV-Modell lokale App-Kommandos akzeptiert
- Diagnose-Ausgabe mit Plattform, Capabilities, letztem Fehler und letztem erfolgreichen Befehl
- persistentes Diagnose-Log mit Zeitstempel, das per ADB vom Smartphone ausgelesen werden kann

## Samsung

Samsung ist aktuell der am besten ausgebaute Modus.

- Wake-on-LAN fuer Einschalten
- WebSocket-Steuerung fuer Tasten
- App-Starts lokal
- Beim ersten Zugriff muss am TV eventuell eine Freigabe bestaetigt werden

## LG webOS

LG wird lokal ueber webOS/SSAP angesprochen. Je nach Geraet kann beim ersten Zugriff ein Pairing-Dialog am TV erscheinen.

- Scanner erkennt LG ueber SSDP/UPnP und die typischen webOS-Ports 3000/3001
- nach dem Verbinden fragt die App Geraeteinfos ab und speichert Modell, Firmware, webOS-SDK, Netzwerktyp und Wake-on-WiFi-Hinweise
- Einschalten ist nur moeglich, wenn Wake-on-LAN beziehungsweise Wake-on-WiFi am TV erlaubt ist und eine MAC-Adresse bekannt ist
- Pairing nutzt ein erweitertes Rechte-Manifest fuer geschuetzte Funktionen wie Pointer-/Tastensteuerung und App-Liste
- bei LG-Fehlern wie `401` wird der gespeicherte Client-Key verworfen, damit der TV beim naechsten Verbinden neu nach Pairing fragen kann
- Diagnose zeigt Pairing-Status, letzten Fehler und letzten erfolgreichen Befehl

## Nabo / Vestel SmartCenter

Fuer Nabo/Vestel wurde die installierte Android-App `TV Smart Centre` nur fuer Interoperabilitaet untersucht. Die App nutzt bei passenden Geraeten Vestel SmartCenter/SuperTVCommunicator statt reiner TiVo-Steuerung.

Die Universal-Fernbedienung sucht nach:

- DIAL/SSDP: `urn:dial-multiscreen-org:service:dial:1`
- UPnP/MediaRenderer-Antworten mit Vestel/Nabo/SmartCenter-Hinweisen
- Vestel-UDP-Discovery auf Port 4950 mit `vr_query_tv_version_782`
- gezielter Vestel-Handshake auf Port 4950 mit `vr_query_tv`, um bei manchen TVs den echten SmartCenter-Steuerport zu ermitteln
- validiertem SmartCenter-Endpunkt, meist `http://TV-IP:56789/apps/SmartCenter`
- Vestel-WebSocket auf Port 7681
- TiVo-IRCODE-Port 31339 nur noch als Fallback

Tasten werden bevorzugt als SmartCenter-XML an `Application-URL + SmartCenter` gesendet. Wenn das nicht klappt, probiert die App den Vestel-WebSocket auf Port 7681 und danach den TiVo-Fallback.

Der Scan legt Nabo/Vestel-Geraete nicht mehr allein wegen eines offenen Ports an. Ein Geraet muss ueber Vestel/SmartCenter-Hinweise, DIAL/UPnP oder einen validierten SmartCenter-Endpunkt plausibel sein. Niedrige HTTP-Statuszahlen wie `200` werden nicht mehr versehentlich als SmartCenter-Port gespeichert.

Wichtig: Nicht jedes Nabo/Vestel-Modell schaltet jede Funktion frei. App-Starts und Quellen koennen je nach Firmware andere Paketnamen oder Menue-Sequenzen benoetigen.

MAC-Adressen werden von Nabo/Vestel ueber SmartCenter nicht immer direkt geliefert. Die App versucht zusaetzlich, die MAC-Adresse aus Geraetebeschreibungen, UDP-Antworten und der ARP-Tabelle des Android-Geraets zu lernen. Wenn Android oder der Router diese Information nicht freigibt, bleibt die MAC unbekannt. Fuer normale Steuerung ist das egal; fuer Einschalten per Wake-on-LAN wird die MAC benoetigt.

Wenn ein Nabo/Vestel-TV im Handy-Hotspot steuerbar ist, im vorhandenen WLAN aber nur gefunden wird und nicht reagiert, liegt das meist am Netzwerk und nicht am TV-Code. Pruefe dann im Router:

- Gaeste-WLAN deaktivieren oder Handy und TV ins normale WLAN bringen
- AP-Isolation, Client-Isolation oder "WLAN-Geraete duerfen nicht miteinander kommunizieren" deaktivieren
- Multicast/UPnP/IGMP nicht blockieren
- 2,4-GHz- und 5-GHz-Geraete duerfen miteinander kommunizieren
- VLANs oder getrennte Mesh-/Repeater-Netze vermeiden

## Scannen in anderen WLANs

Der Scanner ist nicht fest an `192.168.0.x` gebunden. Er ermittelt die lokalen IPv4-Netze des Smartphones und scannt diese Netze. Zusaetzlich werden SSDP/UPnP- und Vestel-UDP-Antworten direkt uebernommen, auch wenn sie ausserhalb der geratenen Kandidatenliste liegen.

Wenn ein TV in einem Gaeste-WLAN, VLAN oder anderen Layer-2-Netz haengt, werden Multicast und Broadcast oft vom Router blockiert. Dann kann kein lokaler Scanner zuverlaessig Geraete finden.

## Build

Voraussetzungen:

- Android Studio
- Android SDK passend zum Projekt
- aktiviertes USB-Debugging fuer Installationen per ADB

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

Fuer die lokale Entwicklung gibt es ein Skript, das die Debug-APK baut, auf dem S23 Ultra installiert und danach eine Kopie in den Download-Ordner des Smartphones legt:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-s23.ps1
```

Die APK liegt danach auf dem Smartphone unter:

- `/sdcard/Download/Universal-Fernbedienung-debug.apk`

Fuer eine komplette Neuinstallation:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy-s23.ps1 -Fresh
```

## Diagnose-Log vom S23 auslesen

Die App schreibt waehrend der Nutzung ein persistentes Log in den App-Speicher:

- interne Datei: `files/universal-remote-diagnostic.log`
- gespiegelte ADB-Datei: `/sdcard/Android/data/de.craftplay.universalremote/files/universal-remote-diagnostic.log`

Das Log enthaelt Zeitstempel, Info-/Fehlerstatus, die ausgefuehrte Aktion, die geplante Funktion sowie den aktiven TV mit IP und Geraetetyp. Token und Client-Keys werden maskiert.

Auslesen mit angeschlossenem S23:

```powershell
$adb = "C:\Users\speed_pctca6b\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s R5CW82ZH8SB shell run-as de.craftplay.universalremote cat files/universal-remote-diagnostic.log
```

Alternativ kann die gespiegelte Datei kopiert werden:

```powershell
$adb = "C:\Users\speed_pctca6b\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb -s R5CW82ZH8SB pull /sdcard/Android/data/de.craftplay.universalremote/files/universal-remote-diagnostic.log .
```

In der App zeigt die Log-Karte die letzten Eintraege, die ADB-Befehle, einen Button zum Neuladen und einen Button zum Leeren der Logdatei.

## Pruefliste

Eine Test-Pruefliste liegt im Projekt unter:

- `docs/test-checklist.md`
- `docs/Universal-Fernbedienung-Pruefliste.pdf`

PDF neu erzeugen:

```powershell
node scripts\create-test-checklist-pdf.mjs
```

## Projektstruktur

- `app/src/main/java/de/craftplay/samsungtvbridge/MainActivity.kt`
  - Oberflaeche
- `app/src/main/java/de/craftplay/samsungtvbridge/ui/MainViewModel.kt`
  - UI-Logik
- `app/src/main/java/de/craftplay/samsungtvbridge/data/DirectTvStore.kt`
  - lokale Geraetespeicherung und Mappings
- `app/src/main/java/de/craftplay/samsungtvbridge/data/SamsungDirectTvClient.kt`
  - direkte TV-Kommunikation fuer Samsung, LG und Nabo/Vestel
- `app/src/main/java/de/craftplay/samsungtvbridge/data/PersistentAppLogger.kt`
  - persistente Diagnose-Logdatei fuer App-Nutzung und ADB-Auswertung

## Wichtig

- Die fruehere Senderlisten-Funktion ist bewusst entfernt.
- Die App arbeitet lokal im Heimnetz.
- Fuer Samsung ist die Unterstuetzung derzeit am stabilsten.
- LG und Nabo/Vestel haengen staerker von Modell, Firmware, Wake-Einstellungen und lokalen Netzwerkfreigaben ab.
