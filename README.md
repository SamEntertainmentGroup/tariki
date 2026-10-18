# DZ Carte — carte hors ligne de l'Algérie

Deux workflows GitHub Actions :

1. **Build Algeria offline map** : fabrique `algeria-map.mbtiles` (routes et noms de lieux) à partir du fichier OpenStreetMap de Geofabrik, et le publie dans la Release `map-latest` (zone `algerie-complete` seulement).
2. **Build Android APK** : télécharge cette carte et les polices, puis compile l'APK de test (`android/`).

## Étapes

1. Envoie dans ton dépôt les dossiers `.github`, `tools`, `tests` et `android` (en remplaçant les fichiers existants). Le dossier `.github` est caché : sous Linux, Ctrl+H.
2. Publie la carte dans la Release `map-latest`, soit en relançant **Build Algeria offline map** avec `algerie-complete`, soit à la main : Releases → Draft a new release → tag `map-latest` → dépose `algeria-map.mbtiles` → Publish.
3. Lance **Build Android APK**. Le résumé de l'exécution montre le contrôle de la carte et celui des polices (dont l'arabe).
4. Télécharge l'artefact `dz-carte-apk`, décompresse-le, installe `app-debug.apk` sur le téléphone (autoriser les sources inconnues).
5. Teste en mode avion.

## L'appli

- Carte vectorielle locale : routes par importance, numéros de route, noms de lieux.
- Position GPS, avec la carte qui tourne selon la direction (bouton Cap / Nord).
- Bouton AR / FR pour la langue des noms, bouton ◎ pour recentrer.
- Au premier lancement, la carte est copiée dans le stockage de l'appli (quelques secondes).

## Contenu technique

- `tools/prepare_layers.py`, `tools/inspect_mbtiles.py` : préparation et contrôle de la carte.
- `tools/fetch_glyphs.py` : télécharge les polices (Noto Sans Regular, Protomaps basemaps-assets) et vérifie la couverture de l'arabe.
- `android/app/src/main/assets/style.json` : style de la carte.
- `tests/` : tests automatiques, lancés à chaque exécution.

© OpenStreetMap contributors (ODbL).
