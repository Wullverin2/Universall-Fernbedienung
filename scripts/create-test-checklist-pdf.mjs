import fs from "node:fs";
import path from "node:path";

const outputPath = path.resolve("docs", "Universal-Fernbedienung-Pruefliste.pdf");

const sections = [
  {
    title: "1. Vorbereitung",
    items: [
      "Handy ist im gleichen WLAN wie der zu testende TV.",
      "Gäste-WLAN, VPN und mobile Daten sind für den Test ausgeschaltet oder stören nicht.",
      "TV ist eingeschaltet und vollständig hochgefahren.",
      "Am TV sind Mobilgeräte-, Remote- oder Smart-Center-Freigaben aktiviert, falls vorhanden.",
      "App \"Universal Fernbedienung\" startet ohne Absturz.",
      "Unter Downloads liegt die aktuelle APK Universal-Fernbedienung-debug.apk."
    ]
  },
  {
    title: "2. Gerätescan",
    items: [
      "In der App auf \"Scannen\" tippen.",
      "Samsung-TV wird gefunden, falls im Netzwerk vorhanden.",
      "LG-webOS-TV wird gefunden, falls im Netzwerk vorhanden.",
      "Nabo/Vestel-TV wird gefunden, falls im Netzwerk vorhanden.",
      "Gefundene Geräte erscheinen im Dropdown.",
      "Gerät im Dropdown auswählen und mit \"Auswahl bestätigen\" aktivieren.",
      "\"Status aktualisieren\" zeigt eine sinnvolle IP und einen Port."
    ]
  },
  {
    title: "3. Samsung-TV testen",
    items: [
      "Beim ersten Verbinden erscheint am TV die Zulassen-Abfrage, falls noch kein Token vorhanden ist.",
      "Lauter und Leiser reagieren.",
      "Mute reagiert.",
      "Steuerkreuz reagiert: Hoch, Runter, Links, Rechts, OK.",
      "Home, Zurück, Menü, Source und Guide reagieren.",
      "Ziffernblock 0 bis 9 reagiert.",
      "TXT/Teletext-Taste wird gesendet.",
      "Ausschalten funktioniert.",
      "Einschalten per Wake-on-LAN funktioniert.",
      "App-Buttons starten die passenden Apps, soweit am TV unterstützt."
    ]
  },
  {
    title: "4. LG-webOS-TV testen",
    items: [
      "LG-TV wird nach dem Scan als \"LG webOS\" angezeigt.",
      "Beim ersten Verbinden erscheint am TV eine Pairing-Abfrage, falls nötig.",
      "Lauter, Leiser und Mute reagieren.",
      "Steuerkreuz und OK reagieren.",
      "Home, Zurück, Source, Guide und Info reagieren.",
      "Ausschalten funktioniert.",
      "Einschalten funktioniert nur dann, wenn Wake-on-LAN oder Wake-on-WiFi am TV aktiv ist und eine MAC-Adresse bekannt ist.",
      "Diagnose zeigt Modell, Firmware oder Netzwerkinfos, falls der TV diese liefert."
    ]
  },
  {
    title: "5. Nabo/Vestel-TV testen",
    items: [
      "Nabo/Vestel-TV wird nach dem Scan als \"Nabo / Vestel\" angezeigt.",
      "Wenn der TV nicht gefunden wird: prüfen, ob Handy und TV wirklich im gleichen WLAN sind.",
      "Wenn vorhanden: am TV \"Virtual Remote\", \"Smart Center\" oder Mobilgeräte-Freigabe aktivieren.",
      "Lauter, Leiser und Mute reagieren.",
      "Steuerkreuz und OK reagieren.",
      "Menü, Zurück, Source, Guide und Info reagieren.",
      "Ziffernblock 0 bis 9 reagiert.",
      "TXT/Teletext-Taste reagiert.",
      "Ausschalten funktioniert.",
      "Einschalten funktioniert nur dann, wenn Wake-on-LAN/Wake-on-WiFi am TV aktiv ist und eine MAC-Adresse bekannt ist.",
      "Quellen-Taste öffnet oder wechselt die Eingangsquelle.",
      "App-Starts testen, aber beachten: Paketnamen können je nach Firmware abweichen."
    ]
  },
  {
    title: "6. Netzwerkfälle testen",
    items: [
      "Test im normalen Heim-WLAN durchführen.",
      "Test in einem anderen WLAN mit anderem IP-Bereich durchführen.",
      "Prüfen, ob der Scanner den neuen IP-Bereich des Handys verwendet.",
      "Falls kein Gerät gefunden wird: Router prüfen, ob Multicast/Broadcast im WLAN blockiert wird.",
      "Falls TV im Gäste-WLAN hängt: Test im normalen WLAN wiederholen."
    ]
  },
  {
    title: "7. Fehlerprotokoll",
    items: [
      "TV-Modell notieren.",
      "TV-IP notieren.",
      "Handy-IP oder WLAN-Name notieren.",
      "Gefundener Gerätetyp in der App notieren.",
      "Welche Taste oder Funktion nicht reagiert hat notieren.",
      "Screenshot der Diagnose machen, falls möglich.",
      "Uhrzeit des Tests notieren."
    ]
  },
  {
    title: "8. Erfolgreich, wenn",
    items: [
      "App startet stabil.",
      "Scanner findet Geräte im aktuellen WLAN.",
      "Gerät kann per Dropdown ausgewählt werden.",
      "Mindestens Lautstärke, Steuerkreuz, OK und Power-Off funktionieren.",
      "APK liegt nach dem Deploy im Download-Ordner des S23 Ultra."
    ]
  }
];

