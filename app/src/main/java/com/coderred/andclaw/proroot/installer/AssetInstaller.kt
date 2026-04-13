package com.coderred.andclaw.proroot.installer

interface AssetInstaller<T> {
    suspend fun install(
        spec: T,
        onProgress: (entries: Int) -> Unit = {},
    )
}

