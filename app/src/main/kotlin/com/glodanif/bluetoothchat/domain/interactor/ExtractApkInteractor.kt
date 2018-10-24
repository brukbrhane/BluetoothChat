package com.glodanif.bluetoothchat.domain.interactor

import android.net.Uri
import com.glodanif.bluetoothchat.data.model.FileManager
import com.glodanif.bluetoothchat.domain.exception.FileException

class ExtractApkInteractor(private val fileManager: FileManager) : BaseInteractor<Unit, Uri>() {

    override suspend fun execute(input: Unit): Uri {

        val uri = fileManager.extractApkFile()
        if (uri != null) {
            return uri
        } else {
            throw FileException("Unable to extract .apk file")
        }
    }
}
