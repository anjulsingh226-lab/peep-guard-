GitHub pe upload karne ke steps:
1. github.com pe free account banao.
2. New repository banao (naam: PeepGuard, Public ya Private).
3. Is zip ko PC/phone pe Extract karo.
4. Repo me "uploading an existing file" pe click karo aur extract hue folder ke andar ki SAARI files/folders drag-drop karo
   (app folder, build.gradle.kts, settings.gradle.kts, gradle.properties). Commit changes dabao.
5. IMPORTANT: ".github" folder kabhi kabhi upload me skip ho jata hai. Repo me "Add file > Create new file" dabao,
   naam me ye likho:  .github/workflows/build.yml
   aur build.yml ka poora content paste karke Commit karo.
6. "Actions" tab kholo, "Build APK" chalne do (3-6 min). Green tick aane par us run pe click karo
   aur neeche "Artifacts" se PeepGuard-apk download karo. Zip kholo, andar app-debug.apk hogi.
7. APK phone me bhejo aur install karo ("Unknown sources" allow karna padega).
