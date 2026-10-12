# CoinShop – Coins, Server-Shop, Spieler-Shop, Glücksrad und Investitionen

Ein Plugin für **Paper 26.3** (Java 25).

## Was kann das Plugin?

| Befehl | Wirkung |
|---|---|
| `/shop` | Öffnet das Hauptmenü: **Server-Shop**, **Spieler-Shop**, **Glücksrad**, **Investitionen** (und zeigt das Shop-Konto) |
| `/shop sell [Preis]` | Bietet das Item in deiner Hand im Spieler-Shop an (Alternative zum Menü) |
| `/coins [Spieler]` | Zeigt deinen Kontostand oder den eines anderen Spielers |
| `/pay [Spieler] [Betrag]` | Überweist Coins an einen anderen Spieler |
| `/baltop` | Rangliste der 10 reichsten Spieler |
| `/coinsadmin give\|take\|set [Spieler] [Betrag]` | Coins verwalten (nur Admins) |
| `/coinsadmin resetshop` | Server-Shop sofort neu auslosen, ohne Einkommen (nur Admins) |
| `/coinsadmin reload` | `config.yml` und `messages.yml` neu laden (nur Admins) |

### Coins verdienen
- **Erfolge nach Schwierigkeit:** leicht 10 / mittel 30 / schwer 100 / sehr schwer 250 / extrem 600 Coins. Die Stufe richtet sich nach der Erfolgsart (Aufgabe/Ziel/Herausforderung); besonders schwere Erfolge (z. B. Enderdrachen besiegen, Wither, alle Mobs töten) stehen in `config.yml` unter `achievements.tiers`. Einzelne Erfolge lassen sich unter `overrides` anpassen.
- **Börse** (Investitionen -> Börse): Ein Kurs für alle (1-100 Coins), ändert sich täglich zusammen mit dem Shop-Wechsel. Man kauft mit beliebig vielen Coins ganze Anteile und verkauft sie später zum dann gültigen Kurs. 10 % Gebühr bei Kauf und Verkauf (`stock.fee-percent`), die geht aufs Shop-Konto. Kursgewinne sind neu geschaffene Coins, Verluste verschwinden.
- **Tägliches Einkommen:** 10 Coins für jeden Spieler (auch offline), ausgezahlt zusammen mit dem Shop-Wechsel um 12:00 Uhr. Dazu kommt das Einkommen aus Investitions-Sets. Diese Coins werden neu erzeugt.
- **Verkaufen** im Spieler-Shop, **Gewinnen** am Glücksrad.
- Kein Startguthaben.

### Server-Shop
- 10 zufällige Angebote, **feste Preise**, täglich um 12:00 Uhr (Europe/Berlin) komplett neu.
- Es können **alle Items auftauchen, die es im Vanilla-Survival gibt** (Items wie Command-Blöcke, Spawn-Eier, Bedrock usw. sind ausgeschlossen, siehe `exclude` in der config).
- Dazu **verzauberte Rüstungsteile** (12 % pro Platz) und **Verzauberungsbücher** (18 % pro Platz).
- Die seltensten Items kosten bis zu **100.000 Coins** (Netheritblock 100.000, Elytra 37.500 ...). Das Drachenei gibt es im Shop nicht, alle anderen sind daran angelehnt. Teure Items erscheinen seltener.
- Menge pro Kauf ist zufällig (billige Items in großen Mengen, teure einzeln). Jeder Spieler darf jedes Angebot 3x pro Tag kaufen.

### Spieler-Shop
- Spieler bieten Items zu einem frei gewählten Gesamtpreis an. Der Verkäufer bekommt die Coins sofort (auch offline), abzüglich Steuer.
- **Verkaufs-Slots müssen gekauft werden:** der erste Slot ist gratis, der nächste kostet 5 Coins, jeder weitere kostet das Doppelte (10, 20, 40 ...), höchstens 32 Slots. Kauf unter *Spieler-Shop > Meine Angebote*.
- **Schutz gegen Zwischenlager-Missbrauch:** Slots kosten Coins, Einstellgebühr (5 %, nie erstattet), Angebote laufen nach 48 h ab, Preisgrenzen, Verkaufssteuer, Sperrliste.

### Konto des Shops
Alle Einnahmen des Shops landen hier und werden im Hauptmenü angezeigt:
Käufe im Server-Shop, Einstellgebühren, Verkaufssteuern, Slot-Käufe, Glücksrad-Einsätze und Käufe von Investitions-Items.

### Glücksrad
- Eine Drehung kostet 5 Coins (einstellbar). Die Chance auf irgendeinen Gewinn ist 1 zu 100, auf den Jackpot 1 zu 1000, der Einsatz geht aufs Shop-Konto.
- **Jackpot = das gesamte Shop-Konto**, Chance 1 zu 1000.
- Dazu Trostpreise (Niete, 25, 50, 75, 100, 250, 500, 1000 Coins), bezahlt vom Shop-Konto (höchstens so viel, wie darauf ist).
- Die Gewinnchancen sieht man im Menü. Alles ist in der `config.yml` einstellbar.

