# Samsung MU App-IDs aus SDB

Quelle:

```powershell
C:\tizen-studio\tools\sdb.exe connect 192.168.0.193:26101
C:\tizen-studio\tools\sdb.exe shell 0 vd_applist
C:\tizen-studio\tools\sdb.exe shell 0 applist
```

TV:

- Modell: `UE65MU6275`
- SDB-Gerät: `192.168.0.193:26101`

## App-Mapping

| App | Samsung-App-ID | Tizen-App-ID | Paket / Startname |
| --- | --- | --- | --- |
| YouTube | `111299001912` | `9Ur5IzDKqV.TizenYouTube` | `9Ur5IzDKqV`, `YouTube` |
| Netflix | `11101200001` | `RN1MCdNq8t.Netflix` | `RN1MCdNq8t`, `org.tizen.netflix-app`, `org.tizen.netflixlowmem`, `Netflix` |
| Prime Video | `3201512006785` | `evKhCgZelL.AmazonIgnitionLauncher2` | `evKhCgZelL`, `AmazonInstantVideo` |
| Disney+ | `3201901017640` | `MCmYXNxgcu.DisneyPlus` | `MCmYXNxgcu`, `DisneyPlus` |
| Sky X | `3201812017464` | `J0zX4W0EmB.SkyX` | `J0zX4W0EmB` |
| Joyn | `3202106024013` | `2200MKoe7n.ZAPPNVOLLTVFREIGESTREAMT` | `2200MKoe7n` |
| Plex | `3201512006963` | `kIciSQlYEM.plex` | `kIciSQlYEM`, `Plex` |
| Crunchyroll | `3202302030097` | `OGLLvqej7u.CrunchyrollWebApp` | `OGLLvqej7u` |
| simpliTV | `3202009021699` | `LibFXRqQAD.simplitv` | `LibFXRqQAD` |
| Internet | `org.tizen.browser` | `org.tizen.browser` | `org.tizen.browser` |

Hinweis: Der MU-TV akzeptiert je nach App/Firmware nicht jeden Startweg gleich. Die Android-App probiert deshalb HTTP `/api/v2/applications/{id}` und danach WebSocket `ed.apps.launch` mit `DEEP_LINK` und `NATIVE_LAUNCH`.
