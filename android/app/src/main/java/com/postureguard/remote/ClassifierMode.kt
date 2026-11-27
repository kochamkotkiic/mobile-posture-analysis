package com.postureguard.remote

/**
 * Where the good/bad classification runs (SKILL.md D3 / D8, team contract).
 * Stored in the settings; the pipeline uses it from Step 8 on (RemoteClassifier).
 * EDGE is used only after the optional 3-tier upgrade.
 */
enum class ClassifierMode { ON_DEVICE, CLOUD, EDGE }
