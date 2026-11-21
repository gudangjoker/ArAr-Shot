package com.ararshot.app.model

enum class AnimationEngineType {
    DCT_NET,
    WHITEBOX_CARTOON
}

data class ModelFileSpec(
    val fileName: String,
    val url: String,
    val approxBytes: Long
)

/**
 * Curated catalog of non-anime open-source animation models (downloaded manually on demand).
 * - DCT-Net (Alibaba DAMO Academy, SIGGRAPH 2022) via mobile TFLite
 * - White-box Cartoonization (CVPR 2020) via dynamic-range TFLite
 */
enum class AnimationStyle(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val engineType: AnimationEngineType,
    val files: List<ModelFileSpec>
) {
    ANIMATION_3D(
        id = "dct_3d",
        displayName = "Animasi 3D",
        subtitle = "Karakter 3D bergaya film animasi",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/01/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/01/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    ARTSTYLE(
        id = "dct_artstyle",
        displayName = "Artstyle",
        subtitle = "Animasi 2.5D bergaya lukisan digital",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/03/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/03/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    HANDDRAWN(
        id = "dct_handdrawn",
        displayName = "Kartun 2D",
        subtitle = "Kartun gambar tangan dengan garis halus",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/05/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/05/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    ILLUSTRATION(
        id = "dct_illustration",
        displayName = "Ilustrasi",
        subtitle = "Ilustrasi cerita dengan warna lembut",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/06/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/06/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    DESIGN(
        id = "dct_design",
        displayName = "Desain",
        subtitle = "Animasi grafis modern dan bersih",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/04/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/04/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    SKETCH(
        id = "dct_sketch",
        displayName = "Sketsa",
        subtitle = "Sketsa pensil potret dengan arsiran rapi",
        engineType = AnimationEngineType.DCT_NET,
        files = listOf(
            ModelFileSpec(
                fileName = "model.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/07/model.tflite",
                approxBytes = 3_992_632L
            ),
            ModelFileSpec(
                fileName = "model_header.tflite",
                url = "https://raw.githubusercontent.com/ZTMIDGO/Anime/master/app/src/main/assets/style/07/model_header.tflite",
                approxBytes = 3_121_720L
            )
        )
    ),
    SCENE_CARTOON(
        id = "whitebox_scene",
        displayName = "Kartun Adegan",
        subtitle = "Kartun 2D untuk pemandangan, hewan & objek",
        engineType = AnimationEngineType.WHITEBOX_CARTOON,
        files = listOf(
            ModelFileSpec(
                fileName = "whitebox_cartoon_gan_dr.tflite",
                url = "https://raw.githubusercontent.com/margaretmz/Cartoonizer-with-TFLite/master/android/app/src/main/ml/whitebox_cartoon_gan_dr.tflite",
                approxBytes = 2_568_838L
            )
        )
    );

    val totalApproxBytes: Long
        get() = files.sumOf { it.approxBytes }

    val sizeLabel: String
        get() = String.format(java.util.Locale.US, "%.1f MB", totalApproxBytes / 1_000_000.0)
}

data class AnimationModelsUiState(
    val installedIds: Set<String> = emptySet(),
    val downloadingId: String? = null,
    val downloadProgress: Float = 0f,
    val totalInstalledBytes: Long = 0L
)