const pageWidth = 595.28;
const pageHeight = 841.89;
const margin = 46;
const maxTextWidth = 490;
const lineHeight = 15;
const pages = [];
let current = [];
let y = pageHeight - margin;

function addPage() {
  if (current.length) pages.push(current);
  current = [];
  y = pageHeight - margin;
}

function escapePdf(text) {
  const raw = Buffer.from(text, "latin1").toString("latin1");
  return raw.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
}

function textOp(x, textY, font, size, text) {
  current.push(`BT /${font} ${size} Tf 1 0 0 1 ${x.toFixed(2)} ${textY.toFixed(2)} Tm (${escapePdf(text)}) Tj ET`);
}

function rectOp(x, rectY, w, h) {
  current.push(`${x.toFixed(2)} ${rectY.toFixed(2)} ${w.toFixed(2)} ${h.toFixed(2)} re S`);
}

function wrapText(text, maxChars) {
  const words = text.split(/\s+/);
  const lines = [];
  let line = "";
  for (const word of words) {
    const next = line ? `${line} ${word}` : word;
    if (next.length > maxChars && line) {
      lines.push(line);
      line = word;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines;
}

function ensureSpace(required) {
  if (y - required < margin) addPage();
}

function addTitle() {
  textOp(margin, y, "F2", 20, "Prüfliste Universal Fernbedienung");
  y -= 25;
  textOp(margin, y, "F1", 10, "Für Tests mit Samsung, LG webOS und Nabo/Vestel direkt auf dem Galaxy S23 Ultra.");
  y -= 22;
}

function addSection(title, items) {
  ensureSpace(42);
  textOp(margin, y, "F2", 13, title);
  y -= 18;

  for (const item of items) {
    const lines = wrapText(item, 88);
    ensureSpace(lines.length * lineHeight + 6);
    rectOp(margin, y - 3, 9, 9);
    textOp(margin + 17, y - 1, "F1", 10.5, lines[0]);
    y -= lineHeight;
    for (const extra of lines.slice(1)) {
      textOp(margin + 17, y - 1, "F1", 10.5, extra);
      y -= lineHeight;
    }
    y -= 3;
  }
  y -= 8;
}

addTitle();
for (const section of sections) {
  addSection(section.title, section.items);
}
if (current.length) pages.push(current);

const objects = [];
function addObject(value) {
  objects.push(value);
  return objects.length;
}

const catalogId = addObject("");
const pagesId = addObject("");
const fontRegularId = addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
const fontBoldId = addObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
const pageIds = [];

for (const ops of pages) {
  const stream = ops.join("\n");
  const contentId = addObject(`<< /Length ${Buffer.byteLength(stream, "latin1")} >>\nstream\n${stream}\nendstream`);
  const pageId = addObject(`<< /Type /Page /Parent ${pagesId} 0 R /MediaBox [0 0 ${pageWidth} ${pageHeight}] /Resources << /Font << /F1 ${fontRegularId} 0 R /F2 ${fontBoldId} 0 R >> >> /Contents ${contentId} 0 R >>`);
  pageIds.push(pageId);
}

objects[catalogId - 1] = `<< /Type /Catalog /Pages ${pagesId} 0 R >>`;
objects[pagesId - 1] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(" ")}] /Count ${pageIds.length} >>`;

const chunks = ["%PDF-1.4\n%\xE2\xE3\xCF\xD3\n"];
const offsets = [0];
let cursor = Buffer.byteLength(chunks[0], "latin1");

objects.forEach((obj, index) => {
  offsets.push(cursor);
  const chunk = `${index + 1} 0 obj\n${obj}\nendobj\n`;
  chunks.push(chunk);
  cursor += Buffer.byteLength(chunk, "latin1");
});

const xrefOffset = cursor;
let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
for (let i = 1; i < offsets.length; i += 1) {
  xref += `${String(offsets[i]).padStart(10, "0")} 00000 n \n`;
}
chunks.push(xref);
chunks.push(`trailer\n<< /Size ${objects.length + 1} /Root ${catalogId} 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, Buffer.from(chunks.join(""), "latin1"));
console.log(outputPath);
