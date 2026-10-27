# Dtrad

Traduit les pages de manhwa déjà en anglais vers le français, directement par-dessus ton lecteur (Mihon, etc.).

- Une petite bulle « T » flotte sur l'écran (déplaçable).
- **Double-tap** sur la bulle : capture la page visible, détecte les textes (OCR), les traduit (EN → FR) et les affiche par-dessus les bulles d'origine, au fur et à mesure.
- **Tap simple** sur la bulle ou sur la page traduite : retire la traduction.

## Moteur de traduction

- **Principal : Tencent HY-MT1.5-1.8B, quantification 1,25 bit** (~440 Mo), 100 % sur l'appareil, via llama.cpp (noyau STQ1_0 de la PR #22836). Chaque bulle est traduite avec les bulles précédentes en contexte (modèle de prompt « contexte » de HY-MT) ; la mémoire de lecture se poursuit d'un écran à l'autre.
- **Secours : ML Kit** (rapide, sans contexte), utilisé tant que le modèle n'est pas prêt ou s'il échoue.
- Au premier lancement, le modèle est téléchargé (Wi-Fi conseillé). Aucune clé API.

## Installer

Télécharge `Dtrad.apk` depuis l'onglet **Releases** (version « latest »), installe-le, puis ouvre l'app et suis les 2 étapes.
Sur Android 13+, si l'affichage par-dessus est bloqué : Réglages → Applications → Dtrad → ⋮ → « Autoriser les paramètres restreints ».

## Compiler

Le workflow `.github/workflows/build.yml` récupère llama.cpp, installe le NDK 27 et compile l'APK (arm64 uniquement) à chaque push sur `main`.

## Limites connues

- Téléphones ARM 64 bits uniquement, mode portrait.
- La traduction avec HY-MT prend quelques secondes par bulle.
- OCR latin uniquement ; les polices très stylisées et les onomatopées sont moins bien lues.

## Moteur d'image (v2)

Détection du texte (DBNet), OCR, effacement par IA (AOT-GAN) et mise en page viennent de
[yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) (GPL-3.0, voir `NOTICE.md`).
La traduction est faite par HY-MT 1.8B 1,25 bit, sur le téléphone, avec les bulles précédentes en contexte.
Téléchargements au premier lancement : ~250 Mo (image) + ~440 Mo (HY-MT). Le projet est donc sous GPL-3.0.
Si le moteur d'image échoue, l'appli se rabat sur ML Kit (rectangles unis).
