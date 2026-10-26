# Dtrad

Traduit les pages de manhwa déjà en anglais vers le français, directement par-dessus ton lecteur.

- Une petite bulle « T » flotte sur l'écran (déplaçable).
- **Double-tap** sur la bulle : capture la page visible, détecte les textes (OCR), les traduit (EN → FR) et les affiche par-dessus les bulles d'origine.
- **Tap simple** sur la bulle ou sur la page traduite : retire la traduction.
- Tout se fait sur l'appareil (ML Kit) : pas de clé API, pas de compte. Internet n'est utilisé qu'au premier lancement pour télécharger le modèle de traduction.

## Installer

Télécharge `Dtrad.apk` depuis l'onglet **Releases** (version « latest »), installe-le (autorise les sources inconnues), puis ouvre l'app et suis les 2 étapes.

## Compiler

Le workflow `.github/workflows/build.yml` compile l'APK à chaque push sur `main`.
En local : `gradle :app:assembleDebug` (JDK 17, Gradle 8.9, Android SDK 34).

## Limites connues

- Pensé pour le mode portrait.
- OCR latin uniquement (texte anglais) ; les polices très stylisées et les onomatopées sont moins bien lues.
- Chaque page est traduite seule : pas de contexte entre les pages.
