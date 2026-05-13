# Prüfliste für Universal Fernbedienung

Diese Liste ist für Tests mit dem Galaxy S23 Ultra gedacht. Hake die Punkte der Reihe nach ab und notiere bei Fehlern möglichst TV-Modell, IP-Adresse, WLAN-Name und die genaue Aktion.

## 1. Vorbereitung

- [ ] Handy ist im gleichen WLAN wie der zu testende TV.
- [ ] Gäste-WLAN, VPN und mobile Daten sind für den Test ausgeschaltet oder stören nicht.
- [ ] TV ist eingeschaltet und vollständig hochgefahren.
- [ ] Am TV sind Mobilgeräte-, Remote- oder Smart-Center-Freigaben aktiviert, falls vorhanden.
- [ ] App "Universal Fernbedienung" startet ohne Absturz.
- [ ] Unter Downloads liegt die aktuelle APK `Universal-Fernbedienung-debug.apk`.

## 2. Gerätescan

- [ ] In der App auf "Scannen" tippen.
- [ ] Samsung-TV wird gefunden, falls im Netzwerk vorhanden.
- [ ] LG-webOS-TV wird gefunden, falls im Netzwerk vorhanden.
- [ ] Nabo/Vestel-TV wird gefunden, falls im Netzwerk vorhanden.
- [ ] Gefundene Geräte erscheinen im Dropdown.
- [ ] Gerät im Dropdown auswählen und mit "Auswahl bestätigen" aktivieren.
- [ ] "Status aktualisieren" zeigt eine sinnvolle IP und einen Port.

## 3. Samsung-TV testen

- [ ] Beim ersten Verbinden erscheint am TV die Zulassen-Abfrage, falls noch kein Token vorhanden ist.
- [ ] Lauter und Leiser reagieren.
- [ ] Mute reagiert.
- [ ] Steuerkreuz reagiert: Hoch, Runter, Links, Rechts, OK.
- [ ] Home, Zurück, Menü, Source und Guide reagieren.
- [ ] Ziffernblock 0 bis 9 reagiert.
- [ ] TXT/Teletext-Taste wird gesendet.
- [ ] Ausschalten funktioniert.
- [ ] Einschalten per Wake-on-LAN funktioniert.
- [ ] App-Buttons starten die passenden Apps, soweit am TV unterstützt.

## 4. LG-webOS-TV testen

- [ ] LG-TV wird nach dem Scan als "LG webOS" angezeigt.
- [ ] Beim ersten Verbinden erscheint am TV eine Pairing-Abfrage, falls nötig.
- [ ] Lauter, Leiser und Mute reagieren.
- [ ] Steuerkreuz und OK reagieren.
- [ ] Home, Zurück, Source, Guide und Info reagieren.
- [ ] Ausschalten funktioniert.
- [ ] Einschalten funktioniert nur dann, wenn Wake-on-LAN oder Wake-on-WiFi am TV aktiv ist und eine MAC-Adresse bekannt ist.
- [ ] Diagnose zeigt Modell, Firmware oder Netzwerkinfos, falls der TV diese liefert.

## 5. Nabo/Vestel-TV testen

- [ ] Nabo/Vestel-TV wird nach dem Scan als "Nabo / Vestel" angezeigt.
- [ ] Wenn der TV nicht gefunden wird: prüfen, ob Handy und TV wirklich im gleichen WLAN sind.
- [ ] Wenn vorhanden: am TV "Virtual Remote", "Smart Center" oder Mobilgeräte-Freigabe aktivieren.
- [ ] Lauter, Leiser und Mute reagieren.
- [ ] Steuerkreuz und OK reagieren.
- [ ] Menü, Zurück, Source, Guide und Info reagieren.
- [ ] Ziffernblock 0 bis 9 reagiert.
- [ ] TXT/Teletext-Taste reagiert.
- [ ] Ausschalten funktioniert.
- [ ] Einschalten funktioniert nur dann, wenn Wake-on-LAN/Wake-on-WiFi am TV aktiv ist und eine MAC-Adresse bekannt ist.
- [ ] Quellen-Taste öffnet oder wechselt die Eingangsquelle.
- [ ] App-Starts testen, aber beachten: Paketnamen können je nach Firmware abweichen.

## 6. Netzwerkfälle testen

- [ ] Test im normalen Heim-WLAN durchführen.
- [ ] Test in einem anderen WLAN mit anderem IP-Bereich durchführen.
- [ ] Prüfen, ob der Scanner den neuen IP-Bereich des Handys verwendet.
- [ ] Falls kein Gerät gefunden wird: Router prüfen, ob Multicast/Broadcast im WLAN blockiert wird.
- [ ] Falls TV im Gäste-WLAN hängt: Test im normalen WLAN wiederholen.

## 7. Fehlerprotokoll

- [ ] TV-Modell notieren.
- [ ] TV-IP notieren.
- [ ] Handy-IP oder WLAN-Name notieren.
- [ ] Gefundener Gerätetyp in der App notieren.
- [ ] Welche Taste oder Funktion nicht reagiert hat notieren.
- [ ] Screenshot der Diagnose machen, falls möglich.
- [ ] Uhrzeit des Tests notieren.

## 8. Erfolgreich, wenn

- [ ] App startet stabil.
- [ ] Scanner findet Geräte im aktuellen WLAN.
- [ ] Gerät kann per Dropdown ausgewählt werden.
- [ ] Mindestens Lautstärke, Steuerkreuz, OK und Power-Off funktionieren.
- [ ] APK liegt nach dem Deploy im Download-Ordner des S23 Ultra.
