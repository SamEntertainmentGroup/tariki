# Carte hors ligne de l'Algérie (routes et noms de lieux)

Fabrique `algeria-map.mbtiles` : une carte vectorielle qui ne contient que les routes et les noms de lieux (villes, villages, hameaux), à partir du fichier OpenStreetMap de Geofabrik.

## Utilisation

1. Crée un dépôt GitHub et envoie-y les dossiers `.github`, `tools` et `tests` tels quels (le dossier `.github` est caché : sous Linux, Ctrl+H dans le gestionnaire de fichiers).
2. Onglet **Actions** → **Build Algeria offline map** → **Run workflow**.
3. Premier essai : zone `test-alger`. Le rapport s'affiche en bas de la page de l'exécution (Summary) et dans l'artefact `report.txt`.
4. Si tout va bien, relance avec `algerie-complete`.

## Contenu

- Couche `roads` : propriétés `class` (motorway, trunk, primary, secondary, tertiary, minor) et `ref` si elle existe. Les routes secondaires apparaissent à partir du zoom 9, les tertiaires 11, les petites rues 12.
- Couche `places` : propriétés `class` (city, town, village, hamlet, suburb), `name`, `name_ar`, `name_fr` quand ils existent dans OpenStreetMap. Zooms 4 à 14.

## Vérifications

Tests unitaires à chaque exécution, puis rapport sur le fichier produit (couches présentes, nombre et poids des tuiles par niveau de zoom). L'étape échoue si une couche manque ou si le fichier est vide.
