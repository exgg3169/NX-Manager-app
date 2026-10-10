# NX Manager

Telefonda, root gerektirmeden YouTube APK'sını patch'leyen Android uygulaması.
Morphe/ReVanced'ın **fikri** incelenerek sıfırdan yazıldı; onların kodu kullanılmadı.

## Şu an yapabildikleri

| Patch | Ne yapar |
|---|---|
| **GmsCore (microG) desteği** | Paket adını `app.nx.android.youtube` yapar (orijinal YouTube ile yan yana durur), Google Play Hizmetleri yerine microG'yi kullanır, microG yoksa uyarı gösterir. |
| **Arka planda oynatma** | Uygulamadan çıkınca / ekran kapanınca video çalmaya devam eder; Shorts ve çocuk videoları dahil. YouTube ayarlarındaki "Arka planda oynatma" seçeneği de açılır. |
| **Video reklamlarını engelle** | Sunucuya istemciyi "Android Automotive" olarak tanıtır (reklam gelmez) ve oynatıcının reklam yükleme / reklam katmanı metotlarını devre dışı bırakır. |

Denenen YouTube sürümü: **21.16.256** (APKMirror'daki "APK", `nodpi`, bundle olmayan sürüm).

## Test adımları

1. **microG'yi kurun:** [MicroG-RE](https://github.com/MorpheApp/MicroG-RE/releases/latest) (paket adı `app.revanced.android.gms`).
   microG ayarlarından Google hesabınızı ekleyin ve pil optimizasyonunu kapatın.
2. **NX Manager**'ı kurun (GitHub Actions → *Build* → `nx-manager-apk` artifact'ı).
3. YouTube 21.16.256 APK'sını indirin (bundle değil, tek APK).
4. NX Manager → *APK seç* → patch'leri işaretleyin → *Patch'le* (yaklaşık 1 dakika) → *Kur*.

## Nasıl çalışıyor

```
APK ──► AndroidManifest.xml (ARSCLib)  ─┐
    ──► classes*.dex (dexlib2)          ├─► patch'ler ─► ZIP yeniden yazma ─► imzalama (apksig, v2+v3, 16 KB hizalama)
    ──► nx/extension.dex (kendi Java kodumuz) ─┘
```

- **Metot bulma:** YouTube kodu karartılmış (`a.b.c()`), isimler her sürümde değişir. Metotlar
  içlerindeki sabit metinler, dönüş tipi ve parametrelerle bulunur (`MethodFilter`).
- **GmsCore:**
  - Manifest'te paket adı, uygulamanın kendi izinleri ve provider authority'leri yeni ada,
    Google Play Hizmetleri izinleri/action'ları/authority'leri microG adlarına (`app.revanced.android.*`) çevrilir.
  - microG'nin uygulamayı orijinal YouTube gibi Google'a tanıtması için iki meta-data eklenir:
    `SPOOFED_PACKAGE_NAME` ve `SPOOFED_PACKAGE_SIGNATURE`.
  - Koddaki aynı metinler değiştirilir. Play Hizmetleri imza kontrolü, Cast ve cihaz uyumluluk
    kontrolü devre dışı bırakılır; bildirim kaydı orijinal paket adını gönderir.
- **Arka planda oynatma:** Oynatıcının "arka planda devam edebilir mi?" kontrolleri (normal video,
  Shorts, çocuk videosu, ayar ekranı) her zaman "evet" döner; ilgili sunucu bayrakları (feature flag)
  sabitlenir. Bu metotlarda metin olmadığı için komut dizisi, sayısal sabit ve kaynak kimliği ile bulunur.
- **Eklenti (extension):** `extension/` altındaki Java kodu derlenip DEX olarak APK'ya eklenir
  (ör. microG kurulu mu kontrolü).

## Proje yapısı

| Modül | İçerik |
|---|---|
| `patcher/` | Patch motoru (saf JVM/Kotlin) ve YouTube patch'leri (`nx.patches.youtube`) |
| `extension/` | Patch'lenen uygulamanın içinde çalışan Java kodu |
| `app/` | Android uygulaması (Jetpack Compose) |
| `cli/` | Masaüstü test aracı: `patch`, ve kod incelemek için `grep` / `dump` |

### Masaüstünde deneme

```bash
./gradlew :cli:installDist
cli/build/install/cli/bin/cli patch youtube.apk youtube-nx.apk
cli/build/install/cli/bin/cli grep youtube.apk 'Error fetching CastContext'   # metni kullanan metotlar
cli/build/install/cli/bin/cli dump youtube.apk 'Lrgl;'                        # sınıfı smali olarak göster
```

## Bilinen sınırlar

- **Video akışı:** Yeniden imzalanmış uygulamalarda YouTube oynatmayı bir süre sonra durdurabilir
  (Morphe bunu ayrı bir "Spoof video streams" patch'iyle çözüyor). Henüz eklenmedi.
- Akıştaki ve ana sayfadaki reklam kartları (Litho bileşenleri) henüz gizlenmiyor; şimdilik yalnızca video reklamları.
- Release APK şimdilik debug anahtarıyla imzalanıyor; farklı makinede derlenen sürümü kurmak için önce eskisini kaldırmak gerekebilir.