### Investitionen (Sets)
- **25 Sets mit je 3 Items.** Teuerstes Set: 1000 Coins pro Item und +1000 Coins Einkommen pro Tag. Das billigste kostet 100 pro Item und bringt +100 pro Tag. Dazwischen sinken die Preise gleichmäßig.
- Jedes Item gibt es **nur einmal** auf dem Server (wie Straßen bei Monopoly). Im Menü sieht jeder, wem welches Item gehört.
- 1 Item eines Sets bringt 10 % des Einkommens, 2 Items 35 %, das komplette Set 100 %.
- Items sind **übertragbar:** im Set-Menü auf dein Item klicken, Spielernamen in den Chat schreiben, mit `ja` bestätigen.

---

## So bekommst du die fertige Plugin-Datei (`CoinShop.jar`)

### Weg A: Automatisch auf GitHub bauen lassen (nichts installieren)
1. Öffne dein Repository und lade den **gesamten Inhalt dieser ZIP** hoch. Bestehende Dateien werden dabei ersetzt. Der Ordner `.github/workflows/build.yml` muss erhalten bleiben (hast du schon).
2. Der Bau startet automatisch unter dem Reiter **Actions**.
3. Beim fertigen (grünen) Durchlauf unten bei **Artifacts** `CoinShop` herunterladen. Darin liegt die `CoinShop.jar`.
4. Bei einem roten Durchlauf: Schritt **Run ./gradlew build** öffnen und die Zeilen mit `error:` kopieren.

### Weg B: Auf dem eigenen PC bauen
1. Java 25 installieren (z. B. Temurin von <https://adoptium.net>).
2. In diesem Ordner: Windows `gradlew.bat build`, Mac/Linux `./gradlew build`.
3. Die Datei liegt danach in `build/libs/CoinShop.jar`.

## Installation / Update auf dem Server (Serverminer)

1. Server stoppen.
2. Alte `CoinShop.jar` im Ordner `plugins` durch die neue ersetzen.
3. **Wichtig beim Update:** Lösche im Ordner `plugins/CoinShop/` die Datei **`config.yml`**, damit die neue (mit Preisen, Glücksrad und Sets) erzeugt wird. Die Datenbank `coinshop.db` bleibt erhalten und damit alle Kontostände. Falls du Texte in `messages.yml` angepasst hast, sichere sie vorher. Fehlende Texte werden automatisch ergänzt.
4. Server starten. Es sollte `CoinShop wurde gestartet.` in der Konsole stehen.
5. Zum Testen: `/coinsadmin give DeinName 5000`, dann `/shop`.

## Wichtig: Noch nicht getestet

Der Code wurde geschrieben und auf Tippfehler geprüft, aber **nicht kompiliert und nicht auf einem echten Server getestet**. Beim Bau können noch Fehlermeldungen auftauchen (z. B. weil ein API-Name in Paper 26.3 anders heißt). Besonders neu sind Verzauberungen (Rüstung und Bücher) und die Animation im Glücksrad. Fehlermeldung kopieren, dann wird es behoben.

## Projektaufbau (zum Nachlesen)

```
src/main/resources/
  plugin.yml          Steckbrief: Name, Befehle, Rechte
  config.yml          Einstellungen (Preise, Glücksrad, Slots, Einkommen, 25 Sets)
  messages.yml        Alle Texte (deutsch, änderbar)
src/main/java/de/coinshop/
  CoinShopPlugin.java       Start/Stopp, verbindet alles
  Database.java             Speichert alles in einer SQLite-Datei (coinshop.db)
  Messages.java / ItemUtil.java / ChatPrompts.java   Hilfsklassen (Texte, Items, Chat-Eingaben)
  PlayerListener.java       Beitreten, Erfolge -> Coins, Chat-Eingaben
  CoinCommands.java         /coins /pay /baltop /coinsadmin
  ShopCommand.java          /shop
  ShopItemGenerator.java    Lost die 10 Server-Shop-Angebote aus (Items, Rüstung, Bücher)
  ServerShopManager.java    Täglicher Wechsel um 12:00 Uhr, Kauf im Server-Shop
  PlayerShopManager.java    Slots, Einstellen, Kaufen, Abbrechen, Ablaufen, Abholen
  WheelManager.java         Glücksrad-Logik (Einsatz, Jackpot, Trostpreise)
  InvestmentManager.java    Sets, Besitz, Übertragung, tägliches Einkommen
  gui/                      Alle Menüs (Haupt-, Server-Shop, Spieler-Shop, Meine Angebote,
                            Abholung, Glücksrad, Investitionen, Set)
```


## Update auf Version 3.0
Lösche auf dem Server einmalig `plugins/CoinShop/config.yml` und `messages.yml` (die Datenbank bleibt erhalten), damit die neuen Einstellungen (Börse, Erfolgsstufen) erzeugt werden.
