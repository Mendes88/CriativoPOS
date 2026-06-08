# Criativo POS — Criar APK

## ⚠️ IMPORTANTE — Caminho de extracção

Extrai o ZIP para uma pasta **sem acentos e sem espaços**.

✅ Correcto:  C:\CriativoPOS\
❌ Errado:    C:\Users\João\Downloads\CriativoPOS\
❌ Errado:    C:\Users\mende\Downloads\Criativo POS\

---

## Passos

1. Extrai o ZIP para `C:\CriativoPOS\`
2. Duplo clique em `construir-apk.bat`
3. No Android Studio aguarda o Gradle sync
4. **Build → Generate Signed Bundle/APK → APK → Next**
5. Cria keystore (primeira vez) ou usa a existente
6. Selecciona **release → Finish**
7. APK em: `app\release\app-release.apk`

---

## Versões testadas

| Software | Versão |
|---|---|
| Android Studio | Electric Eel / Flamingo / Giraffe / Hedgehog |
| Gradle | 7.5 |
| AGP | 7.4.2 |
| Android mínimo | 5.0 (API 21) |

