# Samsung TV Local Bridge Android App

Diese Android-App ist eine lokale Fernbedienung fuer die bestehende `samsung-tv-local-bridge`.

## Funktionen

- Bridge-URL lokal speichern
- Status des aktiven TVs anzeigen
- Samsung-TVs laden, scannen und auswaehlen
- Ein-/Ausschalten
- Lautstaerke und Navigation
- Sender per Nummer oder Name
- Quellenwechsel
- App-Starts
- Diagnose-Ausgabe

## Voraussetzungen

- Android Studio Iguana oder neuer
- Android SDK fuer API 35
- Laufende Bridge im Heimnetz, z. B. `http://192.168.0.103:8088`

## Starten

1. Ordner `android-app/` in Android Studio oeffnen
2. Gradle-Sync laufen lassen
3. App auf echtes Android-Geraet oder Emulator installieren
4. In der App die Bridge-URL eintragen und verbinden

## Wichtige Hinweise

- Die App spricht direkt per HTTP mit der Bridge.
- Fuer lokale IP-Adressen ist `usesCleartextTraffic="true"` gesetzt.
- Ein Emulator kann dein Heimnetz je nach Setup eingeschraenkt sehen. Ein echtes Geraet im WLAN ist fuer die ersten Tests meist einfacher.
