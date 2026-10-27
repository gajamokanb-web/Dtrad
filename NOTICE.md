# Crédits et licences

Dtrad est distribué sous **GPL-3.0** (voir `LICENSE`).

Les modules `inference-core/` et `engine/` proviennent de
[joyeli/yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) (GPL-3.0), lui-même un portage de
[zyddnys/manga-image-translator](https://github.com/zyddnys/manga-image-translator) (GPL-3.0) :
détection de texte (DBNet), OCR (48 px CTC), effacement (AOT-GAN) et mise en page, le tout via NCNN.

Modifications apportées par Dtrad : retour à la ligne par mots dans `engine/.../Renderer.kt`
(texte à espaces) ; l'ensemble est branché sur la traduction locale HY-MT au lieu d'un LLM en ligne.
Les poids des modèles sont téléchargés depuis les publications du dépôt yakuyomi-engine.

La traduction utilise [Tencent HY-MT1.5-1.8B](https://huggingface.co/tencent/HY-MT1.5-1.8B) (quantification 1,25 bit)
via [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT).
