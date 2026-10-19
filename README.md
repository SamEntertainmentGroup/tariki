# DZ Carte — carte hors ligne, GPS et alertes (Algérie)

Deux workflows GitHub Actions :

1. **Build Algeria offline map** : fabrique `algeria-map.mbtiles` (routes, noms de lieux, côte, limites du pays, des wilayas et des communes) et `places-index.json` (recherche de lieux), puis les publie dans la Release `map-latest` (zone `algerie-complete` seulement).
2. **Build Android APK** : télécharge la carte, l'index et les polices, puis compile l'APK de test (`android/`).

## Étapes

1. Envoie dans ton dépôt les dossiers `.github`, `tools`, `tests` et `android` (en remplaçant les fichiers existants). Le dossier `.github` est caché : sous Linux, Ctrl+H.
2. Définis l'adresse du serveur : Settings → Secrets and variables → Actions → onglet **Variables** → New repository variable `ALERTS_URL` = `https://ton-site/wp-admin/admin-ajax.php`.
3. Lance **Build Algeria offline map** : d'abord `test-alger` (contrôle du rapport), puis `algerie-complete` (publie la Release `map-latest`).
4. Lance **Build Android APK**, télécharge l'artefact `dz-carte-apk`, installe `app-debug.apk`.

## L'appli

- Carte vectorielle locale (zoom minimal 5) : routes, numéros de route, côte, limites du pays, des wilayas et des communes, et noms de lieux dès le zoom 4 (villes), 5 (villes moyennes), 6 (villages), 8 (hameaux) ; les lieux les plus peuplés passent en premier quand les noms se chevauchent.
- Position GPS, vitesse en km/h, distance et durée du trajet (appui long sur le compteur : remise à zéro).
- Bouton **GPS** : le suivi tourne en arrière-plan avec une notification (bouton Stop dedans), écran éteint. Bouton ☀ : garde l'écran allumé.
- **Alertes** : bouton « تبليغ » (route cassée, accident, danger), alertes affichées sur la carte (toucher une alerte : détail), double bip et voyant rouge à 200 m et 100 m, question « هل التبليغ صحيح؟ » après le passage.
- Un signalement fait sans réseau est mis en attente et envoyé dès le retour de la connexion ; les dernières alertes reçues restent disponibles hors ligne.
- Recherche 🔍 d'une ville, commune ou village (arabe ou français, sans accents).
- Boutons AR / FR (langue des noms), Cap / Nord (rotation), ◎ (recentrer).

## Serveur

L'appli utilise les mêmes actions `admin-ajax.php` que le site (`dztrafic_get_alerts`, `dztrafic_add_alert`, `dztrafic_confirm_alert` et leurs nonces) : les règles anti-spam, les durées de vie et la vérification de la route restent côté WordPress.

## Contenu technique

- `tools/` : préparation de la carte (`prepare_layers.py`), index de recherche (`build_search_index.py`), contrôle (`inspect_mbtiles.py`), polices (`fetch_glyphs.py`).
- `android/app/src/main/assets/style.json` : style de la carte.
- `android/app/src/main/java/com/dztrafic/carte/` : l'appli (service de suivi, alertes, recherche, écran principal).
- `tests/` : tests automatiques, lancés à chaque exécution.

© OpenStreetMap contributors (ODbL).


## Interface v0.3

- Modern green-and-orange map controls and a prominent report button.
- GPS speed is shown in a larger card; the small trip time/distance line was removed.
- Place labels appear from zoom level 12 to reduce clutter at overview zooms.
- Screen stays awake while Tariki is open.
- Report categories use a custom dialog with a separate confirmation step.
- The search control is temporarily hidden until route calculation is implemented; this version does not calculate itineraries yet.

The map-generation and APK workflows are unchanged in this interface update.
